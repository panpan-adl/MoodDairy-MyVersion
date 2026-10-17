package com.example.mydiary.live2d

import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CubismMatrixConcurrencyTest {
    @Test fun independentRenderThreadsKeepTheirOwnTransformsAndAliasedProducts() {
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..4).map { index -> executor.submit(Callable {
                start.await()
                val scale = index.toFloat() + .5f
                val expected = floatArrayOf(scale, 0f, 0f, 0f, 0f, scale + 1f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
                val matrix = CubismMatrix44.create()
                repeat(15000) {
                    matrix.loadIdentity()
                    matrix.scaleRelative(scale, scale + 1f)
                    assertArrayEquals(expected, matrix.array, 0f)
                    val copy = expected.copyOf()
                    val identity = floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
                    CubismMatrix44.multiply(copy, identity, copy)
                    assertArrayEquals(expected, copy, 0f)
                    matrix.translateRelative(index.toFloat(), -index.toFloat())
                    assertTrue(matrix.array.all { value -> value.isFinite() })
                }
            }) }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }
}
