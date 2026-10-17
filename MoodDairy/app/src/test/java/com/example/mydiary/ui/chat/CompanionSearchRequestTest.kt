package com.example.mydiary.ui.chat

import com.example.mydiary.data.models.ChatRequest
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class CompanionSearchRequestTest {
    @Test fun companionSearchFlagUsesBackendWireName() {
        val request = ChatRequest(userId = 1L, message = "搜索课程", conversationId = "test", companionSearchDirect = true)
        val body = JsonParser.parseString(Gson().toJson(request)).asJsonObject
        assertTrue(body["companion_search_direct"].asBoolean)
        assertEquals("搜索课程", body["message"].asString)
        assertTrue(body["confirmation"] == null || body["confirmation"].isJsonNull)
    }
    @Test fun ordinaryChatDoesNotOptInToDirectSearch() {
        val request = ChatRequest(userId = 1L, message = "搜索课程", conversationId = "test")
        assertFalse(request.companionSearchDirect)
    }
}
