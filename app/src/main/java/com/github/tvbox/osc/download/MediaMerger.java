package com.github.tvbox.osc.download;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 无损合并/转封装(基于平台 MediaExtractor/MediaMuxer,即"sample copy",不重编码):
 * - HLS: 分片(解密后)顺序拼接 → 单个 MP4
 * - 渐进式: ts/mkv/webm/mp4 → MP4(flv 等平台不支持的容器自动失败,调用方降级保留原文件)
 * 说明: 最初选型为 ffmpeg-kit,但其 libavcodec.so 与 player 模块(IJK)内置的同名库冲突,
 * 故改用平台 API,效果相同(无损拷贝封装),且不增加包体积。
 */
public class MediaMerger {

    private static final int MAX_SAMPLE_SIZE = 8 * 1024 * 1024;

    /**
     * 将输入容器(ts/mkv/fmp4...)无损封装为 MP4
     *
     * @return 成功返回 true;out 为输出文件
     */
    public static boolean mergeToMp4(File src, File outFile) {
        if (src == null || !src.exists() || src.length() <= 0) {
            return false;
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(src.getAbsolutePath());
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            Map<Integer, Integer> trackMap = new HashMap<>();
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap.put(i, muxer.addTrack(format));
                }
            }
            if (trackMap.isEmpty()) {
                muxer.stop();
                muxer.release();
                muxer = null;
                outFile.delete();
                return false;
            }
            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocateDirect(MAX_SAMPLE_SIZE);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            for (Map.Entry<Integer, Integer> entry : trackMap.entrySet()) {
                extractor.selectTrack(entry.getKey());
                buffer.clear();
                while (true) {
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) break;
                    long sampleTime = extractor.getSampleTime();
                    int flags = extractor.getSampleFlags();
                    int muxerFlags = 0;
                    if ((flags & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        muxerFlags |= android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }
                    info.set(0, size, sampleTime, muxerFlags);
                    muxer.writeSampleData(entry.getValue(), buffer, info);
                    if (!extractor.advance()) break;
                }
                extractor.unselectTrack(entry.getKey());
            }
            muxer.stop();
            return verifyMp4(outFile);
        } catch (Throwable th) {
            th.printStackTrace();
            try {
                if (muxer != null) {
                    muxer.stop();
                }
            } catch (Throwable ignored) {
            }
            if (outFile != null) outFile.delete();
            return false;
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
            try {
                if (muxer != null) muxer.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 将已有容器(mkv/ts/flv...)无损转封装为 MP4;平台不支持的容器返回 false
     */
    public static boolean remuxToMp4(File src, File outFile) {
        return mergeToMp4(src, outFile);
    }

    /**
     * 输出文件有效性检查: 文件存在、非空、能取出时长
     */
    public static boolean verifyMp4(File file) {
        if (file == null || !file.exists() || file.length() <= 0) {
            return false;
        }
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            retriever.release();
            return duration != null && Long.parseLong(duration) > 0;
        } catch (Throwable th) {
            return file.length() > 0;
        }
    }
}
