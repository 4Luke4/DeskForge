package com.deskforge.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.os.Binder
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.deskforge.app.engine.NativeDeskForgeEngine
import com.deskforge.app.model.AudioFailure
import com.deskforge.app.model.AudioMicrophoneStatus
import com.deskforge.app.model.AudioPlaybackStatus
import com.deskforge.app.model.ClipboardFailure
import com.deskforge.app.model.ClipboardTransportStatus
import com.deskforge.app.model.DesktopViewport
import com.deskforge.app.model.GraphicsTransportStatus
import com.deskforge.app.model.SessionAudioState
import com.deskforge.app.model.SessionClipboardState
import com.deskforge.app.model.SessionFailure
import com.deskforge.app.model.SessionState
import com.deskforge.app.model.RendererPreference
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
    private val mutableClipboardState = MutableStateFlow<SessionClipboardState>(SessionClipboardState.Unavailable)
    private val mutableAudioState = MutableStateFlow(SessionAudioState())
    private val operationLock = Any()
    private var launchInProgress = false
    private var stopRequested = false
    private var pendingRootfs: String? = null
    private var pendingRendererPreference = RendererPreference.AUTO
    private var monitorJob: Job? = null
    val state: StateFlow<SessionState> = mutableState
    val clipboardState: StateFlow<SessionClipboardState> = mutableClipboardState
    val audioState: StateFlow<SessionAudioState> = mutableAudioState
    private var receivedClipboard: ReceivedClipboard? = null
    private var clipboardGeneration = 0L
    private var localClipboardFailure: ClipboardFailure? = null
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocusRequest: AudioFocusRequest
    private var audioFocusRequested = false
    private var nextAudioFocusRequestAt = 0L
    private var microphoneConsent = false
    private var localAudioFailure: AudioFailure? = null
    @Volatile
    private var destroying = false

    inner class LocalBinder : Binder() {
        val service: DeskForgeSessionService get() = this@DeskForgeSessionService
    }

    override fun onCreate() {
        super.onCreate()
        engine = NativeDeskForgeEngine(this)
        audioManager = getSystemService(AudioManager::class.java)
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(::onAudioFocusChanged)
            .build()
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
                clearClipboardState()
                clearAudioState()
                val rootfs = intent.getStringExtra(EXTRA_ROOTFS)
                if (rootfs.isNullOrBlank()) {
                    mutableState.value = SessionState.Failed(SessionFailure.WORKSPACE_UNAVAILABLE, true)
                    stopSelf()
                } else {
                    synchronized(operationLock) {
                        stopRequested = false
                        pendingRootfs = rootfs
                        pendingRendererPreference = intent.getStringExtra(EXTRA_RENDERER_PREFERENCE)
                            ?.let { stored ->
                                RendererPreference.entries.firstOrNull { it.name == stored }
                            }
                            ?: RendererPreference.AUTO
                        mutableState.value = SessionState.Starting
                    }
                    promoteToForeground()
                }
            }
            ACTION_STOP -> {
                if (mutableState.value == SessionState.Idle) stopSelf() else stopDesktop()
            }
            ACTION_DISABLE_MICROPHONE -> disableMicrophone()
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
            val result = engine.startSession(
                rootfs,
                surface,
                viewport,
                pendingRendererPreference,
            )
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
                clearClipboardState()
                clearAudioState()
                mutableState.value = SessionState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else if (result is SessionState.Running) {
                updateNotification(running = true)
                startMonitoring()
            } else {
                clearClipboardState()
                clearAudioState()
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

    fun sendText(text: String) {
        runWhileRunning { engine.sendText(text) }
    }

    fun pasteClipboardText(text: String) {
        runWhileRunning {
            localClipboardFailure = null
            mutableClipboardState.value = SessionClipboardState.Sending
            if (!engine.offerClipboardText(text)) refreshClipboardState()
        }
    }

    fun requestDesktopClipboard() {
        runWhileRunning {
            localClipboardFailure = null
            mutableClipboardState.value = SessionClipboardState.Receiving
            if (!engine.requestClipboardText()) refreshClipboardState()
        }
    }

    fun receivedClipboard(generation: Long): String? = synchronized(operationLock) {
        receivedClipboard?.takeIf { it.generation == generation }?.text
    }

    fun acknowledgeClipboard(generation: Long, failure: ClipboardFailure? = null) {
        serviceScope.launch {
            synchronized(operationLock) {
                if (receivedClipboard?.generation != generation) return@launch
                receivedClipboard = null
            }
            localClipboardFailure = failure
            mutableClipboardState.value = failure?.let {
                SessionClipboardState.Failed(it, remoteTextAvailable = false)
            } ?: SessionClipboardState.Idle(remoteTextAvailable = false)
        }
    }

    fun reportClipboardFailure(failure: ClipboardFailure) {
        serviceScope.launch {
            localClipboardFailure = failure
            val remoteAvailable = when (val current = mutableClipboardState.value) {
                is SessionClipboardState.Idle -> current.remoteTextAvailable
                is SessionClipboardState.Failed -> current.remoteTextAvailable
                else -> false
            }
            mutableClipboardState.value = SessionClipboardState.Failed(failure, remoteAvailable)
        }
    }

    fun enableMicrophone() {
        runWhileRunning {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                reportMicrophonePermissionDenied()
                return@runWhileRunning
            }
            microphoneConsent = true
            localAudioFailure = null
            try {
                updateForegroundNotification(running = true, microphoneActive = true)
            } catch (_: SecurityException) {
                microphoneConsent = false
                localAudioFailure = AudioFailure.MICROPHONE_PERMISSION_DENIED
                refreshAudioState()
                return@runWhileRunning
            }
            if (!engine.setMicrophoneCaptureEnabled(true)) {
                microphoneConsent = false
                localAudioFailure = AudioFailure.MICROPHONE_OPEN_FAILED
            }
            refreshAudioState()
            updateForegroundNotification(running = true, microphoneActive = microphoneConsent)
        }
    }

    fun disableMicrophone() {
        runWhileRunning { disableMicrophoneInternal() }
    }

    fun reportMicrophonePermissionDenied() {
        serviceScope.launch {
            microphoneConsent = false
            localAudioFailure = AudioFailure.MICROPHONE_PERMISSION_DENIED
            refreshAudioState()
        }
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
            clearClipboardState()
            clearAudioState()
            mutableState.value = if (result is SessionState.Failed) result else SessionState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        destroying = true
        monitorJob?.cancel()
        val launchActive = synchronized(operationLock) { launchInProgress }
        if (launchActive || mutableState.value != SessionState.Idle) engine.stopSession()
        clearClipboardState()
        clearAudioState()
        engine.detachSurface()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (mutableState.value is SessionState.Running) {
                delay(
                    if (mutableClipboardState.value is SessionClipboardState.Sending ||
                        mutableClipboardState.value is SessionClipboardState.Receiving ||
                        mutableAudioState.value.playbackStatus == AudioPlaybackStatus.WAITING_FOR_FOCUS ||
                        microphoneConsent
                    ) 200 else 1_000,
                )
                if (!engine.isDisplayConnected()) {
                    engine.stopSession()
                    clearClipboardState()
                    clearAudioState()
                    mutableState.value = SessionState.Failed(
                        SessionFailure.DISPLAY_DISCONNECTED,
                        recoverable = true,
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                if (engine.graphicsSnapshot().status == GraphicsTransportStatus.FAILED) {
                    engine.stopSession()
                    clearClipboardState()
                    clearAudioState()
                    mutableState.value = SessionState.Failed(
                        SessionFailure.GRAPHICS_RUNTIME_LOST,
                        recoverable = true,
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                refreshClipboardState()
                if (microphoneConsent &&
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                ) {
                    disableMicrophoneInternal(AudioFailure.MICROPHONE_PERMISSION_REVOKED)
                }
                refreshAudioState()
            }
        }
    }

    private fun runWhileRunning(action: () -> Unit) {
        if (destroying || mutableState.value !is SessionState.Running) return
        serviceScope.launch {
            if (!destroying && mutableState.value is SessionState.Running) action()
        }
    }

    private fun refreshClipboardState() {
        if (mutableClipboardState.value is SessionClipboardState.Ready || localClipboardFailure != null) return
        val snapshot = engine.clipboardSnapshot()
        mutableClipboardState.value = when (snapshot.status) {
            ClipboardTransportStatus.UNSUPPORTED -> SessionClipboardState.Unavailable
            ClipboardTransportStatus.IDLE -> SessionClipboardState.Idle(snapshot.remoteTextAvailable)
            ClipboardTransportStatus.REMOTE_AVAILABLE -> SessionClipboardState.Idle(remoteTextAvailable = true)
            ClipboardTransportStatus.SENDING -> SessionClipboardState.Sending
            ClipboardTransportStatus.RECEIVING -> SessionClipboardState.Receiving
            ClipboardTransportStatus.RECEIVED -> {
                val text = engine.takeClipboardText()
                if (text == null) {
                    SessionClipboardState.Failed(ClipboardFailure.INVALID_TEXT, snapshot.remoteTextAvailable)
                } else {
                    val received = synchronized(operationLock) {
                        ReceivedClipboard(++clipboardGeneration, text).also { receivedClipboard = it }
                    }
                    SessionClipboardState.Ready(received.generation)
                }
            }
            ClipboardTransportStatus.FAILED -> SessionClipboardState.Failed(
                snapshot.failure ?: ClipboardFailure.TRANSFER_FAILED,
                snapshot.remoteTextAvailable,
            )
        }
    }

    private fun clearClipboardState() {
        synchronized(operationLock) { receivedClipboard = null }
        localClipboardFailure = null
        mutableClipboardState.value = SessionClipboardState.Unavailable
    }

    private fun refreshAudioState() {
        var snapshot = engine.audioSnapshot()
        if (microphoneConsent && snapshot.microphoneStatus == AudioMicrophoneStatus.FAILED) {
            // A failed capture route invalidates consent ownership until the user enables it again.
            engine.setMicrophoneCaptureEnabled(false)
            microphoneConsent = false
            localAudioFailure = snapshot.failure ?: AudioFailure.MICROPHONE_OPEN_FAILED
            updateForegroundNotification(
                running = mutableState.value is SessionState.Running,
                microphoneActive = false,
            )
            snapshot = engine.audioSnapshot()
        }
        when (snapshot.playbackStatus) {
            AudioPlaybackStatus.WAITING_FOR_FOCUS -> requestAudioFocusIfEligible()
            AudioPlaybackStatus.IDLE, AudioPlaybackStatus.UNAVAILABLE -> abandonAudioFocus()
            else -> Unit
        }
        mutableAudioState.value = SessionAudioState(
            playbackStatus = snapshot.playbackStatus,
            microphoneStatus = snapshot.microphoneStatus,
            microphoneConsent = microphoneConsent,
            failure = localAudioFailure ?: snapshot.failure,
            outputDeviceId = snapshot.outputDeviceId,
            inputDeviceId = snapshot.inputDeviceId,
            underrunCount = snapshot.underrunCount,
            overflowCount = snapshot.overflowCount,
        )
    }

    private fun requestAudioFocusIfEligible() {
        if (audioFocusRequested || SystemClock.elapsedRealtime() < nextAudioFocusRequestAt) return
        when (audioManager.requestAudioFocus(audioFocusRequest)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                audioFocusRequested = true
                localAudioFailure = null
                if (!engine.setPlaybackAudible(true)) {
                    localAudioFailure = AudioFailure.PLAYBACK_OPEN_FAILED
                }
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> audioFocusRequested = true
            else -> {
                localAudioFailure = AudioFailure.AUDIO_FOCUS_DENIED
                nextAudioFocusRequestAt = SystemClock.elapsedRealtime() + AUDIO_FOCUS_RETRY_MS
            }
        }
    }

    private fun onAudioFocusChanged(change: Int) {
        serviceScope.launch {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    audioFocusRequested = true
                    localAudioFailure = null
                    if (mutableState.value is SessionState.Running) engine.setPlaybackAudible(true)
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    engine.setPlaybackAudible(false)
                    audioFocusRequested = false
                    nextAudioFocusRequestAt = SystemClock.elapsedRealtime() + AUDIO_FOCUS_RETRY_MS
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.setPlaybackAudible(false)
            }
            refreshAudioState()
        }
    }

    private fun abandonAudioFocus() {
        if (!audioFocusRequested) return
        engine.setPlaybackAudible(false)
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        audioFocusRequested = false
    }

    private fun disableMicrophoneInternal(failure: AudioFailure? = null) {
        engine.setMicrophoneCaptureEnabled(false)
        microphoneConsent = false
        localAudioFailure = failure
        refreshAudioState()
        updateForegroundNotification(
            running = mutableState.value is SessionState.Running,
            microphoneActive = false,
        )
    }

    private fun clearAudioState() {
        if (::engine.isInitialized) {
            engine.setMicrophoneCaptureEnabled(false)
            engine.setPlaybackAudible(false)
        }
        if (::audioManager.isInitialized && ::audioFocusRequest.isInitialized) abandonAudioFocus()
        microphoneConsent = false
        localAudioFailure = null
        mutableAudioState.value = SessionAudioState()
    }

    private fun promoteToForeground() {
        updateForegroundNotification(running = false, microphoneActive = false)
    }

    private fun updateNotification(running: Boolean) {
        updateForegroundNotification(running, microphoneConsent)
    }

    private fun updateForegroundNotification(running: Boolean, microphoneActive: Boolean) {
        if (destroying) return
        val types = BASE_FOREGROUND_TYPES or if (microphoneActive) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        // Re-register the active types so disabling capture also removes microphone FGS ownership.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(running, microphoneActive),
            types,
        )
    }

    private fun buildNotification(running: Boolean, microphoneActive: Boolean): Notification {
        // Notification PendingIntents cross process boundaries; fix their targets and mutability.
        val contentIntent = Intent().setComponent(ComponentName(this, MainActivity::class.java))
        val stopIntent = Intent()
            .setComponent(ComponentName(this, DeskForgeSessionService::class.java))
            .setAction(ACTION_STOP)
        val disableMicrophoneIntent = Intent()
            .setComponent(ComponentName(this, DeskForgeSessionService::class.java))
            .setAction(ACTION_DISABLE_MICROPHONE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(
                getString(
                    when {
                        microphoneActive -> R.string.session_notification_microphone
                        running -> R.string.session_notification_running
                        else -> R.string.session_notification_starting
                    },
                ),
            )
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
        if (microphoneActive) {
            builder.addAction(
                0,
                getString(R.string.action_disable_microphone),
                PendingIntent.getService(
                    this,
                    2,
                    disableMicrophoneIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder
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
        const val ACTION_DISABLE_MICROPHONE = "com.deskforge.app.action.DISABLE_MICROPHONE"
        const val EXTRA_ROOTFS = "rootfs"
        const val EXTRA_RENDERER_PREFERENCE = "rendererPreference"
        private const val CHANNEL_ID = "desktop-session"
        private const val NOTIFICATION_ID = 3100
        private const val AUDIO_FOCUS_RETRY_MS = 1_000L
        private val BASE_FOREGROUND_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    }

    private data class ReceivedClipboard(val generation: Long, val text: String)
}
