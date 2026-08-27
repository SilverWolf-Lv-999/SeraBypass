package io.github.seraphina.utility.win;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.lwjgl.system.MemoryUtil;

public final class MemWrite {
    private static final int MAX_ATTEMPTS = 5_000_000;
    private static final int MEM_COMMIT = 0x1000;
    private static final int MEM_RESERVE = 0x2000;
    private static final int MEM_RELEASE = 0x8000;
    private static final int PAGE_READWRITE = 0x04;
    private static final int PAGE_EXECUTE_READ = 0x20;
    private static final long EXPECTED_RESULT = 0x0123456789ABCDEFL;
    private static final byte[] EXPECTED_TEXT = "Hello World"
            .getBytes(StandardCharsets.US_ASCII);

    private static volatile boolean virtualAllocEntered;
    private static volatile boolean virtualProtectEntered;
    private static volatile boolean virtualFreeEntered;
    private static volatile boolean shellcodeEntered;
    private static volatile Api cachedApi;

    private MemWrite() {}

    public static void main(String[] args) {
        if (!run()) {
            throw new IllegalStateException("MemWrite self-test failed");
        }
    }

    public static synchronized PreparedMemory prepareCopy(long sourceAddress, int size)
            throws Exception {
        if (sourceAddress == 0 || size <= 0) {
            throw new IllegalArgumentException("Invalid source address/size");
        }

        Api api = api();
        long address = invokeVirtualAlloc(api.virtualAlloc, size);
        if (address == 0) {
            throw new IllegalStateException("VirtualAlloc returned NULL");
        }

        boolean prepared = false;
        try {
            MemoryUtil.memCopy(sourceAddress, address, size);
            verifyCopy("after copy", sourceAddress, address, size);
            System.err.println("[MemWrite] OK source=0x"
                    + Long.toHexString(sourceAddress)
                    + " target=0x" + Long.toHexString(address)
                    + " size=" + size);

            ByteBuffer oldProtect = MemoryUtil.memCalloc(Integer.BYTES);
            try {
                long attempts = invokeVirtualProtect(
                        api.virtualProtect,
                        address,
                        size,
                        PAGE_EXECUTE_READ,
                        MemoryUtil.memAddress(oldProtect));
                verifyCopy("after VirtualProtect", sourceAddress, address, size);
                System.err.println("[MemWrite] OK 0x"
                        + Integer.toHexString(oldProtect.getInt(0))
                        + " attempts=" + attempts);
            } finally {
                MemoryUtil.memFree(oldProtect);
            }

            prepared = true;
            return new PreparedMemory(address, size, api.virtualFree);
        } finally {
            if (!prepared) {
                release(api.virtualFree, address);
            }
        }
    }

    public static synchronized boolean run() {
        ByteBuffer source = null;
        ByteBuffer output = null;
        PreparedMemory prepared = null;
        try {
            byte[] code = buildHelloWorldShellcode();
            source = MemoryUtil.memAlloc(code.length);
            source.put(code).flip();
            prepared = prepareCopy(MemoryUtil.memAddress(source), code.length);

            Object lookup = SeraNative.HackBase.lookup;
            Object fallback = fallbackHandle(
                    lookup,
                    "shellcodeFallback",
                    SeraNative.Way2Linker.C_POINTER,
                    SeraNative.Way2Linker.C_POINTER);
            Object shellcode = SeraNative.Way2Linker.downcall(
                    prepared.address(),
                    fallback,
                    SeraNative.Way2FunctionDescriptor.of(
                            SeraNative.Way2Linker.C_POINTER,
                            SeraNative.Way2Linker.C_POINTER));

            output = MemoryUtil.memCalloc(64);
            long attempts = 0;
            long result;
            while (true) {
                if (++attempts > MAX_ATTEMPTS) {
                    throw new IllegalStateException(
                            "Shellcode remained on the Java fallback path");
                }
                shellcodeEntered = true;
                result = ((Number) SeraNative.ApacheAccess.invokeHandle(
                        shellcode,
                        MemoryUtil.memAddress(output))).longValue();
                if (shellcodeEntered) {
                    break;
                }
            }
            if (result != EXPECTED_RESULT) {
                throw new IllegalStateException(
                        "Unexpected shellcode result: 0x" + Long.toHexString(result));
            }

            byte[] actualText = new byte[EXPECTED_TEXT.length];
            output.get(actualText);
            if (!Arrays.equals(actualText, EXPECTED_TEXT)) {
                throw new IllegalStateException(
                        "Unexpected shellcode output: " + Arrays.toString(actualText));
            }

            System.out.println(new String(actualText, StandardCharsets.US_ASCII));
            System.err.println("[MemWrite] OK 0x"
                    + Long.toHexString(result)
                    + " attempts=" + attempts);
            System.err.println("[MemWrite] PASS");
            return true;
        } catch (Throwable t) {
            System.err.println("[MemWrite] FAIL: " + t);
            t.printStackTrace();
            return false;
        } finally {
            if (prepared != null) {
                prepared.close();
            }
            if (output != null) {
                MemoryUtil.memFree(output);
            }
            if (source != null) {
                MemoryUtil.memFree(source);
            }
        }
    }

