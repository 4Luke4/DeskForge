package com.deskforge.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ClipboardManager
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.deskforge.app.engine.NativeDeskForgeEngine
import com.deskforge.app.graphics.RendererPreferenceStore
import com.deskforge.app.model.DesktopViewport
import com.deskforge.app.model.SessionAudioState
import com.deskforge.app.model.ClipboardFailure
import com.deskforge.app.model.SessionClipboardState
import com.deskforge.app.model.RuntimeCapabilities
import com.deskforge.app.model.SessionFailure
import com.deskforge.app.model.SessionState
import com.deskforge.app.model.RendererPreference
import com.deskforge.app.ui.DesktopSurfaceCallbacks
import com.deskforge.app.ui.DesktopSurface
import com.deskforge.app.ui.DeskForgeApp
import com.deskforge.app.ui.theme.DeskForgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val workspaceViewModel: WorkspaceViewModel by viewModels()
    private lateinit var diagnosticsEngine: NativeDeskForgeEngine
    private lateinit var rendererPreferenceStore: RendererPreferenceStore
    private var sessionState: SessionState = SessionState.Idle
        set(value) {
            field = value
            renderState.value = value
        }
    private val renderState = androidx.compose.runtime.mutableStateOf<SessionState>(SessionState.Idle)
    private val capabilityState = androidx.compose.runtime.mutableStateOf<RuntimeCapabilities?>(null)
    private val clipboardRenderState = androidx.compose.runtime.mutableStateOf<SessionClipboardState>(
        SessionClipboardState.Unavailable,
    )
    private val audioRenderState = androidx.compose.runtime.mutableStateOf(SessionAudioState())
    private val rootfsState = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val updateRequiredState = androidx.compose.runtime.mutableStateOf(false)
    private val rendererPreferenceState = androidx.compose.runtime.mutableStateOf(RendererPreference.AUTO)
    private var sessionService: DeskForgeSessionService? = null
    private var serviceBound = false
    private var stateCollection: Job? = null
    private var currentSurface: Surface? = null
    private var currentViewport: DesktopViewport? = null
    private var currentDesktopSurface: DesktopSurface? = null
    private var downloadConfirmationShown = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denial does not block the foreground session or its in-app stop control. */ }

    private val downloadConfirmation = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { downloadConfirmationShown = false }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) sessionService?.enableMicrophone()
        else sessionService?.reportMicrophonePermissionDenied()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? DeskForgeSessionService.LocalBinder ?: return
            sessionService = localBinder.service
            serviceBound = true
            stateCollection?.cancel()
            stateCollection = lifecycleScope.launch {
                launch {
                    localBinder.service.state.collectLatest { state ->
                        if (state == SessionState.Idle &&
                            (workspaceViewModel.state.value.progress != null ||
                                workspaceViewModel.state.value.failure != null)
                        ) {
                            applyWorkspaceState(workspaceViewModel.state.value)
                        } else {
                            sessionState = state
                        }
                        if (state is SessionState.Starting) attachCurrentSurface()
                    }
                }
                launch {
                    localBinder.service.clipboardState.collectLatest { state ->
                        clipboardRenderState.value = state
                        if (state is SessionClipboardState.Ready) receiveDesktopClipboard(state.generation)
                    }
                }
                launch {
                    localBinder.service.audioState.collectLatest { state ->
                        audioRenderState.value = state
                    }
                }
            }
            attachCurrentSurface()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stateCollection?.cancel()
            stateCollection = null
            sessionService = null
            serviceBound = false
            clipboardRenderState.value = SessionClipboardState.Unavailable
            audioRenderState.value = SessionAudioState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        diagnosticsEngine = NativeDeskForgeEngine(this)
        rendererPreferenceStore = RendererPreferenceStore(this)
        rendererPreferenceState.value = rendererPreferenceStore.get()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                workspaceViewModel.state.collectLatest(::applyWorkspaceState)
            }
        }

        setContent {
            DeskForgeTheme {
                DeskForgeApp(
                    sessionState = renderState.value,
                    capabilities = capabilityState.value,
                    clipboardState = clipboardRenderState.value,
                    audioState = audioRenderState.value,
                    isInstalled = rootfsState.value != null,
                    requiresUpdate = updateRequiredState.value,
                    rendererPreference = rendererPreferenceState.value,
                    rendererPreferenceEnabled = sessionState is SessionState.Idle ||
                        sessionState is SessionState.Failed,
                    desktopCallbacks = DesktopSurfaceCallbacks(
                        onSurfaceReady = ::onSurfaceReady,
                        onSurfaceViewReady = { currentDesktopSurface = it },
                        onSurfaceResized = ::onSurfaceResized,
                        onSurfaceDestroyed = ::onSurfaceDestroyed,
                        onPointer = ::onPointer,
                        onKey = ::onKey,
                        onText = ::onText,
                    ),
                    onInstall = ::installFedora,
                    onCapabilityCheck = ::inspectCapabilities,
                    onStart = ::startSession,
                    onStop = ::stopSession,
                    onShowKeyboard = { currentDesktopSurface?.showSoftwareKeyboard() },
                    onPasteToDesktop = ::pasteToDesktop,
                    onCopyFromDesktop = { sessionService?.requestDesktopClipboard() },
                    onMicrophoneToggle = ::onMicrophoneToggle,
                    onRendererPreferenceChange = ::setRendererPreference,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DeskForgeSessionService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        sessionService?.detachSurface()
        if (serviceBound) unbindService(connection)
        serviceBound = false
        sessionService = null
        clipboardRenderState.value = SessionClipboardState.Unavailable
        audioRenderState.value = SessionAudioState()
        stateCollection?.cancel()
        stateCollection = null
        super.onStop()
    }

    private fun installFedora() {
        workspaceViewModel.install()
    }

    private fun startSession() {
        val rootfs = rootfsState.value
        if (rootfs == null) {
            sessionState = SessionState.Failed(SessionFailure.WORKSPACE_UNAVAILABLE, true)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        sessionState = SessionState.Starting
        val intent = Intent(this, DeskForgeSessionService::class.java)
            .setAction(DeskForgeSessionService.ACTION_PREPARE)
            .putExtra(DeskForgeSessionService.EXTRA_ROOTFS, rootfs)
            .putExtra(
                DeskForgeSessionService.EXTRA_RENDERER_PREFERENCE,
                rendererPreferenceState.value.name,
            )
        ContextCompat.startForegroundService(this, intent)
        attachCurrentSurface()
    }

    private fun setRendererPreference(preference: RendererPreference) {
        if (sessionState !is SessionState.Idle && sessionState !is SessionState.Failed) return
        rendererPreferenceStore.set(preference)
        rendererPreferenceState.value = preference
        capabilityState.value = null
    }

    private fun stopSession() {
        sessionState = SessionState.Stopping
        sessionService?.stopDesktop() ?: startService(
            Intent(this, DeskForgeSessionService::class.java).setAction(DeskForgeSessionService.ACTION_STOP),
        )
    }

    private fun inspectCapabilities() {
        lifecycleScope.launch {
            capabilityState.value = withContext(Dispatchers.IO) { diagnosticsEngine.inspectCapabilities() }
        }
    }

    private fun onSurfaceReady(surface: Surface, viewport: DesktopViewport) {
        currentSurface = surface
        currentViewport = viewport
        attachCurrentSurface()
    }

    private fun onSurfaceResized(viewport: DesktopViewport) {
        currentViewport = viewport
        sessionService?.resizeDisplay(viewport)
    }

    private fun onSurfaceDestroyed() {
        sessionService?.detachSurface()
        currentDesktopSurface?.closeSoftwareKeyboard()
        currentDesktopSurface = null
        currentSurface = null
        currentViewport = null
    }

    private fun attachCurrentSurface() {
        val surface = currentSurface ?: return
        val viewport = currentViewport ?: return
        sessionService?.attachSurface(surface, viewport)
    }

    private fun onPointer(x: Int, y: Int, buttons: Int) {
        sessionService?.sendPointer(x, y, buttons)
    }

    private fun onKey(keysym: Int, pressed: Boolean) {
        sessionService?.sendKey(keysym, pressed)
    }

    private fun onText(text: String) {
        sessionService?.sendText(text)
    }

    private fun pasteToDesktop() {
        val service = sessionService ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        when (val result = AndroidClipboardBoundary.readPlainText(clipboard)) {
            is AndroidClipboardBoundary.ReadResult.Failed -> service.reportClipboardFailure(result.reason)
            is AndroidClipboardBoundary.ReadResult.Text -> service.pasteClipboardText(result.value)
        }
    }

    private fun receiveDesktopClipboard(generation: Long) {
        val service = sessionService ?: return
        val text = service.receivedClipboard(generation) ?: return
        val failure = runCatching {
            val clip = AndroidClipboardBoundary.sensitivePlainText(
                getString(R.string.clipboard_label),
                text,
            )
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }.exceptionOrNull()?.let { ClipboardFailure.ANDROID_CLIPBOARD_FAILED }
        service.acknowledgeClipboard(generation, failure)
    }

    private fun onMicrophoneToggle(enabled: Boolean) {
        val service = sessionService ?: return
        if (!enabled) {
            service.disableMicrophone()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            service.enableMicrophone()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun applyWorkspaceState(state: WorkspaceState) {
        rootfsState.value = state.rootfsPath
        updateRequiredState.value = state.updateRequired
        if (state.failure == SessionFailure.WAITING_FOR_WIFI && !downloadConfirmationShown) {
            downloadConfirmationShown = workspaceViewModel.showDownloadConfirmation(downloadConfirmation)
        } else if (state.failure != SessionFailure.WAITING_FOR_WIFI) {
            downloadConfirmationShown = false
        }
        if (sessionState is SessionState.Starting || sessionState is SessionState.Running ||
            sessionState is SessionState.Stopping
        ) {
            return
        }
        sessionState = when {
            state.progress != null -> SessionState.Preparing(state.progress)
            state.failure != null -> SessionState.Failed(state.failure, recoverable = true)
            sessionState is SessionState.Preparing -> SessionState.Idle
            sessionState is SessionState.Failed &&
                (sessionState as SessionState.Failed).reason in INSTALL_FAILURES -> SessionState.Idle
            else -> sessionState
        }
    }

    private companion object {
        private val INSTALL_FAILURES = setOf(SessionFailure.WAITING_FOR_WIFI, SessionFailure.INSTALL_FAILED)
    }
}
