# GoMusic 桌面版移植对照与验收

基准：手机端 `GoMusicForAndroid` `main` @ `26.11.7`
产物：桌面版 `GoMusicForDesktop` `26.11.10`（Compose Desktop / JVM）

## 一、结论

界面与功能**不重排、不重写**：手机端 26 个 Kotlin 源文件整体复制进 `android-src/`，
用一层 Android API 兼容层在 JVM 上原样编译。

校验方式（本次已实际执行）：

```
diff -rq android-src/de/myocd/gomusic/ <手机端26.11.7>/app/src/main/java/de/myocd/gomusic/
# 无输出 = 26 个文件逐字节一致
gradle compileKotlin --no-daemon
# BUILD SUCCESSFUL（四平台 CI 日志 gd_build3.log，48s）
```

参与编译的 20 个文件零改动；另 6 个是 Android 平台宿主/服务，
由 `DesktopPlatform.kt` 等同名对象平替，业务代码无需感知平台差异。

## 二、6 个平台文件的平替关系

| 被排除的手机端文件 | 桌面平替 | 落点 |
| --- | --- | --- |
| `MainActivity.kt` | 桌面窗口入口：Window + LocalContext / LocalConfiguration、Esc 返回、初始化 ImageCache / NmLog / CrashHandler / 简繁预热 / 更新检查 | `DesktopMain.kt` |
| `PlaybackService.kt` | 无 Service，保留同名 `PlaybackService` 对象承接播放焦点指令；实际解码播放由 FFmpeg+OpenAL 内核承担 | `DesktopPlatform.kt` + `DesktopPlayer.kt` + `Player.kt` / `Mp3.kt` |
| `Noti.kt` | `java.awt.SystemTray` 托盘气泡，下载进度 / 完成提示 | `DesktopPlatform.kt` |
| `PlayCache.kt` | 同名对象记录当前音源，切歌清上一首缓存引用（FFmpeg 直接流式播放） | `DesktopPlatform.kt` |
| `Fonts.kt` | `FontFamily.Serif` 系统衬线字体，`FONT_SONG` 常量保留，字重常量保留 | `DesktopPlatform.kt` |
| `InstallResultReceiver.kt` | 同名空接收器；更新包下载后交系统安装器打开 | `DesktopPlatform.kt` |

另外 `R` / `BuildConfig` 这两个编译期生成的类也由 `DesktopPlatform.kt` 提供常量，
`VERSION_NAME = 26.11.10` 与 `build.gradle.kts` 一致。

## 三、Android API 兼容层

`src/main/kotlin/` 下按原包名建同名空壳，只为让源码零改动编译：

- `android.*`：Application / Context / Intent / BroadcastReceiver / Bundle / Handler / Looper /
  Parcel / Uri / Log / Toast / Bitmap / Drawable / Resources / Configuration / ConnectivityManager /
  WifiManager / TelephonyManager / Settings / Environment / View / TextView 等
- `androidx.*`：activity（含 BackDispatcher，接 Esc/Backspace）、lifecycle、annotation、
  media3（common / exoplayer / session）、palette（HSL 主色统计）、
  compose.foundation.layout（Insets：状态栏/导航栏 padding 返回原 Modifier）
- `coil.*`：`AsyncImage` / `ImageRequest` / `ImageLoader` → OkHttp + ImageIO(WebP)
- `com.google.common.util.concurrent`：ListenableFuture 相关

`DesktopConfiguration` 把窗口宽高实时映射成 `Configuration.orientation`。
**26.11.9 起桌面只保留横屏**：入口把 `orientation` 固定为 `LANDSCAPE`，
窗口默认 1280×800，最小 960×600（低于下限自动拉回），
手机端源码里的竖屏分支永远不会被渲染 —— 界面上等于删除了竖屏，
源码仍保持逐字副本不变。

## 四、功能逐项对照

