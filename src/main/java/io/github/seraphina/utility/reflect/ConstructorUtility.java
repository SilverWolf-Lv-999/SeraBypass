package io.github.seraphina.utility.reflect;

import io.github.seraphina.utility.jdk.UnsafeUtility;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Objects;

/** Utilities for selecting and invoking constructors with trusted lookup access. */
public final class ConstructorUtility {
    public static <T> T allocateInstance(Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz");

        try {
            return clazz.cast(UnsafeUtility.UNSAFE.allocateInstance(clazz));
        } catch (InstantiationException exception) {
            throw new IllegalArgumentException("Cannot allocate " + clazz.getName(), exception);
        }
    }

    public static <T> T newInstance(Class<T> clazz, Object... arguments) {
        Objects.requireNonNull(clazz, "clazz");
        Objects.requireNonNull(arguments, "arguments");

        Constructor<?> constructor = findMatchingConstructor(clazz, arguments);
        return newInstance(clazz, constructor.getParameterTypes(), arguments);
    }

    public static <T> T newInstance(
            Class<T> clazz, Class<?>[] parameterTypes, Object... arguments) {
        Objects.requireNonNull(clazz, "clazz");
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        Objects.requireNonNull(arguments, "arguments");

        if (parameterTypes.length != arguments.length) {
            throw new IllegalArgumentException(
                    "Parameter type count does not match argument count for " + clazz.getName());
        }

        try {
            MethodType methodType = MethodType.methodType(void.class, parameterTypes);
            MethodHandle constructor = UnsafeUtility.TRUSTED_LOOKUP.findConstructor(clazz, methodType);
            return clazz.cast(constructor.invokeWithArguments(arguments));
        } catch (Throwable throwable) {
            throw rethrow("Could not invoke a constructor for " + clazz.getName(), throwable);
        }
    }

    private static Constructor<?> findMatchingConstructor(Class<?> clazz, Object[] arguments) {
        Constructor<?> matchingConstructor = null;
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length != arguments.length
                    || !areCompatible(parameterTypes, arguments)) {
                continue;
            }

            if (matchingConstructor == null
                    || isMoreSpecific(parameterTypes, matchingConstructor.getParameterTypes())) {
                matchingConstructor = constructor;
            } else if (!isMoreSpecific(matchingConstructor.getParameterTypes(), parameterTypes)) {
                throw new IllegalArgumentException(
                        "Ambiguous constructor for " + clazz.getName()
                                + " with arguments " + Arrays.toString(arguments)
                                + "; specify parameter types explicitly");
            }
        }

        if (matchingConstructor == null) {
            throw new IllegalArgumentException(
                    "No matching constructor for " + clazz.getName()
                            + " with arguments " + Arrays.toString(arguments));
        }
        return matchingConstructor;
    }

    private static boolean areCompatible(Class<?>[] parameterTypes, Object[] arguments) {
        for (int index = 0; index < parameterTypes.length; index++) {
            Object argument = arguments[index];
            Class<?> parameterType = parameterTypes[index];
            if (argument == null) {
                if (parameterType.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!box(parameterType).isInstance(argument)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMoreSpecific(Class<?>[] first, Class<?>[] second) {
        boolean moreSpecific = false;
        for (int index = 0; index < first.length; index++) {
            Class<?> firstType = box(first[index]);
            Class<?> secondType = box(second[index]);
            if (!secondType.isAssignableFrom(firstType)) {
                return false;
            }
            moreSpecific |= firstType != secondType;
        }
        return moreSpecific;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
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

    private static RuntimeException rethrow(String message, Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(message, throwable);
    }
}
