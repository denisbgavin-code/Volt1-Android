package com.volt1.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

class RecorderService : Service() {

    @Volatile
    private var stopRequested = false

    private var worker: Thread? = null
    private var recorder: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            runCatching { recorder?.stop() }
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_START || worker?.isAlive == true) {
            return START_NOT_STICKY
        }

        val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)

        startForeground(
            NOTIFICATION_ID,
            createNotification("Проверка Volt 1…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        stopRequested = false

        worker = Thread(
            { capture(deviceId) },
            "Volt1-Capture"
        ).apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return START_NOT_STICKY
    }

    private fun capture(deviceId: Int) {
        var writer: Wav24Writer? = null
        var published = false

        try {
            resetPublicState()
            status = "Поиск Volt 1…"

            val audioManager =
                getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val device = audioManager
                .getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == deviceId }
                ?: error("Volt 1 отключён")

            requireVolt1(device)

            val requestedFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_24BIT_PACKED)
                .setSampleRate(SAMPLE_RATE)
                .setChannelIndexMask(CHANNEL_INDEX_MASK)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_24BIT_PACKED
            )

            if (minBuffer == AudioRecord.ERROR_BAD_VALUE) {
                error("Android отверг PCM24/48 kHz")
            }

            val desiredBuffer = SAMPLE_RATE * FRAME_SIZE_BYTES / 2
            val rawBufferSize = max(
                if (minBuffer > 0) minBuffer * 4 else 0,
                desiredBuffer
            )
            val recordBufferSize = alignToFrame(rawBufferSize)

            val supportsUnprocessed = audioManager
                .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                .equals("true", ignoreCase = true)

            val audioSource = if (supportsUnprocessed) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }

            status = "Инициализация PCM24 / 48 kHz…"

            val audioRecord = AudioRecord.Builder()
                .setContext(this)
                .setAudioSource(audioSource)
                .setAudioFormat(requestedFormat)
                .setBufferSizeInBytes(recordBufferSize)
                .setPrivacySensitive(true)
                .build()

            recorder = audioRecord

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                error("AudioRecord не инициализирован")
            }

            if (!audioRecord.setPreferredDevice(device)) {
                error("Android отказался выбрать Volt 1")
            }

            audioRecord.startRecording()

            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error("Android не начал запись")
            }

            val initialConfig = awaitValidConfiguration(audioRecord)
            validateCapturePath(audioRecord, device, initialConfig)

            writer = Wav24Writer(
                context = this,
                sampleRate = SAMPLE_RATE,
                channels = CHANNELS
            )

            currentFile = writer.displayName
            recording = true
            status = "REC • PCM24 • 48 kHz • Volt 1"
            updateNotification(status)

            val applicationBuffer = ByteBuffer.allocateDirect(
                SAMPLE_RATE * FRAME_SIZE_BYTES / 10
            )

            val startedAt = SystemClock.elapsedRealtime()
            var blocksSinceValidation = 0

            while (!stopRequested) {
                applicationBuffer.clear()

                val count = audioRecord.read(
                    applicationBuffer,
                    applicationBuffer.capacity(),
                    AudioRecord.READ_BLOCKING
                )

                when {
                    count > 0 -> {
                        if (count % FRAME_SIZE_BYTES != 0) {
                            error("Получен нецелый PCM24 frame: $count bytes")
                        }

                        peakDb = calculatePeakDb(applicationBuffer, count)
                        writer.write(applicationBuffer, count)
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt

                        blocksSinceValidation++
                        if (blocksSinceValidation >= 10) {
                            val config = audioRecord.activeRecordingConfiguration
                                ?: error("Потеряна конфигурация audio capture")
                            validateCapturePath(audioRecord, device, config)
                            blocksSinceValidation = 0
                        }
                    }

                    count == AudioRecord.ERROR_DEAD_OBJECT ->
                        error("Volt 1 отключён")

                    count == AudioRecord.ERROR_INVALID_OPERATION ->
                        error("AudioRecord: invalid operation")

                    count == AudioRecord.ERROR_BAD_VALUE ->
                        error("AudioRecord: bad value")

                    count < 0 ->
                        error("AudioRecord.read(): $count")
                }
            }

            recording = false
            runCatching { audioRecord.stop() }

            writer.closeAndPublish()
            published = true

            status = "Сохранено: ${writer.displayName}"
            peakDb = -120f

        } catch (t: Throwable) {
            recording = false
            peakDb = -120f
            status = "Запись остановлена: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            runCatching { recorder?.release() }
            recorder = null

            if (!published) {
                runCatching { writer?.abort() }
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun awaitValidConfiguration(
        audioRecord: AudioRecord
    ): AudioRecordingConfiguration {
        repeat(20) {
            val routed = audioRecord.routedDevice
            val config = audioRecord.activeRecordingConfiguration

            if (routed != null && config != null) {
                return config
            }

            SystemClock.sleep(50L)
        }

        error("Android не сообщил фактический USB capture path")
    }

    private fun validateCapturePath(
        audioRecord: AudioRecord,
        requestedDevice: AudioDeviceInfo,
        configuration: AudioRecordingConfiguration
    ) {
        val routed = audioRecord.routedDevice
            ?: error("Нет фактического audio route")

        if (routed.id != requestedDevice.id) {
            error("Маршрут записи переключён на ${routed.productName}")
        }

        if (configuration.isClientSilenced) {
            error("Android заглушил capture stream")
        }

        val activeDevice = configuration.audioDevice
            ?: error("Не определён активный input device")

        if (activeDevice.id != requestedDevice.id) {
            error("Capture path больше не использует Volt 1")
        }

        val client = configuration.clientFormat

        if (client.sampleRate != SAMPLE_RATE) {
            error("Client sample rate ${client.sampleRate} Hz вместо 48000 Hz")
        }

        if (client.encoding != AudioFormat.ENCODING_PCM_24BIT_PACKED) {
            error("Клиентский поток не PCM 24-bit packed")
        }

        if (client.channelCount != CHANNELS) {
            error("Клиентский поток содержит ${client.channelCount} канал(а)")
        }

        val hardware = configuration.format

        if (hardware.sampleRate != SAMPLE_RATE) {
            error(
                "Hardware/HAL работает ${hardware.sampleRate} Hz; " +
                    "ресэмплинг запрещён"
            )
        }

        if (!isHighResolutionPcm(hardware.encoding)) {
            error(
                "Hardware/HAL не подтвердил >16-bit PCM " +
                    "(encoding=${hardware.encoding})"
            )
        }

        if (configuration.effects.isNotEmpty()) {
            val effects = configuration.effects.joinToString { it.name }
            error("Обнаружена системная обработка: $effects")
        }
    }

    private fun requireVolt1(device: AudioDeviceInfo) {
        if (
            device.type != AudioDeviceInfo.TYPE_USB_DEVICE &&
            device.type != AudioDeviceInfo.TYPE_USB_HEADSET
        ) {
            error("Выбранное устройство не USB Audio")
        }

        if (!VOLT_1_NAME.containsMatchIn(device.productName.toString())) {
            error("USB-устройство не распознано как UA Volt 1")
        }

        if (device.sampleRates.isNotEmpty() && SAMPLE_RATE !in device.sampleRates) {
            error("Устройство не объявляет 48 kHz")
        }
    }

    private fun isHighResolutionPcm(encoding: Int): Boolean = when (encoding) {
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT -> true
        else -> false
    }

    private fun alignToFrame(bytes: Int): Int {
        val remainder = bytes % FRAME_SIZE_BYTES
        return if (remainder == 0) bytes else bytes + FRAME_SIZE_BYTES - remainder
    }

    private fun calculatePeakDb(buffer: ByteBuffer, count: Int): Float {
        var peak = 0L
        var index = 0

        while (index + 2 < count) {
            val b0 = buffer.get(index).toInt() and 0xff
            val b1 = buffer.get(index + 1).toInt() and 0xff
            val b2 = buffer.get(index + 2).toInt() and 0xff

            var sample = b0 or (b1 shl 8) or (b2 shl 16)

            if ((sample and 0x800000) != 0) {
                sample = sample or -0x1000000
            }

            peak = max(peak, abs(sample.toLong()))
            index += FRAME_SIZE_BYTES
        }

        if (peak == 0L) return -120f

        return (20.0 * log10(peak.toDouble() / PCM24_MAX)).toFloat()
            .coerceAtLeast(-120f)
    }

    private fun resetPublicState() {
        recording = false
        status = "Подготовка…"
        peakDb = -120f
        elapsedMs = 0L
        currentFile = ""
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Volt 1 recording",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
    }

    private fun createNotification(text: String): Notification {
        val stopIntent = Intent(this, RecorderService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Volt 1 Recorder")
            .setContentText(text)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Стоп",
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onDestroy() {
        stopRequested = true
        runCatching { recorder?.stop() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.volt1.recorder.START"
        const val ACTION_STOP = "com.volt1.recorder.STOP"
        const val EXTRA_DEVICE_ID = "device_id"

        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 1
        private const val CHANNEL_INDEX_MASK = 0x1
        private const val FRAME_SIZE_BYTES = 3
        private const val PCM24_MAX = 8_388_607.0

        private const val NOTIFICATION_CHANNEL = "volt1_recording"
        private const val NOTIFICATION_ID = 1001

        private val VOLT_1_NAME =
            Regex("""\\bvolt\\s*1\\b""", RegexOption.IGNORE_CASE)

        @Volatile
        var recording: Boolean = false
            private set

        @Volatile
        var status: String = "Готово"
            private set

        @Volatile
        var peakDb: Float = -120f
            private set

        @Volatile
        var elapsedMs: Long = 0L
            private set

        @Volatile
        var currentFile: String = ""
            private set
    }
}
