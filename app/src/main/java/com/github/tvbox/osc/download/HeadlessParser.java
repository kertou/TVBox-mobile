package com.github.tvbox.osc.download;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.VideoParseRuler;

import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 无界面解析器:复刻 PlayFragment 的解析管线(initParse/doParse),把"需要在线解析"
 * (parse=1/jx=1)的集数解析成真实直链,让这类源也能应用内缓存。
 * json 解析(type1/2/3)是纯 HTTP 直接复用(请求走 PlayUrlResolver.fetchText 的
 * 带超时 GET);网页嗅探(type0/兜底)用不挂到界面的后台 WebView,
 * shouldInterceptRequest 命中视频格式即完成。
 * 阻塞式接口:调用线程(下载解析池)在 CountDownLatch 上等待,WebView/请求在主线程执行。
 * 发起任何请求前都经 isSafeHttpUrl 校验:仅 http/https,拒绝 localhost/环回/私有/保留地址。
 */
public class HeadlessParser {

    /** 单次网页嗅探超时,与播放端同量级 */
    private static final long SNIFF_TIMEOUT_MS = 30_000;
    /** 单集解析总兜底(spider 解析已耗 30 秒时,下载调度侧仍有界) */
    private static final long TOTAL_TIMEOUT_MS = 60_000;

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "headless-parse");
        t.setDaemon(true);
        return t;
    });

    public static class Result {
        public boolean ok;
        public String url;
        public HashMap<String, String> headers;
        public String errMsg;
    }

    private final SourceBean sourceBean;
    private String parseFlag;
    private String webUrl;
    private String webUserAgent;
    private Map<String, String> webHeaderMap;

    private WebView webView;
    private final Map<String, Boolean> loadedUrls = new HashMap<>();
    private final LinkedList<String> foundUrls = new LinkedList<>();
    private final HashMap<String, HashMap<String, String>> foundHeaders = new HashMap<>();
    private final AtomicInteger foundCount = new AtomicInteger(0);

    private final CountDownLatch latch = new CountDownLatch(1);
    private final Result result = new Result();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable sniffTimeout = () -> {
        completeFail(sniffTimeoutMsg());
        destroyWebView();
    };

    private HeadlessParser(SourceBean sourceBean) {
        this.sourceBean = sourceBean;
    }

    /**
     * @param flag    线路名(判定 vipParseFlags 用)
     * @param playUrl spider 返回的解析前缀(json:/parse:/解析站地址,可空)
     * @param url     spider 返回的集数地址(解析页入参)
     * @param jx      spider 标记的 jx(走用户配置的聚合解析)
     */
    public static Result resolve(SourceBean sourceBean, String flag, String playUrl, String url, boolean jx) {
        HeadlessParser parser = new HeadlessParser(sourceBean);
        return parser.run(flag, playUrl == null ? "" : playUrl, url, jx);
    }

    private Result run(String flag, String playUrl, String url, boolean jx) {
        parseFlag = flag;
        webUrl = url;
        try {
            ParseBean pb = chooseParseBean(playUrl, jx);
            if (pb == null) {
                completeFail("未配置可用解析接口");
            } else {
                dispatchParse(pb);
            }
        } catch (Throwable th) {
            th.printStackTrace();
            completeFail(String.format("解析失败:%s", th.getMessage()));
        }
        try {
            latch.await(TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completeFail("解析被取消");
        }
        destroyWebView();
        return result;
    }

    private static String sniffTimeoutMsg() {
        return String.format("网页嗅探超时(%d秒)", SNIFF_TIMEOUT_MS / 1000);
    }

    /**
     * 发请求前的 SSRF 校验:仅允许 http/https,host 不得为
     * localhost/环回/私有(10、172.16-31、192.168)/链路本地/保留地址段。
     */
    private static boolean isSafeHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Throwable th) {
            return false;
        }
        if (TextUtils.isEmpty(host)) return false;
        return !isRestrictedHost(host);
    }

    private static boolean isRestrictedHost(String host) {
        String h = host.toLowerCase();
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            return true;
        }
        if (h.contains(":")) { // IPv6 字面量:环回/未指定/链路本地/内网唯一(ULA)
            String v6 = h.replace("[", "").replace("]", "");
            return v6.equals("::1") || v6.equals("::") || v6.startsWith("fe80") || v6.startsWith("fc") || v6.startsWith("fd");
        }
        String[] parts = h.split("\\.");
        if (parts.length == 4 && isNumericParts(parts)) {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 0 || a == 10 || a == 127) return true;
            if (a == 192 && b == 168) return true;
            if (a == 172 && b >= 16 && b <= 31) return true;
            if (a == 169 && b == 254) return true;
            if (a == 100 && b >= 64 && b <= 127) return true;
        }
        return false;
    }

    private static boolean isNumericParts(String[] parts) {
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (char c : p.toCharArray()) {
                if (c < '0' || c > '9') return false;
            }
        }
        return true;
    }

    /** 与 PlayFragment.mObserverPlayResult → initParse 的 ParseBean 选择规则一致 */
    private ParseBean chooseParseBean(String playUrl, boolean jx) {
        boolean useParse = (TextUtils.isEmpty(playUrl) && ApiConfig.get().getVipParseFlags().contains(parseFlag)) || jx;
        if (useParse) {
            return ApiConfig.get().getDefaultParse();
        }
        if (playUrl.startsWith("json:")) {
            ParseBean pb = new ParseBean();
            pb.setType(1);
            pb.setUrl(playUrl.substring(5));
            return pb;
        }
        if (playUrl.startsWith("parse:")) {
            String parseRedirect = playUrl.substring(6);
            for (ParseBean p : ApiConfig.get().getParseBeanList()) {
                if (p.getName().equals(parseRedirect)) {
                    return p;
                }
            }
        }
        ParseBean pb = new ParseBean();
        pb.setType(0);
        pb.setUrl(playUrl);
        return pb;
    }

    private void dispatchParse(ParseBean pb) {
        if (pb.getType() == 0) { // 网页嗅探
            parseExtHeaders(pb);
            loadWebView(appendParam(pb.getUrl(), webUrl));
        } else if (pb.getType() == 1) { // json 解析
            String target = appendParam(pb.getUrl(), encodeUrl(webUrl));
            if (!isSafeHttpUrl(target)) {
                completeFail("解析接口地址不安全,已拒绝请求");
                return;
            }
            POOL.execute(() -> {
                // 复用 PlayUrlResolver 的带超时 GET(30 秒 callTimeout)
                String body = PlayUrlResolver.fetchText(target);
                if (TextUtils.isEmpty(body)) {
                    completeFail("解析接口请求失败");
                    return;
                }
                try {
                    JSONObject play = jsonParse(body);
                    complete(play.getString("url"), toHeaderMap(play.optJSONObject("header")));
                } catch (Throwable th) {
                    completeFail("解析接口返回无效");
                }
            });
        } else if (pb.getType() == 2) { // json 扩展
            POOL.execute(() -> {
                try {
                    LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
                    for (ParseBean p : ApiConfig.get().getParseBeanList()) {
                        if (p.getType() == 1) {
                            jxs.put(p.getName(), p.mixUrl());
                        }
                    }
                    handleJsonExtResult(ApiConfig.get().jsonExt(pb.getUrl(), jxs, webUrl));
                } catch (Throwable th) {
                    completeFail(String.format("解析失败:%s", th.getMessage()));
                }
            });
        } else if (pb.getType() == 3) { // json 聚合
            POOL.execute(() -> {
                try {
                    LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
                    String extendName = "";
                    for (ParseBean p : ApiConfig.get().getParseBeanList()) {
                        HashMap<String, String> data = new HashMap<>();
                        data.put("url", p.getUrl());
                        if (p.getUrl().equals(pb.getUrl())) {
                            extendName = p.getName();
                        }
                        data.put("type", String.valueOf(p.getType()));
                        data.put("ext", p.getExt());
                        jxs.put(p.getName(), data);
                    }
                    String mixFlag = String.format("%s111", parseFlag);
                    JSONObject rs = ApiConfig.get().jsonExtMix(mixFlag, pb.getUrl(), extendName, jxs, webUrl);
                    if (rs != null && rs.has("ua")) {
                        webUserAgent = rs.optString("ua").trim();
                    }
                    handleJsonExtResult(rs);
                } catch (Throwable th) {
                    completeFail(String.format("解析失败:%s", th.getMessage()));
                }
            });
        } else {
            completeFail("不支持的解析类型");
        }
    }

    /** 解析请求地址 = 解析接口前缀 + 集数地址(TVBox 解析协议约定直接拼接,播放端同款规则) */
    private static String appendParam(String prefix, String param) {
        return String.format("%s%s", prefix, param);
    }

    /** type2/3 jsonExt 结果:直接出直链则完成;仍要求解析(parse=1)则转网页嗅探 */
    private void handleJsonExtResult(JSONObject rs) {
        if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
            completeFail("解析接口未返回地址");
            return;
        }
        if (rs.optInt("parse", 0) == 1) {
            loadWebView(DefaultConfig.checkReplaceProxy(rs.optString("url", "")));
            return;
        }
        complete(rs.optString("url", ""), toHeaderMap(rs.optJSONObject("header")));
    }

    /** 与 PlayFragment.jsonParse 一致:data.url/url,//补协议,ua/referer 转 header */
    private JSONObject jsonParse(String json) throws Exception {
        JSONObject jsonPlayData = new JSONObject(json);
        String url;
        if (jsonPlayData.has("data")) {
            url = jsonPlayData.getJSONObject("data").getString("url");
        } else {
            url = jsonPlayData.getString("url");
        }
        if (url.startsWith("//")) {
            url = String.format("http:%s", url);
        }
        if (!url.startsWith("http")) {
            throw new IllegalStateException("非http地址");
        }
        JSONObject headers = new JSONObject();
        String ua = jsonPlayData.optString("user-agent", "");
        if (ua.trim().length() > 0) {
            headers.put("User-Agent", String.format(" %s", ua));
        }
        String referer = jsonPlayData.optString("referer", "");
        if (referer.trim().length() > 0) {
            headers.put("Referer", String.format(" %s", referer));
        }
        JSONObject taskResult = new JSONObject();
        taskResult.put("header", headers);
        taskResult.put("url", url);
        return taskResult;
    }

    /** type0 解析站 ext 里的 header:UA 单独给 WebView,其余作加载头 */
    private void parseExtHeaders(ParseBean pb) {
        if (pb.getExt() == null) return;
        try {
            JSONObject jsonObject = new JSONObject(pb.getExt());
            if (jsonObject.has("header")) {
                JSONObject headerJson = jsonObject.optJSONObject("header");
                HashMap<String, String> reqHeaders = new HashMap<>();
                Iterator<String> keys = headerJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (key.equalsIgnoreCase("user-agent")) {
                        webUserAgent = headerJson.getString(key).trim();
                    } else {
                        reqHeaders.put(key, headerJson.optString(key, ""));
                    }
                }
                if (reqHeaders.size() > 0) webHeaderMap = reqHeaders;
            }
        } catch (Throwable ignored) {
        }
    }

    private HashMap<String, String> toHeaderMap(JSONObject hds) {
        if (hds == null) return null;
        try {
            HashMap<String, String> headers = new HashMap<>();
            Iterator<String> keys = hds.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                headers.put(key, hds.getString(key));
            }
            return headers.isEmpty() ? null : headers;
        } catch (Throwable th) {
            return null;
        }
    }

    private String encodeUrl(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    private void loadWebView(String url) {
        if (!isSafeHttpUrl(url)) {
            completeFail("解析页地址不安全,已拒绝加载");
            return;
        }
        mainHandler.post(() -> {
            if (isDone()) return;
            try {
                if (webView == null) {
                    webView = new WebView(App.getInstance());
                    configWebView(webView);
                }
                webView.stopLoading();
                if (webUserAgent != null) {
                    webView.getSettings().setUserAgentString(webUserAgent);
                }
                if (webHeaderMap != null) {
                    webView.loadUrl(url, webHeaderMap);
                } else {
                    webView.loadUrl(url);
                }
                mainHandler.removeCallbacks(sniffTimeout);
                mainHandler.postDelayed(sniffTimeout, SNIFF_TIMEOUT_MS);
            } catch (Throwable th) {
                completeFail(String.format("WebView初始化失败:%s", th.getMessage()));
            }
        });
    }

    private void destroyWebView() {
        mainHandler.post(() -> {
            WebView wv = webView;
            webView = null;
            if (wv != null) {
                try {
                    wv.stopLoading();
                    wv.loadUrl("about:blank");
                    wv.removeAllViews();
                    wv.destroy();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configWebView(WebView wv) {
        // 与 PlayFragment.configWebViewSys 一致,但不挂到界面(1x1 挂载只为防 UI 残留,嗅探回调不依赖可见性)
        wv.setFocusable(false);
        wv.setFocusableInTouchMode(false);
        wv.clearFocus();
        WebSettings settings = wv.getSettings();
        settings.setNeedInitialFocus(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        settings.setBlockNetworkImage(true);
        settings.setUseWideViewPort(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDefaultTextEncodingName("utf-8");
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return false;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                return true;
            }
        });
        wv.setWebViewClient(new HeadlessWebClient());
        wv.setBackgroundColor(Color.BLACK);
    }

    private class HeadlessWebClient extends WebViewClient {

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView wv, SslErrorHandler handler, SslError error) {
            handler.proceed();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // 与播放端一致:按源配置的 clickSelector 自动点击播放按钮
            try {
                String click = sourceBean.getClickSelector();
                if (click == null || click.isEmpty()) return;
                String selector;
                if (click.contains(";")) {
                    if (!url.contains(click.split(";")[0])) return;
                    selector = click.split(";")[1];
                } else {
                    selector = click.trim();
                }
                selector = selector.replace("'", "");
                String js = String.format("$('%s').click();", selector);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    view.evaluateJavascript(js, null);
                } else {
                    view.loadUrl(String.format("javascript:%s", js));
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            HashMap<String, String> webHeaders = new HashMap<>();
            Map<String, String> hds = request.getRequestHeaders();
            if (hds != null && !hds.isEmpty()) {
                for (String k : hds.keySet()) {
                    if (k.equalsIgnoreCase("user-agent")
                            || k.equalsIgnoreCase("referer")
                            || k.equalsIgnoreCase("origin")) {
                        webHeaders.put(k, String.format(" %s", hds.get(k)));
                    }
                }
            }
            return checkIsVideo(url, webHeaders);
        }
    }

    private WebResourceResponse checkIsVideo(String url, HashMap<String, String> headers) {
        if (url.endsWith("/favicon.ico")) {
            if (url.startsWith("http://127.0.0.1")) {
                return new WebResourceResponse("image/x-icon", "UTF-8", null);
            }
            return null;
        }
        if (VideoParseRuler.isFilter(webUrl, url)) {
            return null;
        }
        boolean ad;
        if (!loadedUrls.containsKey(url)) {
            ad = AdBlocker.isAd(url);
            loadedUrls.put(url, ad);
        } else {
            ad = Boolean.TRUE.equals(loadedUrls.get(url));
        }
        if (!ad && isVideoUrl(url)) {
            foundUrls.add(url);
            foundHeaders.put(url, headers);
            if (foundCount.incrementAndGet() == 1) {
                String videoUrl = foundUrls.poll();
                HashMap<String, String> head = foundHeaders.get(videoUrl);
                if (head == null) head = new HashMap<>();
                destroyWebView();
                if (!isSafeHttpUrl(videoUrl)) {
                    completeFail("嗅探到的视频地址不安全,已拒绝");
                    return AdBlocker.createEmptyResource();
                }
                String cookie = CookieManager.getInstance().getCookie(videoUrl);
                if (!TextUtils.isEmpty(cookie)) head.put("Cookie", String.format(" %s", cookie));
                complete(videoUrl, head);
            }
        }
        return ad || foundCount.get() > 0 ? AdBlocker.createEmptyResource() : null;
    }

    /** 与 PlayFragment.checkVideoFormat 一致:type3 spider 可手动判定,否则走通用规则 */
    private boolean isVideoUrl(String url) {
        try {
            if (url.contains("url=http") || url.contains(".html")) {
                return false;
            }
            if (sourceBean.getType() == 3) {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                if (sp != null && sp.manualVideoCheck()) {
                    return sp.isVideoFormat(url);
                }
            }
            return VideoParseRuler.checkIsVideoForParse(webUrl, url);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean done = false;

    private synchronized boolean isDone() {
        return done;
    }

    private synchronized void complete(String url, HashMap<String, String> headers) {
        if (done) return;
        done = true;
        mainHandler.removeCallbacks(sniffTimeout);
        if (TextUtils.isEmpty(url) || !isSafeHttpUrl(url)) {
            result.ok = false;
            result.errMsg = String.format("播放地址类型不支持缓存:%s", url);
        } else {
            result.ok = true;
            result.url = url;
            result.headers = headers;
        }
        latch.countDown();
    }

    private synchronized void completeFail(String msg) {
        if (done) return;
        done = true;
        mainHandler.removeCallbacks(sniffTimeout);
        result.ok = false;
        result.errMsg = msg;
        latch.countDown();
    }
}
