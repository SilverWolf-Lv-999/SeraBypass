package io.github.seraphina.reflect;

import io.github.seraphina.utility.UnsafeUtility;
import sun.misc.Unsafe;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Creates lambda-backed accessors for fields and methods.
 *
 * <p>Reflection is used only while resolving and creating an accessor. The
 * returned object invokes a generated lambda on every subsequent call.</p>
 */
@SuppressWarnings("removal")
public final class LambdaManager {
    private static final Unsafe UNSAFE = UnsafeUtility.UNSAFE;
    private static final MethodHandles.Lookup LAMBDA_LOOKUP = MethodHandles.lookup();
    private static final MethodType OBJECT_GETTER = MethodType.methodType(Object.class);
    private static final MethodType OBJECT_SETTER = MethodType.methodType(void.class, Object.class);
    private static final MethodType OBJECT_INVOKER = MethodType.methodType(Object.class);
    private static final MethodType VOID_INVOKER = MethodType.methodType(void.class);

    private static final ConcurrentMap<FieldKey, LambdaField<?>> FIELD_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<MethodKey, LambdaMethod<?>> METHOD_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<Method, MethodFactory> METHOD_FACTORIES =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<FieldFactoryKey, FieldFactory> FIELD_FACTORIES =
            new ConcurrentHashMap<>();


    /** Returns an accessor for a static field. */
    public static <T> LambdaField<T> getField(Class<?> clazz, String fieldName) {
        return getField(clazz, fieldName, null);
    }

