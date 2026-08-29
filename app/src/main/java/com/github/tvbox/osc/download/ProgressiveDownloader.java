package com.github.tvbox.osc.download;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 渐进式(mp4/mkv/ts/flv 等)单文件下载器,支持 Range 断点续传。
 */
public class ProgressiveDownloader {

    public interface AbortChecker {
        boolean shouldAbort();
    }

    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    public static class Result {
        public boolean success;
        public boolean paused;
        public boolean cancelled;
        /** 416 且本地文件与远端不一致时置位,由 download() 清零重下一次 */
        boolean restart;
        public String errMsg;
        public String localFilePath;
        public long totalBytes;
    }

    private static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * @param target 目标文件(续传时若存在则从文件尾部继续)
     */
    public static Result download(String url, Map<String, String> headers, File target,
                                  AbortChecker abort, ProgressListener listener) {
        long downloaded = target.exists() && target.length() > 0 ? target.length() : 0;
        // 最多清零重下一次:再次 416 直接失败,防止"Range 越界→416→重试"死循环
        for (int attempt = 0; attempt < 2; attempt++) {
            Result result = downloadOnce(url, headers, target, abort, listener, downloaded);
            if (result.restart) {
                downloaded = 0;
                continue;
            }
            return result;
        }
        Result result = new Result();
        result.errMsg = "下载请求失败: HTTP 416";
        return result;
    }

    private static Result downloadOnce(String url, Map<String, String> headers, File target,
                                       AbortChecker abort, ProgressListener listener, long downloaded) {
        Result result = new Result();
        OkHttpClient client = DownloadHttp.client();
        try {
            Request.Builder builder = new Request.Builder().url(url);
            builder.header("User-Agent", DEFAULT_UA);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    if ("user-agent".equalsIgnoreCase(h.getKey())) {
                        builder.header("User-Agent", h.getValue());
                    } else {
                        builder.header(h.getKey(), h.getValue());
                    }
                }
            }
            if (downloaded > 0) {
                builder.header("Range", "bytes=" + downloaded + "-");
            }
            Response response = client.newCall(builder.build()).execute();
            if (response.code() == 416) {
                // 断点越界:常见于"字节已下满但未合并"瞬间进程被杀后恢复,也可能是本地文件损坏
                long total = totalFromContentRange(response.header("Content-Range"));
                if (total >= 0 && downloaded >= total) {
                    // 已下满:跳过下载直接成功,由上层走合并/收尾
                    result.success = true;
                    result.totalBytes = Math.max(total, target.length());
                    result.localFilePath = target.getAbsolutePath();
                } else {
                    // 本地文件比远端大或拿不到远端大小:清零重下
                    result.restart = true;
                }
                response.close();
                return result;
            }
            if (!response.isSuccessful() || response.body() == null) {
                result.errMsg = "下载请求失败: HTTP " + response.code();
                response.close();
                return result;
            }
            String range = response.header("Content-Range");
            long contentLength = response.body().contentLength();
            long totalBytes;
            if (response.code() == 206 && range != null && range.contains("/")) {
                String totalStr = range.substring(range.lastIndexOf('/') + 1);
                try {
                    totalBytes = Long.parseLong(totalStr);
                } catch (NumberFormatException e) {
                    totalBytes = downloaded + contentLength;
                }
            } else if (response.code() == 200) {
                // 服务端不支持 Range,重新下载
                totalBytes = contentLength;
                downloaded = 0;
                target.delete();
            } else {
                totalBytes = downloaded + contentLength;
            }
            if (totalBytes <= 0 && downloaded > 0) {
                totalBytes = downloaded;
            }

            FileOutputStream fos = new FileOutputStream(target, response.code() == 206 && downloaded > 0);
            InputStream is = response.body().byteStream();
            byte[] buf = new byte[64 * 1024];
            long lastNotify = 0;
            int len;
            while ((len = is.read(buf)) != -1) {
                if (abort.shouldAbort()) {
                    fos.close();
                    is.close();
                    response.close();
                    result.paused = true;
                    result.totalBytes = Math.max(totalBytes, target.length());
                    result.localFilePath = target.getAbsolutePath();
                    return result;
                }
                fos.write(buf, 0, len);
                downloaded += len;
                long now = System.currentTimeMillis();
                if (listener != null && now - lastNotify > 500) {
                    lastNotify = now;
                    listener.onProgress(downloaded, totalBytes);
                }
            }
            fos.close();
            is.close();
            response.close();
            result.success = true;
            result.totalBytes = Math.max(totalBytes, target.length());
            result.localFilePath = target.getAbsolutePath();
            return result;
        } catch (IOException ioe) {
            if (abort.shouldAbort()) {
                result.paused = true;
                result.totalBytes = Math.max(result.totalBytes, target.length());
                result.localFilePath = target.getAbsolutePath();
                return result;
            }
            result.errMsg = "下载异常: " + ioe.getMessage();
            return result;
        } catch (Throwable th) {
            result.errMsg = "下载异常: " + th.getMessage();
            return result;
        }
    }

    /** 解析 416 响应的 Content-Range: bytes *\/12345 → 12345,拿不到返回 -1 */
    private static long totalFromContentRange(String range) {
        if (range == null) return -1;
        int slash = range.lastIndexOf('/');
        if (slash < 0) return -1;
        try {
            return Long.parseLong(range.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 从地址推断文件扩展名(默认 mp4) */
    public static String guessExt(String url) {
        try {
            String path = url.split("\\?")[0];
            int dot = path.lastIndexOf('.');
            int slash = path.lastIndexOf('/');
            if (dot > slash && dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot + 1).toLowerCase();
                if (ext.length() <= 5 && ext.matches("[a-z0-9]+")) return ext;
            }
        } catch (Throwable ignored) {
        }
        return "mp4";
    }
}
