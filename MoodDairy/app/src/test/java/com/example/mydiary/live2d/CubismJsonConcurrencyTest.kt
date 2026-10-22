package com.example.mydiary.live2d

import com.live2d.sdk.cubism.framework.motion.CubismMotionJson
import com.live2d.sdk.cubism.framework.utils.jsonparser.CubismJson
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CubismJsonConcurrencyTest {
    @Test fun parallelModelParsersKeepTheirOwnStringsAndNumbers() {
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..6).map { worker -> pool.submit(Callable {
                start.await()
                repeat(600) { index ->
                    val number = worker * 10000 + index
                    val name = "model_${worker}_$index"
                    val bytes = "{\"name\":\"$name\",\"values\":[$number,1,0,2,3],\"id\":\"$name\"}".toByteArray()
                    val root = CubismJson.create(bytes).root
                    assertEquals(name, root.get("name").string)
                    assertEquals(number, root.get("values").get(0).toInt())
                    assertEquals(1, root.get("values").get(1).toInt())
                    assertEquals(name, root.get("id").string)
                }
            }) }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    @Test fun bundledMotionsPassTheActualSdkParser() {
        val files = File("src/main/assets/live2d").walkTopDown().filter { it.name.endsWith(".motion3.json") }.toList()
        assertTrue(files.isNotEmpty())
        files.forEach { assertTrue(it.path, CubismMotionJson(it.readBytes()).hasConsistency()) }
    }
}
