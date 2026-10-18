package gomusic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val BASE = "https://gomusic.myocd.de5.net"

@Serializable
data class Song(
    val ssid: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val coverUrl: String? = null,
    val repoName: String? = null,
    val owner: String? = null,
    val locked: Boolean? = null,
)

@Serializable
data class ArtistInfo(val image: String? = null, val bio: String? = null)

data class SongDetail(
    val ssid: String,
    val lyrics: String,
    val cover: String?,
    /** 音质 -> 播放地址数组 */
    val audio: Map<String, List<String>>,
)

data class LrcLine(val timeMs: Long, val text: String)

object Api {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** IPv4 优先：规避部分网络下 IPv6 不通导致的连接中断 */
    private val ipv4First = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = try {
                InetAddress.getAllByName(hostname).toList()
            } catch (e: Exception) {
                throw UnknownHostException("$hostname: ${e.message}")
            }
            if (all.isEmpty()) throw UnknownHostException(hostname)
            return all.sortedBy { if (it is Inet4Address) 0 else 1 }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dns(ipv4First)
        .build()

    /** 探测用：短超时，一次失败即判定离线 */
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .dns(ipv4First)
        .build()

    private val detailCache = ConcurrentHashMap<String, SongDetail>()

    private val ORDER = listOf("normal", "high", "low", "extreme", "source", "lossless")
    fun rank(k: String): Int = ORDER.indexOf(k).let { if (it < 0) 99 else it }

    fun abs(u: String?): String? = when {
        u.isNullOrBlank() -> null
        u.startsWith("http") || u.startsWith("data:") -> u
        u.startsWith("/") -> BASE + u
        else -> "$BASE/$u"
    }

    private fun get(path: String, tries: Int = 3, http: OkHttpClient = client): String {
        var last: Exception? = null
        repeat(tries) { i ->
            try {
                val req = Request.Builder().url(BASE + path).header("Connection", "close").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    return r.body!!.string()
                }
            } catch (e: Exception) {
                last = e
                if (i < tries - 1) runCatching { Thread.sleep(500L * (i + 1)) }
            }
        }
        throw last ?: IOException("请求失败")
    }

    /** [quick] = true：短超时只试一次，失败立即离线 */
    fun songs(quick: Boolean = true): List<Song> = json.decodeFromString(
        if (quick) get("/api/songs", tries = 1, http = probeClient) else get("/api/songs")
    )

    fun artists(): Map<String, ArtistInfo> =
        runCatching { json.decodeFromString<Map<String, ArtistInfo>>(get("/api/artists", tries = 2)) }
            .getOrDefault(emptyMap())

    fun lyrics(ssid: String): String = get("/api/lyrics/$ssid")

    private fun JsonElement?.str(): String? = when (this) {
        is JsonPrimitive -> content.takeIf { it.isNotBlank() }
        else -> null
    }

    fun detail(ssid: String): SongDetail {
        detailCache[ssid]?.let { return it }
        val obj = json.parseToJsonElement(get("/api/song/$ssid")) as? JsonObject
            ?: throw IOException("详情格式错误")
        val audio = mutableMapOf<String, List<String>>()
        (obj["audio"] as? JsonObject)?.let { a ->
            for ((k, v) in a) {
                val list = when (v) {
                    is JsonPrimitive -> listOf(v.content)
                    is kotlinx.serialization.json.JsonArray ->
                        v.mapNotNull { (it as? JsonPrimitive)?.content }
                    else -> emptyList()
                }
                if (list.isNotEmpty()) audio[k] = list.map { abs(it) ?: it }
            }
        }
        val d = SongDetail(
            ssid = ssid,
            lyrics = obj["lyrics"].str() ?: obj["lrc"].str() ?: "",
            cover = abs(obj["cover"].str() ?: obj["coverUrl"].str()),
            audio = audio,
        )
        detailCache[ssid] = d
        return d
    }

    fun clearCache() { detailCache.clear() }

    /** 选择最接近所需音质的地址（多段只取第一段） */
    fun audioUrl(ssid: String, quality: String): String {
        val d = detail(ssid)
        if (d.audio.isEmpty()) throw IOException("没有可用音频")
        val key = d.audio.keys.minByOrNull { kotlin.math.abs(rank(it) - rank(quality)) }!!
        return d.audio[key]!!.first()
    }

    /** 桌面版用 VLC 播放，各音质都能播，直接挑最接近目标音质的地址 */
    fun audioUrlFor(ssid: String, quality: String): String = audioUrl(ssid, quality)

    /** 下载到输出流；[onProgress] 回调 (已读字节, 总字节，-1 未知) */
    fun fetch(url: String, out: OutputStream, onProgress: ((Long, Long) -> Unit)? = null) {
        val req = Request.Builder().url(url).header("Connection", "close").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("空响应")
            val total = body.contentLength()
            body.byteStream().use { input ->
                if (onProgress == null) {
                    input.copyTo(out)
                    return
                }
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (read - last >= 128 * 1024) {
                        last = read
                        onProgress(read, total)
                    }
                }
                out.flush()
                onProgress(read, total)
            }
        }
    }

    fun bytes(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).header("Connection", "close").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()
        }
    } catch (e: Exception) {
        null
    }
}

