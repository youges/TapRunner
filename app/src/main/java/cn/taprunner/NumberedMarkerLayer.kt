package cn.taprunner

import android.content.Context
import android.graphics.*
import android.view.View
import cn.taprunner.model.Point

data class PointMarker(val id:String,val number:Int,val point:Point?,val end:Point?=null)
class NumberedMarkerLayer(context:Context):View(context) {
    var markers:List<PointMarker> = emptyList()
        set(value){if(field!=value){field=value;order();postInvalidateOnAnimation()}}
    var highlight:String? = null
        set(value){if(field!=value){field=value;order();postInvalidateOnAnimation()}}
    private var ordered:List<PointMarker> = emptyList()
    private fun order(){ordered=markers.take(1000).sortedBy {if(it.id==highlight)1 else 0}}
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val origin=IntArray(2)
    private val displaySize=TapApp.instance.runtime.size()
    init {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);getLocationOnScreen(origin)
        val (screenWidth,screenHeight)=displaySize
        ordered.forEach {marker ->
            val point=marker.point?:return@forEach
            val x=(point.x*(screenWidth-1)-origin[0]).toFloat();val y=(point.y*(screenHeight-1)-origin[1]).toFloat();val color=color(marker.number)
            marker.end?.let {end ->
                paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density
                val ex=(end.x*(screenWidth-1)-origin[0]).toFloat();val ey=(end.y*(screenHeight-1)-origin[1]).toFloat()
                canvas.drawLine(x,y,ex,ey,paint);canvas.drawCircle(ex,ey,5*density,paint)
            }
            paint.style=Paint.Style.FILL;paint.color=color;canvas.drawCircle(x,y,4*density,paint)
            val radius=if(marker.number>=100)17*density else 14*density
            val cx=(x+18*density).coerceIn(radius,maxOf(radius,width-radius));val cy=(y-18*density).coerceIn(radius,maxOf(radius,height-radius))
            paint.style=Paint.Style.STROKE;paint.color=color;paint.strokeWidth=2*density;canvas.drawLine(x,y,cx,cy,paint)
            if(marker.id==highlight){paint.color=Color.WHITE;paint.strokeWidth=3*density;canvas.drawCircle(cx,cy,radius+3*density,paint)}
            paint.style=Paint.Style.FILL;paint.color=color;canvas.drawCircle(cx,cy,radius,paint)
            paint.color=Color.WHITE;paint.typeface=Typeface.DEFAULT_BOLD;paint.textAlign=Paint.Align.CENTER;paint.textSize=12*density
            canvas.drawText(marker.number.toString(),cx,cy-(paint.ascent()+paint.descent())/2,paint)
        }
    }
    companion object {
        private val colors=intArrayOf(0xFFE34C3B.toInt(),0xFF2878DA.toInt(),0xFF16976B.toInt(),0xFF9A4CC7.toInt(),0xFFBC700E.toInt(),0xFFCA3980.toInt())
        fun color(number:Int)=colors[(number-1).coerceAtLeast(0)%colors.size]
    }
}
