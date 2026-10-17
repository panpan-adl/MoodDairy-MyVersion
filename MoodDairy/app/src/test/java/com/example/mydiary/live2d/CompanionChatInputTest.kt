package com.example.mydiary.live2d

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class CompanionChatInputTest {
    @Test fun waitsForConversationInitializationAndSerializesInputsDuringReplies() = runTest {
        val ready = MutableStateFlow<Long?>(null)
        val busy = MutableStateFlow(false)
        val sent = mutableListOf<String>()
        val delivery = CompanionInputDelivery(ready, busy, { 1 }) { sent.add(it); busy.value = true }
        launch { delivery.deliver(CompanionUtterance(1, "第一句")) }
        launch { delivery.deliver(CompanionUtterance(1, "第二句")) }
        runCurrent()
        assertTrue(sent.isEmpty())
        busy.value = true; ready.value = 1
        runCurrent()
        assertTrue(sent.isEmpty())
        busy.value = false; runCurrent()
        assertEquals(listOf("第一句"), sent)
        busy.value = false; runCurrent()
        assertEquals(listOf("第一句", "第二句"), sent)
    }

    @Test fun accountChangesCannotSendAnotherUsersPendingInput() = runTest {
        val ready = MutableStateFlow<Long?>(1)
        val busy = MutableStateFlow(true)
        var owner = 1L
        val sent = mutableListOf<String>()
        val delivery = CompanionInputDelivery(ready, busy, { owner }) { sent.add(it) }
        launch { delivery.deliver(CompanionUtterance(1, "旧账号内容")) }
        runCurrent()
        owner = 2; ready.value = 2; busy.value = false
        runCurrent()
        delivery.deliver(CompanionUtterance(1, "迟到的旧内容"))
        assertTrue(sent.isEmpty())
        delivery.deliver(CompanionUtterance(2, "新内容"))
        assertEquals(listOf("新内容"), sent)
    }

    @Test fun inputWaitsForChatAndIsDeliveredOnceInOrderWithOwner() = runTest {
        val queue = CompanionInputQueue()
        assertTrue(queue.submit(1, "  今天很开心  "))
        assertTrue(queue.submit(2, "第二条"))
        assertEquals(CompanionUtterance(1, "今天很开心"), queue.inputs.first())
        assertEquals(CompanionUtterance(2, "第二条"), queue.inputs.first())
        assertTrue(queue.submit(1, "第三条"))
        assertEquals("第三条", queue.inputs.first().text)
    }

    @Test fun blankAndUnauthenticatedInputsAreRejectedAndOverflowIsExplicit() = runTest {
        val queue = CompanionInputQueue()
        assertFalse(queue.submit(0, "hello"))
        assertFalse(queue.submit(1, "  "))
        repeat(32) { assertTrue(queue.submit(1, "message $it")) }
        assertFalse(queue.submit(1, "overflow"))
        assertEquals("message 0", queue.inputs.first().text)
        assertTrue(queue.submit(1, "space available"))
    }
}
