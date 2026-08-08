package com.opennow.diagnostics

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class DumpsysCollector constructor(
    private val context: Context,
) {

    private val packageName = context.packageName

    data class DumpsysSnapshot(
        val timestamp: Long = System.currentTimeMillis(),
        val gfxInfo: String? = null,
        val surfaceFlingerLatency: String? = null,
        val surfaceFlingerLayers: String? = null,
        val mediaCodec: String? = null,
        val thermal: String? = null,
        val battery: String? = null,
        val codecCapabilities: String? = null,
        val thermalBaseline: String? = null,
        val batteryBaseline: String? = null,
        val deviceInfo: String? = null,
    )

    fun collectSessionStart(): DumpsysSnapshot {
        return DumpsysSnapshot(
            deviceInfo = getDeviceInfo(),
            codecCapabilities = collectMediaCodecList(),
            thermalBaseline = collectThermal(),
            batteryBaseline = collectBattery(),
        )
    }

    fun collectSessionEnd(): DumpsysSnapshot {
        return DumpsysSnapshot(
            gfxInfo = collectGfxInfo(),
            surfaceFlingerLatency = collectSurfaceFlingerLatency(),
            surfaceFlingerLayers = collectSurfaceFlingerLayers(),
            mediaCodec = collectMediaCodec(),
            thermal = collectThermal(),
            battery = collectBattery(),
        )
    }

    private fun collectGfxInfo(): String = runShell("dumpsys gfxinfo $packageName")
    private fun collectSurfaceFlingerLatency(): String = runShell("dumpsys SurfaceFlinger --latency")
    private fun collectSurfaceFlingerLayers(): String = runShell("dumpsys SurfaceFlinger --list")
    private fun collectMediaCodec(): String = runShell("dumpsys media.codec")
    private fun collectMediaCodecList(): String = runShell("dumpsys media.codec --list")
    private fun collectThermal(): String = runShell("dumpsys thermal")
    private fun collectBattery(): String = runShell("dumpsys batterystats $packageName")

    private fun getDeviceInfo(): String {
        return "Model: ${android.os.Build.MODEL}\n" +
               "Manufacturer: ${android.os.Build.MANUFACTURER}\n" +
               "Hardware: ${android.os.Build.HARDWARE}\n" +
               "Brand: ${android.os.Build.BRAND}\n" +
               "Device: ${android.os.Build.DEVICE}\n" +
               "SDK: ${android.os.Build.VERSION.SDK_INT}\n" +
               "Fingerprint: ${android.os.Build.FINGERPRINT}"
    }

    private fun runShell(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec("sh -c $cmd")
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                "Exit code: $exitCode\nError: $error"
            } else {
                output
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}