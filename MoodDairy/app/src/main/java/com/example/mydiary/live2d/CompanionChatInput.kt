package com.example.mydiary.live2d

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CompanionUtterance(val userId: Long, val text: String)

internal class CompanionInputQueue {
    private val queue = Channel<CompanionUtterance>(32)
    val inputs = queue.receiveAsFlow()

    fun submit(userId: Long, text: String): Boolean {
        val clean = text.trim()
        if (userId <= 0 || clean.isBlank()) return false
        return queue.trySend(CompanionUtterance(userId, clean)).isSuccess
    }
}

internal object CompanionChatInput {
    private val queue = CompanionInputQueue()
    val inputs = queue.inputs
    fun submit(userId: Long, text: String) = queue.submit(userId, text)
}

internal class CompanionInputDelivery(
    private val initializedUser: StateFlow<Long?>,
    private val busy: StateFlow<Boolean>,
    private val currentUser: () -> Long,
    private val send: (String) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun deliver(input: CompanionUtterance) = mutex.withLock {
        if (input.userId != currentUser()) return@withLock
        val state = combine(initializedUser, busy) { user, loading -> user to loading }
            .first { (user, loading) -> user != null && (user != input.userId || !loading) }
        if (state.first == input.userId && currentUser() == input.userId) send(input.text)
    }
}
