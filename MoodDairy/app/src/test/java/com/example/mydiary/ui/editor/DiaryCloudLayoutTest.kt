package com.example.mydiary.ui.editor

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.Gson
import java.io.File
import kotlin.math.*

class DiaryCloudLayoutTest {
    @Test fun rotatedWordsFitWithoutTouchingAtNarrowWidthsAndLargeFontScales() {
        for (width in listOf(224f, 288f, 352f, 520f)) {
            for (scale in listOf(.85f, 1f, 1.5f, 2f, 3f)) {
                for (count in listOf(1, 3, 12)) {
                    val sizes = (0 until count).map { index ->
                        val font = diaryWordFontSize(1f - index / 15f, 16f, 36f) * scale
                        val textWidth = font * (if (index % 4 == 0) 8 else 2)
                        val w = min(width * .78f, textWidth + 38f)
                        val lines = ceil(textWidth / max(1f, w - 38f))
                        val h = lines * font * 1.22f + 30f
                        CloudWordSize(w, h, diaryFittingAngle(index, w, h, width, 8f))
                    }
                    val geometry = arrangeDiaryCloud(width, sizes, 8f)
                    assertEquals(count, geometry.words.size)
                    geometry.words.forEachIndexed { index, word ->
                        assertTrue(word.left >= 7.9f && word.right <= width - 7.9f)
                        assertTrue(word.top >= 7.9f && word.bottom <= geometry.height - 7.9f)
                        geometry.words.take(index).forEach { assertFalse(word.overlaps(it, 7.9f)) }
                    }
                    assertEquals(geometry, arrangeDiaryCloud(width, sizes, 8f))
                }
            }
        }
    }

    @Test fun moreTextCanGrowCanvasRatherThanClipping() {
        val short = arrangeDiaryCloud(280f, List(12) { CloudWordSize(76f, 45f, 0f) }, 8f)
        val tall = arrangeDiaryCloud(280f, List(12) { CloudWordSize(210f, 180f, 0f) }, 8f)
        assertTrue(tall.height > short.height)
        assertTrue(arrangeDiaryCloud(280f, emptyList(), 8f).words.isEmpty())
    }

    @Test fun weightsAndFailedUpdatesPreserveReadableState() {
        assertEquals(16f, diaryWordFontSize(Float.NaN, 16f, 36f), .001f)
        assertEquals(36f, diaryWordFontSize(8f, 16f, 36f), .001f)
        assertTrue(diaryWordFontSize(.9f, 16f, 36f) > diaryWordFontSize(.3f, 16f, 36f))
        assertFalse(shouldOpenDiaryReview(true, false, true, "更新失败"))
        assertFalse(shouldOpenDiaryReview(true, true, true, null))
        assertFalse(shouldOpenDiaryReview(false, false, true, null))
        assertTrue(shouldOpenDiaryReview(true, false, true, null))
    }

    @Test fun wordsFlyFromEnvelopeMouthAlongCurvesAndSettleAtTheirOwnTargets() {
        val originY = 400f + diaryEnvelopeMouthOffset(360f)
        assertEquals(500.8f, originY, .01f)
        val start = diaryFlightPosition(172f, originY, 80f, 120f, 400f, -48f, 0f)
        assertEquals(172f to originY, start)
        assertEquals(80f to 120f, diaryFlightPosition(172f, originY, 80f, 120f, 400f, -48f, 1f))
        val middle = diaryFlightPosition(172f, originY, 80f, 120f, 400f, -48f, .5f)
        assertTrue(middle.first < (172f + 80f) / 2)
        assertTrue(middle.second in 120f..originY)
    }

    private data class PreviewWord(val text: String, val weight: Float, val category: String,
        val width: Float, val height: Float, val font: Float)
    private data class PreviewInput(val width: Float, val words: List<PreviewWord>)

    @Test fun exportMeasuredCompositionForStaticPreview() {
        val fixture = File("../docs/qa/diary-envelope-measurements.json")
        if (!fixture.isFile) return
        val gson = Gson()
        val input = gson.fromJson(fixture.readText(), PreviewInput::class.java)
        val sizes = input.words.mapIndexed { index, word ->
            CloudWordSize(word.width, word.height, diaryFittingAngle(index, word.width, word.height, input.width, 8f))
        }
        val geometry = arrangeDiaryCloud(input.width, sizes, 8f)
        // Baseline before compacting was 1016 dp including the envelope and controls.
        assertTrue("Normal 12-word composition should be about half its old height",
            geometry.height + 80f + 144f + 76f <= 520f)
        val output = mapOf("width" to input.width, "height" to geometry.height,
            "words" to input.words.mapIndexed { index, word ->
                mapOf("text" to word.text, "weight" to word.weight, "category" to word.category,
                    "width" to word.width, "height" to word.height, "font" to word.font,
                    "x" to geometry.words[index].centerX, "y" to geometry.words[index].centerY,
                    "angle" to sizes[index].angle)
            })
        File("build/outputs/diary-envelope/geometry.json").apply { parentFile.mkdirs(); writeText(gson.toJson(output)) }
    }
}
