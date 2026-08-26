package io.github.seraphina.utility.thread;

import io.github.seraphina.utility.hook.SeraLegitHook;
import io.github.seraphina.utility.jdk.UnsafeUtility;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Utilities for protecting application threads from Java-level control operations. */
public final class ThreadUtility {
    private static final String PROTECTED_THREAD_PREFIX = "!!!sera&thread_";
    private static final Object LOCK = new Object();
    private static final Set<Thread> protectedThreads =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private static final MethodHandle STOP_METHOD = findStopMethod();
    private static final MethodHandle SUSPEND_METHOD = findSuspendMethod();
    private static final MethodHandle RESUME_METHOD = findResumeMethod();

    private static volatile boolean threadHooksInstalled;

    /**
     * Marks a thread as protected and installs the Java hooks used to ignore
     * {@link Thread#stop()}, {@link Thread#suspend()}, and {@link Thread#resume()}
     * for that exact thread object.
     */
    public static void protectThread(Thread thread) {
        Objects.requireNonNull(thread, "thread");

        synchronized (LOCK) {
            if (protectedThreads.contains(thread)) {
                return;
            }

            protectedThreads.add(thread);
            try {
                ensureThreadHooks();
                renameProtectedThread(thread);
            } catch (Throwable throwable) {
                protectedThreads.remove(thread);
                throw throwable;
            }
        }
    }

    private static void ensureThreadHooks() {
        if (threadHooksInstalled) {
            return;
        }

        SeraLegitHook.hookInstanceVoidMethod(Thread.class, "stop", ThreadUtility::handleStop);
        SeraLegitHook.hookInstanceVoidMethod(
                Thread.class, "suspend", ThreadUtility::handleSuspend);
        SeraLegitHook.hookInstanceVoidMethod(
                Thread.class, "resume", ThreadUtility::handleResume);
        threadHooksInstalled = true;
    }

    private static void renameProtectedThread(Thread thread) {
        String threadName = thread.getName();
        if (threadName.isEmpty()) {
            threadName = "unnamed";
        }
        if (!threadName.startsWith(PROTECTED_THREAD_PREFIX)) {
            thread.setName(PROTECTED_THREAD_PREFIX + threadName);
        }
    }

    private static boolean isProtectedThread(Thread thread) {
        synchronized (LOCK) {
            return protectedThreads.contains(thread);
        }
    }

    private static void handleStop(Object value) {
        Thread thread = (Thread) value;
        if (isProtectedThread(thread)) {
            return;
        }

        // Thread.stop() resumes a suspended target before asking HotSpot to stop it.
        invokeResume(thread);
        invokeStop(thread);
    }

    private static void handleSuspend(Object value) {
        Thread thread = (Thread) value;
        if (isProtectedThread(thread)) {
            return;
        }
        invokeSuspend(thread);
    }

    private static void handleResume(Object value) {
        Thread thread = (Thread) value;
        if (isProtectedThread(thread)) {
            return;
        }
        invokeResume(thread);
    }

    private static void invokeStop(Thread thread) {
        try {
            STOP_METHOD.invoke(thread, new ThreadDeath());
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not invoke Thread.stop0", throwable);
        }
    }

    private static void invokeSuspend(Thread thread) {
        try {
            SUSPEND_METHOD.invoke(thread);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not invoke Thread.suspend0", throwable);
        }
    }

    private static void invokeResume(Thread thread) {
        try {
            RESUME_METHOD.invoke(thread);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not invoke Thread.resume0", throwable);
        }
    }

    private static MethodHandle findStopMethod() {
        try {
            return UnsafeUtility.TRUSTED_LOOKUP.findVirtual(
                    Thread.class,
                    "stop0",
                    MethodType.methodType(void.class, Object.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Could not find Thread.stop0", exception);
        }
    }

    private static MethodHandle findSuspendMethod() {
        try {
            return UnsafeUtility.TRUSTED_LOOKUP.findVirtual(
                    Thread.class,
                    "suspend0",
                    MethodType.methodType(void.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Could not find Thread.suspend0", exception);
        }
    }

    private static MethodHandle findResumeMethod() {
        try {
            return UnsafeUtility.TRUSTED_LOOKUP.findVirtual(
                    Thread.class,
                    "resume0",
                    MethodType.methodType(void.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Could not find Thread.resume0", exception);
        }
    }
}
