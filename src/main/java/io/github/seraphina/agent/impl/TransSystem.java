package io.github.seraphina.agent.impl;

import io.github.seraphina.agent.api.SeraTransImpl;
import io.github.seraphina.utility.hook.SeraLegitHook;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TransSystem implements SeraTransImpl {
    private final Set<Class<?>> transformedClasses = ConcurrentHashMap.newKeySet();

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (classBeingRedefined != null && isTargetClass(className, classBeingRedefined, null)) {
            transformLoadedClass(classBeingRedefined);
            return classfileBuffer;
        }

        if (classfileBuffer == null || !isTargetClass(className, classBeingRedefined, classfileBuffer)) {
            return null;
        }

        ClassReader classReader = new ClassReader(classfileBuffer);
        ClassWriter classWriter = new ClassWriter(classReader, 0);
        String targetClassName = classReader.getClassName();
        classReader.accept(new ClassVisitor(Opcodes.ASM9, classWriter) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                if (("java/lang/System".equals(targetClassName)
                        && "exit".equals(name)
                        && "(I)V".equals(descriptor))
                        || ("java/lang/Object".equals(targetClassName)
                        && (("toString".equals(name)
                        && "()Ljava/lang/String;".equals(descriptor))
                        || ("getClass".equals(name)
                        && "()Ljava/lang/Class;".equals(descriptor))))) {
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        return classWriter.toByteArray();
    }

    private void transformLoadedClass(Class<?> loadedClass) {
        if (!transformedClasses.add(loadedClass)) {
            return;
        }

        try {
            if (loadedClass == System.class) {
                removeMethodIfPresent(loadedClass, "exit", int.class);
            } else if (loadedClass == Object.class) {
                removeMethodIfPresent(loadedClass, "toString");
                removeMethodIfPresent(loadedClass, "getClass");
            }
        } catch (RuntimeException | Error exception) {
            transformedClasses.remove(loadedClass);
            throw exception;
        }
    }

    private static void removeMethodIfPresent(
            Class<?> loadedClass, String methodName, Class<?>... parameterTypes) {
        try {
            loadedClass.getDeclaredMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException exception) {
            return;
        }
        SeraLegitHook.removeMethod(loadedClass, methodName, parameterTypes);
    }

    private static boolean isTargetClass(
            String className,
            Class<?> classBeingRedefined,
            byte[] classfileBuffer) {
        if (className != null) {
            String normalizedName = className.replace('.', '/');
            return "java/lang/System".equals(normalizedName)
                    || "java/lang/Object".equals(normalizedName);
        }

        if (classBeingRedefined != null) {
            return classBeingRedefined == System.class || classBeingRedefined == Object.class;
        }

        if (classfileBuffer != null) {
            String transformedClassName = new ClassReader(classfileBuffer).getClassName();
            return "java/lang/System".equals(transformedClassName)
                    || "java/lang/Object".equals(transformedClassName);
        }

        return false;
    }

}
