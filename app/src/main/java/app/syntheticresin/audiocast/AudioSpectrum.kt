package app.syntheticresin.audiocast

import kotlin.math.*

/** Real stereo FFT spectrum, 24 logarithmic bands from 60 Hz to 16 kHz. */
class AudioSpectrum(enabled: Boolean = true) {
    private var enabled=enabled
    @Synchronized fun setEnabled(value:Boolean) {
        if(enabled!=value) { enabled=value; clear() }
    }
    private val size=2048
    private val left=DoubleArray(size)
    private val right=DoubleArray(size)
    private val real=DoubleArray(size)
    private val imaginary=DoubleArray(size)
    private val power=DoubleArray(size/2)
    private val window=DoubleArray(size) { 0.5-0.5*cos(2*PI*it/(size-1)) }
    private val levels=FloatArray(24)
    private var position=0
    private var frames=0
    @Synchronized fun addPcm(pcm:ByteArray,count:Int) {
        if(!enabled) return
        require(count in 0..pcm.size && count%4==0)
        for(offset in 0 until count step 4) {
            fun sample(index:Int)=((pcm[index].toInt() and 255) or (pcm[index+1].toInt() shl 8)).toShort().toDouble()/32768
            left[position]=sample(offset); right[position]=sample(offset+2)
            position=(position+1)%size
            if(++frames>=4800) { frames=0; analyze() }
        }
    }
    private fun analyze() {
        power.fill(0.0)
        for(channel in arrayOf(left,right)) {
            for(i in 0 until size) { real[i]=channel[(position+i)%size]*window[i]; imaginary[i]=0.0 }
            fft()
            for(i in power.indices) power[i]+=(real[i]*real[i]+imaginary[i]*imaginary[i])/2
        }
        for(band in levels.indices) {
            val low=60.0*(16000.0/60).pow(band/24.0)
            val high=60.0*(16000.0/60).pow((band+1)/24.0)
            val from=(low*size/48000).toInt().coerceAtLeast(1)
            val until=maxOf(from+1,ceil(high*size/48000).toInt()).coerceAtMost(size/2)
            var sum=0.0
            for(bin in from until until) sum+=power[bin]
            val magnitude=sqrt(sum/(until-from))/(size/4.0)
            val db=if(magnitude>0) 20*log10(magnitude) else -120.0
            // Gentle +3 dB/octave visual weighting offsets music's bass-heavy spectrum.
            // Headroom and an absolute noise floor prevent saturation or amplified silence.
            val center=sqrt(low*high)
            val weightedDb=db+(3*log2(center/1000)).coerceIn(-12.0,12.0)
            val target=((weightedDb+66)/66).coerceIn(0.0,1.0).pow(1.5).toFloat()
            val blend=if(target>levels[band]) 0.8f else 0.4f
            levels[band]+=(target-levels[band])*blend
            if(levels[band]<0.001f) levels[band]=0f
        }
    }
    private fun fft() {
        var reversed=0
        for(i in 1 until size) {
            var bit=size shr 1
            while(reversed and bit!=0) { reversed=reversed xor bit; bit=bit shr 1 }
            reversed=reversed xor bit
            if(i<reversed) {
                val r=real[i]; real[i]=real[reversed]; real[reversed]=r
                val im=imaginary[i]; imaginary[i]=imaginary[reversed]; imaginary[reversed]=im
            }
        }
        var length=2
        while(length<=size) {
            val angle=-2*PI/length
            val stepR=cos(angle); val stepI=sin(angle)
            for(start in 0 until size step length) {
                var wr=1.0; var wi=0.0
                for(offset in 0 until length/2) {
                    val even=start+offset; val odd=even+length/2
                    val r=real[odd]*wr-imaginary[odd]*wi
                    val im=real[odd]*wi+imaginary[odd]*wr
                    real[odd]=real[even]-r; imaginary[odd]=imaginary[even]-im
                    real[even]+=r; imaginary[even]+=im
                    val next=wr*stepR-wi*stepI; wi=wr*stepI+wi*stepR; wr=next
                }
            }
            length=length shl 1
        }
    }
    @Synchronized fun snapshot()=levels.copyOf()
    @Synchronized fun clear() { left.fill(0.0); right.fill(0.0); levels.fill(0f); position=0; frames=0 }
}