    /**
     * Returns an accessor for a field on {@code instance}. Static fields ignore
     * {@code instance} and use one shared cached accessor.
     */
    @SuppressWarnings("unchecked")
    public static <T> LambdaField<T> getField(
            Class<?> clazz, String fieldName, Object instance) {
        Objects.requireNonNull(clazz, "clazz");
        Objects.requireNonNull(fieldName, "fieldName");

        Field field = findField(clazz, fieldName);
        boolean isStatic = Modifier.isStatic(field.getModifiers());
        if (!isStatic && instance == null) {
            throw new IllegalArgumentException(
                    "An instance is required for non-static field " + field);
        }
        if (!isStatic && !field.getDeclaringClass().isInstance(instance)) {
            throw new IllegalArgumentException(
                    "Instance type " + instance.getClass().getName()
        }
        Object target = isStatic ? null : instance;
        return (LambdaField<T>) FIELD_CACHE.computeIfAbsent(
                new FieldKey(field, target), ignored -> createField(field, target));
    }

    /**
     * Returns a no-argument lambda accessor for one method invocation. The
     * receiver and arguments are captured when this method returns.
     */
    @SuppressWarnings("unchecked")
    public static <T> LambdaMethod<T> getMethod(
            Class<?> clazz, String methodName, Object instance, Object... arguments) {
        Objects.requireNonNull(clazz, "clazz");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(arguments, "arguments");

        MethodSelection selection = findMethod(clazz, methodName, arguments);
        Method method = selection.method();
        boolean isStatic = Modifier.isStatic(method.getModifiers());
        if (!isStatic && instance == null) {
            throw new IllegalArgumentException(
                    "An instance is required for non-static method " + method);
        }
        Object target = isStatic ? null : instance;
        Object[] capturedArguments = selection.arguments();
        MethodKey key = new MethodKey(method, target, selection.keyArguments());
        return (LambdaMethod<T>) METHOD_CACHE.computeIfAbsent(
                key, ignored -> createMethod(method, target, capturedArguments));
    }

    /** Drops generated accessors and their reusable lambda factories. */
    public static void clearCache() {
        FIELD_CACHE.clear();
        METHOD_CACHE.clear();
        METHOD_FACTORIES.clear();
        FIELD_FACTORIES.clear();
    }

    static RuntimeException rethrow(Throwable throwable) {
        LambdaManager.<RuntimeException>throwUnchecked(throwable);
        return null;
    }

    static void checkFieldValue(Class<?> fieldType, Object value) {
        if (value == null) {
            if (fieldType.isPrimitive()) {
                throw new NullPointerException(
                        "Cannot assign null to primitive field " + fieldType.getName());
            }
            return;
        }
        if (!box(fieldType).isInstance(value)) {
            throw new IllegalArgumentException(
                    "Cannot assign " + value.getClass().getName()
                            + " to field of type " + fieldType.getName());
        }
    }

    private static <T> LambdaField<T> createField(Field field, Object instance) {
        try {
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            Object base = isStatic ? UNSAFE.staticFieldBase(field) : instance;
            long offset = isStatic
                    ? UNSAFE.staticFieldOffset(field)
                    : UNSAFE.objectFieldOffset(field);
            FieldFactory factory = FIELD_FACTORIES.computeIfAbsent(
                    new FieldFactoryKey(field.getType(), Modifier.isVolatile(field.getModifiers())),
                    LambdaManager::createFieldFactory);
            LambdaField.Getter getter = (LambdaField.Getter)
                    factory.getter().invokeWithArguments(base, offset);
            LambdaField.Setter setter = (LambdaField.Setter)
                    factory.setter().invokeWithArguments(base, offset);
            return new LambdaField<>(getter, setter, field.getType());
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Could not create lambda accessor for field " + field, throwable);
        }
    }

    private static LambdaMethod<?> createMethod(
            Method method, Object instance, Object[] arguments) {
        try {
            MethodFactory factory = METHOD_FACTORIES.computeIfAbsent(
                    method, LambdaManager::createMethodFactory);
            boolean isStatic = Modifier.isStatic(method.getModifiers());
            Object[] captures = new Object[arguments.length + (isStatic ? 0 : 1)];
            int argumentStart = 0;
            if (!isStatic) {
                captures[0] = instance;
                argumentStart = 1;
            }
            System.arraycopy(arguments, 0, captures, argumentStart, arguments.length);
            Object invoker = factory.factory().invokeWithArguments(captures);
            if (method.getReturnType() == void.class) {
                return new LambdaMethod<>((LambdaMethod.VoidInvoker) invoker);
            }
            return new LambdaMethod<>((LambdaMethod.ReturnInvoker) invoker);
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Could not create lambda accessor for method " + method, throwable);
        }
    }

    private static FieldFactory createFieldFactory(FieldFactoryKey key) {
        try {
            MethodHandle getterImplementation = LAMBDA_LOOKUP.findStatic(
                    LambdaManager.class,
                    getterName(key.type(), key.isVolatile()),
                    MethodType.methodType(Object.class, Object.class, long.class));
            MethodHandle setterImplementation = LAMBDA_LOOKUP.findStatic(
                    LambdaManager.class,
                    setterName(key.type(), key.isVolatile()),
                    MethodType.methodType(void.class, Object.class, long.class, Object.class));

            MethodHandle getterFactory = LambdaMetafactory.metafactory(
                    LAMBDA_LOOKUP,
                    "get",
                    MethodType.methodType(LambdaField.Getter.class, Object.class, long.class),
                    OBJECT_GETTER,
                    getterImplementation,
                    OBJECT_GETTER).getTarget();
            MethodHandle setterFactory = LambdaMetafactory.metafactory(
                    LAMBDA_LOOKUP,
                    "set",
                    MethodType.methodType(LambdaField.Setter.class, Object.class, long.class),
                    OBJECT_SETTER,
                    setterImplementation,
                    OBJECT_SETTER).getTarget();
            return new FieldFactory(getterFactory, setterFactory);
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Could not create field lambda factory for " + key.type(), throwable);
        }
    }

    private static MethodFactory createMethodFactory(Method method) {
        try {
            MethodHandles.Lookup lookup = UnsafeUtility.TRUSTED_LOOKUP
                    .in(method.getDeclaringClass());
            MethodHandle implementation = lookup.unreflect(method);
            boolean returnsVoid = method.getReturnType() == void.class;
            Class<?> invokerType = returnsVoid
                    ? LambdaMethod.VoidInvoker.class
                    : LambdaMethod.ReturnInvoker.class;
            MethodType samType = returnsVoid ? VOID_INVOKER : OBJECT_INVOKER;
            MethodType captureType = MethodType.methodType(
                    invokerType, captureTypes(method));
            CallSite callSite = LambdaMetafactory.metafactory(
                    lookup, "invoke", captureType, samType, implementation, samType);
            return new MethodFactory(callSite.getTarget());
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Could not create method lambda factory for " + method, throwable);
        }
    }

    private static Class<?>[] captureTypes(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        if (Modifier.isStatic(method.getModifiers())) {
            return parameters;
        }
        Class<?>[] captures = new Class<?>[parameters.length + 1];
        captures[0] = method.getDeclaringClass();
        System.arraycopy(parameters, 0, captures, 1, parameters.length);
        return captures;
    }

    private static Field findField(Class<?> clazz, String name) {
        Set<Class<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            Field field = declaredField(current, name);
            if (field != null) {
                return field;
            }
            field = findInterfaceField(current.getInterfaces(), name, visited);
            if (field != null) {
                return field;
            }
        }
        throw new IllegalArgumentException(
                "No field named " + name + " found on " + clazz.getName());
    }

