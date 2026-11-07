package androidx.core.content

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.Executor
import javax.swing.SwingUtilities

object ContextCompat {
    const val PERMISSION_GRANTED = 0
    const val PERMISSION_DENIED = -1
    fun checkSelfPermission(ctx: Context, permission: String): Int = PERMISSION_GRANTED
    fun getMainExecutor(ctx: Context): Executor = Executor { r -> SwingUtilities.invokeLater(r) }
}
object FileProvider {
    fun getUriForFile(ctx: Context, authority: String, f: File): Uri = Uri.fromFile(f)
}
