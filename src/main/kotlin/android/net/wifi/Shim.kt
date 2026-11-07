package android.net.wifi

class WifiManager {
    inner class WifiLock { fun acquire() {}; fun release() {} }
    fun createWifiLock(type: Int, tag: String): WifiLock = WifiLock()
    companion object { const val WIFI_MODE_FULL_HIGH_PERF = 1 }
}
