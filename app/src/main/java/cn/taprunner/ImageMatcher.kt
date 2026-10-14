package cn.taprunner

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.*

object ImageMatcher {
    // Downscale the ROI, then evaluate NCC. Templates are saved at the same normalized scale.
    suspend fun score(frame: Bitmap, template: Bitmap, roi: List<Double>): Double {
        val scale = min(1.0, 240.0 / frame.width)
        val left = (roi[0] * frame.width).toInt(); val top = (roi[1] * frame.height).toInt()
        val width = max(1, ((roi[2]-roi[0]) * frame.width).toInt()); val height = max(1, ((roi[3]-roi[1]) * frame.height).toInt())
        val crop = Bitmap.createBitmap(frame, left, top, min(width,frame.width-left), min(height,frame.height-top))
        val source = crop.scale(max(1,(width*scale).toInt()), max(1,(height*scale).toInt()), true)
        val target = template.scale(max(2,(template.width*scale).toInt()), max(2,(template.height*scale).toInt()), true)
        try {
            val sw=source.width; val sh=source.height; val tw=target.width; val th=target.height
            if(tw>sw || th>sh) return 0.0
            fun gray(b: Bitmap): DoubleArray { val p=IntArray(b.width*b.height); b.getPixels(p,0,b.width,0,0,b.width,b.height); return DoubleArray(p.size) { val c=p[it]; .299*((c shr 16) and 255)+.587*((c shr 8) and 255)+.114*(c and 255) } }
            val a=gray(source); val b=gray(target); val mean=b.average(); val centered=b.map { it-mean }; val norm=sqrt(centered.sumOf { it*it })
            if(norm<1e-5) return 0.0
            var best=-1.0
            for(y in 0..sh-th) { currentCoroutineContext().ensureActive(); for(x in 0..sw-tw) {
                var sum=0.0; var squares=0.0; var cross=0.0
                for(j in 0 until th) for(i in 0 until tw) { val v=a[(y+j)*sw+x+i]; sum+=v; squares+=v*v; cross+=v*centered[j*tw+i] }
                val d=sqrt(max(0.0,squares-sum*sum/(tw*th))) * norm
                if(d>1e-5) best=max(best,cross/d)
                if(best>=.995) return best
            } }
            return best
        } finally { if(source !== crop) source.recycle(); if(crop !== frame) crop.recycle(); if(target !== template) target.recycle() }
    }
}
