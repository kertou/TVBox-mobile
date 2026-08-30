package com.github.tvbox.osc.download;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.tvbox.osc.cache.DownloadEpisode;
import com.github.tvbox.osc.api.ApiConfig;
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
    /** 失败自动重试: 每集已重试次数与下次重试时间(内存态,不落库,进程重启后手动重试重新计)。
     *  次数给足 6 次:限流型源(防盗链配额)每窗口只能推进几十个分片,已下分片跨尝试累加,多退几次能下完整集 */
    private static final int MAX_AUTO_RETRY = 6;
    private static final long RETRY_DELAY_MS = 5000;
    /** 限流型失败阶梯退避: 403/成批网络重置多为源站配额(实测约放行45个分片后拦截),
     *  5 秒后立刻重试只会撞在同一堵墙上,拉开间隔等配额窗口恢复,每次重试能推进一段 */
    private static final long[] RETRY_DELAYS_THROTTLED = {20_000, 60_000, 180_000, 300_000, 600_000, 600_000};
    private final Map<Integer, Integer> retryCounts = new ConcurrentHashMap<>();
    private final Map<Integer, Long> retryUntil = new ConcurrentHashMap<>();
    private final Handler retryHandler = new Handler(Looper.getMainLooper());

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

    /** App 启动时把上次异常退出的"下载中"任务重置为暂停;自检"假完成"条目;自动续跑遗留的等待队列 */
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
            // 自检要读文件头,放调度线程执行,完成后再决定是否续跑
            scheduler.execute(() -> {
                try {
                    int fixed = sanitizeDoneEpisodes();
                    boolean hasWaiting = nextWaitingTask() != null;
                    if ((hasWaiting || fixed > 0) && !pausedAll) {
                        // 否则上次退出时已排队的任务要等到下次入队才会开始下载
                        kickWhenSourceReady();
                    }
                } catch (Throwable th) {
                    th.printStackTrace();
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /**
     * 启动自检: 历史上直链下载不校验内容,把 m3u8 播放列表/网页错误页存成了
     * 视频并标成"已缓存"(假完成、无法播放)。文件头嗅探把这些条目打回等待队列,
     * 配合直链 m3u8 嗅探转 HLS,重新下载后即可正常离线播放。
     * 分片模式(索引为 .m3u8)同样有假完成形态:源站死节点把整集"分片"放在
     * 图床上(BMP/JPG 占位图),抽查 parts 文件头识别。
     *
     * @return 重置的条目数
     */
    private int sanitizeDoneEpisodes() {
        int fixed = 0;
        try {
            List<DownloadEpisode> all = RoomDataManger.getAllDownloadEpisodes();
            for (DownloadEpisode t : all) {
                if (t.status != DownloadEpisode.STATUS_DONE || TextUtils.isEmpty(t.localFilePath)) continue;
                String reason = null;
                if (t.localFilePath.endsWith(".m3u8")) {
                    reason = segmentPartsBroken(t);
                } else {
                    File f = new File(t.localFilePath);
                    if (!f.exists() || f.length() <= 0) continue;
                    byte[] head = ContentSniff.sniff(f, 512);
                    if (ContentSniff.isM3u8(head) || ContentSniff.isHtml(head)) {
                        reason = "内容非视频";
                    } else if (ContentSniff.isImageHead(head)) {
                        // 老版本可能把"图片头包裹的真视频"直链存成了假完成:能剥出视频就原地剥离保留
                        if (ContentSniff.stripImageWrapper(f) < 0) reason = "内容非视频";
                    } else {
                        reason = null;
                    }
                }
                if (reason == null) continue;
                // 直链假完成把误存文件一并删掉;分片模式保留 parts,
                // 重下时已存在的合法分片会被跳过,垃圾分片(含 BMP)会被重新下载
                if (!t.localFilePath.endsWith(".m3u8")) {
                    new File(t.localFilePath).delete();
                }
                t.status = DownloadEpisode.STATUS_WAITING;
                t.errMsg = null;
                t.totalBytes = 0;
                t.downloadedBytes = 0;
                t.localFilePath = null;
                // 产生假缓存的解析地址大概率是死节点,清掉强制重下时重新解析
                t.resolvedUrl = null;
                t.headersJson = null;
                t.updateTime = System.currentTimeMillis();
                RoomDataManger.updateDownloadEpisode(t);
                fixed++;
                android.util.Log.w("DownloadTask", "自检重置假完成条目(" + reason + "): " + t.displayTitle());
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        if (fixed > 0) {
            postChanged();
        }
        return fixed;
    }

    /** 分片模式假完成自检: 索引存在且 parts 里均匀抽查最多 6 个分片,
     *  任一是图片/网页头或空文件即判整集为假缓存(源站死节点批量返回占位图) */
    private static String segmentPartsBroken(DownloadEpisode t) {
        File index = new File(t.localFilePath);
        if (!index.exists() || index.length() <= 0) return "索引缺失";
        File[] files = new File(index.getParentFile(), "parts").listFiles();
        if (files == null) return "分片缺失";
        int segCount = 0;
        for (File f : files) {
            if (f.getName().startsWith("seg_")) segCount++;
        }
        if (segCount == 0) return "分片缺失";
        File[] segs = new File[segCount];
        int n = 0;
        for (File f : files) {
            if (f.getName().startsWith("seg_")) segs[n++] = f;
        }
        int samples = Math.min(6, segs.length);
        for (int i = 0; i < samples; i++) {
            File pick = samples == 1 ? segs[0]
                    : segs[Math.round(i * (segs.length - 1) / (float) (samples - 1))];
            if (pick.length() <= 0 || ContentSniff.isGarbageHead(ContentSniff.sniff(pick, 16))) {
                return "分片内容非视频";
            }
        }
        return null;
    }

    /** 等订阅/源列表加载完成再启动队列:App 启动早期源列表还是空的,
     *  立即跑队列会让所有任务瞬间失败"数据源不存在或未启用"。
     *  最多等 60 秒,订阅加载失败也放行,让任务拿到明确的失败原因。
     *  该等待占用调度线程,期间入队的新任务会排在后面,同样不会抢跑。 */
    private void kickWhenSourceReady() {
        scheduler.execute(() -> {
            for (int i = 0; i < 60 && ApiConfig.get().getSourceBeanList().isEmpty(); i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            DownloadService.start();
            kick();
        });
    }

    public void enqueue(List<DownloadEpisode> tasks) {
        long now = System.currentTimeMillis();
        // 入队是明确的下载意图:清掉全局暂停,否则清空缓存等路径遗留的
        // pausedAll 会让新队列永远停在"等待中"且界面无恢复入口
        pausedAll = false;
        for (DownloadEpisode t : tasks) {
            pauseIds.remove(t.getId());
            cancelIds.remove(t.getId());
            clearRetryState(t.getId());
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
        // 立即打断在途下载请求:阻塞中的连接可能长时间无响应,不能等它自然超时
        DownloadHttp.cancelAll();
    }

    public void resume(int episodeId) {
        pauseIds.remove(episodeId);
        clearRetryState(episodeId);
        DownloadEpisode t = RoomDataManger.getDownloadEpisode(episodeId);
        if (t != null && (t.status == DownloadEpisode.STATUS_PAUSED || t.status == DownloadEpisode.STATUS_FAILED)) {
            if (t.status == DownloadEpisode.STATUS_FAILED) {
                // 失败集重试重新解析:缓存地址可能已失效(源站多节点轮换)
                t.resolvedUrl = null;
                t.headersJson = null;
            }
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
        DownloadHttp.cancelAll();
        DownloadEpisode t = RoomDataManger.getDownloadEpisode(episodeId);
        if (t != null && t.status != DownloadEpisode.STATUS_DOWNLOADING && t.status != DownloadEpisode.STATUS_RESOLVING) {
            removeTask(t);
            postChanged();
        }
    }

    public void pauseAll() {
        pausedAll = true;
        DownloadHttp.cancelAll();
    }

    public void resumeAll() {
        pausedAll = false;
        List<DownloadEpisode> all = RoomDataManger.getAllDownloadEpisodes();
        long now = System.currentTimeMillis();
        boolean hasWaiting = false;
        for (DownloadEpisode t : all) {
            if (t.status == DownloadEpisode.STATUS_PAUSED || t.status == DownloadEpisode.STATUS_FAILED) {
                clearRetryState(t.getId());
                if (t.status == DownloadEpisode.STATUS_FAILED) {
                    // 失败集重新解析:缓存地址可能已失效(源站多节点轮换)
                    t.resolvedUrl = null;
                    t.headersJson = null;
                }
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
        android.util.Log.d("DownloadTask", "kick looping=" + looping.get() + " pausedAll=" + pausedAll);
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
        android.util.Log.d("DownloadTask", "runLoop enter pausedAll=" + pausedAll);
        while (!pausedAll) {
            DownloadEpisode task = nextWaitingTask();
            if (task == null) break;
            android.util.Log.d("DownloadTask", "runLoop pick id=" + task.getId() + " " + task.displayTitle());
            if (cancelIds.containsKey(task.getId())) {
                removeTask(task);
                postChanged();
                continue;
            }
            if (isWifiOnly() && !isWifi()) {
                // 仅Wi-Fi下载模式下等待网络
                break;
            }
            try {
                processTask(task);
            } catch (Throwable th) {
                // 单集处理中的意外异常不允许杀死唯一的调度线程,否则整个队列永久卡死
                android.util.Log.e("DownloadTask", "processTask crashed: " + task.displayTitle(), th);
                if (!failOrRetry(task, "内部错误:" + th.getMessage())) {
                    task.status = DownloadEpisode.STATUS_FAILED;
                    task.errMsg = "内部错误:" + th.getMessage();
                    touch(task);
                    postChanged();
                }
            }
        }
        // 注意:这里不能调用 DownloadService.stop()——
        // 外部 stopService 与 startForegroundService 竞态会触发
        // ForegroundServiceDidNotStartInTimeException 崩溃;
        // 服务空队列时自行 stopSelf(见 DownloadService.onDownloadEvent)。
    }

    private DownloadEpisode nextWaitingTask() {
        List<DownloadEpisode> waiting = RoomDataManger.getAllDownloadEpisodes();
        DownloadEpisode first = null;
        long now = System.currentTimeMillis();
        for (DownloadEpisode t : waiting) {
            if (t.status != DownloadEpisode.STATUS_WAITING) continue;
            Long until = retryUntil.get(t.getId());
            if (until != null && until > now) continue; // 重试退避期内先跳过,不阻塞其他集
            if (first == null || t.createTime < first.createTime) {
                first = t;
            }
        }
        return first;
    }

    private void processTask(DownloadEpisode task) {
        int id = task.getId();
        // 解析开始前先响应暂停/取消,点暂停的集不再进入解析
        if (pauseIds.containsKey(id) || pausedAll) {
            pauseIds.remove(id);
            task.status = DownloadEpisode.STATUS_PAUSED;
            touch(task);
            postChanged();
            return;
        }
        if (cancelIds.containsKey(id)) {
            removeTask(task);
            postChanged();
            return;
        }
        task.status = DownloadEpisode.STATUS_RESOLVING;
        task.errMsg = null;
        touch(task);
        postChanged();

        // 1. 解析真实地址
        // 本地代理地址(spider 经 127.0.0.1:9978/proxy?do=xx 暴露)依赖 spider 的内存态,
        // 进程重启后必然失效(实测返回 200 空体),每次处理都强制重新解析
        if (task.resolvedUrl != null && isSessionProxyUrl(task.resolvedUrl)) {
            task.resolvedUrl = null;
        }
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
                if (failOrRetry(task, resolved.errMsg)) return;
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
        if (task.mediaType == DownloadEpisode.TYPE_HLS) {
            runHlsTask(task, dir, headers, id);
            return;
        }
        String ext = ProgressiveDownloader.guessExt(task.resolvedUrl);
        File target = new File(dir, "source." + ext);
        final long[] lastPostTime = {0};
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
        if (r.playlistContent) {
            // "直链"实际返回的是 m3u8 播放列表(地址不带 .m3u8 的伪装/代理直链):
            // 删掉误存文件,转 HLS 流水线重新下载
            target.delete();
            task.mediaType = DownloadEpisode.TYPE_HLS;
            touch(task);
            runHlsTask(task, dir, headers, id);
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
            if (failOrRetry(task, r.errMsg)) return;
            task.status = DownloadEpisode.STATUS_FAILED;
            task.errMsg = r.errMsg;
            touch(task);
            postChanged();
            return;
        }
        // mp4 直接完成,其它容器尝试无损转封装
        // 先剥防盗链伪装:部分源把真视频存成"占位图头+视频负载"(实测 PNG/BMP 包 TS),
        // 播放器能靠 TS 同步字节容错,不剥离则合并产物损坏
        int stripped = ContentSniff.stripImageWrapper(target);
        if (stripped < 0) {
            target.delete();
            if (failOrRetry(task, "内容无效,非视频文件")) return;
            task.status = DownloadEpisode.STATUS_FAILED;
            task.errMsg = "内容无效,非视频文件";
            touch(task);
            postChanged();
            return;
        }
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

    /** HLS 下载→合并→完成收尾(取消/暂停/失败在其中处理) */
    private void runHlsTask(DownloadEpisode task, File dir, Map<String, String> headers, int id) {
        final long[] lastPostTime = {0};
        HlsDownloader.ProgressListener listener = (done, total, bytes) -> {
            long now = System.currentTimeMillis();
            if (now - lastPostTime[0] > 500) {
                lastPostTime[0] = now;
                // 字节由 HlsDownloader 按完成分片累计上报,不再每 500ms 递归扫描 parts 目录
                postProgress(task, DownloadEpisode.STATUS_DOWNLOADING, bytes, 0, bytes, done, total);
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
            if (failOrRetry(task, r.errMsg, r.segmentsDone)) return;
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
            android.util.Log.w("DownloadTask", "HLS合并回退分片模式: " + task.displayTitle()
                    + " 原因: " + MediaMerger.lastError());
            mergeInput.delete();
            task.localFilePath = playlistFile.getAbsolutePath();
            task.totalBytes = DownloadStorage.dirSize(dir);
        }
        task.downloadedBytes = task.totalBytes;
        finishTask(task);
    }

    private void finishTask(DownloadEpisode task) {
        task.status = DownloadEpisode.STATUS_DONE;
        task.errMsg = null;
        clearRetryState(task.getId());
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
        clearRetryState(task.getId());
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

    /** spider 经应用本地代理(127.0.0.1:9978/proxy?do=xx)暴露的地址,生命周期只在当前进程内 */
    private static boolean isSessionProxyUrl(String url) {
        return url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
                || url.startsWith("https://127.0.0.1") || url.startsWith("https://localhost");
    }

    /**
     * 失败自动重试: 非暂停/取消/全局暂停时回队列等待,间隔后退避自动续跑,
     * 最多 MAX_AUTO_RETRY 次;返回 true 表示已安排重试,false 表示调用方走原有的失败收尾。
     *
     * @param attemptSegments 本次尝试已完成的分片数(-1=非分片下载);达到阈值的失败按限流处理
     */
    private boolean failOrRetry(DownloadEpisode task, String errMsg, int attemptSegments) {
        int id = task.getId();
        if (pauseIds.containsKey(id) || cancelIds.containsKey(id) || pausedAll) return false;
        int count = retryCounts.merge(id, 1, Integer::sum);
        if (count > MAX_AUTO_RETRY) {
            clearRetryState(id);
            return false;
        }
        long delay = isThrottledFailure(errMsg, attemptSegments)
                ? RETRY_DELAYS_THROTTLED[Math.min(count - 1, RETRY_DELAYS_THROTTLED.length - 1)]
                : RETRY_DELAY_MS;
        // 解析地址可能已失效:这类源在多个镜像节点间轮换,死节点只会返回占位图/假分片,
        // 拿缓存地址重试永远撞同一堵墙;重试一律重新解析,与在线播放每次现解析的行为对齐
        task.resolvedUrl = null;
        task.headersJson = null;
        task.status = DownloadEpisode.STATUS_WAITING;
        task.errMsg = "下载失败,自动重试(" + count + "/" + MAX_AUTO_RETRY + ")"
                + (TextUtils.isEmpty(errMsg) ? "" : " · " + errMsg);
        retryUntil.put(id, System.currentTimeMillis() + delay);
        touch(task);
        postChanged();
        retryHandler.postDelayed(this::kick, delay);
        return true;
    }

    private boolean failOrRetry(DownloadEpisode task, String errMsg) {
        return failOrRetry(task, errMsg, -1);
    }

    /** 限流型失败:显式 403,或单次尝试已推进大量分片后才失败(配额耗尽特征,常表现为成批网络重置);
     *  占位图/假分片型失败(内容无效):源当前解析到的节点是死的,窗口内立刻重试只会重复拿到
     *  同一死节点,同样需要拉开间隔等源站节点轮换 */
    private static boolean isThrottledFailure(String errMsg, int attemptSegments) {
        if (errMsg != null && errMsg.contains("HTTP 403")) return true;
        if (errMsg != null && errMsg.contains("内容无效")) return true;
        return attemptSegments >= 20;
    }

    private void clearRetryState(int episodeId) {
        retryCounts.remove(episodeId);
        retryUntil.remove(episodeId);
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
