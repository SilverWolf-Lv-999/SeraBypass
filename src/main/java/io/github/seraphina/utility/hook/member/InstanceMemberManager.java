package io.github.seraphina.utility.hook.member;

import io.github.seraphina.jnct.JNCT;
import io.github.seraphina.utility.UnsafeUtility;
import sun.misc.Unsafe;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public final class InstanceMemberManager {
    private static final Object LOCK = new Object();
    private static final Map<Class<?>, byte[]> CLASS_FILES = new HashMap<>();
    private static final Map<String, BiFunction<Object, Object[], Object>> CALLBACKS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, String>> CALLBACK_IDS = new HashMap<>();
    private static final AtomicLong NEXT_CALLBACK_ID = new AtomicLong();

    public static void addInstanceField(
            Class<?> target, String name, Class<?> type, int modifiers) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        validateMutableClass(target);
        validateIdentifier(name);
        validateFieldModifiers(modifiers);
        if (Modifier.isStatic(modifiers)) {
            throw new IllegalArgumentException("An instance field cannot be static");
        }
        synchronized (LOCK) {
            ensureClassInitialized(target);
            if (declaredField(target, name) != null) {
                throw new IllegalArgumentException(target.getName() + " already declares field " + name);
            }
            byte[] previous = currentBytes(target);
            byte[] replacement = InstanceClassFileEditor.addField(
                    previous, name, InstanceClassFileEditor.descriptor(type), modifiers);
            install(target, replacement, () -> {
                Field field = requireDeclaredField(target, name);
                if (field.getType() != type || Modifier.isStatic(field.getModifiers())) {
                    throw new IllegalStateException("The added field has incorrect metadata: " + field);
                }
            });
        }
    }

    public static void removeField(Class<?> target, String name) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(name, "name");
        validateMutableClass(target);
        synchronized (LOCK) {
            ensureClassInitialized(target);
            Field field = requireDeclaredField(target, name);
            if (Modifier.isStatic(field.getModifiers())) {
                throw new IllegalArgumentException("Static fields use the static metadata path: " + field);
            }
            byte[] previous = currentBytes(target);
            byte[] replacement = InstanceClassFileEditor.removeField(previous, name);
            install(target, replacement, () -> {
                if (declaredField(target, name) != null) {
                    throw new IllegalStateException("The removed field is still visible: " + name);
                }
            });
        }
    }

    public static void addInstanceMethod(
            Class<?> target, String name, Consumer<Object> body) {
        Objects.requireNonNull(body, "body");
        addInstanceMethod(target, name, void.class, new Class<?>[0], (receiver, arguments) -> {
            body.accept(receiver);
            return null;
        });
    }

    public static void addInstanceMethod(
            Class<?> target,
            String name,
            Class<?> returnType,
            Class<?>[] parameterTypes,
            BiFunction<Object, Object[], Object> body) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        Objects.requireNonNull(body, "body");
        validateConcreteClass(target);
        validateIdentifier(name);
        int parameterSlots = 1;
        for (Class<?> parameterType : parameterTypes) {
            Objects.requireNonNull(parameterType, "parameterType");
            if (parameterType == void.class) {
                throw new IllegalArgumentException("A method parameter cannot be void");
            }
            parameterSlots += parameterType == long.class || parameterType == double.class ? 2 : 1;
        }
        if (parameterSlots > 255) {
            throw new IllegalArgumentException("An instance method cannot use more than 255 argument slots");
        }
        synchronized (LOCK) {
            ensureClassInitialized(target);
            if (declaredMethod(target, name, parameterTypes) != null) {
                throw new IllegalArgumentException(
                        target.getName() + " already declares method " + name
                                + InstanceClassFileEditor.methodDescriptor(returnType, parameterTypes));
            }
            String descriptor = InstanceClassFileEditor.methodDescriptor(returnType, parameterTypes);
            String callbackId = Long.toUnsignedString(NEXT_CALLBACK_ID.incrementAndGet(), Character.MAX_RADIX);
            CALLBACKS.put(callbackId, body);
            try {
                byte[] previous = currentBytes(target);
                byte[] replacement = InstanceClassFileEditor.addInstanceMethod(
                        previous, name, returnType, parameterTypes, callbackId);
                install(target, replacement, () -> {
                    Method method = requireDeclaredMethod(target, name, parameterTypes);
                    if (Modifier.isStatic(method.getModifiers())
                            || method.getReturnType() != returnType) {
                        throw new IllegalStateException("The added method has incorrect metadata: " + method);
                    }
                });
                CALLBACK_IDS.computeIfAbsent(target, ignored -> new HashMap<>())
                        .put(name + descriptor, callbackId);
            } catch (Throwable throwable) {
                CALLBACKS.remove(callbackId);
                throw throwable;
            }
        }
    }

    public static void removeMethod(Class<?> target, String name, Class<?>... parameterTypes) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        validateMutableClass(target);
        synchronized (LOCK) {
            ensureClassInitialized(target);
            Method method = requireDeclaredMethod(target, name, parameterTypes);
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers)) {
                throw new IllegalArgumentException("Static methods use the static metadata path: " + method);
            }
            String descriptor = InstanceClassFileEditor.methodDescriptor(
                    method.getReturnType(), parameterTypes);
            byte[] previous = currentBytes(target);
            byte[] replacement = InstanceClassFileEditor.removeMethod(previous, name, descriptor);
            install(target, replacement, () -> {
                try {
                    target.getDeclaredMethod(name, parameterTypes);
                    throw new IllegalStateException("The removed method is still visible: " + name);
                } catch (NoSuchMethodException expected) {
                }
            });
            Map<String, String> ids = CALLBACK_IDS.get(target);
            if (ids != null) {
                String callbackId = ids.remove(name + descriptor);
                if (callbackId != null) {
                    CALLBACKS.remove(callbackId);
                }
            }
        }
    }

    public static Object invokeInjectedInstance(
            String callbackId, Object receiver, Object[] arguments) {
        BiFunction<Object, Object[], Object> body = CALLBACKS.get(callbackId);
        if (body == null) {
            throw new IllegalStateException("No injected instance method body registered for " + callbackId);
        }
        return body.apply(receiver, arguments);
    }

    private static void install(Class<?> target, byte[] replacement, Runnable verification) {
        Map<Class<?>, byte[]> originalFiles = new HashMap<>(CLASS_FILES);
        InstanceKlassSwapper.Change change = null;
        try {
            List<Class<?>> hierarchy = InstanceKlassSwapper.captureHierarchy(target);
            Map<Class<?>, byte[]> classFiles = new HashMap<>();
            for (Class<?> type : hierarchy) {
                classFiles.put(type, currentBytes(type));
            }
            deoptimize(hierarchy, classFiles);
            CLASS_FILES.put(target, replacement);
            classFiles.put(target, replacement);
            change = InstanceKlassSwapper.swap(hierarchy, classFiles);
            verification.run();
            InstanceKlassSwapper.commit(change);
        } catch (Throwable throwable) {
            if (change != null) {
                InstanceKlassSwapper.rollback(change);
            }
            CLASS_FILES.clear();
            CLASS_FILES.putAll(originalFiles);
            throw throwable;
        }
    }

    private static void deoptimize(List<Class<?>> hierarchy, Map<Class<?>, byte[]> classFiles) {
        Class<?>[] classes = hierarchy.toArray(new Class<?>[0]);
        byte[][] bytes = new byte[classes.length][];
        for (int index = 0; index < classes.length; index++) {
            bytes[index] = classFiles.get(classes[index]);
        }
        Object result = JNCT.ivk(
                "deoptimizeClasses", (Object) classes, (Object) bytes);
        if (!"deoptimized".equals(result)) {
            throw new IllegalStateException(
                    "JVMTI could not deoptimize " + hierarchy + ": " + result);
        }
    }

    private static byte[] currentBytes(Class<?> target) {
        return CLASS_FILES.computeIfAbsent(target, InstanceMemberManager::readClassFile);
    }

    private static byte[] readClassFile(Class<?> target) {
        String resource = target.getName().replace('.', '/') + ".class";
        InputStream stream = target.getResourceAsStream("/" + resource);
        if (stream == null && target.getClassLoader() != null) {
            stream = target.getClassLoader().getResourceAsStream(resource);
        }
        if (stream == null) {
            throw new IllegalStateException("Could not read the class file for " + target.getName());
        }
        try (InputStream input = stream) {
            byte[] bytes = input.readAllBytes();
            String className = new org.objectweb.asm.ClassReader(bytes).getClassName();
            if (!className.equals(target.getName().replace('.', '/'))) {
                throw new IllegalStateException("Class file name does not match " + target.getName());
            }
            return bytes;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not read the class file for " + target.getName(), exception);
        }
    }

    private static void ensureClassInitialized(Class<?> target) {
        UnsafeUtility.UNSAFE.ensureClassInitialized(target);
    }

    private static void validateMutableClass(Class<?> target) {
        if (target.isArray() || target.isPrimitive() || target.isInterface()) {
            throw new IllegalArgumentException("Member mutation requires a class: " + target.getTypeName());
        }
    }

    private static void validateConcreteClass(Class<?> target) {
        validateMutableClass(target);
        if (Modifier.isAbstract(target.getModifiers())) {
            throw new IllegalArgumentException("Method injection requires a concrete class: " + target.getTypeName());
        }
    }

    private static void validateIdentifier(String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Member name cannot be empty");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean letter = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z';
            if (!letter && character != '_' && character != '$'
                    && (index == 0 || character < '0' || character > '9')) {
                throw new IllegalArgumentException("Member name must be an ASCII Java identifier: " + name);
            }
        }
    }

    private static void validateFieldModifiers(int modifiers) {
        if ((modifiers & ~Modifier.fieldModifiers()) != 0) {
            throw new IllegalArgumentException("Unsupported field modifiers: 0x" + Integer.toHexString(modifiers));
        }
        int visibility = modifiers & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
        if (Integer.bitCount(visibility) > 1) {
            throw new IllegalArgumentException("A field can declare at most one visibility modifier");
        }
    }

    private static Field declaredField(Class<?> target, String name) {
        try {
            return target.getDeclaredField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static Field requireDeclaredField(Class<?> target, String name) {
        Field field = declaredField(target, name);
        if (field == null) {
            throw new IllegalArgumentException("No declared field " + name + " on " + target.getName());
        }
        return field;
    }

    private static Method declaredMethod(Class<?> target, String name, Class<?>... parameterTypes) {
        for (Method method : target.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && java.util.Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                return method;
            }
        }
        return null;
    }

    private static Method requireDeclaredMethod(Class<?> target, String name, Class<?>... parameterTypes) {
        for (Method method : target.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && java.util.Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                return method;
            }
        }
        throw new IllegalArgumentException(
                "No declared method " + name + java.util.Arrays.toString(parameterTypes)
                        + " on " + target.getName());
    }
}
