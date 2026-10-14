package cn.taprunner

import android.content.Context
import androidx.core.text.layoutDirection
import android.view.*
import android.widget.TextView
import java.util.Locale

open class TouchSurface(context: Context): View(context) {
    override fun performClick(): Boolean { super.performClick(); return true }
}
class DragLabel(context: Context): TextView(context) {
    override fun performClick(): Boolean { super.performClick(); return true }
}
/** Pixel coordinates always originate at the physical left, including RTL locales. */
fun overlayGravity(): Int = Gravity.TOP or if (Locale.getDefault().layoutDirection == View.LAYOUT_DIRECTION_RTL) Gravity.END else Gravity.START

/** Collapse repeated move events into one update on the next display frame. */
class FrameWindowUpdater(private val manager: WindowManager, private val view: View, private val parameters: WindowManager.LayoutParams) {
    private var pending = false
    private val update = Runnable { pending=false; if(view.isAttachedToWindow) runCatching {manager.updateViewLayout(view,parameters)} }
    fun schedule() { if(!pending) { pending=true; view.postOnAnimation(update) } }
    fun flush() { view.removeCallbacks(update); pending=false; if(view.isAttachedToWindow) runCatching {manager.updateViewLayout(view,parameters)} }
}
