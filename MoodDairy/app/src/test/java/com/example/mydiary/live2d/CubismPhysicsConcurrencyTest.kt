package com.example.mydiary.live2d

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.live2d.sdk.cubism.framework.math.CubismVector2
import com.live2d.sdk.cubism.framework.physics.CubismPhysics
import com.live2d.sdk.cubism.framework.physics.CubismPhysicsInternal.CubismPhysicsParticle

class CubismPhysicsConcurrencyTest {
    private fun simulate(index: Int, start: CountDownLatch? = null): FloatArray {
        val method = CubismPhysics::class.java.declaredMethods.single { it.name == "updateParticles" }.apply { isAccessible = true }
        val stabilize = CubismPhysics::class.java.declaredMethods.single { it.name == "updateParticlesForStabilization" }.apply { isAccessible = true }
        val strand = (0..5).map { i -> CubismPhysicsParticle().apply {
            position.set(0f, i.toFloat()); lastGravity.set(0f, 1f)
            mobility = .8f; delay = .7f; acceleration = .9f; radius = 1f
        } }
        val wind = CubismVector2(.02f * index, .01f)
        val translation = CubismVector2(index.toFloat(), .2f)
        start?.await()
        repeat(1500) { i ->
            if (i % 50 == 0) stabilize.invoke(null, strand, 0, strand.size, translation, index * 7f, wind, .001f)
            method.invoke(null, strand, 0, strand.size, translation, index * 7f, wind, .001f, 1f / 60, 5f)
        }
        return strand.flatMap { listOf(it.position.x, it.position.y, it.velocity.x, it.velocity.y) }.toFloatArray()
    }
    @Test fun parallelHairAndClothingPhysicsMatchIsolatedSimulation() {
        val expected = (1..4).map { simulate(it) }
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..4).map { i -> executor.submit(Callable { simulate(i, start) }) }
            start.countDown()
            jobs.forEachIndexed { i, result -> assertArrayEquals(expected[i], result.get(30, TimeUnit.SECONDS), .00001f) }
        } finally { executor.shutdownNow() }
    }
}
