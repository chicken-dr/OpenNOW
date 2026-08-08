package com.opennow.diagnostics

import android.os.Trace
import android.util.Log

class PerfettoTrace {

    // Network receive
    inline fun traceNetworkRecv(crossinline block: () -> ByteArray): ByteArray {
        Trace.beginSection("WebRTC.recvfrom")
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    // RTP processing
    inline fun traceRtpParse(crossinline block: () -> EncodedFrame): EncodedFrame {
        Trace.beginSection("WebRTC.rtpParse")
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    // MediaCodec input
    inline fun traceDequeueInputBuffer(crossinline block: () -> Int): Int {
        Trace.beginSection("MediaCodec.dequeueInputBuffer")
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    inline fun traceQueueInputBuffer(crossinline block: () -> Unit) {
        Trace.beginSection("MediaCodec.queueInputBuffer")
        try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    // MediaCodec output
    inline fun traceDequeueOutputBuffer(crossinline block: () -> Pair<Int, android.media.MediaCodec.BufferInfo>): Pair<Int, android.media.MediaCodec.BufferInfo> {
        Trace.beginSection("MediaCodec.dequeueOutputBuffer")
        try {
            return block()
        } finally {
            Trace.endSection()
        }
    }

    inline fun traceReleaseOutputBuffer(crossinline block: () -> Unit) {
        Trace.beginSection("MediaCodec.releaseOutputBuffer")
        try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    // Input processing
    inline fun traceInputProcess(crossinline block: () -> Unit) {
        Trace.beginSection("Input.processEvent")
        try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    inline fun traceInputSend(crossinline block: () -> Unit) {
        Trace.beginSection("Input.sendPacket")
        try {
            block()
        } finally {
            Trace.endSection()
        }
    }

    // Encoded frame data class
    data class EncodedFrame(
        val data: ByteArray,
        val pts: Long,
        val isKeyframe: Boolean,
    )
}