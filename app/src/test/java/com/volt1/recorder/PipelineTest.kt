package com.volt1.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * End-to-end: the capture loop of RecorderService (PCM32 multi-channel
 * blocks -> channel 0 PCM24 -> WAV) followed by parse, peaks and edits.
 */
class PipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun simulatedRecordingIsBitExactAndEditable() {
        val random = Random(40)
        val sourceChannels = 3                 // the OEM build that reports 3 channels
        val frameSize = sourceChannels * 4
        val blockFrames = 48_000 / 10          // 100 ms, as RecorderService reads
        val blocks = 25                        // 2.5 s

        val applicationBuffer = ByteBuffer.allocateDirect(blockFrames * frameSize).order(ByteOrder.nativeOrder())
        val wavBytes = ByteArray(blockFrames * 3)
        val wavBuffer = ByteBuffer.wrap(wavBytes)
        val converted = Pcm24.ConvertedBlock()
        val expectedList = ArrayList<Int>()
        val meter = ArrayList<Float>()

        val file = tmp.newFile("rec.wav")
        FileWavWriter(file).use { writer ->
            for (block in 0 until blocks) {
                // Short reads must also work as long as they are frame aligned.
                val frames = if (block == 7) 1_000 else blockFrames
                applicationBuffer.clear()
                for (f in 0 until frames) {
                    val ch0 = random.nextInt()
                    expectedList += ch0 shr 8
                    applicationBuffer.putInt(ch0)
                    repeat(sourceChannels - 1) { applicationBuffer.putInt(random.nextInt()) }
                }
                Pcm24.convertChannel0Pcm32ToPcm24(applicationBuffer, frames * frameSize, frameSize, wavBytes, converted)
                writer.write(wavBuffer, converted.bytes)
                meter += converted.peakLinear
            }
        }
        val recorded = expectedList.toIntArray()
        val recordedFrames = recorded.size
        assertEquals(blockFrames * (blocks - 1) + 1_000, recordedFrames)

        val layout = TestWav.layout(file)
        WavEdit.validateEditable(layout)
        assertEquals(recordedFrames.toLong(), layout.totalFrames)
        assertArrayEquals(recorded, TestWav.readSamples(file))
        assertTrue(meter.all { it in 0f..1f })

        val peaks = TestWav.open(file) { ch -> WavEdit.computePeaks(ch, WavHeader.parse(ch), 8_000) }
        assertEquals(8_000, peaks.size)
        assertTrue(peaks.all { it in 0f..1f })

        // Delete 1 s in the middle, then crop the first half of the result.
        val (cutStart, cutEnd) = SelectionMath.selectedFrames(layout.totalFrames, 0.3f, 0.7f)
        val afterDelete = TestWav.edit(file, tmp.newFile()) { ch, l, sink ->
            WavEdit.deleteRange(ch, l, sink, cutStart, cutEnd)
        }
        assertEquals(recordedFrames - (cutEnd - cutStart).toInt(), afterDelete.size)
        assertArrayEquals(recorded.copyOfRange(0, cutStart.toInt() - 240), afterDelete.copyOfRange(0, cutStart.toInt() - 240))
        assertArrayEquals(
            recorded.copyOfRange(cutEnd.toInt() + 240, recordedFrames),
            afterDelete.copyOfRange(cutStart.toInt() + 240, afterDelete.size)
        )
    }
}
