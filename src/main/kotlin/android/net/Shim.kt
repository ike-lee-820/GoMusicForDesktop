package android.net

import java.io.File
import java.net.URLDecoder

class Uri private constructor(private val s: String) {
    val scheme: String? get() = if (s.contains(':')) s.substringBefore(':') else null
    val path: String? get() = s.substringAfter('/', "")
    val lastPathSegment: String? get() = s.substringBefore('?').substringAfterLast('/', "").takeIf { it.isNotEmpty() }
    fun getQueryParameter(k: String): String? {
        val q = s.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return null
        return q.split('&').firstNotNullOfOrNull { seg ->
            val i = seg.indexOf('=')
            if (i < 0) null
            else runCatching { URLDecoder.decode(seg.substring(0, i), "UTF-8") }.getOrNull()
                ?.takeIf { it == k }
                ?.let { runCatching { URLDecoder.decode(seg.substring(i + 1), "UTF-8") }.getOrNull() }
        }
    }
    override fun toString(): String = s
    companion object {
        fun parse(s: String): Uri = Uri(s)
        fun fromFile(f: File): Uri = Uri(f.toURI().toString())
        fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")
        fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    }
}
class Network
class ConnectivityManager {
    fun registerDefaultNetworkCallback(cb: Any) {}
    fun unregisterNetworkCallback(cb: Any) {}
}
