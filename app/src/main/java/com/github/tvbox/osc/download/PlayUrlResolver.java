package com.github.tvbox.osc.download;

import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.DefaultConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 后台解析某一集的真实播放地址(与 SourceViewModel.getPlay 的判定规则保持一致)。
 * 仅支持直连地址;需要网页嗅探(parse=1)的集数无法离线缓存。
 */
public class PlayUrlResolver {

    public static class Result {
        public boolean ok;
        public String url;
        public HashMap<String, String> headers;
        public String errMsg;

        static Result fail(String msg) {
            Result r = new Result();
            r.ok = false;
            r.errMsg = msg;
            return r;
        }

        static Result success(String url, HashMap<String, String> headers) {
            Result r = new Result();
            r.ok = true;
            r.url = url;
            r.headers = headers;
            return r;
        }
    }

    /**
     * @param sourceKey 数据源标识
     * @param flag      线路
     * @param rawUrl    详情接口给出的集数地址
     */
    public static Result resolve(String sourceKey, String flag, String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) {
            return Result.fail("集数地址为空");
        }
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            return Result.fail("数据源不存在或未启用");
        }
        try {
            int type = sourceBean.getType();
            JSONObject result = null;
            if (type == 3) {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                if (sp == null) {
                    return Result.fail("数据源插件加载失败");
                }
                String json = sp.playerContent(flag, rawUrl, ApiConfig.get().getVipParseFlags());
                if (TextUtils.isEmpty(json)) {
                    return Result.fail("数据源未返回播放信息");
                }
                result = new JSONObject(json);
            } else if (type == 0 || type == 1) {
                result = new JSONObject();
                String playUrl = sourceBean.getPlayerUrl().trim();
                if (DefaultConfig.isVideoFormat(rawUrl) && playUrl.isEmpty()) {
                    result.put("parse", 0);
                } else {
                    result.put("parse", 1);
                }
                result.put("url", rawUrl);
                result.put("playUrl", playUrl);
            } else if (type == 4) {
                okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(sourceBean.getApi());
                if (parsed == null) {
                    return Result.fail("扩展数据源地址无效");
                }
                okhttp3.HttpUrl url4 = parsed.newBuilder()
                        .addQueryParameter("play", rawUrl)
                        .addQueryParameter("flag", flag)
                        .build();
                String body = fetchText(url4.toString());
                if (TextUtils.isEmpty(body)) {
                    return Result.fail("扩展数据源返回为空");
                }
                result = new JSONObject(body);
            } else {
                return Result.fail("不支持缓存该类型数据源");
            }

            boolean parse = result.optString("parse", "1").equals("1");
            boolean jx = result.optString("jx", "0").equals("1");
            if (parse || jx) {
                return Result.fail("该集需要在线解析,暂不支持缓存");
            }
            String playUrl = result.optString("playUrl", "").trim();
            String url = result.getString("url");
            if (TextUtils.isEmpty(url)) {
                return Result.fail("未获取到播放地址");
            }
            url = playUrl + url;
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return Result.fail("播放地址类型不支持缓存:" + url);
            }
            return Result.success(url, parseHeaders(result));
        } catch (Throwable th) {
            th.printStackTrace();
            return Result.fail("解析失败:" + th.getMessage());
        }
    }

    private static HashMap<String, String> parseHeaders(JSONObject result) {
        HashMap<String, String> headers = null;
        try {
            if (result.has("header")) {
                JSONObject hds = new JSONObject(result.getString("header"));
                Iterator<String> keys = hds.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (headers == null) {
                        headers = new HashMap<>();
                    }
                    headers.put(key, hds.getString(key).trim());
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return headers;
    }

    private static String fetchText(String url) {
        try {
            okhttp3.OkHttpClient client = com.github.tvbox.osc.util.OkGoHelper.getDefaultClient();
            okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
            okhttp3.Response response = client.newCall(request).execute();
            if (response.body() == null) return null;
            String body = response.body().string();
            response.close();
            return body;
        } catch (Throwable th) {
            return null;
        }
    }

    public static String headersToJson(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        return new Gson().toJson(headers);
    }

    public static HashMap<String, String> headersFromJson(String json) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            return new Gson().fromJson(json, new TypeToken<HashMap<String, String>>() {
            }.getType());
        } catch (Throwable th) {
            return null;
        }
    }
}
