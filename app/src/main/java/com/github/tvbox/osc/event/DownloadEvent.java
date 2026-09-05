package com.github.tvbox.osc.event;

/**
 * 应用内缓存(离线下载)事件
 */
public class DownloadEvent {
    /** 列表级变化(入队/删除/暂停/继续),UI 直接重查数据库 */
    public static final int TYPE_TASKS_CHANGED = 0;
    /** 单集进度/状态更新 */
    public static final int TYPE_PROGRESS = 1;
    /** 单集缓存完成 */
    public static final int TYPE_DONE = 2;
    /** 单集分片下载完成,正在合并/转封装 MP4(状态仍是 DOWNLOADING,不落库) */
    public static final int TYPE_MERGING = 3;

    public int type;
    public int episodeId;
    public int status;
    public long downloadedBytes;
    public long totalBytes;
    /** HLS 分片进度(非 HLS 时为 -1) */
    public int segmentsDone = -1;
    public int segmentsTotal = -1;
    /** 下载速度(字节/秒) */
    public long speedBytes;
    public String errMsg;
    /** 用于完成通知 */
    public String title;

    public DownloadEvent(int type) {
        this.type = type;
    }
}