    private static Field findInterfaceField(
            Class<?>[] interfaces, String name, Set<Class<?>> visited) {
        for (Class<?> interfaceType : interfaces) {
            if (!visited.add(interfaceType)) {
                continue;
            }
            Field field = declaredField(interfaceType, name);
            if (field != null) {
                return field;
            }
            field = findInterfaceField(interfaceType.getInterfaces(), name, visited);
            if (field != null) {
                return field;
            }
        }
        return null;
    }

    private static Field declaredField(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static MethodSelection findMethod(
            Class<?> clazz, String name, Object[] arguments) {
        MethodCandidate best = null;
        for (Method method : collectMethods(clazz, name)) {
            MethodCandidate candidate = scoreMethod(method, arguments);
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.isBetterThan(best)) {
                best = candidate;
            } else if (candidate.score() == best.score()
                    && !sameSignature(candidate.method(), best.method())) {
                throw new IllegalArgumentException(
                        "Ambiguous method " + name + " on " + clazz.getName()
                                + " with arguments " + Arrays.toString(arguments));
            }
        }
        if (best == null) {
            throw new IllegalArgumentException(
                    "No matching method " + name + " on " + clazz.getName()
                            + " with arguments " + Arrays.toString(arguments));
        }
        return new MethodSelection(best.method(), best.arguments(), best.keyArguments());
    }

