package com.volt1.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class WavHeaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun le32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
    private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    private fun fmtPayload(
        format: Int = 1, channels: Int = 1, rate: Int = 48_000, bits: Int = 24, extra: Int = 0
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(le16(format)); out.write(le16(channels)); out.write(le32(rate.toLong()))
        out.write(le32((rate * channels * bits / 8).toLong())); out.write(le16(channels * bits / 8))
        out.write(le16(bits))
        if (extra > 0) { out.write(le16(extra - 2)); out.write(ByteArray(extra - 2)) }
        return out.toByteArray()
    }

    private fun chunk(id: String, payload: ByteArray, declaredSize: Long = payload.size.toLong()): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.write(le32(declaredSize))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun riff(vararg chunks: ByteArray, form: String = "WAVE", magic: String = "RIFF"): File {
        val body = ByteArrayOutputStream()
        body.write(form.toByteArray(Charsets.US_ASCII))
        chunks.forEach { body.write(it) }
        val file = tmp.newFile()
        file.writeBytes(magic.toByteArray(Charsets.US_ASCII) + le32(body.size().toLong()) + body.toByteArray())
        return file
    }

    @Test
    fun canonicalHeaderBytes() {
        val header = WavHeader.create(48_000, 1, 24, 300L)
        val bytes = ByteArray(header.remaining()).also { header.get(it) }
        val expected = "RIFF".toByteArray() + le32(336) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) + le32(48_000) +
            le32(144_000) + le16(3) + le16(24) + "data".toByteArray() + le32(300)
        assertArrayEquals(expected, bytes)
        assertEquals(44, bytes.size)
    }

    @Test
    fun oddPayloadCountsPadByteInRiffSize() {
        val header = WavHeader.create(48_000, 1, 24, 3L)
        assertEquals(36 + 3 + 1, header.order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        assertEquals(3, header.getInt(40))
    }

    @Test
    fun rejectsSizesOutsideRiffRange() {
        assertThrows(IllegalArgumentException::class.java) { WavHeader.create(48_000, 1, 24, -1L) }
        assertThrows(IllegalArgumentException::class.java) {
            WavHeader.create(48_000, 1, 24, WavHeader.MAX_PCM_BYTES + 1)
        }
        // The largest allowed size still has a RIFF size that fits in uint32.
        val max = WavHeader.create(48_000, 1, 24, WavHeader.MAX_PCM_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val riffSize = max.getInt(4).toLong() and 0xffffffffL
        assertTrue(riffSize <= 0xffffffffL)
        assertEquals(WavHeader.MAX_PCM_BYTES, max.getInt(40).toLong() and 0xffffffffL)
    }

    @Test
    fun writerRoundTripForEvenAndOddFrameCounts() {
        val random = Random(10)
        for (frames in intArrayOf(1, 2, 3, 999, 1000, 48_001)) {
            val samples = TestWav.randomSamples(random, frames)
            val file = TestWav.write(tmp.newFile(), samples, blockFrames = 777)
            val layout = TestWav.layout(file)

            assertEquals(1, layout.audioFormat)
            assertEquals(1, layout.channels)
            assertEquals(48_000, layout.sampleRate)
            assertEquals(24, layout.bitsPerSample)
            assertEquals(44L, layout.dataOffset)
            assertEquals(frames * 3L, layout.dataSize)
            assertEquals(frames.toLong(), layout.totalFrames)

            val pad = (frames * 3L) and 1L
            assertEquals(44L + frames * 3L + pad, file.length())

            val riffSize = ByteBuffer.wrap(file.readBytes(), 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            assertEquals(file.length() - 8, riffSize.toLong())

            assertArrayEquals(samples, TestWav.readSamples(file))
        }
    }

    @Test
    fun durationMath() {
        val layout = WavLayout(1, 1, 48_000, 24, 44, 48_000L * 3 * 90)
        assertEquals(48_000L * 90, layout.totalFrames)
        assertEquals(90_000L, layout.durationMs)
        assertEquals(3, layout.frameSizeBytes)
        assertEquals(44L + 30L, layout.frameOffset(10))
        assertEquals(0L, WavLayout(1, 0, 48_000, 24, 44, 99).totalFrames)
        assertEquals(0L, WavLayout(1, 1, 0, 24, 44, 99).durationMs)
        // 10 hour file: no overflow
        val long = WavLayout(1, 1, 48_000, 24, 44, 48_000L * 3 * 36_000)
        assertEquals(36_000_000L, long.durationMs)
    }

    @Test
    fun skipsUnknownChunksIncludingOddSizedOnesWithPadding() {
        val data = TestWav.encode(intArrayOf(1, -2, 3))
        val file = riff(
            chunk("JUNK", ByteArray(5)),              // odd -> pad byte
            chunk("fmt ", fmtPayload(extra = 4)),      // fmt with cbSize extension
            chunk("LIST", "INFOtest".toByteArray()),
            chunk("data", data)
        )
        val layout = TestWav.layout(file)
        assertEquals(1, layout.audioFormat)
        assertEquals(48_000, layout.sampleRate)
        assertEquals(9L, layout.dataSize)
        assertEquals(12L + 14 + 28 + 16 + 8, layout.dataOffset)
    }

    @Test
    fun dataBeforeFmtIsAccepted() {
        val file = riff(chunk("data", TestWav.encode(intArrayOf(5, 6))), chunk("fmt ", fmtPayload()))
        val layout = TestWav.layout(file)
        assertEquals(20L, layout.dataOffset)
        assertEquals(6L, layout.dataSize)
        assertEquals(24, layout.bitsPerSample)
    }

    @Test
    fun reportsOtherFormatsSoValidationCanRejectThem() {
        val stereo16 = riff(chunk("fmt ", fmtPayload(channels = 2, bits = 16, rate = 44_100)), chunk("data", ByteArray(8)))
        val layout = TestWav.layout(stereo16)
        assertEquals(2, layout.channels)
        assertEquals(16, layout.bitsPerSample)
        assertEquals(44_100, layout.sampleRate)
        assertThrows(IllegalStateException::class.java) { WavEdit.validateEditable(layout) }

        val float = TestWav.layout(riff(chunk("fmt ", fmtPayload(format = 3, bits = 32)), chunk("data", ByteArray(8))))
        assertEquals(3, float.audioFormat)
    }

    private fun assertParseFails(file: File, messagePart: String) {
        val e = assertThrows(IllegalStateException::class.java) { TestWav.layout(file) }
        assertTrue("got '${e.message}'", e.message!!.contains(messagePart))
    }

    @Test
    fun failsClosedOnDamagedFiles() {
        assertParseFails(riff(chunk("fmt ", fmtPayload()), chunk("data", ByteArray(3)), magic = "RIFX"), "RIFF/WAVE")
        assertParseFails(riff(chunk("fmt ", fmtPayload()), form = "AVI "), "RIFF/WAVE")
        assertParseFails(riff(chunk("fmt ", fmtPayload())), "fmt/data")
        assertParseFails(riff(chunk("data", ByteArray(3))), "fmt/data")
        assertParseFails(riff(chunk("fmt ", ByteArray(14), 14)), "fmt chunk")
        assertParseFails(riff(chunk("fmt ", fmtPayload()), chunk("data", ByteArray(3), declaredSize = 3000)), "data")
        assertParseFails(riff(chunk("fmt ", fmtPayload(channels = 0)), chunk("data", ByteArray(3))), "fmt/data")

        val tiny = tmp.newFile().apply { writeBytes("RIFF".toByteArray()) }
        assertParseFails(tiny, "конец")

        val empty = tmp.newFile()
        assertParseFails(empty, "конец")
    }

    @Test
    fun streamingPlaceholderHeaderParsesAsEmpty() {
        // A crash mid-recording leaves the placeholder header (data size 0).
        val file = tmp.newFile()
        FileWavWriter(file).apply {
            write(ByteBuffer.wrap(TestWav.encode(IntArray(10))), 30)
        }
        // not closed -> header still says 0 bytes
        val layout = TestWav.layout(file)
        assertEquals(0L, layout.dataSize)
        assertThrows(IllegalStateException::class.java) { WavEdit.validateEditable(layout) }
    }

    @Test
    fun randomGarbageNeverCrashesWithUnexpectedExceptions() {
        val random = Random(11)
        repeat(2_000) {
            val size = random.nextInt(0, 200)
            val bytes = random.nextBytes(size)
            if (size >= 12 && random.nextBoolean()) {
                "RIFF".toByteArray().copyInto(bytes, 0)
                "WAVE".toByteArray().copyInto(bytes, 8)
            }
            val file = tmp.newFile()
            file.writeBytes(bytes)
            try {
                val layout = TestWav.layout(file)
                assertTrue(layout.dataOffset + layout.dataSize <= file.length())
                assertTrue(layout.channels > 0 && layout.sampleRate != 0 && layout.bitsPerSample > 0)
            } catch (expected: IllegalStateException) {
                // fail-closed parse error is fine
            }
            file.delete()
        }
    }
}
