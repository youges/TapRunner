package cn.taprunner

import android.content.Context
import android.graphics.*
import android.view.*
import cn.taprunner.model.Point

class DraggablePoints(private val context:Context,private val onMove:(String,Point,Boolean)->Unit,private val onTap:(String)->Unit) {
    private val wm=context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density=context.resources.displayMetrics.density
    private val size=(44*density).toInt()
    private val windows=mutableMapOf<String,Handle>()
    fun render(points:List<PointMarker>,visible:Boolean) {
        if(!visible){clear();return}
        val expected=mutableSetOf<String>()
        points.take(500).forEach {entry ->
            fun show(point:Point?,end:Boolean){if(point==null)return;val key="${entry.id}:$end";expected+=key;val existing=windows[key];if(existing==null){val handle=Handle(entry,point,end);wm.addView(handle,handle.parameters);windows[key]=handle}else existing.update(entry,point)}
            show(entry.point,false);show(entry.end,true)
        }
        windows.keys.filter {it !in expected}.forEach {key -> windows.remove(key)?.let {runCatching {wm.removeView(it)}}}
    }
    private inner class Handle(var entry:PointMarker,var point:Point,val end:Boolean):TouchSurface(context) {
        private val displaySize=TapApp.instance.runtime.size()
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX=0f;private var downY=0f;private var start=point;private var moved=false
        val parameters=WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT).apply {gravity=overlayGravity();layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS}
        private val updater=FrameWindowUpdater(wm,this,parameters)
        init {
            position();contentDescription=context.getString(R.string.point_handle,entry.number,if(end)"终点"else"起点")
            setOnClickListener {onTap(entry.id)}
            setOnTouchListener {_,event -> when(event.actionMasked){
                MotionEvent.ACTION_DOWN -> {downX=event.rawX;downY=event.rawY;start=point;moved=false}
                MotionEvent.ACTION_MOVE -> {
                    if(kotlin.math.hypot(event.rawX-downX,event.rawY-downY)>6*density)moved=true
                    if(moved){point=Point((start.x+(event.rawX-downX)/(displaySize.first-1)).coerceIn(0.0,1.0),(start.y+(event.rawY-downY)/(displaySize.second-1)).coerceIn(0.0,1.0));position();updater.schedule()}
                }
                MotionEvent.ACTION_UP -> {updater.flush();if(moved)onMove(entry.id,point,end)else performClick()}
                MotionEvent.ACTION_CANCEL -> {point=start;position();updater.flush()}
            };true}
        }
        private fun position(){parameters.x=(point.x*(displaySize.first-1)).toInt()-size/2;parameters.y=(point.y*(displaySize.second-1)).toInt()-size/2}
        fun update(value:PointMarker,location:Point){if(entry==value&&point==location)return;entry=value;point=location;position();updater.schedule();postInvalidateOnAnimation()}
        override fun onDraw(canvas:Canvas) {
            val cx=width/2f;val cy=height/2f;val radius=width/2f-3*density
            paint.style=Paint.Style.FILL;paint.color=NumberedMarkerLayer.color(entry.number);paint.alpha=210;canvas.drawCircle(cx,cy,radius,paint)
            paint.alpha=255;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density;paint.color=Color.WHITE;canvas.drawCircle(cx,cy,radius,paint)
            paint.strokeWidth=density;canvas.drawLine(cx,0f,cx,height.toFloat(),paint);canvas.drawLine(0f,cy,width.toFloat(),cy,paint)
            paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=(if(end)14 else 18)*density
            canvas.drawText("${entry.number}${if(end)"终"else""}",cx,cy-(paint.ascent()+paint.descent())/2,paint)
        }
    }
    private fun clear(){windows.values.toList().forEach {runCatching {wm.removeView(it)}};windows.clear()}
    fun close(){clear()}
}
