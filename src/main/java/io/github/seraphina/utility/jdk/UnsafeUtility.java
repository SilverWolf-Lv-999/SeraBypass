package io.github.seraphina.utility.jdk;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

/** Provides low-level JDK internals required by the JVM utility packages. */
@SuppressWarnings("removal")
public final class UnsafeUtility {
    public static final Unsafe UNSAFE = getUnsafe();
    public static final MethodHandles.Lookup TRUSTED_LOOKUP = getTrustedLookup();

    private static Unsafe getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not obtain sun.misc.Unsafe", exception);
        }
    }

    private static MethodHandles.Lookup getTrustedLookup() {
        try {
            Field implLookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            Object base = UNSAFE.staticFieldBase(implLookupField);
            long offset = UNSAFE.staticFieldOffset(implLookupField);
            MethodHandles.Lookup lookup = (MethodHandles.Lookup) UNSAFE.getObject(base, offset);
            if (lookup == null) {
                throw new IllegalStateException("MethodHandles.Lookup.IMPL_LOOKUP is null");
            }
            return lookup;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not obtain the trusted method lookup", exception);
        }
    }
}
