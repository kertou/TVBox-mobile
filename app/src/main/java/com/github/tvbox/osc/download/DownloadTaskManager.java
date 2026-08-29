package com.github.tvbox.osc.download;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.text.TextUtils;

import com.github.tvbox.osc.cache.DownloadEpisode;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.service.DownloadService;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;
import com.github.tvbox.osc.base.App;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用内缓存(离线下载)总调度:
 * - 队列: 同时处理 1 集,集内 HLS 分片并发下载
 * - 状态机: 等待→解析中→下载中→(合并)→完成 / 暂停 / 失败
 * - 支持暂停/继续/取消/重试,断点续传精确到分片
 * - 进度通过 EventBus 广播,前台服务展示通知
 */
public class DownloadTaskManager {

    private static DownloadTaskManager instance;

    private final ExecutorService scheduler = Executors.newSingleThreadExecutor();
    private final AtomicBoolean looping = new AtomicBoolean(false);
    /** 请求暂停的集数id */
    private final Map<Integer, Boolean> pauseIds = new ConcurrentHashMap<>();
    /** 请求取消的集数id */
    private final Map<Integer, Boolean> cancelIds = new ConcurrentHashMap<>();
    private volatile boolean pausedAll = false;
    /** 当前正在下载的集数id,用于速度统计 */
    private final Map<Integer, Long> lastBytes = new HashMap<>();
    private final Map<Integer, Long> lastTime = new HashMap<>();

    public static DownloadTaskManager get() {
        if (instance == null) {
            synchronized (DownloadTaskManager.class) {
                if (instance == null) {
                    instance = new DownloadTaskManager();
                }
            }
        }
        return instance;
    }

    private DownloadTaskManager() {
    }