| 手机端功能 | 桌面端 | 是否 1:1 |
| --- | --- | --- |
| 首页（推荐/热搜/榜单/新歌/最新专辑/热门歌手） | 同一份 `MainViewModel` + `Screens` | 是 |
| 分标签搜索、翻页、防串扰 | 同上 | 是 |
| 网易云外部曲库强制开启、不显示源站、无来源角标 | 同上 | 是 |
| 播放队列、单曲循环、随机、倍速 | 同上 | 是 |
| 音质菜单：逐档探测、返回档位名严格匹配才判「有」、当前档高亮、无音源置灰 | 同上 | 是 |
| 切音质换 uri 强制重新解析 | 同上 | 是 |
| cookie 清洗去重 + 强制 `os=pc`，所有网易云请求带 `randomCNIP=true` | 同上 | 是 |
| 验证码登录、风控 `-462` / `10004` 提示卡、打开验证页、复制链接 | 同上；外链改系统浏览器、剪贴板改系统剪贴板 | 行为一致，载体平替 |
| 歌词区间侦测翻译、纯中文不显示、外语占比 <10% 不显示按钮 | 同上 | 是 |
| 下载时随存译文、已有则跳过、离线播放调取 | 同上，路径改 `~/Downloads/GoMusic/` | 是 |
| 封面高清化 `param=1000y1000`、FilterQuality.High | 同上，加载走 OkHttp+ImageIO | 是 |
| 边播边缓存整首、切歌清上一首 | FFmpeg 流式播放 + 缓存引用管理 | 行为等价 |
| 请求日志 / 崩溃日志 / 自动更新（v6.gh-proxy.com） | 同上，日志落 `~/.gomusic/` | 是 |
| 还原字符导入导出歌单、按 id 跳转专辑/歌手 | 同上 | 是 |
| 简繁转换 opencc4j | 同一依赖，JVM 原生可用 | 是 |
| 横屏左侧导航 + 右侧竖排播放栏 | 桌面唯一布局；竖屏分支不渲染 | 是（仅横屏） |
| 横屏左侧摄像头避让用内边距、背景延伸 | 同一套布局代码 | 是 |
| 横屏播放控制栏不自动收起 | 同一套布局代码 | 是 |
| 系统状态栏显隐 / 竖屏界面 | 桌面无系统栏、无竖屏，`StatusBarMode()` 空实现 | 已移除 |
| 后台保活（WakeLock / 电池白名单 / 网络恢复重连） | 桌面无 Doze，设置项保留显示「无需设置」 | 不适用 |
| 通知栏媒体控制 | 系统托盘气泡 | 平替 |
| APK 安装 | 下载对应平台 zip / 安装包交系统打开 | 平替 |

## 五、为什么是这些「最高级平替」

- **播放内核选 JavaCV(FFmpeg) + OpenAL**，而不是内嵌 VLC 或调用系统播放器：
  mp3 / flac / aac / ogg / opus / wav 全格式通吃，跨平台一致，
  且在 JVM 进程内可控 seek / 缓冲 / 音质切换，最接近 ExoPlayer 的能力边界。
- **通知选 AWT SystemTray**：JDK 自带，无需额外原生依赖，Windows / macOS / Linux 都有。
- **封面选 OkHttp + TwelveMonkeys WebP**：保留手机端同一套 URL 与缓存逻辑，
  只替换解码器，WebP 也能读。
- **字体选系统衬线**：手机端内置 22MB 思源宋体可变字体是为了 Android 没有该字体；
  桌面三平台都有衬线字体，字重选择项仍保留，避免为一个字体把安装包撑大。
- **验证页走系统浏览器**：易盾滑块 / 网易风控页必须在真实浏览器里过，
  内嵌 WebView 反而容易被判非真实环境，这是能力上限而非降级。

## 六、已知限制

1. 桌面没有系统状态栏与导航栏，`statusBarsPadding()` / `navigationBarsPadding()`
   返回原 Modifier；横屏左侧避让仍由应用内边距完成，视觉与手机横屏一致。
2. 没有电话打断、Doze、电池优化，后台保活相关设置项只作展示。
3. 思源宋体可变字体的实时字重调节在桌面退化为系统衬线字体，字重参数保留但不生效。
4. 更新检查拿到的是桌面端 `desktop-v*` 的 Release，与手机端 `v*` 分开。
5. 四平台**原生安装包**由 CI 生成（exe / msi / dmg / deb），不再提供 zip；
   jpackage 不支持交叉编译，Windows 安装包只能在 Windows runner 上生成。
