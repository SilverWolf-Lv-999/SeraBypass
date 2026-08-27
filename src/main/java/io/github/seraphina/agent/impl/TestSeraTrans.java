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
    private static final String TARGET_REMOVED_FIELD_NAME = "targetRemovedField";
    private static final String TARGET_ADDED_FIELD_NAME = "targetAddedField";
    private static final String TARGET_MODIFY_FIELD_NAME = "targetModifiedField";
    private static final String INT_DESCRIPTOR = "I";
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
            private boolean targetAddedFieldPresent;

            @Override
            public org.objectweb.asm.FieldVisitor visitField(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    Object value) {
                if (TARGET_REMOVED_FIELD_NAME.equals(name) && INT_DESCRIPTOR.equals(descriptor)) {
                    return null;
                }

                if (TARGET_ADDED_FIELD_NAME.equals(name) && INT_DESCRIPTOR.equals(descriptor)) {
                    targetAddedFieldPresent = true;
                }

                if (TARGET_MODIFY_FIELD_NAME.equals(name) && INT_DESCRIPTOR.equals(descriptor)) {
                    access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
                }
                return super.visitField(access, name, descriptor, signature, value);
            }

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
                if (methodVisitor == null) {
                    return null;
                }
                boolean modifiesOutput = TARGET_MODIFY_METHOD_NAME.equals(name)
                        && NO_ARGUMENT_VOID_DESCRIPTOR.equals(descriptor);

                return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        super.visitLdcInsn(modifiesOutput && ORIGINAL_OUTPUT.equals(value)
                                ? MODIFIED_OUTPUT
                                : value);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (TARGET_CLASS_NAME.equals(owner)
                                && TARGET_REMOVED_FIELD_NAME.equals(name)
                                && INT_DESCRIPTOR.equals(descriptor)) {
                            if (opcode == Opcodes.GETSTATIC) {
                                super.visitInsn(Opcodes.ICONST_0);
                                return;
                            }
                            if (opcode == Opcodes.PUTSTATIC) {
                                super.visitInsn(Opcodes.POP);
                                return;
                            }
                        }
                        super.visitFieldInsn(opcode, owner, name, descriptor);
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

                if (!targetAddedFieldPresent) {
                    org.objectweb.asm.FieldVisitor fieldVisitor = super.visitField(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            TARGET_ADDED_FIELD_NAME,
                            INT_DESCRIPTOR,
                            null,
                            null);
                    fieldVisitor.visitEnd();
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
            if (!hasDeclaredField(loadedClass, TARGET_ADDED_FIELD_NAME)) {
                SeraLegitHook.addStaticField(
                        loadedClass,
                        TARGET_ADDED_FIELD_NAME,
                        TARGET_REMOVED_FIELD_NAME,
                        java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.STATIC);
            }
            if (hasDeclaredMethod(loadedClass, TARGET_REMOVED_METHOD_NAME)) {
                SeraLegitHook.removeMethod(loadedClass, TARGET_REMOVED_METHOD_NAME);
            }
            if (hasDeclaredField(loadedClass, TARGET_REMOVED_FIELD_NAME)) {
                SeraLegitHook.removeField(loadedClass, TARGET_REMOVED_FIELD_NAME);
            }
            if (!hasPublicDeclaredField(loadedClass, TARGET_MODIFY_FIELD_NAME)) {
                SeraLegitHook.modifyFieldModifiers(
                        loadedClass,
                        TARGET_MODIFY_FIELD_NAME,
                        java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.STATIC);
            }
            SeraLegitHook.hookStaticVoidMethod(
                    loadedClass, TARGET_MODIFY_METHOD_NAME,
                    () -> System.out.println(MODIFIED_OUTPUT));
        } catch (RuntimeException | Error exception) {
            transformedClasses.remove(loadedClass);
            throw exception;
        }
    }

    private static boolean hasDeclaredField(Class<?> loadedClass, String fieldName) {
        try {
            loadedClass.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException exception) {
            return false;
        }
    }

    private static boolean hasPublicDeclaredField(Class<?> loadedClass, String fieldName) {
        try {
            return java.lang.reflect.Modifier.isPublic(
                    loadedClass.getDeclaredField(fieldName).getModifiers());
        } catch (NoSuchFieldException exception) {
            return false;
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
