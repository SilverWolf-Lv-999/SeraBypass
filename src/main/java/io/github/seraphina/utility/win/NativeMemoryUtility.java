package io.github.seraphina.utility.win;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Windows virtual-memory range validation for native addresses before they are
 * accessed through {@code Unsafe}.
 */
public final class NativeMemoryUtility {
    private static final int MEM_COMMIT = 0x1000;
    private static final int PAGE_GUARD = 0x100;
    private static final int PAGE_READONLY = 0x02;
    private static final int PAGE_READWRITE = 0x04;
    private static final int PAGE_WRITECOPY = 0x08;
    private static final int PAGE_EXECUTE_READ = 0x20;
    private static final int PAGE_EXECUTE_READWRITE = 0x40;
    private static final int PAGE_EXECUTE_WRITECOPY = 0x80;
    private static final int POINTER_SIZE = Native.POINTER_SIZE;
    private static final int REGION_SIZE_OFFSET = align(POINTER_SIZE * 2 + Integer.BYTES, POINTER_SIZE);
    private static final int STATE_OFFSET = REGION_SIZE_OFFSET + POINTER_SIZE;
    private static final int PROTECT_OFFSET = STATE_OFFSET + Integer.BYTES;
    private static final int MEMORY_BASIC_INFORMATION_SIZE = align(
            PROTECT_OFFSET + Integer.BYTES * 2,
            POINTER_SIZE);

    private static final Kernel32 KERNEL32 = Native.load("kernel32", Kernel32.class);

    public static long getContiguousReadableBytes(long address, long requestedByteCount) {
        if (address == 0L || requestedByteCount <= 0L) {
            return 0L;
        }

        long currentAddress = address;
        long remainingByteCount = requestedByteCount;
        while (remainingByteCount > 0L) {
            Memory memoryInformation = new Memory(MEMORY_BASIC_INFORMATION_SIZE);
            long queriedByteCount;
            try {
                queriedByteCount = KERNEL32.VirtualQuery(
                        new Pointer(currentAddress), memoryInformation, MEMORY_BASIC_INFORMATION_SIZE);
            } catch (Throwable ignored) {
                break;
            }
            if (queriedByteCount < MEMORY_BASIC_INFORMATION_SIZE) {
                break;
            }

            long regionBaseAddress = Pointer.nativeValue(memoryInformation.getPointer(0L));
            long regionSize = readNativeSize(memoryInformation, REGION_SIZE_OFFSET);
            if (!isReadable(memoryInformation) || regionSize <= 0L
                    || Long.compareUnsigned(currentAddress, regionBaseAddress) < 0) {
                break;
            }

            long regionEndAddress = regionBaseAddress + regionSize;
            if (Long.compareUnsigned(regionEndAddress, regionBaseAddress) <= 0
                    || Long.compareUnsigned(currentAddress, regionEndAddress) >= 0) {
                break;
            }

            long availableByteCount = regionEndAddress - currentAddress;
            long consumedByteCount = Math.min(remainingByteCount, availableByteCount);
            remainingByteCount -= consumedByteCount;
            if (remainingByteCount == 0L) {
                break;
            }

            long nextAddress = currentAddress + consumedByteCount;
            if (Long.compareUnsigned(nextAddress, currentAddress) <= 0) {
                break;
            }
            currentAddress = nextAddress;
        }
        return requestedByteCount - remainingByteCount;
    }

    public static boolean isReadable(long address, long byteCount) {
        return byteCount > 0L && getContiguousReadableBytes(address, byteCount) == byteCount;
    }

    private static boolean isReadable(Memory memoryInformation) {
        if (memoryInformation.getInt(STATE_OFFSET) != MEM_COMMIT) {
            return false;
        }

        int protection = memoryInformation.getInt(PROTECT_OFFSET);
        if ((protection & PAGE_GUARD) != 0) {
            return false;
        }

        return switch (protection & 0xFF) {
            case PAGE_READONLY,
                    PAGE_READWRITE,
                    PAGE_WRITECOPY,
                    PAGE_EXECUTE_READ,
                    PAGE_EXECUTE_READWRITE,
                    PAGE_EXECUTE_WRITECOPY -> true;
            default -> false;
        };
    }

    private static long readNativeSize(Memory memoryInformation, long offset) {
        return POINTER_SIZE == Long.BYTES
                ? memoryInformation.getLong(offset)
                : Integer.toUnsignedLong(memoryInformation.getInt(offset));
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) & -alignment;
    }

    private interface Kernel32 extends Library {
        long VirtualQuery(Pointer address, Pointer memoryInformation, long length);
    }
}
