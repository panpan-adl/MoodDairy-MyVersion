package com.example.mydiary.ml

import org.junit.Assert.*
import org.junit.Test

class PositiveExpressionClassifierTest {
    private val scores = floatArrayOf(.02f, .02f, .02f, .75f, .10f, .04f, .05f)
    @Test fun smileIntensityCreatesObservableSubtypes() {
        assertEquals("smile", PositiveExpressionClassifier.classify("happy" to .75f, scores, .7f, 0f, 0f))
        assertEquals("broad_smile", PositiveExpressionClassifier.classify("happy" to .75f, scores, .98f, 0f, 0f))
        assertNull(PositiveExpressionClassifier.classify("happy" to .75f, scores, .3f, 0f, 0f))
    }
    @Test fun surpriseNeedsSmileAndHappyEvidenceToBecomeDelighted() {
        val surprised = floatArrayOf(.04f, .03f, .04f, .20f, .10f, .04f, .55f)
        assertEquals("delighted_surprise", PositiveExpressionClassifier.classify("surprise" to .55f, surprised, .9f, 0f, 0f))
        assertNull(PositiveExpressionClassifier.classify("surprise" to .55f, surprised, .1f, 0f, 0f))
        val scared = floatArrayOf(.04f, .03f, .20f, .04f, .10f, .04f, .55f)
        assertNull(PositiveExpressionClassifier.classify("surprise" to .55f, scared, .99f, 0f, 0f))
    }
    @Test fun weakOrTurnedOrNegativeObservationsDoNotGetPositiveSubtype() {
        assertNull(PositiveExpressionClassifier.classify("happy" to .3f, scores, .99f, 0f, 0f))
        assertNull(PositiveExpressionClassifier.classify("happy" to .75f, scores, .99f, 25f, 0f))
        assertNull(PositiveExpressionClassifier.classify("sad" to .75f, scores, .99f, 0f, 0f))
    }
    @Test fun subtypeMappingKeepsSevenClassApiCompatibility() {
        assertEquals("happy", PositiveExpressionClassifier.baseEmotion("smile"))
        assertEquals("happy", PositiveExpressionClassifier.baseEmotion("broad_smile"))
        assertEquals("surprise", PositiveExpressionClassifier.baseEmotion("delighted_surprise"))
    }
}
