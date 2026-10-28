package io.github.seraphina.utility.hook;

import io.github.seraphina.jnct.api.JVM;
import io.github.seraphina.test.member.MemberBytecodeCallerFactory;
import io.github.seraphina.utility.UnsafeUtility;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReference;

import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeraLegitHookInstanceTest {
    @Test
    void addsIndependentInstanceFieldAndRemovesIt() throws Exception {
        Class<?> targetClass = InstanceFieldTarget.class;
        Field backingField = targetClass.getDeclaredField("backing");
        Object original = newCurrentInstance(targetClass);
        assertEquals(17, backingField.getInt(original));

        SeraLegitHook.addInstanceField(
                targetClass, "added", int.class, Modifier.PRIVATE | Modifier.TRANSIENT);

        Field addedField = targetClass.getDeclaredField("added");
        assertFalse(Modifier.isStatic(addedField.getModifiers()));
        Object target = newCurrentInstance(targetClass);
        assertEquals(0, addedField.getInt(target));
        addedField.setInt(target, 29);
        assertEquals(17, backingField.getInt(target));

        SeraLegitHook.removeField(targetClass, "added");
        assertThrows(NoSuchFieldException.class, () -> targetClass.getDeclaredField("added"));
        assertEquals(17, backingField.getInt(newCurrentInstance(targetClass)));
    }

    @Test
    void keepsConstantPoolCacheAlignedWhenAppendingUtf8() throws Exception {
        Class<?> targetClass = ConstantPoolTarget.class;
        assertEquals(6, ConstantPoolTarget.warm("before"));
        assertEquals(2, ConstantPoolTarget.resolveAfterExpansion("before"));
        ConstantPoolTarget oldInstance = new ConstantPoolTarget();
        assertEquals(6, oldInstance.existingVirtual());

        JVM jvm = JVM.getInstance();
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        long klass = unsafe.getLong(targetClass, jvm.classKlassOffset);
        long originalPool = unsafe.getLong(klass + jvm.klassConstantsOffset);
        long cache = unsafe.getLong(originalPool + jvm.constantPoolCacheOffset);
        assertNotEquals(0L, cache);
        assertEquals(originalPool,
                unsafe.getLong(cache + jvm.constantPoolCacheConstantPoolOffset));
        int cacheLength = unsafe.getInt(cache + jvm.constantPoolCacheLengthOffset);

        SeraLegitHook.addStaticField(
                targetClass, "backingAlias", "backing", Modifier.PUBLIC | Modifier.STATIC);

        long expandedPool = unsafe.getLong(klass + jvm.klassConstantsOffset);
        assertNotEquals(originalPool, expandedPool);
        assertEquals(cache, unsafe.getLong(expandedPool + jvm.constantPoolCacheOffset));
        assertEquals(expandedPool,
                unsafe.getLong(cache + jvm.constantPoolCacheConstantPoolOffset));
        assertEquals(cacheLength, unsafe.getInt(cache + jvm.constantPoolCacheLengthOffset));
        assertEquals(2, ConstantPoolTarget.resolveAfterExpansion("before"));
        Class<?> virtualCaller = MemberBytecodeCallerFactory.createVirtualCaller(
                targetClass, "existingVirtual");
        assertEquals(6, virtualCaller.getDeclaredMethod("invoke", targetClass).invoke(null, oldInstance));
        assertEquals(6, oldInstance.existingVirtual());

        Field backingAlias = targetClass.getDeclaredField("backingAlias");
        assertEquals("initial", backingAlias.get(null));
        backingAlias.set(null, "updated");
        assertEquals("updated", ConstantPoolTarget.backing);
        SeraLegitHook.removeField(targetClass, "backingAlias");
        assertEquals(unsafe.getLong(klass + jvm.klassConstantsOffset),
                unsafe.getLong(cache + jvm.constantPoolCacheConstantPoolOffset));
    }

    @Test
    void addsInvokesAndRemovesInstanceMethod() throws Exception {
        Class<?> targetClass = InstanceMethodTarget.class;
        Field valueField = targetClass.getDeclaredField("value");
        Method originalMethod = targetClass.getDeclaredMethod("original");
        AtomicReference<Object> receiver = new AtomicReference<>();
        Object baseline = newCurrentInstance(targetClass);
        assertEquals(3, originalMethod.invoke(baseline));

        SeraLegitHook.addInstanceMethod(targetClass, "added", value -> {
            receiver.set(value);
            try {
                valueField.setInt(value, valueField.getInt(value) + 10);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        });

        Method addedMethod = targetClass.getDeclaredMethod("added");
        assertFalse(Modifier.isStatic(addedMethod.getModifiers()));
        Object target = newCurrentInstance(targetClass);
        addedMethod.invoke(target);
        assertSame(target, receiver.get());
        assertEquals(13, originalMethod.invoke(target));

        SeraLegitHook.removeMethod(targetClass, "added");
        assertThrows(NoSuchMethodException.class, () -> targetClass.getDeclaredMethod("added"));
        assertEquals(3, originalMethod.invoke(newCurrentInstance(targetClass)));
    }

    @Test
    void addsParameterizedMethodsWithPrimitiveAndReferenceArguments() throws Exception {
        Class<?> targetClass = ParameterizedMethodTarget.class;
        Class<?>[] parameterTypes = {
                boolean.class, byte.class, char.class, short.class, int.class,
                long.class, float.class, double.class, String.class
        };
        AtomicReference<Object> receiver = new AtomicReference<>();
        SeraLegitHook.addInstanceMethod(
                targetClass,
                "combine",
                String.class,
                parameterTypes,
                (instance, arguments) -> {
                    receiver.set(instance);
                    return arguments[0] + "|" + arguments[1] + "|" + arguments[2]
                            + "|" + arguments[3] + "|" + arguments[4] + "|" + arguments[5]
                            + "|" + arguments[6] + "|" + arguments[7] + "|" + arguments[8];
                });

        Object target = newCurrentInstance(targetClass);
        Class<?> caller = MemberBytecodeCallerFactory.createVirtualCaller(
                targetClass, "combine", String.class, parameterTypes);
        Object[] arguments = {true, (byte) 2, 'A', (short) 3, 5, 7L, 1.5F, 2.5D, "ok"};
        Method virtualInvoke = caller.getDeclaredMethod(
                "invoke",
                targetClass,
                boolean.class,
                byte.class,
                char.class,
                short.class,
                int.class,
                long.class,
                float.class,
                double.class,
                String.class);
        Object[] invokeArguments = new Object[arguments.length + 1];
        invokeArguments[0] = target;
        System.arraycopy(arguments, 0, invokeArguments, 1, arguments.length);
        assertEquals("true|2|A|3|5|7|1.5|2.5|ok", virtualInvoke.invoke(null, invokeArguments));
        assertSame(target, receiver.get());
        assertEquals(
                "true|2|A|3|5|7|1.5|2.5|ok",
                targetClass.getDeclaredMethod("combine", parameterTypes).invoke(target, arguments));
        AtomicReference<Object> overloadReceiver = new AtomicReference<>();
        SeraLegitHook.addInstanceMethod(
                targetClass, "combine", instance -> overloadReceiver.set(instance));
        Object overloadTarget = newCurrentInstance(targetClass);
        targetClass.getDeclaredMethod("combine").invoke(overloadTarget);
        assertSame(overloadTarget, overloadReceiver.get());
        SeraLegitHook.removeMethod(targetClass, "combine");

        SeraLegitHook.addInstanceMethod(
                targetClass,
                "sum",
                long.class,
                new Class<?>[]{long.class, int.class},
                (instance, values) -> (Long) values[0] + (Integer) values[1]);
        Class<?> sumCaller = MemberBytecodeCallerFactory.createVirtualCaller(
                targetClass, "sum", long.class, long.class, int.class);
        Method sumInvoke = sumCaller.getDeclaredMethod("invoke", targetClass, long.class, int.class);
        target = newCurrentInstance(targetClass);
        assertEquals(42L, sumInvoke.invoke(null, target, 40L, 2));
        SeraLegitHook.removeMethod(targetClass, "combine", parameterTypes);
        SeraLegitHook.removeMethod(targetClass, "sum", long.class, int.class);
        assertThrows(NoSuchMethodException.class,
                () -> targetClass.getDeclaredMethod("combine", parameterTypes));
        assertThrows(NoSuchMethodException.class,
                () -> targetClass.getDeclaredMethod("sum", long.class, int.class));
    }
    @Test
    void keepsOtherAddedInstanceMethodsWhenOneIsRemoved() throws Exception {
        Class<?> targetClass = MultipleMethodTarget.class;
        Field valueField = targetClass.getDeclaredField("value");
        SeraLegitHook.addInstanceMethod(targetClass, "firstAdded", value -> add(valueField, value, 1));
        SeraLegitHook.addInstanceMethod(targetClass, "secondAdded", value -> add(valueField, value, 2));

        Object target = newCurrentInstance(targetClass);
        targetClass.getDeclaredMethod("firstAdded").invoke(target);
        targetClass.getDeclaredMethod("secondAdded").invoke(target);
        assertEquals(3, valueField.getInt(target));

        SeraLegitHook.removeMethod(targetClass, "firstAdded");
        assertThrows(NoSuchMethodException.class, () -> targetClass.getDeclaredMethod("firstAdded"));
        Object currentTarget = newCurrentInstance(targetClass);
        targetClass.getDeclaredMethod("secondAdded").invoke(currentTarget);
        assertEquals(2, valueField.getInt(currentTarget));
    }

    @Test
    void removesOriginalInstanceMembers() throws Exception {
        Class<?> targetClass = RemovalTarget.class;
        Field removableField = targetClass.getDeclaredField("removable");
        Object target = newCurrentInstance(targetClass);
        assertEquals(31, removableField.getInt(target));
        targetClass.getDeclaredMethod("original").invoke(target);

        SeraLegitHook.removeMethod(targetClass, "original");
        assertThrows(NoSuchMethodException.class, () -> targetClass.getDeclaredMethod("original"));

        SeraLegitHook.removeField(targetClass, "removable");
        assertThrows(NoSuchFieldException.class, () -> targetClass.getDeclaredField("removable"));
    }

    @Test
    void expandsConstantPoolAndReplacesInstanceMembers() throws Exception {
        Class<?> targetClass = MultiFieldTarget.class;
        Field intBacking = targetClass.getDeclaredField("intBacking");
        Field longBacking = targetClass.getDeclaredField("longBacking");
        Field objectBacking = targetClass.getDeclaredField("objectBacking");

        SeraLegitHook.addInstanceField(targetClass, "intAdded", int.class,
                Modifier.PRIVATE | Modifier.TRANSIENT);
        SeraLegitHook.addInstanceField(targetClass, "longAdded", long.class,
                Modifier.PRIVATE | Modifier.TRANSIENT);
        SeraLegitHook.addInstanceField(targetClass, "objectAdded", Object.class,
                Modifier.PRIVATE | Modifier.TRANSIENT);

        Field intAdded = targetClass.getDeclaredField("intAdded");
        Field longAdded = targetClass.getDeclaredField("longAdded");
        Field objectAdded = targetClass.getDeclaredField("objectAdded");
        Object target = newCurrentInstance(targetClass);
        assertEquals(0, intAdded.getInt(target));
        assertEquals(0L, longAdded.getLong(target));
        assertEquals(null, objectAdded.get(target));

        intAdded.setInt(target, 17);
        longAdded.setLong(target, 19L);
        Object backingValue = new Object();
        objectBacking.set(target, backingValue);
        Object replacement = new Object();
        objectAdded.set(target, replacement);
        assertEquals(11, intBacking.getInt(target));
        assertEquals(13L, longBacking.getLong(target));
        assertSame(backingValue, objectBacking.get(target));
        assertSame(replacement, objectAdded.get(target));

        SeraLegitHook.addInstanceMethod(targetClass, "dynamicMethod",
                value -> add(intBacking, value, 5));
        Object methodTarget = newCurrentInstance(targetClass);
        intBacking.setInt(methodTarget, 17);
        targetClass.getDeclaredMethod("dynamicMethod").invoke(methodTarget);
        assertEquals(22, intBacking.getInt(methodTarget));

        SeraLegitHook.removeMethod(targetClass, "dynamicMethod");
        assertThrows(NoSuchMethodException.class, () -> targetClass.getDeclaredMethod("dynamicMethod"));
        SeraLegitHook.addInstanceMethod(targetClass, "dynamicMethod",
                value -> add(intBacking, value, 7));
        Object replacementMethodTarget = newCurrentInstance(targetClass);
        intBacking.setInt(replacementMethodTarget, 22);
        targetClass.getDeclaredMethod("dynamicMethod").invoke(replacementMethodTarget);
        assertEquals(29, intBacking.getInt(replacementMethodTarget));
        SeraLegitHook.removeMethod(targetClass, "dynamicMethod");

        SeraLegitHook.addInstanceField(targetClass, "fieldAgain", int.class,
                Modifier.PRIVATE | Modifier.TRANSIENT);
        SeraLegitHook.removeField(targetClass, "fieldAgain");
        assertThrows(NoSuchFieldException.class, () -> targetClass.getDeclaredField("fieldAgain"));
        SeraLegitHook.addInstanceField(targetClass, "fieldAgain", int.class,
                Modifier.PRIVATE | Modifier.TRANSIENT);
        Object readdedFieldTarget = newCurrentInstance(targetClass);
        assertEquals(0, targetClass.getDeclaredField("fieldAgain").getInt(readdedFieldTarget));

        SeraLegitHook.removeField(targetClass, "objectAdded");
        SeraLegitHook.removeField(targetClass, "longAdded");
        SeraLegitHook.removeField(targetClass, "intAdded");
        SeraLegitHook.removeField(targetClass, "fieldAgain");
        assertThrows(NoSuchFieldException.class, () -> targetClass.getDeclaredField("intAdded"));
    }

    private static Object newCurrentInstance(Class<?> targetClass) throws Exception {
        Class<?> factory = MemberBytecodeCallerFactory.createInstanceFactory(targetClass);
        return factory.getDeclaredMethod("instantiate").invoke(null);
    }

    private static void add(Field field, Object receiver, int increment) {
        try {
            field.setInt(receiver, field.getInt(receiver) + increment);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
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

    private static final class InstanceFieldTarget {
        private int backing = 17;
    }

    private static final class InstanceMethodTarget {
        private int value = 3;

        private int original() {
            return value;
        }
    }

    private static final class ParameterizedMethodTarget {
        private int value = 4;
    }
    private static final class MultipleMethodTarget {
        private int value;
    }

    private static final class RemovalTarget {
        private int removable = 31;

        private void original() {
        }
    }

    private static final class MultiFieldTarget {
        private int intBacking = 11;
        private long longBacking = 13L;
        private Object objectBacking = new Object();
    }
}