6. 本地 `gradle run` 需要 JDK 17+ 与对应平台的 FFmpeg/OpenAL 原生库。

## 七、同步手机端新版本的方法

1. 用新版 `app/src/main/java/de/myocd/gomusic/` **整体覆盖** `android-src/de/myocd/gomusic/`
2. `diff -rq` 确认只有预期文件变化
3. 若新增了 Android 平台类，在兼容层补同名空壳；若新增了平台服务，在 `DesktopPlatform.kt` 补平替
4. `gradle compileKotlin` 通过后，按 `年.月.该月版本` 递增 `build.gradle.kts` 的
   `version` / `packageVersion`、`DesktopPlatform.VERSION_NAME` 与 CI 产物名
5. 推 `desktop-v<版本>` tag 触发六平台出包


## 八、26.11.9 产物与「部分编译失败」的修复

以前 `compose-desktop.yml` 在「压缩分发包」这一步做了两件跨平台不兼容的事：

- `find "$ROOT/build/compose/binaries" -maxdepth 4 ...` —— macOS 的 BSD `find` 不支持 `-maxdepth`
- `python3 "$ROOT/ci/package.py"` —— Windows runner 的 Git Bash 里没有 `python3`

所以 Linux 编译能过、macOS / Windows 在打包阶段挂掉，表现就是「部分编译失败」。
现在整段删掉，改为 Gradle 原生 package 任务按平台产出单文件安装包，
用 shell glob 直接收集上传；四个 runner 互不阻塞，某个格式失败不影响其它格式。

| 平台 | Gradle 任务 | Release 里的文件 |
| --- | --- | --- |
| Windows x64 | `packageReleaseExe` / `packageReleaseMsi` | `GoMusic-26.11.10-windows-x64.exe`、`.msi` |
| Windows ARM64 | `packageReleaseExe` / `packageReleaseMsi` | `GoMusic-26.11.10-windows-arm64.exe`、`.msi` |
| macOS arm64 / x64 | `packageReleaseDmg` | `GoMusic-26.11.10-macos-arm64.dmg`、`-macos-x64.dmg` |
| Linux x64 | `packageReleaseDeb` | `GoMusic-26.11.10-linux-x64.deb` |
| Linux ARM64 | `packageReleaseDeb` | `GoMusic-26.11.10-linux-arm64.deb` |

打包依赖：Windows exe/msi 需要 WiX v3，Linux deb 需要 `fakeroot`，CI 已显式准备。

### 这次还修掉的两个真实失败

1. **Windows jpackage 报 `Input length = 1`**（`MalformedInputException`）：
   `nativeDistributions.description` 原先写的是中文，jpackage 在 Windows 上用平台
   默认编码读 args 文件，UTF-8 中文字节解析失败，`exe` / `msi` 全部 exit 1。
   现在交给 jpackage 的所有元数据（description / vendor / copyright / menuGroup）
   一律 ASCII，中文说明只放在 README 里。
2. **`macos-14` runner 被取消**：改回 `macos-latest`（arm64）后 arm 包正常产出。

修复后四个 runner 全部 `success`，Release 直接挂 5 个单文件安装包：
`windows-x64.exe` / `.msi`、`macos-arm64.dmg`、`macos-x64.dmg`、`linux-x64.deb`。

### 26.11.10：补齐 ARM64

新增 `ubuntu-24.04-arm` 与 `windows-11-arm` 两个官方公共 runner，六平台共 8 个安装包。
Linux ARM64 走 `aarch64` Temurin JDK + `linux-arm64` FFmpeg + `natives-linux-aarch64` JOAL，
是真正的原生包，CI 用 `dpkg-deb -f` 断言 deb 架构。Windows ARM64 上游 JavaCV(FFmpeg) /
JogAmp(JOAL) 只有 `windows-x86_64` / `natives-windows-amd64`，没有 windows-arm64 原生库；
按「最高级平替」用 x64 仿真 JRE 出兼容包，界面与功能零降级，但不是原生 ARM64 机器码。
