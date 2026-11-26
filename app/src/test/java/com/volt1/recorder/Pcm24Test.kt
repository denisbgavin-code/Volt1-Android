package com.volt1.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random

class Pcm24Test {

    @Test
    fun encodeDecodeRoundTripsEdgeValues() {
        val values = intArrayOf(
            0, 1, -1, 127, 128, -128, -129, 255, 256, 0x7fff, 0x8000, -0x8000,
            0x7fffff, -0x800000, 0x123456, -0x123456, 0x400000, -0x400001
        )
        val bytes = ByteArray(3)
        for (v in values) {
            Pcm24.encode(v, bytes, 0)
            assertEquals("value $v", v, Pcm24.decode(bytes, 0))
        }
    }

    @Test
    fun encodeDecodeRoundTripsEveryHighByteAndRandomValues() {
        val bytes = ByteArray(3)
        for (hi in -128..127) {
            for (lo in intArrayOf(0, 1, 0x7f, 0x80, 0xff, 0xabcd, 0xffff)) {
                val v = (hi shl 16) or lo
                Pcm24.encode(v, bytes, 0)
                assertEquals(v, Pcm24.decode(bytes, 0))
            }
        }
        val random = Random(1)
        repeat(200_000) {
            val v = random.nextInt(Pcm24.MIN_VALUE, Pcm24.MAX_VALUE + 1)
            Pcm24.encode(v, bytes, 0)
            assertEquals(v, Pcm24.decode(bytes, 0))
        }
    }

