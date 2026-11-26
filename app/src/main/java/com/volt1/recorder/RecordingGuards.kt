package com.volt1.recorder

/** Android-free policy for long-running capture safety. */
object RecordingGuards {

    /** PCM24 mono at 48 kHz. */
    const val BYTES_PER_SECOND = 48_000L * 3L

    /** Refuse to start below this much free space (~5.8 min of audio). */
    const val MIN_FREE_TO_START = 50L * 1024 * 1024

    /** Stop and save (not discard) when free space falls below this. */
    const val MIN_FREE_WHILE_RECORDING = 16L * 1024 * 1024

    /** Upper bound for the capture wake lock; released explicitly on stop. */
    const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000

    /** Unknown free space (-1) never blocks a recording. */
    fun canStart(freeBytes: Long): Boolean =
        freeBytes < 0L || freeBytes >= MIN_FREE_TO_START

    fun mustStop(freeBytes: Long): Boolean =
        freeBytes in 0L until MIN_FREE_WHILE_RECORDING

    fun minutesLeft(freeBytes: Long): Long =
        if (freeBytes <= 0L) 0L else freeBytes / BYTES_PER_SECOND / 60L

    /** Our own capture files, as named by Wav24Writer (not working edit files). */
    fun isRecordingName(displayName: String?): Boolean =
        displayName != null &&
            displayName.startsWith("Volt1_") &&
            displayName.endsWith("_24bit_48k.wav") &&
            !displayName.startsWith("Volt1_Working_")

    fun isWorkingName(displayName: String?): Boolean =
        displayName != null && displayName.startsWith("Volt1_Working_")
}
