package io.github.seraphina.cpp;

/**
 * JNI smoke-test declaration.
 *
 * <p>This class deliberately contains no DLL loading logic. Native images are loaded through
 * the Java-side {@code SysUtility} API before this method is invoked.</p>
 */
public final class SeraBypass {
    private SeraBypass() {
    }

    public static native void nativeSayHello();
}
