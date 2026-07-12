package com.chatmini.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chatmini.app.MainActivity
import com.chatmini.app.R
import com.chatmini.app.data.ProxyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class ProxyService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
            ACTION_SWITCH_NODE -> {
                val node = intent.getStringExtra(EXTRA_NODE)
                if (node != null) {
                    serviceScope.launch { switchNode(node) }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopProxy()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProxyInternal()
        serviceScope.cancel()
    }

    private fun startProxy() {
        if (process != null) {
            broadcastState(STATE_RUNNING)
            return
        }

        // Start foreground immediately to satisfy the 5-second limit after
        // Context.startForegroundService() is called.
        startForeground(NOTIFICATION_ID, buildNotification())

        serviceScope.launch {
            try {
                val workDir = File(filesDir, ProxyConfig.WORK_DIR_NAME).apply { mkdirs() }
                val nativeLibDir = applicationInfo.nativeLibraryDir
                    ?: throw IllegalStateException("nativeLibraryDir not available")
                val binary = File(nativeLibDir, ProxyConfig.MIHOMO_LIB_NAME)

                if (!binary.exists() || !binary.canExecute()) {
                    broadcastState(STATE_ERROR, "mihomo binary not found in nativeLibraryDir")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    return@launch
                }

                val configFile = File(workDir, ProxyConfig.CONFIG_FILE_NAME)
                if (!configFile.exists()) {
                    broadcastState(STATE_ERROR, "config not found, please import subscription first")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    return@launch
                }

                // Older configs may trigger a GeoIP MMDB download on startup.
                // Make sure fallback-filter.geoip is disabled to avoid this.
                ensureGeoIpFallbackDisabled(configFile)

                // If a previous mihomo survived an abrupt app kill, terminate it
                // before trying to bind the same ports.
                killStaleMihomo(workDir)
                if (isSocksPortOpen() || isControlPortOpen()) {
                    broadcastState(STATE_ERROR, "proxy ports still occupied, please stop other proxy apps")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    return@launch
                }

                val pb = ProcessBuilder(binary.absolutePath, "-f", configFile.absolutePath)
                    .directory(workDir)
                    .redirectErrorStream(true)
                process = pb.start()
                val pid = getProcessPid(process!!)
                if (pid > 0) {
                    writeProcessPid(workDir, pid)
                }

                // Read output in parallel so we can diagnose startup failures.
                readProcessOutput()

                // Wait for mihomo to finish initial setup (it may download GeoIP DB).
                val ready = waitForSocksPort()
                if (ready) {
                    broadcastState(STATE_RUNNING)
                } else {
                    val reason = when {
                        process?.isAlive != true -> "mihomo exited with code ${process?.exitValue() ?: -1}"
                        else -> "SOCKS5 port is not listening after ${STARTUP_TIMEOUT_MS}ms"
                    }
                    process?.destroy()
                    process = null
                    broadcastState(STATE_ERROR, reason)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy", e)
                broadcastState(STATE_ERROR, e.message)
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun stopProxy() {
        stopProxyInternal()
        stopSelf()
    }

    private fun stopProxyInternal() {
        process?.let {
            try {
                it.destroy()
                if (!it.waitFor(2, TimeUnit.SECONDS)) {
                    it.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping process", e)
            }
        }
        process = null
        try {
            File(filesDir, "${ProxyConfig.WORK_DIR_NAME}/$PID_FILE_NAME").delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete mihomo pid file", e)
        }
        broadcastState(STATE_STOPPED)
    }

    private suspend fun switchNode(nodeName: String) {
        withContext(Dispatchers.IO) {
            try {
                val url = "http://127.0.0.1:${ProxyConfig.CONTROL_API_PORT}/proxies/Proxy"
                val body = JSONObject().put("name", nodeName).toString()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${ProxyConfig.CONTROL_API_SECRET}")
                    .put(body.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    broadcastState(STATE_NODE_CHANGED, nodeName)
                } else {
                    broadcastState(STATE_ERROR, "switch node failed: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch node", e)
                broadcastState(STATE_ERROR, e.message)
            }
        }
    }

    private suspend fun waitForSocksPort(): Boolean {
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (process?.isAlive != true) {
                return false
            }
            if (isSocksPortOpen()) {
                return true
            }
            delay(SOCKS_CHECK_INTERVAL_MS)
        }
        return isSocksPortOpen()
    }

    private fun isSocksPortOpen(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", ProxyConfig.SOCKS_PROXY_PORT), SOCKS_CHECK_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isControlPortOpen(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", ProxyConfig.CONTROL_API_PORT), SOCKS_CHECK_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun killStaleMihomo(workDir: File) {
        if (!isSocksPortOpen() && !isControlPortOpen()) return

        val pidFile = File(workDir, PID_FILE_NAME)
        val pid = try {
            pidFile.readText().trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }

        pid?.let {
            if (it > 0) {
                try {
                    android.os.Process.killProcess(it)
                    Log.d(TAG, "Killed stale mihomo process $it")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to kill stale mihomo process $it", e)
                }
            }
        }

        val deadline = System.currentTimeMillis() + STALE_KILL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && (isSocksPortOpen() || isControlPortOpen())) {
            delay(STALE_KILL_CHECK_INTERVAL_MS)
        }

        pidFile.delete()
    }

    private fun writeProcessPid(workDir: File, pid: Int) {
        try {
            File(workDir, PID_FILE_NAME).writeText(pid.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write mihomo pid", e)
        }
    }

    private fun getProcessPid(process: Process): Int {
        return try {
            process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read process pid", e)
            -1
        }
    }

    private fun ensureGeoIpFallbackDisabled(configFile: File) {
        try {
            val lines = configFile.readLines().toMutableList()

            val dnsStart = lines.indexOfFirst { it.trim() == "dns:" }
            val proxiesStart = lines.indexOfFirst { it.trim() == "proxies:" }
            if (dnsStart == -1 || proxiesStart == -1 || dnsStart > proxiesStart) return

            // Remove any existing fallback-filter block (even if it was placed incorrectly).
            var i = 0
            while (i < lines.size) {
                if (lines[i].trim() == "fallback-filter:") {
                    lines.removeAt(i)
                    while (i < lines.size && lines[i].startsWith("    ")) {
                        lines.removeAt(i)
                    }
                } else {
                    i++
                }
            }

            // Find the end of the fallback list inside the DNS section.
            val newProxiesStart = lines.indexOfFirst { it.trim() == "proxies:" }
            var inFallback = false
            var insertIndex = -1
            for (j in (dnsStart + 1) until newProxiesStart) {
                val line = lines[j]
                when {
                    line.trim() == "fallback:" -> inFallback = true
                    inFallback && line.startsWith("    - ") -> insertIndex = j + 1
                    inFallback && !line.startsWith("    ") -> break
                }
            }

            if (insertIndex != -1) {
                lines.add(insertIndex, "  fallback-filter:")
                lines.add(insertIndex + 1, "    geoip: false")
                configFile.writeText(lines.joinToString("\n") + "\n")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to patch config for GeoIP fallback", e)
        }
    }

    private fun readProcessOutput() {
        serviceScope.launch {
            try {
                process?.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line ->
                        Log.d(TAG, "mihomo: $line")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading process output", e)
            }
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun broadcastState(state: String, message: String? = null) {
        proxyState.value = state
        val intent = Intent(ACTION_PROXY_STATE).apply {
            putExtra(EXTRA_STATE, state)
            message?.let { putExtra(EXTRA_MESSAGE, it) }
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "ProxyService"
        private const val CHANNEL_ID = "proxy_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PID_FILE_NAME = "mihomo.pid"
        private const val STALE_KILL_TIMEOUT_MS = 3000L
        private const val STALE_KILL_CHECK_INTERVAL_MS = 200L
        private const val STARTUP_TIMEOUT_MS = 20000L
        private const val SOCKS_CHECK_INTERVAL_MS = 500L
        private const val SOCKS_CHECK_TIMEOUT_MS = 1000

        const val ACTION_START = "com.chatmini.app.action.START_PROXY"
        const val ACTION_STOP = "com.chatmini.app.action.STOP_PROXY"
        const val ACTION_SWITCH_NODE = "com.chatmini.app.action.SWITCH_NODE"
        const val ACTION_PROXY_STATE = "com.chatmini.app.action.PROXY_STATE"

        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_NODE = "node"

        const val STATE_RUNNING = "running"
        const val STATE_STOPPED = "stopped"
        const val STATE_ERROR = "error"
        const val STATE_NODE_CHANGED = "node_changed"

        val proxyState = MutableStateFlow(STATE_STOPPED)

        fun start(context: Context) {
            val intent = Intent(context, ProxyService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun switchNode(context: Context, nodeName: String) {
            val intent = Intent(context, ProxyService::class.java).apply {
                action = ACTION_SWITCH_NODE
                putExtra(EXTRA_NODE, nodeName)
            }
            context.startService(intent)
        }
    }
}
