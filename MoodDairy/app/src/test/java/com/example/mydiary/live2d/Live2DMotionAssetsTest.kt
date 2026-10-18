package com.example.mydiary.live2d

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Live2DMotionAssetsTest {
    @Test fun bundledMotionCountsMatchEncodedCurves() {
        val files = File("src/main/assets/live2d").walkTopDown()
            .filter { it.name.endsWith(".motion3.json") }.toList()
        assertTrue("Motion assets must be included in validation", files.isNotEmpty())
        for (file in files) {
            val json = JsonParser.parseString(file.readText()).asJsonObject
            val curves = json.getAsJsonArray("Curves")
            var segments = 0
            var points = 0
            for (curve in curves) {
                val values = curve.asJsonObject.getAsJsonArray("Segments")
                assertTrue(file.path, values.size() >= 2)
                points++
                var index = 2
                while (index < values.size()) {
                    val type = values[index].asInt
                    assertTrue(file.path, type in 0..3)
                    index += if (type == 1) 7 else 3
                    points += if (type == 1) 3 else 1
                    segments++
                }
                assertEquals(file.path, values.size(), index)
            }
            val meta = json.getAsJsonObject("Meta")
            assertEquals(file.path, curves.size(), meta["CurveCount"].asInt)
            assertEquals(file.path, segments, meta["TotalSegmentCount"].asInt)
            assertEquals(file.path, points, meta["TotalPointCount"].asInt)
        }
    }
}
