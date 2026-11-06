package com.example.stylustest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import dev.rikka.shizuku.api.Shizuku
import dev.rikka.shizuku.api.ShizukuPermission
import java.io.BufferedReader
import java.io.InputStreamReader

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
 * Uses Shizuku's public API (v13+): pingBinder, checkSelfPermission, requestPermission,
 * bindAsUser, and Shell. Reflection is NOT used.
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
    private var binder: IBinder? = null
    private var bound = false

    /** Current Shizuku availability state. */
    fun state(): ShizukuState {
        return if (Shizuku.pingBinder()) {
            ShizukuState.InstalledRunning
        } else {
            ShizukuState.InstalledNotRunning
        }
    }

    /** Check whether the shell permission is already granted. */
    fun checkShellPermission(): ShellPermissionState {
        return when {
            Shizuku.checkSelfPermission(context, ShizukuPermission.SHELL) -> ShellPermissionState.Granted
            else -> ShellPermissionState.Denied
        }
    }

    /**
     * Request the shell permission from the user. The result is delivered via
     * [Activity.onRequestPermissionsResult] or the Shizuku permission result listener,
     * depending on the Shizuku version. Callers should re-check [checkShellPermission]
     * after the user responds.
     */
    fun requestShellPermission(activity: Activity, requestCode: Int) {
        Shizuku.requestPermission(activity, ShizukuPermission.SHELL, requestCode)
    }

    /**
     * Bind to Shizuku's service so we can run shell commands. Call [unbind] when done.
     * The [onBound] callback is invoked with true on success, false on failure.
     */
    fun bind(onBound: (Boolean) -> Unit) {
        if (bound) {
            onBound(true)
            return
        }
        val cb = object : Shizuku.BinderCallback {
            override fun onBinderReceived(binder: IBinder) {
                this@ShizukuManager.binder = binder
                bound = true
                onBound(true)
            }

            override fun onBinderSent() {
                // No-op
            }

            override fun onBinderDisconnected() {
                bound = false
                onBound(false)
            }
        }
        try {
            Shizuku.bindAsUser(context, cb)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to bind to Shizuku", e)
            onBound(false)
        }
    }

    fun unbind() {
        binder = null
        bound = false
    }

    /** Run a one-shot shell command and return its stdout. */
    fun runCommand(command: String): String? {
        if (!bound || binder == null) return null
        try {
            val shell = dev.rikka.shizuku.api.Shell(binder!!)
            val result = shell.run(command)
            return if (result.code == 0) result.stdout else null
        } catch (e: Exception) {
            Log.e(logTag, "Shell command failed: $command", e)
            return null
        }
    }

    /**
     * Start an interactive shell session that runs `getevent -l <device>` and streams
     * lines back via [onLine]. Returns the session so the caller can [stop] it.
     */
    fun startGetEvent(device: String, onLine: (String) -> Unit): GetEventSession {
        return GetEventSession(binder, device, onLine)
    }

    /** An active getevent session bound to a specific input device. */
    class GetEventSession private constructor(
        private val binder: IBinder?,
        device: String,
        private val onLine: (String) -> Unit
    ) {
        private var thread: Thread? = null
        private var running = false

        init {
            start(device)
        }

        private fun start(device: String) {
            if (binder == null) return
            running = true
            thread = Thread {
                try {
                    val shell = dev.rikka.shizuku.api.Shell(binder!!)
                    val session = shell.start()
                    val out = java.io.OutputStreamWriter(session.outputStream)
                    out.write("getevent -l $device\n")
                    out.flush()
                    val reader = BufferedReader(InputStreamReader(session.inputStream))
                    while (running) {
                        val line = reader.readLine()
                        if (line == null) break
                        onLine(line)
                    }
                    session.close()
                } catch (e: Exception) {
                    android.util.Log.e("GetEventSession", "getevent stream failed", e)
                }
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
