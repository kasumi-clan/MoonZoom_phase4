package com.godly.moonzoom

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomLimitsTest {
    @Test fun clampsToExactly100() {
        assertEquals(100f, SmoothZoomController.clamp(100f), 0f)
        assertEquals(100f, SmoothZoomController.clamp(101f), 0f)
        assertEquals(100f, SmoothZoomController.clamp(1e9f), 0f)
    }

    @Test fun clampsLowerBound() {
        assertEquals(1f, SmoothZoomController.clamp(0.2f), 0f)
        assertEquals(1f, SmoothZoomController.clamp(-5f), 0f)
    }

    @Test fun nanFallsBackToMin() {
        assertEquals(1f, SmoothZoomController.clamp(Float.NaN), 0f)
    }

    @Test fun formatsLimit() {
        assertEquals("100x", formatZoom(100f))
        assertEquals("2.5x", formatZoom(2.5f))
        assertEquals("30x", formatZoom(30f))
        assertEquals("1x", formatZoom(1f))
    }
}
