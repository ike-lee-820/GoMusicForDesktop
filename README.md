# GoMusic 桌面版

GoMusic 的桌面客户端，逻辑与 Android 版一致（同一套接口、同一套下载与离线方案），
只把界面改成桌面宽屏排版：左栏列表 / 右栏正在播放。

- 后端：线上 `https://gomusic.myocd.de5.net`（与 Android 版相同，写死在 `Data.kt`）
- 技术栈：Kotlin + Compose for Desktop（JetBrains）+ OkHttp + JLayer
- 平台：Windows x64、Linux x64 / ARM64、macOS x64 / ARM64

## 界面排版（相对 Android 版的改动）

单窗口、左右两栏，可自由缩放：

- **左栏**：搜索框 + 歌曲 / 专辑 / 歌手 / 歌单四个标签 + 列表；
  点专辑 / 歌手 / 歌单在左栏内打开二级页（左上角返回），右侧播放面板始终可见
- **右栏**：正在播放 —— 大封面、歌名歌手、进度条、播放控制、音质，
  下方在「歌词」和「播放列表」之间切换
- 鼠标悬停高亮、右键外没有多余动效；快捷键：空格播放/暂停，左右方向键 ±5 秒

## 功能

- 歌曲按播放次数降序，专辑 / 歌手 / 歌单各自列表，四栏分别搜索
- 主题色：跟随封面取色，或预设 / 自定义；播放模式（列表循环 / 随机 / 单曲）、音质切换
- 下载：保存音频 + 封面 + 歌词 + 歌曲信息到 `~/Downloads/GoMusic/`，显示实时速度与进度
- 离线模式：连不上服务器时自动进入，只展示已下载内容并本地播放
- 本地歌单（新建 / 重命名 / 删除 / 加歌 / 移除），播放队列

## 桌面版与 Android 版的差异（技术原因）

| 项目 | Android 版 | 桌面版 |
| --- | --- | --- |
| 播放内核 | Media3 / ExoPlayer | JLayer（纯 Java MP3 解码）+ javax.sound.sampled |
| 倍速 | 支持 | 不支持（JLayer 无变速，设置保留但不生效） |
| 音质 | 各音质都行 | 优先挑 mp3 地址；无损（flac）播不了 |
| 后台播放 | 有（通知栏 / 锁屏） | 无（桌面没有这个场景） |
| 存储位置 | `Download/GoMusic/` | `~/Downloads/GoMusic/`，配置在 `~/.gomusic/` |

播放内核没有选 JavaFX：JavaFX Media 在 Linux 上依赖系统 ffmpeg 的特定版本，
新发行版上经常直接不可用；JLayer 是纯 Java，三平台所有架构开箱即用。

## 构建与运行

**运行（需 JDK 17+）**
```
gradle run
```

**打包当前平台**
```
gradle createDistributable     # 产物：build/compose/binaries/main-release/app/
gradle packageDeb              # Linux：deb
gradle packageMsi              # Windows：msi（需 WiX）
gradle packageDmg              # macOS：dmg
```

**GitHub Actions**：推到 main 后自动在 5 个 runner 上构建（Linux x64 / ARM64、
Windows x64、macOS x64 / ARM64），产物发布到 Releases 的 `v1.0.0`，
下载后解压即可运行（内含自带 JDK，无需额外安装 Java）。
