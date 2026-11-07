# GoMusic 桌面版

**界面与功能和手机端 GoMusicForAndroid 26.11.7 完全一致**——不是桌面重排版，
而是把手机端的 Compose 源码逐字搬过来，用兼容层在 JVM 上原样编译。

- 源码：`android-src/de/myocd/gomusic/` 是从手机端仓库复制的 26 个 Kotlin 文件，
  除 6 个 Android 平台宿主/服务由桌面实现平替外，**其余文件零改动**
- 布局：窗口默认 420×860（手机竖屏，底栏 + 迷你播放栏）；把窗口拉宽，
  `LocalConfiguration` 判定横屏，自动切换成「左侧导航栏 + 右侧竖排播放栏」，
  播放页也变成横版双栏 —— 和手机转屏一模一样
- 弹层：设置 / 播放队列 / 全屏播放器 / 歌词 / 网易云登录 / 历史 / 下载 /
  加入歌单 / MV 全部保留，位置与交互不变
- 返回键：Esc 或 Backspace，栈顺序与 Android 一致

## 最高级平替（Android 专有能力 → 桌面对应物）

| Android | 桌面平替 |
| --- | --- |
| Media3 / ExoPlayer | JavaCV(FFmpeg) 解码 + OpenAL 输出，全格式、全平台，无需外部播放器 |
| 前台服务 / 通知栏 | 系统托盘气泡 + 窗口常驻 |
| 唤醒锁 / 电池优化白名单 | 桌面无 Doze，设置项保留但显示「无需设置」 |
| SharedPreferences | `~/.gomusic/*.properties`（java.util.Properties） |
| `cacheDir` / 公共 Download | `~/.gomusic/cache/` / `~/Downloads/GoMusic/` |
| Coil / Palette | OkHttp + ImageIO(WebP) + HSL 主色统计，封面 URL 与缓存逻辑不变 |
| 状态栏 / 导航栏 padding | 桌面无系统栏，同名扩展返回原 Modifier |
| 横竖屏传感器 | 窗口宽高比实时判定 |
| 扫码 / 验证页 / 复制链接 | 系统默认浏览器 + 系统剪贴板 |
| APK 安装 | 下载对应平台安装包后交系统安装器打开 |
| 思源宋体可变字体 | 系统衬线字体（全平台无需内置 22MB 字体） |

网易云取地址、音质切换（`?q=&nl=` 换 uri 触发重新解析）、逐档探测、
歌词翻译、离线译文、封面高清化、请求日志、崩溃日志、自动更新检查
全部沿用手机端同一份逻辑。

## 构建（JDK 17+）

```
gradle compileKotlin
gradle run
gradle createReleaseDistributable     # build/compose/binaries/main-release/app/
```

CI 在 Windows x64 / macOS x64 / macOS arm64 / Linux x64 四个 runner 上
构建并发布到 Releases 的 `desktop-v*` 标签。

> 仓库里的 `tauri/` 与 `src/main/kotlin/gomusic/`（除 Player.kt、Mp3.kt）
> 是上一代桌面版，保留留档，不参与本次构建。