    private static Api api() throws Exception {
        Api current = cachedApi;
        if (current != null) {
            return current;
        }

        Object lookup = SeraNative.HackBase.lookup;
        Object nativeLibraryClass = SeraNative.ApacheAccess.type(
                "jdk.internal.loader.NativeLibraries$NativeLibraryImpl");
        Object constructor = SeraNative.ApacheAccess.invoke(
                lookup,
                "findConstructor",
                nativeLibraryClass,
                SeraNative.ApacheAccess.methodType(
                        SeraNative.Way2Linker.C_VOID,
                        SeraNative.ApacheAccess.CLASS_TYPE,
                        SeraNative.ApacheAccess.STRING_TYPE,
                        SeraNative.Way2Linker.C_BOOLEAN,
                        SeraNative.Way2Linker.C_BOOLEAN));
        Object openHandle = SeraNative.ApacheAccess.invoke(
                lookup,
                "findVirtual",
                nativeLibraryClass,
                "open",
                SeraNative.ApacheAccess.methodType(
                        SeraNative.Way2Linker.C_BOOLEAN));
        Object findHandle = SeraNative.ApacheAccess.invoke(
                lookup,
                "findVirtual",
                nativeLibraryClass,
                "find",
                SeraNative.ApacheAccess.methodType(
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.ApacheAccess.STRING_TYPE));
        Object kernel32 = SeraNative.ApacheAccess.invokeHandle(
                constructor,
                MemWrite.class,
                "kernel32.dll",
                false,
                false);
        if (!(boolean) SeraNative.ApacheAccess.invokeHandle(openHandle, kernel32)) {
            throw new UnsatisfiedLinkError("Unable to open kernel32.dll");
        }

        long virtualAllocAddress = findAddress(findHandle, kernel32, "VirtualAlloc");
        long virtualProtectAddress = findAddress(findHandle, kernel32, "VirtualProtect");
        long virtualFreeAddress = findAddress(findHandle, kernel32, "VirtualFree");

        Object virtualAlloc = SeraNative.Way2Linker.downcall(
                virtualAllocAddress,
                fallbackHandle(
                        lookup,
                        "virtualAllocFallback",
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_INT,
                        SeraNative.Way2Linker.C_INT),
                SeraNative.Way2FunctionDescriptor.of(
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_INT,
                        SeraNative.Way2Linker.C_INT));
        Object virtualProtect = SeraNative.Way2Linker.downcall(
                virtualProtectAddress,
                fallbackHandle(
                        lookup,
                        "virtualProtectFallback",
                        SeraNative.Way2Linker.C_INT,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_INT,
                        SeraNative.Way2Linker.C_POINTER),
                SeraNative.Way2FunctionDescriptor.of(
                        SeraNative.Way2Linker.C_INT,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_INT,
                        SeraNative.Way2Linker.C_POINTER));
        Object virtualFree = SeraNative.Way2Linker.downcall(
                virtualFreeAddress,
                fallbackHandle(
                        lookup,
                        "virtualFreeFallback",
                        SeraNative.Way2Linker.C_INT,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_INT),
                SeraNative.Way2FunctionDescriptor.of(
                        SeraNative.Way2Linker.C_INT,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_POINTER,
                        SeraNative.Way2Linker.C_INT));

        current = new Api(lookup, virtualAlloc, virtualProtect, virtualFree);
        cachedApi = current;
        return current;
    }

    private static Object fallbackHandle(
            Object lookup,
            String name,
            Object returnType,
            Object... argumentTypes) throws Exception {
        Object[] fallbackArguments = new Object[argumentTypes.length + 1];
        fallbackArguments[0] = SeraNative.Way2Linker.C_POINTER;
        System.arraycopy(argumentTypes, 0, fallbackArguments, 1, argumentTypes.length);
        return SeraNative.ApacheAccess.invoke(
                lookup,
                "findStatic",
                MemWrite.class,
                name,
                SeraNative.ApacheAccess.methodType(returnType, fallbackArguments));
    }

    private static long findAddress(Object findHandle, Object library, String symbol)
            throws Exception {
        long address = ((Number) SeraNative.ApacheAccess.invokeHandle(
                findHandle,
                library,
                symbol)).longValue();
        if (address == 0) {
            throw new UnsatisfiedLinkError(symbol + " not found");
        }
        return address;
    }