    private static List<Method> collectMethods(Class<?> clazz, String name) {
        Map<MethodSignature, Method> methods = new LinkedHashMap<>();
        Set<Class<?>> visitedInterfaces = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            addMethods(methods, current.getDeclaredMethods(), name);
            addInterfaceMethods(
                    methods, current.getInterfaces(), name, visitedInterfaces);
        }
        return new ArrayList<>(methods.values());
    }

    private static void addInterfaceMethods(
            Map<MethodSignature, Method> methods,
            Class<?>[] interfaces,
            String name,
            Set<Class<?>> visited) {
        for (Class<?> interfaceType : interfaces) {
            if (!visited.add(interfaceType)) {
                continue;
            }
            addMethods(methods, interfaceType.getDeclaredMethods(), name);
            addInterfaceMethods(
                    methods, interfaceType.getInterfaces(), name, visited);
        }
    }

    private static void addMethods(
            Map<MethodSignature, Method> methods,
            Method[] declaredMethods,
            String name) {
        for (Method method : declaredMethods) {
            if (!method.getName().equals(name)) {
                continue;
            }
            MethodSignature signature = new MethodSignature(method);
            Method previous = methods.get(signature);
            if (previous == null
                    || (previous.isBridge() && !method.isBridge())
                    || (previous.isSynthetic() && !method.isSynthetic())) {
                methods.put(signature, method);
            }
        }
    }

    private static MethodCandidate scoreMethod(Method method, Object[] arguments) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (!method.isVarArgs() && parameterTypes.length != arguments.length) {
            return null;
        }
        if (method.isVarArgs() && arguments.length < parameterTypes.length - 1) {
            return null;
        }

        boolean directVarargsArray = method.isVarArgs()
                && arguments.length == parameterTypes.length
                && isDirectVarargsArray(
                        parameterTypes[parameterTypes.length - 1],
                        arguments[arguments.length - 1]);
        int fixedCount = method.isVarArgs() && !directVarargsArray
                ? parameterTypes.length - 1
                : parameterTypes.length;
        int score = method.isVarArgs() ? 1000 : 0;

        for (int index = 0; index < fixedCount; index++) {
            int part = conversionScore(parameterTypes[index], arguments[index]);
            if (part < 0) {
                return null;
            }
            score += part;
        }
        if (method.isVarArgs() && !directVarargsArray) {
            Class<?> componentType = parameterTypes[parameterTypes.length - 1]
                    .getComponentType();
            for (int index = fixedCount; index < arguments.length; index++) {
                int part = conversionScore(componentType, arguments[index]);
                if (part < 0) {
                    return null;
                }
                score += part + 1;
            }
            score++;
        } else if (method.isVarArgs()) {
            int part = conversionScore(
                    parameterTypes[parameterTypes.length - 1],
                    arguments[arguments.length - 1]);
            if (part < 0) {
                return null;
            }
            score += part;
        }

        try {
            return new MethodCandidate(
                    method, normalizeArguments(method, arguments, directVarargsArray),
                    arguments, score);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isDirectVarargsArray(Class<?> arrayType, Object argument) {
        return argument == null || arrayType.isInstance(argument);
    }

    private static Object[] normalizeArguments(
            Method method, Object[] arguments, boolean directVarargsArray) {
        if (!method.isVarArgs() || directVarargsArray) {
            return arguments.clone();
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        int fixedCount = parameterTypes.length - 1;
        Object[] normalized = new Object[parameterTypes.length];
        System.arraycopy(arguments, 0, normalized, 0, fixedCount);
        Class<?> componentType = parameterTypes[fixedCount].getComponentType();
        Object varargs = Array.newInstance(componentType, arguments.length - fixedCount);
        for (int index = fixedCount; index < arguments.length; index++) {
            Array.set(varargs, index - fixedCount, arguments[index]);
        }
        normalized[fixedCount] = varargs;
        return normalized;
    }

    private static int conversionScore(Class<?> parameterType, Object argument) {
        if (argument == null) {
            return parameterType.isPrimitive() ? -1 : 10;
        }
        if (parameterType.isPrimitive()) {
            Class<?> argumentType = argument.getClass();
            if (box(parameterType) == argumentType) {
                return 0;
            }
            int distance = primitiveWideningDistance(argumentType, parameterType);
            return distance < 0 ? -1 : 5 + distance;
        }
        if (parameterType == argument.getClass()) {
            return 0;
        }
        return parameterType.isAssignableFrom(argument.getClass())
                ? 1 + inheritanceDistance(argument.getClass(), parameterType)
                : -1;
    }

    private static int primitiveWideningDistance(
            Class<?> source, Class<?> target) {
        if (source == Byte.class) {
            if (target == short.class) return 1;
            if (target == int.class) return 2;
            if (target == long.class) return 3;
            if (target == float.class) return 4;
            if (target == double.class) return 5;
        } else if (source == Short.class) {
            if (target == int.class) return 1;
            if (target == long.class) return 2;
            if (target == float.class) return 3;
            if (target == double.class) return 4;
        } else if (source == Character.class) {
            if (target == int.class) return 1;
            if (target == long.class) return 2;
            if (target == float.class) return 3;
            if (target == double.class) return 4;
        } else if (source == Integer.class) {
            if (target == long.class) return 1;
            if (target == float.class) return 2;
            if (target == double.class) return 3;
        } else if (source == Long.class) {
            if (target == float.class) return 1;
            if (target == double.class) return 2;
        } else if (source == Float.class && target == double.class) {
            return 1;
        }
        return -1;
    }

    private static int inheritanceDistance(Class<?> child, Class<?> parent) {
        if (child == parent) {
            return 0;
        }
        if (parent.isInterface()) {
            return interfaceDistance(child, parent, 1);
        }
        int distance = 0;
        for (Class<?> current = child; current != null; current = current.getSuperclass()) {
            if (current == parent) {
                return distance;
            }
            distance++;
        }
        return 100;
    }

    private static int interfaceDistance(
            Class<?> child, Class<?> parent, int distance) {
        for (Class<?> interfaceType : child.getInterfaces()) {
            if (interfaceType == parent) {
                return distance;
            }
            int nested = interfaceDistance(interfaceType, parent, distance + 1);
            if (nested < 100) {
                return nested;
            }
        }
        Class<?> superclass = child.getSuperclass();
        return superclass == null ? 100
                : interfaceDistance(superclass, parent, distance + 1);
    }


    private static String getterName(Class<?> type, boolean isVolatile) {
        String suffix = isVolatile ? "Volatile" : "";
        if (type == boolean.class) return "getBoolean" + suffix;
        if (type == byte.class) return "getByte" + suffix;
        if (type == short.class) return "getShort" + suffix;
        if (type == char.class) return "getChar" + suffix;
        if (type == int.class) return "getInt" + suffix;
        if (type == long.class) return "getLong" + suffix;
        if (type == float.class) return "getFloat" + suffix;
        if (type == double.class) return "getDouble" + suffix;
        return "getObject" + suffix;
    }

    private static String setterName(Class<?> type, boolean isVolatile) {
        String suffix = isVolatile ? "Volatile" : "";
        if (type == boolean.class) return "putBoolean" + suffix;
        if (type == byte.class) return "putByte" + suffix;
        if (type == short.class) return "putShort" + suffix;
        if (type == char.class) return "putChar" + suffix;
        if (type == int.class) return "putInt" + suffix;
        if (type == long.class) return "putLong" + suffix;
        if (type == float.class) return "putFloat" + suffix;
        if (type == double.class) return "putDouble" + suffix;
        return "putObject" + suffix;
    }

    private static Object getBoolean(Object base, long offset) {
        return UNSAFE.getBoolean(base, offset);
    }

    private static Object getBooleanVolatile(Object base, long offset) {
        return UNSAFE.getBooleanVolatile(base, offset);
    }

    private static Object getByte(Object base, long offset) {
        return UNSAFE.getByte(base, offset);
    }

    private static Object getByteVolatile(Object base, long offset) {
        return UNSAFE.getByteVolatile(base, offset);
    }

    private static Object getShort(Object base, long offset) {
        return UNSAFE.getShort(base, offset);
    }

    private static Object getShortVolatile(Object base, long offset) {
        return UNSAFE.getShortVolatile(base, offset);
    }

    private static Object getChar(Object base, long offset) {
        return UNSAFE.getChar(base, offset);
    }

    private static Object getCharVolatile(Object base, long offset) {
        return UNSAFE.getCharVolatile(base, offset);
    }

    private static Object getInt(Object base, long offset) {
        return UNSAFE.getInt(base, offset);
    }

    private static Object getIntVolatile(Object base, long offset) {
        return UNSAFE.getIntVolatile(base, offset);
    }

    private static Object getLong(Object base, long offset) {
        return UNSAFE.getLong(base, offset);
    }

    private static Object getLongVolatile(Object base, long offset) {
        return UNSAFE.getLongVolatile(base, offset);
    }

    private static Object getFloat(Object base, long offset) {
        return UNSAFE.getFloat(base, offset);
    }

    private static Object getFloatVolatile(Object base, long offset) {
        return UNSAFE.getFloatVolatile(base, offset);
    }

    private static Object getDouble(Object base, long offset) {
        return UNSAFE.getDouble(base, offset);
    }

    private static Object getDoubleVolatile(Object base, long offset) {
        return UNSAFE.getDoubleVolatile(base, offset);
    }

    private static Object getObject(Object base, long offset) {
        return UNSAFE.getObject(base, offset);
    }

    private static Object getObjectVolatile(Object base, long offset) {
        return UNSAFE.getObjectVolatile(base, offset);
    }

    private static void putBoolean(Object base, long offset, Object value) {
        UNSAFE.putBoolean(base, offset, (Boolean) value);
    }

    private static void putBooleanVolatile(Object base, long offset, Object value) {
        UNSAFE.putBooleanVolatile(base, offset, (Boolean) value);
    }

    private static void putByte(Object base, long offset, Object value) {
        UNSAFE.putByte(base, offset, (Byte) value);
    }

    private static void putByteVolatile(Object base, long offset, Object value) {
        UNSAFE.putByteVolatile(base, offset, (Byte) value);
    }

    private static void putShort(Object base, long offset, Object value) {
        UNSAFE.putShort(base, offset, (Short) value);
    }

    private static void putShortVolatile(Object base, long offset, Object value) {
        UNSAFE.putShortVolatile(base, offset, (Short) value);
    }

    private static void putChar(Object base, long offset, Object value) {
        UNSAFE.putChar(base, offset, (Character) value);
    }

    private static void putCharVolatile(Object base, long offset, Object value) {
        UNSAFE.putCharVolatile(base, offset, (Character) value);
    }

    private static void putInt(Object base, long offset, Object value) {
        UNSAFE.putInt(base, offset, (Integer) value);
    }

    private static void putIntVolatile(Object base, long offset, Object value) {
        UNSAFE.putIntVolatile(base, offset, (Integer) value);
    }

    private static void putLong(Object base, long offset, Object value) {
        UNSAFE.putLong(base, offset, (Long) value);
    }

    private static void putLongVolatile(Object base, long offset, Object value) {
        UNSAFE.putLongVolatile(base, offset, (Long) value);
    }

    private static void putFloat(Object base, long offset, Object value) {
        UNSAFE.putFloat(base, offset, (Float) value);
    }

    private static void putFloatVolatile(Object base, long offset, Object value) {
        UNSAFE.putFloatVolatile(base, offset, (Float) value);
    }

    private static void putDouble(Object base, long offset, Object value) {
        UNSAFE.putDouble(base, offset, (Double) value);
    }

    private static void putDoubleVolatile(Object base, long offset, Object value) {
        UNSAFE.putDoubleVolatile(base, offset, (Double) value);
    }

    private static void putObject(Object base, long offset, Object value) {
        UNSAFE.putObject(base, offset, value);
    }

    private static void putObjectVolatile(Object base, long offset, Object value) {
        UNSAFE.putObjectVolatile(base, offset, value);
    }
    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable throwable) throws T {
        throw (T) throwable;
    }

    private record FieldFactoryKey(Class<?> type, boolean isVolatile) {
    }

    private record FieldFactory(MethodHandle getter, MethodHandle setter) {
    }

    private record MethodFactory(MethodHandle factory) {
    }

    private static final class FieldKey {
        private final Field field;
        private final Object instance;
        private final int hashCode;

        private FieldKey(Field field, Object instance) {
            this.field = field;
            this.instance = instance;
            this.hashCode = 31 * field.hashCode()
                    + (instance == null ? 0 : System.identityHashCode(instance));
        }

        @Override
        public boolean equals(Object object) {
            return this == object || object instanceof FieldKey other
                    && field.equals(other.field) && instance == other.instance;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final class MethodKey {
        private final Method method;
        private final Object instance;
        private final Object[] arguments;
        private final int hashCode;

        private MethodKey(Method method, Object instance, Object[] arguments) {
            this.method = method;
            this.instance = instance;
            this.arguments = arguments.clone();
            int hash = 31 * method.hashCode()
                    + (instance == null ? 0 : System.identityHashCode(instance));
            for (int index = 0; index < this.arguments.length; index++) {
                hash = 31 * hash + argumentHash(
                        argumentType(method, index, this.arguments), this.arguments[index]);
            }
            this.hashCode = hash;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof MethodKey other)
                    || !method.equals(other.method)
                    || instance != other.instance
                    || arguments.length != other.arguments.length) {
                return false;
            }
            for (int index = 0; index < arguments.length; index++) {
                if (!sameArgument(
                        argumentType(method, index, arguments),
                        arguments[index],
                        other.arguments[index])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static Class<?> argumentType(Method method, int index, Object[] arguments) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (!method.isVarArgs() || index < parameterTypes.length - 1) {
            return parameterTypes[index];
        }
        Class<?> arrayType = parameterTypes[parameterTypes.length - 1];
        if (arguments.length == parameterTypes.length
                && (arguments[index] == null || arrayType.isInstance(arguments[index]))) {
            return arrayType;
        }
        return arrayType.getComponentType();
    }
    private static int argumentHash(Class<?> type, Object argument) {
        if (type.isPrimitive()) {
            return Objects.hashCode(argument);
        }
        return argument == null ? 0 : System.identityHashCode(argument);
    }

    private static boolean sameArgument(Class<?> type, Object first, Object second) {
        return type.isPrimitive() ? Objects.equals(first, second) : first == second;
    }

    private record MethodSelection(Method method, Object[] arguments, Object[] keyArguments) {
        private MethodSelection {
            arguments = arguments.clone();
            keyArguments = keyArguments.clone();
        }
    }

    private static final class MethodCandidate {
        private final Method method;
        private final Object[] arguments;
        private final Object[] keyArguments;
        private final int score;

        private MethodCandidate(
                Method method, Object[] arguments, Object[] keyArguments, int score) {
            this.method = method;
            this.arguments = arguments;
            this.keyArguments = keyArguments.clone();
            this.score = score;
        }

        private Method method() {
            return method;
        }

        private Object[] arguments() {
            return arguments;
        }

        private Object[] keyArguments() {
            return keyArguments;
        }

        private int score() {
            return score;
        }

        private boolean isBetterThan(MethodCandidate other) {
            if (score != other.score) {
                return score < other.score;
            }
            if (method.isBridge() != other.method.isBridge()) {
                return !method.isBridge();
            }
            if (method.isSynthetic() != other.method.isSynthetic()) {
                return !method.isSynthetic();
            }
            return declaringClassDepth(method.getDeclaringClass())
                    > declaringClassDepth(other.method.getDeclaringClass());
        }
    }

    private record MethodSignature(String name, List<Class<?>> parameterTypes) {
        private MethodSignature(Method method) {
            this(method.getName(), List.of(method.getParameterTypes()));
        }
    }


    private static boolean sameSignature(Method first, Method second) {
        return new MethodSignature(first).equals(new MethodSignature(second));
    }

    private static int declaringClassDepth(Class<?> type) {
        int depth = 0;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            depth++;
        }
        return depth;
    }
}









