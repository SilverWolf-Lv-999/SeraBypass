package io.github.seraphina.utility.hook.member;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
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

    static byte[] addInstanceVoidMethod(byte[] original, String methodName, String callbackId) {
        ClassNode node = read(original);
        for (MethodNode method : node.methods) {
            if (method.name.equals(methodName) && method.desc.equals("()V")) {
                throw new IllegalArgumentException(
                        node.name + " already declares method " + methodName + "()V");
            }
        }
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new LdcInsnNode(callbackId));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                InstanceMemberManager.class.getName().replace('.', '/'),
                "runInjectedInstance",
                "(Ljava/lang/String;Ljava/lang/Object;)V",
                false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 1;
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

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(byte[] original, ClassNode node) {
        ClassWriter writer = new ClassWriter(new ClassReader(original), 0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
