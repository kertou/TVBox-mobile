package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.cache.DownloadEpisode;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.databinding.ActivityDownloadBinding;
import com.github.tvbox.osc.download.DownloadStorage;
import com.github.tvbox.osc.download.DownloadTaskManager;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.ui.adapter.DownloadAdapter;
import com.github.tvbox.osc.ui.adapter.DownloadSection;
import com.github.tvbox.osc.ui.fragment.CacheFragment;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 我的缓存详情页(带 EXTRA_GROUP_KEY 进入):某部剧的集数列表,支持离线播放、暂停/继续/重试/删除。
 * 网格模式(缓存总览)已迁入底部"我的缓存"tab(CacheFragment);不带 groupKey 进入时转跳缓存 tab。
 */
public class DownloadActivity extends BaseVbActivity<ActivityDownloadBinding> {

    public static final String EXTRA_GROUP_KEY = "groupKey";

    private DownloadAdapter mAdapter;
    private volatile String detailGroupKey;
    private volatile String detailSeriesName;
    private long lastReloadTime = 0;
    private volatile boolean titleApplied = false;
    /** 下载中集数的实时进度(来自 EventBus 事件,数据库不落盘,避免频繁写库) */
    private final Map<Integer, DownloadAdapter.Live> liveProgress = new ConcurrentHashMap<>();
    /** 数据加载后台化:Room 查询 + 目录大小统计都不占主线程 */
    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean loadRunning = new AtomicBoolean(false);
    private final AtomicBoolean loadPending = new AtomicBoolean(false);

    @Override
    protected void init() {
        detailGroupKey = getIntent().getStringExtra(EXTRA_GROUP_KEY);
        if (detailGroupKey == null) {
            // 网格模式已迁入底部"我的缓存"tab,裸进入时转跳过去
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_TAB, 2);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
            return;
        }
        initView();
        loadData();
    }

    private void initView() {
        RecyclerView rv = mBinding.rvList;
        mBinding.titleBar.setNavigationOnClickListener(v -> finish());
        rv.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new DownloadAdapter();
        mAdapter.setLiveProgress(liveProgress);
        rv.setAdapter(mAdapter);
        mAdapter.setOnItemClickListener(mItemClickListener);
        mAdapter.setOnItemChildClickListener(mEpisodeChildClick);
        mBinding.rightView.setOnClickListener(v -> confirmDeleteSeries(detailGroupKey, detailSeriesName));
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
            } else if (view.getId() == R.id.btnRetryFailed) {
                int n = DownloadTaskManager.get().retryAllFailed();
                if (n > 0) {
                    ToastUtils.showShort("已重新排队 " + n + " 个失败集");
                }
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
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除缓存")
                .setMessage("确定删除《" + (seriesName == null ? "" : seriesName) + "》的全部缓存文件吗?")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
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
                        CacheFragment.markSizesDirty();
                        if (detailGroupKey != null) {
                            // 详情页删的是自己,回到网格
                            runOnUiThread(this::finish);
                        } else {
                            loadData();
                        }
                    });
                                })
                .show();
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
            CacheFragment.markSizesDirty();
            loadData();
            return;
        }
        if (event.type == DownloadEvent.TYPE_MERGING) {
            // 分片下完进入合并:列表行显示"正在合成视频…"(状态仍 DOWNLOADING)
            DownloadAdapter.Live live = liveProgress.get(event.episodeId);
            if (live == null) {
                live = new DownloadAdapter.Live();
                liveProgress.put(event.episodeId, live);
            }
            live.merging = true;
            loadData();
            return;
        }
        if (event.type == DownloadEvent.TYPE_PROGRESS) {
            DownloadAdapter.Live live = liveProgress.get(event.episodeId);
            if (live == null) {
                live = new DownloadAdapter.Live();
                liveProgress.put(event.episodeId, live);
            }
            live.merging = false;
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
                        applyDetail(all);
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

    /** 详情模式:只显示该剧的分组头 + 集数列表 */
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
            detailSeriesName = CacheFragment.displayNameOf(eps.get(0));
        }
        DownloadEpisode first = eps.get(0);
        DownloadSection header = new DownloadSection(true);
        header.seriesName = CacheFragment.displayNameOf(first) + " · " + first.flag;
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
                mBinding.tvStorage.setVisibility(View.GONE);
            }
            mAdapter.setNewData(sections);
            mBinding.tvEmpty.setVisibility(View.GONE);
        });
    }

    /** 单集实际占用:下载/解析中的集直接用调度器上报的实时字节(免磁盘遍历),
     *  其余状态统计真实磁盘并按集缓存,仅结构变化后重算(缓存与网格 tab 共享) */
    private long episodeSize(DownloadEpisode ep) {
        boolean active = ep.status == DownloadEpisode.STATUS_DOWNLOADING
                || ep.status == DownloadEpisode.STATUS_RESOLVING;
        if (!active) {
            Long cached = CacheFragment.cachedEpisodeSize(ep.getId());
            if (cached != null) {
                return cached;
            }
        }
        long size;
        DownloadAdapter.Live live = active ? liveProgress.get(ep.getId()) : null;
        if (live != null && live.downloadedBytes > 0) {
            size = live.downloadedBytes;
        } else {
            size = CacheFragment.diskEpisodeSize(ep);
        }
        CacheFragment.cacheEpisodeSize(ep.getId(), size);
        return size;
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
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除缓存")
                .setMessage("确定删除 " + ep.displayTitle() + " 的缓存文件吗?")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
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
                        CacheFragment.markSizesDirty();
                        loadData();
                    });
                                })
                .show();
    }

    
    protected void onDestroy() {
        loadExecutor.shutdownNow();
        super.onDestroy();
    }
}
