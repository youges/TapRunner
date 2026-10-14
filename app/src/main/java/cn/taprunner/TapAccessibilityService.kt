package cn.taprunner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow

open class TapAccessibilityService: AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var volumeHeld = false
    private val connectionId = java.util.UUID.randomUUID().toString().take(8)
    private fun trace(event: String) {
        val enabled = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { android.content.ComponentName.unflattenFromString(it)?.className == javaClass.name } == true
        val progress = TapApp.instance.engine.state.value
        TapApp.instance.store.log("service", "$event component=${javaClass.simpleName} instance=$connectionId current=${instance === this} enabled=$enabled pid=${android.os.Process.myPid()} uptime=${android.os.SystemClock.elapsedRealtime()} status=${progress.status} step=${progress.step}")
    }
    override fun onCreate() { super.onCreate(); trace("CREATE") }
    private val emergency = Runnable { if (volumeHeld) { TapApp.instance.engine.stop("音量键急停"); val vibrator=if(android.os.Build.VERSION.SDK_INT>=31)getSystemService(android.os.VibratorManager::class.java).defaultVibrator else getSystemService(android.os.Vibrator::class.java);vibrator.vibrate(android.os.VibrationEffect.createOneShot(150, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) } }
    override fun onServiceConnected() {
        connections.add(this); refreshConnection()
        // Declare flags once in XML, avoiding unnecessary Binder configuration updates.
        trace("CONNECTED")
        TapApp.instance.engine.accessibilityConnected()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString() != packageName && event.packageName?.toString() != "com.android.systemui") TapApp.instance.runtime.foregroundPkg = event.packageName?.toString().orEmpty() }
    override fun onInterrupt() { trace("INTERRUPT"); if(instance === this) TapApp.instance.engine.accessibilityUnavailable("无障碍服务中断") }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { volumeHeld = true; handler.postDelayed(emergency, 1500) }
            if (event.action == KeyEvent.ACTION_UP) { volumeHeld = false; handler.removeCallbacks(emergency) }
        }
        return false
    }
    override fun onUnbind(intent: Intent?): Boolean {
        trace("UNBIND")
        handler.removeCallbacksAndMessages(null); volumeHeld=false
        disconnect("无障碍已断开，请重新开启")
        return super.onUnbind(intent)
    }
    override fun onRebind(intent: Intent?) { super.onRebind(intent); trace("REBIND") }
    override fun onDestroy() {
        trace("DESTROY"); handler.removeCallbacksAndMessages(null)
        disconnect("无障碍服务已销毁")
        super.onDestroy()
    }
    private fun disconnect(reason: String) {
        val wasCurrent=instance===this
        connections.remove(this); refreshConnection()
        if(wasCurrent) TapApp.instance.engine.accessibilityUnavailable(reason)
    }
    companion object {
        private val connections=mutableSetOf<TapAccessibilityService>()
        var instance: TapAccessibilityService? = null
            private set
        val connected=MutableStateFlow(false)
        val advancedConnected=MutableStateFlow(false)
        private fun refreshConnection() {
            instance=connections.firstOrNull { it !is CoordinateAccessibilityService } ?: connections.firstOrNull()
            connected.value=instance!=null
            advancedConnected.value=connections.any {it !is CoordinateAccessibilityService}
        }
    }
}

class CoordinateAccessibilityService: TapAccessibilityService()
