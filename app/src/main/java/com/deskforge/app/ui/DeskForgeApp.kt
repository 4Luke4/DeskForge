package com.deskforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.deskforge.app.BuildConfig
import com.deskforge.app.R
import com.deskforge.app.model.RendererMode
import com.deskforge.app.model.AudioFailure
import com.deskforge.app.model.AudioMicrophoneStatus
import com.deskforge.app.model.AudioPlaybackStatus
import com.deskforge.app.model.SessionAudioState
import com.deskforge.app.model.ClipboardFailure
import com.deskforge.app.model.SessionClipboardState
import com.deskforge.app.model.RuntimeCapabilities
import com.deskforge.app.model.SessionFailure
import com.deskforge.app.model.SessionState

private enum class Destination { WORKSPACES, DIAGNOSTICS, SETTINGS }

@Composable
fun DeskForgeApp(
    sessionState: SessionState,
    capabilities: RuntimeCapabilities?,
    clipboardState: SessionClipboardState,
    audioState: SessionAudioState,
    isInstalled: Boolean,
    requiresUpdate: Boolean,
    desktopCallbacks: DesktopSurfaceCallbacks,
    onInstall: () -> Unit,
    onCapabilityCheck: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShowKeyboard: () -> Unit,
    onPasteToDesktop: () -> Unit,
    onCopyFromDesktop: () -> Unit,
    onMicrophoneToggle: (Boolean) -> Unit,
) {
    if (sessionState is SessionState.Starting || sessionState is SessionState.Running || sessionState is SessionState.Stopping) {
        DesktopSessionScreen(
            sessionState,
            clipboardState,
            audioState,
            onStop,
            onShowKeyboard,
            onPasteToDesktop,
            onCopyFromDesktop,
            onMicrophoneToggle,
            desktopCallbacks,
        )
        return
    }

    var destination by rememberSaveable { mutableStateOf(Destination.WORKSPACES) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 840.dp
        Scaffold(
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = item == destination,
                                onClick = { destination = item },
                                icon = { Text(destinationGlyph(item)) },
                                label = { Text(destinationLabel(item)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (useRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        Spacer(Modifier.height(24.dp))
                        Text("DF", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(32.dp))
                        Destination.entries.forEach { item ->
                            NavigationRailItem(
                                selected = item == destination,
                                onClick = { destination = item },
                                icon = { Text(destinationGlyph(item)) },
                                label = { Text(destinationLabel(item)) },
                            )
                        }
                    }
                    VerticalDivider(modifier = Modifier.fillMaxHeight())
                }

                when (destination) {
                    Destination.WORKSPACES -> WorkspaceScreen(
                        sessionState = sessionState,
                        isInstalled = isInstalled,
                        requiresUpdate = requiresUpdate,
                        onInstall = onInstall,
                        onStart = onStart,
                    )
                    Destination.DIAGNOSTICS -> DiagnosticsScreen(capabilities, onCapabilityCheck)
                    Destination.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun WorkspaceScreen(
    sessionState: SessionState,
    isInstalled: Boolean,
    requiresUpdate: Boolean,
    onInstall: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(stringResource(R.string.brand_tagline), color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.welcome_title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeTile()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.fedora_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.fedora_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        statusLabel(sessionState, isInstalled, requiresUpdate),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (sessionState is SessionState.Preparing) {
                        LinearProgressIndicator(
                            progress = { sessionState.progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    if (sessionState is SessionState.Failed) {
                        Text(failureLabel(sessionState.reason), color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    onClick = if (isInstalled) onStart else onInstall,
                    enabled = sessionState !is SessionState.Preparing && sessionState !is SessionState.Starting,
                ) {
                    Text(
                        stringResource(
                            when {
                                isInstalled -> R.string.action_start
                                requiresUpdate -> R.string.action_update
                                else -> R.string.action_install
                            },
                        ),
                    )
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                stringResource(R.string.release_gate),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun DiagnosticsScreen(capabilities: RuntimeCapabilities?, onCapabilityCheck: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            "DeskForge ${BuildConfig.VERSION_NAME} · " +
                stringResource(R.string.diagnostics_version_code, BuildConfig.VERSION_CODE),
        )
        if (capabilities == null) {
            Text(stringResource(R.string.diagnostics_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            DiagnosticRow("PRoot", readinessLabel(capabilities.prootAvailable))
            DiagnosticRow("Vulkan", readinessLabel(capabilities.vulkanAvailable))
            DiagnosticRow("AAudio", readinessLabel(capabilities.audioAvailable))
            DiagnosticRow(
                "Renderer",
                when (val renderer = capabilities.rendererMode) {
                    is RendererMode.Accelerated -> renderer.backend
                    is RendererMode.Software -> renderer.reason
                },
            )
            Text(capabilities.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onCapabilityCheck) { Text(stringResource(R.string.action_check)) }
    }
}

@Composable
private fun SettingsScreen() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(stringResource(R.string.navigation_settings), style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.microphone_label), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.microphone_session_summary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopSessionScreen(
    state: SessionState,
    clipboardState: SessionClipboardState,
    audioState: SessionAudioState,
    onStop: () -> Unit,
    onShowKeyboard: () -> Unit,
    onPasteToDesktop: () -> Unit,
    onCopyFromDesktop: () -> Unit,
    onMicrophoneToggle: (Boolean) -> Unit,
    callbacks: DesktopSurfaceCallbacks,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("DeskForge", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(
                when (state) {
                    SessionState.Starting -> stringResource(R.string.status_starting)
                    SessionState.Stopping -> stringResource(R.string.status_stopping)
                    is SessionState.Running -> stringResource(R.string.session_pid, state.processId)
                    else -> ""
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (state is SessionState.Running) {
                Text(
                    when (state.rendererMode) {
                        is RendererMode.Accelerated -> stringResource(R.string.renderer_accelerated)
                        is RendererMode.Software -> stringResource(R.string.renderer_software)
                    },
                )
            }
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
        }
        if (state is SessionState.Running) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(onClick = onShowKeyboard) {
                    Text(stringResource(R.string.action_show_keyboard))
                }
                OutlinedButton(
                    onClick = onPasteToDesktop,
                    enabled = clipboardCanStartTransfer(clipboardState),
                ) {
                    Text(stringResource(R.string.action_paste_to_desktop))
                }
                OutlinedButton(
                    onClick = onCopyFromDesktop,
                    enabled = clipboardCanCopyFromDesktop(clipboardState),
                ) {
                    Text(stringResource(R.string.action_copy_from_desktop))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val microphoneLabel = stringResource(R.string.microphone_label)
                    Text(microphoneLabel)
                    Switch(
                        checked = audioState.microphoneConsent,
                        onCheckedChange = onMicrophoneToggle,
                        modifier = Modifier.semantics { contentDescription = microphoneLabel },
                    )
                }
                Text(
                    audioStatusLabel(audioState),
                    modifier = Modifier.align(Alignment.CenterVertically).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    color = if (audioState.failure != null ||
                        audioState.microphoneStatus == AudioMicrophoneStatus.FAILED ||
                        audioState.playbackStatus == AudioPlaybackStatus.FAILED
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    clipboardStatusLabel(clipboardState),
                    modifier = Modifier.align(Alignment.CenterVertically).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                    color = if (clipboardState is SessionClipboardState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        AndroidView(
            factory = { context -> DesktopSurface(context, callbacks) },
            update = { surface -> surface.callbacks = callbacks },
            modifier = Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(12.dp)),
        )
    }
}

private fun clipboardCanStartTransfer(state: SessionClipboardState): Boolean =
    state is SessionClipboardState.Idle || state is SessionClipboardState.Failed

private fun clipboardCanCopyFromDesktop(state: SessionClipboardState): Boolean = when (state) {
    is SessionClipboardState.Idle -> state.remoteTextAvailable
    is SessionClipboardState.Failed -> state.remoteTextAvailable
    else -> false
}

@Composable
private fun audioStatusLabel(state: SessionAudioState): String {
    val failure = state.failure
    if (failure != null) {
        return stringResource(
            when (failure) {
                AudioFailure.TRANSPORT_UNAVAILABLE -> R.string.audio_error_transport
                AudioFailure.PLAYBACK_OPEN_FAILED,
                AudioFailure.PLAYBACK_DISCONNECTED -> R.string.audio_error_playback
                AudioFailure.AUDIO_FOCUS_DENIED -> R.string.audio_error_focus
                AudioFailure.MICROPHONE_PERMISSION_DENIED -> R.string.microphone_error_permission
                AudioFailure.MICROPHONE_PERMISSION_REVOKED -> R.string.microphone_error_revoked
                AudioFailure.MICROPHONE_OPEN_FAILED,
                AudioFailure.MICROPHONE_DISCONNECTED -> R.string.microphone_error_start
            },
        )
    }
    if (state.microphoneStatus == AudioMicrophoneStatus.ACTIVE) {
        return stringResource(R.string.microphone_active)
    }
    return stringResource(
        when (state.playbackStatus) {
            AudioPlaybackStatus.PLAYING -> R.string.audio_playing
            AudioPlaybackStatus.WAITING_FOR_FOCUS -> R.string.audio_waiting_focus
            AudioPlaybackStatus.FAILED -> R.string.audio_error_playback
            AudioPlaybackStatus.UNAVAILABLE -> R.string.audio_unavailable
            AudioPlaybackStatus.IDLE -> R.string.audio_ready
        },
    )
}

@Composable
private fun clipboardStatusLabel(state: SessionClipboardState): String = stringResource(
    when (state) {
        SessionClipboardState.Unavailable -> R.string.clipboard_unavailable
        is SessionClipboardState.Idle -> if (state.remoteTextAvailable) {
            R.string.clipboard_remote_available
        } else {
            R.string.clipboard_ready
        }
        SessionClipboardState.Sending -> R.string.clipboard_sending
        SessionClipboardState.Receiving -> R.string.clipboard_receiving
        is SessionClipboardState.Ready -> R.string.clipboard_receiving
        is SessionClipboardState.Failed -> clipboardFailureResource(state.reason)
    },
)

private fun clipboardFailureResource(reason: ClipboardFailure): Int = when (reason) {
    ClipboardFailure.NO_PLAIN_TEXT -> R.string.clipboard_error_plain_text
    ClipboardFailure.TEXT_TOO_LARGE -> R.string.clipboard_error_too_large
    ClipboardFailure.INVALID_TEXT -> R.string.clipboard_error_invalid_text
    ClipboardFailure.TRANSFER_TIMEOUT -> R.string.clipboard_error_timeout
    ClipboardFailure.TRANSFER_FAILED -> R.string.clipboard_error_transfer
    ClipboardFailure.ANDROID_CLIPBOARD_FAILED -> R.string.clipboard_error_android
}

@Composable
private fun ForgeTile() {
    val description = stringResource(R.string.app_name)
    Box(
        modifier = Modifier.size(84.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.background).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("DF", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun destinationLabel(destination: Destination): String = stringResource(
    when (destination) {
        Destination.WORKSPACES -> R.string.navigation_workspaces
        Destination.DIAGNOSTICS -> R.string.navigation_diagnostics
        Destination.SETTINGS -> R.string.navigation_settings
    },
)

private fun destinationGlyph(destination: Destination): String = when (destination) {
    Destination.WORKSPACES -> "W"
    Destination.DIAGNOSTICS -> "D"
    Destination.SETTINGS -> "S"
}

@Composable
private fun statusLabel(state: SessionState, installed: Boolean, requiresUpdate: Boolean): String = when (state) {
    is SessionState.Running -> stringResource(R.string.status_running)
    is SessionState.Preparing -> stringResource(R.string.action_install)
    SessionState.Starting -> stringResource(R.string.action_start)
    SessionState.Stopping -> stringResource(R.string.action_stop)
    is SessionState.Failed -> failureLabel(state.reason)
    SessionState.Idle -> stringResource(
        when {
            installed -> R.string.status_installed
            requiresUpdate -> R.string.status_update_required
            else -> R.string.status_ready
        },
    )
}

@Composable
private fun readinessLabel(available: Boolean): String =
    stringResource(if (available) R.string.status_available else R.string.status_unavailable)

@Composable
private fun failureLabel(reason: SessionFailure): String = stringResource(
    when (reason) {
        SessionFailure.WORKSPACE_UNAVAILABLE -> R.string.error_workspace_unavailable
        SessionFailure.WAITING_FOR_WIFI -> R.string.error_waiting_for_wifi
        SessionFailure.INSTALL_FAILED -> R.string.error_install_failed
        SessionFailure.RUNTIME_UNAVAILABLE -> R.string.runtime_unavailable
        SessionFailure.SESSION_ALREADY_RUNNING -> R.string.error_session_already_running
        SessionFailure.SESSION_START_FAILED -> R.string.error_session_start_failed
        SessionFailure.SESSION_STOP_FAILED -> R.string.error_session_stop_failed
        SessionFailure.DISPLAY_DISCONNECTED -> R.string.error_display_disconnected
    },
)