    private static long invokeVirtualAlloc(Object virtualAlloc, int size) throws Exception {
        long attempts = 0;
        long address;
        while (true) {
            if (++attempts > MAX_ATTEMPTS) {
                throw new IllegalStateException(
                        "VirtualAlloc remained on the Java fallback path");
            }
            virtualAllocEntered = true;
            address = ((Number) SeraNative.ApacheAccess.invokeHandle(
                    virtualAlloc,
                    0L,
                    (long) size,
                    MEM_COMMIT | MEM_RESERVE,
                    PAGE_READWRITE)).longValue();
            if (virtualAllocEntered) {
                return address;
            }
        }
    }

    private static long invokeVirtualProtect(
            Object virtualProtect,
            long address,
            int size,
            int protection,
            long oldProtectAddress) throws Exception {
        long attempts = 0;
        int result;
        while (true) {
            if (++attempts > MAX_ATTEMPTS) {
                throw new IllegalStateException(
                        "VirtualProtect remained on the Java fallback path");
            }
            virtualProtectEntered = true;
            result = ((Number) SeraNative.ApacheAccess.invokeHandle(
                    virtualProtect,
                    address,
                    (long) size,
                    protection,
                    oldProtectAddress)).intValue();
            if (virtualProtectEntered) {
                break;
            }
        }
        if (result == 0) {
            throw new IllegalStateException("VirtualProtect returned FALSE");
        }
        return attempts;
    }

    private static synchronized void release(Object virtualFree, long address) {
        if (address == 0 || virtualFree == null) {
            return;
        }
        try {
            long attempts = 0;
            int result;
            while (true) {
                if (++attempts > MAX_ATTEMPTS) {
                    throw new IllegalStateException(
                            "VirtualFree remained on the Java fallback path");
                }
                virtualFreeEntered = true;
                result = ((Number) SeraNative.ApacheAccess.invokeHandle(
                        virtualFree,
                        address,
                        0L,
                        MEM_RELEASE)).intValue();
                if (virtualFreeEntered) {
                    break;
                }
            }
            if (result == 0) {
                System.err.println("[MemWrite] VirtualFree err for 0x"
                        + Long.toHexString(address));
            }
        } catch (Throwable t) {
            System.err.println("[MemWrite] VirtualFree err for 0x"
                    + Long.toHexString(address) + ": " + t);
        }
    }

    private static void verifyCopy(String stage, long source, long target, int size) {
        ByteBuffer sourceBytes = MemoryUtil.memByteBuffer(source, size);
        ByteBuffer targetBytes = MemoryUtil.memByteBuffer(target, size);
        if (!sourceBytes.equals(targetBytes)) {
            throw new IllegalStateException("Code copy changed " + stage);
        }
    }

    private static byte[] buildHelloWorldShellcode() {
        byte[] code = new byte[31];
        ByteBuffer bytes = ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[] {(byte) 0x48, (byte) 0xB8});
        bytes.putLong(0x6F57206F6C6C6548L);
        bytes.put(new byte[] {(byte) 0x48, (byte) 0x89, (byte) 0x01});
        bytes.put(new byte[] {
                (byte) 0xC7, (byte) 0x41, (byte) 0x08,
                (byte) 0x72, (byte) 0x6C, (byte) 0x64, (byte) 0x00});
        bytes.put(new byte[] {(byte) 0x48, (byte) 0xB8});
        bytes.putLong(EXPECTED_RESULT);
        bytes.put((byte) 0xC3);
        return code;
    }

    private static long virtualAllocFallback(
            long target,
            long address,
            long size,
            int allocationType,
            int protect) {
        virtualAllocEntered = false;
        return 0;
    }

    private static int virtualProtectFallback(
            long target,
            long address,
            long size,
            int protect,
            long oldProtect) {
        virtualProtectEntered = false;
        return 0;
    }

    private static int virtualFreeFallback(
            long target,
            long address,
            long size,
            int freeType) {
        virtualFreeEntered = false;
        return 0;
    }

    private static long shellcodeFallback(long target, long outputAddress) {
        shellcodeEntered = false;
        return 0;
    }

    private static final class Api {
        final Object lookup;
        final Object virtualAlloc;
        final Object virtualProtect;
        final Object virtualFree;

        Api(Object lookup, Object virtualAlloc, Object virtualProtect, Object virtualFree) {
            this.lookup = lookup;
            this.virtualAlloc = virtualAlloc;
            this.virtualProtect = virtualProtect;
            this.virtualFree = virtualFree;
        }
    }

    public static final class PreparedMemory implements AutoCloseable {
        private long address;
        private final int size;
        private final Object virtualFree;

        private PreparedMemory(long address, int size, Object virtualFree) {
            this.address = address;
            this.size = size;
            this.virtualFree = virtualFree;
        }

        public long address() {
            if (address == 0) {
                throw new IllegalStateException("Prepared memory has been released");
            }
            return address;
        }

        public int size() {
            return size;
        }

        @Override
        public synchronized void close() {
            long current = address;
            address = 0;
            release(virtualFree, current);
        }
    }
}

