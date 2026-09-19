package com.volt1.recorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var audioManager: AudioManager

    private lateinit var deviceCard: LinearLayout
    private lateinit var deviceStateDot: TextView
    private lateinit var deviceView: TextView
    private lateinit var deviceMetaView: TextView

    private lateinit var statusView: TextView
    private lateinit var recordStateView: TextView
    private lateinit var recordTimeView: TextView
    private lateinit var peakValueView: TextView
    private lateinit var fileView: TextView

    private lateinit var waveformView: LiveWaveformView
    private lateinit var meterView: LevelMeterView

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var editorButton: Button

    private var detectedVolt: AudioDeviceInfo? = null

    private val handler =
        Handler(Looper.getMainLooper())

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(
                addedDevices: Array<out AudioDeviceInfo>
            ) {
                refreshVolt()
            }

            override fun onAudioDevicesRemoved(
                removedDevices: Array<out AudioDeviceInfo>
            ) {
                refreshVolt()
            }
        }

    private val updateUi =
        object : Runnable {
            override fun run() {
                val recording =
                    RecorderService.recording

                val elapsed =
                    RecorderService.elapsedMs

                val peak =
                    RecorderService.peakDb

                val voltPresent =
                    detectedVolt != null

                statusView.text =
                    RecorderService.status

                recordTimeView.text =
                    formatDuration(elapsed)

                peakValueView.text =
                    if (recording) {
                        "%6.1f dBFS".format(peak)
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

                waveformView.setWaveform(
                    RecordingWaveformBuffer.snapshot(),
                    recording
                )

                when {
                    recording -> {
                        recordStateView.text =
                            "● REC"

                        recordStateView.setTextColor(
                            COLOR_RECORDING
                        )
                    }

                    voltPresent -> {
                        recordStateView.text =
                            "● READY"

                        recordStateView.setTextColor(
                            COLOR_READY
                        )
                    }

                    else -> {
                        recordStateView.text =
                            "○ OFFLINE"

                        recordStateView.setTextColor(
                            COLOR_MUTED
                        )
                    }
                }

                fileView.text =
                    RecorderService.currentFile
                        .ifBlank {
                            "No recording yet"
                        }

                startButton.isEnabled =
                    voltPresent && !recording

                stopButton.isEnabled =
                    recording

                editorButton.isEnabled =
                    !recording

                startButton.alpha =
                    if (
                        startButton.isEnabled
                    ) {
                        1f
                    } else {
                        0.48f
                    }

                stopButton.alpha =
                    if (
                        stopButton.isEnabled
                    ) {
                        1f
                    } else {
                        0.48f
                    }

                editorButton.alpha =
                    if (
                        editorButton.isEnabled
                    ) {
                        1f
                    } else {
                        0.48f
                    }

                handler.postDelayed(
                    this,
                    UI_UPDATE_MS
                )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        deviceCard =
            findViewById(R.id.deviceCard)

        deviceStateDot =
            findViewById(R.id.deviceStateDot)

        deviceView =
            findViewById(R.id.device)

        deviceMetaView =
            findViewById(R.id.deviceMeta)

        statusView =
            findViewById(R.id.status)

        recordStateView =
            findViewById(R.id.recordState)

        recordTimeView =
            findViewById(R.id.recordTime)

        peakValueView =
            findViewById(R.id.peakValue)

        fileView =
            findViewById(R.id.file)

        waveformView =
            findViewById(R.id.liveWaveform)

        meterView =
            findViewById(R.id.meter)

        startButton =
            findViewById(R.id.start)

        stopButton =
            findViewById(R.id.stop)

        editorButton =
            findViewById(R.id.openEditor)

        findViewById<Button>(
            R.id.refresh
        ).setOnClickListener {
            refreshVolt()
        }

        startButton.setOnClickListener {
            if (hasRecordPermission()) {
                startCapture()
            } else {
                requestRequiredPermissions()
            }
        }

        stopButton.setOnClickListener {
            startService(
                Intent(
                    this,
                    RecorderService::class.java
                ).apply {
                    action =
                        RecorderService.ACTION_STOP
                }
            )
        }

        editorButton.setOnClickListener {
            startActivity(
                Intent(
                    this,
                    EditorActivity::class.java
                )
            )
        }

        audioManager.registerAudioDeviceCallback(
            deviceCallback,
            handler
        )

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
        audioManager.unregisterAudioDeviceCallback(
            deviceCallback
        )

        super.onDestroy()
    }

    private fun hasRecordPermission(): Boolean =
        checkSelfPermission(
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

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

        detectedVolt = volt

        if (volt == null) {
            deviceCard.setBackgroundResource(
                R.drawable.studio_card
            )

            deviceStateDot.text = "●"

            deviceStateDot.setTextColor(
                COLOR_MUTED
            )

            deviceView.text =
                "Volt 1"

            deviceMetaView.text =
                "USB interface not connected\n" +
                    "Connect Volt 1 via USB-C / OTG."

            startButton.isEnabled = false
            return
        }

        deviceCard.setBackgroundResource(
            R.drawable
                .studio_card_connected
        )

        deviceStateDot.text = "●"

        deviceStateDot.setTextColor(
            COLOR_READY
        )

        deviceView.text =
            volt.productName
                .toString()
                .ifBlank {
                    "Volt 1"
                }

        val rates =
            if (
                volt.sampleRates.isEmpty()
            ) {
                "rate metadata unavailable"
            } else {
                volt.sampleRates
                    .joinToString(", ")
            }

        val encodings =
            if (
                volt.encodings.isEmpty()
            ) {
                "format metadata unavailable"
            } else {
                volt.encodings
                    .joinToString(", ") {
                        encodingName(it)
                    }
            }

        val channels =
            if (
                volt.channelCounts.isEmpty()
            ) {
                "?"
            } else {
                volt.channelCounts
                    .joinToString(",")
            }

        val preflight48k =
            volt.sampleRates.isEmpty() ||
                48_000 in
                volt.sampleRates

        deviceMetaView.text =
            buildString {
                append(
                    "USB id "
                )
                append(
                    volt.id
                )
                append(
                    "  •  "
                )
                append(
                    channels
                )
                append(
                    " ch"
                )
                appendLine()

                append(
                    rates
                )
                appendLine()

                append(
                    encodings
                )
            }

        if (
            !preflight48k &&
            !RecorderService.recording
        ) {
            statusView.text =
                "Volt 1 не объявляет 48 kHz"
        }

        startButton.isEnabled =
            preflight48k &&
                !RecorderService.recording
    }

    private fun startCapture() {
        val volt =
            findVolt()

        if (volt == null) {
            statusView.text =
                "Volt 1 не найден"
            return
        }

        if (
            volt.sampleRates.isNotEmpty() &&
            48_000 !in volt.sampleRates
        ) {
            statusView.text =
                "Volt 1/Android " +
                    "не объявляет 48 kHz"
            return
        }

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

    private fun encodingName(
        encoding: Int
    ): String = when (encoding) {
        AudioFormat
            .ENCODING_PCM_16BIT ->
            "PCM16"

        AudioFormat
            .ENCODING_PCM_24BIT_PACKED ->
            "PCM24"

        AudioFormat
            .ENCODING_PCM_32BIT ->
            "PCM32"

        AudioFormat
            .ENCODING_PCM_FLOAT ->
            "FLOAT"

        else ->
            encoding.toString()
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
                totalSeconds % 3600L
                ) / 60L

        val seconds =
            totalSeconds % 60L

        val tenth =
            (
                elapsedMs % 1000L
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
        permissions: Array<out String>,
        grantResults: IntArray
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
            statusView.text =
                "Нужно разрешение " +
                    "на запись аудио"
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS =
            100

        private const val UI_UPDATE_MS =
            100L

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
