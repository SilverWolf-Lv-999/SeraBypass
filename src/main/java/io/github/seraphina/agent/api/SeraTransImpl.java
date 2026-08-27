package io.github.seraphina.agent.api;

import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

public interface SeraTransImpl {

    default void transform(Class<?> loadedClass) {
    }

    default byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        return null;
    }

    default byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        return transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }
}
