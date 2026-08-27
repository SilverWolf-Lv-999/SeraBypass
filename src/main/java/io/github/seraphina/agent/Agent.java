package io.github.seraphina.agent;

import io.github.seraphina.agent.api.SeraTransImpl;

import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Agent {
    private static final AtomicBoolean onStart = new AtomicBoolean(false);

    public static final Set<SeraTransImpl> transformers = new CopyOnWriteArraySet<>();

    public static void reg(SeraTransImpl... seraTransImpl) {
        transformers.addAll(List.of(seraTransImpl));
    }

    public static synchronized void start(String[] args) {
        attach();
    }

    public static void attach() {
        onStart.set(true);
    }

    public static void detachAll() {
        onStart.set(false);
    }

    public static boolean isAttached() {
        return onStart.get();
    }

    public static void transform(Class<?>... loadedClasses) {
        if (!onStart.get()) {
            return;
        }
        if (loadedClasses == null) {
            throw new NullPointerException("loadedClasses");
        }

        for (Class<?> loadedClass : loadedClasses) {
            if (loadedClass == null) {
                throw new NullPointerException("loadedClass");
            }
            for (SeraTransImpl seraTransImpl : transformers) {
                if (seraTransImpl != null) {
                    seraTransImpl.transform(
                            loadedClass.getClassLoader(),
                            loadedClass.getName().replace('.', '/'),
                            loadedClass,
                            loadedClass.getProtectionDomain(),
                            null);
                }
            }
        }
    }

    public static byte[] transformer(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (!onStart.get()) {
            return classfileBuffer;
        }

        byte[] currentBuffer = classfileBuffer;

        for (SeraTransImpl seraTransImpl : transformers) {
            if (seraTransImpl == null) {
                continue;
            }

            try {
                byte[] transformedBuffer = seraTransImpl.transform(
                        loader,
                        className,
                        classBeingRedefined,
                        protectionDomain,
                        currentBuffer);
                if (transformedBuffer != null) {
                    currentBuffer = transformedBuffer;
                }
            } catch (RuntimeException exception) {
                exception.printStackTrace();
            }
        }

        return currentBuffer;
    }

    public static byte[] transformer(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (!onStart.get()) {
            return classfileBuffer;
        }

        byte[] currentBuffer = classfileBuffer;

        for (SeraTransImpl seraTransImpl : transformers) {
            if (seraTransImpl == null) {
                continue;
            }

            try {
                byte[] transformedBuffer = seraTransImpl.transform(
                        module,
                        loader,
                        className,
                        classBeingRedefined,
                        protectionDomain,
                        currentBuffer);
                if (transformedBuffer != null) {
                    currentBuffer = transformedBuffer;
                }
            } catch (IllegalClassFormatException | RuntimeException exception) {
                exception.printStackTrace();
            }
        }

        return currentBuffer;
    }
}
