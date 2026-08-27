package io.github.seraphina.utility;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Objects;

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

        Constructor<?> matchingConstructor = null;
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length != arguments.length) {
                continue;
            }

            boolean compatible = true;
            for (int index = 0; index < parameterTypes.length; index++) {
                Object argument = arguments[index];
                if (argument == null) {
                    if (parameterTypes[index].isPrimitive()) {
                        compatible = false;
                        break;
                    }
                } else if (!box(parameterTypes[index]).isInstance(argument)) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) {
                continue;
            }

            if (matchingConstructor == null) {
                matchingConstructor = constructor;
                continue;
            }

            Class<?>[] matchedParameterTypes = matchingConstructor.getParameterTypes();
            boolean constructorIsMoreSpecific = false;
            boolean matchedConstructorIsMoreSpecific = false;
            boolean constructorCanReplaceMatch = true;
            boolean matchCanRemain = true;
            for (int index = 0; index < parameterTypes.length; index++) {
                Class<?> candidateType = box(parameterTypes[index]);
                Class<?> matchedType = box(matchedParameterTypes[index]);
                if (!matchedType.isAssignableFrom(candidateType)) {
                    constructorCanReplaceMatch = false;
                } else {
                    constructorIsMoreSpecific |= candidateType != matchedType;
                }
                if (!candidateType.isAssignableFrom(matchedType)) {
                    matchCanRemain = false;
                } else {
                    matchedConstructorIsMoreSpecific |= candidateType != matchedType;
                }
            }

            if (constructorCanReplaceMatch && constructorIsMoreSpecific) {
                matchingConstructor = constructor;
            } else if (!matchCanRemain || !matchedConstructorIsMoreSpecific) {
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
        return newInstance(clazz, matchingConstructor.getParameterTypes(), arguments);
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
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "Could not invoke a constructor for " + clazz.getName(), throwable);
        }
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
}
