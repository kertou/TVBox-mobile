package com.github.tvbox.osc.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.download.DownloadTaskManager;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.ui.activity.DownloadActivity;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.DecimalFormat;

/**
 * 应用内缓存前台服务:常驻进度通知 + 每集完成通知。
 */
public class DownloadService extends Service {

    public static final String ACTION_PAUSE_ALL = "com.github.tvbox.osc.download.ACTION_PAUSE_ALL";
    public static final String ACTION_RESUME_ALL = "com.github.tvbox.osc.download.ACTION_RESUME_ALL";

    private static final String CHANNEL_PROGRESS = "download_progress";
    private static final String CHANNEL_DONE = "download_done";
    private static final int FOREGROUND_ID = 100;

    /** 通知节流 */
    private long lastNotifyTime = 0;

    private final BroadcastReceiver actionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_PAUSE_ALL.equals(action)) {
                DownloadTaskManager.get().pauseAll();
                updateNotification(buildNotification("已暂停", "点击通知栏继续按钮可恢复下载", 0, false));
            } else if (ACTION_RESUME_ALL.equals(action)) {
                DownloadTaskManager.get().resumeAll();
            }
        }
    };

    public static void start() {
        try {
            ContextCompat.startForegroundService(App.getInstance(),
                    new Intent(App.getInstance(), DownloadService.class));
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    public static void stop() {
        App.getInstance().stopService(new Intent(App.getInstance(), DownloadService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        EventBus.getDefault().register(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel progress = new NotificationChannel(CHANNEL_PROGRESS, "缓存进度",
                    NotificationManager.IMPORTANCE_LOW);
            progress.setShowBadge(false);
            nm.createNotificationChannel(progress);
            NotificationChannel done = new NotificationChannel(CHANNEL_DONE, "缓存完成提醒",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(done);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PAUSE_ALL);
        filter.addAction(ACTION_RESUME_ALL);
        registerReceiver(actionReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_ID, buildNotification("正在缓存视频", "正在准备下载任务…", 0, true));
        if (!DownloadTaskManager.get().hasActiveTasks()) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        if (event.type == DownloadEvent.TYPE_DONE) {
            notifyDone(event);
            return;
        }
        if (event.type != DownloadEvent.TYPE_PROGRESS) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastNotifyTime < 800) {
            return;
        }
        lastNotifyTime = now;
        if (DownloadTaskManager.get().hasActiveTasks()) {
            String title = "正在缓存视频";
            String text = buildProgressText(event);
            updateNotification(buildNotification(title, text, percentOf(event), true));
        } else {
            stopSelf();
        }
    }

    private String buildProgressText(DownloadEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.segmentsDone >= 0 && event.segmentsTotal > 0) {
            sb.append("已下载 ").append(event.segmentsDone).append("/").append(event.segmentsTotal).append(" 个分片");
        } else if (event.totalBytes > 0) {
            sb.append(formatSize(event.downloadedBytes)).append(" / ")
                    .append(formatSize(event.totalBytes));
        } else if (event.downloadedBytes > 0) {
            sb.append("已下载 ").append(formatSize(event.downloadedBytes));
        }
        if (event.speedBytes > 0) {
            sb.append(" · ").append(formatSize(event.speedBytes)).append("/s");
        }
        return sb.length() > 0 ? sb.toString() : "正在下载…";
    }

    private int percentOf(DownloadEvent event) {
        if (event.segmentsDone >= 0 && event.segmentsTotal > 0) {
            return (int) (event.segmentsDone * 100L / event.segmentsTotal);
        }
        if (event.totalBytes > 0) {
            return (int) Math.min(100, event.downloadedBytes * 100 / event.totalBytes);
        }
        return 0;
    }

    private Notification buildNotification(String title, String text, int percent, boolean withPauseAction) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent());
        if (percent > 0) {
            builder.setProgress(100, percent, false);
        } else {
            builder.setProgress(0, 0, true);
        }
        if (withPauseAction) {
            builder.addAction(0, "暂停全部", actionIntent(ACTION_PAUSE_ALL, 1));
        } else {
            builder.addAction(0, "继续", actionIntent(ACTION_RESUME_ALL, 2));
        }
        return builder.build();
    }

    private void notifyDone(DownloadEvent event) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_DONE)
                    .setSmallIcon(R.drawable.app_icon)
                    .setContentTitle("缓存完成")
                    .setContentText((event.title == null ? "视频" : event.title) + " 已保存到应用内缓存")
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent());
            NotificationManagerCompat.from(this).notify(200 + event.episodeId, builder.build());
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void updateNotification(Notification notification) {
        NotificationManagerCompat.from(this).notify(FOREGROUND_ID, notification);
    }

    private PendingIntent contentIntent() {
        Intent intent = new Intent(this, DownloadActivity.class);
        return PendingIntent.getActivity(this, 10, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent actionIntent(String action, int requestCode) {
        Intent intent = new Intent(action).setPackage(getPackageName());
        return PendingIntent.getBroadcast(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return new DecimalFormat("#.#").format(bytes / 1024.0) + "KB";
        if (bytes < 1024L * 1024 * 1024) return new DecimalFormat("#.#").format(bytes / 1024.0 / 1024.0) + "MB";
        return new DecimalFormat("#.##").format(bytes / 1024.0 / 1024.0 / 1024.0) + "GB";
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        unregisterReceiver(actionReceiver);
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
