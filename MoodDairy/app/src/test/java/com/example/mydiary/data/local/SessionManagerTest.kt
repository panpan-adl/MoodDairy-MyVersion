package com.example.mydiary.data.local

import android.content.Context
import android.content.SharedPreferences
import com.example.mydiary.data.models.User
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class SessionManagerTest {
    private val context = mockk<Context>()
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private lateinit var session: SessionManager

    @Before fun setup() {
        mockkObject(SecurePreferences)
        every { SecurePreferences.create(context, any()) } returns prefs
        every { prefs.getBoolean(SessionManager.KEY_IS_LOGGED_IN, false) } returns true
        every { prefs.getString(SessionManager.KEY_ACCESS_TOKEN, null) } returns "original-token"
        every { prefs.getLong(SessionManager.KEY_USER_ID, -1) } returns 1L
        every { prefs.edit() } returns editor
        every { editor.commit() } returns true
        session = SessionManager(context)
    }

    @After fun teardown() { unmockkObject(SecurePreferences) }

    @Test fun lateProfileResponseFromPreviousAccountCannotReplaceCurrentAccount() {
        session.updateSession(User(id = 58, username = "qa"))
        verify(exactly = 0) { prefs.edit() }
    }

    @Test fun currentProfileRefreshKeepsTokenAndCommitsBeforeReturning() {
        session.updateSession(User(id = 1, username = "original", nickname = "updated"))
        verify { editor.putString(SessionManager.KEY_ACCESS_TOKEN, "original-token") }
        verify { editor.putLong(SessionManager.KEY_USER_ID, 1) }
        verify { editor.putString(SessionManager.KEY_NICKNAME, "updated") }
        verify { editor.commit() }
        verify(exactly = 0) { editor.apply() }
    }

    @Test fun lateProfileResponseAfterLogoutCannotRestoreSession() {
        every { prefs.getBoolean(SessionManager.KEY_IS_LOGGED_IN, false) } returns false
        session.updateSession(User(id = 1, username = "original"))
        verify(exactly = 0) { prefs.edit() }
    }
}
