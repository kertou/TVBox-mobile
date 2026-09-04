package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.ToastUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseVbFragment;
import com.github.tvbox.osc.cache.DownloadEpisode;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.databinding.FragmentCacheBinding;
import com.github.tvbox.osc.download.DownloadStorage;
import com.github.tvbox.osc.download.DownloadTaskManager;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.ui.activity.DownloadActivity;
import com.github.tvbox.osc.ui.adapter.CacheGridAdapter;
import com.github.tvbox.osc.ui.adapter.DownloadAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "我的缓存" tab,网格模式:每部剧一张大海报(与主页同款外观),点卡片进详情(DownloadActivity 详情模式),
 * 长按删剧,右上清空。原 DownloadActivity 网格模式逻辑原样迁入,异步加载/进程级大小缓存/两段渲染保留。
 */
public class CacheFragment extends BaseVbFragment<FragmentCacheBinding> {

    private CacheGridAdapter mGridAdapter;
    private long lastReloadTime = 0;
    private boolean eventBusRegistered = false;
    /** 下载中集数的实时进度(来自 EventBus 事件,数据库不落盘,避免频繁写库)。
     *  后台加载线程算角标时也会读它,因此用并发容器 */
    private final Map<Integer, DownloadAdapter.Live> liveProgress = new ConcurrentHashMap<>();
    /** 大小缓存(进程级):目录大小与"已占用"总量的磁盘遍历只随结构变化重算,
     *  页面重进、网格(CacheFragment)与详情(DownloadActivity)之间共享,避免每次进页都全量 stat 上万个分片 */
    private static final Map<String, Long> groupSizeCache = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> episodeSizeCache = new ConcurrentHashMap<>();
    private static volatile boolean sizeDirty = true;
    /** 数据加载后台化:Room 查询 + 目录大小统计(全树递归)都不占主线程 */
    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean loadRunning = new AtomicBoolean(false);
    private final AtomicBoolean loadPending = new AtomicBoolean(false);
    /** "已占用"总量:进页先显示上次值,后台重算且间隔 ≥2 秒 */
    private static volatile long cachedTotalBytes = -1;
    private static volatile long lastTotalCalc = 0;
    private long lastAppliedTotal = -1;

    private void runOnUiThread(Runnable action) {
        if (mActivity != null) {
            mActivity.runOnUiThread(action);
        }
    }

