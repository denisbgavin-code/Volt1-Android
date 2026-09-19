package com.volt1.recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class Pcm24Player {

    private var worker:
        Thread? = null

    private var stopFlag =
        AtomicBoolean(false)

    private var audioTrack:
        AudioTrack? = null

    @Synchronized
    fun play(
        context: Context,
        info: WavInfo,
        startFrame: Long,
        endFrameExclusive: Long,
        onFinished: (String?) -> Unit
    ) {
        stop()

        val localStop =
            AtomicBoolean(false)

        stopFlag =
            localStop

        worker =
            Thread {
                var errorMessage:
                    String? = null

                try {
                    WavFile.validateEditable(
                        info
                    )

                    val minBuffer =
                        AudioTrack.getMinBufferSize(
                            info.sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat
                                .ENCODING_PCM_24BIT_PACKED
                        )

                    if (minBuffer <= 0) {
                        error(
                            "Android не поддерживает " +
                                "PCM24 playback"
                        )
                    }

                    val bufferSize =
                        alignUp(
                            max(
                                minBuffer * 2,
                                48 * 1024
                            ),
                            info.frameSizeBytes
                        )

                    val track =
                        AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(
                                        AudioAttributes
                                            .USAGE_MEDIA
                                    )
                                    .setContentType(
                                        AudioAttributes
                                            .CONTENT_TYPE_MUSIC
                                    )
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(
                                        AudioFormat
                                            .ENCODING_PCM_24BIT_PACKED
                                    )
                                    .setSampleRate(
                                        info.sampleRate
                                    )
                                    .setChannelMask(
                                        AudioFormat
                                            .CHANNEL_OUT_MONO
                                    )
                                    .build()
                            )
                            .setBufferSizeInBytes(
                                bufferSize
                            )
                            .setTransferMode(
                                AudioTrack.MODE_STREAM
                            )
                            .build()

                    audioTrack =
                        track

                    if (
                        track.state !=
                        AudioTrack.STATE_INITIALIZED
                    ) {
                        error(
                            "AudioTrack " +
                                "не инициализирован"
                        )
                    }

                    val safeStart =
                        startFrame.coerceIn(
                            0L,
                            info.totalFrames - 1L
                        )

                    val safeEnd =
                        endFrameExclusive.coerceIn(
                            safeStart + 1L,
                            info.totalFrames
                        )

                    var remaining =
                        (
                            safeEnd -
                                safeStart
                            ) *
                            info.frameSizeBytes

                    val pfd =
                        context.contentResolver
                            .openFileDescriptor(
                                info.uri,
                                "r"
                            )
                            ?: error(
                                "Не удалось открыть WAV"
                            )

                    pfd.use { descriptor ->
                        FileInputStream(
                            descriptor.fileDescriptor
                        ).use { input ->
                            val channel =
                                input.channel

                            channel.position(
                                info.dataOffset +
                                    safeStart *
                                    info.frameSizeBytes
                            )

                            val buffer =
                                ByteBuffer.allocateDirect(
                                    bufferSize
                                )

                            track.play()

                            while (
                                remaining > 0L &&
                                !localStop.get()
                            ) {
                                buffer.clear()

                                val requested =
                                    min(
                                        buffer.capacity()
                                            .toLong(),
                                        remaining
                                    ).toInt()

                                buffer.limit(
                                    requested
                                )

                                var readTotal =
                                    0

                                while (
                                    readTotal <
                                    requested &&
                                    !localStop.get()
                                ) {
                                    val read =
                                        channel.read(
                                            buffer
                                        )

                                    if (
                                        read < 0
                                    ) {
                                        error(
                                            "Неожиданный конец " +
                                                "WAV при playback"
                                        )
                                    }

                                    readTotal +=
                                        read
                                }

                                if (
                                    localStop.get()
                                ) {
                                    break
                                }

                                buffer.flip()

                                while (
                                    buffer.hasRemaining() &&
                                    !localStop.get()
                                ) {
                                    val written =
                                        track.write(
                                            buffer,
                                            buffer.remaining(),
                                            AudioTrack.WRITE_BLOCKING
                                        )

                                    if (
                                        written < 0
                                    ) {
                                        error(
                                            "AudioTrack.write(): " +
                                                written
                                        )
                                    }
                                }

                                remaining -=
                                    readTotal
                            }
                        }
                    }

                } catch (
                    t: Throwable
                ) {
                    errorMessage =
                        t.message
                            ?: t.javaClass
                                .simpleName

                } finally {
                    val track =
                        audioTrack

                    audioTrack =
                        null

                    runCatching {
                        if (
                            track?.playState ==
                            AudioTrack
                                .PLAYSTATE_PLAYING
                        ) {
                            track.stop()
                        }
                    }

                    runCatching {
                        track?.flush()
                    }

                    runCatching {
                        track?.release()
                    }

                    if (
                        !localStop.get()
                    ) {
                        onFinished(
                            errorMessage
                        )
                    }
                }
            }.apply {
                name =
                    "Volt1-Selection-Playback"

                start()
            }
    }

    @Synchronized
    fun stop() {
        stopFlag.set(
            true
        )

        val track =
            audioTrack

        runCatching {
            track?.pause()
        }

        runCatching {
            track?.flush()
        }

        worker =
            null
    }

    private fun alignUp(
        value: Int,
        alignment: Int
    ): Int {
        val remainder =
            value % alignment

        return if (
            remainder == 0
        ) {
            value
        } else {
            value +
                alignment -
                remainder
        }
    }
}
