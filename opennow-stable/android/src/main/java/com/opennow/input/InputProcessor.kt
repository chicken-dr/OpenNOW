package com.opennow.input

import android.util.Log
import com.opennow.network.NetworkSender
import com.opennow.threading.ThreadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class InputProcessor constructor(
    private val networkSender: NetworkSender,
    private val threadManager: ThreadManager,
) {

    // High-priority input channel - bounded to prevent memory pressure
    private val inputChannel = Channel<InputEvent>(1024)
    private val scope = CoroutineScope(Dispatchers.Default)
    private var running = false

    // Touch handling (from SurfaceView)
    fun onTouchEvent(event: android.view.MotionEvent) {
        val timestampUs = event.eventTime * 1000L  // Kernel CLOCK_MONOTONIC (ms -> μs)
        val inputEvent = InputEvent.Touch(
            action = event.action,
            x = event.x,
            y = event.y,
            pressure = event.pressure,
            pointerId = event.getPointerId(event.actionIndex),
            timestampUs = timestampUs,
        )
        // Non-blocking offer
        inputChannel.trySend(inputEvent)
    }

    // Gamepad handling (from Activity)
    fun onGamepadEvent(event: android.view.MotionEvent) {
        if (!event.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) return

        val timestampUs = event.eventTime * 1000L
        val axes = android.util.SparseArray<Float>()
        
        // Common gamepad axes - MotionEvent doesn't have axisCount/getAxis methods
        // Use known axis constants
        val axisConstants = intArrayOf(
            android.view.MotionEvent.AXIS_X,
            android.view.MotionEvent.AXIS_Y,
            android.view.MotionEvent.AXIS_Z,
            android.view.MotionEvent.AXIS_RZ,
            android.view.MotionEvent.AXIS_HAT_X,
            android.view.MotionEvent.AXIS_HAT_Y,
            android.view.MotionEvent.AXIS_LTRIGGER,
            android.view.MotionEvent.AXIS_RTRIGGER,
            android.view.MotionEvent.AXIS_THROTTLE,
            android.view.MotionEvent.AXIS_RUDDER,
            android.view.MotionEvent.AXIS_WHEEL,
            android.view.MotionEvent.AXIS_GAS,
            android.view.MotionEvent.AXIS_BRAKE
        )
        
        for (axis in axisConstants) {
            val value = event.getAxisValue(axis)
            if (value != 0f) {
                axes.put(axis, value)
            }
        }

        // Note: Gamepad buttons come from KeyEvent, not MotionEvent
        // This is a simplified implementation
        val buttons = 0

        val inputEvent = InputEvent.Gamepad(
            axes = axes,
            buttons = buttons,
            timestampUs = timestampUs,
        )
        inputChannel.trySend(inputEvent)
    }

    // Keyboard handling
    fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        val timestampUs = event.eventTime * 1000L
        val inputEvent = InputEvent.Keyboard(
            keyCode = event.keyCode,
            action = event.action,
            metaState = event.metaState,
            timestampUs = timestampUs,
        )
        inputChannel.trySend(inputEvent)
        return true
    }

    fun start() {
        if (running) return
        running = true
        
        // Launch processing loop on dedicated input thread
        val inputThread = threadManager.createInputThread {
            processingLoop()
        }
        
        Log.i("InputProcessor", "Input processor started on thread: ${inputThread.name}")
    }

    fun stop() {
        running = false
        inputChannel.close()
        Log.i("InputProcessor", "Input processor stopped")
    }

    private fun processingLoop() {
        scope.launch {
            while (running) {
                try {
                    val event = inputChannel.receive()
                    val packet = InputEncoder.encode(event)
                    networkSender.sendImmediate(packet)
                } catch (e: Exception) {
                    if (running) {
                        Log.e("InputProcessor", "Error processing input: ${e.message}")
                    }
                }
            }
        }
    }

    private fun getPressedButtons(event: android.view.KeyEvent): Int {
        var buttons = 0
        val device = event.device
        if (device != null) {
            // Map standard gamepad buttons to bitmask
            // This is a simplified version - real implementation would use InputDevice.getKeyLayout()
            val keyCode = event.keyCode
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_BUTTON_A -> buttons = buttons or 0x0001
                android.view.KeyEvent.KEYCODE_BUTTON_B -> buttons = buttons or 0x0002
                android.view.KeyEvent.KEYCODE_BUTTON_X -> buttons = buttons or 0x0004
                android.view.KeyEvent.KEYCODE_BUTTON_Y -> buttons = buttons or 0x0008
                android.view.KeyEvent.KEYCODE_BUTTON_L1 -> buttons = buttons or 0x0010
                android.view.KeyEvent.KEYCODE_BUTTON_R1 -> buttons = buttons or 0x0020
                android.view.KeyEvent.KEYCODE_BUTTON_L2 -> buttons = buttons or 0x0040
                android.view.KeyEvent.KEYCODE_BUTTON_R2 -> buttons = buttons or 0x0080
                android.view.KeyEvent.KEYCODE_BUTTON_SELECT -> buttons = buttons or 0x0100
                android.view.KeyEvent.KEYCODE_BUTTON_START -> buttons = buttons or 0x0200
                android.view.KeyEvent.KEYCODE_BUTTON_THUMBL -> buttons = buttons or 0x0400
                android.view.KeyEvent.KEYCODE_BUTTON_THUMBR -> buttons = buttons or 0x0800
                android.view.KeyEvent.KEYCODE_DPAD_UP -> buttons = buttons or 0x1000
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> buttons = buttons or 0x2000
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> buttons = buttons or 0x4000
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> buttons = buttons or 0x8000
            }
        }
        return buttons
    }
}