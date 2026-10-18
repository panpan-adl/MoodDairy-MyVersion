package com.example.mydiary.live2d

import org.junit.Assert.*
import org.junit.Test

class ModelLoadGateTest {
    @Test fun latestSelectionWinsIncludingReturnToOriginalAppearance() {
        val gate = ModelLoadGate()
        val first = gate.next()!!
        val second = gate.next()!!
        val third = gate.next()!!
        assertFalse(gate.accepts(first))
        assertFalse(gate.accepts(second))
        assertTrue(gate.accepts(third))
    }

    @Test fun disposalRejectsPendingLoadsAndCallbacksAndCannotReleaseTwice() {
        val gate = ModelLoadGate()
        val pending = gate.next()!!
        assertTrue(gate.release())
        assertFalse(gate.accepts(pending))
        assertNull(gate.next())
        assertFalse(gate.release())
    }
}
