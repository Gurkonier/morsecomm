package uitls

import kotlinx.coroutines.*
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.sin

class MorseTone(var frequency: Int, var volume: Double) {
    var audioJob: Job? = null

    val rate = 44100f

    val buf = ByteArray(2)
    val audioF = AudioFormat(rate, 16, 1, true, false)

    //sampleRate, sampleSizeInBits,channels,signed,bigEndian
    var sourceDL = AudioSystem.getSourceDataLine(audioF)

    init {
        sourceDL = AudioSystem.getSourceDataLine(audioF)
        sourceDL.open(audioF)
        sourceDL.start()
    }

    fun start(newFrequency: Int? = null, newVolume: Double? = null) {
        if (audioJob?.isActive == true) return
        newFrequency?.let { this.frequency = it }
        newVolume?.let { this.volume = it }

        audioJob = CoroutineScope(Dispatchers.Default).launch {
            var i = 0
            while (isActive) {
                val angle = i * (frequency / rate) * 2.0 * Math.PI
                val value = (sin(angle) * volume).toInt()
                buf[0] = (value and 0xFF).toByte()
                buf[1] = ((value shr 8) and 0xFF).toByte()
                sourceDL.write(buf, 0, 2)
                i++
                if (i >= rate) i = 0
            }
        }
    }

    fun stop() {
        audioJob?.cancel()
        audioJob = null
        sourceDL.flush()
    }
}