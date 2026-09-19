package com.volt1.recorder

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class EditorActivity : Activity() {

    private lateinit var recordingSpinner: Spinner
    private lateinit var fileInfoView: TextView
    private lateinit var selectionView: TextView
    private lateinit var editorStatusView: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var playButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveButton: Button

    private val player = Pcm24Player()

    private var recordings: List<RecordingItem> =
        emptyList()

    private var currentInfo: WavInfo? = null
    private var loadGeneration = 0
    private var updatingSelectionControls = false

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        recordingSpinner =
            findViewById(R.id.recordingSpinner)

        fileInfoView =
            findViewById(R.id.editorFileInfo)

        selectionView =
            findViewById(R.id.selectionInfo)

        editorStatusView =
            findViewById(R.id.editorStatus)

        waveformView =
            findViewById(R.id.waveform)

        startSeek =
            findViewById(R.id.startSeek)

        endSeek =
            findViewById(R.id.endSeek)

        playButton =
            findViewById(R.id.playSelection)

        stopButton =
            findViewById(R.id.stopPlayback)

        saveButton =
            findViewById(R.id.saveSelection)

        findViewById<Button>(
            R.id.backToRecorder
        ).setOnClickListener {
            finish()
        }

        findViewById<Button>(
            R.id.refreshRecordings
        ).setOnClickListener {
            refreshRecordings()
        }

        recordingSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (position in recordings.indices) {
                        loadRecording(
                            recordings[position]
                        )
                    }
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) = Unit
            }

        waveformView.onSelectionChanged =
            { start, end ->
                if (!updatingSelectionControls) {
                    updatingSelectionControls = true

                    startSeek.progress =
                        (start * SEEK_MAX).toInt()

                    endSeek.progress =
                        (end * SEEK_MAX).toInt()

                    updatingSelectionControls = false
                    updateSelectionText()
                }
            }

        startSeek.max = SEEK_MAX
        endSeek.max = SEEK_MAX

        startSeek.setOnSeekBarChangeListener(
            SelectionSeekListener(
                isStart = true
            )
        )

        endSeek.setOnSeekBarChangeListener(
            SelectionSeekListener(
                isStart = false
            )
        )

        playButton.setOnClickListener {
            playSelection()
        }

        stopButton.setOnClickListener {
            player.stop()

            editorStatusView.text =
                "Воспроизведение остановлено"
        }

        saveButton.setOnClickListener {
            saveSelection()
        }

        setEditorEnabled(false)
        refreshRecordings()
    }

    override fun onDestroy() {
        player.stop()
        super.onDestroy()
    }

    private fun refreshRecordings(
        selectName: String? = null
    ) {
        player.stop()
        currentInfo = null
        setEditorEnabled(false)

        editorStatusView.text =
            "Поиск записей…"

        Thread {
            val items = queryRecordings()

            runOnUiThread {
                recordings = items

                val adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_item,
                    items.map { it.name }
                ).apply {
                    setDropDownViewResource(
                        android.R.layout
                            .simple_spinner_dropdown_item
                    )
                }

                recordingSpinner.adapter = adapter

                if (items.isEmpty()) {
                    fileInfoView.text =
                        "В папке Music/Volt1 Recorder " +
                            "нет WAV-файлов."

                    editorStatusView.text =
                        "Нет записей для редактирования"

                    waveformView.setWaveform(
                        FloatArray(0)
                    )

                    return@runOnUiThread
                }

                val selectedIndex =
                    if (selectName != null) {
                        items.indexOfFirst {
                            it.name == selectName
                        }.takeIf { it >= 0 } ?: 0
                    } else {
                        0
                    }

                recordingSpinner.setSelection(
                    selectedIndex
                )

                loadRecording(
                    items[selectedIndex]
                )
            }
        }.apply {
            name = "Volt1-Editor-Query"
            start()
        }
    }

    private fun queryRecordings():
        List<RecordingItem> {

        val result =
            mutableListOf<RecordingItem>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED
        )

        val selection =
            "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"

        val selectionArgs =
            arrayOf("%.wav")

        val sortOrder =
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Audio.Media
                .EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn =
                cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media._ID
                )

            val nameColumn =
                cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media
                        .DISPLAY_NAME
                )

            val pathColumn =
                cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media
                        .RELATIVE_PATH
                )

            val expectedPath =
                Environment.DIRECTORY_MUSIC +
                    "/Volt1 Recorder"

            while (cursor.moveToNext()) {
                val relativePath =
                    cursor.getString(pathColumn)
                        ?: ""

                if (
                    !relativePath.startsWith(
                        expectedPath
                    )
                ) {
                    continue
                }

                val id =
                    cursor.getLong(idColumn)

                val name =
                    cursor.getString(nameColumn)

                val uri =
                    ContentUris.withAppendedId(
                        MediaStore.Audio.Media
                            .EXTERNAL_CONTENT_URI,
                        id
                    )

                result += RecordingItem(
                    uri = uri,
                    name = name
                )
            }
        }

        return result
    }

    private fun loadRecording(
        item: RecordingItem
    ) {
        player.stop()

        val generation =
            ++loadGeneration

        setEditorEnabled(false)

        editorStatusView.text =
            "Анализ WAV и построение waveform…"

        fileInfoView.text =
            item.name

        Thread {
            try {
                val info =
                    WavFile.readInfo(
                        context = this,
                        uri = item.uri,
                        displayName = item.name
                    )

                val waveform =
                    WavFile.loadWaveform(
                        context = this,
                        info = info
                    )

                runOnUiThread {
                    if (
                        generation !=
                        loadGeneration
                    ) {
                        return@runOnUiThread
                    }

                    currentInfo = info

                    waveformView.setWaveform(
                        waveform
                    )

                    updatingSelectionControls =
                        true

                    startSeek.progress = 0
                    endSeek.progress = SEEK_MAX

                    waveformView.setSelection(
                        0f,
                        1f
                    )

                    updatingSelectionControls =
                        false

                    fileInfoView.text =
                        buildString {
                            appendLine(
                                info.displayName
                            )

                            append(
                                "PCM " +
                                    "${info.bitsPerSample} bit • " +
                                    "${info.sampleRate} Hz • " +
                                    "${info.channels} ch • " +
                                    formatTime(
                                        info.durationMs
                                    )
                            )
                        }

                    updateSelectionText()
                    setEditorEnabled(true)

                    editorStatusView.text =
                        "Готово. Переместите " +
                            "границы выделения."
                }

            } catch (t: Throwable) {
                runOnUiThread {
                    if (
                        generation !=
                        loadGeneration
                    ) {
                        return@runOnUiThread
                    }

                    currentInfo = null

                    waveformView.setWaveform(
                        FloatArray(0)
                    )

                    setEditorEnabled(false)

                    editorStatusView.text =
                        "Ошибка: " +
                            (
                                t.message
                                    ?: t.javaClass
                                        .simpleName
                            )
                }
            }
        }.apply {
            name = "Volt1-Editor-Waveform"
            start()
        }
    }

    private fun updateSelectionFromSeekBars(
        changedStart: Boolean
    ) {
        if (updatingSelectionControls) {
            return
        }

        updatingSelectionControls = true

        if (changedStart) {
            if (
                startSeek.progress >=
                endSeek.progress
            ) {
                startSeek.progress =
                    (
                        endSeek.progress -
                            MIN_SEEK_GAP
                    ).coerceAtLeast(0)
            }
        } else {
            if (
                endSeek.progress <=
                startSeek.progress
            ) {
                endSeek.progress =
                    (
                        startSeek.progress +
                            MIN_SEEK_GAP
                    ).coerceAtMost(SEEK_MAX)
            }
        }

        val start =
            startSeek.progress /
                SEEK_MAX.toFloat()

        val end =
            endSeek.progress /
                SEEK_MAX.toFloat()

        waveformView.setSelection(
            start,
            end
        )

        updatingSelectionControls = false

        updateSelectionText()
    }

    private fun updateSelectionText() {
        val info =
            currentInfo ?: return

        val (startFrame, endFrame) =
            selectedFrames(info)

        val startMs =
            startFrame * 1000L /
                info.sampleRate

        val endMs =
            endFrame * 1000L /
                info.sampleRate

        val durationMs =
            max(0L, endMs - startMs)

        selectionView.text =
            buildString {
                appendLine(
                    "Начало: " +
                        formatTime(startMs)
                )

                appendLine(
                    "Конец: " +
                        formatTime(endMs)
                )

                append(
                    "Длительность: " +
                        formatTime(durationMs)
                )
            }
    }

    private fun selectedFrames(
        info: WavInfo
    ): Pair<Long, Long> {
        val startFraction =
            startSeek.progress /
                SEEK_MAX.toDouble()

        val endFraction =
            endSeek.progress /
                SEEK_MAX.toDouble()

        val start =
            floor(
                info.totalFrames *
                    startFraction
            ).toLong().coerceIn(
                0L,
                info.totalFrames - 1L
            )

        val end =
            ceil(
                info.totalFrames *
                    endFraction
            ).toLong().coerceIn(
                start + 1L,
                info.totalFrames
            )

        return start to end
    }

    private fun playSelection() {
        val info =
            currentInfo ?: return

        val (startFrame, endFrame) =
            selectedFrames(info)

        editorStatusView.text =
            "Воспроизведение выделения…"

        player.play(
            context = this,
            info = info,
            startFrame = startFrame,
            endFrameExclusive = endFrame,
            onFinished = { error ->
                runOnUiThread {
                    editorStatusView.text =
                        if (error == null) {
                            "Воспроизведение завершено"
                        } else {
                            "Ошибка воспроизведения: " +
                                error
                        }
                }
            }
        )
    }

    private fun saveSelection() {
        val info =
            currentInfo ?: return

        val (startFrame, endFrame) =
            selectedFrames(info)

        player.stop()
        setEditorEnabled(false)

        editorStatusView.text =
            "Сохранение выделения " +
                "без перекодирования…"

        Thread {
            try {
                val newName =
                    WavFile.saveSelection(
                        context = this,
                        info = info,
                        startFrame = startFrame,
                        endFrameExclusive =
                            endFrame
                    )

                runOnUiThread {
                    editorStatusView.text =
                        "Сохранено: $newName"

                    refreshRecordings(
                        selectName = newName
                    )
                }

            } catch (t: Throwable) {
                runOnUiThread {
                    setEditorEnabled(true)

                    editorStatusView.text =
                        "Ошибка сохранения: " +
                            (
                                t.message
                                    ?: t.javaClass
                                        .simpleName
                            )
                }
            }
        }.apply {
            name = "Volt1-Editor-Save"
            start()
        }
    }

    private fun setEditorEnabled(
        enabled: Boolean
    ) {
        startSeek.isEnabled = enabled
        endSeek.isEnabled = enabled
        playButton.isEnabled = enabled
        stopButton.isEnabled = enabled
        saveButton.isEnabled = enabled
        waveformView.isEnabled = enabled
    }

    private fun formatTime(ms: Long): String {
        val safeMs =
            max(0L, ms)

        val totalSeconds =
            safeMs / 1000L

        val minutes =
            totalSeconds / 60L

        val seconds =
            totalSeconds % 60L

        val millis =
            safeMs % 1000L

        return "%02d:%02d.%03d".format(
            minutes,
            seconds,
            millis
        )
    }

    private inner class SelectionSeekListener(
        private val isStart: Boolean
    ) : SeekBar.OnSeekBarChangeListener {

        override fun onProgressChanged(
            seekBar: SeekBar?,
            progress: Int,
            fromUser: Boolean
        ) {
            if (fromUser) {
                updateSelectionFromSeekBars(
                    changedStart = isStart
                )
            }
        }

        override fun onStartTrackingTouch(
            seekBar: SeekBar?
        ) = Unit

        override fun onStopTrackingTouch(
            seekBar: SeekBar?
        ) = Unit
    }

    private data class RecordingItem(
        val uri: Uri,
        val name: String
    )

    companion object {
        private const val SEEK_MAX = 10_000
        private const val MIN_SEEK_GAP = 1
    }
}

