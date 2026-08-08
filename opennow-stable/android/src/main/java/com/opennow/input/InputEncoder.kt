package com.opennow.input

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object InputEncoder {
    
    // Protocol v3 packet structure (matching desktop implementation):
    // [Header: 4B] [Timestamp: 8B] [Data: variable]
    // Header: [Version:1B][Type:1B][Flags:2B]
    
    private const val PROTOCOL_VERSION = 3
    private const val INPUT_MOUSE_REL = 0x01
    private const val INPUT_KEYBOARD = 0x02
    private const val INPUT_GAMEPAD = 0x03
    private const val INPUT_TOUCH = 0x04
    
    fun encode(event: InputEvent): ByteArray {
        return when (event) {
            is InputEvent.Touch -> encodeTouch(event)
            is InputEvent.Gamepad -> encodeGamepad(event)
            is InputEvent.Keyboard -> encodeKeyboard(event)
            is InputEvent.Mouse -> encodeMouse(event)
        }
    }
    
    private fun encodeTouch(event: InputEvent.Touch): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 8 + 16).apply { order(ByteOrder.LITTLE_ENDIAN) }
        putHeader(buffer, INPUT_TOUCH)
        buffer.putLong(event.timestampUs)
        buffer.putInt(event.action)
        buffer.putFloat(event.x)
        buffer.putFloat(event.y)
        buffer.putFloat(event.pressure)
        buffer.putInt(event.pointerId)
        return buffer.array()
    }
    
    private fun encodeGamepad(event: InputEvent.Gamepad): ByteArray {
        // Gamepad data: axes count + axes values + buttons bitmask
        val axesCount = event.axes.size()
        val buffer = ByteBuffer.allocate(4 + 8 + 4 + axesCount * 8 + 4).apply { order(ByteOrder.LITTLE_ENDIAN) }
        putHeader(buffer, INPUT_GAMEPAD)
        buffer.putLong(event.timestampUs)
        buffer.putInt(axesCount)
        for (i in 0 until axesCount) {
            val axis = event.axes.keyAt(i)
            val value = event.axes.valueAt(i)
            buffer.putInt(axis)
            buffer.putFloat(value)
        }
        buffer.putInt(event.buttons)
        return buffer.array()
    }
    
    private fun encodeKeyboard(event: InputEvent.Keyboard): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 8 + 12).apply { order(ByteOrder.LITTLE_ENDIAN) }
        putHeader(buffer, INPUT_KEYBOARD)
        buffer.putLong(event.timestampUs)
        buffer.putInt(event.keyCode)
        buffer.putInt(event.action)
        buffer.putInt(event.metaState)
        return buffer.array()
    }
    
    private fun encodeMouse(event: InputEvent.Mouse): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 8 + 8).apply { order(ByteOrder.LITTLE_ENDIAN) }
        putHeader(buffer, INPUT_MOUSE_REL)
        buffer.putLong(event.timestampUs)
        buffer.putFloat(event.deltaX)
        buffer.putFloat(event.deltaY)
        return buffer.array()
    }
    
    private fun putHeader(buffer: ByteBuffer, type: Int) {
        buffer.put(PROTOCOL_VERSION.toByte())
        buffer.put(type.toByte())
        buffer.putShort(0) // Flags (reserved)
    }
}

sealed class InputEvent {
    data class Touch(
        val action: Int,
        val x: Float,
        val y: Float,
        val pressure: Float,
        val pointerId: Int,
        val timestampUs: Long,
    ) : InputEvent()
    
    data class Gamepad(
        val axes: android.util.SparseArray<Float>,
        val buttons: Int,  // Bitmask
        val timestampUs: Long,
    ) : InputEvent()
    
    data class Keyboard(
        val keyCode: Int,
        val action: Int,
        val metaState: Int,
        val timestampUs: Long,
    ) : InputEvent()
    
    data class Mouse(
        val deltaX: Float,
        val deltaY: Float,
        val timestampUs: Long,
    ) : InputEvent()
}