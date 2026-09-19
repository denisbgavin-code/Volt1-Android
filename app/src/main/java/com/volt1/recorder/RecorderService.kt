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
import java.nio.ByteOrder
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

            /*
             * Volt 1 advertises two USB input channels on the tested phone,
             * but this OEM Android build reports three channels through
             * AudioRecordingConfiguration.clientFormat after routing.
             *
             * That configuration object is therefore not used to calculate
             * the byte layout returned by AudioRecord.read(). The read layout
             * is derived from AudioRecord.getFormat()/getChannelCount(), which
             * Android documents as the configured AudioRecord format.
             */
            val requestedFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_32BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelIndexMask(REQUESTED_CHANNEL_INDEX_MASK)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_32BIT
            )

            if (minBuffer == AudioRecord.ERROR_BAD_VALUE) {
                error("Android отверг PCM32/48 kHz для Volt 1")
            }

            val desiredBuffer =
                SAMPLE_RATE * SOURCE_BYTES_PER_SAMPLE * BUFFER_CHANNEL_HEADROOM / 2

            val recordBufferSize = max(
                if (minBuffer > 0) minBuffer * 4 else 0,
                desiredBuffer
            )

            val supportsUnprocessed = audioManager
                .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                .equals("true", ignoreCase = true)

            val audioSource = if (supportsUnprocessed) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }

            status = "Инициализация USB PCM32 / 48 kHz…"

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

            val recordFormat = audioRecord.format
            val sourceChannels = audioRecord.channelCount

            if (recordFormat.encoding != AudioFormat.ENCODING_PCM_32BIT) {
                error("AudioRecord фактически настроен не на PCM32")
            }

            if (recordFormat.sampleRate != SAMPLE_RATE) {
                error(
                    "AudioRecord фактически настроен на " +
                        "${recordFormat.sampleRate} Hz вместо 48000 Hz"
                )
            }

            if (sourceChannels < 1) {
                error("AudioRecord не сообщил число каналов")
            }

            val sourceFrameSizeBytes =
                sourceChannels * SOURCE_BYTES_PER_SAMPLE

            writer = Wav24Writer(
                context = this,
                sampleRate = SAMPLE_RATE,
                channels = OUTPUT_CHANNELS
            )

            currentFile = writer.displayName
            recording = true
            status =
                "REC • WAV PCM24/48 • Volt 1 CH1 • source ${sourceChannels}ch"
            updateNotification(status)

            val applicationBuffer = ByteBuffer.allocateDirect(
                SAMPLE_RATE * sourceFrameSizeBytes / 10
            ).order(ByteOrder.nativeOrder())

            val wavBuffer = ByteBuffer.allocateDirect(
                SAMPLE_RATE * OUTPUT_FRAME_SIZE_BYTES / 10
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
                        if (count % sourceFrameSizeBytes != 0) {
                            error(
                                "AudioRecord вернул $count байт, " +
                                    "не кратных frame size $sourceFrameSizeBytes"
                            )
                        }

                        val converted = convertChannel0Pcm32ToPcm24(
                            source = applicationBuffer,
                            sourceBytes = count,
                            sourceFrameSizeBytes = sourceFrameSizeBytes,
                            destination = wavBuffer
                        )

                        peakDb = converted.peakDb
                        RecordingWaveformBuffer.append(
                            converted.peakLinear
                        )
                        writer.write(wavBuffer, converted.bytes)
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

        /*
         * Do not reject on configuration.clientFormat.channelCount here.
         * On the tested OEM build it reports 3 while the USB device advertises
         * 2. For the data layout returned by AudioRecord.read(), use the
         * configured AudioRecord format instead.
         */
        val recordFormat = audioRecord.format

        if (recordFormat.sampleRate != SAMPLE_RATE) {
            error(
                "AudioRecord sample rate ${recordFormat.sampleRate} Hz " +
                    "вместо 48000 Hz"
            )
        }

        if (recordFormat.encoding != AudioFormat.ENCODING_PCM_32BIT) {
            error("AudioRecord client stream не PCM32")
        }

        if (audioRecord.channelCount < 1) {
            error("AudioRecord client stream не содержит каналов")
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

    private data class ConvertedBlock(
        val bytes: Int,
        val peakDb: Float,
        val peakLinear: Float
    )

    private fun convertChannel0Pcm32ToPcm24(
        source: ByteBuffer,
        sourceBytes: Int,
        sourceFrameSizeBytes: Int,
        destination: ByteBuffer
    ): ConvertedBlock {
        val frameCount = sourceBytes / sourceFrameSizeBytes
        val outputBytes = frameCount * OUTPUT_FRAME_SIZE_BYTES

        if (destination.capacity() < outputBytes) {
            error("Недостаточный PCM24 output buffer")
        }

        destination.clear()
        var peak = 0L
        var sourceOffset = 0

        repeat(frameCount) {
            /*
             * PCM32 is signed Q.31. Arithmetic shift by 8 produces signed Q.23.
             * Only channel index 0 is copied; all other client channels are
             * deliberately ignored.
             */
            val q31 = source.getInt(sourceOffset)
            val q23 = q31 shr 8

            destination.put((q23 and 0xff).toByte())
            destination.put(((q23 ushr 8) and 0xff).toByte())
            destination.put(((q23 ushr 16) and 0xff).toByte())

            peak = max(peak, abs(q23.toLong()))
            sourceOffset += sourceFrameSizeBytes
        }

        val linear = if (peak == 0L) {
            0f
        } else {
            (peak.toDouble() / PCM24_MAX)
                .toFloat()
                .coerceIn(0f, 1f)
        }

        val db = if (linear <= 0f) {
            -120f
        } else {
            (20.0 * log10(linear.toDouble()))
                .toFloat()
                .coerceAtLeast(-120f)
        }

        return ConvertedBlock(
            bytes = outputBytes,
            peakDb = db,
            peakLinear = linear
        )
    }

    private fun resetPublicState() {
        recording = false
        status = "Подготовка…"
        peakDb = -120f
        elapsedMs = 0L
        currentFile = ""
        RecordingWaveformBuffer.reset()
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
        private const val SOURCE_BYTES_PER_SAMPLE = 4

        // Request USB endpoint channels 0 and 1. The actual AudioRecord client
        // channel count is queried after construction and is not hard-coded.
        private const val REQUESTED_CHANNEL_INDEX_MASK = 0x3

        // Extra native-buffer headroom for OEMs that internally expose more
        // channels than the USB endpoint advertises.
        private const val BUFFER_CHANNEL_HEADROOM = 4

        private const val OUTPUT_CHANNELS = 1
        private const val OUTPUT_FRAME_SIZE_BYTES = 3
        private const val PCM24_MAX = 8_388_607.0

        private const val NOTIFICATION_CHANNEL = "volt1_recording"
        private const val NOTIFICATION_ID = 1001

        private val VOLT_1_NAME =
            Regex("""\bvolt\s*1\b""", RegexOption.IGNORE_CASE)

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