private class Pcm24Player {

    private var worker: Thread? = null

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

        stopFlag = localStop

        worker = Thread {
            var errorMessage:
                String? = null

            try {
                WavFile.validateEditable(info)

                val minBuffer =
                    AudioTrack.getMinBufferSize(
                        info.sampleRate,
                        AudioFormat
                            .CHANNEL_OUT_MONO,
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

                audioTrack = track

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
                    (safeEnd - safeStart) *
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

                            var readTotal = 0

                            while (
                                readTotal <
                                requested &&
                                !localStop.get()
                            ) {
                                val read =
                                    channel.read(
                                        buffer
                                    )

                                if (read < 0) {
                                    error(
                                        "Неожиданный конец " +
                                            "WAV при playback"
                                    )
                                }

                                readTotal += read
                            }

                            if (localStop.get()) {
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
                                        AudioTrack
                                            .WRITE_BLOCKING
                                    )

                                if (written < 0) {
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

            } catch (t: Throwable) {
                errorMessage =
                    t.message
                        ?: t.javaClass.simpleName

            } finally {
                val track =
                    audioTrack

                audioTrack = null

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

                if (!localStop.get()) {
                    onFinished(errorMessage)
                }
            }
        }.apply {
            name = "Volt1-Editor-Playback"
            start()
        }
    }

    @Synchronized
    fun stop() {
        stopFlag.set(true)

        val track =
            audioTrack

        runCatching {
            track?.pause()
        }

        runCatching {
            track?.flush()
        }

        worker = null
    }

    private fun alignUp(
        value: Int,
        alignment: Int
    ): Int {
        val remainder =
            value % alignment

        return if (remainder == 0) {
            value
        } else {
            value +
                alignment -
                remainder
        }
    }
}
