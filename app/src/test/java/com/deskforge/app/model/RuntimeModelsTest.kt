package com.deskforge.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeModelsTest {
    @Test
    fun `running state retains supervised pid and renderer decision`() {
        val renderer = RendererMode.Accelerated("Vulkan")
        val state = SessionState.Running(processId = 42, rendererMode = renderer)

        assertEquals(42, state.processId)
        assertEquals(renderer, state.rendererMode)
    }

    @Test
    fun `software renderer requires a diagnostic reason`() {
        val renderer = RendererMode.Software("Vulkan self-test failed")

        assertTrue(renderer.reason.isNotBlank())
    }
}
