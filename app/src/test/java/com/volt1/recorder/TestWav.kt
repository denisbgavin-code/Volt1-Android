package com.volt1.recorder

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.random.Random

/** JVM stand-in for Wav24Writer: same header/finalize code, plain file. */
class FileWavWriter(
    val file: File,
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1
) : PcmSink, AutoCloseable {

    private val channel: FileChannel = FileChannel.open(
        file.toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE
    )

    var dataBytes = 0L
        private set

    init {
        writeAll(WavHeader.create(sampleRate, channels, 24, 0L))
    }

    override fun write(source: ByteBuffer, bytes: Int) {
        require(bytes % (channels * 3) == 0)
        source.limit(bytes)
        source.position(0)
        writeAll(source)
        dataBytes += bytes
    }

    private fun writeAll(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    override fun close() {
        WavHeader.finalize(channel, sampleRate, channels, 24, dataBytes)
        channel.close()
    }
}

object TestWav {

    fun randomSamples(random: Random, count: Int, amplitude: Int = Pcm24.MAX_VALUE): IntArray =
        IntArray(count) { random.nextInt(-amplitude - 1, amplitude + 1).coerceIn(Pcm24.MIN_VALUE, Pcm24.MAX_VALUE) }

    fun encode(samples: IntArray): ByteArray {
        val bytes = ByteArray(samples.size * 3)
        samples.forEachIndexed { i, s -> Pcm24.encode(s, bytes, i * 3) }
        return bytes
    }

    fun decode(bytes: ByteArray): IntArray =
        IntArray(bytes.size / 3) { Pcm24.decode(bytes, it * 3) }

    /** Writes a canonical mono 24/48 WAV through [FileWavWriter]. */
    fun write(file: File, samples: IntArray, blockFrames: Int = 1_000): File {
        FileWavWriter(file).use { writer ->
            var offset = 0
            while (offset < samples.size) {
                val n = minOf(blockFrames, samples.size - offset)
                val bytes = encode(samples.copyOfRange(offset, offset + n))
                writer.write(ByteBuffer.wrap(bytes), bytes.size)
                offset += n
            }
        }
        return file
    }

    fun <T> open(file: File, block: (FileChannel) -> T): T =
        RandomAccessFile(file, "r").use { block(it.channel) }

    fun layout(file: File): WavLayout = open(file) { WavHeader.parse(it) }

    fun readSamples(file: File): IntArray = open(file) { channel ->
        val layout = WavHeader.parse(channel)
        val buffer = ByteBuffer.allocate(layout.dataSize.toInt())
        readFullyAt(channel, buffer, layout.dataOffset)
        decode(buffer.array())
    }

    /** Runs an edit into a new WAV file and returns its samples. */
    fun edit(source: File, target: File, body: (FileChannel, WavLayout, PcmSink) -> Unit): IntArray {
        open(source) { channel ->
            val layout = WavHeader.parse(channel)
            FileWavWriter(target).use { body(channel, layout, it) }
        }
        return readSamples(target)
    }
}
