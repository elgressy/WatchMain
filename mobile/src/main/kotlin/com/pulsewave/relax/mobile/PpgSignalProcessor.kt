package com.pulsewave.relax.mobile

/**
 * Rolling-buffer BPM estimator for finger-over-camera-and-flash PPG.
 * Feed it mean luma samples as frames arrive; call estimateBpm() to get the
 * current best guess from the last [windowMs] of signal.
 *
 * Detrends with a local moving-average baseline (isolates the pulsatile AC
 * component from ambient DC drift/motion), then counts rising zero-crossings
 * with a minimum spacing so a plausible heart rate range (35-220 bpm) caps
 * both ends.
 */
class PpgSignalProcessor(
    private val windowMs: Long = 8000,
    private val bufferMs: Long = 20000
) {
    private data class Sample(val t: Long, val v: Double)

    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun addSample(timestampMs: Long, luma: Double) {
        samples.addLast(Sample(timestampMs, luma))
        val cutoff = timestampMs - bufferMs
        while (samples.isNotEmpty() && samples.first().t < cutoff) samples.removeFirst()
    }

    @Synchronized
    fun estimateBpm(): Double? {
        if (samples.size < 20) return null
        val now = samples.last().t
        val windowed = samples.filter { it.t >= now - windowMs }
        if (windowed.size < 20) return null

        val values = windowed.map { it.v }
        val baselineSpan = 15
        val detrended = DoubleArray(values.size)
        for (i in values.indices) {
            val lo = maxOf(0, i - baselineSpan)
            val hi = minOf(values.size - 1, i + baselineSpan)
            var sum = 0.0
            for (j in lo..hi) sum += values[j]
            detrended[i] = values[i] - (sum / (hi - lo + 1))
        }

        val minSpacingMs = 60_000 / 220 // reject double-counting above 220 bpm
        var lastPeakT = Long.MIN_VALUE
        val peakTimes = mutableListOf<Long>()
        for (i in 1 until detrended.size) {
            val crossedUp = detrended[i - 1] <= 0 && detrended[i] > 0
            if (crossedUp) {
                val t = windowed[i].t
                if (t - lastPeakT >= minSpacingMs) {
                    peakTimes.add(t)
                    lastPeakT = t
                }
            }
        }
        if (peakTimes.size < 4) return null

        val meanIntervalMs = peakTimes.zipWithNext { a, b -> (b - a).toDouble() }.average()
        val bpm = 60_000.0 / meanIntervalMs
        return bpm.takeIf { it in 35.0..220.0 }
    }

    @Synchronized
    fun reset() = samples.clear()
}
