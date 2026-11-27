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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.ArrayDeque
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

    private lateinit var takesButton:
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

    private lateinit var fadeInButton:
        Button

    private lateinit var fadeOutButton:
        Button

    private lateinit var normalizeButton:
        Button

    private lateinit var undoButton:
        Button

    private var detailGeneration =
        0

    private val detailLoader =
        Runnable {
            loadDetailPeaks()
        }

    private val player =
        Pcm24Player()

    private val meterBallistics =
        LevelMeter.Ballistics()

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

    private var playbackPosition =
        0f

    private var playbackActive =
        false

    private val editHistory =
        ArrayDeque<EditState>()

    private val ownedWorkingUris =
        linkedSetOf<Uri>()

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

    /*
     * Android 16 (API 36) with targetSdk 36 no longer calls onBackPressed()
     * for back gestures. On API 33+ the platform OnBackInvokedDispatcher is
     * used instead; the callback is registered only while there is something
     * for Back to do (stop, stop playback, undo), so otherwise the system
     * default back (with predictive animation) is kept.
     */
    private var backCallback:
        Any? = null

    private var backCallbackRegistered =
        false

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
                    playbackActive = false
                    playbackPosition = 0f
                    clearEditingSession()
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

                    val savedUri =
                        RecorderService.lastSavedUri

                    when {
                        savedUri.isNotBlank() &&
                            finishedName.isNotBlank() -> {
                            player.stop()
                            playbackActive = false
                            playbackPosition = 0f
                            clearEditingSession()
                            sessionDisplayName =
                                finishedName
                            editDirty =
                                false

                            loadRecording(
                                uri =
                                    Uri.parse(
                                        savedUri
                                    ),
                                displayName =
                                    finishedName
                            )
                        }

                        RecorderService.status
                            .startsWith(
                                "Запись остановлена"
                            ) -> {
                            showMessage(
                                RecorderService.status
                            )
                        }

                        finishedName.isNotBlank() -> {
                            loadAppRecording(
                                finishedName
                            )
                        }
                    }
                }

                val info =
                    currentInfo

                val displayTimeMs =
                    when {
                        recording ->
                            RecorderService.elapsedMs

                        info != null ->
                            (
                                info.durationMs *
                                    playbackPosition
                                ).toLong()

                        else ->
                            0L
                    }

                recordTimeView.setTextIfChanged(
                    TimeFormat.duration(
                        displayTimeMs
                    )
                )

                updateMeter(
                    recording
                )

                when {
                    recording -> {
                        recordStateView.setTextIfChanged(
                            "● REC"
                        )

                        recordStateView
                            .setTextColorIfChanged(
                                COLOR_RECORDING
                            )
                    }

                    playbackActive -> {
                        recordStateView.setTextIfChanged(
                            "▶ PLAY"
                        )

                        recordStateView
                            .setTextColorIfChanged(
                                COLOR_READY
                            )
                    }

                    info != null -> {
                        recordStateView.setTextIfChanged(
                            "● EDIT"
                        )

                        recordStateView
                            .setTextColorIfChanged(
                                COLOR_READY
                            )
                    }

                    voltPresent -> {
                        recordStateView.setTextIfChanged(
                            "● READY"
                        )

                        recordStateView
                            .setTextColorIfChanged(
                                COLOR_READY
                            )
                    }

                    else -> {
                        recordStateView.setTextIfChanged(
                            "○ OFFLINE"
                        )

                        recordStateView
                            .setTextColorIfChanged(
                                COLOR_MUTED
                            )
                    }
                }

                val serviceStatus =
                    RecorderService.status

                statusView.setTextIfChanged(
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
                )

                fileView.setTextIfChanged(
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
                )

                startButton.isEnabled =
                    voltPresent &&
                        !recording

                stopButton.isEnabled =
                    recording ||
                        playbackActive

                openFileButton.isEnabled =
                    !recording

                takesButton.isEnabled =
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

                updateBackCallback()

                handler.postDelayed(
                    this,
                    // ~30 fps while recording for smooth metering.
                    if (recording) METER_UPDATE_MS else UI_UPDATE_MS
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

        takesButton =
            findViewById(
                R.id.takes
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

        fadeInButton =
            findViewById(
                R.id.fadeIn
            )

        fadeOutButton =
            findViewById(
                R.id.fadeOut
            )

        normalizeButton =
            findViewById(
                R.id.normalize
            )

        fadeInButton.setOnClickListener { fadeSelection(fadeIn = true) }
        fadeOutButton.setOnClickListener { fadeSelection(fadeIn = false) }
        normalizeButton.setOnClickListener { normalizeTrack() }

        undoButton =
            findViewById(
                R.id.undo
            )

        undoButton.setOnClickListener {
            if (!undoLastEdit()) {
                showMessage("Нечего отменять")
            }
        }

        waveformView
            .onViewportChanged =
            { _, _ ->
                scheduleDetailLoad()
            }

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

        waveformView
            .onPlayheadChanged =
            { position ->
                playbackPosition =
                    position

                if (
                    playbackActive
                ) {
                    playSelection()
                }
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
                    playbackActive =
                        false
                }
            }

        openFileButton
            .setOnClickListener {
                openWavPicker()
            }

        takesButton
            .setOnClickListener {
                startActivityForResult(
                    Intent(
                        this,
                        RecordingsActivity::class.java
                    ),
                    REQUEST_TAKES
                )
            }

        fitButton
            .setOnClickListener {
                waveformView.resetZoom()
            }

        meterView
            .setOnClickListener {
                // Clear the CLIP latch and the take maximum.
                RecorderService.meter.clearHold()
                meterBallistics.reset()
                updateMeter(
                    RecorderService.recording
                )
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
        recoverInterruptedRecordings()
    }

    /** Publishes recordings cut off by a crash/reboot (see RecordingRecovery). */
    private fun recoverInterruptedRecordings() {
        Thread {
            val result =
                runCatching {
                    RecordingRecovery.run(
                        applicationContext
                    )
                }.getOrNull()
                    ?: return@Thread

            val message =
                when {
                    result.recovered.size == 1 ->
                        "Восстановлена прерванная запись: ${result.recovered.first()}"

                    result.recovered.size > 1 ->
                        "Восстановлено прерванных записей: ${result.recovered.size}"

                    result.failed.isNotEmpty() ->
                        "Не удалось восстановить: ${result.failed.joinToString()}"

                    else ->
                        null
                }
                    ?: return@Thread

            runOnUiThread {
                if (!isDestroyed) {
                    showMessage(
                        message
                    )
                }
            }
        }.apply {
            name =
                "Volt1-Recovery"

            start()
        }
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

    // Only reached on API 31-32; API 33+ uses OnBackInvokedCallback
    // (android:enableOnBackInvokedCallback="true", see updateBackCallback).
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated(
        "Android back callback compatibility"
    )
    override fun onBackPressed() {
        if (!handleBack()) {
            super.onBackPressed()
        }
    }

    private fun backHasWork(): Boolean =
        RecorderService.recording ||
            playbackActive ||
            editHistory.isNotEmpty()

    private fun updateBackCallback() {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }

        val wanted =
            backHasWork()

        if (
            wanted ==
            backCallbackRegistered
        ) {
            return
        }

        val callback =
            (backCallback as? OnBackInvokedCallback)
                ?: OnBackInvokedCallback {
                    handleBack()
                    updateBackCallback()
                }.also {
                    backCallback = it
                }

        if (wanted) {
            onBackInvokedDispatcher
                .registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    callback
                )
        } else {
            onBackInvokedDispatcher
                .unregisterOnBackInvokedCallback(
                    callback
                )
        }

        backCallbackRegistered =
            wanted
    }

    /** Returns true when Back was consumed. */
    private fun handleBack(): Boolean {
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

            return true
        }

        if (
            playbackActive
        ) {
            player.stop()

            playbackActive =
                false

            return true
        }

        return undoLastEdit()
    }

    override fun onDestroy() {
        player.stop()
        clearEditingSession()

        if (
            backCallbackRegistered &&
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {
            (backCallback as? OnBackInvokedCallback)?.let {
                onBackInvokedDispatcher
                    .unregisterOnBackInvokedCallback(it)
            }
            backCallbackRegistered = false
        }

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
                Volt1Device.isVolt1Name(
                    it.productName
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
            Volt1Device.supports48k(
                volt.sampleRates
            )

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
            !Volt1Device.supports48k(
                volt.sampleRates
            )
        ) {
            showMessage(
                "Volt 1/Android " +
                    "не объявляет 48 kHz"
            )

            return
        }

        player.stop()
        playbackActive = false
        playbackPosition = 0f
        clearEditingSession()
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
            requestCode == REQUEST_TAKES
        ) {
            if (
                resultCode == RESULT_OK &&
                data != null
            ) {
                applyTakesResult(
                    data
                )
            }

            return
        }

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
                if (
                    data.flags and
                    Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
                ) {
                    runCatching {
                        contentResolver
                            .takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                    }
                }

                player.stop()
                playbackActive = false
                playbackPosition = 0f
                clearEditingSession()

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

    /** Reflects deletes/renames from the takes list and opens a chosen take. */
    private fun applyTakesResult(
        data: Intent
    ) {
        val info =
            currentInfo

        val deleted =
            data.getStringArrayListExtra(
                RecordingsActivity.EXTRA_DELETED
            ).orEmpty()

        if (
            info != null &&
            info.uri.toString() in deleted
        ) {
            player.stop()
            playbackActive = false
            playbackPosition = 0f
            clearEditingSession()
            currentInfo = null
            sessionDisplayName = null
            editDirty = false
            waveformView.showIdle()
            updateEditButtons(
                RecorderService.recording
            )
        }

        data.getStringArrayListExtra(
            RecordingsActivity.EXTRA_RENAMED
        ).orEmpty().forEach { entry ->
            val uri =
                entry.substringBefore('\n')

            val name =
                entry.substringAfter('\n')

            if (
                currentInfo?.uri?.toString() == uri &&
                !editDirty
            ) {
                sessionDisplayName =
                    name
            }
        }

        val uri =
            data.data ?: return

        val displayName =
            data.getStringExtra(
                RecordingsActivity.EXTRA_DISPLAY_NAME
            ) ?: resolveDisplayName(uri)

        player.stop()
        playbackActive = false
        playbackPosition = 0f
        clearEditingSession()
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
                player.stop()
                playbackActive = false
                playbackPosition = 0f
                clearEditingSession()
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
        displayName: String,
        restoreState: EditState? = null,
        keepViewport: Boolean = false
    ) {
        player.stop()
        playbackActive = false

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

                    val targetSelectionStart =
                        restoreState
                            ?.selectionStart
                            ?: 0f

                    val targetSelectionEnd =
                        restoreState
                            ?.selectionEnd
                            ?: 1f

                    val targetPlaybackPosition =
                        restoreState
                            ?.playbackPosition
                            ?: 0f

                    waveformView
                        .showEditable(
                            values = peaks,
                            trackDurationMs =
                                info.durationMs,
                            resetViewport =
                                !keepViewport
                        )

                    waveformView.totalFrames =
                        info.totalFrames

                    scheduleDetailLoad()

                    selectionStart =
                        targetSelectionStart

                    selectionEnd =
                        targetSelectionEnd

                    playbackPosition =
                        targetPlaybackPosition

                    waveformView
                        .setSelection(
                            selectionStart,
                            selectionEnd
                        )

                    waveformView
                        .setPlaybackPosition(
                            playbackPosition
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

        player.stop()

        val selected =
            selectedFrames(
                info
            )

        val cursorFrame =
            SelectionMath.cursorFrame(
                info.totalFrames,
                playbackPosition
            )

        val selectionIsPartial =
            selectionEnd -
                selectionStart <
                MAX_FULL_SELECTION

        val cursorInsideSelection =
            cursorFrame >=
                selected.first &&
                cursorFrame <
                selected.second

        val endFrame =
            if (
                selectionIsPartial &&
                cursorInsideSelection
            ) {
                selected.second
            } else {
                info.totalFrames
            }

        val safeEnd =
            endFrame.coerceAtLeast(
                cursorFrame + 1L
            )

        playbackActive =
            true

        waveformView
            .setPlaybackPosition(
                playbackPosition
            )

        // Each playback gets its own clock: late reports of a replaced
        // playback only reach their own (stopped) clock.
        val clock =
            PlayheadClock(
                info.sampleRate
            ).apply {
                start(
                    cursorFrame,
                    safeEnd,
                    System.nanoTime()
                )
            }

        playheadClock =
            clock

        startPlayheadAnimation()

        player.play(
            context = this,
            info = info,
            startFrame =
                cursorFrame,
            endFrameExclusive =
                safeEnd,
            onProgress = {
                frame ->
                // Timestamp taken on the player thread, not on arrival.
                val reportedAt =
                    System.nanoTime()

                runOnUiThread {
                    clock.onReport(
                        frame,
                        reportedAt
                    )
                }
            },
            onFinished = {
                error ->
                runOnUiThread {
                    if (
                        clock.running &&
                        playheadClock === clock &&
                        error == null
                    ) {
                        showPlayheadFrame(
                            info,
                            clock.estimate(
                                System.nanoTime()
                            )
                        )
                    }

                    clock.stop()

                    playbackActive =
                        false

                    if (
                        error != null
                    ) {
                        showMessage(
                            error
                        )
                    }
                }
            }
        )
    }

    private var playheadClock:
        PlayheadClock? = null

    private var playheadAnimating =
        false

    /** Redraws the playhead on every display frame while playing. */
    private val playheadFrame =
        object : android.view.Choreographer.FrameCallback {
            override fun doFrame(
                frameTimeNanos: Long
            ) {
                val clock =
                    playheadClock

                val info =
                    currentInfo

                if (
                    !playbackActive ||
                    clock == null ||
                    !clock.running ||
                    info == null
                ) {
                    playheadAnimating =
                        false

                    return
                }

                showPlayheadFrame(
                    info,
                    clock.estimate(
                        System.nanoTime()
                    )
                )

                android.view.Choreographer
                    .getInstance()
                    .postFrameCallback(
                        this
                    )
            }
        }

    private fun startPlayheadAnimation() {
        if (playheadAnimating) return

        playheadAnimating =
            true

        android.view.Choreographer
            .getInstance()
            .postFrameCallback(
                playheadFrame
            )
    }

    private fun showPlayheadFrame(
        info: WavInfo,
        frame: Long
    ) {
        if (info.totalFrames <= 0L) return

        playbackPosition =
            (frame.toDouble() / info.totalFrames.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)

        waveformView
            .setPlaybackPosition(
                playbackPosition
            )
    }

    private fun deleteSelection() =
        rangeEdit(
            emptyMessage = "Переместите оба вертикальных маркера к удаляемому фрагменту",
            busy = "Deleting range…",
            done = "Range deleted • unsaved",
            failed = "Ошибка удаления",
            keepsLength = false
        ) { info, start, end ->
            WavFile.deleteSelectionWorking(this, info, start, end)
        }

    private fun cropSelection() =
        rangeEdit(
            emptyMessage = "Сдвиньте вертикальные маркеры к нужному диапазону",
            busy = "Cropping working edit…",
            done = "Crop applied • unsaved",
            failed = "Ошибка сохранения",
            keepsLength = false
        ) { info, start, end ->
            WavFile.cropSelectionWorking(this, info, start, end)
        }

    private fun fadeSelection(
        fadeIn: Boolean
    ) =
        rangeEdit(
            emptyMessage = "Выделите участок для fade",
            busy = if (fadeIn) "Fade in…" else "Fade out…",
            done = if (fadeIn) "Fade in applied • unsaved" else "Fade out applied • unsaved",
            failed = "Ошибка fade",
            keepsLength = true
        ) { info, start, end ->
            WavFile.fadeSelectionWorking(this, info, start, end, fadeIn)
        }

    private fun normalizeTrack() {
        val info =
            currentInfo ?: return

        runWorkingEdit(
            info = info,
            busy = "Normalizing to -1 dBFS…",
            done = "Normalized to -1 dBFS • unsaved",
            failed = "Ошибка нормализации",
            keepsLength = true
        ) {
            WavFile.normalizeWorking(this, info)
        }
    }

    /** Edit of the selected frame range (requires a partial selection). */
    private fun rangeEdit(
        emptyMessage: String,
        busy: String,
        done: String,
        failed: String,
        keepsLength: Boolean,
        operation: (WavInfo, Long, Long) -> Uri
    ) {
        val info =
            currentInfo ?: return

        val (start, end) =
            selectedFrames(
                info
            )

        val selected =
            end - start

        if (
            selected <= 0L ||
            selected >= info.totalFrames
        ) {
            showMessage(
                emptyMessage
            )

            return
        }

        runWorkingEdit(info, busy, done, failed, keepsLength) {
            operation(info, start, end)
        }
    }

    /**
     * Runs an edit that writes a new pending working file, pushes the
     * previous state for undo and loads the result. Length-preserving
     * edits keep selection, playhead and zoom.
     */
    private fun runWorkingEdit(
        info: WavInfo,
        busy: String,
        done: String,
        failed: String,
        keepsLength: Boolean,
        operation: () -> Uri
    ) {
        player.stop()
        playbackActive = false

        setEditingBusy(
            true,
            busy
        )

        val previousState =
            captureEditState(
                info
            )

        Thread {
            try {
                val newUri =
                    operation()

                runOnUiThread {
                    editHistory.addLast(
                        previousState
                    )

                    ownedWorkingUris.add(
                        newUri
                    )

                    workingUri =
                        newUri

                    editDirty =
                        true

                    setEditingBusy(
                        false,
                        done
                    )

                    loadRecording(
                        uri = newUri,
                        displayName =
                            sessionDisplayName
                                ?: info.displayName,
                        restoreState =
                            if (keepsLength) previousState else null,
                        keepViewport =
                            keepsLength
                    )
                }

            } catch (
                t: Throwable
            ) {
                runOnUiThread {
                    setEditingBusy(
                        false,
                        t.message
                            ?: failed
                    )
                }
            }
        }.apply {
            name =
                "Volt1-Edit"

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
        playbackActive = false

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

                runOnUiThread {
                    editDirty =
                        false

                    sessionDisplayName =
                        savedName

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

    private fun clearEditingSession() {
        editHistory.clear()

        val uris =
            ownedWorkingUris
                .toList()

        ownedWorkingUris.clear()

        uris.forEach {
            uri ->
            WavFile.discardPending(
                this,
                uri
            )
        }

        workingUri =
            null
    }

    private fun captureEditState(
        info: WavInfo
    ): EditState =
        EditState(
            uri = info.uri,
            displayName =
                sessionDisplayName
                    ?: info.displayName,
            isWorking =
                workingUri ==
                    info.uri,
            dirty =
                editDirty,
            selectionStart =
                selectionStart,
            selectionEnd =
                selectionEnd,
            playbackPosition =
                playbackPosition
        )

    private fun undoLastEdit():
        Boolean {

        if (
            editHistory.isEmpty()
        ) {
            return false
        }

        player.stop()
        playbackActive =
            false

        val currentWorking =
            workingUri

        if (
            currentWorking != null &&
            ownedWorkingUris.remove(
                currentWorking
            )
        ) {
            WavFile.discardPending(
                this,
                currentWorking
            )
        }

        val state =
            editHistory.removeLast()

        workingUri =
            if (
                state.isWorking
            ) {
                state.uri
            } else {
                null
            }

        editDirty =
            state.dirty

        sessionDisplayName =
            state.displayName

        selectionStart =
            state.selectionStart

        selectionEnd =
            state.selectionEnd

        playbackPosition =
            state.playbackPosition

        statusView.text =
            "UNDO • previous edit restored"

        loadRecording(
            uri = state.uri,
            displayName =
                state.displayName,
            restoreState =
                state
        )

        return true
    }

    private fun selectedFrames(
        info: WavInfo
    ): Pair<Long, Long> =
        SelectionMath.selectedFrames(
            info.totalFrames,
            selectionStart,
            selectionEnd
        )

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

        fadeInButton.isEnabled =
            hasRange

        fadeOutButton.isEnabled =
            hasRange

        normalizeButton.isEnabled =
            hasFile

        undoButton.isEnabled =
            !recording &&
                editHistory.isNotEmpty()

        saveAsButton.isEnabled =
            hasFile

        applyEnabledAlpha(fitButton)
        applyEnabledAlpha(playButton)
        applyEnabledAlpha(deleteButton)
        applyEnabledAlpha(cropButton)
        applyEnabledAlpha(fadeInButton)
        applyEnabledAlpha(fadeOutButton)
        applyEnabledAlpha(normalizeButton)
        applyEnabledAlpha(undoButton)
        applyEnabledAlpha(saveAsButton)
    }

    private fun updateMeter(
        recording: Boolean
    ) {
        val meter =
            RecorderService.meter

        val now =
            android.os.SystemClock.uptimeMillis()

        if (recording) {
            meterBallistics.update(
                now,
                Pcm24.linearToDb(
                    meter.drainPeak()
                )
            )
        } else {
            meterBallistics.update(
                now,
                Pcm24.SILENCE_DB
            )
        }

        val sessionPeak =
            meter.sessionPeak()

        meterView.setLevels(
            meterBallistics.barDb,
            meterBallistics.holdDb,
            meter.clipEvents()
        )

        peakValueView.setTextIfChanged(
            when {
                recording ->
                    TimeFormat.meter(
                        meterBallistics.holdDb,
                        Pcm24.linearToDb(sessionPeak)
                    )

                sessionPeak > 0f ->
                    TimeFormat.maxPeak(
                        Pcm24.linearToDb(sessionPeak)
                    )

                else ->
                    "— dBFS"
            }
        )
    }

    /** Debounced: exact peaks for the visible window once zoomed in. */
    private fun scheduleDetailLoad() {
        handler.removeCallbacks(
            detailLoader
        )

        handler.postDelayed(
            detailLoader,
            DETAIL_DEBOUNCE_MS
        )
    }

    private fun loadDetailPeaks() {
        val info =
            currentInfo ?: return

        val (start, end) =
            waveformView.visibleWindow()

        val span =
            end - start

        // The 8000-point overview is enough until each point covers > 1 px.
        if (
            span >= DETAIL_MAX_SPAN ||
            waveformView.width <= 0
        ) {
            return
        }

        // Load a window 3x the visible one so short pans stay detailed.
        val margin =
            span

        val windowStart =
            (start - margin).coerceAtLeast(0f)

        val windowEnd =
            (end + margin).coerceAtMost(1f)

        val total =
            info.totalFrames

        val startFrame =
            SelectionMath.cursorFrame(total, windowStart)

        val endFrame =
            kotlin.math.ceil(total.toDouble() * windowEnd.toDouble())
                .toLong()
                .coerceIn(startFrame + 1L, total)

        val points =
            (waveformView.width * 3 * 2)
                .coerceIn(64, 20_000)

        val generation =
            ++detailGeneration

        Thread {
            val peaks =
                runCatching {
                    WavFile.loadWaveformRange(
                        this,
                        info,
                        startFrame,
                        endFrame,
                        points
                    )
                }.getOrNull()
                    ?: return@Thread

            runOnUiThread {
                if (
                    generation == detailGeneration &&
                    currentInfo == info
                ) {
                    waveformView.setDetail(
                        startFrame.toFloat() / total,
                        endFrame.toFloat() / total,
                        peaks
                    )
                }
            }
        }.apply {
            name =
                "Volt1-Detail-Peaks"

            start()
        }
    }

    private fun applyEnabledAlpha(
        button: Button
    ) {
        // Disabled look comes from the colour/drawable state lists now;
        // extra alpha on top made labels unreadable.
        val alpha =
            1f

        if (button.alpha != alpha) {
            button.alpha = alpha
        }
    }

    private fun TextView.setTextIfChanged(
        value: CharSequence
    ) {
        if (!android.text.TextUtils.equals(text, value)) {
            text = value
        }
    }

    private fun TextView.setTextColorIfChanged(
        color: Int
    ) {
        if (currentTextColor != color) {
            setTextColor(color)
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

        takesButton.isEnabled =
            enabled

        waveformView.isEnabled =
            enabled

        if (busy) {
            playButton.isEnabled = false
            fitButton.isEnabled = false
            deleteButton.isEnabled = false
            cropButton.isEnabled = false
            fadeInButton.isEnabled = false
            fadeOutButton.isEnabled = false
            normalizeButton.isEnabled = false
            undoButton.isEnabled = false
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

    private data class EditState(
        val uri: Uri,
        val displayName: String,
        val isWorking: Boolean,
        val dirty: Boolean,
        val selectionStart: Float,
        val selectionEnd: Float,
        val playbackPosition: Float
    )

    companion object {
        private const val REQUEST_PERMISSIONS =
            100

        private const val REQUEST_OPEN_WAV =
            4101

        private const val REQUEST_SAVE_WAV =
            4102

        private const val REQUEST_TAKES =
            4103

        private const val UI_UPDATE_MS =
            100L

        private const val METER_UPDATE_MS =
            33L

        private const val DETAIL_DEBOUNCE_MS =
            120L

        /** Below this visible fraction the overview is too coarse. */
        private const val DETAIL_MAX_SPAN =
            0.12f

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
    }
}
