package cn.taprunner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.*
import android.os.*
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.scale
import cn.taprunner.engine.Runtime
import cn.taprunner.model.*
import cn.taprunner.model.Target
import cn.taprunner.model.Point
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

class AndroidRuntime(private val app: TapApp): Runtime {
    var overlayGestureGate:((Point,Point?)->(() -> Unit)?)?=null
    var foregroundPkg = ""
    private var mismatchSince = 0L
    private var lastFrameAt = 0L
    private val gestureHandler=Handler(Looper.getMainLooper())
    fun size(): Pair<Int, Int> { val bounds = (app.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds; return bounds.width() to bounds.height() }
    override fun now() = SystemClock.elapsedRealtime()
    override fun healthy(expectedPkg: String): String? {
        val svc = TapAccessibilityService.instance ?: return "无障碍未连接"
        if(expectedPkg.isNotBlank() && svc is CoordinateAccessibilityService) return "前台应用校验需要高级识别服务；坐标模式无法检查当前应用"
        if (svc.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0) return "设备不支持无障碍手势"
        if ((app.getSystemService(android.content.Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked || !(app.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isInteractive) return "屏幕已锁定或关闭"
        val battery = (app.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (battery in 0..9) return "电量低于 10%，已停止"
        if (expectedPkg.isNotBlank() && foregroundPkg.isNotBlank() && foregroundPkg != expectedPkg) { if (mismatchSince == 0L) mismatchSince = now(); if (now() - mismatchSince > 5000) return "前台应用不符合脚本设置" } else mismatchSince = 0
        return null
    }
    override suspend fun gesture(from: Point, to: Point?, duration: Long): Boolean {
        val svc = TapAccessibilityService.instance ?: return false
        val (w,h) = size(); val path = Path().apply { moveTo((from.x * (w-1)).toFloat(), (from.y * (h-1)).toFloat()); to?.let { lineTo((it.x * (w-1)).toFloat(), (it.y * (h-1)).toFloat()) } }
        val restore=overlayGestureGate?.invoke(from,to)
        try {
        if(restore!=null)delay(80)
        return withTimeout(duration + 3000) { suspendCancellableCoroutine { cont ->
            val accepted = svc.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), object: AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, gestureHandler)
            if (!accepted && cont.isActive) cont.resume(false)
        } }
        } finally {restore?.invoke()}
    }
    private fun matches(node: AccessibilityNodeInfo, target: Target): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        val text = when(target.find) { "id" -> node.viewIdResourceName.orEmpty(); "desc" -> node.contentDescription?.toString().orEmpty(); else -> node.text?.toString().orEmpty() }
        if (target.find == "id" || target.mode == "equals") return text == target.value
        return if (target.mode == "regex") runCatching { Regex(target.value).containsMatchIn(text) }.getOrDefault(false) else text.contains(target.value)
    }
    suspend fun nodes(): List<AccessibilityNodeInfo> {
        val svc = TapAccessibilityService.instance ?: return emptyList()
        if(svc.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT == 0) return emptyList()
        val roots = svc.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(svc.rootInActiveWindow) }
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>(); roots.forEach(queue::add)
        val result = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty() && result.size < 3000) { val n = queue.removeFirst(); result += n; for(i in 0 until n.childCount) n.getChild(i)?.let(queue::add); if (result.size % 500 == 0) yield() }
        return result
    }
    private suspend fun node(target:Target):AccessibilityNodeInfo? {
        val svc=TapAccessibilityService.instance?:return null
        if(svc.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT==0)return null
        val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
        svc.windows.mapNotNull {it.root}.ifEmpty {listOfNotNull(svc.rootInActiveWindow)}.forEach(queue::add)
        var visited=0
        while(queue.isNotEmpty()&&visited<3000){val node=queue.removeFirst();if(matches(node,target))return node;for(index in 0 until node.childCount)node.getChild(index)?.let(queue::add);visited++;if(visited%500==0)yield()}
        return null
    }
    override suspend fun find(target: Target): Point? { if (target.find == "image") return null; val node = node(target) ?: return null; val r = Rect(); node.getBoundsInScreen(r); val (w,h) = size(); return Point(r.exactCenterX() / (w-1).toDouble(), r.exactCenterY() / (h-1).toDouble()) }
    suspend fun reverse(point: Point): Target? {
        val (w,h) = size(); val x = (point.x * (w-1)).toInt(); val y = (point.y * (h-1)).toInt()
        val n = nodes().filter { val r = Rect(); it.getBoundsInScreen(r); it.isClickable && r.contains(x,y) }.minByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.width().toLong() * r.height() } ?: return null
        return when { !n.viewIdResourceName.isNullOrBlank() -> Target("id", n.viewIdResourceName, "equals"); !n.text.isNullOrBlank() -> Target("text", n.text.toString(), "equals"); !n.contentDescription.isNullOrBlank() -> Target("desc", n.contentDescription.toString(), "equals"); else -> null }
    }
    override suspend fun key(name: String): Boolean {
        val action = when(name) { "back" -> AccessibilityService.GLOBAL_ACTION_BACK; "home" -> AccessibilityService.GLOBAL_ACTION_HOME; "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS; else -> return false }
        return TapAccessibilityService.instance?.performGlobalAction(action) ?: false
    }
    override suspend fun input(target: Target, text: String): Boolean = node(target)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }) ?: false
    override suspend fun launch(pkg: String): Boolean = runCatching { val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return false; app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    override fun log(stepId: String, result: String) = app.store.log(stepId, result)
    suspend fun screenshot(): Bitmap {
        val svc = TapAccessibilityService.instance ?: error("无障碍未连接")
        require(svc.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0){"截图需要在权限检查中开启高级识别服务"}
        val elapsed = now() - lastFrameAt; if (elapsed < 500) delay(500-elapsed)
        return withTimeout(4000) { suspendCancellableCoroutine { cont -> svc.takeScreenshot(Display.DEFAULT_DISPLAY, app.mainExecutor, object: AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) { val buffer = result.hardwareBuffer; val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false); buffer.close(); if (bitmap == null) { if(cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("截图解码失败"))); return }; if(cont.isActive) cont.resume(bitmap) else bitmap.recycle() }
            override fun onFailure(errorCode: Int) { if(cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("截图不可用 ($errorCode)，目标可能禁止截图"))) }
        }) } }.also { lastFrameAt = now() }
    }
    override suspend fun image(step: Step): Boolean {
        val base = File(app.filesDir, "templates").canonicalFile
        val file = File(base, step.template).canonicalFile
        require(file.path.startsWith(base.path + File.separator)) { "模板路径越界" }
        val template=withContext(Dispatchers.IO) {
            val original=BitmapFactory.decodeFile(file.path)?:error("模板不存在，请先截取模板")
            val captureWidth=File(base,step.template+".width").takeIf{it.exists()}?.readText()?.toIntOrNull()?:size().first
            if(captureWidth!=size().first)original.scale(maxOf(2,original.width*size().first/captureWidth),maxOf(2,original.height*size().first/captureWidth),true).also {original.recycle()}else original
        }
        val frame = screenshot()
        try { return withContext(Dispatchers.Default) { ImageMatcher.score(frame, template, step.roi) >= step.threshold } }
        finally { template.recycle(); frame.recycle() }
    }
}
