package com.github.tvbox.osc.download;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 下载内容文件头嗅探:识别伪装成视频直链的 m3u8 播放列表与网页错误页。
 * 视频容器(mp4/mkv/ts/flv)的文件头都不会是 "#EXTM3U" 或 '<'。
 */
public class ContentSniff {

    /** 读文件头(最多 max 字节),读不到任何字节返回 null */
    public static byte[] sniff(File file, int max) {
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            byte[] buf = new byte[max];
            int off = 0;
            while (off < max) {
                int n = fis.read(buf, off, max - off);
                if (n < 0) break;
                off += n;
            }
            return off == 0 ? null : Arrays.copyOf(buf, off);
        } catch (IOException e) {
            return null;
        } finally {
            try {
                if (fis != null) fis.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** HLS 播放列表 */
    public static boolean isM3u8(byte[] head) {
        return head != null && head.length >= 7
                && head[0] == '#' && head[1] == 'E' && head[2] == 'X' && head[3] == 'T'
                && head[4] == 'M' && head[5] == '3' && head[6] == 'U';
    }

    /** HTML/错误页:跳过 BOM 与空白后以 '<' 开头 */
    public static boolean isHtml(byte[] head) {
        if (head == null || head.length == 0) return false;
        int i = 0;
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < head.length && (head[i] == ' ' || head[i] == '\t' || head[i] == '\r' || head[i] == '\n')) {
            i++;
        }
        return i < head.length && head[i] == '<';
    }
}
