# 云巢影音 NestPlayer

一个专为 **NAS 视频** 做的 Android 播放器：五种协议一套播放内核，海报墙 + 文件浏览双模式，交互对齐主流播放器（MX / VLC / 迅雷影音）的习惯。

- 包名 `com.cloudnest.player` · 版本 1.1.2 · minSdk 24（Android 7.0） · targetSdk 34
- 技术栈：Kotlin + Jetpack Compose + Media3 (ExoPlayer)
- 无广告、无账号、无云服务依赖，你的视频只在你的 NAS 和手机之间流转

## 功能

**协议与访问**

| 协议 | 说明 |
| --- | --- |
| SMB / SMB2-3 | 飞牛 / 群晖 / 威联通 / Windows 共享，基于 smbj |
| WebDAV | Nextcloud、群晖、Alist、坚果云等 |
| FTP / FTPS | 传统 FTP 服务器 |
| NFS v3 | 相对导出目录访问 |
| DLNA / UPnP | 自动发现局域网媒体服务器（含海报与专辑封面） |

所有协议统一由 App 内置的本地 HTTP 流代理（`127.0.0.1`，优先端口 18990）转成支持 `Range` 的 HTTP 源，播放、字幕、封面都走这一个入口 —— 因此任意协议下来的文件都能 seek、都能挂外挂字幕。

**库与界面**

- 海报墙：扫描目录自动识别剧集/电影，可接入 TMDB 元数据
- 文件浏览：按目录树浏览，不必等扫描
- 详情页：整屏头图 + 剧集列表（每集带观看进度）
- 搜索、服务器管理、观看历史与「继续观看」

**播放器**

- Media3 ExoPlayer 内核，硬解/软解自动回退
- 左右滑动实时快进：划满一整屏 ≈ 20~60 秒，带目标时间预览
- 左半屏上下滑亮度、右半屏上下滑音量（带百分比提示）
- 双击左右 ±10 秒、长按 2× 加速、单击显隐控件
- 选集面板（当前集高亮 + 每集已看进度）、自动连播、切集不串记录
- 倍速 0.5×–4.0×、音轨切换、字幕切换与本地外挂字幕、画面比例（适应/裁剪/拉伸）、旋转、锁屏防误触
- 视频信息面板（分辨率/帧率/编码/码率/声道/采样率）
- 续播记忆、播放记录持久化

**TS 也能拖（重点）**

MPEG-TS 容器没有索引表，ExoPlayer 默认把它当成不可 seek。本项目实现了一层「估时 + 字节映射」：

1. 播放前读文件头、文件尾各 768KB，解析 PES 时间戳算出总时长（与 VLC 的做法一致）；
2. 把「时长 ↔ 字节位置」的线性映射通过拦截 `ExtractorOutput.seekMap()` 塞给 TS 解析器；
3. ExoPlayer 于是会用 Range 请求直接跳字节，`.ts / .m2ts / .mts / .tp / .trp / .evo` 都能拖动，落点误差通常 1 秒内。

代码见 `app/src/main/java/com/cloudnest/player/player/TsSeek.kt`。

## 构建

```bash
# 需要 JDK 17、Android SDK（platform 35 + build-tools 35.0.0）
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleRelease   # 或 :app:assembleDebug
```

签名：`app/build.gradle.kts` 里读的是 `../nestplayer.keystore`（自签证书，**不在仓库里**）。自己构建时请生成自己的 keystore 并改路径，或在 CI 里用 secrets 注入。

## 目录

```
app/src/main/java/com/cloudnest/player/
├── App.kt                 # Application
├── data/{Model,Store}.kt  # kotlinx-serialization 单文件持久化（filesDir/appdata.json）
├── net/                   # 协议实现 + 流代理
│   ├── RemoteFs.kt        # 抽象与 FsManager
│   ├── SmbFs / WebDavFs / FtpFs / NfsFs / DlnaFs / DlnaDiscovery
│   └── StreamProxy.kt     # 本地 HTTP 流代理（Range / 长度兜底 / 字幕解码）
├── lib/{Naming,Scanner,Tmdb}.kt
├── player/                # PlayerActivity、手势与控件、TsSeek
└── ui/                    # Compose 界面与主题
```

## 已知限制

- `.ts` 的拖动是**按字节比例估算**，不是精确索引；同一文件内 GOP 越大偏差越明显（一般 < 1 秒）。长期片库建议无损转封装成 MKV（见 `tools/ts2mkv.sh`）。
- DLNA 的容器枚举依赖设备自身实现，个别设备不提供文件大小，此时该类文件不可 seek。
- 未实现：投屏推送、在线字幕搜索、云盘直连、截图/GIF、字幕压制。

## 许可

MIT License，见 `LICENSE`。第三方库许可见文末。

- AndroidX / Media3：Apache-2.0
- smbj：Apache-2.0
- Apache Commons Net：Apache-2.0
- ECS nfs-client：Apache-2.0
- OkHttp：Apache-2.0
- Coil：Apache-2.0

## 免责声明 / Disclaimer

本项目是一个**本地网络媒体播放客户端**，仅提供协议连接与媒体解码能力，作者不提供、不分发、不托管任何影视内容或第三方源站。

- 你通过本应用访问的所有文件、服务器和账号**均由你自己提供**，请确保你对这些内容拥有合法访问与观看权利。
- 请遵守你所在地区的法律法规以及内容版权方的授权范围。因使用本应用产生的任何版权、合规或数据责任，由使用者自行承担。
- 本项目按「现状」提供，不附带任何明示或暗示的担保；作者不对数据丢失、播放失败、设备异常等后果负责。
- 应用运行在纯局域网内，不向任何第三方服务器上传你的服务器地址、账号或媒体信息；抓取海报信息时仅向 TMDb 发送影片名称查询。
- 仓库与安装包中**不包含**任何账号、密钥、私有服务器地址或个人数据；示例中的地址均为占位符。

本仓库以 MIT 协议开源，你可以自由使用、修改、分发，但请保留原作者署名与上述声明。

> This project is a local-network media client only. It does not host, provide or distribute any media content. All servers and credentials are supplied by the user; you are solely responsible for lawful use. Provided "as is", without warranty of any kind.
