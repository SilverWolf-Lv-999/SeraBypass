package io.github.seraphina.utility.win;

import io.github.seraphina.utility.UnsafeUtility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeMemoryUtilityTest {
    @Test
    void detectsTheReadableRangeContainingAKlass() {
        long klassAddress = UnsafeUtility.UNSAFE.getLong(Probe.class, 16L);
        long readableByteCount = NativeMemoryUtility.getContiguousReadableBytes(klassAddress, 4096L);
        assertTrue(readableByteCount >= Long.BYTES);
        assertEquals(0L, readableByteCount % Long.BYTES);
    }

    private static final class Probe {
        private void first() {
        }

        private void second() {
        }
    }
}