    /** App 启动时把上次异常退出的"下载中"任务重置为暂停 */
    public void recoverOnStart() {
        try {
            List<DownloadEpisode> actives = RoomDataManger.getAllDownloadEpisodes();
            for (DownloadEpisode t : actives) {
                if (t.status == DownloadEpisode.STATUS_DOWNLOADING || t.status == DownloadEpisode.STATUS_RESOLVING) {
                    t.status = DownloadEpisode.STATUS_PAUSED;
                    t.updateTime = System.currentTimeMillis();
                    RoomDataManger.updateDownloadEpisode(t);
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public void enqueue(List<DownloadEpisode> tasks) {
        long now = System.currentTimeMillis();
        for (DownloadEpisode t : tasks) {
            pauseIds.remove(t.getId());
            cancelIds.remove(t.getId());
            t.status = DownloadEpisode.STATUS_WAITING;
            if (t.createTime <= 0) t.createTime = now;
            t.updateTime = now;
            RoomDataManger.insertDownloadEpisode(t);
        }
        postChanged();
        DownloadService.start();
        kick();
    }

    public void pause(int episodeId) {
        pauseIds.put(episodeId, true);
    }

    public void resume(int episodeId) {
        pauseIds.remove(episodeId);
        DownloadEpisode t = RoomDataManger.getDownloadEpisode(episodeId);
        if (t != null && (t.status == DownloadEpisode.STATUS_PAUSED || t.status == DownloadEpisode.STATUS_FAILED)) {
            t.status = DownloadEpisode.STATUS_WAITING;
            t.errMsg = null;
            t.updateTime = System.currentTimeMillis();
            RoomDataManger.updateDownloadEpisode(t);
            postChanged();
            DownloadService.start();
            kick();
        }
    }

    public void cancel(int episodeId) {
        cancelIds.put(episodeId, true);
        pauseIds.remove(episodeId);
        DownloadEpisode t = RoomDataManger.getDownloadEpisode(episodeId);
        if (t != null && t.status != DownloadEpisode.STATUS_DOWNLOADING && t.status != DownloadEpisode.STATUS_RESOLVING) {
            removeTask(t);
            postChanged();
        }
    }

    public void pauseAll() {
        pausedAll = true;
    }

    public void resumeAll() {
        pausedAll = false;
        List<DownloadEpisode> all = RoomDataManger.getAllDownloadEpisodes();
        long now = System.currentTimeMillis();
        boolean hasWaiting = false;
        for (DownloadEpisode t : all) {
            if (t.status == DownloadEpisode.STATUS_PAUSED || t.status == DownloadEpisode.STATUS_FAILED) {
                t.status = DownloadEpisode.STATUS_WAITING;
                t.updateTime = now;
                RoomDataManger.updateDownloadEpisode(t);
            }
            if (t.status == DownloadEpisode.STATUS_WAITING) hasWaiting = true;
        }
        postChanged();
        if (hasWaiting) {
            DownloadService.start();
            kick();
        } else {
            DownloadService.stop();
        }
    }

    public boolean isPausedAll() {
        return pausedAll;
    }

    /** 是否有任务处于 等待/解析/下载 中 */
    public boolean hasActiveTasks() {
        List<DownloadEpisode> all = RoomDataManger.getAllDownloadEpisodes();
        for (DownloadEpisode t : all) {
            if (t.status == DownloadEpisode.STATUS_WAITING
                    || t.status == DownloadEpisode.STATUS_RESOLVING
                    || t.status == DownloadEpisode.STATUS_DOWNLOADING) {
                return true;
            }
        }
        return false;
    }

    private void kick() {
        if (looping.compareAndSet(false, true)) {
            scheduler.execute(() -> {
                try {
                    runLoop();
                } finally {
                    looping.set(false);
                }
                // 防止竞态:循环退出瞬间又入了新任务
                if (nextWaitingTask() != null && !pausedAll) {
                    kick();
                }
            });
        }
    }

    private void runLoop() {
        while (!pausedAll) {
            DownloadEpisode task = nextWaitingTask();
            if (task == null) break;
            if (cancelIds.containsKey(task.getId())) {
                removeTask(task);
                postChanged();
                continue;
            }
            if (isWifiOnly() && !isWifi()) {
                // 仅Wi-Fi下载模式下等待网络
                break;
            }
            processTask(task);
        }
        if (!hasActiveTasks()) {
            DownloadService.stop();
        }
    }

    private DownloadEpisode nextWaitingTask() {
        List<DownloadEpisode> waiting = RoomDataManger.getAllDownloadEpisodes();
        DownloadEpisode first = null;
        for (DownloadEpisode t : waiting) {
            if (t.status == DownloadEpisode.STATUS_WAITING) {
                if (first == null || t.createTime < first.createTime) {
                    first = t;
                }
            }
        }
        return first;
    }

    private void processTask(DownloadEpisode task) {
        int id = task.getId();
        task.status = DownloadEpisode.STATUS_RESOLVING;
        task.errMsg = null;
        touch(task);
        postChanged();

        // 1. 解析真实地址
        if (TextUtils.isEmpty(task.resolvedUrl)) {
            PlayUrlResolver.Result resolved = PlayUrlResolver.resolve(task.sourceKey, task.flag, task.rawUrl);
            if (cancelIds.containsKey(id)) {
                removeTask(task);
                postChanged();
                return;
            }
            if (pauseIds.containsKey(id) || pausedAll) {
                pauseIds.remove(id);
                task.status = DownloadEpisode.STATUS_PAUSED;
                touch(task);
                postChanged();
                return;
            }
            if (!resolved.ok) {
                task.status = DownloadEpisode.STATUS_FAILED;
                task.errMsg = resolved.errMsg;
                touch(task);
                postChanged();
                return;
            }
            task.resolvedUrl = resolved.url;
            task.headersJson = PlayUrlResolver.headersToJson(resolved.headers);
        }
        task.mediaType = task.resolvedUrl != null && task.resolvedUrl.contains(".m3u8")
                ? DownloadEpisode.TYPE_HLS : DownloadEpisode.TYPE_PROGRESSIVE;

        // 2. 准备目录
        File dir = DownloadStorage.episodeDir(task.vodId, task.vodName, task.flag, task.episodeIndex, task.episodeName);
        task.localDir = dir.getAbsolutePath();

        // 3. 下载
        task.status = DownloadEpisode.STATUS_DOWNLOADING;
        touch(task);
        postChanged();
        Map<String, String> headers = PlayUrlResolver.headersFromJson(task.headersJson);
        final long[] lastPostTime = {0};
        if (task.mediaType == DownloadEpisode.TYPE_HLS) {
            HlsDownloader.ProgressListener listener = (done, total, bytes) -> {
                long now = System.currentTimeMillis();
                if (now - lastPostTime[0] > 500) {
                    lastPostTime[0] = now;
                    long bytesDone = DownloadStorage.dirSize(DownloadStorage.partsDir(dir));
                    postProgress(task, DownloadEpisode.STATUS_DOWNLOADING, bytesDone, 0, bytesDone, done, total);
                }
            };
            HlsDownloader.Result r = HlsDownloader.download(task.resolvedUrl, headers, dir,
                    () -> pausedAll || pauseIds.containsKey(id) || cancelIds.containsKey(id), listener);
            if (r.cancelled || cancelIds.containsKey(id)) {
                removeTask(task);
                postChanged();
                return;
            }
            if (r.paused || pauseIds.containsKey(id) || pausedAll) {
                pauseIds.remove(id);
                task.status = DownloadEpisode.STATUS_PAUSED;
                task.downloadedBytes = DownloadStorage.dirSize(dir);
                touch(task);
                postChanged();
                return;
            }
            if (!r.success) {
                task.status = DownloadEpisode.STATUS_FAILED;
                task.errMsg = r.errMsg;
                touch(task);
                postChanged();
                return;
            }
            // 4. 合并 MP4(分片已解密拼接,无损转封装)
            File playlistFile = new File(r.localPlaylistPath);
            File mergeInput = new File(r.mergeInputPath);
            File outFile = new File(dir, "index.mp4");
            boolean merged = MediaMerger.mergeToMp4(mergeInput, outFile);
            if (cancelIds.containsKey(id)) {
                removeTask(task);
                postChanged();
                return;
            }
            if (pauseIds.containsKey(id) || pausedAll) {
                pauseIds.remove(id);
                task.status = DownloadEpisode.STATUS_PAUSED;
                touch(task);
                postChanged();
                return;
            }
            if (merged) {
                // 删除分片与索引,只保留 MP4
                DownloadStorage.deleteRecursive(DownloadStorage.partsDir(dir));
                playlistFile.delete();
                task.localFilePath = outFile.getAbsolutePath();
                task.totalBytes = outFile.length();
            } else {
                // 降级: 保留分片+本地索引,同样可离线播放
                mergeInput.delete();
                task.localFilePath = playlistFile.getAbsolutePath();
                task.totalBytes = DownloadStorage.dirSize(dir);
            }
            task.downloadedBytes = task.totalBytes;
            finishTask(task);
        } else {
            String ext = ProgressiveDownloader.guessExt(task.resolvedUrl);
            File target = new File(dir, "source." + ext);
            ProgressiveDownloader.ProgressListener listener = (bytes, total) -> {
                long now = System.currentTimeMillis();
                if (now - lastPostTime[0] > 500) {
                    lastPostTime[0] = now;
                    postProgress(task, DownloadEpisode.STATUS_DOWNLOADING, bytes, total, 0, -1, -1);
                }
            };
            ProgressiveDownloader.Result r = ProgressiveDownloader.download(task.resolvedUrl, headers, target,
                    () -> pausedAll || pauseIds.containsKey(id) || cancelIds.containsKey(id), listener);
            if (r.cancelled || cancelIds.containsKey(id)) {
                removeTask(task);
                postChanged();
                return;
            }
            if (r.paused || pauseIds.containsKey(id) || pausedAll) {
                pauseIds.remove(id);
                task.status = DownloadEpisode.STATUS_PAUSED;
                task.downloadedBytes = target.exists() ? target.length() : 0;
                task.totalBytes = r.totalBytes;
                touch(task);
                postChanged();
                return;
            }
            if (!r.success) {
                task.status = DownloadEpisode.STATUS_FAILED;
                task.errMsg = r.errMsg;
                touch(task);
                postChanged();
                return;
            }
            // mp4 直接完成,其它容器尝试无损转封装
            if ("mp4".equals(ext)) {
                task.localFilePath = target.getAbsolutePath();
                task.totalBytes = target.length();
            } else {
                File outFile = new File(dir, "index.mp4");
                if (MediaMerger.remuxToMp4(target, outFile)) {
                    target.delete();
                    task.localFilePath = outFile.getAbsolutePath();
                    task.totalBytes = outFile.length();
                } else {
                    outFile.delete();
                    task.localFilePath = target.getAbsolutePath();
                    task.totalBytes = target.length();
                }
            }
            task.downloadedBytes = task.totalBytes;
            finishTask(task);
        }
    }

    private void finishTask(DownloadEpisode task) {
        task.status = DownloadEpisode.STATUS_DONE;
        task.errMsg = null;
        touch(task);
        DownloadEvent event = new DownloadEvent(DownloadEvent.TYPE_DONE);
        event.episodeId = task.getId();
        event.status = task.status;
        event.title = task.displayTitle();
        event.totalBytes = task.totalBytes;
        EventBus.getDefault().post(event);
        postChanged();
    }

    private void removeTask(DownloadEpisode task) {
        cancelIds.remove(task.getId());
        pauseIds.remove(task.getId());
        if (task.localDir != null) {
            DownloadStorage.deleteRecursive(new File(task.localDir));
            DownloadStorage.cleanEmptyParents(new File(task.localDir));
        }
        RoomDataManger.deleteDownloadEpisode(task);
    }

    private void touch(DownloadEpisode task) {
        task.updateTime = System.currentTimeMillis();
        RoomDataManger.updateDownloadEpisode(task);
    }

    private void postProgress(DownloadEpisode task, int status, long downloaded, long total,
                              long hlsBytes, int segmentsDone, int segmentsTotal) {
        DownloadEvent event = new DownloadEvent(DownloadEvent.TYPE_PROGRESS);
        event.episodeId = task.getId();
        event.status = status;
        event.errMsg = task.errMsg;
        if (total > 0) {
            event.downloadedBytes = downloaded;
            event.totalBytes = total;
        } else {
            event.downloadedBytes = hlsBytes;
            event.totalBytes = 0;
        }
        event.segmentsDone = segmentsDone;
        event.segmentsTotal = segmentsTotal;
        Long preBytes = lastBytes.get(task.getId());
        Long preTime = lastTime.get(task.getId());
        long now = System.currentTimeMillis();
        if (preBytes != null && preTime != null && now > preTime) {
            event.speedBytes = Math.max(0, (downloaded - preBytes) * 1000 / (now - preTime));
        }
        lastBytes.put(task.getId(), downloaded);
        lastTime.put(task.getId(), now);
        EventBus.getDefault().post(event);
    }

    private void postChanged() {
        EventBus.getDefault().post(new DownloadEvent(DownloadEvent.TYPE_TASKS_CHANGED));
    }

    private boolean isWifiOnly() {
        try {
            return Hawk.get(HawkConfig.DOWNLOAD_WIFI_ONLY, false);
        } catch (Throwable th) {
            return false;
        }
    }

    private boolean isWifi() {
        try {
            Context context = App.getInstance();
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable th) {
            return true;
        }
    }
}
