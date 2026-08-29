package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.view.View;

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
import com.github.tvbox.osc.ui.adapter.DownloadAdapter;
import com.github.tvbox.osc.ui.adapter.DownloadSection;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.Utils;
import com.lxj.xpopup.XPopup;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 我的缓存:按剧集分组展示应用内缓存,支持离线播放、暂停/继续/重试/删除。
 */
public class DownloadActivity extends BaseVbActivity<ActivityDownloadBinding> {

    private DownloadAdapter mAdapter;
    private long lastReloadTime = 0;

    @Override
    protected void init() {
        initView();
        loadData();
    }

    private void initView() {
        RecyclerView rv = mBinding.rvList;
        rv.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new DownloadAdapter();
        rv.setAdapter(mAdapter);

        mAdapter.setOnItemClickListener(mItemClickListener);
        mAdapter.setOnItemChildClickListener(mEpisodeChildClick);
        mBinding.titleBar.getLeftView().setOnClickListener(v -> finish());
        mBinding.titleBar.getRightView().setOnClickListener(v -> confirmClearAll());
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
                confirmDeleteSeries(section);
            }
        } else if (view.getId() == R.id.btnAction) {
            handleEpisodeAction(section.t);
        }
    };

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

    private void confirmDeleteSeries(DownloadSection section) {
        new XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("删除缓存", "确定删除《" + section.seriesName + "》的全部缓存文件吗?", () -> {
                    List<DownloadEpisode> eps = RoomDataManger.getAllDownloadEpisodes();
                    for (DownloadEpisode ep : eps) {
                        if (!section.groupKey.equals(ep.groupKey())) continue;
                        DownloadTaskManager.get().cancel(ep.getId());
                        if (ep.localDir != null) {
                            DownloadStorage.deleteRecursive(new File(ep.localDir));
                        }
                        RoomDataManger.deleteDownloadEpisode(ep);
                    }
                    loadData();
                }).show();
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
            loadData();
            return;
        }
        if (now - lastReloadTime < 400) {
            return;
        }
        lastReloadTime = now;
        loadData();
    }

    private void loadData() {
        List<DownloadEpisode> all = RoomDataManger.getAllDownloadEpisodes();
        LinkedHashMap<String, List<DownloadEpisode>> groups = new LinkedHashMap<>();
        for (DownloadEpisode ep : all) {
            List<DownloadEpisode> list = groups.get(ep.groupKey());
            if (list == null) {
                list = new ArrayList<>();
                groups.put(ep.groupKey(), list);
            }
            list.add(ep);
        }
        List<DownloadSection> sections = new ArrayList<>();
        for (Map.Entry<String, List<DownloadEpisode>> entry : groups.entrySet()) {
            List<DownloadEpisode> eps = entry.getValue();
            DownloadEpisode first = eps.get(0);
            DownloadSection header = new DownloadSection(true);
            header.seriesName = first.vodName == null ? "" : first.vodName + " · " + first.flag;
            header.pic = first.vodPic;
            header.groupKey = first.groupKey();
            int done = 0;
            long size = 0;
            for (DownloadEpisode ep : eps) {
                if (ep.status == DownloadEpisode.STATUS_DONE) {
                    done++;
                    if (ep.localFilePath != null) {
                        File f = new File(ep.localFilePath);
                        size += f.exists() ? f.length() : ep.totalBytes;
                    } else {
                        size += ep.totalBytes;
                    }
                }
            }
            header.doneCount = done;
            header.totalCount = eps.size();
            header.sizeBytes = size;
            sections.add(header);
            for (DownloadEpisode ep : eps) {
                sections.add(new DownloadSection(ep));
            }
        }
        mAdapter.setNewData(sections);
        mBinding.tvStorage.setText("已占用: " + DownloadStorage.formatSize(DownloadStorage.totalUsedBytes()));
        mBinding.tvEmpty.setVisibility(sections.isEmpty() ? View.VISIBLE : View.GONE);
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
                .asConfirm("删除缓存", "确定删除 " + ep.displayTitle() + " 的缓存文件吗?", () -> {
                    DownloadTaskManager.get().cancel(ep.getId());
                    DownloadEpisode task = RoomDataManger.getDownloadEpisode(ep.getId());
                    if (task != null) {
                        if (task.localDir != null) {
                            DownloadStorage.deleteRecursive(new File(task.localDir));
                            DownloadStorage.cleanEmptyParents(new File(task.localDir));
                        }
                        RoomDataManger.deleteDownloadEpisode(task);
                    }
                    loadData();
                }).show();
    }

    private void confirmClearAll() {
        new XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("清空缓存", "确定删除全部缓存文件吗?", () -> {
                    DownloadTaskManager.get().pauseAll();
                    List<DownloadEpisode> all = RoomDataManger.getAllDownloadEpisodes();
                    for (DownloadEpisode ep : all) {
                        DownloadTaskManager.get().cancel(ep.getId());
                        if (ep.localDir != null) {
                            DownloadStorage.deleteRecursive(new File(ep.localDir));
                        }
                        RoomDataManger.deleteDownloadEpisode(ep);
                    }
                    DownloadStorage.deleteRecursive(DownloadStorage.baseDir());
                    loadData();
                }).show();
    }
}
