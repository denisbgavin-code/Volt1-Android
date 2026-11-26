package com.volt1.recorder

import java.nio.ByteBuffer
import kotlin.math.log10

/**
 * Pure (Android-free) PCM24 helpers shared by capture, editing and playback.
 * Kept free of android.* so it is covered by JVM unit tests.
 */
object Pcm24 {

    const val BYTES_PER_SAMPLE = 3
    const val MIN_VALUE = -8_388_608
    const val MAX_VALUE = 8_388_607
    const val SILENCE_DB = -120f

    private const val FULL_SCALE = 8_388_607.0

    /** Decodes one signed little-endian 24-bit sample. */
    fun decode(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            (bytes[offset + 2].toInt() shl 16))

    /** Encodes one sample (must already be in 24-bit range) little-endian. */
    fun encode(sample: Int, bytes: ByteArray, offset: Int) {
        bytes[offset] = sample.toByte()
        bytes[offset + 1] = (sample shr 8).toByte()
        bytes[offset + 2] = (sample shr 16).toByte()
    }

    /** Absolute peak of a 24-bit sample normalized to 0..1. */
    fun peakToLinear(peakAbs: Int): Float =
        if (peakAbs <= 0) 0f else (peakAbs / FULL_SCALE).toFloat().coerceIn(0f, 1f)

    fun linearToDb(linear: Float): Float =
        if (linear <= 0f) {
            SILENCE_DB
        } else {
            (20.0 * log10(linear.toDouble())).toFloat().coerceAtLeast(SILENCE_DB)
        }

    /** Peak absolute value of [frameCount] mono PCM24 samples from [bytes]. */
    fun peakAbs(bytes: ByteArray, offset: Int, frameCount: Int): Int {
        var peak = 0
        var index = offset
        val end = offset + frameCount * BYTES_PER_SAMPLE
        while (index < end) {
            val sample = decode(bytes, index)
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peak) peak = magnitude
            index += BYTES_PER_SAMPLE
        }
        return peak
    }

    /**
     * Linear fade applied in place to mono PCM24 [bytes].
     * Gain runs 0 -> 1 (fade-in) or 1 -> 0 (fade-out) inclusive over
     * [frameCount] frames; a single frame is passed through unchanged.
     */
    fun applyLinearFade(bytes: ByteArray, frameCount: Int, fadeIn: Boolean) {
        if (frameCount <= 1) return
        val denominator = (frameCount - 1).toDouble()
        for (index in 0 until frameCount) {
            val ratio = index / denominator
            val gain = if (fadeIn) ratio else 1.0 - ratio
            val offset = index * BYTES_PER_SAMPLE
            val scaled = (decode(bytes, offset) * gain).toInt()
                .coerceIn(MIN_VALUE, MAX_VALUE)
            encode(scaled, bytes, offset)
        }
    }

    class ConvertedBlock {
        var bytes: Int = 0
        var peakLinear: Float = 0f
        var peakDb: Float = SILENCE_DB
    }

    /**
     * Copies channel index 0 of interleaved native-order PCM32 (signed Q.31)
     * frames into packed little-endian mono PCM24 by an arithmetic shift of 8.
     * All other channels are deliberately ignored. No dither, no gain.
     */
    fun convertChannel0Pcm32ToPcm24(
        source: ByteBuffer,
        sourceBytes: Int,
        sourceFrameSizeBytes: Int,
        destination: ByteArray,
        result: ConvertedBlock = ConvertedBlock()
    ): ConvertedBlock {
        require(sourceFrameSizeBytes >= 4 && sourceFrameSizeBytes % 4 == 0) {
            "Некорректный PCM32 frame size $sourceFrameSizeBytes"
        }
        require(sourceBytes >= 0 && sourceBytes % sourceFrameSizeBytes == 0) {
            "PCM32 block не кратен frame size"
        }

        val frameCount = sourceBytes / sourceFrameSizeBytes
        val outputBytes = frameCount * BYTES_PER_SAMPLE

        if (destination.size < outputBytes) {
            error("Недостаточный PCM24 output buffer")
        }

        var peak = 0
        var sourceOffset = 0
        var destinationOffset = 0

        repeat(frameCount) {
            val q23 = source.getInt(sourceOffset) shr 8
            encode(q23, destination, destinationOffset)
            val magnitude = if (q23 < 0) -q23 else q23
            if (magnitude > peak) peak = magnitude
            sourceOffset += sourceFrameSizeBytes
            destinationOffset += BYTES_PER_SAMPLE
        }

        val linear = peakToLinear(peak)
        result.bytes = outputBytes
        result.peakLinear = linear
        result.peakDb = linearToDb(linear)
        return result
    }
}
