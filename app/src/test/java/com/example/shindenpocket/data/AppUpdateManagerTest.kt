package com.example.shindenpocket.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun `detects newer semantic version`() {
        assertTrue(AppUpdateManager.isNewerVersion("0.2.2", "0.2.1"))
        assertTrue(AppUpdateManager.isNewerVersion("v0.10.0", "0.9.9"))
        assertTrue(AppUpdateManager.isNewerVersion("1.0.1", "1.0"))
    }

    @Test
    fun `rejects equal older and malformed versions`() {
        assertFalse(AppUpdateManager.isNewerVersion("0.2.1", "0.2.1"))
        assertFalse(AppUpdateManager.isNewerVersion("0.2.0", "0.2.1"))
        assertFalse(AppUpdateManager.isNewerVersion("latest", "0.2.1"))
    }
}
