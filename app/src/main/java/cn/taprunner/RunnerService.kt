package cn.taprunner

import android.app.*
import android.content.*
import android.os.IBinder
import android.provider.Settings
import cn.taprunner.engine.Status
import cn.taprunner.engine.forDisplay
import kotlinx.coroutines.*

class RunnerService: Service() {
    private val app get() = application as TapApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: OverlayController? = null
    private val receiver = object: BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { if(intent?.action == Intent.ACTION_SCREEN_OFF) app.engine.stop("息屏，已停止", true) } }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("runner", "脚本运行与急停", NotificationManager.IMPORTANCE_LOW))
        startForeground(7, notification("悬浮控制已就绪"))
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        if(Settings.canDrawOverlays(this)) overlay = OverlayController(this)
        scope.launch {
            var lastStatus: Status? = null
            var lastNotificationAt = 0L
            app.engine.state.forDisplay(app.runtime::now).collect { p ->
                overlay?.render(p)
                val now = android.os.SystemClock.elapsedRealtime()
                if(p.status != lastStatus || now-lastNotificationAt >= 1000) {
                    manager.notify(7, notification("${p.status} · ${p.step} · ${p.message}"))
                    lastStatus=p.status; lastNotificationAt=now
                }
            }
        }
    }
    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, RunnerService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "runner").setSmallIcon(cn.taprunner.R.drawable.ic_tap).setContentTitle("TapRunner 连点器").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(Notification.Action.Builder(null, "立即停止", stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            "run" -> runCatching {require(!app.overlayDraft.dirty){"请先保存或作废悬浮草稿，再运行脚本"};app.engine.start(app.store.current() ?: error("未选择脚本"))}.onFailure {app.engine.stop(it.message?:"无法执行",true)}
            "stop" -> app.engine.stop()
            "close" -> { app.engine.stop(); stopSelf() }
            "record" -> overlay?.pick()
            "edit" -> overlay?.edit()
            "tapRecord" -> overlay?.record()
            "quickSingle" -> overlay?.newQuick(true)
            "quickMulti" -> overlay?.newQuick(false)
            "runDraft" -> runCatching {app.engine.start(app.overlayDraft.current()?:error("先点＋添加点位"))}.onFailure {app.engine.stop(it.message?:"无法执行",true)}
        }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { app.engine.stop("应用被移出最近任务，已停止", true); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() { app.engine.stop("悬浮控制已关闭"); overlay?.close(); unregisterReceiver(receiver); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); app.engine.stop("屏幕方向改变，请确认点位", true); overlay?.close(); overlay = if(Settings.canDrawOverlays(this)) OverlayController(this) else null }
}
