package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.cache.DownloadEpisode;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.databinding.ActivityDownloadBinding;
import com.github.tvbox.osc.download.DownloadStorage;
import com.github.tvbox.osc.download.DownloadTaskManager;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.ui.adapter.CacheGridAdapter;
import com.github.tvbox.osc.ui.adapter.DownloadAdapter;
import com.github.tvbox.osc.ui.adapter.DownloadSection;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
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
 * 我的缓存,双模式:
 * - 网格模式(无 EXTRA_GROUP_KEY):每部剧一张大海报(与主页同款外观),点卡片进详情,长按删剧
 * - 详情模式(带 EXTRA_GROUP_KEY):该剧的集数列表,支持离线播放、暂停/继续/重试/删除
 */
public class DownloadActivity extends BaseVbActivity<ActivityDownloadBinding> {

    public static final String EXTRA_GROUP_KEY = "groupKey";

    private DownloadAdapter mAdapter;
    private CacheGridAdapter mGridAdapter;
    /** null = 网格模式;非 null = 详情模式,只显示该剧 */
    private String detailGroupKey;
    private volatile String detailSeriesName;
    private long lastReloadTime = 0;
    /** 下载中集数的实时进度(来自 EventBus 事件,数据库不落盘,避免频繁写库)。
     *  后台加载线程算角标时也会读它,因此用并发容器 */
    private final Map<Integer, DownloadAdapter.Live> liveProgress = new ConcurrentHashMap<>();
    /** 大小缓存(进程级):目录大小与"已占用"总量的磁盘遍历只随结构变化重算,
     *  页面重进、网格与详情两个模式之间共享,避免每次进页都全量 stat 上万个分片 */
    private static final Map<String, Long> groupSizeCache = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> episodeSizeCache = new ConcurrentHashMap<>();
    private static volatile boolean sizeDirty = true;
    /** 数据加载后台化:Room 查询 + 目录大小统计(全树递归)都不占主线程,
     *  这是"点进我的缓存卡一下"与"下载期间缓存页卡顿"的根因 */
    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean loadRunning = new AtomicBoolean(false);
    private final AtomicBoolean loadPending = new AtomicBoolean(false);
    /** "已占用"总量:进页先显示上次值,后台重算且间隔 ≥2 秒 */
    private static volatile long cachedTotalBytes = -1;
    private static volatile long lastTotalCalc = 0;
    private static volatile long lastAppliedTotal = -1;
    private volatile boolean titleApplied = false;

    @Override
    protected void init() {
        detailGroupKey = getIntent().getStringExtra(EXTRA_GROUP_KEY);
        initView();
        loadData();
    }

    private void initView() {
        RecyclerView rv = mBinding.rvList;
        mBinding.titleBar.getLeftView().setOnClickListener(v -> finish());
        if (detailGroupKey == null) {
            rv.setLayoutManager(new GridLayoutManager(this, 3));
            // 总量在后台计算,先给占位文案
            mBinding.tvStorage.setText("已占用: …");
            mGridAdapter = new CacheGridAdapter();
            rv.setAdapter(mGridAdapter);
            mGridAdapter.setOnItemClickListener((adapter, view, position) -> {
                CacheGridAdapter.Item item = mGridAdapter.getData().get(position);
                FastClickCheckUtil.check(view);
                Intent intent = new Intent(this, DownloadActivity.class);
                intent.putExtra(EXTRA_GROUP_KEY, item.groupKey);
                startActivity(intent);
            });
            mGridAdapter.setOnItemLongClickListener((adapter, view, position) -> {
                CacheGridAdapter.Item item = mGridAdapter.getData().get(position);
                FastClickCheckUtil.check(view);
                confirmDeleteSeries(item.groupKey, item.name);
                return true;
            });
            mBinding.titleBar.getRightView().setOnClickListener(v -> confirmClearAll());
        } else {
            rv.setLayoutManager(new LinearLayoutManager(this));
            mAdapter = new DownloadAdapter();
            mAdapter.setLiveProgress(liveProgress);
            rv.setAdapter(mAdapter);
            mAdapter.setOnItemClickListener(mItemClickListener);
            mAdapter.setOnItemChildClickListener(mEpisodeChildClick);
            mBinding.titleBar.getRightView().setOnClickListener(v -> confirmDeleteSeries(detailGroupKey, detailSeriesName));
        }
    }

    private final BaseQuickAdapter.OnItemClickListener mItemClickListener = (adapter, view, position) -> {
        DownloadSection section = mAdapter.getData().get(position);
        if (!section.isHeader && section.t.status == DownloadEpisode.STATUS_DONE) {
            FastClickCheckUtil.check(view);
            playEpisode(section.t.groupKey(), section.t.getId());
        }
    };

