package com.example.sesstream

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Monitors the main thread for potential ANRs (Application Not Responding).
 * This class posts a delayed task to the main thread and checks if it executes in a timely manner.
 * If the main thread is blocked, the watchdog thread will detect it and log it.
 */
class ANRMonitor private constructor() {
    private val TAG = "ANRMonitor"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watchdogThread: Thread? = null
    private var isMonitoring = false
    private var lastTickTime = 0L
    private val tickInterval = 1000L // Check every second
    private val anrThreshold = 5000L // 5 seconds is considered an ANR
    
    companion object {
        private var instance: ANRMonitor? = null
        
        @JvmStatic
        fun getInstance(): ANRMonitor {
            if (instance == null) {
                synchronized(ANRMonitor::class.java) {
                    if (instance == null) {
                        instance = ANRMonitor()
                    }
                }
            }
            return instance!!
        }
    }
    
    /**
     * Start monitoring for ANRs
     */
    fun start() {
        if (isMonitoring) return
        
        isMonitoring = true
        lastTickTime = System.currentTimeMillis()
        
        // Schedule the first tick on the main thread
        scheduleTick()
        
        // Start watchdog thread to monitor main thread responsiveness
        watchdogThread = Thread({
            try {
                monitorMainThread()
            } catch (e: InterruptedException) {
                Log.i(TAG, "Watchdog thread interrupted")
            }
        }, "ANR-Watchdog")
        
        watchdogThread?.isDaemon = true
        watchdogThread?.start()
        
        FileLogger.getInstance()?.info(TAG, "ANR monitoring started")
        Log.i(TAG, "ANR monitoring started")
    }
    
    /**
     * Stop monitoring for ANRs
     */
    fun stop() {
        if (!isMonitoring) return
        
        isMonitoring = false
        watchdogThread?.interrupt()
        watchdogThread = null
        
        FileLogger.getInstance()?.info(TAG, "ANR monitoring stopped")
        Log.i(TAG, "ANR monitoring stopped")
    }
    
    private fun scheduleTick() {
        if (!isMonitoring) return
        
        mainHandler.postDelayed({
            // Update the tick time to indicate the main thread is responsive
            lastTickTime = System.currentTimeMillis()
            
            // Schedule the next tick
            scheduleTick()
        }, tickInterval)
    }
    
    private fun monitorMainThread() {
        while (isMonitoring && !Thread.currentThread().isInterrupted) {
            try {
                // Sleep for the tick interval
                Thread.sleep(tickInterval)
                
                // Check if the main thread has updated lastTickTime recently
                val currentTime = System.currentTimeMillis()
                val timeSinceLastTick = currentTime - lastTickTime
                
                // If it's been too long since the last tick, log potential ANR
                if (timeSinceLastTick > anrThreshold) {
                    val threadState = getMainThreadState()
                    val stackTrace = getMainThreadStackTrace()
                    
                    FileLogger.getInstance()?.error(TAG, "Potential ANR detected! Main thread blocked for ${timeSinceLastTick}ms")
                    FileLogger.getInstance()?.error(TAG, "Main thread state: $threadState")
                    FileLogger.getInstance()?.error(TAG, "Main thread stack trace:\n$stackTrace")
                    
                    Log.e(TAG, "Potential ANR detected! Main thread blocked for ${timeSinceLastTick}ms")
                    
                    // Don't log again for this ANR period - wait until it resolves or the app crashes
                    lastTickTime = currentTime - anrThreshold + 10000
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                FileLogger.getInstance()?.error(TAG, "Error in ANR monitoring", e)
                Log.e(TAG, "Error in ANR monitoring", e)
            }
        }
    }
    
    private fun getMainThreadState(): String {
        val mainThread = getMainThread()
        return mainThread?.state?.toString() ?: "Unknown"
    }
    
    private fun getMainThreadStackTrace(): String {
        val mainThread = getMainThread()
        return if (mainThread != null) {
            val stackTraceElements = mainThread.stackTrace
            stackTraceElements.joinToString("\n") { "\tat $it" }
        } else {
            "Could not get main thread stack trace"
        }
    }
    
    private fun getMainThread(): Thread? {
        // Get all threads
        val threadSet = Thread.getAllStackTraces().keys
        
        // Find the main thread
        return threadSet.find { thread ->
            thread.id == Looper.getMainLooper().thread.id
        }
    }
} 