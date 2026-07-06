package fixtures;

import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/**
 * Test stand-in for {@code WailoRuntime}: the visitor is retargeted to weave calls to this class, which
 * records how many times it ran and adds a no-op interceptor (mirroring the real runtime hook).
 */
public final class TestHook {

    public static final AtomicInteger CALLS = new AtomicInteger(0);

    private TestHook() {
    }

    public static void reset() {
        CALLS.set(0);
    }

    public static OkHttpClient.Builder hook(OkHttpClient.Builder builder) {
        CALLS.incrementAndGet();
        builder.addInterceptor(chain -> chain.proceed(chain.request()));
        return builder;
    }
}
