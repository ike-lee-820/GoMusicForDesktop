package android.telephony

open class PhoneStateListener {
    open fun onCallStateChanged(state: Int, phoneNumber: String?) {}
}
class TelephonyManager {
    companion object { const val CALL_STATE_IDLE = 0; const val CALL_STATE_OFFHOOK = 2; const val CALL_STATE_RINGING = 1 }
    fun listen(l: PhoneStateListener, events: Int) {}
}
