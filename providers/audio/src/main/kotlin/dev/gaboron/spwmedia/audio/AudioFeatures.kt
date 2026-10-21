package dev.gaboron.spwmedia.audio

import dev.gaboron.spwmedia.protocol.AudioFeatures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Windowed radix-2 FFT and linear peak/RMS; computation is shared per capture source. */
internal fun analyze(bytes: ByteArray, rate: Int, channels: Int, fftSize: Int): AudioFeatures {
    require(rate in 8000..384000 && channels in 1..32 && fftSize in 64..32768 && fftSize and (fftSize - 1) == 0)
    require(bytes.size % (channels * 4) == 0)
    val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val frames = pcm.remaining() / channels
    require(frames > 0)
    var sum = 0.0; var peak = 0.0
    val waveform = FloatArray(min(frames, fftSize))
    for (frame in 0 until frames) {
        var mono = 0.0
        repeat(channels) {
            val value = pcm.get().toDouble().takeIf { it.isFinite() } ?: 0.0
            sum += value * value; peak = max(peak, abs(value)); mono += value / channels
        }
        if (frame >= frames - waveform.size) waveform[frame - (frames - waveform.size)] = mono.toFloat()
    }
    val real = DoubleArray(fftSize) { i -> (waveform.getOrElse(i) { 0f }) * (0.5 - 0.5 * cos(2 * PI * i / (fftSize - 1))) }
    val imaginary = DoubleArray(fftSize)
    var j = 0
    for (i in 1 until fftSize) {
        var bit = fftSize shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
        if (i < j) { val value = real[i]; real[i] = real[j]; real[j] = value }
    }
    var length = 2
    while (length <= fftSize) {
        val angle = -2 * PI / length
        for (start in 0 until fftSize step length) for (offset in 0 until length / 2) {
            val a = start + offset; val b = a + length / 2
            val wr = cos(angle * offset); val wi = sin(angle * offset)
            val br = real[b] * wr - imaginary[b] * wi; val bi = real[b] * wi + imaginary[b] * wr
            real[b] = real[a] - br; imaginary[b] = imaginary[a] - bi; real[a] += br; imaginary[a] += bi
        }
        length = length shl 1
    }
    val rms = sqrt(sum / (frames * channels))
    return AudioFeatures(rate, channels, rms, peak, if (rms > 0) 20 * log10(rms) else -160.0, waveform.toList(), (0 until fftSize / 2).map { hypot(real[it], imaginary[it]) * 2 / fftSize })
}
