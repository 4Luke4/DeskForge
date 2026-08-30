package com.deskforge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.weight
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
import androidx.compose.material3.WindowInsets
import androidx.compose.material3.safeDrawing
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.deskforge.app.BuildConfig
import com.deskforge.app.R
import com.deskforge.app.model.RendererMode
import com.deskforge.app.model.RuntimeCapabilities
import com.deskforge.app.model.SessionState

private enum class Destination { WORKSPACES, DIAGNOSTICS, SETTINGS }

@Composable
fun DeskForgeApp(
    sessionState: SessionState,
    capabilities: RuntimeCapabilities?,
    isInstalled: Boolean,
    microphoneEnabled: Boolean,
    onInstall: () -> Unit,
    onCapabilityCheck: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMicrophoneChanged: (Boolean) -> Unit,
) {
    if (sessionState is SessionState.Running) {
        DesktopSessionScreen(sessionState, onStop)
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
                        onInstall = onInstall,
                        onStart = onStart,
                    )
                    Destination.DIAGNOSTICS -> DiagnosticsScreen(capabilities, onCapabilityCheck)
                    Destination.SETTINGS -> SettingsScreen(microphoneEnabled, onMicrophoneChanged)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceScreen(
    sessionState: SessionState,
    isInstalled: Boolean,
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
                    Text(statusLabel(sessionState, isInstalled), color = MaterialTheme.colorScheme.primary)
                    if (sessionState is SessionState.Preparing) {
                        LinearProgressIndicator(
                            progress = { sessionState.progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    if (sessionState is SessionState.Failed) {
                        Text(sessionState.message, color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(
                    onClick = if (isInstalled) onStart else onInstall,
                    enabled = sessionState !is SessionState.Preparing && sessionState !is SessionState.Starting,
                ) {
                    Text(stringResource(if (isInstalled) R.string.action_start else R.string.action_install))
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
private fun SettingsScreen(microphoneEnabled: Boolean, onMicrophoneChanged: (Boolean) -> Unit) {
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
                        stringResource(R.string.microphone_summary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = microphoneEnabled, onCheckedChange = onMicrophoneChanged)
            }
        }
    }
}

@Composable
private fun DesktopSessionScreen(state: SessionState.Running, onStop: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("DeskForge", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("PID ${state.processId}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                when (state.rendererMode) {
                    is RendererMode.Accelerated -> stringResource(R.string.renderer_accelerated)
                    is RendererMode.Software -> stringResource(R.string.renderer_software)
                },
            )
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
        }
        AndroidView(
            factory = { context -> DesktopSurface(context) },
            modifier = Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(12.dp)),
        )
    }
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
private fun statusLabel(state: SessionState, installed: Boolean): String = when (state) {
    is SessionState.Running -> stringResource(R.string.status_running)
    is SessionState.Preparing -> stringResource(R.string.action_install)
    SessionState.Starting -> stringResource(R.string.action_start)
    SessionState.Stopping -> stringResource(R.string.action_stop)
    is SessionState.Failed -> state.message
    SessionState.Idle -> stringResource(if (installed) R.string.status_installed else R.string.status_ready)
}

@Composable
private fun readinessLabel(available: Boolean): String =
    stringResource(if (available) R.string.status_available else R.string.status_unavailable)
