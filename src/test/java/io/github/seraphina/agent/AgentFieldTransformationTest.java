package io.github.seraphina.agent;

import io.github.seraphina.agent.impl.TestSeraTrans;
import io.github.seraphina.test.TransTarget;
import io.github.seraphina.utility.hook.SeraLegitHook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentFieldTransformationTest {
    @AfterEach
    void resetAgent() {
        Agent.detachAll();
        Agent.transformers.clear();
    }

    @Test
    void transformsClassBytesWithAddedRemovedAndModifiedFields() throws Exception {
        byte[] originalBytecode = classBytecode();
        byte[] transformedBytecode = new TestSeraTrans().transform(
                null,
                "io/github/seraphina/test/TransTarget",
                null,
                null,
                originalBytecode);
        assertNotNull(transformedBytecode);

        Class<?> transformedClass = new ByteArrayClassLoader().define(
                "io.github.seraphina.test.TransTarget", transformedBytecode);
        assertNotNull(transformedClass.getDeclaredMethod("targetNew"));
        assertThrows(NoSuchMethodException.class,
                () -> transformedClass.getDeclaredMethod("targetRemoved"));
        assertThrows(NoSuchFieldException.class,
                () -> transformedClass.getDeclaredField("targetRemovedField"));

        Field addedField = transformedClass.getDeclaredField("targetAddedField");
        assertEquals(int.class, addedField.getType());
        assertTrue(Modifier.isPublic(addedField.getModifiers()));
        assertTrue(Modifier.isStatic(addedField.getModifiers()));
        addedField.setInt(null, 1919);
        assertEquals(1919, addedField.getInt(null));

        Field modifiedField = transformedClass.getField("targetModifiedField");
        assertTrue(Modifier.isPublic(modifiedField.getModifiers()));
        assertTrue(Modifier.isStatic(modifiedField.getModifiers()));
    }

    @Test
    void removesTheLastDeclaredField() {
        assertEquals(1, SingleFieldTarget.class.getDeclaredFields().length);

        SeraLegitHook.removeField(SingleFieldTarget.class, "onlyField");

        assertEquals(0, SingleFieldTarget.class.getDeclaredFields().length);
        assertThrows(NoSuchFieldException.class,
                () -> SingleFieldTarget.class.getDeclaredField("onlyField"));
    }

    @Test
    void transformsLoadedClassMethodsAndFieldsInMemory() throws Exception {
        Agent.reg(new TestSeraTrans());
        Agent.attach();

        Agent.transform(TransTarget.class);

        assertNotNull(TransTarget.class.getDeclaredMethod("targetNew"));
        assertThrows(NoSuchMethodException.class,
                () -> TransTarget.class.getDeclaredMethod("targetRemoved"));

        Field addedField = TransTarget.class.getDeclaredField("targetAddedField");
        assertEquals(int.class, addedField.getType());
        assertTrue(Modifier.isPublic(addedField.getModifiers()));
        assertTrue(Modifier.isStatic(addedField.getModifiers()));
        addedField.setInt(null, 114514);
        assertEquals(114514, addedField.getInt(null));

        assertThrows(NoSuchFieldException.class,
                () -> TransTarget.class.getDeclaredField("targetRemovedField"));
        Field modifiedField = TransTarget.class.getField("targetModifiedField");
        assertTrue(Modifier.isPublic(modifiedField.getModifiers()));
        assertTrue(Modifier.isStatic(modifiedField.getModifiers()));
        assertFalse(Modifier.isPrivate(modifiedField.getModifiers()));

        assertDoesNotThrow(TransTarget::targetAdded);
    }

    private static byte[] classBytecode() throws IOException {
        try (InputStream input = TransTarget.class.getResourceAsStream(
                "/io/github/seraphina/test/TransTarget.class")) {
            if (input == null) {
                throw new IOException("Could not load TransTarget class bytecode");
            }
            return input.readAllBytes();
        }
    }

    private static final class SingleFieldTarget {
        private static int onlyField;
    }

    private static final class ByteArrayClassLoader extends ClassLoader {
        private ByteArrayClassLoader() {
            super(AgentFieldTransformationTest.class.getClassLoader());
        }

        private Class<?> define(String className, byte[] bytecode) {
            return defineClass(className, bytecode, 0, bytecode.length);
        }
    }
}
