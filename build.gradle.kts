plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

group = "de.myocd"
version = "1.0.0"

dependencies {
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
    // 播放内核：VLC（vlcj）。本机装了 VLC 就能播 mp3 / flac / 无损等所有格式
    implementation("uk.co.caprica:vlcj:4.12.1")
    // 没装 VLC 时的回退内核：纯 Java MP3 解码，保证至少能出声
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
            packageVersion = "1.0.0"
            description = "GoMusic Desktop"
            vendor = "GoMusic"
            copyright = "GoMusic"
            macOS { bundleID = "net.de5.gomusic" }
            windows { menuGroup = "GoMusic" }
            linux { packageName = "gomusic" }
            buildTypes.release.proguard { isEnabled.set(false) }
        }
    }
}