    @Override
    protected void init() {
        if (!eventBusRegistered) {
            eventBusRegistered = true;
            EventBus.getDefault().register(this);
        }
        RecyclerView rv = mBinding.rvList;
        rv.setLayoutManager(new GridLayoutManager(requireActivity(), 3));
        // 总量在后台计算,先给占位文案;重置去重标记,保证视图重建后(值未变)也会回填真实总量
        mBinding.tvStorage.setText("已占用: …");
        lastAppliedTotal = -1;
        mGridAdapter = new CacheGridAdapter();
        rv.setAdapter(mGridAdapter);
        mGridAdapter.setOnItemClickListener((adapter, view, position) -> {
            CacheGridAdapter.Item item = mGridAdapter.getData().get(position);
            FastClickCheckUtil.check(view);
            Intent intent = new Intent(requireActivity(), DownloadActivity.class);
            intent.putExtra(DownloadActivity.EXTRA_GROUP_KEY, item.groupKey);
            startActivity(intent);
        });
        mGridAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            CacheGridAdapter.Item item = mGridAdapter.getData().get(position);
            FastClickCheckUtil.check(view);
            confirmDeleteSeries(item.groupKey, item.name);
            return true;
        });
        mBinding.btnClear.setOnClickListener(v -> confirmClearAll());
        loadData();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        long now = System.currentTimeMillis();
        if (event.type == DownloadEvent.TYPE_DONE) {
            liveProgress.remove(event.episodeId);
            markSizesDirty();
            loadData();
            return;
        }
        if (event.type == DownloadEvent.TYPE_PROGRESS) {
            DownloadAdapter.Live live = liveProgress.get(event.episodeId);
            if (live == null) {
                live = new DownloadAdapter.Live();
                liveProgress.put(event.episodeId, live);
            }
            live.downloadedBytes = event.downloadedBytes;
            live.totalBytes = event.totalBytes;
            live.speedBytes = event.speedBytes;
            live.segmentsDone = event.segmentsDone;
            live.segmentsTotal = event.segmentsTotal;
        }
        if (now - lastReloadTime < 400) {
            return;
        }
        lastReloadTime = now;
        loadData();
    }

    /** 数据加载入口:实际工作投到后台单线程串行处理,计算期间又来的刷新记一个待办 */
    private void loadData() {
        loadPending.set(true);
        if (loadExecutor.isShutdown()) {
            return;
        }
        if (!loadRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            loadExecutor.execute(() -> {
                try {
                    while (loadPending.getAndSet(false)) {
                        List<DownloadEpisode> all = RoomDataManger.getAllDownloadEpisodes();
                        applyGrid(all);
                        refreshTotalUsed();
                    }
                } catch (Throwable th) {
                    th.printStackTrace();
                } finally {
                    loadRunning.set(false);
                }
                // 最后一轮计算期间又有新事件:补跑一轮
                if (loadPending.get() && !loadExecutor.isShutdown()) {
                    loadData();
                }
            });
        } catch (Throwable th) {
            loadRunning.set(false);
            th.printStackTrace();
        }
    }

    /** 结构性变化(完成/删除/清空)后大小缓存全部失效,"已占用"总量强制重算 */
    public static void markSizesDirty() {
        sizeDirty = true;
        groupSizeCache.clear();
        episodeSizeCache.clear();
        lastTotalCalc = 0;
    }

    public static Long cachedGroupSize(String groupKey) {
        return groupSizeCache.get(groupKey);
    }

    public static void cacheGroupSize(String groupKey, long size) {
        groupSizeCache.put(groupKey, size);
    }

    public static Long cachedEpisodeSize(int episodeId) {
        return episodeSizeCache.get(episodeId);
    }

    public static void cacheEpisodeSize(int episodeId, long size) {
        episodeSizeCache.put(episodeId, size);
    }

    /** 取走"需要全量重算"标记(结构性变化后置位) */
    public static boolean consumeSizeDirty() {
        boolean dirty = sizeDirty;
        if (dirty) {
            sizeDirty = false;
        }
        return dirty;
    }

    /** 网格模式:按剧聚合,每部一张海报卡。两段渲染:
     *  先用缓存里的大小立即出卡片(未算出的先空着),后台补算完再刷一次,
     *  保证首次进入大缓存时页面渲染不必等全量目录遍历 */
    private void applyGrid(List<DownloadEpisode> all) {
        LinkedHashMap<String, List<DownloadEpisode>> groups = new LinkedHashMap<>();
        for (DownloadEpisode ep : all) {
            List<DownloadEpisode> list = groups.get(ep.groupKey());
            if (list == null) {
                list = new ArrayList<>();
                groups.put(ep.groupKey(), list);
            }
            list.add(ep);
        }
        // 只保留仍处于 下载/解析 中的实时进度:退避(等待中)/暂停/失败的集清掉,
        // 避免列表行滞留旧速度旧字节(限流退避的集可能几分钟后才重试)
        Set<Integer> activeIds = new HashSet<>();
        for (List<DownloadEpisode> list : groups.values()) {
            for (DownloadEpisode ep : list) {
                if (ep.status == DownloadEpisode.STATUS_DOWNLOADING
                        || ep.status == DownloadEpisode.STATUS_RESOLVING) {
                    activeIds.add(ep.getId());
                }
            }
        }
        liveProgress.keySet().retainAll(activeIds);

        boolean needsWalk = consumeSizeDirty();
        List<CacheGridAdapter.Item> items = new ArrayList<>();
        Set<String> pendingGroups = new HashSet<>();
        for (Map.Entry<String, List<DownloadEpisode>> entry : groups.entrySet()) {
            List<DownloadEpisode> eps = entry.getValue();
            CacheGridAdapter.Item item = new CacheGridAdapter.Item();
            item.groupKey = entry.getKey();
            item.name = displayNameOf(eps.get(0));
            item.pic = eps.get(0).vodPic;
            int done = 0, waiting = 0, resolving = 0, downloading = 0, paused = 0, failed = 0;
            for (DownloadEpisode ep : eps) {
                switch (ep.status) {
                    case DownloadEpisode.STATUS_WAITING:
                        waiting++;
                        break;
                    case DownloadEpisode.STATUS_RESOLVING:
                        resolving++;
                        break;
                    case DownloadEpisode.STATUS_DOWNLOADING:
                        downloading++;
                        break;
                    case DownloadEpisode.STATUS_PAUSED:
                        paused++;
                        break;
                    case DownloadEpisode.STATUS_FAILED:
                        failed++;
                        break;
                    case DownloadEpisode.STATUS_DONE:
                    default:
                        done++;
                        break;
                }
            }
            Long cached = cachedGroupSize(entry.getKey());
            if (cached != null) {
                item.sizeText = cached > 0 ? DownloadStorage.formatSize(cached) : null;
            } else if (!needsWalk) {
                // 缓存未热(如刚入队的新剧):只补算这一个组
                item.sizeText = computeGroupSize(entry.getKey(), eps);
            } else {
                pendingGroups.add(entry.getKey());
            }
            item.note = CacheGridAdapter.buildNote(done, eps.size(), waiting, resolving,
                    downloading, paused, failed);
            items.add(item);
        }
        runOnUiThread(() -> {
            if (mGridAdapter != null) {
                mGridAdapter.setNewData(items);
                mBinding.tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
        if (!pendingGroups.isEmpty()) {
            for (Map.Entry<String, List<DownloadEpisode>> entry : groups.entrySet()) {
                if (pendingGroups.contains(entry.getKey())) {
                    computeGroupSize(entry.getKey(), entry.getValue());
                }
            }
            for (CacheGridAdapter.Item item : items) {
                Long size = cachedGroupSize(item.groupKey);
                item.sizeText = size != null && size > 0 ? DownloadStorage.formatSize(size) : null;
            }
            runOnUiThread(() -> {
                if (mGridAdapter != null) {
                    mGridAdapter.setNewData(items);
                }
            });
        }
    }

    /** 补算单个分组的大小(真实磁盘占用,含下载中/暂停的半成品)并写入缓存,返回格式化文本 */
    private String computeGroupSize(String groupKey, List<DownloadEpisode> eps) {
        long size = 0;
        for (DownloadEpisode ep : eps) {
            size += episodeSize(ep);
        }
        cacheGroupSize(groupKey, size);
        return size > 0 ? DownloadStorage.formatSize(size) : null;
    }

    /** 显示名:剧名为空时(部分源入队时没带剧名)回退用线路名,避免卡片标题空白 */
    public static String displayNameOf(DownloadEpisode ep) {
        if (ep.vodName != null && !ep.vodName.trim().isEmpty()) return ep.vodName;
        return ep.flag == null ? "" : ep.flag;
    }

    /** 单集实际占用:下载/解析中的集直接用调度器上报的实时字节(免磁盘遍历),
     *  其余状态统计真实磁盘并按集缓存,仅结构变化后重算 */
    private long episodeSize(DownloadEpisode ep) {
        boolean active = ep.status == DownloadEpisode.STATUS_DOWNLOADING
                || ep.status == DownloadEpisode.STATUS_RESOLVING;
        if (!active) {
            Long cached = cachedEpisodeSize(ep.getId());
            if (cached != null) {
                return cached;
            }
        }
        long size;
        DownloadAdapter.Live live = active ? liveProgress.get(ep.getId()) : null;
        if (live != null && live.downloadedBytes > 0) {
            size = live.downloadedBytes;
        } else {
            size = diskEpisodeSize(ep);
        }
        cacheEpisodeSize(ep.getId(), size);
        return size;
    }

    /** 单集磁盘占用:分片模式(m3u8)统计整个集目录,直链模式统计文件本身(详情模式共用) */
    public static long diskEpisodeSize(DownloadEpisode ep) {
        if (ep.localFilePath != null) {
            File f = new File(ep.localFilePath);
            if (f.exists()) {
                if (f.getName().endsWith(".m3u8") && ep.localDir != null) {
                    return Math.max(f.length(), DownloadStorage.dirSize(new File(ep.localDir)));
                }
                return f.length();
            }
        }
        if (ep.localDir != null) {
            File d = new File(ep.localDir);
            if (d.exists()) return DownloadStorage.dirSize(d);
        }
        return ep.totalBytes;
    }

    /** "已占用"总量:对整个 offline 树递归求和,代价与缓存总量成正比。
     *  进页先用上次值,后台重算且间隔 ≥2 秒 */
    private void refreshTotalUsed() {
        long now = System.currentTimeMillis();
        if (cachedTotalBytes >= 0 && now - lastTotalCalc < 2000) {
            applyTotalUsed(cachedTotalBytes);
            return;
        }
        lastTotalCalc = now;
        cachedTotalBytes = DownloadStorage.totalUsedBytes();
        applyTotalUsed(cachedTotalBytes);
    }

    private void applyTotalUsed(long total) {
        if (total == lastAppliedTotal) {
            return;
        }
        lastAppliedTotal = total;
        runOnUiThread(() -> {
            if (mBinding != null) {
                mBinding.tvStorage.setText("已占用: " + DownloadStorage.formatSize(total));
            }
        });
    }

    private void confirmDeleteSeries(String groupKey, String seriesName) {
        new XPopup.Builder(requireActivity())
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("删除缓存", "确定删除《" + (seriesName == null ? "" : seriesName) + "》的全部缓存文件吗?", "取消", "确定", () -> {
                    // 递归删除整个剧集目录是大量磁盘操作,放后台执行
                    loadExecutor.execute(() -> {
                        try {
                            List<DownloadEpisode> eps = RoomDataManger.getAllDownloadEpisodes();
                            for (DownloadEpisode ep : eps) {
                                if (!groupKey.equals(ep.groupKey())) continue;
                                DownloadTaskManager.get().cancel(ep.getId());
                                if (ep.localDir != null) {
                                    DownloadStorage.deleteRecursive(new File(ep.localDir));
                                }
                                RoomDataManger.deleteDownloadEpisode(ep);
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                        markSizesDirty();
                        loadData();
                    });
                }, null, false).show();
    }

    private void confirmClearAll() {
        new XPopup.Builder(requireActivity())
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("清空缓存", "确定删除全部缓存文件吗?", "取消", "确定", () -> {
                    // 不能在这里 pauseAll():全局暂停置位后没有界面入口可以恢复,
                    // 之后新入队的任务会永远停在"等待中";逐集 cancel 已足以打断在途下载
                    loadExecutor.execute(() -> {
                        try {
                            List<DownloadEpisode> all = RoomDataManger.getAllDownloadEpisodes();
                            for (DownloadEpisode ep : all) {
                                DownloadTaskManager.get().cancel(ep.getId());
                                if (ep.localDir != null) {
                                    DownloadStorage.deleteRecursive(new File(ep.localDir));
                                }
                                RoomDataManger.deleteDownloadEpisode(ep);
                            }
                            DownloadStorage.deleteRecursive(DownloadStorage.baseDir());
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                        markSizesDirty();
                        loadData();
                    });
                }, null, false).show();
    }

    @Override
    public void onDestroy() {
        if (eventBusRegistered) {
            eventBusRegistered = false;
            EventBus.getDefault().unregister(this);
        }
        loadExecutor.shutdownNow();
        super.onDestroy();
    }
}
