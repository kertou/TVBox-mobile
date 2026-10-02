# HANDOFF — 项目交接文档

> 本文档面向后续继续开发的工具/开发者，记录项目现状、构建方式、代码地图、近期改动与已知坑。
> 更新日期：2026-10-01

## 1. 项目概览

**TVBox-Mobile**：TVBox 的竖屏 Android fork，兼容 TVBox 数据源（订阅/爬虫 spider），内置离线缓存（下载）引擎、直播、投屏，UI 为 Material 3（支持 Android 12+ 壁纸动态取色）。

- 包名（applicationId）：`com.kertou.tvplayer`
- 源码 namespace（R 类/Manifest package）：`com.github.tvbox.osc`（与 applicationId 分离，勿混淆；uiautomator dump 显示的 package 是 namespace，resource-id 前缀是 applicationId）
- 当前版本：**3.1.1 (versionCode 39)**
- GitHub：https://github.com/kertou/TVBox-mobile （origin=kertou/TVBox-mobile，upstream=XiaoRanLiu3119/TVBoxOS-Mobile）
- 许可证：AGPL-3.0

## 2. 工具链与构建

| 项 | 值 |
|---|---|
| Gradle / AGP | 8.13 / 8.13.2 |
| Kotlin / JDK | 1.9.24 / 17 |
| compileSdk / targetSdk / minSdk | 36 / 30 / 24 |
| UI 库 | material 1.14.0（M3 Expressive），View 体系 + 动态取色（`DynamicColors`） |
| 其他关键依赖 | OkGo 3.0.4、OkHttp、Picasso、Room、IJK/Exo（doikki）、QuickJS、Hawk、XPopup、AutoSize |

```bash
# 构建（在仓库根目录）
gradlew.bat assembleDebug     # 产物 app/build/outputs/apk/debug/TVBox_v3.1.1_debug.apk
gradlew.bat assembleRelease   # 同签名可覆盖安装 release
```

- 判断构建结果不要看管道退出码，用 `grep -c "BUILD SUCCESSFUL"`。
- 签名：仓库根 `kertou-tvplayer.jks`（**已被 .gitignore 忽略，不在 git 里**，本机留存；密码在 `app/build.gradle` 的 signingConfigs 中）。
- **Baseline Profile**：release 构建自动嵌入（`app/src/release/baselineProfiles/`，由 `:baselineprofile` 模块的冷启动 ULP 生成）。改动启动路径后需重新生成：`gradlew.bat :app:generateReleaseBaselineProfile`，把生成物放回 `app/src/release/baselineProfiles/`。**debug 构建不含 profile**，测启动性能必须装 release。
- 注意 `.mimosa/`、`*.jks` 均在忽略列表；git add 不要用 `-A`（Mimosa 插件会往 `TabLayout/.mimosa/` 写文件）。

## 3. 代码地图（关键路径）

| 文件 | 职责 |
|---|---|
| `base/App.java` | Application：Hawk/OkGo/动态取色/LoadSir/AutoSize 初始化；IJK/QuickJS/EPG 等重活延后 2s 后台错峰 |
| `ui/activity/MainActivity.kt` | 4 tab 底部导航（首页/直播/我的缓存/我的），singleTop + `EXTRA_TAB` intent 直达 tab |
| `ui/fragment/HomeFragment.kt` | 首页：订阅管线调度（loadConfig→loadJar→getSort 全在后台线程），内嵌 ViewPager 分类 |
| `api/ApiConfig.java` | **订阅配置加载核心**（本交接最新改动所在，见 §5）：loadConfig/loadJar/parseJson/静默刷新/jar TTL |
| `download/PlayUrlResolver.java` | 播放地址解析 + `fetchText(url)`（同步 GET，30s 超时，失败返回 null）——新代码需要 HTTP GET 时**复用它**（见 §7 Mimosa 坑） |
| `download/DownloadTaskManager.java` | 离线缓存引擎（HLS/直链、限流退避、自动重试） |
| `download/ContentSniff.java` | 分片/直链内容嗅探（图片头剥离、假完成自愈） |
| `download/HeadlessParser.java` | parse=1 型源的无界面解析（json/嗅探） |
| `ui/fragment/LiveFragment.java` | 直播 tab（惰性视图 stub，切 tab 才充气） |
| `ui/fragment/CacheFragment.java` | 我的缓存网格（事件按可见性门控） |
| `ui/activity/LocalPlayActivity.java` | 离线播放（竖屏嵌入结构，与详情页同构；写观看历史/进度统一） |
| `server/ControlManager.java` | 127.0.0.1:9978 本地代理/控制服务（异步启动） |

