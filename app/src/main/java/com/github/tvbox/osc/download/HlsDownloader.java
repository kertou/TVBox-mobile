package com.github.tvbox.osc.download;

import android.text.TextUtils;

import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.OkGoHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * HLS(m3u8 + ts/fMP4 分片)下载器:
 * - 支持 master 播放列表(自动选最高码率)
 * - 支持 AES-128 加密(密钥一并下载,重写为本地相对路径,保留 IV)
 * - 支持 #EXT-X-MAP(init 段)
 * - 分片按序号命名,已存在且非空的分片直接跳过 → 断点续传
 * - 输出本地 index.m3u8,分片/密钥引用全部重写为相对路径
 */
public class HlsDownloader {

    public interface AbortChecker {
        boolean shouldAbort();
    }

    public interface ProgressListener {
        void onProgress(int segmentsDone, int segmentsTotal, long bytesDone);
    }

    public static class Result {
        public boolean success;
        public boolean paused;
        public boolean cancelled;
        public String errMsg;
        /** 本地 index.m3u8(合并失败时的分片播放模式) */
        public String localPlaylistPath;
        /** 解密/拼接后的待合并文件(ts 或 fmp4) */
        public String mergeInputPath;
        public long totalBytes;
    }

    private static final int THREADS = 4;
    private static final int SEGMENT_RETRY = 2;
    private static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Pattern ATTR_URI = Pattern.compile("URI=\"([^\"\\s]+)\"");

