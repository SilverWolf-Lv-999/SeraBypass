package io.github.seraphina.utility;

import io.github.seraphina.utility.hook.SeraLegitHook;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

public final class ThreadUtility {
    private static final String PROTECTED_THREAD_PREFIX = "!!!sera&thread_";
    private static final Object LOCK = new Object();
    private static final Set<Thread> protectedThreads =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final MethodHandle STOP_METHOD;
    private static final MethodHandle SUSPEND_METHOD;
    private static final MethodHandle RESUME_METHOD;

    private static volatile boolean threadHooksInstalled;

    static {
        try {
            STOP_METHOD = UnsafeUtility.TRUSTED_LOOKUP.findVirtual(
                    Thread.class, "stop0", MethodType.methodType(void.class, Object.class));
            SUSPEND_METHOD = UnsafeUtility.TRUSTED_LOOKUP.findVirtual(
                    Thread.class, "suspend0", MethodType.methodType(void.class));
            RESUME_METHOD = UnsafeUtility.TRUSTED_LOOKUP.findVirtual(
                    Thread.class, "resume0", MethodType.methodType(void.class));
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Could not find Thread native methods", exception);
        }
    }

    public static void protectThread(Thread thread) {
        Objects.requireNonNull(thread, "thread");

        synchronized (LOCK) {
            if (protectedThreads.contains(thread)) {
                return;
            }

            protectedThreads.add(thread);
            try {
                if (!threadHooksInstalled) {
                    SeraLegitHook.hookInstanceVoidMethod(Thread.class, "stop", value -> {
                        Thread target = (Thread) value;
                        synchronized (LOCK) {
                            if (protectedThreads.contains(target)) {
                                return;
                            }
                        }
                        try {
                            RESUME_METHOD.invoke(target);
                        } catch (Throwable throwable) {
                            throw new IllegalStateException("Could not invoke Thread.resume0", throwable);
                        }
                        try {
                            STOP_METHOD.invoke(target, new ThreadDeath());
                        } catch (Throwable throwable) {
                            throw new IllegalStateException("Could not invoke Thread.stop0", throwable);
                        }
                    });
                    SeraLegitHook.hookInstanceVoidMethod(Thread.class, "suspend", value -> {
                        Thread target = (Thread) value;
                        synchronized (LOCK) {
                            if (protectedThreads.contains(target)) {
                                return;
                            }
                        }
                        try {
                            SUSPEND_METHOD.invoke(target);
                        } catch (Throwable throwable) {
                            throw new IllegalStateException("Could not invoke Thread.suspend0", throwable);
                        }
                    });
                    SeraLegitHook.hookInstanceVoidMethod(Thread.class, "resume", value -> {
                        Thread target = (Thread) value;
                        synchronized (LOCK) {
                            if (protectedThreads.contains(target)) {
                                return;
                            }
                        }
                        try {
                            RESUME_METHOD.invoke(target);
                        } catch (Throwable throwable) {
                            throw new IllegalStateException("Could not invoke Thread.resume0", throwable);
                        }
                    });
                    threadHooksInstalled = true;
                }

                String threadName = thread.getName();
                if (threadName.isEmpty()) {
                    threadName = "unnamed";
                }
                if (!threadName.startsWith(PROTECTED_THREAD_PREFIX)) {
                    thread.setName(PROTECTED_THREAD_PREFIX + threadName);
                }
            } catch (Throwable throwable) {
                protectedThreads.remove(thread);
                throw throwable;
            }
        }
    }
}
