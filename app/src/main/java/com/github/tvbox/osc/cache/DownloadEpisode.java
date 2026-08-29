package com.github.tvbox.osc.cache;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 应用内缓存的某一集(bilibili式离线缓存)
 */
@Entity(tableName = "downloadEpisode")
public class DownloadEpisode implements Serializable {
    /** 任务状态 */
    public static final int STATUS_WAITING = 0;
    public static final int STATUS_RESOLVING = 1;
    public static final int STATUS_DOWNLOADING = 2;
    public static final int STATUS_PAUSED = 3;
    public static final int STATUS_DONE = 4;
    public static final int STATUS_FAILED = 5;
    /** 媒体类型 */
    public static final int TYPE_HLS = 0;
    public static final int TYPE_PROGRESSIVE = 1;

    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "sourceKey")
    public String sourceKey;
    @ColumnInfo(name = "vodId")
    public String vodId;
    @ColumnInfo(name = "vodName")
    public String vodName;
    @ColumnInfo(name = "vodPic")
    public String vodPic;
    /** 线路 */
    @ColumnInfo(name = "flag")
    public String flag;
    @ColumnInfo(name = "episodeName")
    public String episodeName;
    /** 集数下标(线路内的顺序) */
    @ColumnInfo(name = "episodeIndex")
    public int episodeIndex;
    /** 详情接口给出的原始地址 */
    @ColumnInfo(name = "rawUrl")
    public String rawUrl;
    /** 解析后的最终下载地址 */
    @ColumnInfo(name = "resolvedUrl")
    public String resolvedUrl;
    /** 下载请求头(JSON) */
    @ColumnInfo(name = "headersJson")
    public String headersJson;
    @ColumnInfo(name = "mediaType")
    public int mediaType;
    /** 本地缓存目录 */
    @ColumnInfo(name = "localDir")
    public String localDir;
    /** 最终可播放文件(本地绝对路径) */
    @ColumnInfo(name = "localFilePath")
    public String localFilePath;
    @ColumnInfo(name = "totalBytes")
    public long totalBytes;
    @ColumnInfo(name = "downloadedBytes")
    public long downloadedBytes;
    @ColumnInfo(name = "status")
    public int status;
    @ColumnInfo(name = "errMsg")
    public String errMsg;
    @ColumnInfo(name = "createTime")
    public long createTime;
    @ColumnInfo(name = "updateTime")
    public long updateTime;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String groupKey() {
        return sourceKey + "|" + vodId + "|" + flag;
    }

    public String displayTitle() {
        return (vodName == null ? "" : vodName) + " " + (episodeName == null ? "" : episodeName);
    }
}
