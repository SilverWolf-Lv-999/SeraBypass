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
        Class<?> targetClass = MemberTarget.class;
        Class<?> childClass = MemberChild.class;

        check(jvm.klassVtableLenOffset > 0, "vtable length offset was not resolved");
        check(jvm.instanceKlassNonstaticFieldSizeOffset > 0, "field size offset was not resolved");
        check(jvm.klassLayoutHelperOffset > 0, "layout helper offset was not resolved");

        System.out.println("instanceMembers: offsets checked");
        MemberChild childBefore = new MemberChild();
        check(childBefore.baseValue() == 111, "child baseline failed");

        MemberTarget.staticMarker = new String("static-retained");
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
        check(MemberTarget.staticMarker == "static-retained", "static field value was not retained");

        System.out.println("instanceMembers: field metadata checked");
        MemberTarget target = new MemberTarget();
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
        callbackCount = 0;
        SeraLegitHook.addInstanceMethod(targetClass, "addedPulse", receiver -> {
            if (!(receiver instanceof MemberTarget)) {
                throw new IllegalStateException("injected method received the wrong receiver");
            }
            callbackCount++;
        });
        System.out.println("instanceMembers: method added");
        Method addedPulse = targetClass.getDeclaredMethod("addedPulse");
        int methodVtableLength = vtableLength(targetClass);
        int childMethodVtableLength = vtableLength(childClass);
        check(methodVtableLength > initialVtableLength, "target vtable did not grow");
        check(childMethodVtableLength > initialChildVtableLength, "child vtable did not grow");
        addedPulse.invoke(target);
        MemberChild child = new MemberChild();
        addedPulse.invoke(child);
        check(callbackCount == 2, "reflection did not reach the added virtual method");

        System.out.println("instanceMembers: method reflection checked");
        Class<?> caller = MemberBytecodeCallerFactory.create(
                targetClass, "addedPulse", "addedLong", "addedReference");
        invokeCaller(caller, "invoke", target);
        invokeCaller(caller, "invoke", child);
        check(callbackCount == 4, "INVOKEVIRTUAL did not dispatch the added method");
        invokeCaller(caller, "writeLong", target, 0x1122334455667788L);
        long bytecodeLong = (Long) invokeCaller(caller, "readLong", target);
        check(bytecodeLong == 0x1122334455667788L, "GETFIELD/PUTFIELD did not use added storage");
        Object bytecodeReference = invokeCaller(caller, "readReference", target);
        check(bytecodeReference != null && bytecodeReference == addedReference.get(target),
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
        check(MemberTarget.staticMarker == "static-retained", "static field was lost on removal");

        SeraLegitHook.removeField(targetClass, "addedLong");
        expectNoField(targetClass, "addedLong");
        check(fieldSize(targetClass) == initialFieldSize,
                "nonstatic field size did not return to baseline");
        MemberTarget reduced = new MemberTarget();
        check(reduced.baseValue() == 11, "constructor failed after field removal");

        System.out.println("instanceMembers: removals checked");
        for (int index = 0; index < 3; index++) {
            String fieldName = "cycleField" + index;
            String methodName = "cycleMethod" + index;
            SeraLegitHook.addInstanceField(targetClass, fieldName, int.class, Modifier.PUBLIC);
            Field cycleField = targetClass.getDeclaredField(fieldName);
            MemberTarget cycleTarget = new MemberTarget();
            cycleField.setInt(cycleTarget, 700 + index);
            check(cycleField.getInt(cycleTarget) == 700 + index, "cycle field storage failed");
            check(cycleTarget.baseValue() == 11, "cycle field overlapped existing storage");
            SeraLegitHook.removeField(targetClass, fieldName);
            expectNoField(targetClass, fieldName);

            int beforeMethodVtable = vtableLength(targetClass);
            SeraLegitHook.addInstanceMethod(
                    targetClass, methodName, receiver -> callbackCount++);
            int afterMethodVtable = vtableLength(targetClass);
            check(afterMethodVtable > beforeMethodVtable, "cycle method did not grow the vtable");
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
        Class<?>[] parameterTypes = arguments.length == 0
                ? new Class<?>[]{MemberTarget.class}
                : new Class<?>[]{MemberTarget.class, long.class};
        Object[] values = new Object[arguments.length + 1];
        values[0] = receiver;
        System.arraycopy(arguments, 0, values, 1, arguments.length);
        Method method = caller.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, values);
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

    private static void expectNoMethod(Class<?> type, String name) {
        try {
            type.getDeclaredMethod(name);
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

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
