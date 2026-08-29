# TVBoxMobile

基于
* [q215613905](https://github.com/q215613905)/[TVBoxOS](https://github.com/q215613905/TVBoxOS)   
* [XiaoRanLiu3119](https://github.com/XiaoRanLiu3119)/[TVBoxOS-Mobile](https://github.com/XiaoRanLiu3119/TVBoxOS-Mobile)

## Build
[Github Actions](https://github.com/XiaoRanLiu3119/MBox-Build/actions)   

精力有限,未必会及时维护,仅用于学习
   
## 推荐使用   
[takagen99](https://github.com/takagen99/Box)   
[FongMi](https://github.com/FongMi/TV)   

## 本仓库修改内容(应用内离线缓存)

在原 MBox 基础上新增 bilibili 式的**应用内视频缓存**(离线观看),遵循 AGPL-3.0 一并开源:

- 详情页"下载"按钮改为应用内缓存选集弹窗:支持整剧全选 / 反选 / 仅当前集 / 自定义多集;底部保留"用 1DM 下载当前集"作为外部下载器的次选入口
- 下载引擎:HLS(m3u8+ts/fMP4,含 AES-128 加密流解密)与渐进式(mp4/mkv 等)直链,4 线程分片并发,断点续传精确到分片,支持暂停/继续/取消/重试
- 每集最终合并为**单个 MP4**(平台 MediaExtractor/MediaMuxer 无损转封装);转封装失败自动降级为"分片+本地索引"模式,依然可离线播放
- 存储于应用私有目录 `Android/data/<包名>/files/offline/`,写入 `.nomedia`,**相册/媒体库不可见**,卸载即清
- 前台服务 + 通知:常驻进度通知(总进度/速度,可一键暂停)、每集完成通知(点击进入"我的缓存")
- "我的"页新增**"我的缓存"**入口:按剧集分组展示进度/大小,支持离线播放(复用本地播放器,含选集/上下集)、单集与整剧删除、占用空间统计
- 详情页集数列表对已缓存集显示 ✓ 角标;播放时自动优先使用本地缓存(离线可用)
- 首次使用缓存功能会弹出**免责声明**,同意后不再提示
- 设置页新增"缓存仅Wi-Fi下载"开关(默认关闭,即允许移动网络下载)
- 不支持项:需网页嗅探(parse=1)的集数、直播流(无 #EXT-X-ENDLIST)、#EXT-X-BYTERANGE 播放列表,均会明确提示
- 结构调整:由于 JitPack 上 `com.github.devin1014.DLNA-Cast:dlna-dmc:V1.0.0` 已无法构建,将 `dlna-core`/`dlna-dmc` 以源码模块形式内置(功能不变)
- 数据库 Room v1→v2 迁移新增 `downloadEpisode` 表,不影响原有历史/收藏数据

### 发布说明

- 本项目基于 AGPL-3.0,发布 fork 时请保留 LICENSE 并注明来源与本修改说明
- 本仓库不包含签名文件 `TVBoxOSC.jks`(已在 .gitignore 排除),构建前请自行生成或替换:
  `keytool -genkeypair -keystore TVBoxOSC.jks -alias TVBoxOSC -keypass TVBoxOSC -storepass TVBoxOSC -keyalg RSA -keysize 2048 -validity 36500`
- 构建环境:JDK 11~17(Gradle 7.3.3 / AGP 7.2.2),`local.properties` 指向本机 Android SDK
