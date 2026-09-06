# TVBox-Mobile

TVBox 竖屏手机增强版 —— 为手机单手操作打造的影视聚合播放器，内置**应用内离线缓存**，无网也能追剧。

> 🛠 **积极维护中**：本仓库处于积极维护状态，基本保持一周更新的频率，欢迎提交 Issue 反馈问题与建议。

## ✨ 两大亮点

### 📱 竖屏版

- 全程竖屏交互：底部导航、海报流、单手可达，区别于 TV 横屏盒子应用
- 浏览、选集、播放、缓存管理一屏完成，手机端原生体验

### ⬇️ 离线缓存到应用内部

像主流视频 App 一样把视频提前缓存到本地，离线随时看：

- **无网也能看**：地铁、飞机、弱网环境直接播放本地缓存，不占外部存储
- **不依赖外部下载器**：无需 1DM 等第三方工具，应用内选集一键缓存
- **私密安全**：存放于应用私有目录（`Android/data/<包名>/files/offline/`），写入 `.nomedia`，相册/媒体库不可见，卸载即清
- **无缝衔接**：点已缓存集自动优先播放本地；在线/离线观看进度互通，换入口也接着上次的位置看；离线播放同样写入观看历史

## 离线缓存功能详解

- 详情页"下载"即缓存选集弹窗：整剧全选 / 反选 / 仅当前集 / 自定义多选；保留 1DM 作为外部下载次选入口
- 双下载引擎：HLS（m3u8 + ts/fMP4，含 AES-128 加密流解密）与渐进式直链（mp4/mkv 等）
- 4 线程分片并发下载，断点续传精确到分片；支持暂停 / 继续 / 取消 / 重试
- 每集合并为**单个 MP4**（MediaExtractor/MediaMuxer 无损转封装）；转封装失败自动降级为"分片 + 本地索引"模式，依然可离线播放
- 前台服务 + 通知：常驻进度通知（总进度/速度，可暂停）、每集完成通知（点击进入"我的缓存"）
- "我的缓存"统一管理：按剧集分组展示进度/大小、离线播放（选集/上下集/自动连播）、单集与整剧删除、占用空间统计
- 详情页集数列表对已缓存集显示 ✓ 角标
- 首次使用缓存功能弹出免责声明，同意后不再提示
- 设置页"缓存仅 Wi-Fi 下载"开关（默认关闭，即允许移动网络下载）
- 「需要在线解析」（parse=1）的集数同样可缓存：后台解析管线（json 解析接口 / WebView 网页嗅探）解析出真实地址后下载，个别顽固解析页可能失败并提示原因
- 明确不支持并会明确提示：直播流（无 `#EXT-X-ENDLIST`）、`#EXT-X-BYTERANGE` 播放列表

## 兼容 TVBox 数据源

- 支持通过**订阅链接**导入 TVBox 通用格式的数据源配置，多订阅管理、单选切换
- 兼容 TVBox 生态的接口配置：普通影视源与 Spider 扩展源均可使用（Spider 依赖内置 QuickJS 运行时）
- 数据源完全由用户自行配置，应用本身不内置任何资源

## 基础功能

- 影视浏览 / 搜索 / 详情 / 选集播放（ExoPlayer / IJKPlayer 双内核）
- 直播、收藏、观看历史
- DLNA 投屏（协议栈以源码形式内置）
- 多订阅管理与数据源切换

## 构建

- 构建环境：JDK 17（Gradle 8.13 / AGP 8.13.2），`local.properties` 指向本机 Android SDK
- 当前仅产出 **arm64-v8a**
- 签名文件 `kertou-tvplayer.jks` 不入库（已在 .gitignore 排除）。构建前请自行生成签名并替换 `app/build.gradle` 中 signingConfigs 的别名与密码：

  `keytool -genkeypair -keystore kertou-tvplayer.jks -alias kertou -keypass 你的密码 -storepass 你的密码 -keyalg RSA -keysize 2048 -validity 10950`

- `./gradlew assembleDebug`

## 免责声明

- 本项目是一个本地播放器框架，**不包含、不提供任何影视资源或数据源接口**；应用内展示的一切内容均来自用户自行配置的订阅/数据源，开发者不对任何订阅内容负责
- 仅用于个人学习与技术交流，请勿传播或用于商业用途，请遵守所在地法律法规
- 本项目基于 **AGPL-3.0** 开源，发布 fork 时请保留 LICENSE 并注明来源

## 来源与致谢

- [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS)
- [XiaoRanLiu3119/TVBoxOS-Mobile](https://github.com/XiaoRanLiu3119/TVBoxOS-Mobile)
- UI 设计参考（Material 3 设计语言）：[jarnedemeulemeester/findroid](https://github.com/jarnedemeulemeester/findroid)（GPL-3.0，仅借鉴设计，未使用其代码）
- 参考项目：[takagen99/Box](https://github.com/takagen99/Box)、[FongMi/TV](https://github.com/FongMi/TV)
