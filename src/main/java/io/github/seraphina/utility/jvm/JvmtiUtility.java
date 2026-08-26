package io.github.seraphina.utility.jvm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Coordinates the JVMTI peer recovery lifecycle. */
public final class JvmtiUtility {
    private static final long RECOVERY_PERIOD_MILLIS = 1000L;
    private static final Object LOCK = new Object();
    private static final Logger LOGGER = LogManager.getLogger();

    private static volatile boolean patched;
    private static ScheduledExecutorService recoveryExecutor;

    public static void initializeJvmtiPeer() {
        synchronized (LOCK) {
            if (patched) {
                return;
            }
            if (!JvmtiNativeUtility.initializeJvmti()) {
                LOGGER.debug("Could not initialize the Java JVMTI peer neutralizer");
                return;
            }

            JvmtiNativeUtility.neutralizeAlienEnvironments();
            JvmtiNativeUtility.disarmAlienEnvironments();
            JvmtiNativeUtility.recoverJvmti();
            JvmtiNativeUtility.disarmAlienEnvironments();

            patched = true;
            recoveryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ninecun-lingtai-jvmti-recovery");
                thread.setDaemon(true);
                return thread;
            });
            recoveryExecutor.scheduleWithFixedDelay(
                    JvmtiUtility::recoverJvmti,
                    RECOVERY_PERIOD_MILLIS,
                    RECOVERY_PERIOD_MILLIS,
                    TimeUnit.MILLISECONDS
            );
            LOGGER.info("Java JVMTI peer neutralizer initialized");
        }
    }

    public static boolean isJvmtiPeerInitialized() {
        synchronized (LOCK) {
            return patched;
        }
    }

    public static void shutdownJvmti() {
        synchronized (LOCK) {
            patched = false;
            if (recoveryExecutor != null) {
                recoveryExecutor.shutdownNow();
                recoveryExecutor = null;
            }
            JvmtiNativeUtility.shutdownJvmti();
        }
    }

    private static void recoverJvmti() {
        if (!patched) {
            return;
        }
        try {
            if (JvmtiNativeUtility.recoverJvmti() > 0) {
                JvmtiNativeUtility.disarmAlienEnvironments();
            }
        } catch (Throwable throwable) {
            LOGGER.debug("JVMTI peer neutralizer recovery failed", throwable);
        }
    }
}



