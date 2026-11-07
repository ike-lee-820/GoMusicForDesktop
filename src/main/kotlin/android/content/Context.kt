package android.content

import android.app.Application
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import java.awt.Desktop
import java.io.File
import java.net.URI

/** 桌面端目录：把 Android 的 cacheDir / 公共 Download / 应用私有目录一一平替 */
object AppDirs {
    val home = File(System.getProperty("user.home"))
    val config = File(home, ".gomusic").apply { mkdirs() }
    val cache = File(config, "cache").apply { mkdirs() }
    val downloadRoot = File(home, "Downloads").apply { mkdirs() }
    val logs = File(File(downloadRoot, "gomusic"), "log").apply { mkdirs() }
}

/** SharedPreferences 平替：java.util.Properties 文件持久化。
 *  用 Properties 而不是手写 JSON —— 歌单/快照里存的是整段 JSON 字符串，
 *  里面全是逗号、冒号、引号，手写解析一定会把它切坏。 */
class SharedPreferences private constructor(private val f: File) {
    private val p = java.util.Properties()
    init { runCatching { if (f.exists()) f.inputStream().use { p.load(it) } } }
    fun getString(k: String, d: String?): String? = p.getProperty(k) ?: d
    fun getInt(k: String, d: Int): Int = p.getProperty(k)?.toIntOrNull() ?: d
    fun getLong(k: String, d: Long): Long = p.getProperty(k)?.toLongOrNull() ?: d
    fun getBoolean(k: String, d: Boolean): Boolean = p.getProperty(k)?.toBooleanStrictOrNull() ?: d
    fun contains(k: String): Boolean = p.containsKey(k)
    fun edit(): Editor = Editor()
    inner class Editor {
        fun putString(k: String, v: String?): Editor { if (v == null) p.remove(k) else p.setProperty(k, v); return this }
        fun putInt(k: String, v: Int): Editor { p.setProperty(k, v.toString()); return this }
        fun putLong(k: String, v: Long): Editor { p.setProperty(k, v.toString()); return this }
        fun putBoolean(k: String, v: Boolean): Editor { p.setProperty(k, v.toString()); return this }
        fun remove(k: String): Editor { p.remove(k); return this }
        fun apply() { commit() }
        fun commit(): Boolean { runCatching { f.outputStream().use { p.store(it, "GoMusic") } }; return true }
    }
    companion object {
        private val all = HashMap<String, SharedPreferences>()
        @Synchronized fun get(name: String): SharedPreferences =
            all.getOrPut(name) { SharedPreferences(File(AppDirs.config, "$name.properties")) }
    }
}

class Resources {
    fun getIdentifier(name: String, defType: String, defPackage: String): Int = 0
    fun getDimensionPixelSize(id: Int): Int = 0
}
class AssetManager

/** 把 content://gomusic/... 映射到 ~/Downloads/... 下的真实文件 */
class ContentResolver {
    fun insert(uri: Uri?, values: ContentValues): Uri? {
        val rel = values.getAsString(android.provider.MediaStore.Downloads.RELATIVE_PATH) ?: return null
        val name = values.getAsString(android.provider.MediaStore.Downloads.DISPLAY_NAME) ?: return null
        values.put("rel", rel)
        val dir = File(AppDirs.downloadRoot, rel)
        dir.mkdirs()
        val f = File(dir, name)
        f.createNewFile()
        return Uri.parse("content://gomusic/" + f.absolutePath.removePrefix(AppDirs.downloadRoot.absolutePath).trimStart('/'))
    }
    fun openOutputStream(uri: Uri?): java.io.OutputStream? = uri?.let { fileOf(it)?.outputStream() }
    fun openInputStream(uri: Uri?): java.io.InputStream? = uri?.let { fileOf(it)?.takeIf { f -> f.exists() }?.inputStream() }
    fun update(uri: Uri?, values: ContentValues, where: String?, args: Array<String>?): Int = 0
    fun delete(uri: Uri?, where: String?, args: Array<String>?): Int {
        val f = fileOf(uri ?: return 0) ?: return 0
        return if (f.delete()) 1 else 0
    }

    private fun fileOf(u: Uri): File? {
        val s = u.toString()
        if (s.startsWith("content://gomusic/")) return File(AppDirs.downloadRoot, s.removePrefix("content://gomusic/"))
        if (s.startsWith("file://")) return File(java.net.URI(s))
        if (s.startsWith("/")) return File(s)
        return null
    }
}

