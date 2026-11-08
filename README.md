# GoMusic 桌面版 26.11.10

> **已停止开发（2026-11-08）**：桌面端不再发布新版本，仓库与已发布的 26.11.10 安装包仅作留存；后续功能只维护手机端 GoMusicForAndroid。

**界面与功能跟手机端 GoMusicForAndroid 26.11.7 完全一致**——不是桌面重排版，
而是把手机端的 Compose 源码逐字搬过来，靠一层 Android API 兼容层在 JVM 上原样编译。

对齐基准：`android-src/de/myocd/gomusic/` 的 26 个 Kotlin 文件与手机端 `main`
的 26.11.7 快照**逐字节一致**（`diff -rq` 无输出）。除 6 个 Android 平台宿主/服务
由桌面实现平替外，其余 20 个文件零改动。

## 界面

- **只保留横屏**：竖屏界面已移除，`LocalConfiguration.orientation` 恒为 LANDSCAPE
- 窗口默认 **1280×800**，最小 960×600（低于下限自动拉回），永远不会掉进竖屏分支
- 布局固定为「左侧导航栏 + 右侧竖排播放栏」，播放页横版双栏 —— 与手机横屏同一套代码
- 横屏左侧摄像头避让用**内边距**实现，背景延伸到屏幕边缘，不留黑条
- 设置 / 播放队列 / 全屏播放器 / 歌词 / 网易云登录 / 历史 / 下载 / 加入歌单 / MV
  弹层全部保留，位置与交互不变
- 返回键：Esc 或 Backspace，栈顺序与 Android 一致
- 横屏播放控制栏**不自动收起**（与手机端 26.11.7 一致）；系统状态栏在桌面不存在

## 最高级平替（Android 专有能力 → 桌面对应物）

| Android | 桌面平替 |
| --- | --- |
| Media3 / ExoPlayer | JavaCV(FFmpeg) 解码 + OpenAL 输出，全格式、全平台，无需外部播放器 |
| 前台服务 / 通知栏媒体控制 | 系统托盘气泡 + 窗口常驻 |
| 唤醒锁 / 电池优化白名单 | 桌面无 Doze，设置项保留但显示「无需设置」 |
| SharedPreferences | `~/.gomusic/*.properties`（java.util.Properties） |
| `cacheDir` / 公共 Download | `~/.gomusic/cache/` / `~/Downloads/GoMusic/` |
| Coil / Palette | OkHttp + ImageIO(WebP) + HSL 主色统计，封面 URL 与缓存逻辑不变 |
| 状态栏 / 导航栏 padding | 桌面无系统栏，同名扩展返回原 Modifier |
| 横竖屏传感器 | 窗口宽高比实时判定 |
| 扫码 / 风控验证页 / 复制链接 | 系统默认浏览器 + 系统剪贴板 |
| APK 安装 | 下载对应平台安装包后交系统安装器打开 |
| 思源宋体可变字体 | 系统衬线字体（全平台无需内置 22MB 字体） |
| Windows ARM64 原生解码库（上游缺失） | x64 仿真 JRE + 现有 FFmpeg/OpenAL，界面与功能零降级，见下方架构说明 |

网易云取地址、音质切换（`?q=&nl=` 换 uri 触发重新解析）、逐档探测、
歌词翻译、离线译文、封面高清化、请求日志、崩溃日志、自动更新检查
全部沿用手机端同一份逻辑，没有重写。

## 目录

| 路径 | 作用 |
| --- | --- |
| `android-src/` | 手机端源码逐字副本（26 个文件，**不要手改**，同步时整体覆盖） |
| `src/main/kotlin/android*/` `androidx/` `coil/` | Android API 兼容层，让源码在 JVM 编译 |
| `src/main/kotlin/de/myocd/gomusic/` | 桌面入口、平台实现、播放内核 |
| `src/main/kotlin/gomusic/` | 上一代桌面版留档，仅 Player.kt / Mp3.kt 参与编译 |
| `tauri/` | 上一代 Tauri 版留档，不参与构建、不发布 |
| `ci/package.py` | 旧版 zip 脚本，留档，CI 已不再调用 |

## 构建（JDK 17+）

```
gradle compileKotlin
gradle run
gradle createReleaseDistributable     # build/compose/binaries/main-release/app/
```

CI：`.github/workflows/compose-desktop.yml` 在 `windows-x64 / windows-arm64 /
macos-x64 / macos-arm64 / linux-x64 / linux-arm64` 六个 runner 上编译并用
jpackage **直接产出原生安装包**，推送 `desktop-v*` tag 时把单文件安装包传到同一个 Release：

| 平台 | 产物（Release 里直接下载） |
| --- | --- |
| Windows x64 | `GoMusic-26.11.10-windows-x64.exe` / `.msi` |
| Windows ARM64 | `GoMusic-26.11.10-windows-arm64.exe` / `.msi` |
| macOS | `GoMusic-26.11.10-macos-arm64.dmg` / `-macos-x64.dmg` |
| Linux x64 | `GoMusic-26.11.10-linux-x64.deb` |
| Linux ARM64 | `GoMusic-26.11.10-linux-arm64.deb` |

**不再提供 zip 压缩包**。安装包内置 JRE，双击安装即可运行。
jpackage 不支持交叉编译，所以每个平台的包由对应平台的 runner 生成。

**架构说明**：Linux ARM64 是真正的原生 aarch64 包（aarch64 JRE + `linux-arm64`
FFmpeg + `natives-linux-aarch64` JOAL），CI 里用 `dpkg-deb -f` 断言 deb 架构为
`arm64`。Windows ARM64 上游 JavaCV(FFmpeg) / JogAmp(JOAL) 不发布
`windows-arm64` 原生库，因此该包内置 x64 JRE，由 Windows 自带的 x64 仿真运行，
功能与 x64 版完全一致，但不是原生 ARM64 机器码（详见「最高级平替」）。

`build.yml` 与 `tauri.yml` 是旧流程留档，只在 Actions 手动触发，不随 push 跑、
不发布 release，避免和主线抢 tag。

> 详见 [PORTING.md](PORTING.md)：逐功能对照、6 个平台文件的平替关系、已知限制。
