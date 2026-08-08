package com.opennow.threading

import android.os.Build
import android.util.Log
import java.io.File

class CpuAffinity {

    // Parse big core topology from /sys/devices/system/cpu/
    fun getBigCoreMask(): Long {
        var mask = 0L
        
        val topologyDir = File("/sys/devices/system/cpu/")
        if (!topologyDir.exists()) return 0L

        topologyDir.listFiles()?.forEach { cpuDir ->
            if (cpuDir.name.startsWith("cpu")) {
                val cpuNum = cpuDir.name.substring(3).toIntOrNull() ?: return@forEach
                val clusterId = getClusterId(cpuDir)
                
                // Big cores typically in cluster 1+ (varies by SoC)
                if (isBigCoreCluster(clusterId)) {
                    mask = mask or (1L shl cpuNum)
                }
            }
        }
        return mask
    }

    private fun getClusterId(cpuDir: File): Int {
        // Read /sys/devices/system/cpu/cpuN/topology/cluster_id or similar
        val clusterFile = File(cpuDir, "topology/cluster_id")
        return clusterFile.readText().toIntOrNull() ?: 0
    }

    private fun isBigCoreCluster(clusterId: Int): Boolean {
        // Heuristic: cluster 0 = LITTLE, cluster 1+ = big (varies by vendor)
        // Qualcomm: cluster 0=LITTLE, 1=big, 2=prime
        // MediaTek: cluster 0=LITTLE, 1=big
        // Exynos: cluster 0=LITTLE, 1=big
        // Tensor: cluster 0=LITTLE, 1=big, 2=TPU
        return clusterId > 0
    }

    fun applyAffinity(thread: Thread) {
        if (Build.VERSION.SDK_INT >= 29) {
            val mask = getBigCoreMask()
            if (mask != 0L) {
                try {
                    // Note: Setting CPU affinity for a specific thread from another thread
                    // requires native code or the thread to set its own affinity.
                    // For now, log the intent. In production, this should be called
                    // from within the target thread's runnable.
                    Log.i("CpuAffinity", "Would pin ${thread.name} to big cores: ${mask.toString(2)} (call from thread itself)")
                } catch (e: Exception) {
                    Log.w("CpuAffinity", "Failed to set affinity for ${thread.name}: ${e.message}")
                }
            }
        }
    }
}