fun qualityLabel(k: String): String = when (k) {
    "lossless" -> "无损"
    "high" -> "高品质"
    "normal" -> "标准"
    "low", "saver", "economy", "lite" -> "省流"
    else -> k
}

fun parseLrc(t: String): List<LrcLine> {
    val re = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]")
    val out = mutableListOf<LrcLine>()
    for (line in t.lines()) {
        val ms = re.findAll(line).toList()
        if (ms.isEmpty()) continue
        val text = line.replace(re, "").trim()
        for (m in ms) {
            val mm = m.groupValues[1].toLong()
            val ss = m.groupValues[2].toLong()
            val fr = m.groupValues[3]
            val f = if (fr.isEmpty()) 0L else fr.padEnd(3, '0').take(3).toLong()
            out += LrcLine(mm * 60000 + ss * 1000 + f, text)
        }
    }
    return out.filter { it.text.isNotEmpty() }.sortedBy { it.timeMs }
}

/** 有时间戳按 LRC 解析，否则当作纯文本逐行显示（timeMs = -1） */
fun lyricLines(t: String): List<LrcLine> {
    val p = parseLrc(t)
    if (p.isNotEmpty()) return p
    return t.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { LrcLine(-1, it) }
}

fun fmt(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

/**
 * 文档 6：仓库内「专辑名/cover.webp」即该专辑封面，通过
 * `/api/proxy/raw/:repo/:path?o=<owner>` 代理访问。列表没给封面时用这个兜底。
 */
fun guessCoverUrl(s: Song): String? {
    val repo = s.repoName?.takeIf { it.isNotBlank() } ?: return null
    val owner = s.owner?.takeIf { it.isNotBlank() } ?: return null
    val album = s.album.takeIf { it.isNotBlank() } ?: return null
    val encAlbum = URLEncoder.encode(album, "UTF-8").replace("+", "%20")
    val encOwner = URLEncoder.encode(owner, "UTF-8")
    return "$BASE/api/proxy/raw/$repo/$encAlbum/cover.webp?o=$encOwner"
}

/** 图片统一转成可加载的引用：data: URI 保持原样，补全相对地址 */
fun coverRef(u: String?): String? {
    if (u.isNullOrBlank()) return null
    if (u.startsWith("data:") || u.startsWith("http")) return u
    if (u.startsWith("/")) return Api.abs(u)
    return u // 本地文件路径
}

fun decodeDataUri(u: String): ByteArray? {
    val i = u.indexOf("base64,")
    if (i < 0) return null
    return runCatching { Base64.getDecoder().decode(u.substring(i + 7)) }.getOrNull()
}
