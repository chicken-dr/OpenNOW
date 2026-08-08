package com.opennow.diagnostics

import android.util.Log

class TelemetryCollector {

    // Rolling percentile for latency measurements
    private val metrics = mutableMapOf<String, RollingPercentile>()

    fun recordLatency(stage: String, latencyUs: Long) {
        getOrCreate(stage).add(latencyUs)
    }

    fun recordFrameMetrics(
        frameId: Long,
        networkRecvUs: Long,
        decodeStartUs: Long,
        decodeEndUs: Long,
        presentUs: Long,
    ) {
        recordLatency("network", networkRecvUs)
        recordLatency("decode", decodeEndUs - decodeStartUs)
        recordLatency("end_to_end", presentUs - networkRecvUs)
    }

    fun recordThermal(status: Int) {
        android.os.Trace.setCounter("thermal_status", status.toLong())
    }

    fun recordBattery(level: Int, tempCelsius: Float) {
        android.os.Trace.setCounter("battery_level", level.toLong())
        android.os.Trace.setCounter("battery_temp_celsius", (tempCelsius * 10).toInt().toLong())
    }

    fun recordNetwork(rttMs: Int, lossPct: Float, bwMbps: Int) {
        android.os.Trace.setCounter("network_rtt_ms", rttMs.toLong())
        android.os.Trace.setCounter("network_loss_pct", (lossPct * 100).toInt().toLong())
        android.os.Trace.setCounter("network_bw_mbps", bwMbps.toLong())
    }

    fun recordFrameDrop() {
        android.os.Trace.setCounter("frame_drops", 1L)
    }

    fun recordChoreographerFrame(frameTimeNanos: Long) {
        android.os.Trace.setCounter("choreographer_frame_time_ns", frameTimeNanos)
    }

    private fun getOrCreate(name: String): RollingPercentile {
        return metrics.getOrPut(name) { RollingPercentile(1000) }
    }

    fun getPercentiles(name: String): Triple<Double, Double, Double>? {
        return metrics[name]?.let { it.getPercentiles(50, 95, 99) }
    }

    // Simple rolling percentile implementation
    class RollingPercentile(private val windowSize: Int) {
        private val values = mutableListOf<Long>()

        fun add(value: Long) {
            values.add(value)
            if (values.size > windowSize) values.removeAt(0)
        }

        fun getPercentiles(vararg percentiles: Int): Triple<Double, Double, Double>? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return Triple(
                percentile(sorted, percentiles[0]),
                percentile(sorted, percentiles[1]),
                percentile(sorted, percentiles[2]),
            )
        }

        private fun percentile(sorted: List<Long>, p: Int): Double {
            val index = (sorted.size * p / 100).coerceIn(0, sorted.size - 1)
            return sorted[index].toDouble()
        }
    }
}