package io.github.seraphina.test.member;

import io.github.seraphina.utility.UnsafeUtility;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;

public final class MemberBytecodeCallerFactory {

    public static Class<?> createInstanceFactory(Class<?> target) throws IllegalAccessException {
        String targetName = target.getName().replace('.', '/');
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                targetName + "$MemberInstanceFactory",
                null,
                "java/lang/Object",
                null);
        MethodVisitor instantiate = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "instantiate",
                "()Ljava/lang/Object;",
                null,
                null);
        instantiate.visitCode();
        instantiate.visitTypeInsn(Opcodes.NEW, targetName);
        instantiate.visitInsn(Opcodes.DUP);
        instantiate.visitMethodInsn(Opcodes.INVOKESPECIAL, targetName, "<init>", "()V", false);
        instantiate.visitInsn(Opcodes.ARETURN);
        instantiate.visitMaxs(0, 0);
        instantiate.visitEnd();
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

    public static Class<?> createVirtualCaller(Class<?> target, String methodName)
            throws IllegalAccessException {
        String targetName = target.getName().replace('.', '/');
        String targetDescriptor = "L" + targetName + ";";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                targetName + "$PostPoolVirtualCaller",
                null,
                "java/lang/Object",
                null);
        MethodVisitor invoke = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "invoke",
                "(" + targetDescriptor + ")I",
                null,
                null);
        invoke.visitCode();
        invoke.visitVarInsn(Opcodes.ALOAD, 0);
        invoke.visitMethodInsn(Opcodes.INVOKEVIRTUAL, targetName, methodName, "()I", false);
        invoke.visitInsn(Opcodes.IRETURN);
        invoke.visitMaxs(0, 0);
        invoke.visitEnd();
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
    public static Class<?> createVirtualCaller(
            Class<?> target, String methodName, Class<?> returnType, Class<?>... parameterTypes)
            throws IllegalAccessException {
        String targetName = target.getName().replace('.', '/');
        Type[] argumentTypes = new Type[parameterTypes.length];
        StringBuilder callerDescriptor = new StringBuilder("(L").append(targetName).append(';');
        for (int index = 0; index < parameterTypes.length; index++) {
            argumentTypes[index] = Type.getType(parameterTypes[index]);
            callerDescriptor.append(argumentTypes[index].getDescriptor());
        }
        String methodDescriptor = Type.getMethodDescriptor(
                Type.getType(returnType), argumentTypes);
        callerDescriptor.append(')').append(Type.getType(returnType).getDescriptor());
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                targetName + "$DynamicVirtualCaller",
                null,
                "java/lang/Object",
                null);
        MethodVisitor invoke = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "invoke",
                callerDescriptor.toString(),
                null,
                null);
        invoke.visitCode();
        invoke.visitVarInsn(Opcodes.ALOAD, 0);
        int local = 1;
        for (Type argumentType : argumentTypes) {
            invoke.visitVarInsn(argumentType.getOpcode(Opcodes.ILOAD), local);
            local += argumentType.getSize();
        }
        invoke.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, targetName, methodName, methodDescriptor, false);
        invoke.visitInsn(Type.getType(returnType).getOpcode(Opcodes.IRETURN));
        invoke.visitMaxs(0, 0);
        invoke.visitEnd();
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
    public static Class<?> createExistingMemberCaller(Class<?> target) throws IllegalAccessException {
        String targetName = target.getName().replace('.', '/');
        String targetDescriptor = "L" + targetName + ";";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                targetName + "$MemberResolvedCaller",
                null,
                "java/lang/Object",
                null);

        MethodVisitor readInt = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "readExistingInt",
                "(" + targetDescriptor + ")I",
                null,
                null);
        readInt.visitCode();
        readInt.visitVarInsn(Opcodes.ALOAD, 0);
        readInt.visitFieldInsn(Opcodes.GETFIELD, targetName, "existingInt", "I");
        readInt.visitInsn(Opcodes.IRETURN);
        readInt.visitMaxs(0, 0);
        readInt.visitEnd();

        MethodVisitor invokeBase = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "invokeBaseValue",
                "(" + targetDescriptor + ")I",
                null,
                null);
        invokeBase.visitCode();
        invokeBase.visitVarInsn(Opcodes.ALOAD, 0);
        invokeBase.visitMethodInsn(Opcodes.INVOKEVIRTUAL, targetName, "baseValue", "()I", false);
        invokeBase.visitInsn(Opcodes.IRETURN);
        invokeBase.visitMaxs(0, 0);
        invokeBase.visitEnd();

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

    public static Class<?> create(
            Class<?> target, String methodName, String longFieldName, String referenceFieldName)
            throws IllegalAccessException {
        String targetName = target.getName().replace('.', '/');
        String targetDescriptor = "L" + targetName + ";";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                targetName + "$MemberBytecodeCaller",
                null,
                "java/lang/Object",
                null);

        MethodVisitor instantiate = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "instantiate",
                "()Ljava/lang/Object;",
                null,
                null);
        instantiate.visitCode();
        instantiate.visitTypeInsn(Opcodes.NEW, targetName);
        instantiate.visitInsn(Opcodes.DUP);
        instantiate.visitMethodInsn(Opcodes.INVOKESPECIAL, targetName, "<init>", "()V", false);
        instantiate.visitInsn(Opcodes.ARETURN);
        instantiate.visitMaxs(0, 0);
        instantiate.visitEnd();

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