    public static Result download(String playlistUrl, Map<String, String> headers, File episodeDir,
                                  AbortChecker abort, ProgressListener listener) {
        Result result = new Result();
        File partsDir = DownloadStorage.partsDir(episodeDir);
        try {
            String content = fetchText(playlistUrl, headers);
            if (TextUtils.isEmpty(content) || !content.contains("#EXTM3U")) {
                result.errMsg = "播放列表获取失败";
                return result;
            }
            // master 播放列表 → 选最高码率的子流
            if (content.contains("#EXT-X-STREAM-INF")) {
                String child = pickBestVariant(content, playlistUrl);
                if (TextUtils.isEmpty(child)) {
                    result.errMsg = "未找到可用的播放列表";
                    return result;
                }
                playlistUrl = child;
                content = fetchText(playlistUrl, headers);
                if (TextUtils.isEmpty(content) || !content.contains("#EXTM3U")) {
                    result.errMsg = "播放列表获取失败";
                    return result;
                }
            }
            if (!content.contains("#EXT-X-ENDLIST")) {
                result.errMsg = "直播流暂不支持缓存";
                return result;
            }

            String newline = content.contains("\r\n") ? "\r\n" : "\n";
            String[] lines = content.split(newline);
            String playlistBase = playlistUrl.substring(0, playlistUrl.lastIndexOf('/') + 1);

            List<Segment> segments = new ArrayList<>();
            List<String> rewritten = new ArrayList<>();
            Map<String, String> keyLocalNames = new LinkedHashMap<>();// 绝对key地址 -> 本地名
            Map<String, String> mapLocalNames = new LinkedHashMap<>();// 绝对init段地址 -> 本地名
            long mediaSequence = 0;
            String currentKeyLocal = null;
            String currentIv = null;
            String currentInitLocal = null;

            for (String line : lines) {
                if (line.startsWith("#EXT-X-BYTERANGE")) {
                    result.errMsg = "该播放列表使用了暂不支持的分片方式";
                    return result;
                }
                if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try {
                        mediaSequence = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (line.startsWith("#EXT-X-KEY")) {
                    currentKeyLocal = null;
                    currentIv = null;
                    if (!line.contains("METHOD=NONE")) {
                        if (line.contains("SAMPLE-AES")) {
                            result.errMsg = "暂不支持 SAMPLE-AES 加密流";
                            return result;
                        }
                        Matcher m = ATTR_URI.matcher(line);
                        if (m.find()) {
                            String keyUri = resolveUrl(playlistBase, m.group(1));
                            String localName = keyLocalNames.get(keyUri);
                            if (localName == null) {
                                localName = "keys/" + MD5.string2MD5(keyUri) + ".key";
                                keyLocalNames.put(keyUri, localName);
                            }
                            currentKeyLocal = localName;
                            line = line.replace("URI=\"" + m.group(1) + "\"", "URI=\"" + localName + "\"");
                        }
                        Matcher iv = Pattern.compile("IV=0[xX]([0-9a-fA-F]+)").matcher(line);
                        if (iv.find()) {
                            currentIv = iv.group(1);
                        }
                    }
                } else if (line.startsWith("#EXT-X-MAP")) {
                    Matcher m = ATTR_URI.matcher(line);
                    currentInitLocal = null;
                    if (m.find()) {
                        String mapUri = resolveUrl(playlistBase, m.group(1));
                        String localName = mapLocalNames.get(mapUri);
                        if (localName == null) {
                            String ext = guessExt(mapUri, "mp4");
                            localName = "init_" + MD5.string2MD5(mapUri) + "." + ext;
                            mapLocalNames.put(mapUri, localName);
                        }
                        currentInitLocal = localName;
                        line = line.replace("URI=\"" + m.group(1) + "\"", "URI=\"" + localName + "\"");
                    }
                } else if (line.length() > 0 && line.charAt(0) != '#') {
                    String segUri = resolveUrl(playlistBase, line);
                    String ext = guessExt(segUri, "ts");
                    Segment seg = new Segment();
                    seg.remoteUrl = segUri;
                    seg.localName = "seg_" + segments.size() + "." + ext;
                    seg.keyLocalName = currentKeyLocal;
                    seg.iv = currentIv;
                    seg.initLocalName = currentInitLocal;
                    seg.sequence = mediaSequence;
                    segments.add(seg);
                    mediaSequence++;
                    line = "parts/" + seg.localName;
                }
                rewritten.add(line);
            }

            if (segments.isEmpty()) {
                result.errMsg = "播放列表中没有可下载的分片";
                return result;
            }

            // 密钥
            for (Map.Entry<String, String> entry : keyLocalNames.entrySet()) {
                if (abort.shouldAbort()) return aborted(result);
                File keyFile = new File(partsDir, entry.getValue());
                if (!keyFile.exists() || keyFile.length() <= 0) {
                    keyFile.getParentFile().mkdirs();
                    if (!downloadToFile(entry.getKey(), headers, keyFile)) {
                        result.errMsg = "密钥下载失败";
                        return result;
                    }
                }
            }
            // init 段
            for (Map.Entry<String, String> entry : mapLocalNames.entrySet()) {
                if (abort.shouldAbort()) return aborted(result);
                File initFile = new File(partsDir, entry.getValue());
                if (!initFile.exists() || initFile.length() <= 0) {
                    if (!downloadToFile(entry.getKey(), headers, initFile)) {
                        result.errMsg = "init 段下载失败";
                        return result;
                    }
                }
            }

            // 分片并发下载
            int total = segments.size();
            AtomicInteger doneCount = new AtomicInteger(0);
            AtomicBoolean failed = new AtomicBoolean(false);
            StringBuilder failMsg = new StringBuilder("分片下载失败");
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            List<Future<?>> futures = new ArrayList<>();
            for (Segment seg : segments) {
                futures.add(pool.submit(() -> {
                    if (failed.get()) return;
                    File target = new File(partsDir, seg.localName);
                    boolean ok = target.exists() && target.length() > 0;
                    for (int retry = 0; !ok && retry <= SEGMENT_RETRY; retry++) {
                        if (failed.get()) return;
                        ok = downloadToFile(seg.remoteUrl, headers, target);
                    }
                    if (!ok) {
                        failed.set(true);
                        failMsg.append(": ").append(seg.localName);
                        return;
                    }
                    int done = doneCount.incrementAndGet();
                    if (listener != null && !failed.get()) {
                        listener.onProgress(done, total, 0);
                    }
                }));
            }
            try {
                for (Future<?> f : futures) {
                    while (!f.isDone()) {
                        if (abort.shouldAbort() || failed.get()) {
                            pool.shutdownNow();
                            if (abort.shouldAbort()) return aborted(result);
                            result.errMsg = failMsg.toString();
                            return result;
                        }
                        Thread.sleep(150);
                    }
                    f.get();
                }
            } catch (InterruptedException ie) {
                pool.shutdownNow();
                return aborted(result);
            } catch (Throwable th) {
                pool.shutdownNow();
                result.errMsg = "分片下载失败: " + th.getMessage();
                return result;
            } finally {
                pool.shutdown();
            }
            if (failed.get()) {
                result.errMsg = failMsg.toString();
                return result;
            }
            if (abort.shouldAbort()) return aborted(result);

            // 写本地 index.m3u8
            File playlistFile = new File(episodeDir, "index.m3u8");
            StringBuilder sb = new StringBuilder();
            for (String line : rewritten) {
                sb.append(line).append(newline);
            }
            FileOutputStream fos = new FileOutputStream(playlistFile);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();

            // 生成解密+顺序拼接的合并输入文件
            File concatFile;
            try {
                concatFile = buildConcatFile(segments, partsDir);
            } catch (Throwable th) {
                th.printStackTrace();
                result.errMsg = "合并准备失败: " + th.getMessage();
                return result;
            }

            result.success = true;
            result.localPlaylistPath = playlistFile.getAbsolutePath();
            result.mergeInputPath = concatFile.getAbsolutePath();
            result.totalBytes = DownloadStorage.dirSize(episodeDir);
            return result;
        } catch (Throwable th) {
            th.printStackTrace();
            result.errMsg = "下载异常: " + th.getMessage();
            return result;
        }
    }

    private static Result aborted(Result result) {
        result.paused = true;
        return result;
    }

    private static class Segment {
        String remoteUrl;
        String localName;
        String keyLocalName;
        String iv;
        String initLocalName;
        long sequence;
    }

    /**
     * 按 HLS 顺序把分片拼成单个文件:
     * - AES-128 加密的分片在此用本地密钥解密(平台 Extractor 不解 HLS 加密)
     * - fMP4 流: 先写 init 段再追加 m4s
     */
    private static File buildConcatFile(List<Segment> segments, File partsDir) throws Exception {
        boolean fmp4 = false;
        for (Segment seg : segments) {
            if (seg.initLocalName != null) {
                fmp4 = true;
                break;
            }
        }
        File concat = new File(partsDir, fmp4 ? "concat.mp4" : "concat.ts");
        FileOutputStream out = new FileOutputStream(concat);
        try {
            if (fmp4) {
                String initName = segments.get(0).initLocalName;
                if (initName != null) {
                    writeFileTo(new File(partsDir, initName), out);
                }
            }
            for (Segment seg : segments) {
                File segFile = new File(partsDir, seg.localName);
                if (!segFile.exists() || segFile.length() <= 0) {
                    throw new IllegalStateException("分片缺失: " + seg.localName);
                }
                byte[] data = readFileBytes(segFile);
                if (seg.keyLocalName != null) {
                    byte[] key = readFileBytes(new File(partsDir, seg.keyLocalName));
                    if (key.length < 16) {
                        throw new IllegalStateException("密钥文件无效");
                    }
                    byte[] iv = new byte[16];
                    if (seg.iv != null) {
                        byte[] parsed = hexToBytes(seg.iv);
                        System.arraycopy(parsed, 0, iv, Math.max(0, 16 - parsed.length), Math.min(16, parsed.length));
                    } else {
                        long seq = seg.sequence;
                        for (int i = 15; i >= 8; i--) {
                            iv[i] = (byte) (seq & 0xFF);
                            seq >>>= 8;
                        }
                    }
                    javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(key, 0, 16, "AES");
                    javax.crypto.spec.IvParameterSpec ivSpec = new javax.crypto.spec.IvParameterSpec(iv);
                    javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec);
                    data = cipher.doFinal(data);
                }
                out.write(data);
            }
        } finally {
            out.close();
        }
        return concat;
    }

