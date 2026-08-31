package com.deskforge.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.deskforge.app.engine.NativeDeskForgeEngine
import com.deskforge.app.model.DesktopViewport
import com.deskforge.app.model.SessionFailure
import com.deskforge.app.model.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Owns the native supervisor while the activity and its rendering surface are recreated. */
class DeskForgeSessionService : Service() {
    private lateinit var engine: NativeDeskForgeEngine
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val operationLock = Any()
    private var launchInProgress = false
    private var stopRequested = false
    private var pendingRootfs: String? = null
    private var monitorJob: Job? = null
    val state: StateFlow<SessionState> = mutableState

    inner class LocalBinder : Binder() {
        val service: DeskForgeSessionService get() = this@DeskForgeSessionService
    }

    override fun onCreate() {
        super.onCreate()
        engine = NativeDeskForgeEngine(this)
        createNotificationChannel()
        engine.activeSessionState()?.let { running ->
            mutableState.value = running
            startMonitoring()
        }
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                val rootfs = intent.getStringExtra(EXTRA_ROOTFS)
                if (rootfs.isNullOrBlank()) {
                    mutableState.value = SessionState.Failed(SessionFailure.WORKSPACE_UNAVAILABLE, true)
                    stopSelf()
                } else {
                    synchronized(operationLock) {
                        stopRequested = false
                        pendingRootfs = rootfs
                        mutableState.value = SessionState.Starting
                    }
                    promoteToForeground()
                }
            }
            ACTION_STOP -> {
                if (mutableState.value == SessionState.Idle) stopSelf() else stopDesktop()
            }
        }
        return START_NOT_STICKY
    }

    fun launchOnSurface(surface: Surface, viewport: DesktopViewport) {
        if (mutableState.value is SessionState.Running) {
            engine.attachSurface(surface, viewport)
            engine.resizeDisplay(viewport)
            return
        }
        val rootfs = synchronized(operationLock) {
            if (launchInProgress) return
            val selectedRootfs = pendingRootfs ?: return
            launchInProgress = true
            selectedRootfs
        }
        serviceScope.launch {
            val result = engine.startSession(rootfs, surface, viewport)
            val shouldStop = synchronized(operationLock) {
                launchInProgress = false
                if (stopRequested) {
                    true
                } else {
                    mutableState.value = result
                    if (result is SessionState.Running) pendingRootfs = null
                    false
                }
            }
            if (shouldStop) {
                engine.stopSession()
                mutableState.value = SessionState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else if (result is SessionState.Running) {
                updateNotification(running = true)
                startMonitoring()
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    fun attachSurface(surface: Surface, viewport: DesktopViewport) {
        if (mutableState.value is SessionState.Running) {
            runWhileRunning {
                engine.attachSurface(surface, viewport)
                engine.resizeDisplay(viewport)
            }
        } else {
            launchOnSurface(surface, viewport)
        }
    }

    fun detachSurface() {
        runWhileRunning { engine.detachSurface() }
    }

    fun resizeDisplay(viewport: DesktopViewport) {
        runWhileRunning { engine.resizeDisplay(viewport) }
    }

    fun sendPointer(x: Int, y: Int, buttons: Int) {
        runWhileRunning { engine.sendPointer(x, y, buttons) }
    }

    fun sendKey(keysym: Int, pressed: Boolean) {
        runWhileRunning { engine.sendKey(keysym, pressed) }
    }

    fun stopDesktop() {
        val waitForLaunch = synchronized(operationLock) {
            if (mutableState.value == SessionState.Idle || mutableState.value is SessionState.Stopping) return
            mutableState.value = SessionState.Stopping
            stopRequested = true
            pendingRootfs = null
            launchInProgress
        }
        monitorJob?.cancel()
        // The launch coroutine owns teardown until native startup releases its serialized boundary.
        if (waitForLaunch) return
        serviceScope.launch {
            val result = engine.stopSession()
            mutableState.value = if (result is SessionState.Failed) result else SessionState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        val launchActive = synchronized(operationLock) { launchInProgress }
        if (launchActive || mutableState.value != SessionState.Idle) engine.stopSession()
        engine.detachSurface()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (mutableState.value is SessionState.Running) {
                delay(1_000)
                if (!engine.isDisplayConnected()) {
                    engine.stopSession()
                    mutableState.value = SessionState.Failed(
                        SessionFailure.DISPLAY_DISCONNECTED,
                        recoverable = true,
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
            }
        }
    }

    private fun runWhileRunning(action: () -> Unit) {
        if (mutableState.value !is SessionState.Running) return
        serviceScope.launch {
            if (mutableState.value is SessionState.Running) action()
        }
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(running = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun updateNotification(running: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(running))
    }

    private fun buildNotification(running: Boolean): Notification {
        // Notification PendingIntents cross process boundaries; fix their targets and mutability.
        val contentIntent = Intent().setComponent(ComponentName(this, MainActivity::class.java))
        val stopIntent = Intent()
            .setComponent(ComponentName(this, DeskForgeSessionService::class.java))
            .setAction(ACTION_STOP)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(getString(if (running) R.string.session_notification_running else R.string.session_notification_starting))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(
                0,
                getString(R.string.action_stop),
                PendingIntent.getService(
                    this,
                    1,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.session_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val ACTION_PREPARE = "com.deskforge.app.action.PREPARE_SESSION"
        const val ACTION_STOP = "com.deskforge.app.action.STOP_SESSION"
        const val EXTRA_ROOTFS = "rootfs"
        private const val CHANNEL_ID = "desktop-session"
        private const val NOTIFICATION_ID = 3100
    }
}
