package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseSectionQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.cache.DownloadEpisode;
import com.github.tvbox.osc.download.DownloadStorage;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.Map;

/**
 * 我的缓存列表:剧集分组(头) + 集数条目
 */
public class DownloadAdapter extends BaseSectionQuickAdapter<DownloadSection, BaseViewHolder> {

    /** 下载中的实时进度(EventBus 事件驱动,不走数据库)。
     *  缓存页后台加载线程也会读(算角标时取实时字节),字段用 volatile 保证可见性 */
    public static class Live {
        public volatile long downloadedBytes;
        public volatile long totalBytes;
        public volatile long speedBytes;
        public volatile int segmentsDone = -1;
        public volatile int segmentsTotal = -1;
        /** 分片已下完,正在合并/转封装 MP4(收到新进度或完成时清掉) */
        public volatile boolean merging;
    }

    private Map<Integer, Live> liveMap;

    public void setLiveProgress(Map<Integer, Live> map) {
        this.liveMap = map;
    }

    public DownloadAdapter() {
        super(R.layout.item_cache_episode, R.layout.item_cache_series, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, DownloadSection section) {
        DownloadEpisode ep = section.t;
        TextView tvName = helper.getView(R.id.tvEpName);
        TextView tvState = helper.getView(R.id.tvEpState);
        TextView btnAction = helper.getView(R.id.btnAction);
        LinearProgressIndicator pb = helper.getView(R.id.pbProgress);
        tvName.setText(ep.episodeName);
        btnAction.setVisibility(View.VISIBLE);
        int progress = 0;
        switch (ep.status) {
            case DownloadEpisode.STATUS_WAITING:
                // 自动重试退避期间会带着 errMsg 回到等待状态,把重试进度显示出来
                tvState.setText(TextUtils.isEmpty(ep.errMsg) ? "等待中…" : ep.errMsg);
                btnAction.setText("删除");
                pb.setVisibility(View.GONE);
                break;
            case DownloadEpisode.STATUS_RESOLVING:
                tvState.setText("正在解析播放地址…");
                btnAction.setText("删除");
                pb.setVisibility(View.VISIBLE);
                pb.setIndeterminate(true);
                break;
            case DownloadEpisode.STATUS_DOWNLOADING: {
                pb.setVisibility(View.VISIBLE);
                Live live = liveMap != null ? liveMap.get(ep.getId()) : null;
                if (live != null && live.merging) {
                    // 合并/转封装期间:不确定进度条,无速度概念,隐藏操作按钮
                    tvState.setText("正在合成视频…");
                    pb.setIndeterminate(true);
                    btnAction.setVisibility(View.GONE);
                    break;
                }
                long done = live != null && live.downloadedBytes > 0 ? live.downloadedBytes : ep.downloadedBytes;
                long speed = live != null ? live.speedBytes : 0;
                String speedText = speed > 0 ? " · " + DownloadStorage.formatSize(speed) + "/s" : "";
                if (ep.totalBytes > 0) {
                    progress = (int) Math.min(100, done * 100 / ep.totalBytes);
                    tvState.setText(DownloadStorage.formatSize(done) + "/"
                            + DownloadStorage.formatSize(ep.totalBytes) + speedText);
                    pb.setIndeterminate(false);
                } else if (live != null && live.segmentsTotal > 0) {
                    // HLS 分片模式:显示分片进度代替无限动画
                    progress = (int) Math.min(100, live.segmentsDone * 100 / live.segmentsTotal);
                    tvState.setText("分片 " + live.segmentsDone + "/" + live.segmentsTotal
                            + " · 已下载 " + DownloadStorage.formatSize(done) + speedText);
                    pb.setIndeterminate(false);
                } else {
                    tvState.setText(done > 0
                            ? "已下载 " + DownloadStorage.formatSize(done)
                            : "正在下载…");
                    pb.setIndeterminate(true);
                }
                btnAction.setText("暂停");
                pb.setProgress(progress);
                break;
            }
            case DownloadEpisode.STATUS_PAUSED:
                pb.setVisibility(View.VISIBLE);
                pb.setIndeterminate(false);
                progress = ep.totalBytes > 0 ? (int) Math.min(100, ep.downloadedBytes * 100 / ep.totalBytes) : 0;
                pb.setProgress(progress);
                String pausedText = ep.totalBytes > 0 ? "已暂停 " + progress + "%" : "已暂停";
                if (ep.totalBytes <= 0 && ep.downloadedBytes > 0) {
                    pausedText += " · 已下载 " + DownloadStorage.formatSize(ep.downloadedBytes);
                }
                tvState.setText(pausedText + (TextUtils.isEmpty(ep.errMsg) ? "" : " · " + ep.errMsg));
                btnAction.setText("继续");
                break;
            case DownloadEpisode.STATUS_FAILED:
                pb.setVisibility(View.GONE);
                tvState.setText(TextUtils.isEmpty(ep.errMsg) ? "缓存失败" : ep.errMsg);
                btnAction.setText("重试");
                break;
            case DownloadEpisode.STATUS_DONE:
            default:
                pb.setVisibility(View.GONE);
                tvState.setText("已缓存 · " + DownloadStorage.formatSize(ep.totalBytes)
                        + (ep.localFilePath != null && ep.localFilePath.endsWith(".m3u8") ? " (分片模式)" : ""));
                btnAction.setText("删除");
                break;
        }
        helper.addOnClickListener(R.id.btnAction);
    }

    @Override
    protected void convertHead(BaseViewHolder helper, DownloadSection section) {
        TextView tvName = helper.getView(R.id.tvSeriesName);
        TextView tvState = helper.getView(R.id.tvSeriesState);
        ImageView ivPic = helper.getView(R.id.ivPic);
        TextView btnRetryFailed = helper.getView(R.id.btnRetryFailed);
        tvName.setText(section.seriesName);
        tvState.setText("已缓存 " + section.doneCount + "/" + section.totalCount + " 集 · "
                + DownloadStorage.formatSize(section.sizeBytes));
        // 统计本组失败集,有失败才显示一键重试按钮(episode section 的分组 key 要经 t.groupKey() 取)
        int failed = 0;
        for (DownloadSection s : getData()) {
            if (!s.isHeader && s.t != null
                    && section.groupKey.equals(s.t.groupKey())
                    && s.t.status == DownloadEpisode.STATUS_FAILED) {
                failed++;
            }
        }
        if (failed > 0) {
            btnRetryFailed.setVisibility(View.VISIBLE);
            btnRetryFailed.setText("重试失败(" + failed + ")");
        } else {
            btnRetryFailed.setVisibility(View.GONE);
        }
        if (!TextUtils.isEmpty(section.pic)) {
            Glide.with(ivPic.getContext()).load(section.pic).placeholder(R.color.bg_gray).into(ivPic);
        }
        helper.addOnClickListener(R.id.btnPlayAll);
        helper.addOnClickListener(R.id.btnDeleteSeries);
        helper.addOnClickListener(R.id.btnRetryFailed);
    }
}