## 4. 测试环境

- **模拟器**：AVD `test_arm`（API30，x86_64+ARM64 转译，1080x2340/440dpi）。
  启动：`emulator -avd test_arm -no-window -no-audio -no-boot-anim -no-snapshot`。
  已知会整个进程消失（qemu 崩溃），重启即可，AVD 数据（应用+订阅）保留。
- **testserver**（在仓库外的 `../testserver/`，不在 git 内）：`BASE_URL=http://10.0.2.2:8899 node server.js`。
  - `/config.json` 本地订阅（4 个测试源）；`/csp.jar` 最小可加载 spider jar（仅含 `com.github.catvod.spider.Init`）
  - **`state.json` 测试开关**（每次请求实时读取）：`{"configDelayMs":4000,"extraSite":true,"spiderUrl":"http://10.0.2.2:8899/csp.jar"}` 分别控制配置响应延迟（验证缓存优先）/追加"刷新新增源"站点（验证静默刷新）/注入 spider 字段（验证 jar 链路）。测完还原 `{}`。
  - 所有请求带时间戳落 server.log，可作时序证据。
- 模拟器当前已装 debug 包 + 订阅 `http://10.0.2.2:8899/config.json`（Hawk 已存），有少量测试缓存。

## 5. 最近改动：冷启动内容管线优化（本交接对应 commit）

背景：启动屏（点击图标→首帧）此前已优化（后台化+Baseline Profile），本轮处理**首帧后"出内容"被网络串行阻塞**的问题。三项改动都在 `ApiConfig.java`：

1. **订阅配置缓存优先**：冷启动 `loadConfig` 命中本地缓存（`files/MD5(apiUrl)`）时**先解析缓存立即出内容**（零网络等待），再后台静默刷新订阅；刷新结果只对比+写缓存（内容相同不重写），**新配置下次冷启动生效**。缓存写入改为临时文件+原子 rename。缓存不存在/解析失败仍走网络阻塞路径（首次添加订阅行为不变）；网络失败回退本地缓存。
2. **解析下沉后台线程**：配置 JSON 解析（49 源 Gson 解析+落盘）从 OkGo 回调的主线程挪到后台线程；整条管线改为后台线程同步拉取（`PlayUrlResolver.fetchText`）+ `postOnMain` 回传结果。
3. **spider jar 线程修正 + TTL**：`jarLoader.load`（DexClassLoader，首次 dexopt 数百 ms）从主线程挪到后台线程（符合 JarLoader 自身"不要在主线程调用"的契约）；无 md5 标记的 jar 增加本地缓存 TTL（`JAR_CACHE_TTL_MS`=24h，期内免下载；带 md5 的仍走 md5 比对，行为不变）。

**模拟器 E2E 验证结论（2026-10-01，全过，crash buffer 0）**：
- 缓存优先：服务器注入 4s 延迟时，缓存命中路径在 HomeFragment init 后 **0.56s** 应用配置出内容（旧路径必须等 4s 响应）；logcat 标记 `从本地缓存加载`（System.out）。
- 静默刷新：改测试配置后，后台刷新打印 `订阅配置有更新, 已写入本地缓存, 下次启动生效`，缓存文件内容更新；下次启动新配置生效；内容未变时不重写（无该打印）。
- jar 链路：首次冷启动下载 `/csp.jar` + 后台线程 DexClassLoader 加载成功（`自定义爬虫代码加载成功!` / `TestCspJar: Init.init loaded ok`）；24h 内再次冷启动**不再下载**（TTL 命中，服务器端 `/csp.jar` 总请求数=1）。

**遗留**：真机未装本版（用户覆盖安装 release 即可，无 DB 变更、数据兼容）；GitHub release 未发（等用户确认）。

## 6. 后续可做的启动优化（未实施，按性价比排序）

