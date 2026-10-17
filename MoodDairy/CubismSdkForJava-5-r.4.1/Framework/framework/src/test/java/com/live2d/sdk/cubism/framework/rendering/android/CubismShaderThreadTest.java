package com.live2d.sdk.cubism.framework.rendering.android;

import org.junit.Test;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class CubismShaderThreadTest {
    @Test
    public void switchingRenderThreadsDoesNotReuseShaderPrograms() throws Exception {
        ExecutorService otherSurface = Executors.newSingleThreadExecutor();
        try {
            CubismShaderAndroid local = CubismShaderAndroid.getInstance();
            CubismShaderAndroid other = otherSurface.submit(CubismShaderAndroid::getInstance)
                .get(5, TimeUnit.SECONDS);
            assertNotSame(local, other);
            // Recreating one surface must not reset the other surface's shaders.
            otherSurface.submit(() -> {
                CubismShaderAndroid.deleteInstance();
                return null;
            }).get(5, TimeUnit.SECONDS);
            assertSame(local, CubismShaderAndroid.getInstance());
            CubismShaderAndroid recreated = otherSurface.submit(CubismShaderAndroid::getInstance)
                .get(5, TimeUnit.SECONDS);
            assertNotSame(other, recreated);
            assertNotSame(local, recreated);
        } finally {
            CubismShaderAndroid.deleteInstance();
            otherSurface.shutdownNow();
        }
    }
}
