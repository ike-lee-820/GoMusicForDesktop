plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

group = "de.myocd"
version = "1.6.1"

// ---------- 平台相关依赖（FFmpeg 原生库 / OpenAL 原生库）----------
// 只打当前系统的原生库，避免把 5 个平台的 so/dll/dylib 全塞进去（几百 MB）
fun hostOs(): String {
    val n = System.getProperty("os.name").lowercase()
    return when {
        "win" in n -> "windows"
        "mac" in n || "darwin" in n -> "macosx"
        else -> "linux"
    }
}

fun isArm(): Boolean {
    val a = System.getProperty("os.arch").lowercase()
    return a.contains("aarch64") || a.contains("arm64") || a.contains("armv7")
}

fun ffmpegClassifier(): String {
    val os = hostOs()
    return when (os) {
        "windows" -> "windows-x86_64"
        "macosx" -> if (isArm()) "macosx-arm64" else "macosx-x86_64"
        else -> if (isArm()) "linux-arm64" else "linux-x86_64"
    }
}

fun joalClassifier(): String {
    val os = hostOs()
    return when (os) {
        "windows" -> "natives-windows-amd64"
        "macosx" -> "natives-macosx-universal"
        else -> if (isArm()) "natives-linux-aarch64" else "natives-linux-amd64"
    }
}

// 可在命令行用 -Pffmpeg.platform=xxx 覆盖
val ffmpegPlat = (project.findProperty("ffmpeg.platform") as String?) ?: ffmpegClassifier()
val joalPlat = (project.findProperty("joal.platform") as String?) ?: joalClassifier()

dependencies {
    // 简繁转换：转换到香港繁体用 opencc4j 的 ZhHkConverterUtil
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

    // 回退：FFmpeg 或 OpenAL 不可用时用它（纯 Java，仅 MP3）
    implementation("javazoom:jlayer:1.0.1")

    // WebP 封面（服务端封面多为 cover.webp，JDK 自带 ImageIO 不认）
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.13.0")
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "gomusic.MainKt"
        nativeDistributions {
            packageName = "GoMusic"
            packageVersion = "1.6.1"
            description = "GoMusic Desktop"
            vendor = "GoMusic"
            copyright = "GoMusic"
            modules("java.base", "java.desktop", "java.logging", "java.prefs", "jdk.unsupported")
            macOS { bundleID = "net.de5.gomusic" }
            windows { menuGroup = "GoMusic" }
            linux { packageName = "gomusic" }
            buildTypes.release.proguard { isEnabled.set(false) }
        }
    }
}