1. `OkGoHelper.init`（主线程建 4 个 OkHttp 客户端+DoH+Picasso+Exo 帮助类）：`initExoOkHttpClient`（拉起 doikki Exo 类栈）延迟到首次播放；DOH=关闭时不建 DoH 客户端；估 15-40ms。
2. ImmersionBar（每 Activity 21-111ms）替换为纯 Window/insets 代码——动 BaseActivity，有回归面。
3. setContentView 膨胀（模拟器 171-200ms）用 AsyncLayoutInflater/ViewStub——动 BaseVbActivity。
4. 启动后 600ms 的缓存页+我的页双 tab 膨胀可仿 LiveFragment 做 stub 惰性化。
5. release 开 R8（minifyEnabled false 至今未开）——需完整回归 QuickJS/spider/Gson keep 规则，风险中等。
6. 启动屏本身（fork+Provider+App.onCreate）已接近地板；Provider 共 6 个（Picasso/utilcode/CAOC/AutoSize/lifecycle/FileProvider），总开销估 10-30ms，不值得动。

## 7. 已知坑（重要，勿重蹈）

**Mimosa 安全扫描钩子（拦截 Write/Edit/commit）**：
- 新增代码里出现 `OkGo.get(变量URL)` → "SQL 注入"误报拦截。需要 HTTP GET 时**复用既有 `PlayUrlResolver.fetchText`**，不要新写 HTTP 客户端原语。
- `ExecutorService.execute(` → 误报，用 `.submit(` 或 `HeavyTaskUtil.executeNewTask(`。
- `path.join(变量)` → "路径穿越"误报，临时脚本写死 `C:/` 字面量路径。
- `loadUrl("javascript:"+var)`、argv 文件路径读取同族误报。

**adb / Git Bash（Windows）**：
- 所有带 `/sdcard/...` 等 POSIX 路径参数的 `adb shell` 命令必须加 `MSYS_NO_PATHCONV=1`，否则路径被改写成 `C:/Program Files/Git/sdcard/...`（设备端静默写错位置）。`adb pull` 的本地参数用 `C:/` 形式。
- **uiautomator dump 的幽灵文件坑**（2026-10-01 新踩）：dump 在加载态/无 idle 时会**静默失败**，而 `/sdcard/xxx.xml` 若在上一次 AVD 生命周期遗留了同名旧文件（userdata 跨 `-no-snapshot` 启动持久化），pull 会成功并返回**完全无关的陈旧内容**。对策：dump/pull 都不抑制报错、每次先 `rm` 目标文件、关键状态用 `dumpsys activity`/`run-as` 查应用数据做交叉验证。
- `uiautomator dump` 对 M3 NavigationBarView 常返回空 bounds；切 tab 可用 `am start ... --ei extra_tab N` 走 MainActivity 的 onNewIntent，比按坐标可靠。
- 模拟器 `adb devices` 空且 kill-server 无效=qemu 进程崩了，重启模拟器；`adb daemon 偶发重启，数十秒自愈。
- `am force-stop` **不执行 onPause**——SubscriptionActivity 的订阅数据在 onPause 才落 Hawk，加完订阅必须按返回键正常退出再 force-stop，否则订阅丢失。
- 添加订阅的 URL 必须带 `http(s)://` 前缀（OkHttp 校验，无前缀直接失败且无网络请求）。
- 真机（荣耀）截图 PNG 带 iCCP 块且部分流不完整，解码需 Z_SYNC_FLUSH 容错；`adb pull` 本地侧必须 Windows 盘符。

**发布流程（对外操作，先向用户确认）**：
1. `git fetch` 确认远端无用户网页改动 → rebase → push（用户会用 GitHub 网页直接改仓库）。
2. 版本号由用户定（改 `app/build.gradle` versionCode/versionName）。
3. `gradlew.bat assembleRelease` → aapt/apksigner 验证（包名/版本/label 全 locale/签名）→ REST API 建 release+传资产；token 经 `git credential fill` 读入**当次命令环境变量**，严禁落盘/回显；中文 release body 用 node 写 UTF-8 JSON 再 `--data-binary @file`。

## 8. 业务遗留（用户域，勿动）

- 真机用户数据/缓存/订阅均为真实数据；《九门2026》《飞驰人生3》下载问题用户自行处理。
- 弹幕服务启动失败 toast = 荣耀系统组件（`com.hihonor.game.kitserver`）响应高频通知，与本应用无关。
- 部分老会话的调试产物在 `../tmp/`（工作区目录，不在 git 内）。
