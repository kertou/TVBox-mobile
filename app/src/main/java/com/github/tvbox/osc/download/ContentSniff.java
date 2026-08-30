package com.github.tvbox.osc.download;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 下载内容文件头嗅探:识别伪装成视频直链的 m3u8 播放列表与网页错误页。
 * 视频容器(mp4/mkv/ts/flv)的文件头都不会是 "#EXTM3U" 或 '<'。
 */
public class ContentSniff {

    /** 图片包裹检测的头部扫描窗口:占位图头极小(几十~几百字节),8KB 足够覆盖 */
    private static final int WRAPPER_SCAN_BYTES = 8 * 1024;

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

    /**
     * 图片文件头(防盗链占位图/图床假分片):JPEG/PNG/GIF/BMP/WebP(RIFF)/AVIF。
     * 只识别无歧义的特征头,TS(0x47) 与 fMP4(ftyp+isom/styp 等) 不会误伤,
     * AVIF 只按 avif/avis 精确 brand 匹配。
     */
    public static boolean isImageHead(byte[] head) {
        if (head == null || head.length < 4) return false;
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return true; // JPEG
        if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return true;    // PNG
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F') return true;                                // GIF
        if (head[0] == 'B' && head[1] == 'M') return true;                                                  // BMP
        if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') return true;              // RIFF/WebP
        if (head.length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p'
                && ((head[8] == 'a' && head[9] == 'v' && head[10] == 'i' && head[11] == 'f')
                || (head[8] == 'a' && head[9] == 'v' && head[10] == 'i' && head[11] == 's'))) return true;  // AVIF
        return false;
    }

    /** 下载内容是否为垃圾(图片/网页而非视频) */
    public static boolean isGarbageHead(byte[] head) {
        return isImageHead(head) || isHtml(head);
    }

    /**
     * 图片头包裹的真视频(防盗链伪装):实测某源把分片存成 "1x1 占位图头 + MPEG-TS 负载"
     * (PNG tEXt "TS_RAW" / BMP 头两种),播放器靠 TS 同步字节重同步照常播放,
     * 合并与校验则必须先剥掉图片头。定位负载起点并原位剥离;无视频负载返回 -1(纯占位图)。
     * 返回 0 表示本就无需处理。仅对命中图片魔数的文件做扫描,视频文件直接跳过。
     */
    public static int stripImageWrapper(File file) {
        byte[] head = sniff(file, WRAPPER_SCAN_BYTES);
        if (head == null || head.length < 4) return 0;
        if (!isImageHead(head)) return 0;
        int off = findVideoPayload(head);
        if (off < 0) return -1;
        if (off <= 0) return 0;
        File tmp = new File(file.getParentFile(), file.getName() + ".strip");
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(file);
            long skipped = in.skip(off);
            if (skipped < off) return -1;
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            return -1;
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (IOException ignored) {
            }
        }
        if (tmp.length() <= 0 || !tmp.renameTo(file)) {
            tmp.delete();
            return -1;
        }
        return off;
    }

    /** 在前几 KB 里定位视频负载起点:TS 取连续 4 个 188 间隔的同步字节,
     *  fMP4 取 ftyp/styp box;找不到返回 -1 */
    private static int findVideoPayload(byte[] b) {
        for (int i = 0; i + 188 * 3 + 1 < b.length; i++) {
            if (b[i] == 0x47 && b[i + 188] == 0x47 && b[i + 376] == 0x47 && b[i + 564] == 0x47) {
                return i;
            }
        }
        for (int i = 0; i + 8 <= b.length; i++) {
            if (b[i] == 'f' && b[i + 1] == 't' && b[i + 2] == 'y' && b[i + 3] == 'p') return i - 4 >= 0 ? i - 4 : 0;
            if (b[i] == 's' && b[i + 1] == 't' && b[i + 2] == 'y' && b[i + 3] == 'p') return i - 4 >= 0 ? i - 4 : 0;
        }
        return -1;
    }
}
