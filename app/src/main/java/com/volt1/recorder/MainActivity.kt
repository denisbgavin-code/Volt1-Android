package com.volt1.recorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var audioManager: AudioManager

    private lateinit var statusView: TextView
    private lateinit var deviceView: TextView
    private lateinit var levelView: TextView
    private lateinit var fileView: TextView
    private lateinit var meterView: ProgressBar
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val handler = Handler(Looper.getMainLooper())

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshVolt()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshVolt()
        }
    }

    private val updateUi = object : Runnable {
        override fun run() {
            statusView.text = RecorderService.status

            val seconds = RecorderService.elapsedMs / 1000L
            val peak = RecorderService.peakDb

            levelView.text = if (RecorderService.recording) {
                "%02d:%02d   Peak %6.1f dBFS".format(
                    seconds / 60L,
                    seconds % 60L,
                    peak
                )
            } else {
                "Peak: — dBFS"
            }

            meterView.progress = if (RecorderService.recording) {
                (peak.coerceIn(-120f, 0f) + 120f).roundToInt()
            } else {
                0
            }

            fileView.text = if (RecorderService.currentFile.isBlank()) {
                "Файл: —"
            } else {
                "Файл: ${RecorderService.currentFile}"
            }

            val voltPresent = findVolt() != null
            startButton.isEnabled = voltPresent && !RecorderService.recording
            stopButton.isEnabled = RecorderService.recording

            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        statusView = findViewById(R.id.status)
        deviceView = findViewById(R.id.device)
        levelView = findViewById(R.id.level)
        fileView = findViewById(R.id.file)
        meterView = findViewById(R.id.meter)
        startButton = findViewById(R.id.start)
        stopButton = findViewById(R.id.stop)

        findViewById<Button>(R.id.refresh).setOnClickListener {
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
                Intent(this, RecorderService::class.java).apply {
                    action = RecorderService.ACTION_STOP
                }
            )
        }

        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        refreshVolt()
    }

    override fun onResume() {
        super.onResume()
        refreshVolt()
        handler.removeCallbacks(updateUi)
        handler.post(updateUi)
    }

    override fun onPause() {
        handler.removeCallbacks(updateUi)
        super.onPause()
    }

    override fun onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        super.onDestroy()
    }

    private fun hasRecordPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun findVolt(): AudioDeviceInfo? {
        return audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .asSequence()
            .filter {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            .firstOrNull {
                VOLT_1_NAME.containsMatchIn(it.productName.toString())
            }
    }

    private fun refreshVolt() {
        val volt = findVolt()

        if (volt == null) {
            deviceView.text =
                "Volt 1 не найден.\nПодключите UA Volt 1 по USB-C/USB OTG."
            startButton.isEnabled = false
            return
        }

        val rates = if (volt.sampleRates.isEmpty()) {
            "не объявлены (проверим после запуска)"
        } else {
            volt.sampleRates.joinToString()
        }

        val encodings = if (volt.encodings.isEmpty()) {
            "не объявлены (проверим после запуска)"
        } else {
            volt.encodings.joinToString { encodingName(it) }
        }

        val channels = if (volt.channelCounts.isEmpty()) {
            "не объявлены"
        } else {
            volt.channelCounts.joinToString()
        }

        val preflight48k = volt.sampleRates.isEmpty() || 48_000 in volt.sampleRates

        deviceView.text = buildString {
            appendLine(volt.productName.toString())
            appendLine("USB audio device id: ${volt.id}")
            appendLine("Sample rates: $rates")
            appendLine("Formats: $encodings")
            append("Channel counts: $channels")
            if (!preflight48k) {
                appendLine()
                append("ВНИМАНИЕ: устройство не объявляет 48 000 Гц.")
            }
        }

        startButton.isEnabled = preflight48k && !RecorderService.recording
    }

    private fun startCapture() {
        val volt = findVolt()

        if (volt == null) {
            statusView.text = "Volt 1 не найден"
            return
        }

        if (volt.sampleRates.isNotEmpty() && 48_000 !in volt.sampleRates) {
            statusView.text = "Volt 1/Android не объявляет режим 48 кГц"
            return
        }

        statusView.text = "Проверка тракта Volt 1…"

        startForegroundService(
            Intent(this, RecorderService::class.java).apply {
                action = RecorderService.ACTION_START
                putExtra(RecorderService.EXTRA_DEVICE_ID, volt.id)
            }
        )
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24 packed"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM32"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        else -> encoding.toString()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_PERMISSIONS && hasRecordPermission()) {
            startCapture()
        } else if (requestCode == REQUEST_PERMISSIONS) {
            statusView.text = "Нужно разрешение на запись аудио"
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private val VOLT_1_NAME =
            Regex("""\bvolt\s*1\b""", RegexOption.IGNORE_CASE)
    }
}
