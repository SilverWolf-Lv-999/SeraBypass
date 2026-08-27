package io.github.seraphina.utility.win;

import java.lang.reflect.InvocationHandler;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SeraNative {

    private static volatile boolean way2VirtualProtectEntered;
    private static volatile boolean way2ShellcodeEntered;

    public static final Logger LOGGER = LogManager.getLogger();

    public long load(byte[] shellcode, String dllPath) {

        try {
            System.out.println("DLL Path: " + dllPath);
            return run(shellcode, dllPath);
        } catch (Throwable t) {
            t.printStackTrace();
            return 0;
        }
    }

    private long run(byte[] sc, String dllPath) throws Exception {
        if (sc == null || sc.length < Long.BYTES || dllPath == null || dllPath.isEmpty()) {
            return 0;
        }
        byte[] pathBytes = dllPath.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer scBuffer = null;
        ByteBuffer pathBuffer = null;
        ByteBuffer lockBuffer = null;
        try {
            scBuffer = LwjglAccess.memAlloc(sc.length);
            pathBuffer = LwjglAccess.memAlloc(pathBytes.length + 1);
            lockBuffer = LwjglAccess.memCalloc(Long.BYTES);

            scBuffer.put(sc).flip();
            pathBuffer.put(pathBytes).put((byte) 0).flip();

            long scAddr = LwjglAccess.memAddress(scBuffer);
            long pathAddr = LwjglAccess.memAddress(pathBuffer);
            long lockAddr = LwjglAccess.memAddress(lockBuffer);

            long magicPath = 0x1122334455667788L;
            long magicLock = 0x9988776655443322L;
            boolean pathPatched = false;
            boolean lockPatched = false;

            for (int i = 0; i <= sc.length - Long.BYTES; i++) {
                long value = LwjglAccess.memGetLong(scAddr + i);
                if (!pathPatched && value == magicPath) {
                    LwjglAccess.memPutLong(scAddr + i, pathAddr);
                    pathPatched = true;
                } else if (!lockPatched && value == magicLock) {
                    LwjglAccess.memPutLong(scAddr + i, lockAddr);
                    lockPatched = true;
                }
                if (pathPatched && lockPatched) {
                    break;
                }
            }
            if (!pathPatched || !lockPatched) {
                return 0;
            }
            LOGGER.info("Loaded, Way1");
            Long way1Result = tryWay1(scAddr, sc.length);
            if (way1Result != null) {
                return way1Result;
            }

            LOGGER.info("Way1 failed, try Way2");
            LOGGER.info("Loaded, Way2");
            return tryWay2(scAddr, sc.length);
        } finally {
            LwjglAccess.memFree(lockBuffer);
            LwjglAccess.memFree(pathBuffer);
            LwjglAccess.memFree(scBuffer);
        }
    }


    private Long tryWay1(long shellcodeAddress, int shellcodeSize) {
        MemWrite.PreparedMemory fallbackMemory = null;
        try {
            boolean protectedOk = false;
            try {
                long kernel32 = LwjglAccess.getModuleHandle("kernel32");
                if (kernel32 != 0) {
                    long virtualProtect = LwjglAccess.getProcAddress(
                            kernel32,
                            "VirtualProtect");
                    long virtualQuery = LwjglAccess.getProcAddress(
                            kernel32,
                            "VirtualQuery");
                    if (virtualProtect != 0) {
                        Object stack = LwjglAccess.stackPush();
                        try {
                            ByteBuffer oldProtect = LwjglAccess.stackMalloc(
                                    stack,
                                    Integer.BYTES);
                            protectedOk = LwjglAccess.invokePPZ(
                                    shellcodeAddress,
                                    shellcodeSize,
                                    0x40,
                                    LwjglAccess.memAddress(oldProtect),
                                    virtualProtect);
                        } finally {
                            LwjglAccess.closeStack(stack);
                        }
                    }

                    // A TRUE return alone is not enough: the crash report showed
                    // invokeP reaching a readable but NX page. Verify both ends of
                    // the shellcode range before executing the original buffer.
                    protectedOk = protectedOk
                            && virtualQuery != 0
                            && LwjglAccess.isExecutableRange(
                                    shellcodeAddress,
                                    shellcodeSize,
                                    virtualQuery);
                }
            } catch (Throwable protectFailure) {
                System.err.println("[SeraNative] Way1 VirtualProtect path failed: "
                        + protectFailure);
                protectedOk = false;
            }

            long executionAddress = shellcodeAddress;
            if (!protectedOk) {
                System.err.println("[SeraNative] Way1 VP err");
                fallbackMemory = MemWrite.prepareCopy(shellcodeAddress, shellcodeSize);
                executionAddress = fallbackMemory.address();
            }

            return LwjglAccess.invokeP(executionAddress);
        } catch (Throwable t) {
            System.err.println("[SeraNative] Way1 failed: " + t);
            return null;
        } finally {
            if (fallbackMemory != null) {
                fallbackMemory.close();
            }
        }
    }


    private long tryWay2(long shellcodeAddress, long shellcodeSize) {
        try {
            requireWindowsX64Jdk17();
            if (shellcodeAddress == 0 || shellcodeSize <= 0) {
                throw new IllegalArgumentException("Invalid shellcode address/size");
            }

            Object lookup = HackBase.lookup;
            Object nativeLibraryClass =
                    ApacheAccess.type("jdk.internal.loader.NativeLibraries$NativeLibraryImpl");
            Object kernel32Constructor = ApacheAccess.invoke(
                    lookup,
                    "findConstructor",
                    nativeLibraryClass,
                    ApacheAccess.methodType(
                            Way2Linker.C_VOID,
                            ApacheAccess.CLASS_TYPE,
                            ApacheAccess.STRING_TYPE,
                            Way2Linker.C_BOOLEAN,
                            Way2Linker.C_BOOLEAN));
            Object kernel32 = ApacheAccess.invokeHandle(
                    kernel32Constructor,
                    SeraNative.class,
                    "kernel32.dll",
                    false,
                    false);
            Object openHandle = ApacheAccess.invoke(
                    lookup,
                    "findVirtual",
                    nativeLibraryClass,
                    "open",
                    ApacheAccess.methodType(Way2Linker.C_BOOLEAN));
            boolean opened = (boolean) ApacheAccess.invokeHandle(openHandle, kernel32);
            if (!opened) {
                throw new UnsatisfiedLinkError("Unable to open kernel32.dll");
            }

            Object findHandle = ApacheAccess.invoke(
                    lookup,
                    "findVirtual",
                    nativeLibraryClass,
                    "find",
                    ApacheAccess.methodType(Way2Linker.C_POINTER, ApacheAccess.STRING_TYPE));
            long virtualProtectAddress = ((Number) ApacheAccess.invokeHandle(
                    findHandle,
                    kernel32,
                    "VirtualProtect")).longValue();
            if (virtualProtectAddress == 0) {
                throw new UnsatisfiedLinkError("VirtualProtect not found");
            }

            Object virtualProtectFallback = ApacheAccess.invoke(
                    lookup,
                    "findStatic",
                    SeraNative.class,
                    "way2VirtualProtectFallback",
                    ApacheAccess.methodType(
                            Way2Linker.C_INT,
                            Way2Linker.C_POINTER,
                            Way2Linker.C_POINTER,
                            Way2Linker.C_POINTER,
                            Way2Linker.C_INT,
                            Way2Linker.C_POINTER));
            Object virtualProtect = Way2Linker.downcall(
                    virtualProtectAddress,
                    virtualProtectFallback,
                    Way2FunctionDescriptor.of(
                            Way2Linker.C_INT,
                            Way2Linker.C_POINTER,
                            Way2Linker.C_POINTER,
                            Way2Linker.C_INT,
                            Way2Linker.C_POINTER));

            long oldProtectAddress = ((Number) ApacheAccess.invoke(
                    HackBase.unsafe,
                    "allocateMemory",
                    (long) Integer.BYTES)).longValue();
            MemWrite.PreparedMemory fallbackMemory = null;
            try {
                ApacheAccess.invoke(HackBase.unsafe, "putInt", oldProtectAddress, 0);
                int protectResult = 0;
                long protectAttempts = 0;
                try {
                    while (true) {
                        if (++protectAttempts > 5_000_000L) {
                            throw new IllegalStateException(
                                    "Way2 VirtualProtect remained on the Java fallback path");
                        }
                        way2VirtualProtectEntered = true;
                        protectResult = ((Number) ApacheAccess.invokeHandle(
                                virtualProtect,
                                shellcodeAddress,
                                shellcodeSize,
                                0x40,
                                oldProtectAddress)).intValue();
                        if (way2VirtualProtectEntered) {
                            break;
                        }
                    }
                } catch (Throwable protectFailure) {
                    System.err.println("[SeraNative] Way2 VirtualProtect path failed: "
                            + protectFailure);
                    protectResult = 0;
                }

                long executionAddress = shellcodeAddress;
                if (protectResult == 0) {
                    if (shellcodeSize > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException(
                                "Shellcode is too large for MemWrite: " + shellcodeSize);
                    }
                    System.err.println("[SeraNative] Way2 VP err");
                    fallbackMemory = MemWrite.prepareCopy(
                            shellcodeAddress,
                            (int) shellcodeSize);
                    executionAddress = fallbackMemory.address();
                }

                Object shellcodeFallback = ApacheAccess.invoke(
                        lookup,
                        "findStatic",
                        SeraNative.class,
                        "way2ShellcodeFallback",
                        ApacheAccess.methodType(
                                Way2Linker.C_POINTER,
                                Way2Linker.C_POINTER));
                Object shellcode = Way2Linker.downcall(
                        executionAddress,
                        shellcodeFallback,
                        Way2FunctionDescriptor.of(Way2Linker.C_POINTER));

                long mappedBase = 0;
                long shellcodeAttempts = 0;
                while (true) {
                    if (++shellcodeAttempts > 5_000_000L) {
                        throw new IllegalStateException(
                                "Way2 shellcode remained on the Java fallback path");
                    }
                    way2ShellcodeEntered = true;
                    mappedBase = ((Number) ApacheAccess.invokeHandle(shellcode)).longValue();
                    if (way2ShellcodeEntered) {
                        break;
                    }
                }

                System.err.println("[SeraNative] Way2 executed shellcode; mappedBase=0x"
                        + Long.toHexString(mappedBase)
                        + ", protectAttempts=" + protectAttempts
                        + ", shellcodeAttempts=" + shellcodeAttempts);
                return mappedBase;
            } finally {
                if (fallbackMemory != null) {
                    fallbackMemory.close();
                }
                ApacheAccess.invoke(HackBase.unsafe, "freeMemory", oldProtectAddress);
            }
        } catch (Throwable t) {
            System.err.println("[SeraNative] Way2 failed: " + t);
            t.printStackTrace();
            return 0;
        }
    }

    private static int way2VirtualProtectFallback(
            long target,
            long address,
            long size,
            int protect,
            long oldProtect) {
        way2VirtualProtectEntered = false;
        return 0;
    }

    private static long way2ShellcodeFallback(long target) {
        way2ShellcodeEntered = false;
        return 0;
    }

    private static void requireWindowsX64Jdk17() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        int feature = Runtime.version().feature();
        if (!os.contains("windows")
                || !(arch.equals("amd64") || arch.equals("x86_64"))
                || feature != 17) {
            throw new UnsupportedOperationException(
                    "Way2 requires Windows x64 JDK 17; got "
                            + System.getProperty("java.runtime.version") + ", "
                            + System.getProperty("os.name") + "/"
                            + System.getProperty("os.arch"));
        }
    }

    static final class HackBase {
        static final Object unsafe = getUnsafe();
        static final Object lookup = getImplLookup();

        private HackBase() {}

        private static Object getUnsafe() {
            try {
                return ApacheAccess.readDeclaredStaticField(
                        ApacheAccess.type("sun.misc.Unsafe"),
                        "theUnsafe");
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static Object getImplLookup() {
            try {
                Object field = ApacheAccess.invoke(
                        ApacheAccess.LOOKUP_TYPE,
                        "getDeclaredField",
                        "IMPL_LOOKUP");
                Object base = ApacheAccess.invoke(unsafe, "staticFieldBase", field);
                long offset = ((Number) ApacheAccess.invoke(
                        unsafe,
                        "staticFieldOffset",
                        field)).longValue();
                return ApacheAccess.invoke(unsafe, "getObject", base, offset);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    static final class Way2Linker {
        static final Object C_VOID = void.class;
        static final Object C_BOOLEAN = boolean.class;
        static final Object C_BYTE = byte.class;
        static final Object C_SHORT = short.class;
        static final Object C_CHAR = char.class;
        static final Object C_INT = int.class;
        static final Object C_POINTER = long.class;
        private static final int[] WIN64_INTEGER_ARGUMENT_REGISTERS = {1, 2, 8, 9};

        private Way2Linker() {}

        static Object downcall(
                long targetAddress,
                Object fallback,
                Way2FunctionDescriptor descriptor) {
            try {
                if (targetAddress == 0) {
                    throw new IllegalArgumentException("targetAddress == 0");
                }
                if (descriptor.argumentTypes.length > WIN64_INTEGER_ARGUMENT_REGISTERS.length) {
                    throw new UnsupportedOperationException("Way2 supports at most four arguments");
                }

                Object nativeType = ApacheAccess.methodType(
                        descriptor.returnType,
                        descriptor.argumentTypes);
                Object hiddenTargetTypes = ApacheAccess.classArray(C_POINTER);
                Object leafTypeWithAddress = ApacheAccess.invokeTyped(
                        nativeType,
                        "insertParameterTypes",
                        new Object[]{0, hiddenTargetTypes},
                        C_INT,
                        ApacheAccess.runtimeType(hiddenTargetTypes));
                Object fallbackType = ApacheAccess.invoke(fallback, "type");
                if (!leafTypeWithAddress.equals(fallbackType)) {
                    throw new IllegalArgumentException(
                            "fallback type mismatch: " + fallbackType
                                    + " != " + leafTypeWithAddress);
                }

                Object abiProxyClass = ApacheAccess.type("jdk.internal.invoke.ABIDescriptorProxy");
                Object storageProxyClass = ApacheAccess.type("jdk.internal.invoke.VMStorageProxy");
                Object nativeEntryPointClass = ApacheAccess.type("jdk.internal.invoke.NativeEntryPoint");
                Object sharedSecretsClass = ApacheAccess.type("jdk.internal.access.SharedSecrets");
                Object javaLangInvokeAccessClass =
                        ApacheAccess.type("jdk.internal.access.JavaLangInvokeAccess");

                Object abi = ApacheAccess.proxy(
                        abiProxyClass,
                        (InvocationHandler) (proxy, method, args) -> switch (
                                ApacheAccess.methodName(method)) {
                            case "shadowSpaceBytes" -> 32;
                            case "toString" -> "Win64AbiProxy";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> ArrayUtils.isNotEmpty(args) && proxy == args[0];
                            default -> throw new UnsupportedOperationException(String.valueOf(method));
                        });

                Object argumentMoves = ApacheAccess.newArray(
                        storageProxyClass,
                        descriptor.argumentTypes.length);
                for (int i = 0; i < descriptor.argumentTypes.length; i++) {
                    Object carrier = descriptor.argumentTypes[i];
                    if (carrier != C_POINTER
                            && carrier != C_INT
                            && carrier != C_BYTE
                            && carrier != C_SHORT
                            && carrier != C_CHAR
                            && carrier != C_BOOLEAN) {
                        throw new UnsupportedOperationException(
                                "Way2 only supports integer/pointer carriers: " + carrier);
                    }
                    ApacheAccess.setArray(
                            argumentMoves,
                            i,
                            storage(
                                    storageProxyClass,
                                    0,
                                    WIN64_INTEGER_ARGUMENT_REGISTERS[i]));
                }

                Object returnMoves;
                if (descriptor.returnType == C_VOID) {
                    returnMoves = ApacheAccess.newArray(storageProxyClass, 0);
                } else {
                    returnMoves = ApacheAccess.newArray(storageProxyClass, 1);
                    ApacheAccess.setArray(
                            returnMoves,
                            0,
                            storage(storageProxyClass, 0, 0));
                }

                Object makeNativeEntryPoint = ApacheAccess.invoke(
                        HackBase.lookup,
                        "findStatic",
                        nativeEntryPointClass,
                        "make",
                        ApacheAccess.methodType(
                                nativeEntryPointClass,
                                ApacheAccess.STRING_TYPE,
                                abiProxyClass,
                                ApacheAccess.runtimeType(argumentMoves),
                                ApacheAccess.runtimeType(returnMoves),
                                C_BOOLEAN,
                                ApacheAccess.METHOD_TYPE));
                Object nativeEntryPoint = ApacheAccess.invokeHandle(
                        makeNativeEntryPoint,
                        "raw_downcall_0x" + Long.toHexString(targetAddress),
                        abi,
                        argumentMoves,
                        returnMoves,
                        true,
                        leafTypeWithAddress);

                Object getJavaLangInvokeAccess = ApacheAccess.invoke(
                        HackBase.lookup,
                        "findStatic",
                        sharedSecretsClass,
                        "getJavaLangInvokeAccess",
                        ApacheAccess.methodType(javaLangInvokeAccessClass));
                Object javaLangInvokeAccess = ApacheAccess.invokeHandle(getJavaLangInvokeAccess);
                Object nativeMethodHandleFactory = ApacheAccess.invoke(
                        HackBase.lookup,
                        "findVirtual",
                        javaLangInvokeAccessClass,
                        "nativeMethodHandle",
                        ApacheAccess.methodType(
                                ApacheAccess.METHOD_HANDLE_TYPE,
                                nativeEntryPointClass,
                                ApacheAccess.METHOD_HANDLE_TYPE));
                Object unbound = ApacheAccess.invokeHandle(
                        nativeMethodHandleFactory,
                        javaLangInvokeAccess,
                        nativeEntryPoint,
                        fallback);
                return ApacheAccess.invokeStatic(
                        ApacheAccess.METHOD_HANDLES_TYPE,
                        "insertArguments",
                        unbound,
                        0,
                        new Object[]{targetAddress});
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        private static Object storage(Object storageProxyClass, int type, int index)
                throws Exception {
            return ApacheAccess.proxy(
                    storageProxyClass,
                    (InvocationHandler) (proxy, method, args) -> switch (
                            ApacheAccess.methodName(method)) {
                        case "type" -> type;
                        case "index" -> index;
                        case "toString" -> "VMStorage(type=" + type + ",index=" + index + ")";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> ArrayUtils.isNotEmpty(args) && proxy == args[0];
                        default -> throw new UnsupportedOperationException(String.valueOf(method));
                    });
        }
    }

    static final class Way2FunctionDescriptor {
        final Object returnType;
        final Object[] argumentTypes;

        private Way2FunctionDescriptor(Object returnType, Object[] argumentTypes) {
            this.returnType = returnType;
            this.argumentTypes = ArrayUtils.clone(argumentTypes);
        }

        static Way2FunctionDescriptor of(Object returnType, Object... argumentTypes) {
            return new Way2FunctionDescriptor(returnType, argumentTypes);
        }
    }

    static final class LwjglAccess {
        private static final int MEMORY_BASIC_INFORMATION_SIZE = 48;
        private static final int MEMORY_BASIC_INFORMATION_STATE_OFFSET = 32;
        private static final int MEMORY_BASIC_INFORMATION_PROTECT_OFFSET = 36;
        private static final int MEM_COMMIT = 0x1000;
        private static final int PAGE_GUARD = 0x100;
        private static final int PAGE_EXECUTE_MASK = 0x10 | 0x20 | 0x40 | 0x80;

        private static final Object JNI_TYPE =
                ApacheAccess.type("org.lwjgl.system.JNI");
        private static final Object MEMORY_STACK_TYPE =
                ApacheAccess.type("org.lwjgl.system.MemoryStack");
        private static final Object MEMORY_UTIL_TYPE =
                ApacheAccess.type("org.lwjgl.system.MemoryUtil");
        private static final Object WIN_BASE_TYPE =
                ApacheAccess.type("org.lwjgl.system.windows.WinBase");

        private LwjglAccess() {}

        static ByteBuffer memAlloc(int size) throws Exception {
            return (ByteBuffer) ApacheAccess.invokeStatic(MEMORY_UTIL_TYPE, "memAlloc", size);
        }

        static ByteBuffer memCalloc(int size) throws Exception {
            return (ByteBuffer) ApacheAccess.invokeStatic(MEMORY_UTIL_TYPE, "memCalloc", size);
        }

        static long memAddress(ByteBuffer buffer) throws Exception {
            return ((Number) ApacheAccess.invokeStatic(
                    MEMORY_UTIL_TYPE,
                    "memAddress",
                    buffer)).longValue();
        }

        static long memGetLong(long address) throws Exception {
            return ((Number) ApacheAccess.invokeStatic(
                    MEMORY_UTIL_TYPE,
                    "memGetLong",
                    address)).longValue();
        }

        static void memPutLong(long address, long value) throws Exception {
            ApacheAccess.invokeStatic(MEMORY_UTIL_TYPE, "memPutLong", address, value);
        }

        static void memFree(ByteBuffer buffer) throws Exception {
            if (buffer != null) {
                ApacheAccess.invokeStatic(MEMORY_UTIL_TYPE, "memFree", buffer);
            }
        }

        static Object stackPush() throws Exception {
            return ApacheAccess.invokeStatic(MEMORY_STACK_TYPE, "stackPush");
        }

        static ByteBuffer stackMalloc(Object stack, int size) throws Exception {
            return (ByteBuffer) ApacheAccess.invoke(stack, "malloc", size);
        }

        static void closeStack(Object stack) throws Exception {
            if (stack != null) {
                ApacheAccess.invoke(stack, "close");
            }
        }

        static long getModuleHandle(String moduleName) throws Exception {
            return ((Number) ApacheAccess.invokeStatic(
                    WIN_BASE_TYPE,
                    "GetModuleHandle",
                    moduleName)).longValue();
        }

        static long getProcAddress(long module, String functionName) throws Exception {
            return ((Number) ApacheAccess.invokeStatic(
                    WIN_BASE_TYPE,
                    "GetProcAddress",
                    module,
                    functionName)).longValue();
        }

        static boolean invokePPZ(
                long address,
                int size,
                int protection,
                long oldProtection,
                long function) throws Exception {
            return (boolean) ApacheAccess.invokeStatic(
                    JNI_TYPE,
                    "invokePPZ",
                    address,
                    size,
                    protection,
                    oldProtection,
                    function);
        }

        static boolean isExecutableRange(
                long address,
                int size,
                long virtualQuery) throws Exception {
            if (address == 0 || size <= 0 || virtualQuery == 0) {
                return false;
            }
            long lastAddress = address + size - 1L;
            if (Long.compareUnsigned(lastAddress, address) < 0) {
                return false;
            }

            Object stack = stackPush();
            try {
                ByteBuffer memoryInfo = stackMalloc(
                        stack,
                        MEMORY_BASIC_INFORMATION_SIZE);
                if (!isExecutableAddress(address, memoryInfo, virtualQuery)) {
                    return false;
                }
                return lastAddress == address
                        || isExecutableAddress(lastAddress, memoryInfo, virtualQuery);
            } finally {
                closeStack(stack);
            }
        }

        private static boolean isExecutableAddress(
                long address,
                ByteBuffer memoryInfo,
                long virtualQuery) throws Exception {
            memoryInfo.clear();
            long queried = ((Number) ApacheAccess.invokeStatic(
                    JNI_TYPE,
                    "invokePPP",
                    address,
                    memAddress(memoryInfo),
                    MEMORY_BASIC_INFORMATION_SIZE,
                    virtualQuery)).longValue();
            if (queried < MEMORY_BASIC_INFORMATION_PROTECT_OFFSET
                    + Integer.BYTES) {
                return false;
            }

            int state = memoryInfo.getInt(
                    MEMORY_BASIC_INFORMATION_STATE_OFFSET);
            int protection = memoryInfo.getInt(
                    MEMORY_BASIC_INFORMATION_PROTECT_OFFSET);
            return state == MEM_COMMIT
                    && (protection & PAGE_GUARD) == 0
                    && (protection & PAGE_EXECUTE_MASK) != 0;
        }

        static long invokeP(long function) throws Exception {
            return ((Number) ApacheAccess.invokeStatic(
                    JNI_TYPE,
                    "invokeP",
                    function)).longValue();
        }
    }

    static final class ApacheAccess {
        static final Object CLASS_TYPE = type("java.lang.Class");
        static final Object STRING_TYPE = type("java.lang.String");
        static final Object LOOKUP_TYPE = type("java.lang.invoke.MethodHandles$Lookup");
        static final Object METHOD_TYPE = type("java.lang.invoke.MethodType");
        static final Object METHOD_HANDLE_TYPE = type("java.lang.invoke.MethodHandle");
        static final Object METHOD_HANDLES_TYPE = type("java.lang.invoke.MethodHandles");

        private static final Object ARRAY_TYPE = type("java.lang.reflect.Array");
        private static final Object PROXY_TYPE = type("java.lang.reflect.Proxy");

        private ApacheAccess() {}

        static Object type(String className) {
            try {
                return ClassUtils.getClass(className);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Class unavailable: " + className, e);
            }
        }

        static Object runtimeType(Object value) throws Exception {
            return invoke(value, "getClass");
        }

        static Object invoke(Object target, String methodName, Object... arguments)
                throws Exception {
            return MethodUtils.invokeMethod(target, methodName, arguments);
        }

        static Object invokeTyped(
                Object target,
                String methodName,
                Object[] arguments,
                Object... parameterTypes) throws Exception {
            Class<?>[] exactTypes = new Class<?>[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                exactTypes[i] = (Class<?>) parameterTypes[i];
            }
            return MethodUtils.invokeMethod(
                    target,
                    true,
                    methodName,
                    arguments,
                    exactTypes);
        }

        static Object invokeStatic(Object owner, String methodName, Object... arguments)
                throws Exception {
            return MethodUtils.invokeStaticMethod(
                    (Class<?>) owner,
                    methodName,
                    arguments);
        }

        static Object invokeHandle(Object handle, Object... arguments) throws Exception {
            return MethodUtils.invokeMethod(
                    handle,
                    "invokeWithArguments",
                    new Object[]{arguments});
        }

        static Object methodType(Object returnType, Object... parameterTypes) throws Exception {
            return invokeStatic(
                    METHOD_TYPE,
                    "methodType",
                    returnType,
                    classArray(parameterTypes));
        }

        static Object classArray(Object... values) throws Exception {
            Object result = newArray(CLASS_TYPE, values.length);
            for (int i = 0; i < values.length; i++) {
                setArray(result, i, values[i]);
            }
            return result;
        }

        static Object newArray(Object componentType, int length) throws Exception {
            return invokeStatic(ARRAY_TYPE, "newInstance", componentType, length);
        }

        static void setArray(Object array, int index, Object value) throws Exception {
            invokeStatic(ARRAY_TYPE, "set", array, index, value);
        }

        static Object proxy(Object interfaceType, Object handler) throws Exception {
            return invokeStatic(
                    PROXY_TYPE,
                    "newProxyInstance",
                    invoke(interfaceType, "getClassLoader"),
                    classArray(interfaceType),
                    handler);
        }

        static String methodName(Object method) throws Exception {
            return (String) invoke(method, "getName");
        }

        static Object readDeclaredStaticField(Object owner, String fieldName)
                throws IllegalAccessException {
            return FieldUtils.readDeclaredStaticField((Class<?>) owner, fieldName, true);
        }
    }
}

