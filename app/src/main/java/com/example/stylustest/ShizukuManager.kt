package com.example.stylustest

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/** Result of checking Shizuku availability. */
sealed class ShizukuState {
    object InstalledRunning : ShizukuState()
    object NotInstalled : ShizukuState()
    object InstalledNotRunning : ShizukuState()
    object Unknown : ShizukuState()
}

/** Result of a shell permission check. */
sealed class ShellPermissionState {
    object Granted : ShellPermissionState()
    object Denied : ShellPermissionState()
    object Unknown : ShellPermissionState()
}

/**
 * Manages Shizuku binding, shell permission, and running shell commands through Shizuku.
 *
 * Uses the real Shizuku v13 API:
 * - [Shizuku.pingBinder] to check if Shizuku is running
 * - [Shizuku.checkSelfPermission] / [Shizuku.requestPermission] for shell permission
 * - [Shizuku.newProcess] to run shell commands (e.g. `getevent -l <device>`)
 */
class ShizukuManager private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: ShizukuManager? = null

        fun get(context: Context): ShizukuManager {
            return instance ?: synchronized(this) {
                instance ?: ShizukuManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val logTag = "ShizukuManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Callback invoked when the shell permission request completes. */
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private var pendingPermissionRequestCode = -1

    /** Current Shizuku availability state. Checks via Shizuku.pingBinder(). */
    fun state(): ShizukuState {
        return if (isShizukuApiClassAvailable()) {
            try {
                val binder = Shizuku.pingBinder()
                if (binder != null) {
                    ShizukuState.InstalledRunning
                } else {
                    ShizukuState.InstalledNotRunning
                }
            } catch (e: Exception) {
                Log.w(logTag, "pingBinder failed", e)
                ShizukuState.Unknown
            }
        } else {
            ShizukuState.NotInstalled
        }
    }

    private fun isShizukuApiClassAvailable(): Boolean {
        return try {
            Class.forName("rikka.shizuku.Shizuku")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    /** Check whether the shell permission is already granted. */
    fun checkShellPermission(): ShellPermissionState {
        if (!isShizukuApiClassAvailable()) return ShellPermissionState.Unknown
        return when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                ShellPermissionState.Granted
            else -> ShellPermissionState.Denied
        }
    }

    /**
     * Request the shell permission from the user. The result is delivered via
     * [setPermissionCallback] rather than Android's standard onRequestPermissionsResult,
     * because Shizuku v13 uses its own listener ([Shizuku.OnRequestPermissionResultListener]).
     */
    fun requestShellPermission(activity: Activity, requestCode: Int, callback: (Boolean) -> Unit) {
        if (!isShizukuApiClassAvailable()) {
            callback(false)
            return
        }
        pendingPermissionRequestCode = requestCode
        permissionCallback = callback

        Shizuku.addRequestPermissionResultListener(
            object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode == pendingPermissionRequestCode) {
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        permissionCallback?.invoke(granted)
                        permissionCallback = null
                        Shizuku.removeRequestPermissionResultListener(this)
                    }
                }
            },
            mainHandler
        )
        Shizuku.requestPermission(requestCode)
    }

    /**
     * Bind to Shizuku. In v13 this is a lightweight check — shell access goes through
     * [Shizuku.newProcess] rather than a bound service connection. Returns true if Shizuku
     * is running and accessible.
     */
    fun bind(onBound: (Boolean) -> Unit) {
        if (!isShizukuApiClassAvailable()) {
            onBound(false)
            return
        }
        val binder = Shizuku.pingBinder()
        if (binder != null) {
            Log.d(logTag, "Shizuku binder available")
            onBound(true)
        } else {
            Log.w(logTag, "Shizuku binder not available")
            onBound(false)
        }
    }

    fun unbind() {
        // No explicit unbinding needed for shell access via newProcess()
    }

    /** Run a one-shot shell command and return its stdout. */
    fun runCommand(command: String): String? {
        if (!isShizukuApiClassAvailable()) {
            Log.w(logTag, "Shizuku API not available")
            return null
        }
        if (Shizuku.pingBinder() == null) {
            Log.w(logTag, "Shizuku not running")
            return null
        }
        try {
            val process: ShizukuRemoteProcess = Shizuku.newProcess(
                arrayOf("sh", "-c", command),
                null,
                null
            )
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            process.destroy()
            return output
        } catch (e: Exception) {
            Log.w(logTag, "runCommand failed: $command", e)
            return null
        }
    }

    /**
     * Start an interactive shell session that runs `getevent -l <device>` and streams
     * lines back via [onLine]. Returns the session so the caller can [stop] it.
     */
    fun startGetEvent(device: String, onLine: (String) -> Unit): GetEventSession {
        return GetEventSession(device, onLine)
    }

    /** An active getevent session bound to a specific input device. */
    class GetEventSession(
        device: String,
        private val onLine: (String) -> Unit
    ) {
        private var process: ShizukuRemoteProcess? = null
        private var thread: Thread? = null
        private var running = false

        private companion object {
            val mainHandler = Handler(Looper.getMainLooper())
        }

        init {
            start(device)
        }

        private fun start(device: String) {
            running = true
            try {
                process = Shizuku.newProcess(
                    arrayOf("getevent", device),
                    null,
                    null
                )
                val inputStream = process?.inputStream
                if (inputStream == null) {
                    running = false
                    return
                }
                thread = Thread {
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    while (running) {
                        val line = reader.readLine()
                        if (line == null) break
                        if (running) {
                            mainHandler.post { onLine(line) }
                        }
                    }
                }
                thread?.isDaemon = true
                thread?.start()
            } catch (e: Exception) {
                Log.w("GetEventSession", "getevent stream failed for $device", e)
                running = false
            }
        }

        fun stop() {
            running = false
            thread?.interrupt()
            thread = null
            process?.destroy()
            process = null
        }
    }
}
