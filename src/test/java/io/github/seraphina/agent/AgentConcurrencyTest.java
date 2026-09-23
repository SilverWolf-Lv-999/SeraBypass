package io.github.seraphina.agent;

import io.github.seraphina.agent.api.SeraTransImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.ProtectionDomain;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConcurrencyTest {
    @AfterEach
    void resetAgent() {
        Agent.detachAll();
        Agent.transformers.clear();
    }

    @Test
    void transformsMultipleClassesConcurrently() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger activeTransforms = new AtomicInteger();
        AtomicInteger maxActiveTransforms = new AtomicInteger();

        Agent.reg(new SeraTransImpl() {
            @Override
            public byte[] transform(
                    ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] classfileBuffer) {
                if (!"java/lang/Integer".equals(className)
                        && !"java/lang/String".equals(className)) {
                    return null;
                }

                int active = activeTransforms.incrementAndGet();
                maxActiveTransforms.accumulateAndGet(active, Math::max);
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent transforms");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                } finally {
                    activeTransforms.decrementAndGet();
                }
                return null;
            }
        });
        Agent.attach();

        Thread caller = new Thread(
                () -> Agent.transform(Integer.class, String.class),
                "agent-transform-test-caller");
        try {
            caller.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            caller.join(5_000L);
        }

        assertFalse(caller.isAlive());
        assertEquals(2, maxActiveTransforms.get());
    }
}
