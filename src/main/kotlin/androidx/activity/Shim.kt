package androidx.activity

import android.content.Context
import android.graphics.Color
import android.os.Bundle

open class ComponentActivity : android.app.Activity()
class SystemBarStyle private constructor(val dark: Int) {
    companion object {
        fun dark(scrim: Int): SystemBarStyle = SystemBarStyle(scrim)
        fun light(scrim: Int, dark: Int): SystemBarStyle = SystemBarStyle(scrim)
    }
}
fun ComponentActivity.enableEdgeToEdge(
    statusBarStyle: SystemBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    navigationBarStyle: SystemBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
) {}
