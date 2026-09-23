package io.github.seraphina.agent;

import io.github.seraphina.agent.api.SeraTransImpl;

import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class Agent {
    private static final AtomicBoolean onStart = new AtomicBoolean(false);
    private static final AtomicInteger TRANSFORM_THREAD_ID = new AtomicInteger();
    private static final ExecutorService TRANSFORM_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "sera-agent-transform-" + TRANSFORM_THREAD_ID.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

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
        if (loadedClasses.length == 0) {
            return;
        }

        for (Class<?> loadedClass : loadedClasses) {
            if (loadedClass == null) {
                throw new NullPointerException("loadedClass");
            }
        }

        SeraTransImpl[] activeTransformers = transformers.toArray(SeraTransImpl[]::new);
        if (loadedClasses.length == 1) {
            transformClass(loadedClasses[0], activeTransformers);
            return;
        }

        List<Callable<Void>> tasks = new ArrayList<>(loadedClasses.length);
        for (Class<?> loadedClass : loadedClasses) {
            tasks.add(() -> {
                transformClass(loadedClass, activeTransformers);
                return null;
            });
        }

        try {
            for (Future<Void> future : TRANSFORM_EXECUTOR.invokeAll(tasks)) {
                try {
                    future.get();
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw new IllegalStateException(cause);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while transforming classes", exception);
        }
    }

    private static void transformClass(Class<?> loadedClass, SeraTransImpl[] activeTransformers) {
        for (SeraTransImpl seraTransImpl : activeTransformers) {
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
