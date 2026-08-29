package com.github.tvbox.osc.ui.adapter;

import com.chad.library.adapter.base.entity.SectionEntity;
import com.github.tvbox.osc.cache.DownloadEpisode;

/**
 * 我的缓存页列表项:剧集分组头 + 集数
 */
public class DownloadSection extends SectionEntity<DownloadEpisode> {
    public String seriesName;
    public String pic;
    public int doneCount;
    public int totalCount;
    public long sizeBytes;
    public String groupKey;

    public DownloadSection(boolean isHeader) {
        super(isHeader, null);
    }

    public DownloadSection(DownloadEpisode episode) {
        super(episode);
    }
}
