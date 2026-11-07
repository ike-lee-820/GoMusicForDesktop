package android.util

import java.util.Base64 as JBase64

object Base64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2
    fun decode(s: String, flags: Int): ByteArray = runCatching {
        JBase64.getMimeDecoder().decode(s)
    }.getOrDefault(JBase64.getDecoder().decode(s.replace("\s".toRegex(), "")))
    fun encodeToString(b: ByteArray, flags: Int): String =
        if (flags and NO_WRAP != 0) JBase64.getEncoder().encodeToString(b)
        else JBase64.getMimeEncoder().encodeToString(b)
}
object Log {
    fun d(tag: String, msg: String): Int { println("[$tag] $msg"); return 0 }
    fun i(tag: String, msg: String): Int { println("[$tag] $msg"); return 0 }
    fun w(tag: String, msg: String): Int { println("[$tag] $msg"); return 0 }
    fun e(tag: String, msg: String): Int { System.err.println("[$tag] $msg"); return 0 }
}