    private final BaseQuickAdapter.OnItemChildClickListener mEpisodeChildClick = (adapter, view, position) -> {
        DownloadSection section = mAdapter.getData().get(position);
        FastClickCheckUtil.check(view);
        if (section.isHeader) {
            if (view.getId() == R.id.btnPlayAll) {
                playFirstOfGroup(section.groupKey);
            } else if (view.getId() == R.id.btnDeleteSeries) {
                confirmDeleteSeries(section.groupKey, seriesNameOf(section));
            }
        } else if (view.getId() == R.id.btnAction) {
            handleEpisodeAction(section.t);
        }
    };

    private String seriesNameOf(DownloadSection section) {
        return section.seriesName == null ? "" : section.seriesName;
    }

    private void playFirstOfGroup(String groupKey) {
        List<DownloadEpisode> eps = RoomDataManger.getAllDownloadEpisodes();
        for (DownloadEpisode ep : eps) {
            if (groupKey.equals(ep.groupKey()) && ep.status == DownloadEpisode.STATUS_DONE) {
                playEpisode(groupKey, ep.getId());
                return;
            }
        }
        ToastUtils.showShort("还没有缓存完成的剧集");
    }

    private void confirmDeleteSeries(String groupKey, String seriesName) {
        new XPopup.Builder(this)
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
                        if (detailGroupKey != null) {
                            // 详情页删的是自己,回到网格
                            runOnUiThread(this::finish);
                        } else {
                            loadData();
                        }
                    });
                }, null, false).show();
    }

    private void handleEpisodeAction(DownloadEpisode ep) {
        switch (ep.status) {
            case DownloadEpisode.STATUS_DOWNLOADING:
            case DownloadEpisode.STATUS_RESOLVING:
            case DownloadEpisode.STATUS_WAITING:
                DownloadTaskManager.get().pause(ep.getId());
                ToastUtils.showShort("已请求暂停");
                break;
            case DownloadEpisode.STATUS_PAUSED:
                DownloadTaskManager.get().resume(ep.getId());
                break;
            case DownloadEpisode.STATUS_FAILED:
                DownloadTaskManager.get().resume(ep.getId());
                break;
            case DownloadEpisode.STATUS_DONE:
            default:
                confirmDelete(ep);
                break;
        }
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
                        if (detailGroupKey != null) {
                            applyDetail(all);
                        } else {
                            applyGrid(all);
                            refreshTotalUsed();
                        }
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
    private void markSizesDirty() {
        sizeDirty = true;
        groupSizeCache.clear();
        episodeSizeCache.clear();
        lastTotalCalc = 0;
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

        boolean needsWalk = sizeDirty;
        if (needsWalk) {
            sizeDirty = false;
        }
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
            Long cached = groupSizeCache.get(entry.getKey());
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
            mGridAdapter.setNewData(items);
            mBinding.tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        });
        if (!pendingGroups.isEmpty()) {
            for (Map.Entry<String, List<DownloadEpisode>> entry : groups.entrySet()) {
                if (pendingGroups.contains(entry.getKey())) {
                    computeGroupSize(entry.getKey(), entry.getValue());
                }
            }
            for (CacheGridAdapter.Item item : items) {
                Long size = groupSizeCache.get(item.groupKey);
                item.sizeText = size != null && size > 0 ? DownloadStorage.formatSize(size) : null;
            }
            runOnUiThread(() -> mGridAdapter.setNewData(items));
        }
    }

    /** 补算单个分组的大小(真实磁盘占用,含下载中/暂停的半成品)并写入缓存,返回格式化文本 */
    private String computeGroupSize(String groupKey, List<DownloadEpisode> eps) {
        long size = 0;
        for (DownloadEpisode ep : eps) {
            size += episodeSize(ep);
        }
        groupSizeCache.put(groupKey, size);
        return size > 0 ? DownloadStorage.formatSize(size) : null;
    }

    /** 详情模式:只显示该剧的分组头 + 集数列表(原列表 UI) */
    private void applyDetail(List<DownloadEpisode> all) {
        List<DownloadEpisode> eps = new ArrayList<>();
        for (DownloadEpisode ep : all) {
            if (detailGroupKey.equals(ep.groupKey())) {
                eps.add(ep);
            }
        }
        if (eps.isEmpty()) {
            // 该剧已无任何缓存条目(被删除),直接回到网格
            runOnUiThread(this::finish);
            return;
        }
        Collections.sort(eps, (a, b) -> Integer.compare(a.episodeIndex, b.episodeIndex));
        if (detailSeriesName == null) {
            detailSeriesName = displayNameOf(eps.get(0));
        }
        DownloadEpisode first = eps.get(0);
        DownloadSection header = new DownloadSection(true);
        header.seriesName = displayNameOf(first) + " · " + first.flag;
        header.pic = first.vodPic;
        header.groupKey = first.groupKey();
        int done = 0;
        long size = 0;
        for (DownloadEpisode ep : eps) {
            if (ep.status == DownloadEpisode.STATUS_DONE) {
                done++;
            }
            //头部大小按真实磁盘占用(含未完成集),完成数只算已完成
            size += episodeSize(ep);
        }
        header.doneCount = done;
        header.totalCount = eps.size();
        header.sizeBytes = size;
        List<DownloadSection> sections = new ArrayList<>();
        sections.add(header);
        for (DownloadEpisode ep : eps) {
            sections.add(new DownloadSection(ep));
        }
        runOnUiThread(() -> {
            if (!titleApplied) {
                titleApplied = true;
                mBinding.titleBar.setTitle(detailSeriesName);
                mBinding.titleBar.setRightTitle("删除");
                mBinding.tvStorage.setVisibility(View.GONE);
            }
            mAdapter.setNewData(sections);
            mBinding.tvEmpty.setVisibility(View.GONE);
        });
    }

    /** 显示名:剧名为空时(部分源入队时没带剧名)回退用线路名,避免卡片标题空白 */
    private String displayNameOf(DownloadEpisode ep) {
        if (ep.vodName != null && !ep.vodName.trim().isEmpty()) return ep.vodName;
        return ep.flag == null ? "" : ep.flag;
    }

    /** 单集实际占用:下载/解析中的集直接用调度器上报的实时字节(免磁盘遍历),
     *  其余状态统计真实磁盘并按集缓存,仅结构变化后重算 */
    private long episodeSize(DownloadEpisode ep) {
        boolean active = ep.status == DownloadEpisode.STATUS_DOWNLOADING
                || ep.status == DownloadEpisode.STATUS_RESOLVING;
        if (!active) {
            Long cached = episodeSizeCache.get(ep.getId());
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
        episodeSizeCache.put(ep.getId(), size);
        return size;
    }

    /** 单集磁盘占用:分片模式(m3u8)统计整个集目录,直链模式统计文件本身 */
    private long diskEpisodeSize(DownloadEpisode ep) {
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
     *  只在网格模式显示;进页先用上次值,后台重算且间隔 ≥2 秒 */
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
        runOnUiThread(() -> mBinding.tvStorage.setText("已占用: " + DownloadStorage.formatSize(total)));
    }

    /** 从同一剧集组中已完成的集数构建离线播放列表(复用本地播放器) */
    private void playEpisode(String groupKey, int episodeId) {
        List<DownloadEpisode> eps = RoomDataManger.getAllDownloadEpisodes();
        List<DownloadEpisode> doneList = new ArrayList<>();
        DownloadEpisode clicked = null;
        for (DownloadEpisode ep : eps) {
            if (!groupKey.equals(ep.groupKey())) continue;
            if (ep.getId() == episodeId) clicked = ep;
            if (ep.status == DownloadEpisode.STATUS_DONE && ep.localFilePath != null
                    && new File(ep.localFilePath).exists()) {
                doneList.add(ep);
            }
        }
        if (clicked == null || doneList.isEmpty()) {
            ToastUtils.showShort("缓存文件不存在");
            loadData();
            return;
        }
        // 按集号正序构建播放列表,保证"上一集/下一集"和自动连播沿集数推进
        Collections.sort(doneList, (a, b) -> Integer.compare(a.episodeIndex, b.episodeIndex));
        List<VideoInfo> videoList = new ArrayList<>();
        int position = 0;
        for (int i = 0; i < doneList.size(); i++) {
            DownloadEpisode ep = doneList.get(i);
            if (ep.getId() == clicked.getId()) {
                position = i;
            }
            VideoInfo vi = new VideoInfo();
            vi.setPath(ep.localFilePath);
            vi.setDisplayName(ep.displayTitle());
            vi.setSize(ep.totalBytes);
            videoList.add(vi);
        }
        Intent intent = new Intent(this, LocalPlayActivity.class);
        intent.putExtra("videoList", GsonUtils.toJson(videoList));
        intent.putExtra("position", position);
        startActivity(intent);
    }

    private void confirmDelete(DownloadEpisode ep) {
        new XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("删除缓存", "确定删除 " + ep.displayTitle() + " 的缓存文件吗?", "取消", "确定", () -> {
                    loadExecutor.execute(() -> {
                        try {
                            DownloadTaskManager.get().cancel(ep.getId());
                            DownloadEpisode task = RoomDataManger.getDownloadEpisode(ep.getId());
                            if (task != null) {
                                if (task.localDir != null) {
                                    DownloadStorage.deleteRecursive(new File(task.localDir));
                                    DownloadStorage.cleanEmptyParents(new File(task.localDir));
                                }
                                RoomDataManger.deleteDownloadEpisode(task);
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
        new XPopup.Builder(this)
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
    protected void onDestroy() {
        loadExecutor.shutdownNow();
        super.onDestroy();
    }
}
