package com.aiapp.builder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.aiapp.MainActivity
import com.aiapp.llm.ProviderException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/** Runs Build Mode in the background with a notification, so closing the app doesn't stop a build. */
class BuildService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val request = BuildRunner.pending
        if (request == null || job?.isActive == true) return START_NOT_STICKY
        BuildRunner.pending = null
        createChannel()
        startInForeground(progressNotification("Starting…"))
        // Keep the CPU and network awake while building, even with the screen off.
        wakeLock = (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AIApp:build")
            .apply { acquire(60 * 60 * 1000L) }

        BuildRunner.running = true
        job = scope.launch {
            val result = try {
                BuildAgent(
                    request,
                    emit = { event ->
                        scope.launch {
                            BuildRunner.timeline += TimelineItem.Event(event)
                            BuildRunner.save(this@BuildService)
                            notify(PROGRESS_ID, progressNotification(event.text))
                        }
                    },
                    onProject = { p ->
                        scope.launch {
                            BuildRunner.project = p
                            BuildRunner.save(this@BuildService)
                        }
                    },
                    onProgress = { value, text ->
                        scope.launch {
                            BuildRunner.progress = value
                            BuildRunner.progressText = text
                            if (value != null) notify(PROGRESS_ID, progressNotification(text.ifBlank { "Working…" }, value))
                        }
                    },
                ).run()
            } catch (e: CancellationException) {
                BuildResult(false, "Stopped.")
            } catch (e: Throwable) {
                BuildResult(false, friendly(e))
            }
            if (!result.conversationalReply.isNullOrBlank()) {
                BuildRunner.timeline += TimelineItem.Reply(result.conversationalReply)
            } else {
                BuildRunner.timeline += TimelineItem.Result(result.success, result.message, result.apkUrl, result.repoUrl)
            }
            BuildRunner.running = false
            BuildRunner.progress = null
            BuildRunner.saveNow(this@BuildService)
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (result.conversationalReply.isNullOrBlank()) {
                notify(DONE_ID, doneNotification(request.project?.appName ?: request.appName, result))
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        BuildRunner.running = false
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_ID, n)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App builds", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished builds", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notify(id: Int, n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(text: String, value: Float? = null): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BuildService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Building your app")
            .setContentText(text)
            .setOngoing(true)
            .apply { if (value != null) setProgress(100, (value * 100).toInt().coerceIn(0, 100), value < 0f) }
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop,
            ).build())
            .build()
    }

    private fun doneNotification(appName: String, r: BuildResult): Notification {
        val tap = r.apkUrl?.let {
            PendingIntent.getActivity(this, 2, Intent(Intent.ACTION_VIEW, Uri.parse(it)), PendingIntent.FLAG_IMMUTABLE)
        } ?: openAppIntent()
        return Notification.Builder(this, CHANNEL_DONE)
            .setSmallIcon(if (r.success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (r.success) "${appName.ifBlank { "Your app" }} is ready" else "${appName.ifBlank { "Your app" }} didn't build")
            .setContentText(if (r.success && r.apkUrl != null) "Tap to download the APK" else r.message)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
    }

    private fun friendly(t: Throwable): String = when {
        t is ProviderException && t.code == 401 -> "A key or token was rejected. Check Settings. (${t.message})"
        t is ProviderException && t.code == 403 ->
            "Not allowed: ${t.message}. Your GitHub token needs the public_repo and workflow scopes."
        t is ProviderException && t.code == 429 -> "Rate limit reached. Wait a moment and try again."
        t is IOException -> "Network error. Check your connection and try again."
        else -> t.message ?: "Something went wrong."
    }

    companion object {
        private const val CHANNEL = "builds"
        private const val CHANNEL_DONE = "builds_done"
        private const val PROGRESS_ID = 41
        private const val DONE_ID = 42
        private const val ACTION_STOP = "com.aiapp.STOP_BUILD"

        fun start(context: Context, request: BuildRequest) {
            if (BuildRunner.running) return
            BuildRunner.pending = request
            BuildRunner.timeline += TimelineItem.User(request.description)
            BuildRunner.running = true
            BuildRunner.save(context)
            context.startForegroundService(Intent(context, BuildService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BuildService::class.java).setAction(ACTION_STOP))
        }
    }
}
