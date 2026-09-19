package com.volt1.recorder

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var audioManager:
        AudioManager

    private lateinit var deviceStatusView:
        TextView

    private lateinit var statusView:
        TextView

    private lateinit var recordStateView:
        TextView

    private lateinit var recordTimeView:
        TextView

    private lateinit var peakValueView:
        TextView

    private lateinit var fileView:
        TextView

    private lateinit var waveformView:
        UnifiedWaveformView

    private lateinit var meterView:
        LevelMeterView

    private lateinit var startButton:
        Button

    private lateinit var stopButton:
        Button

    private lateinit var openFileButton:
        Button

    private lateinit var fitButton:
        Button

    private lateinit var playButton:
        Button

    private lateinit var deleteButton:
        Button

    private lateinit var cropButton:
        Button

    private lateinit var saveAsButton:
        Button

    private val player =
        Pcm24Player()

    private var detectedVolt:
        AudioDeviceInfo? = null

    private var currentInfo:
        WavInfo? = null

    private var workingUri:
        Uri? = null

    private var sessionDisplayName:
        String? = null

    private var editDirty =
        false

    private var selectionStart =
        0f

    private var selectionEnd =
        1f

    private var loadGeneration =
        0

    private var previousRecordingState =
        false

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(
                addedDevices:
                    Array<out AudioDeviceInfo>
            ) {
                refreshVolt()
            }

            override fun onAudioDevicesRemoved(
                removedDevices:
                    Array<out AudioDeviceInfo>
            ) {
                refreshVolt()
            }
        }

    private val updateUi =
        object : Runnable {
            override fun run() {
                val recording =
                    RecorderService.recording

                val voltPresent =
                    detectedVolt != null

                if (
                    recording &&
                    !previousRecordingState
                ) {
                    player.stop()
                    clearWorkingCopy()
                    currentInfo = null
                    sessionDisplayName = null
                    editDirty = false
                    selectionStart = 0f
                    selectionEnd = 1f

                    waveformView.showLive(
                        FloatArray(0)
                    )
                }

                if (recording) {
                    waveformView.showLive(
                        RecordingWaveformBuffer
                            .snapshot()
                    )
                }

                if (
                    !recording &&
                    previousRecordingState
                ) {
                    val finishedName =
                        RecorderService.currentFile

                    if (
                        finishedName.isNotBlank()
                    ) {
                        loadAppRecording(
                            finishedName
                        )
                    }
                }

                val info =
                    currentInfo

                recordTimeView.text =
                    formatDuration(
                        if (recording) {
                            RecorderService.elapsedMs
                        } else {
                            info?.durationMs ?: 0L
                        }
                    )

                val peak =
                    RecorderService.peakDb

                peakValueView.text =
                    if (recording) {
                        "%6.1f dBFS".format(
                            peak
                        )
                    } else {
                        "— dBFS"
                    }

                meterView.setLevelDb(
                    if (recording) {
                        peak
                    } else {
                        -120f
                    }
                )

                when {
                    recording -> {
                        recordStateView.text =
                            "● REC"

                        recordStateView
                            .setTextColor(
                                COLOR_RECORDING
                            )
                    }

                    info != null -> {
                        recordStateView.text =
                            "● EDIT"

                        recordStateView
                            .setTextColor(
                                COLOR_READY
                            )
                    }

                    voltPresent -> {
                        recordStateView.text =
                            "● READY"

                        recordStateView
                            .setTextColor(
                                COLOR_READY
                            )
                    }

                    else -> {
                        recordStateView.text =
                            "○ OFFLINE"

                        recordStateView
                            .setTextColor(
                                COLOR_MUTED
                            )
                    }
                }

                val serviceStatus =
                    RecorderService.status

                statusView.text =
                    if (
                        recording ||
                        serviceStatus.startsWith(
                            "Запись остановлена"
                        )
                    ) {
                        serviceStatus
                    } else if (
                        info != null
                    ) {
                        if (editDirty) {
                            "EDIT • UNSAVED CHANGES • PCM24 / 48 kHz / mono"
                        } else {
                            "EDIT • PCM24 / 48 kHz / mono"
                        }
                    } else {
                        serviceStatus
                    }

                fileView.text =
                    if (info != null) {
                        val base =
                            sessionDisplayName
                                ?: info.displayName

                        if (editDirty) {
                            "$base • UNSAVED"
                        } else {
                            base
                        }
                    } else {
                        RecorderService
                            .currentFile
                            .ifBlank {
                                "No file loaded"
                            }
                    }

                startButton.isEnabled =
                    voltPresent &&
                        !recording

                stopButton.isEnabled =
                    recording ||
                        (
                            !recording &&
                                info != null
                            )

                openFileButton.isEnabled =
                    !recording

                fitButton.isEnabled =
                    !recording &&
                        info != null

                playButton.isEnabled =
                    !recording &&
                        info != null

                updateEditButtons(
                    recording
                )

                previousRecordingState =
                    recording

                handler.postDelayed(
                    this,
                    UI_UPDATE_MS
                )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        setContentView(
            R.layout.activity_main
        )

        audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        deviceStatusView =
            findViewById(
                R.id.deviceStatus
            )

        statusView =
            findViewById(
                R.id.status
            )

        recordStateView =
            findViewById(
                R.id.recordState
            )

        recordTimeView =
            findViewById(
                R.id.recordTime
            )

        peakValueView =
            findViewById(
                R.id.peakValue
            )

        fileView =
            findViewById(
                R.id.file
            )

        waveformView =
            findViewById(
                R.id.waveform
            )

        meterView =
            findViewById(
                R.id.meter
            )

        startButton =
            findViewById(
                R.id.start
            )

        stopButton =
            findViewById(
                R.id.stop
            )

        openFileButton =
            findViewById(
                R.id.openFile
            )

        fitButton =
            findViewById(
                R.id.fitWaveform
            )

        playButton =
            findViewById(
                R.id.playSelection
            )

        deleteButton =
            findViewById(
                R.id.deleteSelection
            )

        cropButton =
            findViewById(
                R.id.cropSelection
            )

        saveAsButton =
            findViewById(
                R.id.saveAs
            )

        waveformView
            .onSelectionChanged =
            { start, end ->
                selectionStart =
                    start

                selectionEnd =
                    end

                updateEditButtons(
                    RecorderService.recording
                )
            }

        startButton
            .setOnClickListener {
                if (
                    hasRecordPermission()
                ) {
                    startCapture()
                } else {
                    requestRequiredPermissions()
                }
            }

        stopButton
            .setOnClickListener {
                if (
                    RecorderService.recording
                ) {
                    startService(
                        Intent(
                            this,
                            RecorderService::class.java
                        ).apply {
                            action =
                                RecorderService.ACTION_STOP
                        }
                    )
                } else {
                    player.stop()
                }
            }

        openFileButton
            .setOnClickListener {
                openWavPicker()
            }

        fitButton
            .setOnClickListener {
                waveformView.resetZoom()
            }

        playButton
            .setOnClickListener {
                playSelection()
            }

        deleteButton
            .setOnClickListener {
                deleteSelection()
            }

        cropButton
            .setOnClickListener {
                cropSelection()
            }

        saveAsButton
            .setOnClickListener {
                openSaveAsPicker()
            }

        audioManager
            .registerAudioDeviceCallback(
                deviceCallback,
                handler
            )

        waveformView.showIdle()
        refreshVolt()
    }

    override fun onResume() {
        super.onResume()

        refreshVolt()

        handler.removeCallbacks(
            updateUi
        )

        handler.post(
            updateUi
        )
    }

    override fun onPause() {
        handler.removeCallbacks(
            updateUi
        )

        super.onPause()
    }

    override fun onDestroy() {
        player.stop()
        clearWorkingCopy()

        audioManager
            .unregisterAudioDeviceCallback(
                deviceCallback
            )

        super.onDestroy()
    }

    private fun hasRecordPermission():
        Boolean =

        checkSelfPermission(
            Manifest.permission.RECORD_AUDIO
        ) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestRequiredPermissions() {
        val permissions =
            mutableListOf(
                Manifest.permission.RECORD_AUDIO
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            permissions +=
                Manifest.permission
                    .POST_NOTIFICATIONS
        }

        requestPermissions(
            permissions.toTypedArray(),
            REQUEST_PERMISSIONS
        )
    }

    private fun findVolt():
        AudioDeviceInfo? {

        return audioManager
            .getDevices(
                AudioManager.GET_DEVICES_INPUTS
            )
            .asSequence()
            .filter {
                it.type ==
                    AudioDeviceInfo
                        .TYPE_USB_DEVICE ||
                    it.type ==
                    AudioDeviceInfo
                        .TYPE_USB_HEADSET
            }
            .firstOrNull {
                VOLT_1_NAME
                    .containsMatchIn(
                        it.productName
                            .toString()
                    )
            }
    }

    private fun refreshVolt() {
        val volt =
            findVolt()

        detectedVolt =
            volt

        if (
            volt == null
        ) {
            deviceStatusView.text =
                "○ Volt 1 offline"

            deviceStatusView
                .setTextColor(
                    COLOR_MUTED
                )

            return
        }

        val supports48k =
            volt.sampleRates.isEmpty() ||
                48_000 in
                volt.sampleRates

        deviceStatusView.text =
            if (supports48k) {
                "● Volt 1 ready • USB • 48 kHz"
            } else {
                "● Volt 1 connected • 48 kHz unavailable"
            }

        deviceStatusView
            .setTextColor(
                if (supports48k) {
                    COLOR_READY
                } else {
                    COLOR_RECORDING
                }
            )
    }

    private fun startCapture() {
        val volt =
            findVolt()

        if (
            volt == null
        ) {
            showMessage(
                "Volt 1 не найден"
            )

            return
        }

        if (
            volt.sampleRates.isNotEmpty() &&
            48_000 !in
            volt.sampleRates
        ) {
            showMessage(
                "Volt 1/Android " +
                    "не объявляет 48 kHz"
            )

            return
        }

        player.stop()
        clearWorkingCopy()
        currentInfo = null
        sessionDisplayName = null
        editDirty = false

        statusView.text =
            "Validating Volt 1 USB capture path…"

        startForegroundService(
            Intent(
                this,
                RecorderService::class.java
            ).apply {
                action =
                    RecorderService.ACTION_START

                putExtra(
                    RecorderService
                        .EXTRA_DEVICE_ID,
                    volt.id
                )
            }
        )
    }

    private fun openWavPicker() {
        val intent =
            Intent(
                Intent.ACTION_OPEN_DOCUMENT
            ).apply {
                addCategory(
                    Intent.CATEGORY_OPENABLE
                )

                type =
                    "*/*"

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }

        startActivityForResult(
            intent,
            REQUEST_OPEN_WAV
        )
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            resultCode !=
            RESULT_OK
        ) {
            return
        }

        val uri =
            data?.data ?: return

        when (
            requestCode
        ) {
            REQUEST_OPEN_WAV -> {
                val takeFlags =
                    data.flags and
                        (
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )

                runCatching {
                    contentResolver
                        .takePersistableUriPermission(
                            uri,
                            takeFlags and
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                }

                clearWorkingCopy()

                val displayName =
                    resolveDisplayName(
                        uri
                    )

                sessionDisplayName =
                    displayName

                editDirty =
                    false

                loadRecording(
                    uri = uri,
                    displayName =
                        displayName
                )
            }

            REQUEST_SAVE_WAV -> {
                saveAsToUri(
                    uri
                )
            }
        }
    }

    private fun resolveDisplayName(
        uri: Uri
    ): String {
        contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns
                    .DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (
                cursor.moveToFirst() &&
                !cursor.isNull(0)
            ) {
                return cursor
                    .getString(0)
            }
        }

        return uri.lastPathSegment
            ?.substringAfterLast(
                '/'
            )
            ?.takeIf {
                it.isNotBlank()
            }
            ?: "selected_audio.wav"
    }

    private fun loadAppRecording(
        displayName: String
    ) {
        Thread {
            val uri =
                findMediaStoreUri(
                    displayName
                )

            if (
                uri == null
            ) {
                runOnUiThread {
                    showMessage(
                        "Не удалось открыть " +
                            displayName
                    )
                }

                return@Thread
            }

            runOnUiThread {
                clearWorkingCopy()
                sessionDisplayName =
                    displayName
                editDirty =
                    false

                loadRecording(
                    uri,
                    displayName
                )
            }
        }.apply {
            name =
                "Volt1-Find-Recording"

            start()
        }
    }

    private fun findMediaStoreUri(
        displayName: String
    ): Uri? {
        val projection =
            arrayOf(
                MediaStore.Audio.Media._ID
            )

        val selection =
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"

        val args =
            arrayOf(
                displayName
            )

        val sort =
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Audio.Media
                .EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            sort
        )?.use { cursor ->
            if (
                cursor.moveToFirst()
            ) {
                val id =
                    cursor.getLong(
                        cursor
                            .getColumnIndexOrThrow(
                                MediaStore.Audio.Media._ID
                            )
                    )

                return ContentUris
                    .withAppendedId(
                        MediaStore.Audio.Media
                            .EXTERNAL_CONTENT_URI,
                        id
                    )
            }
        }

        return null
    }

    private fun loadRecording(
        uri: Uri,
        displayName: String
    ) {
        player.stop()

        val generation =
            ++loadGeneration

        statusView.text =
            "Loading waveform…"

        Thread {
            try {
                val info =
                    WavFile.readInfo(
                        context = this,
                        uri = uri,
                        displayName =
                            displayName
                    )

                val peaks =
                    WavFile.loadWaveform(
                        context = this,
                        info = info,
                        pointCount =
                            WAVEFORM_POINTS
                    )

                runOnUiThread {
                    if (
                        generation !=
                        loadGeneration ||
                        RecorderService.recording
                    ) {
                        return@runOnUiThread
                    }

                    currentInfo =
                        info

                    selectionStart =
                        0f

                    selectionEnd =
                        1f

                    waveformView
                        .showEditable(
                            values = peaks,
                            trackDurationMs =
                                info.durationMs,
                            resetViewport =
                                true
                        )

                    fileView.text =
                        sessionDisplayName
                            ?: info.displayName

                    statusView.text =
                        "EDIT • PCM24 / 48 kHz / mono"

                    updateEditButtons(
                        false
                    )
                }

            } catch (
                t: Throwable
            ) {
                runOnUiThread {
                    if (
                        generation ==
                        loadGeneration
                    ) {
                        showMessage(
                            t.message
                                ?: "Не удалось открыть WAV"
                        )
                    }
                }
            }
        }.apply {
            name =
                "Volt1-Load-Waveform"

            start()
        }
    }

    private fun playSelection() {
        val info =
            currentInfo ?: return

        val frames =
            selectedFrames(
                info
            )

        player.play(
            context = this,
            info = info,
            startFrame =
                frames.first,
            endFrameExclusive =
                frames.second,
            onFinished = {
                error ->
                if (
                    error != null
                ) {
                    runOnUiThread {
                        showMessage(
                            error
                        )
                    }
                }
            }
        )
    }

    private fun deleteSelection() {
        val info =
            currentInfo ?: return

        val frames =
            selectedFrames(
                info
            )

        val selected =
            frames.second -
                frames.first

        if (
            selected <= 0L ||
            selected >=
            info.totalFrames
        ) {
            showMessage(
                "Переместите оба вертикальных маркера " +
                    "к удаляемому фрагменту"
            )

            return
        }

        player.stop()
        setEditingBusy(
            true,
            "Deleting range…"
        )

        Thread {
            try {
                val newUri =
                    WavFile.deleteSelectionWorking(
                        context = this,
                        info = info,
                        startFrame =
                            frames.first,
                        endFrameExclusive =
                            frames.second
                    )

                val oldWorking =
                    workingUri

                WavFile.discardPending(
                    this,
                    oldWorking
                )

                workingUri =
                    newUri

                editDirty =
                    true

                val displayName =
                    sessionDisplayName
                        ?: info.displayName

                runOnUiThread {
                    setEditingBusy(
                        false,
                        "Range deleted • unsaved"
                    )

                    loadRecording(
                        newUri,
                        displayName
                    )
                }

            } catch (
                t: Throwable
            ) {
                runOnUiThread {
                    setEditingBusy(
                        false,
                        t.message
                            ?: "Ошибка удаления"
                    )
                }
            }
        }.apply {
            name =
                "Volt1-Delete-Range"

            start()
        }
    }

    private fun cropSelection() {
        val info =
            currentInfo ?: return

        val frames =
            selectedFrames(
                info
            )

        val selected =
            frames.second -
                frames.first

        if (
            selected <= 0L ||
            selected >=
            info.totalFrames
        ) {
            showMessage(
                "Сдвиньте вертикальные маркеры " +
                    "к нужному диапазону"
            )

            return
        }

        player.stop()

        setEditingBusy(
            true,
            "Cropping working edit…"
        )

        Thread {
            try {
                val newName =
                    WavFile.cropSelectionWorking(
                        context = this,
                        info = info,
                        startFrame =
                            frames.first,
                        endFrameExclusive =
                            frames.second
                    )

                val oldWorking =
                    workingUri

                WavFile.discardPending(
                    this,
                    oldWorking
                )

                workingUri =
                    newName

                editDirty =
                    true

                val displayName =
                    sessionDisplayName
                        ?: info.displayName

                runOnUiThread {
                    setEditingBusy(
                        false,
                        "Crop applied • unsaved"
                    )

                    loadRecording(
                        newName,
                        displayName
                    )
                }

            } catch (
                t: Throwable
            ) {
                runOnUiThread {
                    setEditingBusy(
                        false,
                        t.message
                            ?: "Ошибка сохранения"
                    )
                }
            }
        }.apply {
            name =
                "Volt1-Crop-Range"

            start()
        }
    }

    private fun openSaveAsPicker() {
        val info =
            currentInfo ?: return

        val baseName =
            (
                sessionDisplayName
                    ?: info.displayName
                )
                .removeSuffix(
                    ".wav"
                )
                .removeSuffix(
                    ".WAV"
                )
                .ifBlank {
                    "Volt1_Edit"
                }

        val intent =
            Intent(
                Intent.ACTION_CREATE_DOCUMENT
            ).apply {
                addCategory(
                    Intent.CATEGORY_OPENABLE
                )

                type =
                    "audio/wav"

                putExtra(
                    Intent.EXTRA_TITLE,
                    "${baseName}_edit.wav"
                )
            }

        startActivityForResult(
            intent,
            REQUEST_SAVE_WAV
        )
    }

    private fun saveAsToUri(
        destinationUri: Uri
    ) {
        val info =
            currentInfo ?: return

        player.stop()

        setEditingBusy(
            true,
            "Saving WAV…"
        )

        Thread {
            try {
                WavFile.exportToUri(
                    context = this,
                    info = info,
                    destinationUri =
                        destinationUri
                )

                val savedName =
                    resolveDisplayName(
                        destinationUri
                    )

                editDirty =
                    false

                sessionDisplayName =
                    savedName

                runOnUiThread {
                    setEditingBusy(
                        false,
                        "Saved: $savedName"
                    )

                    fileView.text =
                        savedName
                }

            } catch (
                t: Throwable
            ) {
                runOnUiThread {
                    setEditingBusy(
                        false,
                        t.message
                            ?: "Ошибка сохранения"
                    )
                }
            }
        }.apply {
            name =
                "Volt1-Save-As"

            start()
        }
    }

    private fun clearWorkingCopy() {
        val uri =
            workingUri

        workingUri =
            null

        if (uri != null) {
            WavFile.discardPending(
                this,
                uri
            )
        }
    }

    private fun selectedFrames(
        info: WavInfo
    ): Pair<Long, Long> {
        val start =
            floor(
                info.totalFrames *
                    selectionStart
            ).toLong()
                .coerceIn(
                    0L,
                    info.totalFrames - 1L
                )

        val end =
            ceil(
                info.totalFrames *
                    selectionEnd
            ).toLong()
                .coerceIn(
                    start + 1L,
                    info.totalFrames
                )

        return start to end
    }

    private fun updateEditButtons(
        recording: Boolean
    ) {
        val info =
            currentInfo

        val hasFile =
            info != null &&
                !recording

        val span =
            max(
                0f,
                selectionEnd -
                    selectionStart
            )

        val hasRange =
            hasFile &&
                span >
                MIN_EDIT_SELECTION &&
                span <
                MAX_FULL_SELECTION

        playButton.isEnabled =
            hasFile

        fitButton.isEnabled =
            hasFile

        deleteButton.isEnabled =
            hasRange

        cropButton.isEnabled =
            hasRange

        saveAsButton.isEnabled =
            hasFile

        val controls =
            listOf(
                fitButton,
                playButton,
                deleteButton,
                cropButton,
                saveAsButton
            )

        controls.forEach {
            it.alpha =
                if (
                    it.isEnabled
                ) {
                    1f
                } else {
                    0.42f
                }
        }
    }

    private fun setEditingBusy(
        busy: Boolean,
        message: String
    ) {
        statusView.text =
            message

        val enabled =
            !busy

        openFileButton.isEnabled =
            enabled

        waveformView.isEnabled =
            enabled

        if (busy) {
            playButton.isEnabled = false
            fitButton.isEnabled = false
            deleteButton.isEnabled = false
            cropButton.isEnabled = false
            saveAsButton.isEnabled = false
        } else {
            updateEditButtons(
                RecorderService.recording
            )
        }
    }

    private fun showMessage(
        message: String
    ) {
        statusView.text =
            message

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun formatDuration(
        elapsedMs: Long
    ): String {
        val totalSeconds =
            elapsedMs / 1000L

        val hours =
            totalSeconds / 3600L

        val minutes =
            (
                totalSeconds %
                    3600L
                ) / 60L

        val seconds =
            totalSeconds %
                60L

        val tenth =
            (
                elapsedMs %
                    1000L
                ) / 100L

        return "%02d:%02d:%02d.%01d"
            .format(
                hours,
                minutes,
                seconds,
                tenth
            )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions:
            Array<out String>,
        grantResults:
            IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode ==
            REQUEST_PERMISSIONS &&
            hasRecordPermission()
        ) {
            startCapture()
        } else if (
            requestCode ==
            REQUEST_PERMISSIONS
        ) {
            showMessage(
                "Нужно разрешение " +
                    "на запись аудио"
            )
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS =
            100

        private const val REQUEST_OPEN_WAV =
            4101

        private const val REQUEST_SAVE_WAV =
            4102

        private const val UI_UPDATE_MS =
            100L

        private const val WAVEFORM_POINTS =
            8_000

        private const val MIN_EDIT_SELECTION =
            0.0005f

        private const val MAX_FULL_SELECTION =
            0.9995f

        private val COLOR_READY =
            Color.rgb(
                92,
                220,
                165
            )

        private val COLOR_RECORDING =
            Color.rgb(
                255,
                83,
                83
            )

        private val COLOR_MUTED =
            Color.rgb(
                127,
                135,
                147
            )

        private val VOLT_1_NAME =
            Regex(
                """\bvolt\s*1\b""",
                RegexOption.IGNORE_CASE
            )
    }
}