    @Test
    fun encodingIsLittleEndian() {
        val bytes = ByteArray(3)
        Pcm24.encode(0x123456, bytes, 0)
        assertArrayEquals(byteArrayOf(0x56, 0x34, 0x12), bytes)
        Pcm24.encode(-2, bytes, 0)
        assertArrayEquals(byteArrayOf(0xfe.toByte(), 0xff.toByte(), 0xff.toByte()), bytes)
        Pcm24.encode(Pcm24.MIN_VALUE, bytes, 0)
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte()), bytes)
    }

    @Test
    fun decodeMatchesOriginalSignExtensionImplementation() {
        val random = Random(2)
        val bytes = ByteArray(3)
        repeat(100_000) {
            random.nextBytes(bytes)
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            val b2 = bytes[2].toInt() and 0xff
            var expected = b0 or (b1 shl 8) or (b2 shl 16)
            if ((expected and 0x800000) != 0) expected = expected or -0x1000000
            assertEquals(expected, Pcm24.decode(bytes, 0))
        }
    }

    @Test
    fun levelConversions() {
        assertEquals(0f, Pcm24.peakToLinear(0), 0f)
        assertEquals(1f, Pcm24.peakToLinear(Pcm24.MAX_VALUE), 0f)
        assertEquals(1f, Pcm24.peakToLinear(8_388_608), 0f) // |MIN| is clamped
        assertEquals(0.5f, Pcm24.peakToLinear(4_194_304), 1e-6f)

        assertEquals(Pcm24.SILENCE_DB, Pcm24.linearToDb(0f), 0f)
        assertEquals(Pcm24.SILENCE_DB, Pcm24.linearToDb(-1f), 0f)
        assertEquals(0f, Pcm24.linearToDb(1f), 0f)
        assertEquals(-6.0206f, Pcm24.linearToDb(0.5f), 1e-3f)
        assertEquals(Pcm24.SILENCE_DB, Pcm24.linearToDb(1e-9f), 0f)
        // One LSB of 24-bit is about -138 dB -> clamped to the -120 floor.
        assertEquals(Pcm24.SILENCE_DB, Pcm24.linearToDb(Pcm24.peakToLinear(1)), 0f)
    }

    @Test
    fun peakAbsHandlesNegativeFullScale() {
        val bytes = TestWav.encode(intArrayOf(3, -7, Pcm24.MIN_VALUE, 5))
        assertEquals(8_388_608, Pcm24.peakAbs(bytes, 0, 4))
        assertEquals(7, Pcm24.peakAbs(bytes, 0, 2))
        assertEquals(5, Pcm24.peakAbs(bytes, 9, 1))
        assertEquals(0, Pcm24.peakAbs(bytes, 0, 0))
    }

    // ---- PCM32 -> PCM24 capture conversion -------------------------------

    private fun pcm32Block(frames: Array<IntArray>): ByteBuffer {
        val channels = frames.first().size
        val buffer = ByteBuffer.allocateDirect(frames.size * channels * 4).order(ByteOrder.nativeOrder())
        for (frame in frames) for (sample in frame) buffer.putInt(sample)
        buffer.clear()
        return buffer
    }

    @Test
    fun convertsOnlyChannelZeroForAnyChannelCount() {
        val random = Random(3)
        for (channels in 1..8) {
            val frames = Array(4_800) { IntArray(channels) { random.nextInt() } }
            val source = pcm32Block(frames)
            val out = ByteArray(frames.size * 3)

            val result = Pcm24.convertChannel0Pcm32ToPcm24(
                source, frames.size * channels * 4, channels * 4, out
            )

            assertEquals(frames.size * 3, result.bytes)
            val decoded = TestWav.decode(out)
            var peak = 0L
            for (i in frames.indices) {
                val expected = frames[i][0] shr 8
                assertEquals("ch=$channels frame=$i", expected, decoded[i])
                peak = maxOf(peak, abs(expected.toLong()))
            }

            val expectedLinear = (peak.toDouble() / 8_388_607.0).toFloat().coerceIn(0f, 1f)
            assertEquals(expectedLinear, result.peakLinear, 0f)
            val expectedDb = if (expectedLinear <= 0f) -120f
            else (20.0 * log10(expectedLinear.toDouble())).toFloat().coerceAtLeast(-120f)
            assertEquals(expectedDb, result.peakDb, 0f)
        }
    }

    @Test
    fun conversionKeepsTop24BitsExactly() {
        val samples = intArrayOf(
            Int.MAX_VALUE, Int.MIN_VALUE, 0, 255, 256, -1, -256, -257, 0x7fffff00, 0x12345678
        )
        val source = pcm32Block(Array(samples.size) { intArrayOf(samples[it], 999) })
        val out = ByteArray(samples.size * 3)
        Pcm24.convertChannel0Pcm32ToPcm24(source, samples.size * 8, 8, out)
        val decoded = TestWav.decode(out)
        assertEquals(Pcm24.MAX_VALUE, decoded[0])
        assertEquals(Pcm24.MIN_VALUE, decoded[1])
        assertEquals(0, decoded[2])
        assertEquals(0, decoded[3])          // below 24-bit LSB: truncated
        assertEquals(1, decoded[4])
        assertEquals(-1, decoded[5])         // arithmetic shift rounds toward -inf
        assertEquals(-1, decoded[6])
        assertEquals(-2, decoded[7])
        assertEquals(0x7fffff, decoded[8])
        assertEquals(0x123456, decoded[9])
    }

    @Test
    fun conversionPeakOfSilenceAndFullScale() {
        val out = ByteArray(30)
        val silence = Pcm24.convertChannel0Pcm32ToPcm24(pcm32Block(Array(10) { intArrayOf(0) }), 40, 4, out)
        assertEquals(0f, silence.peakLinear, 0f)
        assertEquals(-120f, silence.peakDb, 0f)

        val full = Pcm24.convertChannel0Pcm32ToPcm24(
            pcm32Block(Array(10) { intArrayOf(Int.MIN_VALUE) }), 40, 4, out
        )
        assertEquals(1f, full.peakLinear, 0f)
        assertEquals(0f, full.peakDb, 0f)
    }

    @Test
    fun conversionReusesResultObjectAndEmptyBlock() {
        val result = Pcm24.ConvertedBlock()
        val out = ByteArray(3)
        val returned = Pcm24.convertChannel0Pcm32ToPcm24(pcm32Block(arrayOf(intArrayOf(1 shl 30))), 4, 4, out, result)
        assertTrue(returned === result)
        assertEquals(3, result.bytes)

        Pcm24.convertChannel0Pcm32ToPcm24(pcm32Block(arrayOf(intArrayOf(0))), 0, 4, out, result)
        assertEquals(0, result.bytes)
        assertEquals(-120f, result.peakDb, 0f)
    }

    @Test
    fun conversionRejectsInvalidInput() {
        val src = pcm32Block(Array(4) { intArrayOf(1, 2) })
        assertThrows(IllegalArgumentException::class.java) {
            Pcm24.convertChannel0Pcm32ToPcm24(src, 12, 8, ByteArray(100))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Pcm24.convertChannel0Pcm32ToPcm24(src, 16, 6, ByteArray(100))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Pcm24.convertChannel0Pcm32ToPcm24(src, 16, 0, ByteArray(100))
        }
        assertThrows(IllegalStateException::class.java) {
            Pcm24.convertChannel0Pcm32ToPcm24(src, 32, 8, ByteArray(11))
        }
    }

    // ---- fades ------------------------------------------------------------

    @Test
    fun fadeOutAndInMatchOriginalFormula() {
        val random = Random(4)
        for (frames in intArrayOf(2, 3, 17, 240)) {
            for (fadeIn in booleanArrayOf(true, false)) {
                val samples = TestWav.randomSamples(random, frames)
                val bytes = TestWav.encode(samples)
                Pcm24.applyLinearFade(bytes, frames, fadeIn)
                val faded = TestWav.decode(bytes)

                for (i in 0 until frames) {
                    val ratio = i.toDouble() / (frames - 1).toDouble()
                    val gain = if (fadeIn) ratio else 1.0 - ratio
                    val expected = (samples[i] * gain).toInt().coerceIn(-8_388_608, 8_388_607)
                    assertEquals("frames=$frames fadeIn=$fadeIn i=$i", expected, faded[i])
                }

                assertEquals(if (fadeIn) 0 else samples[0], faded[0])
                assertEquals(if (fadeIn) samples[frames - 1] else 0, faded[frames - 1])
            }
        }
    }

    @Test
    fun fadeNeverIncreasesMagnitude() {
        val random = Random(5)
        val samples = TestWav.randomSamples(random, 240)
        for (fadeIn in booleanArrayOf(true, false)) {
            val bytes = TestWav.encode(samples)
            Pcm24.applyLinearFade(bytes, samples.size, fadeIn)
            TestWav.decode(bytes).forEachIndexed { i, v ->
                assertTrue(abs(v.toLong()) <= abs(samples[i].toLong()))
            }
        }
    }

    @Test
    fun singleFrameFadeIsNoOp() {
        val bytes = TestWav.encode(intArrayOf(12345))
        Pcm24.applyLinearFade(bytes, 1, fadeIn = true)
        assertEquals(12345, Pcm24.decode(bytes, 0))
        Pcm24.applyLinearFade(bytes, 0, fadeIn = false)
        assertEquals(12345, Pcm24.decode(bytes, 0))
    }
}
