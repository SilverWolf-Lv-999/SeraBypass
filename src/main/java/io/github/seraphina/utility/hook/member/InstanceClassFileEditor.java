package io.github.seraphina.utility.hook.member;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Modifier;

final class InstanceClassFileEditor {

    static byte[] addField(byte[] original, String fieldName, String descriptor, int modifiers) {
        if (Modifier.isStatic(modifiers)) {
            throw new IllegalArgumentException("Instance fields cannot be static");
        }
        ClassNode node = read(original);
        for (FieldNode field : node.fields) {
            if (field.name.equals(fieldName)) {
                throw new IllegalArgumentException(
                        node.name + " already declares field " + fieldName);
            }
        }
        node.fields.add(new FieldNode(modifiers, fieldName, descriptor, null, null));
        return write(original, node);
    }

    static byte[] removeField(byte[] original, String fieldName) {
        ClassNode node = read(original);
        int originalSize = node.fields.size();
        node.fields.removeIf(field -> field.name.equals(fieldName));
        if (node.fields.size() == originalSize) {
            throw new IllegalArgumentException(
                    node.name + " does not declare field " + fieldName);
        }
        return write(original, node);
    }

    static byte[] addInstanceMethod(
            byte[] original,
            String methodName,
            Class<?> returnType,
            Class<?>[] parameterTypes,
            String callbackId) {
        ClassNode node = read(original);
        String descriptor = methodDescriptor(returnType, parameterTypes);
        for (MethodNode current : node.methods) {
            if (current.name.equals(methodName) && current.desc.equals(descriptor)) {
                throw new IllegalArgumentException(
                        node.name + " already declares method " + methodName + descriptor);
            }
        }
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null);
        Type[] argumentTypes = Type.getArgumentTypes(descriptor);
        method.instructions.add(new LdcInsnNode(callbackId));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pushInt(method, argumentTypes.length);
        method.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int local = 1;
        for (int index = 0; index < argumentTypes.length; index++) {
            Type argumentType = argumentTypes[index];
            method.instructions.add(new InsnNode(Opcodes.DUP));
            pushInt(method, index);
            method.instructions.add(new VarInsnNode(argumentType.getOpcode(Opcodes.ILOAD), local));
            if (argumentType.getSort() >= Type.BOOLEAN && argumentType.getSort() <= Type.DOUBLE) {
                String wrapper = switch (argumentType.getSort()) {
                    case Type.BOOLEAN -> "java/lang/Boolean";
                    case Type.CHAR -> "java/lang/Character";
                    case Type.BYTE -> "java/lang/Byte";
                    case Type.SHORT -> "java/lang/Short";
                    case Type.INT -> "java/lang/Integer";
                    case Type.FLOAT -> "java/lang/Float";
                    case Type.LONG -> "java/lang/Long";
                    case Type.DOUBLE -> "java/lang/Double";
                    default -> throw new IllegalStateException();
                };
                method.instructions.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        wrapper,
                        "valueOf",
                        "(" + argumentType.getDescriptor() + ")L" + wrapper + ";",
                        false));
            }
            method.instructions.add(new InsnNode(Opcodes.AASTORE));
            local += argumentType.getSize();
        }
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                InstanceMemberManager.class.getName().replace('.', '/'),
                "invokeInjectedInstance",
                "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        appendReturn(method, Type.getReturnType(descriptor));
        method.maxLocals = local;
        node.methods.add(method);
        return write(original, node);
    }

    static byte[] removeMethod(byte[] original, String methodName, String descriptor) {
        ClassNode node = read(original);
        if (!node.methods.removeIf(
                method -> method.name.equals(methodName) && method.desc.equals(descriptor))) {
            throw new IllegalArgumentException(
                    node.name + " does not declare method " + methodName + descriptor);
        }
        return write(original, node);
    }

    static String descriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) return type.getName().replace('.', '/');
        return "L" + type.getName().replace('.', '/') + ";";
    }

    static String methodDescriptor(Class<?> returnType, Class<?>... parameterTypes) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameterType : parameterTypes) {
            descriptor.append(descriptor(parameterType));
        }
        return descriptor.append(')').append(descriptor(returnType)).toString();
    }

    private static void appendReturn(MethodNode method, Type type) {
        switch (type.getSort()) {
            case Type.VOID -> {
                method.instructions.add(new InsnNode(Opcodes.POP));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
            }
            case Type.BOOLEAN -> unbox(method, "java/lang/Boolean", "booleanValue", "Z", Opcodes.IRETURN);
            case Type.CHAR -> unbox(method, "java/lang/Character", "charValue", "C", Opcodes.IRETURN);
            case Type.BYTE -> unbox(method, "java/lang/Byte", "byteValue", "B", Opcodes.IRETURN);
            case Type.SHORT -> unbox(method, "java/lang/Short", "shortValue", "S", Opcodes.IRETURN);
            case Type.INT -> unbox(method, "java/lang/Integer", "intValue", "I", Opcodes.IRETURN);
            case Type.FLOAT -> unbox(method, "java/lang/Float", "floatValue", "F", Opcodes.FRETURN);
            case Type.LONG -> unbox(method, "java/lang/Long", "longValue", "J", Opcodes.LRETURN);
            case Type.DOUBLE -> unbox(method, "java/lang/Double", "doubleValue", "D", Opcodes.DRETURN);
            case Type.ARRAY -> {
                method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getDescriptor()));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
            }
            case Type.OBJECT -> {
                method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
                method.instructions.add(new InsnNode(Opcodes.ARETURN));
            }
            default -> throw new IllegalArgumentException("Unsupported return type " + type);
        }
    }

    private static void unbox(
            MethodNode method, String wrapper, String methodName, String descriptor, int returnOpcode) {
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapper));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, wrapper, methodName, "()" + descriptor, false));
        method.instructions.add(new InsnNode(returnOpcode));
    }

    private static void pushInt(MethodNode method, int value) {
        if (value >= -1 && value <= 5) {
            method.instructions.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value <= Byte.MAX_VALUE) {
            method.instructions.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value <= Short.MAX_VALUE) {
            method.instructions.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            method.instructions.add(new LdcInsnNode(value));
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(byte[] original, ClassNode node) {
        ClassWriter writer = new ClassWriter(new ClassReader(original), ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}