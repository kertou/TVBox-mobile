package com.github.tvbox.osc.api;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.crawler.JarLoader;
import com.github.catvod.crawler.JsLoader;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.download.PlayUrlResolver;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.IJKCode;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.AES;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HeavyTaskUtil;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class ApiConfig {
    private static ApiConfig instance;
    private LinkedHashMap<String, SourceBean> sourceBeanList;
    private SourceBean mHomeSource;
    private ParseBean mDefaultParse;
    private List<LiveChannelGroup> liveChannelGroupList;
    private List<ParseBean> parseBeanList;
    private List<String> vipParseFlags;
    private List<IJKCode> ijkCodes;
    private String spider = null;
    public String wallpaper = "";

    private SourceBean emptyHome = new SourceBean();

    private JarLoader jarLoader = new JarLoader();
    private JsLoader jsLoader = new JsLoader();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 无 md5 标记的 spider jar 本地缓存有效期: 期内直接用缓存免下载, 超期才重新拉取(带 md5 的走 md5 比对不受影响) */
    private static final long JAR_CACHE_TTL_MS = 24L * 60 * 60 * 1000;

    private String userAgent = "okhttp/3.15";

    private String requestAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9";

    private ApiConfig() {
        sourceBeanList = new LinkedHashMap<>();
        liveChannelGroupList = new ArrayList<>();
        parseBeanList = new ArrayList<>();
    }

    public static ApiConfig get() {
        if (instance == null) {
            synchronized (ApiConfig.class) {
                if (instance == null) {
                    instance = new ApiConfig();
                }
            }
        }
        return instance;
    }

    public static String FindResult(String json, String configKey) {
        String content = json;
        try {
            if (AES.isJson(content)) return content;
            Pattern pattern = Pattern.compile("[A-Za-z0]{8}\\*\\*");
            Matcher matcher = pattern.matcher(content);
            if(matcher.find()){
                content=content.substring(content.indexOf(matcher.group()) + 10);
                content = new String(Base64.decode(content, Base64.DEFAULT));
            }
            if (content.startsWith("2423")) {
                String data = content.substring(content.indexOf("2324") + 4, content.length() - 26);
                content = new String(AES.toBytes(content)).toLowerCase();
                String key = AES.rightPadding(content.substring(content.indexOf("$#") + 2, content.indexOf("#$")), "0", 16);
                String iv = AES.rightPadding(content.substring(content.length() - 13), "0", 16);
                json = AES.CBC(data, key, iv);
            }else if (configKey !=null && !AES.isJson(content)) {
                json = AES.ECB(content, configKey);
            }
            else{
                json = content;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    private static byte[] getImgJar(String body){
        Pattern pattern = Pattern.compile("[A-Za-z0]{8}\\*\\*");
        Matcher matcher = pattern.matcher(body);
        if(matcher.find()){
            body = body.substring(body.indexOf(matcher.group()) + 10);
            return Base64.decode(body, Base64.DEFAULT);
        }
        return "".getBytes();
    }

    public void loadConfig(boolean useCache, LoadConfigCallback callback, Activity activity) {
        // 防御: 拉取/解析含阻塞网络与磁盘 IO, 统一保证在后台线程执行
        // (唯一调用方 HomeFragment 自 6b10379 起已在后台任务线程派发, 此处兜底主线程直调)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            HeavyTaskUtil.executeNewTask(() -> loadConfig(useCache, callback, activity));
            return;
        }
        String apiUrl = Hawk.get(HawkConfig.API_URL, "");
        if (apiUrl.isEmpty()) {
            callback.error("-1");
            return;
        }
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/" + MD5.encode(apiUrl));
        if (useCache && cache.exists()) {
            try {
                parseJson(apiUrl, cache);
                callback.success();
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        String TempKey = null, configUrl = "", pk = ";pk;";
        if (apiUrl.contains(pk)) {
            String[] a = apiUrl.split(pk);
            TempKey = a[1];
            if (apiUrl.startsWith("clan")){
                configUrl = clanToAddress(a[0]);
            }else if (apiUrl.startsWith("http")){
                configUrl = a[0];
            }else {
                configUrl = "http://" + a[0];
            }
        } else if (apiUrl.startsWith("clan")) {
            configUrl = clanToAddress(apiUrl);
        } else if (!apiUrl.startsWith("http")) {
            configUrl = "http://" + configUrl;
        } else {
            configUrl = apiUrl;
        }
        String configKey = TempKey;
        // 冷启动缓存优先: 命中本地缓存先解析渲染(零网络等待, 弱网下此前出内容要串行等
        // 下载+主线程解析), 再后台静默刷新订阅——刷新结果只写缓存文件, 新配置下次冷启动生效
        if (!useCache && cache.exists()) {
            try {
                parseJson(apiUrl, cache);
                callback.success();
                refreshConfigQuietly(apiUrl, configUrl, configKey, cache);
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        fetchAndApplyConfig(apiUrl, configUrl, configKey, cache, callback);
    }


    public void loadJar(boolean useCache, String spider, LoadConfigCallback callback) {
        String[] urls = spider.split(";md5;");
        String jarUrl = urls[0];
        String md5 = urls.length > 1 ? urls[1].trim() : "";
        File cache = new File(App.getInstance().getFilesDir().getAbsolutePath() + "/csp.jar");

        if (cache.exists() && shouldUseCachedJar(useCache, md5, cache)) {
            // 本方法由 HomeFragment 的后台任务线程调用, DexClassLoader(首次 dexopt 可达数百 ms)
            // 在该线程执行, 符合 JarLoader"不要在主线程调用"的契约
            if (jarLoader.load(cache.getAbsolutePath())) {
                callback.success();
            } else {
                callback.error("");
            }
            return;
        }

        boolean isJarInImg = jarUrl.startsWith("img+");
        jarUrl = jarUrl.replace("img+", "");
        OkGo.<File>get(jarUrl)
                .headers("User-Agent", userAgent)
                .headers("Accept", requestAccept)
                .execute(new AbsCallback<File>() {

            @Override
            public File convertResponse(okhttp3.Response response) throws Throwable {
                File cacheDir = cache.getParentFile();
                if (!cacheDir.exists())
                    cacheDir.mkdirs();
                if (cache.exists())
                    cache.delete();
                FileOutputStream fos = new FileOutputStream(cache);
                if(isJarInImg) {
                    String respData = response.body().string();
                    byte[] imgJar = getImgJar(respData);
                    fos.write(imgJar);
                } else {
                    fos.write(response.body().bytes());
                }
                fos.flush();
                fos.close();
                return cache;
            }

            @Override
            public void onSuccess(Response<File> response) {
                // OkGo 回调固定回主线程; DexClassLoader 加载挪到后台线程(修复此前主线程加载)
                File jar = response.body();
                if (!jar.exists()) {
                    callback.error("");
                    return;
                }
                HeavyTaskUtil.executeNewTask(() -> {
                    boolean ok = jarLoader.load(jar.getAbsolutePath());
                    postOnMain(() -> {
                        if (ok) callback.success();
                        else callback.error("");
                    });
                });
            }

            @Override
            public void onError(Response<File> response) {
                super.onError(response);
                callback.error("");
            }
        });
    }

    /** 阻塞拉取订阅配置并应用(必须后台线程); 失败时回退本地缓存, 全部失败才报错回主线程 */
    private void fetchAndApplyConfig(String apiUrl, String configUrl, String configKey, File cache, LoadConfigCallback callback) {
        String json = PlayUrlResolver.fetchText(configUrl);
        if (json != null && !json.isEmpty()) {
            try {
                json = readConfigBody(apiUrl, configKey, json);
                parseJson(apiUrl, json);
                saveConfigCache(cache, json);
                postOnMain(callback::success);
                return;
            } catch (Throwable th) {
                th.printStackTrace();
                postOnMain(() -> callback.error("解析配置失败"));
                return;
            }
        }
        // 网络失败: 本地缓存兜底(与既有离线场景行为一致)
        if (cache.exists()) {
            try {
                parseJson(apiUrl, cache);
                postOnMain(callback::success);
                return;
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        postOnMain(() -> callback.error("拉取配置失败\n网络不可用或配置为空"));
    }

    /** 冷启动缓存命中后的静默刷新: 只对比+写缓存, 不触碰本会话已加载的配置状态(新配置下次启动生效) */
    private void refreshConfigQuietly(String apiUrl, String configUrl, String configKey, File cache) {
        HeavyTaskUtil.executeNewTask(() -> {
            try {
                String json = PlayUrlResolver.fetchText(configUrl);
                if (json == null || json.isEmpty()) return;
                json = readConfigBody(apiUrl, configKey, json);
                String old = readTextFile(cache);
                if (old != null && old.trim().equals(json.trim())) return;
                saveConfigCache(cache, json);
                System.out.println("订阅配置有更新, 已写入本地缓存, 下次启动生效");
            } catch (Throwable th) {
                th.printStackTrace();
            }
        });
    }

    private String readConfigBody(String apiUrl, String configKey, String content) {
        String result = content == null ? "" : content;
        result = FindResult(result, configKey);
        if (apiUrl.startsWith("clan")) {
            result = clanContentFix(clanToAddress(apiUrl), result);
        }
        //假相對路徑
        result = fixContentPath(apiUrl, result);
        return result;
    }

    private boolean shouldUseCachedJar(boolean useCache, String md5, File cache) {
        if (useCache) return true;
        if (!md5.isEmpty()) return MD5.getFileMd5(cache).equalsIgnoreCase(md5);
        return System.currentTimeMillis() - cache.lastModified() < JAR_CACHE_TTL_MS;
    }

    /** 先写临时文件再原子改名: 避免后台刷新写缓存与读取方并发时读到半截文件 */
    private void saveConfigCache(File cache, String json) {
        try {
            File cacheDir = cache.getParentFile();
            if (!cacheDir.exists())
                cacheDir.mkdirs();
            File tmp = new File(cache.getAbsolutePath() + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(json.getBytes("UTF-8"));
            fos.flush();
            fos.close();
            if (cache.exists())
                cache.delete();
            tmp.renameTo(cache);
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private String readTextFile(File f) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = reader.readLine()) != null) {
                sb.append(s).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Throwable th) {
            return null;
        }
    }

    private void postOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    private void parseJson(String apiUrl, File f) throws Throwable {
        System.out.println("从本地缓存加载" + f.getAbsolutePath());
        BufferedReader bReader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String s = "";
        while ((s = bReader.readLine()) != null) {
            sb.append(s + "\n");
        }
        bReader.close();
        parseJson(apiUrl, sb.toString());
    }

    private void parseJson(String apiUrl, String jsonStr) {
        JsonObject infoJson = new Gson().fromJson(jsonStr, JsonObject.class);
        // spider
        spider = DefaultConfig.safeJsonString(infoJson, "spider", "");
        // wallpaper
        wallpaper = DefaultConfig.safeJsonString(infoJson, "wallpaper", "");
        // 远端站点源
        SourceBean firstSite = null;
        if (sourceBeanList!= null)
            sourceBeanList.clear();
        for (JsonElement opt : infoJson.get("sites").getAsJsonArray()) {
            JsonObject obj = (JsonObject) opt;
            SourceBean sb = new SourceBean();
            String siteKey = obj.get("key").getAsString().trim();
            sb.setKey(siteKey);
            sb.setName(obj.get("name").getAsString().trim());
            sb.setType(obj.get("type").getAsInt());
            sb.setApi(obj.get("api").getAsString().trim());
            sb.setSearchable(DefaultConfig.safeJsonInt(obj, "searchable", 1));
            sb.setQuickSearch(DefaultConfig.safeJsonInt(obj, "quickSearch", 1));
            sb.setFilterable(DefaultConfig.safeJsonInt(obj, "filterable", 1));
            sb.setPlayerUrl(DefaultConfig.safeJsonString(obj, "playUrl", ""));
            if(obj.has("ext") && (obj.get("ext").isJsonObject() || obj.get("ext").isJsonArray())){
                sb.setExt(obj.get("ext").toString());
            }else {
                sb.setExt(DefaultConfig.safeJsonString(obj, "ext", ""));
            }
            sb.setJar(DefaultConfig.safeJsonString(obj, "jar", ""));
            sb.setPlayerType(DefaultConfig.safeJsonInt(obj, "playerType", -1));
            sb.setCategories(DefaultConfig.safeJsonStringList(obj, "categories"));
            sb.setClickSelector(DefaultConfig.safeJsonString(obj, "click", ""));
            if (firstSite == null)
                firstSite = sb;
            sourceBeanList.put(siteKey, sb);
        }
        if (sourceBeanList != null && sourceBeanList.size() > 0) {
            String home = Hawk.get(HawkConfig.HOME_API, "");
            SourceBean sh = getSource(home);
            if (sh == null)
                setSourceBean(firstSite);
            else
                setSourceBean(sh);
        }
        // 需要使用vip解析的flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags");
        // 解析地址
        parseBeanList.clear();
        if(infoJson.has("parses")){
            JsonArray parses = infoJson.get("parses").getAsJsonArray();
            for (JsonElement opt : parses) {
                JsonObject obj = (JsonObject) opt;
                ParseBean pb = new ParseBean();
                pb.setName(obj.get("name").getAsString().trim());
                pb.setUrl(obj.get("url").getAsString().trim());
                String ext = obj.has("ext") ? obj.get("ext").getAsJsonObject().toString() : "";
                pb.setExt(ext);
                pb.setType(DefaultConfig.safeJsonInt(obj, "type", 0));
                parseBeanList.add(pb);
            }
        }
        // 获取默认解析
        if (parseBeanList != null && parseBeanList.size() > 0) {
            String defaultParse = Hawk.get(HawkConfig.DEFAULT_PARSE, "");
            if (!TextUtils.isEmpty(defaultParse))
                for (ParseBean pb : parseBeanList) {
                    if (pb.getName().equals(defaultParse))
                        setDefaultParse(pb);
                }
            if (mDefaultParse == null)
                setDefaultParse(parseBeanList.get(0));
        }
        // 直播源
        liveChannelGroupList.clear();           //修复从后台切换重复加载频道列表
        String liveURL = Hawk.get(HawkConfig.LIVE_URL, "");
        //String epgURL  = Hawk.get(HawkConfig.EPG_URL, "");

        String liveURL_final = null;
        try {
            if (infoJson.has("lives") && infoJson.get("lives").getAsJsonArray() != null) {
                JsonObject livesOBJ = infoJson.get("lives").getAsJsonArray().get(0).getAsJsonObject();
                String lives = livesOBJ.toString();
                int index = lives.indexOf("proxy://");
                if (index != -1) {
                    int endIndex = lives.lastIndexOf("\"");
                    String url = lives.substring(index, endIndex);
                    url = DefaultConfig.checkReplaceProxy(url);

                    //clan
                    String extUrl = Uri.parse(url).getQueryParameter("ext");
                    if (extUrl != null && !extUrl.isEmpty()) {
                        String extUrlFix;
                        if (extUrl.startsWith("http") || extUrl.startsWith("clan://")) {
                            extUrlFix = extUrl;
                        } else {
                            extUrlFix = new String(Base64.decode(extUrl, Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                        }
                        if (extUrlFix.startsWith("clan://")) {
                            extUrlFix = clanContentFix(clanToAddress(apiUrl), extUrlFix);
                        }

                        // takagen99: Capture Live URL into Config
                        System.out.println("Live URL :" + extUrlFix);
                        putLiveHistory(extUrlFix);
                        // Overwrite with Live URL from Settings
                        if (!StringUtils.isBlank(liveURL)) {
                            extUrlFix = liveURL;
                        }

                        // Final Live URL
                        liveURL_final = extUrlFix;

//                    // Encoding the Live URL
//                    extUrlFix = Base64.encodeToString(extUrlFix.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
//                    url = url.replace(extUrl, extUrlFix);
                    }

                    // takagen99 : Getting EPG URL from File Config & put into Settings
                    if (livesOBJ.has("epg")) {
                        String epg = livesOBJ.get("epg").getAsString();
                        System.out.println("EPG URL :" + epg);
                        //putEPGHistory(epg);
                        // Overwrite with EPG URL from Settings
                        //if (StringUtils.isBlank(epgURL)) {
                            Hawk.put(HawkConfig.EPG_URL, epg);
//                        } else {
//                            Hawk.put(HawkConfig.EPG_URL, epgURL);
//                        }
                    }

//                // Populate Live Channel Listing
//                LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
//                liveChannelGroup.setGroupName(url);
//                liveChannelGroupList.add(liveChannelGroup);

                } else {

                    // if FongMi Live URL Formatting exists
                    if (!lives.contains("type")) {
                        loadLives(infoJson.get("lives").getAsJsonArray());
                    } else {
                        JsonObject fengMiLives = infoJson.get("lives").getAsJsonArray().get(0).getAsJsonObject();
                        String type = fengMiLives.get("type").getAsString();
                        if (type.equals("0")) {
                            String url = fengMiLives.get("url").getAsString();

                            // takagen99 : Getting EPG URL from File Config & put into Settings
                            if (fengMiLives.has("epg")) {
                                String epg = fengMiLives.get("epg").getAsString();
                                System.out.println("EPG URL :" + epg);
                                //putEPGHistory(epg);
                                // Overwrite with EPG URL from Settings
                                //if (StringUtils.isBlank(epgURL)) {
                                    Hawk.put(HawkConfig.EPG_URL, epg);
//                                } else {
//                                    Hawk.put(HawkConfig.EPG_URL, epgURL);
//                                }
                            }

                            if (url.startsWith("http")) {
                                // takagen99: Capture Live URL into Settings
                                System.out.println("Live URL :" + url);
                                putLiveHistory(url);
                                // Overwrite with Live URL from Settings
                                if (!StringUtils.isBlank(liveURL)) {
                                    url = liveURL;
                                }

                                // Final Live URL
                                liveURL_final = url;

//                            url = Base64.encodeToString(url.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                            }
                        }
                    }
                }

                // takagen99: Load Live Channel from settings URL (WIP)
                if (StringUtils.isBlank(liveURL_final)) {
                    liveURL_final = liveURL;
                }
                liveURL_final = Base64.encodeToString(liveURL_final.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                liveURL_final = "http://127.0.0.1:9978/proxy?do=live&type=txt&ext=" + liveURL_final;
                LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
                liveChannelGroup.setGroupName(liveURL_final);
                liveChannelGroupList.add(liveChannelGroup);
            }


        } catch (Throwable th) {
            th.printStackTrace();
        }


        //video parse rule for host
        if (infoJson.has("rules")) {
            VideoParseRuler.clearRule();
            for(JsonElement oneHostRule : infoJson.getAsJsonArray("rules")) {
                JsonObject obj = (JsonObject) oneHostRule;
                if (obj.has("host")) {
                    String host = obj.get("host").getAsString();
                    if (obj.has("rule")) {
                        JsonArray ruleJsonArr = obj.getAsJsonArray("rule");
                        ArrayList<String> rule = new ArrayList<>();
                        for (JsonElement one : ruleJsonArr) {
                            String oneRule = one.getAsString();
                            rule.add(oneRule);
                        }
                        if (rule.size() > 0) {
                            VideoParseRuler.addHostRule(host, rule);
                        }
                    }
                    if (obj.has("filter")) {
                        JsonArray filterJsonArr = obj.getAsJsonArray("filter");
                        ArrayList<String> filter = new ArrayList<>();
                        for (JsonElement one : filterJsonArr) {
                            String oneFilter = one.getAsString();
                            filter.add(oneFilter);
                        }
                        if (filter.size() > 0) {
                            VideoParseRuler.addHostFilter(host, filter);
                        }
                    }
                }
                if (obj.has("hosts") && obj.has("regex")) {
                    ArrayList<String> rule = new ArrayList<>();
                    JsonArray regexArray = obj.getAsJsonArray("regex");
                    for (JsonElement one : regexArray) {
                        rule.add(one.getAsString());
                    }

                    JsonArray array = obj.getAsJsonArray("hosts");
                    for (JsonElement one : array) {
                        String host = one.getAsString();
                        VideoParseRuler.addHostRule(host, rule);
                    }
                }
            }
        }

        String defaultIJKADS="{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson=new Gson().fromJson(defaultIJKADS, JsonObject.class);
        // 广告地址
        if(AdBlocker.isEmpty()){
            //默认广告拦截
            for (JsonElement host : defaultJson.getAsJsonArray("ads")) {
                AdBlocker.addAdHost(host.getAsString());
            }
            //追加的广告拦截
            if(infoJson.has("ads")){
                for (JsonElement host : infoJson.getAsJsonArray("ads")) {
                    if(!AdBlocker.hasHost(host.getAsString())){
                        AdBlocker.addAdHost(host.getAsString());
                    }
                }
            }
        }
        // IJK解码配置
        if(ijkCodes==null){
            ijkCodes = new ArrayList<>();
            boolean foundOldSelect = false;
            String ijkCodec = Hawk.get(HawkConfig.IJK_CODEC, "");
            JsonArray ijkJsonArray = infoJson.has("ijk")?infoJson.get("ijk").getAsJsonArray():defaultJson.get("ijk").getAsJsonArray();
            for (JsonElement opt : ijkJsonArray) {
                JsonObject obj = (JsonObject) opt;
                String name = obj.get("group").getAsString();
                LinkedHashMap<String, String> baseOpt = new LinkedHashMap<>();
                for (JsonElement cfg : obj.get("options").getAsJsonArray()) {
                    JsonObject cObj = (JsonObject) cfg;
                    String key = cObj.get("category").getAsString() + "|" + cObj.get("name").getAsString();
                    String val = cObj.get("value").getAsString();
                    baseOpt.put(key, val);
                }
                IJKCode codec = new IJKCode();
                codec.setName(name);
                codec.setOption(baseOpt);
                if (name.equals(ijkCodec) || TextUtils.isEmpty(ijkCodec)) {
                    codec.selected(true);
                    ijkCodec = name;
                    foundOldSelect = true;
                } else {
                    codec.selected(false);
                }
                ijkCodes.add(codec);
            }
            if (!foundOldSelect && ijkCodes.size() > 0) {
                ijkCodes.get(0).selected(true);
            }
        }
    }

    private void putLiveHistory(String url) {
        if (!url.isEmpty()) {
            ArrayList<String> liveHistory = Hawk.get(HawkConfig.LIVE_HISTORY, new ArrayList<String>());
            if (!liveHistory.contains(url))
                liveHistory.add(0, url);
            if (liveHistory.size() > 20)
                liveHistory.remove(20);
            Hawk.put(HawkConfig.LIVE_HISTORY, liveHistory);
        }
    }

    public void loadLives(JsonArray livesArray) {
        liveChannelGroupList.clear();
        int groupIndex = 0;
        int channelIndex = 0;
        int channelNum = 0;
        for (JsonElement groupElement : livesArray) {
            LiveChannelGroup liveChannelGroup = new LiveChannelGroup();
            liveChannelGroup.setLiveChannels(new ArrayList<LiveChannelItem>());
            liveChannelGroup.setGroupIndex(groupIndex++);
            String groupName = ((JsonObject) groupElement).get("group").getAsString().trim();
            String[] splitGroupName = groupName.split("_", 2);
            liveChannelGroup.setGroupName(splitGroupName[0]);
            if (splitGroupName.length > 1)
                liveChannelGroup.setGroupPassword(splitGroupName[1]);
            else
                liveChannelGroup.setGroupPassword("");
            channelIndex = 0;
            for (JsonElement channelElement : ((JsonObject) groupElement).get("channels").getAsJsonArray()) {
                JsonObject obj = (JsonObject) channelElement;
                LiveChannelItem liveChannelItem = new LiveChannelItem();
                liveChannelItem.setChannelName(obj.get("name").getAsString().trim());
                liveChannelItem.setChannelIndex(channelIndex++);
                liveChannelItem.setChannelNum(++channelNum);
                ArrayList<String> urls = DefaultConfig.safeJsonStringList(obj, "urls");
                ArrayList<String> sourceNames = new ArrayList<>();
                ArrayList<String> sourceUrls = new ArrayList<>();
                int sourceIndex = 1;
                for (String url : urls) {
                    String[] splitText = url.split("\\$", 2);
                    sourceUrls.add(splitText[0]);
                    if (splitText.length > 1)
                        sourceNames.add(splitText[1]);
                    else
                        sourceNames.add("源" + Integer.toString(sourceIndex));
                    sourceIndex++;
                }
                liveChannelItem.setChannelSourceNames(sourceNames);
                liveChannelItem.setChannelUrls(sourceUrls);
                liveChannelGroup.getLiveChannels().add(liveChannelItem);
            }
            liveChannelGroupList.add(liveChannelGroup);
        }
    }

    public String getSpider() {
        return spider;
    }

    public Spider getCSP(SourceBean sourceBean) {
        boolean js = sourceBean.getApi().endsWith(".js") || sourceBean.getApi().contains(".js?");
        if (js) return jsLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
        return jarLoader.getSpider(sourceBean.getKey(), sourceBean.getApi(), sourceBean.getExt(), sourceBean.getJar());
    }

    public Object[] proxyLocal(Map param) {
        return jarLoader.proxyInvoke(param);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    public interface LoadConfigCallback {
        void success();

        void retry();

        void error(String msg);
    }

    public interface FastParseCallback {
        void success(boolean parse, String url, Map<String, String> header);

        void fail(int code, String msg);
    }

    public SourceBean getSource(String key) {
        if (!sourceBeanList.containsKey(key))
            return null;
        return sourceBeanList.get(key);
    }

    public void setSourceBean(SourceBean sourceBean) {
        this.mHomeSource = sourceBean;
        Hawk.put(HawkConfig.HOME_API, sourceBean.getKey());
    }

    public void setDefaultParse(ParseBean parseBean) {
        if (this.mDefaultParse != null)
            this.mDefaultParse.setDefault(false);
        this.mDefaultParse = parseBean;
        Hawk.put(HawkConfig.DEFAULT_PARSE, parseBean.getName());
        parseBean.setDefault(true);
    }

    public ParseBean getDefaultParse() {
        return mDefaultParse;
    }

    public List<SourceBean> getSourceBeanList() {
        return new ArrayList<>(sourceBeanList.values());
    }

    public List<ParseBean> getParseBeanList() {
        return parseBeanList;
    }

    public List<String> getVipParseFlags() {
        return vipParseFlags;
    }

    public SourceBean getHomeSourceBean() {
        return mHomeSource == null ? emptyHome : mHomeSource;
    }

    public List<LiveChannelGroup> getChannelGroupList() {
        return liveChannelGroupList;
    }

    /**
     * 避免离线(订阅未配置成功时),parseJson未调用,ijkCodes未初始化报空指针
     * @return
     */
    private List<IJKCode> offlineGetIjkCodes() {

        String defaultIJKADS = "{\"ijk\":[{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"0\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"软解码\"},{\"options\":[{\"name\":\"opensles\",\"category\":4,\"value\":\"0\"},{\"name\":\"framedrop\",\"category\":4,\"value\":\"1\"},{\"name\":\"soundtouch\",\"category\":4,\"value\":\"1\"},{\"name\":\"start-on-prepared\",\"category\":4,\"value\":\"1\"},{\"name\":\"http-detect-rangeupport\",\"category\":1,\"value\":\"0\"},{\"name\":\"fflags\",\"category\":1,\"value\":\"fastseek\"},{\"name\":\"skip_loop_filter\",\"category\":2,\"value\":\"48\"},{\"name\":\"reconnect\",\"category\":4,\"value\":\"1\"},{\"name\":\"enable-accurate-seek\",\"category\":4,\"value\":\"0\"},{\"name\":\"mediacodec\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-all-videos\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-auto-rotate\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-handle-resolution-change\",\"category\":4,\"value\":\"1\"},{\"name\":\"mediacodec-hevc\",\"category\":4,\"value\":\"1\"},{\"name\":\"max-buffer-size\",\"category\":4,\"value\":\"15728640\"}],\"group\":\"硬解码\"}],\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}";
        JsonObject defaultJson = new Gson().fromJson(defaultIJKADS, JsonObject.class);

        List<IJKCode> ijkCodes = new ArrayList<>();
        boolean foundOldSelect = false;
        String ijkCodec = Hawk.get(HawkConfig.IJK_CODEC, "");
        JsonArray ijkJsonArray = defaultJson.get("ijk").getAsJsonArray();
        for (JsonElement opt : ijkJsonArray) {
            JsonObject obj = (JsonObject) opt;
            String name = obj.get("group").getAsString();
            LinkedHashMap<String, String> baseOpt = new LinkedHashMap<>();
            for (JsonElement cfg : obj.get("options").getAsJsonArray()) {
                JsonObject cObj = (JsonObject) cfg;
                String key = cObj.get("category").getAsString() + "|" + cObj.get("name").getAsString();
                String val = cObj.get("value").getAsString();
                baseOpt.put(key, val);
            }
            IJKCode codec = new IJKCode();
            codec.setName(name);
            codec.setOption(baseOpt);
            if (name.equals(ijkCodec) || TextUtils.isEmpty(ijkCodec)) {
                codec.selected(true);
                ijkCodec = name;
                foundOldSelect = true;
            } else {
                codec.selected(false);
            }
            ijkCodes.add(codec);
        }
        if (!foundOldSelect && ijkCodes.size() > 0) {
            ijkCodes.get(0).selected(true);
        }
        return ijkCodes;
    }

    /**
     * 订阅成功还是用拉取的ijk解码配置,未拉取成功时用默认的
     * @return
     */
    public List<IJKCode> getIjkCodes() {
        return ijkCodes==null?offlineGetIjkCodes():ijkCodes;
    }

    public IJKCode getCurrentIJKCode() {
        String codeName = Hawk.get(HawkConfig.IJK_CODEC, "");
        return getIJKCodec(codeName);
    }

    public IJKCode getIJKCodec(String name) {
        for (IJKCode code : getIjkCodes()) {
            if (code.getName().equals(name))
                return code;
        }
        return ijkCodes.get(0);
    }

    String clanToAddress(String lanLink) {
        if (lanLink.startsWith("clan://localhost/")) {
            return lanLink.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/");
        } else {
            String link = lanLink.substring(7);
            int end = link.indexOf('/');
            return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1);
        }
    }

    String clanContentFix(String lanLink, String content) {
        String fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6);
        return content.replace("clan://", fix);
    }

    String fixContentPath(String url, String content) {
        if (content.contains("\"./")) {
            if(!url.startsWith("http") && !url.startsWith("clan://")){
                url = "http://" + url;
            }
            if(url.startsWith("clan://"))url=clanToAddress(url);
            content = content.replace("./", url.substring(0,url.lastIndexOf("/") + 1));
        }
        return content;
    }
}
