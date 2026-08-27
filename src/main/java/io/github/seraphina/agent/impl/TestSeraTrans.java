package io.github.seraphina.agent.impl;

import io.github.seraphina.agent.api.SeraTransImpl;
import io.github.seraphina.utility.hook.SeraLegitHook;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TestSeraTrans implements SeraTransImpl {
    private static final String TARGET_CLASS_NAME = "io/github/seraphina/test/TransTarget";
    private static final String TARGET_REMOVED_METHOD_NAME = "targetRemoved";
    private static final String TARGET_NEW_METHOD_NAME = "targetNew";
    private static final String TARGET_MODIFY_METHOD_NAME = "targetModify";
    private static final String NO_ARGUMENT_VOID_DESCRIPTOR = "()V";
    private static final String ORIGINAL_OUTPUT = "1";
    private static final String MODIFIED_OUTPUT = "c";

    private final Set<Class<?>> transformedClasses = ConcurrentHashMap.newKeySet();

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) {
        if (classBeingRedefined != null
                && isTargetClass(className, classBeingRedefined, null)) {
            transformLoadedClass(classBeingRedefined);
            return classfileBuffer;
        }

        if (classfileBuffer == null || !isTargetClass(className, classBeingRedefined, classfileBuffer)) {
            return null;
        }

        ClassReader classReader = new ClassReader(classfileBuffer);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS);
        classReader.accept(new ClassVisitor(Opcodes.ASM9, classWriter) {
            private boolean targetNewPresent;

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                if (TARGET_REMOVED_METHOD_NAME.equals(name)
                        && NO_ARGUMENT_VOID_DESCRIPTOR.equals(descriptor)) {
                    return null;
                }

                if (TARGET_NEW_METHOD_NAME.equals(name) && NO_ARGUMENT_VOID_DESCRIPTOR.equals(descriptor)) {
                    targetNewPresent = true;
                }

                MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!TARGET_MODIFY_METHOD_NAME.equals(name)
                        || !NO_ARGUMENT_VOID_DESCRIPTOR.equals(descriptor)) {
                    return methodVisitor;
                }

                return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        super.visitLdcInsn(ORIGINAL_OUTPUT.equals(value) ? MODIFIED_OUTPUT : value);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (!targetNewPresent) {
                    MethodVisitor methodVisitor = super.visitMethod(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            TARGET_NEW_METHOD_NAME,
                            NO_ARGUMENT_VOID_DESCRIPTOR,
                            null,
                            null);
                    methodVisitor.visitCode();
                    methodVisitor.visitInsn(Opcodes.RETURN);
                    methodVisitor.visitMaxs(0, 0);
                    methodVisitor.visitEnd();
                }

                super.visitEnd();
            }
        }, 0);

        return classWriter.toByteArray();
    }

    /**
     * Applies the live-class part of this transformer. The five-argument
     * transformer remains the single entry point: the loaded class is passed
     * through {@code classBeingRedefined}, while no class-file bytes are read
     * from disk or supplied by a JVMTI hook.
     */
    private void transformLoadedClass(Class<?> loadedClass) {
        if (!TARGET_CLASS_NAME.equals(loadedClass.getName().replace('.', '/'))
                || !transformedClasses.add(loadedClass)) {
            return;
        }

        try {
            if (!hasDeclaredMethod(loadedClass, TARGET_NEW_METHOD_NAME)) {
                SeraLegitHook.addMethod(loadedClass, TARGET_NEW_METHOD_NAME, () -> {
                });
            }
            if (hasDeclaredMethod(loadedClass, TARGET_REMOVED_METHOD_NAME)) {
                SeraLegitHook.removeMethod(loadedClass, TARGET_REMOVED_METHOD_NAME);
            }
            SeraLegitHook.hookStaticVoidMethod(
                    loadedClass, TARGET_MODIFY_METHOD_NAME,
                    () -> System.out.println(MODIFIED_OUTPUT));
        } catch (RuntimeException | Error exception) {
            transformedClasses.remove(loadedClass);
            throw exception;
        }
    }

    private static boolean hasDeclaredMethod(Class<?> loadedClass, String methodName) {
        try {
            loadedClass.getDeclaredMethod(methodName);
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    private static boolean isTargetClass(
            String className,
            Class<?> classBeingRedefined,
            byte[] classfileBuffer) {
        if (className != null) {
            return TARGET_CLASS_NAME.equals(className.replace('.', '/'));
        }

        if (classBeingRedefined != null) {
            return TARGET_CLASS_NAME.equals(classBeingRedefined.getName().replace('.', '/'));
        }

        return classfileBuffer != null
                && TARGET_CLASS_NAME.equals(new ClassReader(classfileBuffer).getClassName());
    }
}
