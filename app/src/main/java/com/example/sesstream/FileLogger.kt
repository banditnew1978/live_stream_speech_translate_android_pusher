package com.example.sesstream

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger utility to write logs to a file in the Downloads directory
 */
class FileLogger private constructor(context: Context) {
    private val logFile: File
    private val logWriter: OutputStreamWriter
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    companion object {
        private const val TAG = "FileLogger"
        private var instance: FileLogger? = null
        
        fun initialize(context: Context): Boolean {
            return try {
                if (instance == null) {
                    instance = FileLogger(context)
                    // Set up uncaught exception handler
                    setupUncaughtExceptionHandler()
                    true
                } else {
                    Log.w(TAG, "FileLogger already initialized")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize FileLogger", e)
                false
            }
        }
        
        fun getInstance(): FileLogger? {
            return instance
        }
        
        // Set up a handler to catch uncaught exceptions
        private fun setupUncaughtExceptionHandler() {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    instance?.error(TAG, "UNCAUGHT EXCEPTION in thread ${thread.name}", throwable)
                    instance?.error(TAG, "Stack trace: ${Log.getStackTraceString(throwable)}")
                    
                    // Log thread state and additional diagnostics
                    instance?.error(TAG, "Thread state: ${thread.state}")
                    instance?.error(TAG, "Memory info: ${getMemoryInfo()}")
                    
                    // Make sure logs are written to disk
                    instance?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in uncaught exception handler", e)
                }
                
                // Call the default handler
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        
        private fun getMemoryInfo(): String {
            val runtime = Runtime.getRuntime()
            val usedMemInMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
            val maxHeapSizeInMB = runtime.maxMemory() / 1048576L
            val availHeapSizeInMB = maxHeapSizeInMB - usedMemInMB
            return "Used: $usedMemInMB MB, Max: $maxHeapSizeInMB MB, Available: $availHeapSizeInMB MB"
        }
        
        // Log levels
        const val DEBUG = "DEBUG"
        const val INFO = "INFO"
        const val WARN = "WARN"
        const val ERROR = "ERROR"
    }
    
    init {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appDir = File(downloadsDir, "SesStreamLogs")
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        logFile = File(appDir, "sesstream_log_$timestamp.txt")
        
        // Create the file and writer
        logFile.createNewFile()
        logWriter = OutputStreamWriter(FileOutputStream(logFile, true))
        
        // Write initial log entry
        log(INFO, TAG, "File logging started. Log file: ${logFile.absolutePath}")
    }
    
    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        try {
            val timestamp = dateFormat.format(Date())
            val logMessage = StringBuilder()
                .append(timestamp)
                .append(" | ")
                .append(level)
                .append(" | ")
                .append(tag)
                .append(" | ")
                .append(message)
            
            // Add exception details if available
            if (throwable != null) {
                logMessage.append("\n").append(throwable.stackTraceToString())
            }
            
            // Write to log file
            synchronized(logWriter) {
                logWriter.write(logMessage.toString() + "\n")
                logWriter.flush()
            }
            
            // Also log to Android's logcat
            when (level) {
                DEBUG -> Log.d(tag, message, throwable)
                INFO -> Log.i(tag, message, throwable)
                WARN -> Log.w(tag, message, throwable)
                ERROR -> Log.e(tag, message, throwable)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }
    
    fun debug(tag: String, message: String, throwable: Throwable? = null) {
        log(DEBUG, tag, message, throwable)
    }
    
    fun info(tag: String, message: String, throwable: Throwable? = null) {
        log(INFO, tag, message, throwable)
    }
    
    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        log(WARN, tag, message, throwable)
    }
    
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        log(ERROR, tag, message, throwable)
    }
    
    fun close() {
        try {
            synchronized(logWriter) {
                logWriter.flush()
                logWriter.close()
            }
            Log.i(TAG, "Log file closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing log file", e)
        }
    }
    
    // Force flush logs to disk
    fun flush() {
        try {
            synchronized(logWriter) {
                logWriter.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing log file", e)
        }
    }
} 