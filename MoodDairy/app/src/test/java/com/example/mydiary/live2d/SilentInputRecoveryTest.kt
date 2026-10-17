package com.example.mydiary.live2d

import org.junit.Assert.*
import org.junit.Test

class SilentInputRecoveryTest {
    private val zero = ShortArray(320)
    private val noise = ShortArray(320) { if (it % 2 == 0) 1 else -1 }

    @Test fun tenSecondsOfExactSilenceReopensOnlyTwice() {
        val recovery = SilentInputRecovery(true)
        repeat(2) {
            repeat(499) { assertFalse(recovery.sample(zero, 320, true)) }
            assertTrue(recovery.sample(zero, 320, true))
            recovery.resetWindow()
        }
        repeat(1000) { assertFalse(recovery.sample(zero, 320, true)) }
        assertEquals(2, recovery.attempts)
    }

    @Test fun quietNonzeroInputDoesNotTriggerRecovery() {
        val recovery = SilentInputRecovery(true)
        repeat(1000) { assertFalse(recovery.sample(noise, 320, true)) }
    }

    @Test fun realDevicesAndActiveSpeechAreNotRestarted() {
        val real = SilentInputRecovery(false)
        val emulator = SilentInputRecovery(true)
        repeat(1000) {
            assertFalse(real.sample(zero, 320, true))
            assertFalse(emulator.sample(zero, 320, false))
        }
    }

    @Test fun sustainedSignalRestoresBudgetButSingleSpikeDoesNot() {
        val recovery = SilentInputRecovery(true)
        repeat(500) { recovery.sample(zero, 320, true) }
        assertEquals(1, recovery.attempts)
        recovery.sample(shortArrayOf(1), 1, true)
        assertEquals(1, recovery.attempts)
        repeat(50) { recovery.sample(noise, 320, true) }
        assertEquals(0, recovery.attempts)
    }

    @Test fun leavingStandbyResetsSilenceWindow() {
        val recovery = SilentInputRecovery(true)
        repeat(499) { recovery.sample(zero, 320, true) }
        recovery.sample(zero, 320, false)
        assertFalse(recovery.sample(zero, 320, true))
        recovery.resetWindow()
        assertFalse(recovery.sample(zero, 320, true))
    }
}
