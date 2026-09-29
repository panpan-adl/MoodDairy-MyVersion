package com.example.mydiary.data.models

import com.google.gson.Gson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestModelsParsingTest {

    private val gson = Gson()

    @Test
    fun `score test supports array result_interpretation`() {
        val json = """
            {
              "test_id": "phq9_v1",
              "test_name": "PHQ-9",
              "test_description": "desc",
              "version": "1.0",
              "category": "mental_health",
              "icon": "mood",
              "total_questions": 1,
              "estimated_time_minutes": 1,
              "result_type": "score",
              "questions": [
                {
                  "question_id": 1,
                  "question_text": "q1"
                }
              ],
              "result_interpretation": [
                {
                  "score_range": [0, 4],
                  "level": "low",
                  "level_name": "Low",
                  "color": "#00AA00",
                  "description": "d",
                  "suggestion": "s"
                }
              ]
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, TestData::class.java)
        assertTrue(parsed.resultInterpretation?.isJsonArray == true)
    }

    @Test
    fun `holland test supports object result_interpretation`() {
        val json = """
            {
              "test_id": "holland_simplified_v1",
              "test_name": "Holland",
              "test_description": "desc",
              "version": "1.0",
              "category": "career",
              "icon": "work",
              "total_questions": 1,
              "estimated_time_minutes": 1,
              "result_type": "holland_code",
              "questions": [
                {
                  "question_id": 1,
                  "question_text": "q1",
                  "dimension": "R",
                  "options": [
                    { "option_id": "Y", "option_text": "yes", "score": 1 },
                    { "option_id": "N", "option_text": "no", "score": 0 }
                  ]
                }
              ],
              "result_interpretation": {
                "description": "object shape used by holland"
              }
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, TestData::class.java)
        assertTrue(parsed.resultInterpretation?.isJsonObject == true)
    }

    @Test
    fun `daily mood json has multi-select config and mood result type`() {
        val jsonFile = File("src/main/assets/tests/daily_mood/test.json")
        assertTrue(jsonFile.exists())

        val parsed = gson.fromJson(jsonFile.readText(), TestData::class.java)
        assertEquals("mood_score", parsed.resultType)

        val question5 = parsed.questions.firstOrNull { it.questionId == 5 }
        assertNotNull(question5)
        assertTrue(question5!!.allowMultiple)
        assertEquals(3, question5.maxSelect)
    }
}
