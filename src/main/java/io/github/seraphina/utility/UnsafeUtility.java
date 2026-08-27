package io.github.seraphina.utility;

import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;

@SuppressWarnings("removal")
public final class UnsafeUtility {
    public static final Unsafe UNSAFE;
    public static final MethodHandles.Lookup TRUSTED_LOOKUP;

    static {
        try {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            UNSAFE = (Unsafe) unsafeField.get(null);

            Field lookupField = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            Object base = UNSAFE.staticFieldBase(lookupField);
            long offset = UNSAFE.staticFieldOffset(lookupField);
            TRUSTED_LOOKUP = (MethodHandles.Lookup) UNSAFE.getObject(base, offset);
            if (TRUSTED_LOOKUP == null) {
                throw new IllegalStateException("MethodHandles.Lookup.IMPL_LOOKUP is null");
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not obtain JDK internals", exception);
        }
    }
}