open class Context {
    open val applicationContext: Context get() = AppContext
    open val cacheDir: File get() = AppDirs.cache
    open val filesDir: File get() = AppDirs.config
    open val packageName: String get() = "de.myocd.gomusic"
    open val resources: Resources get() = Resources()
    open val assets: AssetManager get() = AssetManager()
    open val contentResolver: ContentResolver get() = ContentResolver()
    open val packageManager: PackageManager get() = PackageManager()
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences.get(name)
    open fun getSystemService(name: String): Any? = when (name) {
        CLIPBOARD_SERVICE -> ClipboardManager()
        POWER_SERVICE -> android.os.PowerManager()
        NOTIFICATION_SERVICE -> android.app.NotificationManager()
        else -> null
    }
    open fun startActivity(intent: Intent, options: Bundle? = null) {
        val u = intent.data?.toString()
        if (!u.isNullOrBlank()) runCatching { Desktop.getDesktop().browse(URI(u)) }
    }
    open fun startService(intent: Intent): ComponentName? {
        // 桌面没有 Service：把「应用播放焦点策略」直接转给播放器
        if (intent.action == "de.myocd.gomusic.APPLY_FOCUS") {
            runCatching { de.myocd.gomusic.PlaybackService.applyFocus() }
        }
        return intent.component
    }
    open fun getExternalFilesDir(type: String?): File = File(AppDirs.config, type ?: "files").apply { mkdirs() }
    companion object {
        const val MODE_PRIVATE = 0
        const val CLIPBOARD_SERVICE = "clipboard"
        const val POWER_SERVICE = "power"
        const val WIFI_SERVICE = "wifi"
        const val NOTIFICATION_SERVICE = "notification"
        const val CONNECTIVITY_SERVICE = "connectivity"
        const val TELEPHONY_SERVICE = "phone"
    }
}

/** 全局 Application 单例，由桌面入口注入 */
object AppContext : Context() {
    var realApp: Application? = null
    override val applicationContext: Context get() = realApp ?: this
}

open class ContextWrapper(base: Context?) : Context() {
    protected var mBase: Context? = base
    val baseContext: Context get() = mBase ?: AppContext
    fun getBaseContext(): Context? = mBase
}

class ComponentName(val pkg: String, val cls: String) {
    constructor(ctx: Context, c: Class<*>) : this(ctx.packageName, c.name)
    override fun toString(): String = "$pkg/$cls"
}

class Bundle {
    val map = HashMap<String, Any?>()
    fun getString(k: String): String? = map[k] as? String
    fun putString(k: String, v: String?) { map[k] = v }
}

class Intent {
    var action: String? = null
    var data: Uri? = null
    var flags: Int = 0
    var component: ComponentName? = null
    val extras = Bundle()
    constructor()
    constructor(action: String, data: Uri? = null) { this.action = action; this.data = data }
    constructor(ctx: Context, cls: Class<*>) { this.component = ComponentName(ctx, cls) }
    fun setAction(a: String?): Intent { action = a; return this }
    fun setData(u: Uri?): Intent { data = u; return this }
    fun addFlags(f: Int): Intent { flags = flags or f; return this }
    fun setFlags(f: Int): Intent { flags = f; return this }
    fun putExtra(k: String, v: Any?): Intent { extras.map[k] = v; return this }
    fun getStringExtra(k: String): String? = extras.getString(k)
    fun setPackage(p: String?): Intent { return this }
    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
    }
}

class ClipData private constructor(val text: CharSequence?) {
    companion object { fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData = ClipData(text) }
}
class ClipboardManager {
    fun setPrimaryClip(c: ClipData) {
        runCatching {
            val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            cb.setContents(java.awt.datatransfer.StringSelection(c.text?.toString() ?: ""), null)
        }
    }
}

class ContentValues {
    private val m = HashMap<String, Any?>()
    fun put(k: String, v: Any?) { m[k] = v }
    fun getAsString(k: String): String? = m[k]?.toString()
    fun getAsInteger(k: String): Int? = (m[k] as? Number)?.toInt()
    fun get(k: String): Any? = m[k]
}

abstract class BroadcastReceiver {
    abstract fun onReceive(ctx: Context, intent: Intent)
}

/** Context.findActivity() 是手机端在 Screens.kt 里用的扩展，靠 ContextWrapper 往上找 */
fun Context.findActivity(): android.app.Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.getBaseContext()
    }
    return c as? android.app.Activity
}
