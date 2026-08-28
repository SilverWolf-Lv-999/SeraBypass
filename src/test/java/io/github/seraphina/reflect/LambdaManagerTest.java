package io.github.seraphina.reflect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LambdaManagerTest {
    @Test
    void accessesPrivateFieldsAndCachesByTarget() {
        Probe probe = new Probe();
        LambdaField<Integer> field = LambdaManager.getField(Probe.class, "number", probe);
        assertEquals(1, field.get());
        field.set(9);
        assertEquals(9, probe.number);
        assertSame(field, LambdaManager.getField(Probe.class, "number", probe));
        LambdaField<String> staticField = LambdaManager.getField(Probe.class, "text");
        assertEquals("a", staticField.get());
        staticField.set("b");
        assertEquals("b", Probe.text);
    }

    @Test
    void invokesPrivateMethodsAndCachesCaptures() {
        Probe probe = new Probe();
        LambdaMethod<String> method = LambdaManager.getMethod(Probe.class, "join", probe, "x", 3);
        assertEquals("x3", method.get());
        assertSame(method, LambdaManager.getMethod(Probe.class, "join", probe, "x", 3));
        LambdaManager.getMethod(Probe.class, "touch", probe, 4).ivk();
        assertEquals(4, probe.number);
        LambdaMethod<String> varargs = LambdaManager.getMethod(Probe.class, "varargs", probe, "v", 1, 2);
        assertEquals("v12", varargs.get());
        assertSame(varargs, LambdaManager.getMethod(Probe.class, "varargs", probe, "v", 1, 2));
        assertEquals("s5", LambdaManager.getMethod(Probe.class, "staticJoin", null, "s", 5).get());
    }

    private static final class Probe {
        private static String text = "a";
        private volatile int number = 1;

        private String join(String prefix, int value) {
            return prefix + (value + number - number);
        }

        private void touch(int value) {
            number = value;
        }

        private String varargs(String prefix, int... values) {
            return prefix + values[0] + values[1];
        }

        private static String staticJoin(String prefix, int value) {
            return prefix + value;
        }
    }
}



