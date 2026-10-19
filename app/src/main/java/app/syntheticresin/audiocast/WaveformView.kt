package app.syntheticresin.audiocast

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/** Recent measured capture levels; no random bars or idle animation. */
class WaveformView(context: Context, private val tint: Int) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var levels=FloatArray(24)
    fun show(values: FloatArray) {
        if(!levels.contentEquals(values)) { levels=values; invalidate() }
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        paint.shader=LinearGradient(0f,0f,0f,h.toFloat(),tint,(tint and 0x00ffffff) or 0x88000000.toInt(),Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val slot=width.toFloat()/levels.size
        val bar=(slot*0.55f).coerceAtLeast(1f)
        val minimum=3*resources.displayMetrics.density
        levels.forEachIndexed { index,value ->
            val h=(minimum+(height-minimum)*value).coerceAtMost(height.toFloat())
            paint.alpha=if(value<=0f) 65 else 255
            val x=slot*index+(slot-bar)/2
            canvas.drawRoundRect(x,(height-h)/2,x+bar,(height+h)/2,bar/2,bar/2,paint)
        }
    }
}
