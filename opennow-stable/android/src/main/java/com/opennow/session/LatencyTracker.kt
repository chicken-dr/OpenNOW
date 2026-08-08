package com.opennow.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LatencyTracker {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Per-stage latencies in microseconds
    private val networkLatencies = mutableListOf<Long>()
    private val decodeLatencies = mutableListOf<Long>()
    private val endToEndLatencies = mutableListOf<Long>()
    private val frameIntervals = mutableListOf<Long>()
    
    private var lastFrameTime: Long = 0
    private var lastDecodeStartTime: Long = 0
    
    fun recordNetworkReceive(receiveTimeUs: Long) {
        // Store for end-to-end calculation
    }
    
    fun recordDecodeStart(decodeStartUs: Long) {
        // Store for decode latency
        lastDecodeStartTime = decodeStartUs
    }
    
    fun recordDecodeEnd(decodeEndUs: Long) {
        // Calculate decode latency
        val decodeLatencyUs = decodeEndUs - lastDecodeStartTime
        decodeLatencies.add(decodeLatencyUs)
        if (decodeLatencies.size > 1000) decodeLatencies.removeAt(0)
    }
    
    fun recordFramePresent(presentTimeUs: Long, networkReceiveTimeUs: Long) {
        val endToEndLatencyUs = presentTimeUs - networkReceiveTimeUs
        endToEndLatencies.add(endToEndLatencyUs)
        if (endToEndLatencies.size > 1000) endToEndLatencies.removeAt(0)
        
        if (lastFrameTime > 0) {
            val interval = presentTimeUs - lastFrameTime
            frameIntervals.add(interval)
            if (frameIntervals.size > 1000) frameIntervals.removeAt(0)
        }
        lastFrameTime = presentTimeUs
    }
    
    fun recordNetworkLatency(latencyUs: Long) {
        networkLatencies.add(latencyUs)
        if (networkLatencies.size > 1000) networkLatencies.removeAt(0)
    }
    
    fun getNetworkLatencyP50(): Long = percentile(networkLatencies, 50)
    fun getNetworkLatencyP95(): Long = percentile(networkLatencies, 95)
    fun getNetworkLatencyP99(): Long = percentile(networkLatencies, 99)
    
    fun getDecodeLatencyP50(): Long = percentile(decodeLatencies, 50)
    fun getDecodeLatencyP95(): Long = percentile(decodeLatencies, 95)
    fun getDecodeLatencyP99(): Long = percentile(decodeLatencies, 99)
    
    fun getEndToEndLatencyP50(): Long = percentile(endToEndLatencies, 50)
    fun getEndToEndLatencyP95(): Long = percentile(endToEndLatencies, 95)
    fun getEndToEndLatencyP99(): Long = percentile(endToEndLatencies, 99)
    
    fun getFrameIntervalP50(): Long = percentile(frameIntervals, 50)
    fun getFrameIntervalP95(): Long = percentile(frameIntervals, 95)
    fun getFrameIntervalP99(): Long = percentile(frameIntervals, 99)
    
    fun getAverageFps(): Double {
        val p50 = getFrameIntervalP50()
        return if (p50 > 0) 1_000_000.0 / p50 else 0.0
    }
    
    private fun percentile(list: List<Long>, p: Int): Long {
        if (list.isEmpty()) return 0
        val sorted = list.sorted()
        val index = (sorted.size * p / 100).coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
    
    fun reset() {
        networkLatencies.clear()
        decodeLatencies.clear()
        endToEndLatencies.clear()
        frameIntervals.clear()
        lastFrameTime = 0
        lastDecodeStartTime = 0
    }
}