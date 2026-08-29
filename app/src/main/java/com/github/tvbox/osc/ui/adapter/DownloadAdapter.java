package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseSectionQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.cache.DownloadEpisode;
import com.github.tvbox.osc.download.DownloadStorage;

import java.util.ArrayList;

/**
 * 我的缓存列表:剧集分组(头) + 集数条目
 */
public class DownloadAdapter extends BaseSectionQuickAdapter<DownloadSection, BaseViewHolder> {

    public DownloadAdapter() {
        super(R.layout.item_cache_episode, R.layout.item_cache_series, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, DownloadSection section) {
        DownloadEpisode ep = section.t;
        TextView tvName = helper.getView(R.id.tvEpName);
        TextView tvState = helper.getView(R.id.tvEpState);
        TextView btnAction = helper.getView(R.id.btnAction);
        ProgressBar pb = helper.getView(R.id.pbProgress);
        tvName.setText(ep.episodeName);
        int progress = 0;
        switch (ep.status) {
            case DownloadEpisode.STATUS_WAITING:
                tvState.setText("等待中…");
                btnAction.setText("删除");
                pb.setVisibility(View.GONE);
                break;
            case DownloadEpisode.STATUS_RESOLVING:
                tvState.setText("正在解析播放地址…");
                btnAction.setText("删除");
                pb.setVisibility(View.VISIBLE);
                pb.setIndeterminate(true);
                break;
            case DownloadEpisode.STATUS_DOWNLOADING:
                pb.setVisibility(View.VISIBLE);
                if (ep.totalBytes > 0) {
                    progress = (int) Math.min(100, ep.downloadedBytes * 100 / ep.totalBytes);
                    tvState.setText(DownloadStorage.formatSize(ep.downloadedBytes) + "/"
                            + DownloadStorage.formatSize(ep.totalBytes));
                    pb.setIndeterminate(false);
                } else {
                    tvState.setText(ep.downloadedBytes > 0
                            ? "已下载 " + DownloadStorage.formatSize(ep.downloadedBytes)
                            : "正在下载…");
                    pb.setIndeterminate(true);
                }
                btnAction.setText("暂停");
                pb.setProgress(progress);
                break;
            case DownloadEpisode.STATUS_PAUSED:
                pb.setVisibility(View.VISIBLE);
                pb.setIndeterminate(false);
                progress = ep.totalBytes > 0 ? (int) Math.min(100, ep.downloadedBytes * 100 / ep.totalBytes) : 0;
                pb.setProgress(progress);
                tvState.setText("已暂停 " + progress + "%" + (TextUtils.isEmpty(ep.errMsg) ? "" : " · " + ep.errMsg));
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
        tvName.setText(section.seriesName);
        tvState.setText("已缓存 " + section.doneCount + "/" + section.totalCount + " 集 · "
                + DownloadStorage.formatSize(section.sizeBytes));
        if (!TextUtils.isEmpty(section.pic)) {
            Glide.with(ivPic.getContext()).load(section.pic).placeholder(R.color.bg_gray).into(ivPic);
        }
        helper.addOnClickListener(R.id.btnPlayAll);
        helper.addOnClickListener(R.id.btnDeleteSeries);
    }
}
