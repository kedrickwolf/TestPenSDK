package com.example.stylustest

import android.app.Activity
import android.content.Context
import android.util.Log

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
 * NOTE: The Shizuku v13+ API surface (Shizuku.pingBinder, Shizuku.bindAsUser, Shell, etc.)
 * could not be verified against the installed artifact. This stub compiles against the
 * declared Shizuku dependency but does not yet wire actual Shizuku IPC calls.
 *
 * To wire the real API, replace the stub bodies in [state], [checkShellPermission],
 * [requestShellPermission], [bind], and [runCommand] with calls to the Shizuku API
 * classes (e.g. dev.rikka.shizuku.api.Shizuku, dev.rikka.shizuku.api.Shell).
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
    private var bound = false

    /** Current Shizuku availability state. Checks for the Shizuku API class at runtime. */
    fun state(): ShizukuState {
        return if (isShizukuApiClassAvailable()) {
            ShizukuState.InstalledRunning
        } else {
            ShizukuState.NotInstalled
        }
    }

    private fun isShizukuApiClassAvailable(): Boolean {
        return try {
            Class.forName("dev.rikka.shizuku.api.Shizuku")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    /** Check whether the shell permission is already granted. Stub — returns Denied. */
    fun checkShellPermission(): ShellPermissionState {
        Log.w(logTag, "checkShellPermission not wired to Shizuku API")
        return ShellPermissionState.Denied
    }

    /**
     * Request the shell permission from the user. Stub — no-op.
     * Wire to Shizuku.requestPermission() once the API is verified.
     */
    fun requestShellPermission(activity: Activity, requestCode: Int) {
        Log.w(logTag, "requestShellPermission not wired to Shizuku API")
    }

    /**
     * Bind to Shizuku's service so we can run shell commands. Stub — always fails.
     * Wire to Shizuku.bindAsUser() once the API is verified.
     */
    fun bind(onBound: (Boolean) -> Unit) {
        if (bound) {
            onBound(true)
            return
        }
        Log.w(logTag, "bind not wired to Shizuku API")
        onBound(false)
    }

    fun unbind() {
        bound = false
    }

    /** Run a one-shot shell command and return its stdout. Stub — returns null. */
    fun runCommand(command: String): String? {
        Log.w(logTag, "runCommand not wired to Shizuku API: $command")
        return null
    }

    /**
     * Start an interactive shell session that runs `getevent -l <device>` and streams
     * lines back via [onLine]. Returns the session so the caller can [stop] it.
     * Stub — logs a warning and does not stream data.
     */
    fun startGetEvent(device: String, onLine: (String) -> Unit): GetEventSession {
        return GetEventSession(device, onLine)
    }

    /** An active getevent session bound to a specific input device. */
    class GetEventSession(
        device: String,
        private val onLine: (String) -> Unit
    ) {
        private var thread: Thread? = null
        private var running = false

        init {
            start(device)
        }

        private fun start(device: String) {
            running = true
            thread = Thread {
                Log.w("GetEventSession", "Shizuku shell not wired; no getevent stream for $device")
            }
            thread?.isDaemon = true
            thread?.start()
        }

        fun stop() {
            running = false
            thread?.interrupt()
            thread = null
        }
    }
}
