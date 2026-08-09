package com.closenow.threading

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

class ThreadManager constructor(
    private val cpuAffinity: CpuAffinity,
) {

    // Priority constants
    object Priority {
        const val URGENT_AUDIO = Process.THREAD_PRIORITY_URGENT_AUDIO  // -16
        const val URGENT_DISPLAY = Process.THREAD_PRIORITY_URGENT_DISPLAY  // -8
        const val HIGH = -12  // Custom
        const val MAX_PRIORITY = 10
        const val NORMAL = 0
    }

    private val threads = mutableMapOf<String, Thread>()
    private val handlers = mutableMapOf<String, Handler>()

    fun createNetworkThread(runnable: Runnable): Thread {
        return createThread("CloseNOW-Network", runnable, Priority.URGENT_AUDIO, true)
    }

    fun createDecoderThread(runnable: Runnable): Thread {
        return createThread("CloseNOW-Decoder", runnable, Priority.HIGH, true)
    }

    fun createInputThread(runnable: Runnable): Thread {
        return createThread("CloseNOW-Input", runnable, Priority.MAX_PRIORITY, true)
    }

    fun createCoordinationThread(runnable: Runnable): Thread {
        return createThread("CloseNOW-Coordination", runnable, Priority.NORMAL, false)
    }

    private fun createThread(
        name: String,
        runnable: Runnable,
        priority: Int,
        pinToBigCores: Boolean
    ): Thread {
        val thread = Thread(runnable).apply {
            this.name = name
            this.priority = priority
            if (pinToBigCores) {
                start()
                cpuAffinity.applyAffinity(this)
            } else {
                start()
            }
        }
        threads[name] = thread
        return thread
    }

    fun registerHandler(threadName: String, handler: Handler) {
        handlers[threadName] = handler
    }

    fun getHandler(threadName: String): Handler? = handlers[threadName]

    fun shutdown() {
        handlers.values.forEach { it.removeCallbacksAndMessages(null) }
        threads.values.forEach { it.interrupt() }
        threads.clear()
        handlers.clear()
    }
}