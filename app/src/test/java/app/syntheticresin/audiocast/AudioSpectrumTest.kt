package app.syntheticresin.audiocast

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class AudioSpectrumTest {
    private fun tone(hz:Double,opposite:Boolean=false):ByteArray=ByteArray(9600*4).also { pcm ->
        for(frame in 0 until 9600) {
            val value=(sin(2*PI*hz*frame/48000)*15000).toInt()
            for(channel in 0..1) {
                val sample=if(channel==1 && opposite) -value else value
                val at=frame*4+channel*2; pcm[at]=sample.toByte(); pcm[at+1]=(sample shr 8).toByte()
            }
        }
    }
    @Test fun backgroundDisablesAnalysisAndClearsStaleBands() {
        val meter=AudioSpectrum(enabled=false); val pcm=tone(1000.0)
        meter.addPcm(pcm,pcm.size)
        assertArrayEquals(FloatArray(24),meter.snapshot(),0f)
        meter.setEnabled(true); meter.addPcm(pcm,pcm.size)
        assertTrue(meter.snapshot().max()>0f)
        meter.setEnabled(false)
        meter.addPcm(pcm,pcm.size)
        assertArrayEquals(FloatArray(24),meter.snapshot(),0f)
    }
    @Test fun silenceAndResetHaveNoInventedSpectrum() {
        val meter=AudioSpectrum(); meter.addPcm(ByteArray(38400),38400)
        assertArrayEquals(FloatArray(24),meter.snapshot(),0f)
        val tone=tone(440.0); meter.addPcm(tone,tone.size)
        assertTrue(meter.snapshot().max()>0.3f)
        meter.clear(); assertArrayEquals(FloatArray(24),meter.snapshot(),0f)
    }
    @Test fun differentFrequenciesAppearInDifferentBandsWithoutPhaseCancellation() {
        val bass=AudioSpectrum(); val high=AudioSpectrum(); val opposite=AudioSpectrum()
        for((meter,pcm) in listOf(bass to tone(440.0),high to tone(4000.0),opposite to tone(440.0,true))) meter.addPcm(pcm,pcm.size)
        val lowBand=bass.snapshot().indices.maxBy { bass.snapshot()[it] }
        val highBand=high.snapshot().indices.maxBy { high.snapshot()[it] }
        assertTrue(lowBand in 7..10); assertTrue(highBand in 17..19)
        assertArrayEquals(bass.snapshot(),opposite.snapshot(),0.001f)
        assertTrue(bass.snapshot().count { it<0.2f }>10)
    }
    @Test fun chunkBoundariesDoNotChangeFrequencyResult() {
        val pcm=tone(1000.0); val whole=AudioSpectrum(); val chunks=AudioSpectrum()
        whole.addPcm(pcm,pcm.size)
        for(offset in pcm.indices step 1024) {
            val piece=pcm.copyOfRange(offset,minOf(offset+1024,pcm.size)); chunks.addPcm(piece,piece.size)
        }
        assertArrayEquals(whole.snapshot(),chunks.snapshot(),0.0001f)
        val snapshot=whole.snapshot(); snapshot.fill(0f)
        assertTrue(whole.snapshot().max()>0f)
    }
}
