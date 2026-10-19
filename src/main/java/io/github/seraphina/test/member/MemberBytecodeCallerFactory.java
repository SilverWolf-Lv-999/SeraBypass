package io.github.seraphina.test.member;

import io.github.seraphina.utility.UnsafeUtility;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;

public final class MemberBytecodeCallerFactory {
    public static Class<?> create(
            Class<?> target, String methodName, String longFieldName, String referenceFieldName)
            throws IllegalAccessException {
        String targetName = target.getName().replace('.', '/');
        String targetDescriptor = "L" + targetName + ";";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "io/github/seraphina/test/member/MemberBytecodeCaller",
                null,
                "java/lang/Object",
                null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor invoke = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "invoke",
                "(" + targetDescriptor + ")V",
                null,
                null);
        invoke.visitCode();
        invoke.visitVarInsn(Opcodes.ALOAD, 0);
        invoke.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, targetName, methodName, "()V", false);
        invoke.visitInsn(Opcodes.RETURN);
        invoke.visitMaxs(0, 0);
        invoke.visitEnd();

        MethodVisitor readLong = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "readLong",
                "(" + targetDescriptor + ")J",
                null,
                null);
        readLong.visitCode();
        readLong.visitVarInsn(Opcodes.ALOAD, 0);
        readLong.visitFieldInsn(Opcodes.GETFIELD, targetName, longFieldName, "J");
        readLong.visitInsn(Opcodes.LRETURN);
        readLong.visitMaxs(0, 0);
        readLong.visitEnd();

        MethodVisitor writeLong = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "writeLong",
                "(" + targetDescriptor + "J)V",
                null,
                null);
        writeLong.visitCode();
        writeLong.visitVarInsn(Opcodes.ALOAD, 0);
        writeLong.visitVarInsn(Opcodes.LLOAD, 1);
        writeLong.visitFieldInsn(Opcodes.PUTFIELD, targetName, longFieldName, "J");
        writeLong.visitInsn(Opcodes.RETURN);
        writeLong.visitMaxs(0, 0);
        writeLong.visitEnd();

        MethodVisitor readReference = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "readReference",
                "(" + targetDescriptor + ")Ljava/lang/Object;",
                null,
                null);
        readReference.visitCode();
        readReference.visitVarInsn(Opcodes.ALOAD, 0);
        readReference.visitFieldInsn(
                Opcodes.GETFIELD,
                targetName,
                referenceFieldName,
                "Ljava/lang/Object;");
        readReference.visitInsn(Opcodes.ARETURN);
        readReference.visitMaxs(0, 0);
        readReference.visitEnd();

        writer.visitEnd();
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                target, UnsafeUtility.TRUSTED_LOOKUP);
        return lookup.defineHiddenClass(
                        writer.toByteArray(),
                        true,
                        MethodHandles.Lookup.ClassOption.NESTMATE,
                        MethodHandles.Lookup.ClassOption.STRONG)
                .lookupClass();
    }
}
