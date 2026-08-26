package io.github.seraphina.utility.hook;

import io.github.seraphina.utility.jdk.UnsafeUtility;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class SeraLegitHook {

    private static final long RESOLVED_METHOD_NAME_VMTARGET_OFFSET = 16L;
    private static final long METHOD_CONST_METHOD_OFFSET = 8L;
    private static final long METHOD_VTABLE_INDEX_OFFSET = 44L;
    private static final long CONST_METHOD_CONSTANTS_OFFSET = 8L;
    private static final long CONST_METHOD_CODE_SIZE_OFFSET = 32L;
    private static final long CONST_METHOD_NAME_INDEX_OFFSET = 34L;
    private static final long CONST_METHOD_METHOD_IDNUM_OFFSET = 38L;
    private static final long CONST_METHOD_ORIGINAL_METHOD_IDNUM_OFFSET = 46L;
    private static final long CONST_METHOD_CODE_OFFSET = 48L;
    private static final long CONSTANT_POOL_LENGTH_OFFSET = 60L;
    private static final long CONSTANT_POOL_ENTRIES_OFFSET = 72L;
    private static final long ARRAY_LENGTH_OFFSET = 0L;
    private static final long ARRAY_ELEMENTS_OFFSET = 8L;
    private static final long SYMBOL_LENGTH_OFFSET = 4L;
    private static final long SYMBOL_BODY_OFFSET = 6L;
    private static final long CLASS_KLASS_OFFSET = 16L;
    private static final long KLASS_JAVA_MIRROR_OFFSET = 112L;
    private static final long KLASS_SUBKLASS_OFFSET = 128L;
    private static final long KLASS_NEXT_SIBLING_OFFSET = 136L;
    private static final long VTABLE_LAYOUT_SCAN_BYTES = 2048L;
    private static final long INSTANCE_KLASS_LAYOUT_SCAN_BYTES = 4096L;
    private static final long CONSTANT_POOL_HOLDER_SCAN_BYTES = 64L;
    private static final byte NOP = 0x00;
    private static final byte RETURN = (byte) 0xB1;
    private static final int MAX_METHOD_ID = 0xFFFE;
    private static final Runnable NO_OP = () -> {
    };
    private static final Consumer<Object> NO_OP_INSTANCE = value -> {
    };
    private static final AtomicLong NEXT_INJECTED_METHOD_ID = new AtomicLong();
    private static final Map<String, Runnable> INJECTED_METHOD_BODIES = new ConcurrentHashMap<>();
    private static final Map<String, Consumer<Object>> INJECTED_INSTANCE_METHOD_BODIES =
            new ConcurrentHashMap<>();
    private static final Map<String, InjectedMethod> INJECTED_METHODS = new ConcurrentHashMap<>();

    private static volatile boolean initialized;
    private static Class<?> directMethodHandleClass;
    private static long directMethodHandleMemberOffset;
    private static long memberNameResolvedMethodOffset;
    private static ReferenceSlot referenceSlot;
    private static long referenceSlotValueOffset;
    private static long narrowOopBase;
    private static int narrowOopShift;
    private static long vtableStartOffset;
    private static long methodsOffset;
    private static long classReflectionDataOffset;
    private static long metadataAddressPrefix;

    public static void hookMethod(Class<?> klass, String methodName, Object returnValue) {
        Objects.requireNonNull(klass, "klass");
        Objects.requireNonNull(methodName, "methodName");

        for (Method method : resolveMethods(klass, methodName, new Class<?>[0])) {
            replaceBytecodes(method, constantReturn(method.getReturnType(), returnValue));
        }
    }

    /**
     * Replaces a no-argument instance method returning {@code void} with a Java callback.
     * The callback receives the original method receiver.
     */
    public static void hookInstanceVoidMethod(
            Class<?> klass, String methodName, Consumer<Object> methodBody) {
        Objects.requireNonNull(klass, "klass");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(methodBody, "methodBody");

        synchronized (SeraLegitHook.class) {
            initialize();
            Method targetMethod = findMethodInHierarchy(klass, methodName, new Class<?>[0]);
            validateInstanceVoidHookTarget(klass, methodName, targetMethod);

            String callbackId = Long.toUnsignedString(
                    NEXT_INJECTED_METHOD_ID.incrementAndGet(), Character.MAX_RADIX);
            INJECTED_INSTANCE_METHOD_BODIES.put(callbackId, NO_OP_INSTANCE);
            try {
                Class<?> donorClass = createInstanceDonorClass(callbackId, methodName);
                Method donorMethod = donorClass.getDeclaredMethod(methodName);
                Object donor = donorClass.getDeclaredConstructor().newInstance();
                donorMethod.invoke(donor);

                INJECTED_INSTANCE_METHOD_BODIES.put(callbackId, methodBody);
                replaceMethodImplementation(targetMethod, donorMethod);
                INJECTED_METHODS.put(callbackId, new InjectedMethod(donorClass, 0L));
            } catch (Throwable throwable) {
                INJECTED_INSTANCE_METHOD_BODIES.remove(callbackId);
                throw instanceMethodHookFailure(klass, methodName, throwable);
            }
        }
    }

    public static void addMethod(Class<?> klass, String methodName, Runnable methodBody) {
        Objects.requireNonNull(klass, "klass");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(methodBody, "methodBody");

        synchronized (SeraLegitHook.class) {
            initialize();
            validateAddMethodTarget(klass, methodName);

            String callbackId = Long.toUnsignedString(
                    NEXT_INJECTED_METHOD_ID.incrementAndGet(), Character.MAX_RADIX);
            long targetKlassAddress = klassPointer(klass);
            long originalMethodsAddress = 0L;
            long replacementMethodsAddress = 0L;
            long poolHolderAddress = 0L;
            long originalPoolHolder = 0L;
            boolean installed = false;

            try {
                INJECTED_METHOD_BODIES.put(callbackId, NO_OP);
                Class<?> donorClass = createDonorClass(callbackId, methodName);
                Method donorMethod = donorClass.getDeclaredMethod(methodName);
                donorMethod.invoke(null);

                MethodLocation donorLocation = locate(donorMethod);
                verifyMethodName(donorLocation, methodName);

                originalMethodsAddress = UnsafeUtility.UNSAFE.getLong(
                        targetKlassAddress + methodsOffset);
                int originalMethodCount = methodArrayLength(originalMethodsAddress);
                int methodId = nextMethodId(originalMethodsAddress, originalMethodCount);

                long donorKlassAddress = klassPointer(donorClass);
                poolHolderAddress = findConstantPoolHolderAddress(
                        donorLocation.constMethodAddress, donorKlassAddress);
                originalPoolHolder = UnsafeUtility.UNSAFE.getLong(poolHolderAddress);

                UnsafeUtility.UNSAFE.putShort(
                        donorLocation.constMethodAddress + CONST_METHOD_METHOD_IDNUM_OFFSET,
                        (short) methodId);
                UnsafeUtility.UNSAFE.putShort(
                        donorLocation.constMethodAddress + CONST_METHOD_ORIGINAL_METHOD_IDNUM_OFFSET,
                        (short) methodId);
                UnsafeUtility.UNSAFE.putLongVolatile(null, poolHolderAddress, targetKlassAddress);

                replacementMethodsAddress = expandedMethodArray(
                        originalMethodsAddress, donorLocation.methodAddress);
                UnsafeUtility.UNSAFE.putLongVolatile(
                        null, targetKlassAddress + methodsOffset, replacementMethodsAddress);
                installed = true;
                clearReflectionData(klass);

                Method injectedMethod = klass.getDeclaredMethod(methodName);
                verifyInjectedMethod(klass, injectedMethod);

                INJECTED_METHODS.put(
                        callbackId, new InjectedMethod(donorClass, replacementMethodsAddress));
                INJECTED_METHOD_BODIES.put(callbackId, methodBody);
            } catch (Throwable throwable) {
                if (installed) {
                    UnsafeUtility.UNSAFE.putLongVolatile(
                            null, targetKlassAddress + methodsOffset, originalMethodsAddress);
                }
                if (poolHolderAddress != 0L) {
                    UnsafeUtility.UNSAFE.putLongVolatile(
                            null, poolHolderAddress, originalPoolHolder);
                }
                if (replacementMethodsAddress != 0L) {
                    UnsafeUtility.UNSAFE.freeMemory(replacementMethodsAddress);
                }
                clearReflectionData(klass);
                INJECTED_METHOD_BODIES.remove(callbackId);
                throw addMethodFailure(klass, methodName, throwable);
            }
        }
    }

    public static void runInjectedInstance(String callbackId, Object receiver) {
        Consumer<Object> methodBody = INJECTED_INSTANCE_METHOD_BODIES.get(callbackId);
        if (methodBody == null) {
            throw new IllegalStateException("No injected instance method body registered for " + callbackId);
        }
        methodBody.accept(receiver);
    }

    public static void runInjected(String callbackId) {
        Runnable methodBody = INJECTED_METHOD_BODIES.get(callbackId);
        if (methodBody == null) {
            throw new IllegalStateException("No injected method body registered for " + callbackId);
        }
        methodBody.run();
    }

    private static void validateInstanceVoidHookTarget(
            Class<?> klass, String methodName, Method targetMethod) {
        if (targetMethod == null) {
            throw new IllegalArgumentException(
                    "No no-argument method " + methodName + " on " + klass.getName());
        }
        if (Modifier.isStatic(targetMethod.getModifiers())
                || Modifier.isAbstract(targetMethod.getModifiers())
                || Modifier.isNative(targetMethod.getModifiers())
                || targetMethod.getReturnType() != void.class) {
            throw new IllegalArgumentException(
                    "Instance void hook requires a non-native instance method returning void: "
                            + targetMethod);
        }
    }

    private static RuntimeException instanceMethodHookFailure(
            Class<?> klass, String methodName, Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(
                "Could not hook " + klass.getName() + "." + methodName + "()", throwable);
    }

    private static void validateAddMethodTarget(Class<?> klass, String methodName) {
        if (klass.getClassLoader() != null) {
            throw new IllegalArgumentException(
                    "addMethod only supports bootstrap-loaded JDK classes: " + klass.getName());
        }
        if (klass.isArray() || klass.isPrimitive() || klass.isInterface()
                || Modifier.isAbstract(klass.getModifiers())) {
            throw new IllegalArgumentException(
                    "addMethod requires a concrete class: " + klass.getTypeName());
        }
        if (!isAsciiJavaIdentifier(methodName)) {
            throw new IllegalArgumentException(
                    "Method name must be an ASCII Java identifier: " + methodName);
        }

        try {
            klass.getDeclaredMethod(methodName);
            throw new IllegalArgumentException(
                    klass.getName() + " already declares " + methodName + "()");
        } catch (NoSuchMethodException ignored) {
            // The requested signature is free.
        }
    }

    private static boolean isAsciiJavaIdentifier(String value) {
        if (value.isEmpty() || !isAsciiJavaIdentifierStart(value.charAt(0))) {
            return false;
        }

        for (int index = 1; index < value.length(); index++) {
            if (!isAsciiJavaIdentifierPart(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiJavaIdentifierStart(char value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value == '_' || value == '$';
    }

    private static boolean isAsciiJavaIdentifierPart(char value) {
        return isAsciiJavaIdentifierStart(value) || value >= '0' && value <= '9';
    }

    private static Class<?> createDonorClass(String callbackId, String methodName) {
        long donorId = NEXT_INJECTED_METHOD_ID.get();
        String className = SeraLegitHook.class.getPackageName()
                + ".InjectedMethodDonor" + donorId;
        byte[] bytecode = createDonorBytecode(className, methodName, callbackId);
        return new DonorClassLoader(SeraLegitHook.class.getClassLoader()).define(className, bytecode);
    }

    private static Class<?> createInstanceDonorClass(String callbackId, String methodName) {
        long donorId = NEXT_INJECTED_METHOD_ID.get();
        String className = SeraLegitHook.class.getPackageName()
                + ".InjectedInstanceMethodDonor" + donorId;
        byte[] bytecode = createInstanceDonorBytecode(className, methodName, callbackId);
        return new DonorClassLoader(SeraLegitHook.class.getClassLoader()).define(className, bytecode);
    }

    private static byte[] createInstanceDonorBytecode(
            String className, String methodName, String callbackId) {
        String internalClassName = className.replace('.', '/');
        String hookClassName = SeraLegitHook.class.getName().replace('.', '/');

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0xCAFEBABE);
            output.writeShort(0);
            output.writeShort(61);

            output.writeShort(19);
            writeUtf8Constant(output, internalClassName);                 // 1
            writeClassConstant(output, 1);                                // 2
            writeUtf8Constant(output, "java/lang/Object");                // 3
            writeClassConstant(output, 3);                                // 4
            writeUtf8Constant(output, "<init>");                          // 5
            writeUtf8Constant(output, "()V");                             // 6
            writeNameAndTypeConstant(output, 5, 6);                       // 7
            writeMethodReferenceConstant(output, 4, 7);                   // 8
            writeUtf8Constant(output, methodName);                        // 9
            writeUtf8Constant(output, "Code");                            // 10
            writeUtf8Constant(output, "runInjectedInstance");             // 11
            writeUtf8Constant(output, "(Ljava/lang/String;Ljava/lang/Object;)V"); // 12
            writeUtf8Constant(output, hookClassName);                     // 13
            writeClassConstant(output, 13);                               // 14
            writeNameAndTypeConstant(output, 11, 12);                    // 15
            writeMethodReferenceConstant(output, 14, 15);                 // 16
            writeUtf8Constant(output, callbackId);                        // 17
            writeStringConstant(output, 17);                              // 18

            output.writeShort(Modifier.PUBLIC | 0x0020);
            output.writeShort(2);
            output.writeShort(4);
            output.writeShort(0);
            output.writeShort(0);
            output.writeShort(2);

            writeCodeMethod(
                    output,
                    Modifier.PUBLIC,
                    5,
                    6,
                    1,
                    1,
                    new byte[]{0x2A, (byte) 0xB7, 0x00, 0x08, RETURN});
            writeCodeMethod(
                    output,
                    Modifier.PUBLIC,
                    9,
                    6,
                    2,
                    1,
                    new byte[]{
                            0x12, 0x12,
                            0x2A,
                            (byte) 0xB8, 0x00, 0x10,
                            RETURN
                    });

            output.writeShort(0);
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not generate the instance donor class", exception);
        }
    }

    private static byte[] createDonorBytecode(
            String className, String methodName, String callbackId) {
        String internalClassName = className.replace('.', '/');
        String hookClassName = SeraLegitHook.class.getName().replace('.', '/');

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0xCAFEBABE);
            output.writeShort(0);
            output.writeShort(61);

            output.writeShort(19);
            writeUtf8Constant(output, internalClassName);                 // 1
            writeClassConstant(output, 1);                                // 2
            writeUtf8Constant(output, "java/lang/Object");                // 3
            writeClassConstant(output, 3);                                // 4
            writeUtf8Constant(output, "<init>");                          // 5
            writeUtf8Constant(output, "()V");                             // 6
            writeNameAndTypeConstant(output, 5, 6);                       // 7
            writeMethodReferenceConstant(output, 4, 7);                   // 8
            writeUtf8Constant(output, methodName);                        // 9
            writeUtf8Constant(output, "Code");                            // 10
            writeUtf8Constant(output, "runInjected");                     // 11
            writeUtf8Constant(output, "(Ljava/lang/String;)V");           // 12
            writeUtf8Constant(output, hookClassName);                     // 13
            writeClassConstant(output, 13);                               // 14
            writeNameAndTypeConstant(output, 11, 12);                     // 15
            writeMethodReferenceConstant(output, 14, 15);                 // 16
            writeUtf8Constant(output, callbackId);                        // 17
            writeStringConstant(output, 17);                              // 18

            output.writeShort(Modifier.PUBLIC | 0x0020);
            output.writeShort(2);
            output.writeShort(4);
            output.writeShort(0);
            output.writeShort(0);
            output.writeShort(2);

            writeCodeMethod(
                    output,
                    Modifier.PUBLIC,
                    5,
                    6,
                    1,
                    1,
                    new byte[]{0x2A, (byte) 0xB7, 0x00, 0x08, RETURN});
            writeCodeMethod(
                    output,
                    Modifier.PUBLIC | Modifier.STATIC,
                    9,
                    6,
                    1,
                    0,
                    new byte[]{0x12, 0x12, (byte) 0xB8, 0x00, 0x10, RETURN});

            output.writeShort(0);
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not generate the donor class", exception);
        }
    }

    private static void writeUtf8Constant(DataOutputStream output, String value) throws IOException {
        output.writeByte(1);
        output.writeUTF(value);
    }

    private static void writeClassConstant(DataOutputStream output, int nameIndex) throws IOException {
        output.writeByte(7);
        output.writeShort(nameIndex);
    }

    private static void writeNameAndTypeConstant(
            DataOutputStream output, int nameIndex, int descriptorIndex) throws IOException {
        output.writeByte(12);
        output.writeShort(nameIndex);
        output.writeShort(descriptorIndex);
    }

    private static void writeMethodReferenceConstant(
            DataOutputStream output, int classIndex, int nameAndTypeIndex) throws IOException {
        output.writeByte(10);
        output.writeShort(classIndex);
        output.writeShort(nameAndTypeIndex);
    }

    private static void writeStringConstant(DataOutputStream output, int stringIndex)
            throws IOException {
        output.writeByte(8);
        output.writeShort(stringIndex);
    }

    private static void writeCodeMethod(
            DataOutputStream output,
            int access,
            int nameIndex,
            int descriptorIndex,
            int maxStack,
            int maxLocals,
            byte[] code) throws IOException {
        output.writeShort(access);
        output.writeShort(nameIndex);
        output.writeShort(descriptorIndex);
        output.writeShort(1);
        output.writeShort(10);
        output.writeInt(12 + code.length);
        output.writeShort(maxStack);
        output.writeShort(maxLocals);
        output.writeInt(code.length);
        output.write(code);
        output.writeShort(0);
        output.writeShort(0);
    }

    private static int methodArrayLength(long methodsAddress) {
        if (!looksLikeNativePointer(methodsAddress)) {
            throw new IllegalStateException("InstanceKlass does not contain a method array");
        }

        int length = UnsafeUtility.UNSAFE.getInt(methodsAddress + ARRAY_LENGTH_OFFSET);
        if (length < 0 || length > MAX_METHOD_ID) {
            throw new IllegalStateException("Invalid HotSpot method array length " + length);
        }
        return length;
    }

    private static int nextMethodId(long methodsAddress, int methodCount) {
        if (methodCount > MAX_METHOD_ID) {
            throw new IllegalStateException("Target class has too many methods to add another one");
        }

        int candidate = methodCount;
        while (candidate <= MAX_METHOD_ID) {
            boolean used = false;
            for (int index = 0; index < methodCount; index++) {
                long methodAddress = UnsafeUtility.UNSAFE.getLong(
                        methodsAddress + ARRAY_ELEMENTS_OFFSET + (long) index * Long.BYTES);
                long constMethodAddress = UnsafeUtility.UNSAFE.getLong(
                        methodAddress + METHOD_CONST_METHOD_OFFSET);
                int methodId = Short.toUnsignedInt(UnsafeUtility.UNSAFE.getShort(
                        constMethodAddress + CONST_METHOD_METHOD_IDNUM_OFFSET));
                if (methodId == candidate) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return candidate;
            }
            candidate++;
        }
        throw new IllegalStateException("No free HotSpot method id is available");
    }

    private static long findConstantPoolHolderAddress(
            long constMethodAddress, long donorKlassAddress) {
        long constantPoolAddress = UnsafeUtility.UNSAFE.getLong(
                constMethodAddress + CONST_METHOD_CONSTANTS_OFFSET);
        if (!looksLikeNativePointer(constantPoolAddress)) {
            throw new IllegalStateException("Donor method does not contain a ConstantPool");
        }

        long holderAddress = 0L;
        for (long offset = 0L;
             offset <= CONSTANT_POOL_HOLDER_SCAN_BYTES - Long.BYTES;
             offset += Long.BYTES) {
            long candidateAddress = constantPoolAddress + offset;
            if (UnsafeUtility.UNSAFE.getLong(candidateAddress) != donorKlassAddress) {
                continue;
            }
            if (holderAddress != 0L) {
                throw new IllegalStateException(
                        "Could not uniquely locate ConstantPool::_pool_holder");
            }
            holderAddress = candidateAddress;
        }

        if (holderAddress == 0L) {
            throw new IllegalStateException("Could not locate ConstantPool::_pool_holder");
        }
        return holderAddress;
    }

    private static long expandedMethodArray(long originalMethodsAddress, long donorMethodAddress) {
        int originalMethodCount = methodArrayLength(originalMethodsAddress);
        int replacementMethodCount = originalMethodCount + 1;
        Long[] methodAddresses = new Long[replacementMethodCount];
        for (int index = 0; index < originalMethodCount; index++) {
            methodAddresses[index] = UnsafeUtility.UNSAFE.getLong(
                    originalMethodsAddress + ARRAY_ELEMENTS_OFFSET + (long) index * Long.BYTES);
        }
        methodAddresses[originalMethodCount] = donorMethodAddress;
        Arrays.sort(methodAddresses, Comparator.comparingLong(SeraLegitHook::methodNameSymbolAddress));

        long byteCount = ARRAY_ELEMENTS_OFFSET + (long) replacementMethodCount * Long.BYTES;
        long replacementMethodsAddress = UnsafeUtility.UNSAFE.allocateMemory(byteCount);
        UnsafeUtility.UNSAFE.setMemory(replacementMethodsAddress, byteCount, (byte) 0);
        UnsafeUtility.UNSAFE.putInt(
                replacementMethodsAddress + ARRAY_LENGTH_OFFSET, replacementMethodCount);
        for (int index = 0; index < replacementMethodCount; index++) {
            UnsafeUtility.UNSAFE.putLong(
                    replacementMethodsAddress + ARRAY_ELEMENTS_OFFSET + (long) index * Long.BYTES,
                    methodAddresses[index]);
        }
        return replacementMethodsAddress;
    }

    private static long methodNameSymbolAddress(long methodAddress) {
        long constMethodAddress = UnsafeUtility.UNSAFE.getLong(
                methodAddress + METHOD_CONST_METHOD_OFFSET);
        long constantPoolAddress = UnsafeUtility.UNSAFE.getLong(
                constMethodAddress + CONST_METHOD_CONSTANTS_OFFSET);
        int constantPoolLength = UnsafeUtility.UNSAFE.getInt(
                constantPoolAddress + CONSTANT_POOL_LENGTH_OFFSET);
        int nameIndex = Short.toUnsignedInt(UnsafeUtility.UNSAFE.getShort(
                constMethodAddress + CONST_METHOD_NAME_INDEX_OFFSET));
        if (nameIndex <= 0 || nameIndex >= constantPoolLength) {
            throw new IllegalStateException("Method has an invalid ConstantPool name index");
        }

        long symbolAddress = UnsafeUtility.UNSAFE.getLong(
                constantPoolAddress + CONSTANT_POOL_ENTRIES_OFFSET + (long) nameIndex * Long.BYTES);
        if (!looksLikeNativePointer(symbolAddress)) {
            throw new IllegalStateException("Method name does not resolve to a HotSpot Symbol");
        }
        return symbolAddress;
    }

    private static void verifyMethodName(MethodLocation location, String expectedMethodName) {
        long symbolAddress = methodNameSymbolAddress(location.methodAddress);
        int length = Short.toUnsignedInt(UnsafeUtility.UNSAFE.getShort(
                symbolAddress + SYMBOL_LENGTH_OFFSET));
        if (length != expectedMethodName.length()) {
            throw new IllegalStateException("Unsupported HotSpot Symbol layout");
        }

        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = UnsafeUtility.UNSAFE.getByte(symbolAddress + SYMBOL_BODY_OFFSET + index);
        }
        String actualMethodName = new String(bytes, StandardCharsets.UTF_8);
        if (!expectedMethodName.equals(actualMethodName)) {
            throw new IllegalStateException("Unsupported HotSpot ConstantPool layout");
        }
    }

    private static void verifyInjectedMethod(Class<?> targetClass, Method injectedMethod) {
        if (injectedMethod.getDeclaringClass() != targetClass
                || injectedMethod.getReturnType() != void.class
                || injectedMethod.getParameterCount() != 0
                || !Modifier.isPublic(injectedMethod.getModifiers())
                || !Modifier.isStatic(injectedMethod.getModifiers())) {
            throw new IllegalStateException(
                    "JVM did not expose the injected method as "
                            + targetClass.getName() + "." + injectedMethod.getName() + "()");
        }
    }

    private static void clearReflectionData(Class<?> klass) {
        UnsafeUtility.UNSAFE.putObjectVolatile(klass, classReflectionDataOffset, null);
    }

    private static RuntimeException addMethodFailure(
            Class<?> klass, String methodName, Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(
                "Could not add " + klass.getName() + "." + methodName + "()", throwable);
    }

    private static boolean looksLikeNativePointer(long address) {
        return address >= 0x1_0000L && (address & (Long.BYTES - 1L)) == 0L;
    }

    private static boolean isCurrentMetadataAddress(long address) {
        return looksLikeNativePointer(address)
                && (address >>> Integer.SIZE) == metadataAddressPrefix;
    }

    public static void replaceMethod(Class<?> target, Class<?> now) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(now, "now");

        if (target == now) {
            return;
        }
        if (!target.isAssignableFrom(now)) {
            throw new IllegalArgumentException(
                    now.getName() + " must extend or implement " + target.getName());
        }
        if (target.isInterface()) {
            throw new IllegalArgumentException(
                    "Interface method replacement is not supported: " + target.getName());
        }
        int replacedCount = 0;
        for (Method replacement : now.getDeclaredMethods()) {
            Method targetMethod = findMatchingMethodInHierarchy(target, replacement);
            if (targetMethod == null) {
                continue;
            }
            if (Modifier.isAbstract(replacement.getModifiers())
                    || Modifier.isNative(replacement.getModifiers())) {
                throw new IllegalArgumentException(
                        "Replacement method must contain Java bytecode: " + replacement);
            }
            if (Modifier.isAbstract(targetMethod.getModifiers())
                    || Modifier.isNative(targetMethod.getModifiers())) {
                throw new IllegalArgumentException(
                        "Target method must contain Java bytecode: " + targetMethod);
            }

            if (Modifier.isStatic(targetMethod.getModifiers())
                    || Modifier.isPrivate(targetMethod.getModifiers())
                    || Modifier.isFinal(targetMethod.getModifiers())) {
                for (Method method : resolveMethods(target, targetMethod)) {
                    replaceMethodImplementation(method, replacement);
                }
            } else {
                replaceVirtualMethod(target, targetMethod, replacement);
            }
            replacedCount++;
        }

        if (replacedCount == 0) {
            throw new IllegalArgumentException(
                    now.getName() + " does not declare a replaceable method from " + target.getName());
        }
    }

    public static void hookVoidMethod(Class<?> klass, String methodName, byte[] bytes) {
        hookVoidMethod(klass, methodName, bytes, new Class<?>[0]);
    }

    public static void hookVoidMethod(
            Class<?> klass, String methodName, Class<?>... parameterTypes) {
        hookVoidMethod(klass, methodName, new byte[]{RETURN}, parameterTypes);
    }

    public static void hookVoidMethod(
            Class<?> klass, String methodName, byte[] bytes, Class<?>... parameterTypes) {
        Objects.requireNonNull(klass, "klass");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(parameterTypes, "parameterTypes");

        if (bytes.length == 0) {
            throw new IllegalArgumentException("Replacement bytecode must not be empty");
        }

        for (Method method : resolveMethods(klass, methodName, parameterTypes)) {
            if (method.getReturnType() != void.class) {
                throw new IllegalArgumentException(method + " does not return void");
            }
            replaceBytecodes(method, bytes.clone());
        }
    }

    private static List<Method> resolveMethods(
            Class<?> klass, String methodName, Class<?>[] parameterTypes) {
        Set<Method> methods = new LinkedHashSet<>();
        Method method = findMethodInHierarchy(klass, methodName, parameterTypes);
        if (method == null) {
            throw new IllegalArgumentException(
                    "No method " + methodName + Arrays.toString(parameterTypes)
                            + " on " + klass.getName());
        }
        methods.add(method);

        for (Class<?> subclass : findLoadedSubclasses(klass)) {
            for (Method declaredMethod : subclass.getDeclaredMethods()) {
                if (declaredMethod.getName().equals(methodName)
                        && Arrays.equals(declaredMethod.getParameterTypes(), parameterTypes)) {
                    makeAccessible(declaredMethod);
                    methods.add(declaredMethod);
                }
            }
        }
        return new ArrayList<>(methods);
    }

    private static List<Method> resolveMethods(Class<?> klass, Method template) {
        Set<Method> methods = new LinkedHashSet<>();
        methods.add(template);

        for (Class<?> subclass : findLoadedSubclasses(klass)) {
            Method method = findMatchingDeclaredMethod(subclass, template);
            if (method != null) {
                makeAccessible(method);
                methods.add(method);
            }
        }
        return new ArrayList<>(methods);
    }

    private static Method findMethodInHierarchy(
            Class<?> klass, String methodName, Class<?>[] parameterTypes) {
        for (Class<?> current = klass; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(methodName, parameterTypes);
                makeAccessible(method);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Keep looking through the superclass hierarchy.
            }
        }
        return null;
    }

    private static Method findMatchingDeclaredMethod(Class<?> klass, Method template) {
        for (Method method : klass.getDeclaredMethods()) {
            if (!method.getName().equals(template.getName())
                    || method.getReturnType() != template.getReturnType()
                    || Modifier.isStatic(method.getModifiers())
                    != Modifier.isStatic(template.getModifiers())
                    || !Arrays.equals(method.getParameterTypes(), template.getParameterTypes())) {
                continue;
            }
            makeAccessible(method);
            return method;
        }
        return null;
    }

    private static Method findMatchingMethodInHierarchy(Class<?> klass, Method template) {
        for (Class<?> current = klass; current != null; current = current.getSuperclass()) {
            Method method = findMatchingDeclaredMethod(current, template);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private static void makeAccessible(Method method) {
        if (!method.trySetAccessible()) {
            throw new IllegalArgumentException("Cannot access method " + method);
        }
    }

    private static List<Class<?>> findLoadedSubclasses(Class<?> klass) {
        try {
            initialize();
            long rootKlass = klassPointer(klass);
            if (rootKlass == 0L) {
                throw new IllegalStateException("Class does not contain a Klass*");
            }

            Deque<Long> pending = new ArrayDeque<>();
            Set<Long> visited = new HashSet<>();
            Set<Class<?>> subclasses = new LinkedHashSet<>();
            pending.addLast(rootKlass);
            visited.add(rootKlass);

            while (!pending.isEmpty()) {
                long parentKlass = pending.removeFirst();
                Set<Long> siblings = new HashSet<>();
                long childKlass = UnsafeUtility.UNSAFE.getLong(parentKlass + KLASS_SUBKLASS_OFFSET);
                while (childKlass != 0L) {
                    if (!siblings.add(childKlass)) {
                        throw new IllegalStateException("Cycle in HotSpot Klass sibling chain");
                    }

                    if (visited.add(childKlass)) {
                        Class<?> subclass = classMirror(childKlass);
                        if (subclass == null || !klass.isAssignableFrom(subclass)) {
                            throw new IllegalStateException(
                                    "Klass child does not resolve to a subclass of " + klass.getName());
                        }
                        subclasses.add(subclass);
                        pending.addLast(childKlass);
                    }

                    childKlass = UnsafeUtility.UNSAFE.getLong(childKlass + KLASS_NEXT_SIBLING_OFFSET);
                }
            }
            return new ArrayList<>(subclasses);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not traverse HotSpot Klass subclasses for " + klass.getName(), exception);
        }
    }

    private static void replaceBytecodes(Method method, byte[] replacement) {
        MethodLocation location;
        try {
            initialize();
            location = locate(method);
        } catch (Exception e) {
            throw new IllegalStateException("Could not locate bytecodes for " + method, e);
        }

        if (replacement.length > location.codeSize) {
            throw new IllegalArgumentException(
                    method + " has " + location.codeSize + " bytes of code, but replacement needs "
                            + replacement.length);
        }

        try {
            // Keep the allocated code size unchanged. Trailing NOPs make a
            // shorter replacement safe after its terminal return instruction.
            for (int i = replacement.length; i < location.codeSize; i++) {
                UnsafeUtility.UNSAFE.putByte(location.codeAddress + i, NOP);
            }
            for (int i = replacement.length - 1; i >= 0; i--) {
                UnsafeUtility.UNSAFE.putByte(location.codeAddress + i, replacement[i]);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not replace bytecodes for " + method, e);
        }
    }

    private static void replaceMethodImplementation(Method target, Method replacement) {
        try {
            initialize();
            MethodLocation targetLocation = locate(target);
            MethodLocation replacementLocation = locate(replacement);
            UnsafeUtility.UNSAFE.putLong(
                    targetLocation.methodAddress + METHOD_CONST_METHOD_OFFSET,
                    replacementLocation.constMethodAddress);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not replace " + target + " with " + replacement, e);
        }
    }

    private static void replaceVirtualMethod(
            Class<?> targetClass, Method targetMethod, Method replacement) {
        try {
            initialize();
            long replacementMethodAddress = locate(replacement).methodAddress;
            if (!replaceVtableEntry(targetClass, targetMethod, replacementMethodAddress)) {
                throw new IllegalStateException(
                        "Target vtable slot is not initialized for "
                                + targetClass.getName() + "." + targetMethod.getName());
            }

            for (Class<?> subclass : findLoadedSubclasses(targetClass)) {
                replaceVtableEntry(subclass, targetMethod, replacementMethodAddress);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not replace virtual method " + targetMethod
                            + " with " + replacement, e);
        }
    }

    private static boolean replaceVtableEntry(
            Class<?> klass, Method method, long replacementMethodAddress) {
        MethodLocation methodLocation;
        try {
            methodLocation = locate(method);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not locate " + method, e);
        }

        long klassAddress = klassPointer(klass);
        int vtableIndex = UnsafeUtility.UNSAFE.getInt(
                methodLocation.methodAddress + METHOD_VTABLE_INDEX_OFFSET);
        if (vtableIndex < 0) {
            throw new IllegalStateException(
                    "Method does not have a virtual dispatch slot: " + method);
        }

        long vtableEntryAddress = klassAddress + vtableStartOffset
                + (long) vtableIndex * Long.BYTES;
        if (UnsafeUtility.UNSAFE.getLong(vtableEntryAddress) == 0L) {
            // A class may be visible from the HotSpot subclass chain before linking
            // initializes its vtable. It will inherit the patched parent slot when linked.
            return false;
        }
        UnsafeUtility.UNSAFE.putLongVolatile(
                null, vtableEntryAddress, replacementMethodAddress);
        return true;
    }

    private static long findVtableEntry(long klassAddress, long methodAddress) {
        long vtableEntryAddress = 0L;
        for (long offset = 0L;
             offset <= VTABLE_LAYOUT_SCAN_BYTES - Long.BYTES;
             offset += Long.BYTES) {
            long candidateAddress = klassAddress + offset;
            if (UnsafeUtility.UNSAFE.getLong(candidateAddress) != methodAddress) {
                continue;
            }
            if (vtableEntryAddress != 0L) {
                throw new IllegalStateException("Method appears in multiple vtable entries");
            }
            vtableEntryAddress = candidateAddress;
        }

        if (vtableEntryAddress == 0L) {
            throw new IllegalStateException("Could not find vtable entry");
        }
        return vtableEntryAddress;
    }

    private static byte[] constantReturn(Class<?> returnType, Object value) {
        if (returnType == void.class) {
            throw new IllegalArgumentException("Use hookVoidMethod for void methods");
        }

        if (returnType == boolean.class) {
            if (!(value instanceof Boolean)) {
                throw incompatibleReturnValue(returnType, value);
            }
            return new byte[]{(byte) ((Boolean) value ? 0x04 : 0x03), (byte) 0xAC};
        }

        if (returnType == byte.class) {
            if (!(value instanceof Byte)) {
                throw incompatibleReturnValue(returnType, value);
            }
            return intReturn(((Byte) value).intValue());
        }

        if (returnType == short.class) {
            if (!(value instanceof Short)) {
                throw incompatibleReturnValue(returnType, value);
            }
            return intReturn(((Short) value).intValue());
        }

        if (returnType == char.class) {
            if (!(value instanceof Character)) {
                throw incompatibleReturnValue(returnType, value);
            }
            return intReturn(((Character) value).charValue());
        }

        if (returnType == int.class) {
            if (!(value instanceof Integer)) {
                throw incompatibleReturnValue(returnType, value);
            }
            return intReturn((Integer) value);
        }

        if (returnType == long.class) {
            if (!(value instanceof Long)) {
                throw incompatibleReturnValue(returnType, value);
            }
            long result = (Long) value;
            if (result == 0L) return new byte[]{0x09, (byte) 0xAD};
            if (result == 1L) return new byte[]{0x0A, (byte) 0xAD};
            throw unsupportedConstant(returnType, value);
        }

        if (returnType == float.class) {
            if (!(value instanceof Float)) {
                throw incompatibleReturnValue(returnType, value);
            }
            float result = (Float) value;
            if (Float.floatToRawIntBits(result) == Float.floatToRawIntBits(0.0F)) {
                return new byte[]{0x0B, (byte) 0xAE};
            }
            if (result == 1.0F) return new byte[]{0x0C, (byte) 0xAE};
            if (result == 2.0F) return new byte[]{0x0D, (byte) 0xAE};
            throw unsupportedConstant(returnType, value);
        }

        if (returnType == double.class) {
            if (!(value instanceof Double)) {
                throw incompatibleReturnValue(returnType, value);
            }
            double result = (Double) value;
            if (Double.doubleToRawLongBits(result) == Double.doubleToRawLongBits(0.0D)) {
                return new byte[]{0x0E, (byte) 0xAF};
            }
            if (result == 1.0D) return new byte[]{0x0F, (byte) 0xAF};
            throw unsupportedConstant(returnType, value);
        }

        if (value == null) {
            return new byte[]{0x01, (byte) 0xB0};
        }
        throw unsupportedConstant(returnType, value);
    }

    private static byte[] intReturn(int value) {
        if (value >= -1 && value <= 5) {
            int opcode = value == -1 ? 0x02 : 0x03 + value;
            return new byte[]{(byte) opcode, (byte) 0xAC};
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new byte[]{0x10, (byte) value, (byte) 0xAC};
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new byte[]{
                    0x11,
                    (byte) (value >>> 8),
                    (byte) value,
                    (byte) 0xAC
            };
        }
        throw unsupportedConstant(int.class, value);
    }

    private static IllegalArgumentException incompatibleReturnValue(Class<?> returnType, Object value) {
        String actualType = value == null ? "null" : value.getClass().getName();
        return new IllegalArgumentException(
                "Cannot return " + actualType + " from " + returnType.getTypeName());
    }

    private static IllegalArgumentException unsupportedConstant(Class<?> returnType, Object value) {
        return new IllegalArgumentException(
                "Cannot encode " + value + " as a " + returnType.getTypeName()
                        + " constant without changing the target constant pool");
    }

    private static void initialize() {
        if (initialized) {
            return;
        }

        synchronized (SeraLegitHook.class) {
            if (initialized) {
                return;
            }

            try {
                verifyRuntime();
                directMethodHandleClass = Class.forName("java.lang.invoke.DirectMethodHandle");
                Class<?> memberName = Class.forName("java.lang.invoke.MemberName");

                Field directMember = declaredField(directMethodHandleClass, "member");
                Field resolvedMethod = declaredField(memberName, "method");
                Field referenceValue = declaredField(ReferenceSlot.class, "value");
                Field reflectionData = declaredField(Class.class, "reflectionData");
                directMethodHandleMemberOffset = UnsafeUtility.UNSAFE.objectFieldOffset(directMember);
                memberNameResolvedMethodOffset = UnsafeUtility.UNSAFE.objectFieldOffset(resolvedMethod);
                referenceSlot = new ReferenceSlot();
                referenceSlotValueOffset = UnsafeUtility.UNSAFE.objectFieldOffset(referenceValue);
                classReflectionDataOffset = UnsafeUtility.UNSAFE.objectFieldOffset(reflectionData);
                NarrowOopEncoding encoding = deriveNarrowOopEncoding();
                narrowOopBase = encoding.base;
                narrowOopShift = encoding.shift;
                verifyKlassLayout();
                verifyLayout();
                verifyVtableLayout();
                verifyMethodTableLayout();
                initialized = true;
            } catch (Exception e) {
                throw new IllegalStateException("JDK 17 HotSpot Unsafe hook is unavailable", e);
            }
        }
    }

    private static void verifyRuntime() {
        if (!"17".equals(System.getProperty("java.specification.version"))) {
            throw new IllegalStateException(
                    "SeraphinaHook.addMethod only supports JDK 17, found "
                            + System.getProperty("java.specification.version"));
        }
        String vmName = System.getProperty("java.vm.name", "");
        boolean hotSpotVm = vmName.contains("HotSpot")
                || vmName.contains("OpenJDK") && vmName.contains("Server VM");
        if (!"64".equals(System.getProperty("sun.arch.data.model")) || !hotSpotVm) {
            throw new IllegalStateException("SeraphinaHook only supports 64-bit HotSpot");
        }
    }

    private static void verifyVtableLayout() throws Exception {
        Class<?> probeClass = VtableLayoutProbe.class;
        Method first = probeClass.getDeclaredMethod("first");
        Method second = probeClass.getDeclaredMethod("second");
        Method third = probeClass.getDeclaredMethod("third");
        makeAccessible(first);
        makeAccessible(second);
        makeAccessible(third);

        MethodLocation firstLocation = locate(first);
        long klassAddress = klassPointer(probeClass);
        int firstVtableIndex = UnsafeUtility.UNSAFE.getInt(
                firstLocation.methodAddress + METHOD_VTABLE_INDEX_OFFSET);
        if (firstVtableIndex < 0) {
            throw new IllegalStateException("Virtual probe method does not have a vtable index");
        }

        long vtableEntry = findVtableEntry(klassAddress, firstLocation.methodAddress);
        long startOffset = vtableEntry - klassAddress
                - (long) firstVtableIndex * Long.BYTES;
        if (startOffset < 0L || startOffset >= VTABLE_LAYOUT_SCAN_BYTES) {
            throw new IllegalStateException("Unsupported HotSpot vtable start offset");
        }

        verifyVtableEntry(klassAddress, startOffset, second);
        verifyVtableEntry(klassAddress, startOffset, third);
        vtableStartOffset = startOffset;
    }

    private static void verifyVtableEntry(
            long klassAddress, long startOffset, Method method) throws Exception {
        MethodLocation location = locate(method);
        int vtableIndex = UnsafeUtility.UNSAFE.getInt(
                location.methodAddress + METHOD_VTABLE_INDEX_OFFSET);
        if (vtableIndex < 0L
                || UnsafeUtility.UNSAFE.getLong(
                        klassAddress + startOffset + (long) vtableIndex * Long.BYTES)
                != location.methodAddress) {
            throw new IllegalStateException("Unsupported HotSpot Method/vtable layout");
        }
    }

    private static void verifyMethodTableLayout() throws Exception {
        Class<?> probeClass = MethodTableLayoutProbe.class;
        Set<Long> expectedMethods = new HashSet<>();
        for (Method method : probeClass.getDeclaredMethods()) {
            expectedMethods.add(locate(method).methodAddress);
        }
        for (Constructor<?> constructor : probeClass.getDeclaredConstructors()) {
            expectedMethods.add(locate(constructor).methodAddress);
        }
        metadataAddressPrefix = expectedMethods.iterator().next() >>> Integer.SIZE;

        long probeKlassAddress = klassPointer(probeClass);
        long resolvedMethodsOffset = 0L;
        for (long offset = 0L;
             offset <= INSTANCE_KLASS_LAYOUT_SCAN_BYTES - Long.BYTES;
             offset += Long.BYTES) {
            long methodArrayAddress = UnsafeUtility.UNSAFE.getLong(probeKlassAddress + offset);
            if (!isCurrentMetadataAddress(methodArrayAddress)) {
                continue;
            }

            int methodCount = UnsafeUtility.UNSAFE.getInt(methodArrayAddress + ARRAY_LENGTH_OFFSET);
            if (methodCount != expectedMethods.size()) {
                continue;
            }

            Set<Long> actualMethods = new HashSet<>();
            boolean matches = true;
            for (int index = 0; index < methodCount; index++) {
                long methodAddress = UnsafeUtility.UNSAFE.getLong(
                        methodArrayAddress + ARRAY_ELEMENTS_OFFSET + (long) index * Long.BYTES);
                if (!expectedMethods.contains(methodAddress) || !actualMethods.add(methodAddress)) {
                    matches = false;
                    break;
                }
            }
            if (!matches || actualMethods.size() != expectedMethods.size()) {
                continue;
            }
            if (resolvedMethodsOffset != 0L) {
                throw new IllegalStateException("Could not uniquely locate InstanceKlass::_methods");
            }
            resolvedMethodsOffset = offset;
        }

        if (resolvedMethodsOffset == 0L) {
            throw new IllegalStateException("Could not locate InstanceKlass::_methods");
        }
        methodsOffset = resolvedMethodsOffset;
    }

    private static void verifyLayout() throws Exception {
        Method probe = SeraLegitHook.class.getDeclaredMethod("layoutProbe");
        makeAccessible(probe);
        MethodLocation location = locate(probe);
        if (location.codeSize != 2
                || UnsafeUtility.UNSAFE.getByte(location.codeAddress) != 0x03
                || UnsafeUtility.UNSAFE.getByte(location.codeAddress + 1) != (byte) 0xAC) {
            throw new IllegalStateException("Unsupported HotSpot Method/ConstMethod layout");
        }
    }

    private static long klassPointer(Class<?> klass) {
        return UnsafeUtility.UNSAFE.getLong(klass, CLASS_KLASS_OFFSET);
    }

    private static long rawJavaMirror(long klassPointer) {
        if (klassPointer == 0L) {
            throw new IllegalStateException("Klass pointer is null");
        }

        long mirrorHandle = UnsafeUtility.UNSAFE.getLong(klassPointer + KLASS_JAVA_MIRROR_OFFSET);
        if (mirrorHandle == 0L) {
            throw new IllegalStateException("Klass does not contain a java mirror handle");
        }

        long rawMirror = UnsafeUtility.UNSAFE.getLong(mirrorHandle);
        if (rawMirror == 0L) {
            throw new IllegalStateException("Klass java mirror is null");
        }
        return rawMirror;
    }

    private static Class<?> classMirror(long klassPointer) {
        long rawMirror = rawJavaMirror(klassPointer);
        int narrowMirror = encodeNarrowOop(rawMirror);
        Object mirror;

        synchronized (referenceSlot) {
            try {
                UnsafeUtility.UNSAFE.putIntVolatile(
                        referenceSlot, referenceSlotValueOffset, narrowMirror);
                mirror = referenceSlot.value;
            } finally {
                referenceSlot.value = null;
            }
        }

        if (!(mirror instanceof Class<?>)) {
            throw new IllegalStateException("Klass java mirror is not a Class instance");
        }
        return (Class<?>) mirror;
    }

    private static NarrowOopEncoding deriveNarrowOopEncoding() {
        long parentMirror = rawJavaMirror(klassPointer(KlassLayoutParent.class));
        long childMirror = rawJavaMirror(klassPointer(KlassLayoutChild.class));
        long parentNarrow = narrowOop(KlassLayoutParent.class);
        long childNarrow = narrowOop(KlassLayoutChild.class);
        long rawDelta = childMirror - parentMirror;
        long narrowDelta = childNarrow - parentNarrow;

        if (rawDelta == 0L || narrowDelta == 0L || rawDelta % narrowDelta != 0L) {
            throw new IllegalStateException("Could not derive compressed OOP encoding");
        }

        long scale = rawDelta / narrowDelta;
        if (scale <= 0L || Long.bitCount(scale) != 1) {
            throw new IllegalStateException("Unsupported compressed OOP scale " + scale);
        }

        int shift = Long.numberOfTrailingZeros(scale);
        long base = parentMirror - (parentNarrow << shift);
        return new NarrowOopEncoding(base, shift);
    }

    private static void verifyKlassLayout() {
        long parentKlass = klassPointer(KlassLayoutParent.class);
        long childKlass = klassPointer(KlassLayoutChild.class);
        long firstChild = UnsafeUtility.UNSAFE.getLong(parentKlass + KLASS_SUBKLASS_OFFSET);
        if (parentKlass == 0L || childKlass == 0L || firstChild != childKlass) {
            throw new IllegalStateException("Unsupported HotSpot Klass subclass layout");
        }

        if (classMirror(childKlass) != KlassLayoutChild.class) {
            throw new IllegalStateException("Unsupported HotSpot Klass java mirror layout");
        }
    }

    private static int encodeNarrowOop(long rawOop) {
        long delta = rawOop - narrowOopBase;
        long alignmentMask = (1L << narrowOopShift) - 1L;
        if (delta < 0L || (delta & alignmentMask) != 0L) {
            throw new IllegalStateException("OOP does not match the compressed OOP encoding");
        }

        long narrowOop = delta >>> narrowOopShift;
        if (narrowOop > 0xFFFF_FFFFL) {
            throw new IllegalStateException("OOP does not fit in a compressed reference");
        }
        return (int) narrowOop;
    }

    private static long narrowOop(Object value) {
        synchronized (referenceSlot) {
            try {
                referenceSlot.value = value;
                return Integer.toUnsignedLong(
                        UnsafeUtility.UNSAFE.getIntVolatile(referenceSlot, referenceSlotValueOffset));
            } finally {
                referenceSlot.value = null;
            }
        }
    }

    private static MethodLocation locate(Method method) throws IllegalAccessException {
        return locate((Executable) method);
    }

    private static MethodLocation locate(Constructor<?> constructor) throws IllegalAccessException {
        return locate((Executable) constructor);
    }

    private static MethodLocation locate(Executable executable) throws IllegalAccessException {
        MethodHandle methodHandle;
        if (executable instanceof Method method) {
            methodHandle = UnsafeUtility.TRUSTED_LOOKUP.unreflect(method);
        } else if (executable instanceof Constructor<?> constructor) {
            methodHandle = UnsafeUtility.TRUSTED_LOOKUP.unreflectConstructor(constructor);
        } else {
            throw new IllegalArgumentException("Unsupported executable " + executable);
        }
        if (!directMethodHandleClass.isInstance(methodHandle)) {
            throw new IllegalStateException(
                    "Expected DirectMethodHandle, got " + methodHandle.getClass().getName());
        }

        Object member = UnsafeUtility.UNSAFE.getObject(methodHandle, directMethodHandleMemberOffset);
        if (member == null) {
            throw new IllegalStateException("DirectMethodHandle does not contain a MemberName");
        }

        Object resolvedMethod = UnsafeUtility.UNSAFE.getObject(member, memberNameResolvedMethodOffset);
        if (resolvedMethod == null) {
            throw new IllegalStateException("MemberName does not contain a ResolvedMethodName");
        }

        long methodPointer = UnsafeUtility.UNSAFE.getLong(
                resolvedMethod, RESOLVED_METHOD_NAME_VMTARGET_OFFSET);
        if (methodPointer == 0L) {
            throw new IllegalStateException("ResolvedMethodName does not contain a Method*");
        }

        long constMethod = UnsafeUtility.UNSAFE.getLong(methodPointer + METHOD_CONST_METHOD_OFFSET);
        if (constMethod == 0L) {
            throw new IllegalArgumentException(executable + " has no ConstMethod");
        }

        int codeSize = UnsafeUtility.UNSAFE.getShort(constMethod + CONST_METHOD_CODE_SIZE_OFFSET)
                & 0xFFFF;
        if (codeSize == 0) {
            throw new IllegalArgumentException(executable + " has no Java bytecode");
        }

        return new MethodLocation(
                methodPointer,
                constMethod,
                constMethod + CONST_METHOD_CODE_OFFSET,
                codeSize);
    }

    private static Field declaredField(Class<?> type, String fieldName) throws Exception {
        return type.getDeclaredField(fieldName);
    }

    private static int layoutProbe() {
        return 0;
    }

    private static class KlassLayoutParent {
    }

    private static final class KlassLayoutChild extends KlassLayoutParent {
    }

    private static class VtableLayoutProbe {
        int first() {
            return 1;
        }

        int second() {
            return 2;
        }

        int third() {
            return 3;
        }
    }

    private static class MethodTableLayoutProbe {
        static void first() {
        }

        static void second() {
        }

        static void third() {
        }
    }

    private static final class ReferenceSlot {
        private volatile Object value;
    }

    private static final class DonorClassLoader extends ClassLoader {
        private DonorClassLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String className, byte[] bytecode) {
            return defineClass(className, bytecode, 0, bytecode.length);
        }
    }

    private record InjectedMethod(Class<?> donorClass, long methodArrayAddress) {
    }

    private record MethodLocation(
            long methodAddress, long constMethodAddress, long codeAddress, int codeSize) {
    }

    private record NarrowOopEncoding(long base, int shift) {
    }

}


