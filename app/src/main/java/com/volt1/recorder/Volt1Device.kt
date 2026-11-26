package com.volt1.recorder

/** Android-free identification rules for the UA Volt 1 USB interface. */
object Volt1Device {

    const val SAMPLE_RATE = 48_000

    private val NAME = Regex("""\bvolt\s*1\b""", RegexOption.IGNORE_CASE)

    fun isVolt1Name(productName: CharSequence?): Boolean =
        productName != null && NAME.containsMatchIn(productName)

    /** An empty list means the device did not advertise rates. */
    fun supports48k(sampleRates: IntArray): Boolean =
        sampleRates.isEmpty() || SAMPLE_RATE in sampleRates
}
