package io.github.seraphina.test.member;

import io.github.seraphina.jnct.api.JVM;
import io.github.seraphina.utility.UnsafeUtility;
import io.github.seraphina.utility.hook.SeraLegitHook;
import sun.misc.Unsafe;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class InstanceMemberTest {
    private static int callbackCount;

    public static void run() throws Throwable {
        System.out.println("instanceMembers: start");
        JVM jvm = JVM.getInstance();
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        Class<?> constantPoolTarget = ConstantPoolTarget.class;
        check(ConstantPoolTarget.warm("before") == 6, "constant-pool warmup failed");
        check(ConstantPoolTarget.resolveAfterExpansion("before") == 2, "invokevirtual warmup failed");
        ConstantPoolTarget oldConstantPoolInstance = new ConstantPoolTarget();
        check(oldConstantPoolInstance.existingVirtual() == 6, "old constant-pool instance baseline failed");
        long constantPoolKlass = unsafe.getLong(
                constantPoolTarget, jvm.classKlassOffset);
        long originalConstantPool = unsafe.getLong(
                constantPoolKlass + jvm.klassConstantsOffset);
        long constantPoolCache = unsafe.getLong(
                originalConstantPool + jvm.constantPoolCacheOffset);
        check(constantPoolCache != 0L, "target ConstantPool has no ConstantPoolCache");
        check(unsafe.getLong(
                        constantPoolCache + jvm.constantPoolCacheConstantPoolOffset)
                        == originalConstantPool,
                "ConstantPoolCache does not point to the target ConstantPool");
        int constantPoolCacheLength = unsafe.getInt(
                constantPoolCache + jvm.constantPoolCacheLengthOffset);
        SeraLegitHook.addStaticField(
                constantPoolTarget, "backingAlias", "backing", Modifier.PUBLIC | Modifier.STATIC);
        long expandedConstantPool = unsafe.getLong(
                constantPoolKlass + jvm.klassConstantsOffset);
        long expandedConstantPoolCache = unsafe.getLong(
                expandedConstantPool + jvm.constantPoolCacheOffset);
        check(expandedConstantPool != originalConstantPool,
                "constant pool did not expand for the added field name");
        check(expandedConstantPoolCache == constantPoolCache,
                "constant pool expansion replaced the ConstantPoolCache");
        check(unsafe.getLong(
                        constantPoolCache + jvm.constantPoolCacheConstantPoolOffset)
                        == expandedConstantPool,
                "ConstantPoolCache still references the old ConstantPool");
        check(unsafe.getInt(
                        constantPoolCache + jvm.constantPoolCacheLengthOffset)
                        == constantPoolCacheLength,
                "UTF-8-only constant-pool expansion changed the cache length");
        check(ConstantPoolTarget.resolveAfterExpansion("before") == 2,
                "invokevirtual resolution failed after constant-pool expansion");
        Field backingAlias = constantPoolTarget.getDeclaredField("backingAlias");
        check(backingAlias.get(null).equals("initial"), "static field alias did not read backing storage");
        backingAlias.set(null, "updated");
        check(ConstantPoolTarget.backing.equals("updated"),
                "static field alias did not write backing storage");
        SeraLegitHook.removeField(constantPoolTarget, "backingAlias");
        check(unsafe.getLong(
                        constantPoolCache + jvm.constantPoolCacheConstantPoolOffset)
                        == unsafe.getLong(constantPoolKlass + jvm.klassConstantsOffset),
                "ConstantPoolCache diverged after removing the alias field");
        Class<?> targetClass = MemberTarget.class;
        Class<?> childClass = MemberChild.class;
        Class<?> resolvedCaller = MemberBytecodeCallerFactory.createExistingMemberCaller(targetClass);
        Method resolvedReadInt = resolvedCaller.getDeclaredMethod("readExistingInt", targetClass);
        Method resolvedInvokeBase = resolvedCaller.getDeclaredMethod("invokeBaseValue", targetClass);
        Object resolvedTarget = new MemberTarget();
        check((Integer) resolvedReadInt.invoke(null, resolvedTarget) == 11,
                "preloaded GETFIELD baseline failed");
        check((Integer) resolvedInvokeBase.invoke(null, resolvedTarget) == 11,
                "preloaded INVOKEVIRTUAL baseline failed");
        for (int index = 0; index < 20000; index++) {
            resolvedReadInt.invoke(null, resolvedTarget);
            resolvedInvokeBase.invoke(null, resolvedTarget);
        }

        check(jvm.klassVtableLenOffset > 0, "vtable length offset was not resolved");
        check(jvm.instanceKlassNonstaticFieldSizeOffset > 0, "field size offset was not resolved");
        check(jvm.klassLayoutHelperOffset > 0, "layout helper offset was not resolved");

        System.out.println("instanceMembers: offsets checked");
        MemberChild childBefore = new MemberChild();
        check(childBefore.baseValue() == 111, "child baseline failed");
        String retainedStaticMarker = new String("static-retained");
        MemberTarget.staticMarker = retainedStaticMarker;
        Field originalInt = targetClass.getDeclaredField("existingInt");
        Field originalLong = targetClass.getDeclaredField("existingLong");
        long originalIntOffset = unsafe.objectFieldOffset(originalInt);
        long originalLongOffset = unsafe.objectFieldOffset(originalLong);
        int initialFieldSize = fieldSize(targetClass);
        int initialLayoutSize = layoutSize(targetClass);
        int initialVtableLength = vtableLength(targetClass);
        int initialChildVtableLength = vtableLength(childClass);

        System.out.println("instanceMembers: adding fields");
        SeraLegitHook.addInstanceField(targetClass, "addedLong", long.class, Modifier.PUBLIC);
        SeraLegitHook.addInstanceField(targetClass, "addedReference", Object.class, Modifier.PUBLIC);

        System.out.println("instanceMembers: fields added");
        Field addedLong = targetClass.getDeclaredField("addedLong");
        Field addedReference = targetClass.getDeclaredField("addedReference");
        long addedLongOffset = unsafe.objectFieldOffset(addedLong);
        long addedReferenceOffset = unsafe.objectFieldOffset(addedReference);
        check(addedLong.getType() == long.class, "added long field type mismatch");
        check(addedReference.getType() == Object.class, "added reference field type mismatch");
        check(addedLongOffset != originalIntOffset && addedLongOffset != originalLongOffset,
                "added long field aliases an existing field");
        check(addedReferenceOffset != originalIntOffset
                        && addedReferenceOffset != originalLongOffset
                        && addedReferenceOffset != addedLongOffset,
                "added reference field aliases an existing field");

        int expandedFieldSize = fieldSize(targetClass);
        int expandedLayoutSize = layoutSize(targetClass);
        check(expandedFieldSize > initialFieldSize, "nonstatic field size did not grow");
        check(expandedLayoutSize > initialLayoutSize, "instance size did not grow");
        check(MemberTarget.staticMarker == retainedStaticMarker, "static field value was not retained");

        System.out.println("instanceMembers: field metadata checked");
        Class<?> targetFactory = MemberBytecodeCallerFactory.createInstanceFactory(targetClass);
        Object target = targetFactory.getDeclaredMethod("instantiate").invoke(null);
        check((Integer) resolvedReadInt.invoke(null, target) == 11,
                "preloaded GETFIELD did not follow the replacement Klass");
        check((Integer) resolvedInvokeBase.invoke(null, target) == 11,
                "preloaded INVOKEVIRTUAL did not follow the replacement Klass");
        long allocatedKlass = jvm.compressedKlasses
                ? jvm.narrowKlassBase
                        + ((unsafe.getInt(target, jvm.objectKlassOffset) & 0xFFFF_FFFFL)
                                << jvm.narrowKlassShift)
                : unsafe.getLong(target, jvm.objectKlassOffset);
        long currentKlass = unsafe.getLong(targetClass, jvm.classKlassOffset);
        System.out.println(
                "instanceMembers: allocated klass=0x" + Long.toHexString(allocatedKlass)
                        + " current klass=0x" + Long.toHexString(currentKlass));
        check(allocatedKlass == currentKlass, "new instance retained stale Klass metadata");
        check(originalInt.getInt(target) == 11, "existing int changed after field addition");
        check(originalLong.getLong(target) == 13L, "existing long changed after field addition");
        addedLong.setLong(target, 0x1020304050607080L);
        Object referenceValue = new byte[8192];
        WeakReference<Object> weakReference = new WeakReference<>(referenceValue);
        addedReference.set(target, referenceValue);
        referenceValue = null;
        forceGc();
        check(weakReference.get() != null, "reference field was not marked by GC");
        check(addedLong.getLong(target) == 0x1020304050607080L,
                "added long field changed during GC");
        check(originalInt.getInt(target) == 11, "existing field overlapped added storage");

        System.out.println("instanceMembers: field storage checked");
        Class<?> caller = MemberBytecodeCallerFactory.create(
                targetClass, "addedPulse", "addedLong", "addedReference");
        callbackCount = 0;
        SeraLegitHook.addInstanceMethod(targetClass, "addedPulse", receiver -> {
            if (!targetClass.isInstance(receiver)) {
                throw new IllegalStateException("injected method received the wrong receiver");
            }
            callbackCount++;
        });
        System.out.println("instanceMembers: method added");
        Method addedPulse = targetClass.getDeclaredMethod("addedPulse");
        target = MemberBytecodeCallerFactory.createInstanceFactory(targetClass)
                .getDeclaredMethod("instantiate").invoke(null);
        int methodVtableLength = vtableLength(targetClass);
        int childMethodVtableLength = vtableLength(childClass);
        check(methodVtableLength > initialVtableLength, "target vtable did not grow");
        check(childMethodVtableLength > initialChildVtableLength, "child vtable did not grow");
        addedPulse.invoke(target);
        Class<?> childFactory = MemberBytecodeCallerFactory.createInstanceFactory(childClass);
        Object child = childFactory.getDeclaredMethod("instantiate").invoke(null);
        addedPulse.invoke(child);
        check(callbackCount == 2, "reflection did not reach the added virtual method");

        System.out.println("instanceMembers: method reflection checked");
        invokeCaller(caller, "invoke", target);
        invokeCaller(caller, "invoke", child);
        check(callbackCount == 4, "INVOKEVIRTUAL did not dispatch the added method");
        SeraLegitHook.addInstanceMethod(
                targetClass,
                "addedTotal",
                long.class,
                new Class<?>[]{long.class, int.class, String.class},
                (receiver, values) -> (Long) values[0] + (Integer) values[1]
                        + ((String) values[2]).length());
        Object totalTarget = MemberBytecodeCallerFactory.createInstanceFactory(targetClass)
                .getDeclaredMethod("instantiate").invoke(null);
        Class<?> totalCaller = MemberBytecodeCallerFactory.createVirtualCaller(
                targetClass, "addedTotal", long.class, long.class, int.class, String.class);
        check((Long) invokeCaller(totalCaller, "invoke", totalTarget, 40L, 2, "abc") == 45L,
                "parameterized INVOKEVIRTUAL did not dispatch the callback");
        check((Long) targetClass.getDeclaredMethod(
                        "addedTotal", long.class, int.class, String.class)
                        .invoke(totalTarget, 40L, 2, "abc") == 45L,
                "reflection did not invoke the parameterized method");
        SeraLegitHook.removeMethod(targetClass, "addedTotal", long.class, int.class, String.class);
        expectNoMethod(targetClass, "addedTotal", long.class, int.class, String.class);
        target = MemberBytecodeCallerFactory.createInstanceFactory(targetClass)
                .getDeclaredMethod("instantiate").invoke(null);
        invokeCaller(caller, "writeLong", target, 0x1122334455667788L);
        long bytecodeLong = (Long) invokeCaller(caller, "readLong", target);
        check(bytecodeLong == 0x1122334455667788L, "GETFIELD/PUTFIELD did not use added storage");
        Object bytecodeReferenceValue = new Object();
        addedReference.set(target, bytecodeReferenceValue);
        Object bytecodeReference = invokeCaller(caller, "readReference", target);
        check(bytecodeReference == bytecodeReferenceValue,
                "reference GETFIELD did not read added storage");
        check(Class.forName(targetClass.getName(), false, targetClass.getClassLoader()) == targetClass,
                "dictionary lookup no longer returns the target class");
        check(targetClass.getSuperclass() == Object.class, "target superclass changed");
        check(childClass.getSuperclass() == targetClass, "child superclass changed");

        SeraLegitHook.removeMethod(targetClass, "addedPulse");
        expectNoMethod(targetClass, "addedPulse");
        check(vtableLength(targetClass) == initialVtableLength, "target vtable did not shrink");
        check(vtableLength(childClass) == initialChildVtableLength, "child vtable did not shrink");

        SeraLegitHook.removeField(targetClass, "addedReference");
        expectNoField(targetClass, "addedReference");
        check(fieldSize(targetClass) < expandedFieldSize,
                "nonstatic field size did not shrink after reference removal");
        check(MemberTarget.staticMarker == retainedStaticMarker, "static field was lost on removal");

        SeraLegitHook.removeField(targetClass, "addedLong");
        expectNoField(targetClass, "addedLong");
        check(fieldSize(targetClass) == initialFieldSize,
                "nonstatic field size did not return to baseline");
        Object reduced = targetClass.getDeclaredConstructor().newInstance();
        check((Integer) targetClass.getDeclaredMethod("baseValue").invoke(reduced) == 11,
                "constructor failed after field removal");

        System.out.println("instanceMembers: removals checked");
        for (int index = 0; index < 3; index++) {
            String fieldName = "cycleField" + index;
            String methodName = "cycleMethod" + index;
            SeraLegitHook.addInstanceField(targetClass, fieldName, int.class, Modifier.PUBLIC);
            Field cycleField = targetClass.getDeclaredField(fieldName);
            Object cycleTarget = MemberBytecodeCallerFactory.createInstanceFactory(targetClass)
                    .getDeclaredMethod("instantiate").invoke(null);
            cycleField.setInt(cycleTarget, 700 + index);
            check(cycleField.getInt(cycleTarget) == 700 + index, "cycle field storage failed");
            check((Integer) targetClass.getDeclaredMethod("baseValue").invoke(cycleTarget) == 11,
                    "cycle field overlapped existing storage");
            SeraLegitHook.removeField(targetClass, fieldName);
            expectNoField(targetClass, fieldName);

            int beforeMethodVtable = vtableLength(targetClass);
            SeraLegitHook.addInstanceMethod(
                    targetClass, methodName, receiver -> callbackCount++);
            int afterMethodVtable = vtableLength(targetClass);
            check(afterMethodVtable > beforeMethodVtable, "cycle method did not grow the vtable");
            cycleTarget = MemberBytecodeCallerFactory.createInstanceFactory(targetClass)
                    .getDeclaredMethod("instantiate").invoke(null);
            cycleField.setInt(cycleTarget, 700 + index);
            targetClass.getDeclaredMethod(methodName).invoke(cycleTarget);
            SeraLegitHook.removeMethod(targetClass, methodName);
            expectNoMethod(targetClass, methodName);
            check(vtableLength(targetClass) == beforeMethodVtable,
                    "cycle method did not restore the vtable");
        }

        check(fieldSize(targetClass) == initialFieldSize, "field layout drifted after cycles");
        check(vtableLength(targetClass) == initialVtableLength, "vtable drifted after cycles");
        check(Class.forName(targetClass.getName(), false, targetClass.getClassLoader()) == targetClass,
                "dictionary lookup failed after cycles");
        System.out.println(
                "instanceMembers OK fields=" + initialFieldSize + "->" + expandedFieldSize
                        + " size=" + initialLayoutSize + "->" + expandedLayoutSize
                        + " vtable=" + initialVtableLength + "->" + methodVtableLength);
    }

    private static Object invokeCaller(Class<?> caller, String name, Object receiver, Object... arguments)
            throws Exception {
        Method invoke = null;
        for (Method candidate : caller.getDeclaredMethods()) {
            if (candidate.getName().equals(name)) {
                invoke = candidate;
                break;
            }
        }
        if (invoke == null) {
            throw new NoSuchMethodException(name);
        }
        Object[] values = new Object[arguments.length + 1];
        values[0] = receiver;
        System.arraycopy(arguments, 0, values, 1, arguments.length);
        invoke.setAccessible(true);
        return invoke.invoke(null, values);
    }
    private static int vtableLength(Class<?> type) {
        JVM jvm = JVM.getInstance();
        long klass = UnsafeUtility.UNSAFE.getLong(type, jvm.classKlassOffset);
        return UnsafeUtility.UNSAFE.getInt(klass + jvm.klassVtableLenOffset);
    }

    private static int fieldSize(Class<?> type) {
        JVM jvm = JVM.getInstance();
        long klass = UnsafeUtility.UNSAFE.getLong(type, jvm.classKlassOffset);
        return UnsafeUtility.UNSAFE.getInt(
                klass + jvm.instanceKlassNonstaticFieldSizeOffset);
    }

    private static int layoutSize(Class<?> type) {
        JVM jvm = JVM.getInstance();
        long klass = UnsafeUtility.UNSAFE.getLong(type, jvm.classKlassOffset);
        int layout = UnsafeUtility.UNSAFE.getInt(klass + jvm.klassLayoutHelperOffset);
        return layout & 0xFFFF;
    }

    private static void expectNoField(Class<?> type, String name) {
        try {
            type.getDeclaredField(name);
            throw new IllegalStateException("field still visible: " + name);
        } catch (NoSuchFieldException expected) {
        }
    }

    private static void expectNoMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            type.getDeclaredMethod(name, parameterTypes);
            throw new IllegalStateException("method still visible: " + name);
        } catch (NoSuchMethodException expected) {
        }
    }

    private static void forceGc() throws InterruptedException {
        for (int index = 0; index < 4; index++) {
            System.gc();
            System.runFinalization();
            byte[] pressure = new byte[1 << 20];
            pressure[0] = (byte) index;
            Thread.sleep(20L);
        }
    }

    private static final class ConstantPoolTarget {
        private static String backing = "initial";

        private static int warm(String value) {
            return value.length();
        }

        private static int resolveAfterExpansion(String value) {
            return value.indexOf('f');
        }

        private int existingVirtual() {
            return 6;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