    private static byte[] readFileBytes(File file) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) Math.min(file.length(), Integer.MAX_VALUE / 2));
        FileInputStream fis = new FileInputStream(file);
        byte[] buf = new byte[64 * 1024];
        int len;
        while ((len = fis.read(buf)) != -1) {
            bos.write(buf, 0, len);
        }
        fis.close();
        return bos.toByteArray();
    }

    private static void writeFileTo(File src, FileOutputStream out) throws IOException {
        FileInputStream fis = new FileInputStream(src);
        byte[] buf = new byte[64 * 1024];
        int len;
        while ((len = fis.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        fis.close();
    }

    private static byte[] hexToBytes(String hex) {
        hex = hex.trim();
        if (hex.length() % 2 != 0) hex = "0" + hex;
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static String pickBestVariant(String content, String baseUrl) {
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = content.split(newline);
        long bestBandwidth = -1;
        String bestUrl = null;
        long currentBandwidth = -1;
        String base = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1);
        for (String line : lines) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                Matcher m = Pattern.compile("BANDWIDTH=(\\d+)").matcher(line);
                currentBandwidth = m.find() ? Long.parseLong(m.group(1)) : 0;
            } else if (line.length() > 0 && line.charAt(0) != '#') {
                if (currentBandwidth > bestBandwidth) {
                    bestBandwidth = currentBandwidth;
                    bestUrl = resolveUrl(base, line);
                }
                currentBandwidth = -1;
            }
        }
        return bestUrl;
    }

    private static String resolveUrl(String base, String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (url.startsWith("/")) {
            int i = base.indexOf('/', 9);
            return base.substring(0, i) + url;
        }
        return base + url;
    }

    private static String guessExt(String url, String def) {
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
        return def;
    }

    static boolean downloadToFile(String url, Map<String, String> headers, File target) {
        OkHttpClient client = OkGoHelper.getDefaultClient();
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
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            Response response = client.newCall(builder.build()).execute();
            if (!response.isSuccessful() || response.body() == null) {
                return false;
            }
            is = response.body().byteStream();
            fos = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            fos.close();
            fos = null;
            if (!tmp.renameTo(target)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Throwable th) {
            tmp.delete();
            return false;
        } finally {
            try {
                if (is != null) is.close();
            } catch (IOException ignored) {
            }
            try {
                if (fos != null) fos.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String fetchText(String url, Map<String, String> headers) throws IOException {
        OkHttpClient client = OkGoHelper.getDefaultClient();
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
        Response response = client.newCall(builder.build()).execute();
        if (response.body() == null) return null;
        String body = response.body().string();
        response.close();
        return body;
    }
}
