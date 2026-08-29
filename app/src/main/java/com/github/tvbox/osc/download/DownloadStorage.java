package com.github.tvbox.osc.download;

import com.github.tvbox.osc.base.App;

import java.io.File;

/**
 * 应用内缓存(离线缓存)的目录规划与文件清理。
 * 所有缓存都存放在应用私有目录 getExternalFilesDir/offline 下,
 * 并写入 .nomedia,相册/媒体库不会扫描到。
 */
public class DownloadStorage {

    public static File baseDir() {
        File external = App.getInstance().getExternalFilesDir(null);
        File base = new File(external != null ? external : App.getInstance().getFilesDir(), "offline");
        if (!base.exists()) {
            base.mkdirs();
        }
        ensureNoMedia(base);
        return base;
    }

    private static void ensureNoMedia(File dir) {
        File nomedia = new File(dir, ".nomedia");
        if (!nomedia.exists()) {
            try {
                nomedia.createNewFile();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
    }

    /** 剧集目录: offline/<vodId_剧名>/<线路>/<NN_集名>/ */
    public static File episodeDir(String vodId, String vodName, String flag, int episodeIndex, String episodeName) {
        File seriesDir = new File(baseDir(), sanitize(vodId + "_" + vodName));
        File flagDir = new File(seriesDir, sanitize(flag == null ? "线路" : flag));
        File dir = new File(flagDir, String.format("%03d_%s", episodeIndex + 1, sanitize(episodeName)));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        ensureNoMedia(dir);
        return dir;
    }

    /** 分片下载临时目录 */
    public static File partsDir(File episodeDir) {
        File dir = new File(episodeDir, "parts");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        ensureNoMedia(dir);
        return dir;
    }

    public static String sanitize(String name) {
        if (name == null) return "unknown";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (cleaned.length() > 50) {
            cleaned = cleaned.substring(0, 50);
        }
        if (cleaned.isEmpty()) cleaned = "unknown";
        return cleaned;
    }

    public static long dirSize(File dir) {
        long size = 0;
        if (dir == null || !dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    size += dirSize(f);
                } else {
                    size += f.length();
                }
            }
        }
        return size;
    }

    public static long totalUsedBytes() {
        return dirSize(baseDir());
    }

    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] files = file.listFiles();
        if (files != null) {
            for (File f : files) {
                deleteRecursive(f);
            }
        }
        file.delete();
    }

    /** 删除空的父目录直到 baseDir(含)为止 */
    public static void cleanEmptyParents(File dir) {
        File base = baseDir();
        File cur = dir;
        while (cur != null && !cur.getAbsolutePath().equals(base.getAbsolutePath())) {
            File[] files = cur.listFiles();
            if (files != null && files.length > 0) break;
            cur.delete();
            cur = cur.getParentFile();
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format("%.2fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
