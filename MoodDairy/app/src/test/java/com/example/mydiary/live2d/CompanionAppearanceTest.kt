package com.example.mydiary.live2d

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CompanionAppearanceTest {
    @Test fun missingOrInvalidSelectionUsesWarmCompanion() {
        assertEquals("diana", CompanionAppearance.resolve(null).id)
        assertEquals("diana", CompanionAppearance.resolve("removed-model").id)
    }

    @Test fun bothStylesHaveMatchingExistingAssetsAndOneName() {
        assertEquals(listOf("暖心陪伴", "灵动乐伴"), Live2DConfig.availableModels.map { it.styleName })
        Live2DConfig.availableModels.forEach {
            assertEquals("然然", it.displayName)
            assertTrue(File("src/main/assets", it.path).isFile)
            assertTrue(File("src/main/assets", it.avatarPath).isFile)
            assertEquals(it.path.substringBeforeLast('/'), it.avatarPath.substringBeforeLast('/'))
        }
    }

    @Test fun restoresSavedStyleAndPersistsTheNextSelection() {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("companion", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString("appearance", null) } returns "ava"
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        CompanionAppearance.initialize(context)
        assertEquals("ava", CompanionAppearance.current.value.id)
        CompanionAppearance.select(context, "diana")
        assertEquals("diana", CompanionAppearance.current.value.id)
        verify { editor.putString("appearance", "diana") }
        verify { editor.apply() }
        CompanionAppearance.initialize(context)
        assertEquals("diana", CompanionAppearance.current.value.id)
        verify(exactly = 1) { preferences.getString("appearance", null) }
    }
}
