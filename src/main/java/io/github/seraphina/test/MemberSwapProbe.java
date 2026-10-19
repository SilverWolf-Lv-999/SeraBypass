package io.github.seraphina.test;

import io.github.seraphina.jnct.api.JVM;
import io.github.seraphina.utility.UnsafeUtility;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import sun.misc.Unsafe;

import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public final class MemberSwapProbe {
    public static void run() throws Throwable {
        Class<?> target = SwapTarget.class;
        byte[] bytes;
        try (InputStream stream = target.getResourceAsStream("/" + target.getName().replace('.', '/') + ".class")) {
            bytes = stream.readAllBytes();
        }
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        node.fields.add(new FieldNode(Modifier.PUBLIC, "extraLong", "J", null, null));
        MethodNode method = new MethodNode(Modifier.PUBLIC, "extraValue", "()J", null, null);
        method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
        method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.GETFIELD, node.name.replace('.', '/'), "extraLong", "J"));
        method.instructions.add(new InsnNode(org.objectweb.asm.Opcodes.LRETURN));
        method.maxStack = 2;
        method.maxLocals = 1;
        node.methods.add(method);
        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        byte[] replacementBytes = writer.toByteArray();
        MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(target, UnsafeUtility.TRUSTED_LOOKUP);
        Class<?> replacement = lookup.defineHiddenClass(replacementBytes, false, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        JVM jvm = JVM.getInstance();
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        long targetKlass = unsafe.getLong(target, jvm.classKlassOffset);
        long replacementKlass = unsafe.getLong(replacement, jvm.classKlassOffset);
        long mirrorHandle = unsafe.getLong(targetKlass + jvm.klassJavaMirrorOffset);
        long name = unsafe.getLong(targetKlass + jvm.klassNameOffset);
        unsafe.putLong(replacementKlass + jvm.klassJavaMirrorOffset, mirrorHandle);
        unsafe.putLong(replacementKlass + jvm.klassNameOffset, name);
        unsafe.putObject(target, jvm.classReflectionDataOffset, null);
        unsafe.putLong(target, jvm.classKlassOffset, replacementKlass);
        Field added = target.getDeclaredField("extraLong");
        Object instance = unsafe.allocateInstance(target);
        added.setLong(instance, 41L);
        Object oldValue = target.getDeclaredField("existing").get(instance);
        Object value = target.getDeclaredMethod("extraValue").invoke(instance);
        System.out.println("swap field=" + added + " existing=" + oldValue + " value=" + value + " size=" + target.getDeclaredFields().length);
    }

    public static class SwapTarget {
        public int existing;
        public int baseValue() {
            return existing;
        }
    }
}
