import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

group = "de.myocd"
version = "26.11.10"

// ---------- 平台相关依赖（FFmpeg 原生库 / OpenAL 原生库）----------
// 只打当前系统的原生库，避免把 5 个平台的 so/dll/dylib 全塞进去（几百 MB）
fun hostOs(): String {
    val n = System.getProperty("os.name").lowercase()
    return when { "win" in n -> "windows"; "mac" in n || "darwin" in n -> "macosx"; else -> "linux" }
}
fun isArm(): Boolean {
    val a = System.getProperty("os.arch").lowercase()
    return a.contains("aarch64") || a.contains("arm64") || a.contains("armv7")
}
fun ffmpegClassifier() = when (hostOs()) {
    "windows" -> "windows-x86_64"
    "macosx" -> if (isArm()) "macosx-arm64" else "macosx-x86_64"
    else -> if (isArm()) "linux-arm64" else "linux-x86_64"
}
fun joalClassifier() = when (hostOs()) {
    "windows" -> "natives-windows-amd64"
    "macosx" -> "natives-macosx-universal"
    else -> if (isArm()) "natives-linux-aarch64" else "natives-linux-amd64"
}
val ffmpegPlat = (project.findProperty("ffmpeg.platform") as String?) ?: ffmpegClassifier()
val joalPlat = (project.findProperty("joal.platform") as String?) ?: joalClassifier()

kotlin {
    jvmToolchain(17)
    sourceSets {
        main {
            kotlin {
                // 手机端源码逐字复制进 android-src：界面与业务逻辑零改动，
                // 靠 src/main/kotlin 里的同名 Android API 兼容层在 JVM 上编译。
                srcDirs("src/main/kotlin", "android-src")
                // 6 个 Android 平台宿主/服务由桌面实现平替，其余 22 个文件原样编译。
                exclude(
                    // 旧 Compose Desktop 版（已废弃）的 UI 代码不参与编译，
                    // 只保留 Player.kt / Mp3.kt 作为 FFmpeg+OpenAL 播放内核
                    "gomusic/App.kt", "gomusic/AppState.kt", "gomusic/Data.kt",
                    "gomusic/Fonts.kt", "gomusic/Images.kt", "gomusic/Main.kt",
                    "gomusic/Netease.kt", "gomusic/NmIcon.kt", "gomusic/NmLog.kt",
                    "gomusic/NmUi.kt", "gomusic/PlaylistShare.kt", "gomusic/Prefs.kt",
                    "gomusic/Store.kt", "gomusic/Ui.kt", "gomusic/Updater.kt",
                    "gomusic/ZhConvert.kt",
                    "**/MainActivity.kt",
                    "**/PlaybackService.kt",
                    "**/Fonts.kt",
                    "**/Noti.kt",
                    "**/PlayCache.kt",
                    "**/InstallResultReceiver.kt",
                )
            }
        }
    }
}

dependencies {
    implementation("com.github.houbb:opencc4j:1.14.0")
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 解码：JavaCV 调 FFmpeg（mp3 / flac / aac / ogg / opus / wav 通吃）
    implementation("org.bytedeco:javacv:1.5.12")
    implementation("org.bytedeco:ffmpeg:7.1.1-1.5.12")
    implementation("org.bytedeco:ffmpeg:7.1.1-1.5.12:$ffmpegPlat")
    // 输出：OpenAL（JOAL）
    implementation("org.jogamp.joal:joal:2.6.0")
    implementation("org.jogamp.joal:joal:2.6.0:$joalPlat")
    implementation("org.jogamp.gluegen:gluegen-rt:2.6.0")
    implementation("org.jogamp.gluegen:gluegen-rt:2.6.0:$joalPlat")
    implementation("javazoom:jlayer:1.0.1")
    // WebP 封面
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.13.0")
}

compose.desktop {
    application {
        mainClass = "de.myocd.gomusic.DesktopMainKt"
        nativeDistributions {
            // 直接产出各平台单文件安装包，不再生成可运行目录后另打 zip。
            // jpackage 不支持交叉编译：exe/msi 在 Windows runner、dmg 在 macOS、deb 在 Linux 生成。
            targetFormats(
                TargetFormat.Exe,   // Windows 安装程序（用户主产物）
                TargetFormat.Msi,   // Windows 备用安装包
                TargetFormat.Dmg,   // macOS
                TargetFormat.Deb,   // Linux
            )
            packageName = "GoMusic"
            packageVersion = "26.11.10"
            // Windows jpackage 用平台默认编码（GBK）读 args 文件，中文会触发
            // MalformedInputException: Input length = 1，导致 exe/msi 打包失败。
            // 这里所有交给 jpackage 的元数据一律用 ASCII。
            description = "GoMusic Desktop - landscape UI, same features as the Android app"
            vendor = "GoMusic"
            copyright = "GoMusic"
            // 桌面端用 OkHttp / AWT / WebP / 系统托盘，补上运行时可能缺的模块
            modules(
                "java.base", "java.desktop", "java.logging", "java.prefs",
                "java.naming", "java.management", "java.xml", "java.sql",
                "jdk.unsupported", "jdk.crypto.ec",
            )
            macOS { bundleID = "net.de5.gomusic" }
            windows { menuGroup = "GoMusic"; shortcut = true }
            linux { packageName = "gomusic"; shortcut = true }
            buildTypes.release.proguard { isEnabled.set(false) }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}
