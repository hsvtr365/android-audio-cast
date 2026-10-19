package app.syntheticresin.audiocast

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** Three independently weighted spectrum surfaces; foreground ticks only. */
class AudioRingView(context: Context, @Suppress("UNUSED_PARAMETER") tint: Int) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val levels=Array(3){FloatArray(22)}
    private val focus=doubleArrayOf(.22,.52,.80)
    private val spread=doubleArrayOf(.30,.30,.32)
    private val attack=doubleArrayOf(.085,.045,.020)
    private val release=doubleArrayOf(.55,.30,.14)
    private val peakCounts=intArrayOf(6,7,9)
    private val widths=doubleArrayOf(.054,.043,.031)
    private val offsets=doubleArrayOf(-.025,.015,0.0)
    private val fillAlpha=intArrayOf(168,163,199)
    private val reflectionAlpha=intArrayOf(61,66,82)
    private val strengths=Array(3){DoubleArray(peakCounts[it])}
    private val heights=FloatArray(181)
    // Fixed Gaussian shapes are shared by every frame; only audio strengths change.
    private val shapes=Array(3) { layer -> Array(181) { i ->
        val t=i/180.0
        DoubleArray(peakCounts[layer]) { peak ->
            val center=(peak+.5)/peakCounts[layer]+offsets[layer]
            exp(-((t-center)/widths[layer]).pow(2)/2)*sin(PI*t).coerceAtLeast(0.0).pow(.35)
        }
    } }
    private var previous=0L
    private var gradients=arrayOf<Shader>()
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun show(values:FloatArray) {
        val now=android.os.SystemClock.uptimeMillis()
        val dt=if(previous==0L) .05 else ((now-previous)/1000.0).coerceIn(0.0,.1)
        previous=now
        for(layer in 0..2) for(i in 0..21) {
            val position=i/21.0
            val source=position*(values.size-1).coerceAtLeast(0)
            val index=source.toInt()
            val raw=values.getOrElse(index){0f}+(values.getOrElse(index+1){values.getOrElse(index){0f}}-values.getOrElse(index){0f})*(source-index).toFloat()
            // PlaybackCapture FFT uses mean-bin power; normalize its dB scale for the preview display.
            val visual=((raw.coerceIn(0f,1f).toDouble().pow(2.0/3.0)*66+19)/60).coerceIn(0.0,1.0)
            val gated=if(raw<.003f) 0.0 else visual
            val target=(gated*(.25+.85*exp(-((position-focus[layer])/spread[layer]).pow(2)/2))).coerceIn(0.0,1.0)
            val old=levels[layer][i]
            val tau=if(target>old) attack[layer] else release[layer]
            levels[layer][i]=(old+(target-old)*(1-exp(-dt/tau))).toFloat()
        }
        invalidate()
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        if(w<=0)return
        gradients=arrayOf(
            intArrayOf(0xff2375d0.toInt(),0xff7135c1.toInt(),0xffa516d4.toInt()),
            intArrayOf(0xff099fea.toInt(),0xff813cff.toInt(),0xffcc04ed.toInt()),
            intArrayOf(0xff1dd7eb.toInt(),0xff6557ff.toInt(),0xffde21fc.toInt())
        ).map { LinearGradient(0f,0f,w.toFloat(),0f,it,null,Shader.TileMode.CLAMP) }.toTypedArray()
    }
    private fun updateHeights(layer:Int) {
        val count=peakCounts[layer]
        for(peak in 0 until count) {
            val first=peak*22/count;val last=(peak+1)*22/count
            var energy=0f
            for(j in first until last)energy=max(energy,levels[layer][j])
            strengths[layer][peak]=((energy-.12)/.88).coerceAtLeast(0.0).pow(2.1)
        }
        for(i in heights.indices) {
            var total=0.0
            for(peak in 0 until count) total+=strengths[layer][peak]*shapes[layer][i][peak]
            heights[i]=(height*.65*tanh(total*(1.25-layer*.25)/.65)).toFloat()
        }
    }
    override fun onDraw(canvas:Canvas) {
        if(gradients.size!=3)return
        val base=height*.72f
        for(layer in 0..2) {
            updateHeights(layer)
            paint.shader=gradients[layer];paint.style=Paint.Style.FILL
            for(reflection in 0..1) {
                path.rewind();path.moveTo(0f,base)
                for(i in 0..180) {
                    val t=i/180.0
                    path.lineTo((width*t).toFloat(),base+heights[i]*(if(reflection==1) .24f else -1f))
                }
                path.lineTo(width.toFloat(),base);path.close()
                paint.alpha=if(reflection==1) reflectionAlpha[layer] else fillAlpha[layer]
                canvas.drawPath(path,paint)
            }
        }
        paint.shader=null
    }
}
