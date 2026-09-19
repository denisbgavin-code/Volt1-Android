package com.volt1.recorder

import kotlin.math.max

object RecordingWaveformBuffer {

    private const val CAPACITY = 2048

    private val values = FloatArray(CAPACITY)
    private var count = 0

    @Synchronized
    fun reset() {
        count = 0
        values.fill(0f)
    }

    @Synchronized
    fun append(peakLinear: Float) {
        if (count >= CAPACITY) {
            compress()
        }

        values[count] =
            peakLinear.coerceIn(0f, 1f)

        count++
    }

    @Synchronized
    fun snapshot(): FloatArray =
        values.copyOf(count)

    private fun compress() {
        var target = 0
        var source = 0

        while (source + 1 < count) {
            values[target] = max(
                values[source],
                values[source + 1]
            )

            target++
            source += 2
        }

        if (source < count) {
            values[target] = values[source]
            target++
        }

        count = target
    }
}
