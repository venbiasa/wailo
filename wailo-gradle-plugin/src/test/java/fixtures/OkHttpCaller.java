package fixtures;

import okhttp3.OkHttpClient;

/**
 * Stand-in for app / third-party code that builds an OkHttpClient. The test transforms this class and
 * checks the woven hook fires for each {@code build()} call site.
 */
public final class OkHttpCaller {

    private OkHttpCaller() {
    }

    public static OkHttpClient plainBuild() {
        return new OkHttpClient.Builder().build();
    }

    public static OkHttpClient chainedBuild() {
        return new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .build();
    }

    /** No {@code build()} call site — must be left untouched by the transform. */
    public static int noBuild() {
        return 42;
    }
}
