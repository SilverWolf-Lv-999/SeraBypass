package io.github.seraphina.utility.instrumentation;

import io.github.seraphina.agent.Agent;

/**
 * Entry points for applying registered transformers to loaded classes.
 *
 * <p>The implementation intentionally operates on the live HotSpot class
 * metadata. It does not read a class-file path or use a class redefinition
 * service.</p>
 */
public final class ClassTransformationUtility {
    public static boolean retransform(Class<?>... classes) {
        return apply(classes);
    }

    public static boolean redefine(Class<?>... classes) {
        return apply(classes);
    }

    private static boolean apply(Class<?>... classes) {
        if (classes == null) {
            return false;
        }
        if (!Agent.isAttached()) {
            return false;
        }
        try {
            Agent.transform(classes);
            return true;
        } catch (RuntimeException | Error exception) {
            return false;
        }
    }
}
