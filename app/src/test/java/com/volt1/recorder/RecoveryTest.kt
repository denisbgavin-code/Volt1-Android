package com.volt1.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.random.Random

class RecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun recover(file: File): Long =
        RandomAccessFile(file, "rw").use {
            WavHeader.recoverStreamed(it.channel, 48_000, 1, 24)
        }

    private fun FileWavWriter.writeSamples(samples: IntArray) {
        val bytes = TestWav.encode(samples)
        write(ByteBuffer.wrap(bytes), bytes.size)
    }

    @Test
    fun keepsExactlyTheCheckpointedAudio() {
        val random = Random(50)
        val validated = TestWav.randomSamples(random, 48_000)
        val unvalidated = TestWav.randomSamples(random, 30_000)
        val file = tmp.newFile()

        FileWavWriter(file).apply {
            writeSamples(validated)
            checkpoint()
            writeSamples(unvalidated)  // written after the last validation
            crash()
        }

        assertEquals(48_000L * 3, recover(file))
        assertEquals(44L + 48_000 * 3, file.length())
        val layout = TestWav.layout(file)
        WavEdit.validateEditable(layout)
        assertEquals(48_000L, layout.totalFrames)
        assertArrayEquals(validated, TestWav.readSamples(file))
    }

    @Test
    fun crashBeforeFirstCheckpointRecoversNothing() {
        val file = tmp.newFile()
        FileWavWriter(file).apply {
            writeSamples(IntArray(20_000) { it })
            crash()
        }
        assertEquals(0L, recover(file))
        assertEquals(44L, file.length())
    }

    @Test
    fun fileShorterThanCheckpointIsCutToWholeFrames() {
        // Power loss: header says more than actually reached the disk.
        val samples = TestWav.randomSamples(Random(51), 10_000)
        val file = tmp.newFile()
        FileWavWriter(file).apply {
            writeSamples(samples)
            checkpoint()
            crash()
        }
        RandomAccessFile(file, "rw").use { it.setLength(44L + 5_000 * 3 + 2) } // torn frame

        assertEquals(5_000L * 3, recover(file))
        assertArrayEquals(samples.copyOf(5_000), TestWav.readSamples(file))
    }

    @Test
    fun oddCheckpointGetsPadByteAndValidRiffSize() {
        val samples = IntArray(7) { it * 1000 - 3000 }
        val file = tmp.newFile()
        FileWavWriter(file).apply {
            writeSamples(samples)
            checkpoint()
            writeSamples(IntArray(5))
            crash()
        }
        assertEquals(21L, recover(file))
        assertEquals(44L + 21 + 1, file.length())
        val riff = ByteBuffer.wrap(file.readBytes(), 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(file.length() - 8, riff.toLong())
        assertArrayEquals(samples, TestWav.readSamples(file))
    }

    @Test
    fun recoveringAFinalizedFileIsIdempotent() {
        val samples = TestWav.randomSamples(Random(52), 12_345)
        val file = TestWav.write(tmp.newFile(), samples)
        val before = file.readBytes()
        assertEquals(12_345L * 3, recover(file))
        assertArrayEquals(before, file.readBytes())
        assertEquals(12_345L * 3, recover(file))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun refusesFilesNotWrittenByThisApp() {
        fun headerFile(rate: Int, channels: Int, bits: Int): File {
            val file = tmp.newFile()
            val header = WavHeader.create(rate, channels, bits, 600)
            file.writeBytes(ByteArray(44).also { header.get(it) } + ByteArray(600))
            return file
        }

        for (file in listOf(
            headerFile(44_100, 1, 24),
            headerFile(48_000, 2, 24),
            headerFile(48_000, 1, 16),
            tmp.newFile().apply { writeBytes(ByteArray(100)) },
            tmp.newFile().apply { writeBytes("RIFF".toByteArray()) }
        )) {
            val before = file.readBytes()
            assertThrows(IllegalStateException::class.java) { recover(file) }
            assertArrayEquals("file must stay untouched", before, file.readBytes())
        }

        // Extra chunk between fmt and data: not our layout -> refused.
        val foreign = tmp.newFile()
        val h = ByteArray(44).also { WavHeader.create(48_000, 1, 24, 0).get(it) }
        "LIST".toByteArray().copyInto(h, 36)
        foreign.writeBytes(h + ByteArray(30))
        assertThrows(IllegalStateException::class.java) { recover(foreign) }
    }

    @Test
    fun randomCrashPointsAlwaysYieldValidatedPrefix() {
        val random = Random(53)
        repeat(60) { iteration ->
            val file = tmp.newFile()
            val all = ArrayList<Int>()
            var checkpointed = 0
            FileWavWriter(file).apply {
                repeat(random.nextInt(1, 30)) {
                    val block = TestWav.randomSamples(random, random.nextInt(1, 5_000))
                    writeSamples(block)
                    all.addAll(block.toList())
                    if (random.nextInt(3) == 0) {
                        checkpoint()
                        checkpointed = all.size
                    }
                }
                crash()
            }

            val recovered = recover(file)
            assertEquals("iteration $iteration", checkpointed * 3L, recovered)
            if (recovered > 0) {
                WavEdit.validateEditable(TestWav.layout(file))
                assertArrayEquals(all.subList(0, checkpointed).toIntArray(), TestWav.readSamples(file))
            } else {
                assertEquals(44L, file.length())
            }
            file.delete()
        }
    }

    @Test
    fun recoveredFileCanBeEditedImmediately() {
        val samples = TestWav.randomSamples(Random(54), 30_000)
        val file = tmp.newFile()
        FileWavWriter(file).apply { writeSamples(samples); checkpoint(); crash() }
        recover(file)
        val out = TestWav.edit(file, tmp.newFile()) { ch, l, sink -> WavEdit.crop(ch, l, sink, 100, 200) }
        assertArrayEquals(samples.copyOfRange(100, 200), out)
    }
}

class RecordingGuardsTest {

    @Test
    fun freeSpaceThresholds() {
        val mb = 1024L * 1024
        assertTrue(RecordingGuards.canStart(-1))          // unknown never blocks
        assertTrue(RecordingGuards.canStart(50 * mb))
        assertFalse(RecordingGuards.canStart(50 * mb - 1))
        assertFalse(RecordingGuards.canStart(0))

        assertFalse(RecordingGuards.mustStop(-1))
        assertFalse(RecordingGuards.mustStop(16 * mb))
        assertTrue(RecordingGuards.mustStop(16 * mb - 1))
        assertTrue(RecordingGuards.mustStop(0))
        // Start threshold leaves room above the stop threshold.
        assertTrue(RecordingGuards.MIN_FREE_TO_START > RecordingGuards.MIN_FREE_WHILE_RECORDING)
    }

    @Test
    fun minutesLeft() {
        assertEquals(0L, RecordingGuards.minutesLeft(-5))
        assertEquals(1L, RecordingGuards.minutesLeft(144_000L * 60))
        assertEquals(10L, RecordingGuards.minutesLeft(144_000L * 600 + 5))
    }

    @Test
    fun fileClassification() {
        assertTrue(RecordingGuards.isRecordingName("Volt1_2026-11-26_22-10-05_24bit_48k.wav"))
        assertFalse(RecordingGuards.isRecordingName("Volt1_Working_Cut_123.wav"))
        // A working edit file is never treated as a capture, whatever its suffix.
        assertFalse(RecordingGuards.isRecordingName("Volt1_Working_Cut_1_24bit_48k.wav"))
        assertFalse(RecordingGuards.isRecordingName("Volt1_2026_edit_1.wav"))
        assertFalse(RecordingGuards.isRecordingName("song.wav"))
        assertFalse(RecordingGuards.isRecordingName(null))

        assertTrue(RecordingGuards.isWorkingName("Volt1_Working_Crop_1.wav"))
        assertFalse(RecordingGuards.isWorkingName("Volt1_2026-11-26_22-10-05_24bit_48k.wav"))
        assertFalse(RecordingGuards.isWorkingName(null))
    }
}
