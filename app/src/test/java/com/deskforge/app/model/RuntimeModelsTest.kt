package com.deskforge.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeModelsTest {
    @Test
    fun `running state retains supervised pid and renderer decision`() {
        val renderer = RendererMode.Accelerated(
            GraphicsBackend.VIRGL,
            "Adreno",
            PresentationPath.RFB,
        )
        val state = SessionState.Running(processId = 42, rendererMode = renderer)

        assertEquals(42, state.processId)
        assertEquals(renderer, state.rendererMode)
    }

    @Test
    fun `software renderer requires a diagnostic reason`() {
        val renderer = RendererMode.Software(
            reason = GraphicsFallbackReason.SELF_TEST_FAILED,
            detail = "EGL self-test failed",
        )

        assertTrue(renderer.detail.isNotBlank())
        assertEquals(GraphicsBackend.LLVMPIPE, renderer.backend)
        assertEquals(GraphicsFallbackReason.SELF_TEST_FAILED, renderer.reason)
        assertEquals(PresentationPath.RFB, renderer.presentationPath)
    }

    @Test
    fun `desktop viewport retains bounded tablet geometry`() {
        val viewport = DesktopViewport(
            widthPx = 2560,
            heightPx = 1600,
            densityDpi = 320,
            refreshRateHz = 120f,
        )

        assertEquals(2560, viewport.widthPx)
        assertEquals(1600, viewport.heightPx)
        assertEquals(320, viewport.densityDpi)
        assertEquals(120f, viewport.refreshRateHz)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `desktop viewport rejects an oversized framebuffer`() {
        DesktopViewport(widthPx = 4097, heightPx = 4096, densityDpi = 320)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `desktop viewport rejects an unbounded refresh rate`() {
        DesktopViewport(widthPx = 2560, heightPx = 1600, densityDpi = 320, refreshRateHz = 241f)
    }

    @Test
    fun `audio session starts without microphone consent`() {
        val state = SessionAudioState()

        assertEquals(AudioPlaybackStatus.UNAVAILABLE, state.playbackStatus)
        assertEquals(AudioMicrophoneStatus.OFF, state.microphoneStatus)
        assertFalse(state.microphoneConsent)
    }
}
