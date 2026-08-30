package com.deskforge.app.model

import androidx.annotation.StringRes

/** Identifies how an immutable distro payload reaches the installer. */
enum class InstallSource {
    PLAY_ASSET_DELIVERY,
    LOCAL_DOCUMENT,
}

/** Immutable metadata used by the UI and installer without embedding transport logic. */
data class DistroDescriptor(
    val id: String,
    @StringRes val titleResource: Int,
    @StringRes val subtitleResource: Int,
    val version: String,
    val architecture: String,
    val source: InstallSource,
)

/** The renderer selected after a runtime capability check. */
sealed interface RendererMode {
    data class Accelerated(val backend: String) : RendererMode
    data class Software(val reason: String) : RendererMode
}

/** A complete, user-displayable capability snapshot. */
data class RuntimeCapabilities(
    val prootAvailable: Boolean,
    val vulkanAvailable: Boolean,
    val audioAvailable: Boolean,
    val rendererMode: RendererMode,
    val detail: String,
)

/** Single authoritative lifecycle for a managed Linux session. */
sealed interface SessionState {
    data object Idle : SessionState
    data class Preparing(val progress: Float) : SessionState
    data object Starting : SessionState
    data class Running(val processId: Int, val rendererMode: RendererMode) : SessionState
    data object Stopping : SessionState
    data class Failed(val message: String, val recoverable: Boolean) : SessionState
}
