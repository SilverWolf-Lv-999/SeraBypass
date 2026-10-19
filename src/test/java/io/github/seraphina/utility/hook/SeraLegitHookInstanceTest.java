package io.github.seraphina.utility.hook;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeraLegitHookInstanceTest {
    @Test
    void addsSharesAndRemovesInstanceField() throws Exception {
        InstanceFieldTarget target = new InstanceFieldTarget();
        Field backingField = InstanceFieldTarget.class.getDeclaredField("backing");
        assertEquals(17, backingField.getInt(target));

        SeraLegitHook.addInstanceField(
                InstanceFieldTarget.class,
                "alias",
                "backing",
                Modifier.PRIVATE | Modifier.TRANSIENT);

        Field aliasField = InstanceFieldTarget.class.getDeclaredField("alias");
        assertFalse(Modifier.isStatic(aliasField.getModifiers()));
        assertEquals(17, aliasField.getInt(target));
        aliasField.setInt(target, 29);
        assertEquals(29, backingField.getInt(target));

        SeraLegitHook.removeField(InstanceFieldTarget.class, "alias");
        assertThrows(NoSuchFieldException.class,
                () -> InstanceFieldTarget.class.getDeclaredField("alias"));
        assertEquals(29, backingField.getInt(target));
    }

    @Test
    void addsInvokesAndRemovesInstanceMethod() throws Exception {
        InstanceMethodTarget target = new InstanceMethodTarget(3);
        Method originalMethod = InstanceMethodTarget.class.getDeclaredMethod("original");
        AtomicReference<Object> receiver = new AtomicReference<>();

        SeraLegitHook.addInstanceMethod(InstanceMethodTarget.class, "added", value -> {
            receiver.set(value);
            ((InstanceMethodTarget) value).value += 10;
        });

        Method addedMethod = InstanceMethodTarget.class.getDeclaredMethod("added");
        assertFalse(Modifier.isStatic(addedMethod.getModifiers()));
        addedMethod.invoke(target);
        assertSame(target, receiver.get());
        assertEquals(13, originalMethod.invoke(target));

        SeraLegitHook.removeMethod(InstanceMethodTarget.class, "added");
        assertThrows(NoSuchMethodException.class,
                () -> InstanceMethodTarget.class.getDeclaredMethod("added"));
        assertEquals(13, originalMethod.invoke(target));
    }

    @Test
    void keepsOtherAddedInstanceMethodsWhenOneIsRemoved() throws Exception {
        MultipleMethodTarget target = new MultipleMethodTarget();
        SeraLegitHook.addInstanceMethod(
                MultipleMethodTarget.class, "firstAdded", value -> ((MultipleMethodTarget) value).value += 1);
        SeraLegitHook.addInstanceMethod(
                MultipleMethodTarget.class, "secondAdded", value -> ((MultipleMethodTarget) value).value += 2);

        MultipleMethodTarget.class.getDeclaredMethod("firstAdded").invoke(target);
        MultipleMethodTarget.class.getDeclaredMethod("secondAdded").invoke(target);
        assertEquals(3, target.value);

        SeraLegitHook.removeMethod(MultipleMethodTarget.class, "firstAdded");
        assertThrows(NoSuchMethodException.class,
                () -> MultipleMethodTarget.class.getDeclaredMethod("firstAdded"));
        MultipleMethodTarget.class.getDeclaredMethod("secondAdded").invoke(target);
        assertEquals(5, target.value);
    }

    @Test
    void removesOriginalInstanceMembers() throws Exception {
        RemovalTarget target = new RemovalTarget();
        assertEquals(31, RemovalTarget.class.getDeclaredField("removable").getInt(target));
        RemovalTarget.class.getDeclaredMethod("original").invoke(target);

        SeraLegitHook.removeMethod(RemovalTarget.class, "original");
        assertThrows(NoSuchMethodException.class,
                () -> RemovalTarget.class.getDeclaredMethod("original"));

        SeraLegitHook.removeField(RemovalTarget.class, "removable");
        assertThrows(NoSuchFieldException.class,
                () -> RemovalTarget.class.getDeclaredField("removable"));
    }

    @Test
    void expandsConstantPoolAndReplacesInstanceMembers() throws Exception {
        MultiFieldTarget target = new MultiFieldTarget();

        SeraLegitHook.addInstanceField(
                MultiFieldTarget.class, "intAlias", "intBacking",
                Modifier.PRIVATE | Modifier.TRANSIENT);
        SeraLegitHook.addInstanceField(
                MultiFieldTarget.class, "longAlias", "longBacking",
                Modifier.PRIVATE | Modifier.TRANSIENT);
        SeraLegitHook.addInstanceField(
                MultiFieldTarget.class, "objectAlias", "objectBacking",
                Modifier.PRIVATE | Modifier.TRANSIENT);

        Field intAlias = MultiFieldTarget.class.getDeclaredField("intAlias");
        Field longAlias = MultiFieldTarget.class.getDeclaredField("longAlias");
        Field objectAlias = MultiFieldTarget.class.getDeclaredField("objectAlias");
        assertEquals(11, intAlias.getInt(target));
        assertEquals(13L, longAlias.getLong(target));
        assertSame(target.objectBacking, objectAlias.get(target));

        intAlias.setInt(target, 17);
        longAlias.setLong(target, 19L);
        Object replacement = new Object();
        objectAlias.set(target, replacement);
        assertEquals(17, target.intBacking);
        assertEquals(19L, target.longBacking);
        assertSame(replacement, target.objectBacking);

        SeraLegitHook.addInstanceMethod(MultiFieldTarget.class, "dynamicMethod",
                value -> ((MultiFieldTarget) value).intBacking += 5);
        MultiFieldTarget.class.getDeclaredMethod("dynamicMethod").invoke(target);
        assertEquals(22, target.intBacking);

        SeraLegitHook.removeMethod(MultiFieldTarget.class, "dynamicMethod");
        assertThrows(NoSuchMethodException.class,
                () -> MultiFieldTarget.class.getDeclaredMethod("dynamicMethod"));
        SeraLegitHook.addInstanceMethod(MultiFieldTarget.class, "dynamicMethod",
                value -> ((MultiFieldTarget) value).intBacking += 7);
        MultiFieldTarget.class.getDeclaredMethod("dynamicMethod").invoke(target);
        assertEquals(29, target.intBacking);
        SeraLegitHook.removeMethod(MultiFieldTarget.class, "dynamicMethod");

        SeraLegitHook.addInstanceField(
                MultiFieldTarget.class, "fieldAgain", "intBacking",
                Modifier.PRIVATE | Modifier.TRANSIENT);
        SeraLegitHook.removeField(MultiFieldTarget.class, "fieldAgain");
        assertThrows(NoSuchFieldException.class,
                () -> MultiFieldTarget.class.getDeclaredField("fieldAgain"));
        SeraLegitHook.addInstanceField(
                MultiFieldTarget.class, "fieldAgain", "intBacking",
                Modifier.PRIVATE | Modifier.TRANSIENT);
        assertEquals(29, MultiFieldTarget.class.getDeclaredField("fieldAgain").getInt(target));

        SeraLegitHook.removeField(MultiFieldTarget.class, "objectAlias");
        SeraLegitHook.removeField(MultiFieldTarget.class, "longAlias");
        SeraLegitHook.removeField(MultiFieldTarget.class, "intAlias");
        SeraLegitHook.removeField(MultiFieldTarget.class, "fieldAgain");
        assertThrows(NoSuchFieldException.class,
                () -> MultiFieldTarget.class.getDeclaredField("intAlias"));
    }

    private static final class InstanceFieldTarget {
        private int backing = 17;
    }

    private static final class InstanceMethodTarget {
        private int value;

        private InstanceMethodTarget(int value) {
            this.value = value;
        }

        private int original() {
            return value;
        }
    }

    private static final class MultipleMethodTarget {
        private int value;
    }

    private static final class RemovalTarget {
        private int removable = 31;

        private void original() {
        }
    }

    private static final class MultiFieldTarget {
        private int intBacking = 11;
        private long longBacking = 13L;
        private Object objectBacking = new Object();
    }
}
