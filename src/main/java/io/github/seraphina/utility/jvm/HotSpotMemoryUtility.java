package io.github.seraphina.utility.jvm;

import io.github.seraphina.jnct.api.JVM;
import io.github.seraphina.utility.UnsafeUtility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

@SuppressWarnings("all")
public final class HotSpotMemoryUtility {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int MAX_KLASS_COUNT = 1_000_000;
    private static final int MAX_OBJECT_ARRAY_LENGTH = 16_000_000;
    private static volatile HotSpotMemoryLayout memoryLayout;

    public static Set<Class<?>> getAllLoadedClasses() {
        HotSpotMemoryLayout layout = memoryLayout();
        LinkedHashSet<Class<?>> classes = new LinkedHashSet<>();
        Set<Long> seenKlasses = new java.util.HashSet<>();

        long objectKlass = layout.klassPointer(Object.class);
        walkKlass(objectKlass, layout, classes, seenKlasses);

        Collections.addAll(classes,
                boolean.class, byte.class, char.class, short.class, int.class, long.class,
                float.class, double.class, void.class);
        return classes;
    }

    public static Set<Object> getAllLoadedObjects() {
        HotSpotMemoryLayout layout = memoryLayout();
        Set<Class<?>> classes = getAllLoadedClasses();
        Set<Object> objects = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> pending = new ArrayDeque<>();

        addObjectRoot(objects, pending, layout.referenceSlot);
        addObjectRoot(objects, pending, classes);
        for (Class<?> klass : classes) {
            addObjectRoot(objects, pending, klass);
        }
        addObjectRoot(objects, pending, Thread.currentThread());
        addObjectRoot(objects, pending, Runtime.getRuntime());
        addObjectRoot(objects, pending, System.getProperties());
        addObjectRoot(objects, pending, System.in);
        addObjectRoot(objects, pending, System.out);
        addObjectRoot(objects, pending, System.err);

        try {
            ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            while (systemClassLoader != null) {
                addObjectRoot(objects, pending, systemClassLoader);
                systemClassLoader = systemClassLoader.getParent();
            }
        } catch (Throwable ignored) {
        }

        try {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                addObjectRoot(objects, pending, thread);
            }
        } catch (Throwable ignored) {
        }

        collectStaticReferenceRoots(layout, classes, objects, pending);
        walkReachableObjectGraph(layout, objects, pending);
        return objects;
    }

    private static void collectStaticReferenceRoots(
            HotSpotMemoryLayout layout,
            Set<Class<?>> classes,
            Set<Object> objects,
            ArrayDeque<Object> pending) {
        for (Class<?> klass : classes) {
            Field[] fields;
            try {
                fields = klass.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers())
                        || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    Object base = layout.unsafe.staticFieldBase(field);
                    long offset = layout.unsafe.staticFieldOffset(field);
                    addObjectRoot(objects, pending,
                            layout.unsafe.getObjectVolatile(base, offset));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void walkReachableObjectGraph(
            HotSpotMemoryLayout layout,
            Set<Object> objects,
            ArrayDeque<Object> pending) {
        while (!pending.isEmpty()) {
            Object object = pending.removeFirst();
            Class<?> objectClass;
            try {
                objectClass = object.getClass();
            } catch (Throwable ignored) {
                continue;
            }

            if (objectClass.isArray()) {
                collectArrayReferences(layout, object, objectClass, objects, pending);
                continue;
            }

            for (Class<?> type = objectClass; type != null; type = type.getSuperclass()) {
                Field[] fields;
                try {
                    fields = type.getDeclaredFields();
                } catch (Throwable ignored) {
                    continue;
                }
                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers())
                            || field.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        long offset = layout.unsafe.objectFieldOffset(field);
                        addObjectRoot(objects, pending,
                                layout.unsafe.getObject(object, offset));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static void collectArrayReferences(
            HotSpotMemoryLayout layout,
            Object array,
            Class<?> arrayClass,
            Set<Object> objects,
            ArrayDeque<Object> pending) {
        Class<?> componentType = arrayClass.getComponentType();
        if (componentType == null || componentType.isPrimitive()) {
            return;
        }
        int length;
        try {
            length = Array.getLength(array);
        } catch (IllegalArgumentException exception) {
            return;
        }
        if (length > MAX_OBJECT_ARRAY_LENGTH) {
            LOGGER.debug("Skipping oversized object array of length {}", length);
            return;
        }
        long baseOffset = layout.unsafe.arrayBaseOffset(arrayClass);
        long indexScale = layout.unsafe.arrayIndexScale(arrayClass);
        for (int index = 0; index < length; index++) {
            try {
                addObjectRoot(objects, pending,
                        layout.unsafe.getObject(array, baseOffset + index * indexScale));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void addObjectRoot(
            Set<Object> objects,
            ArrayDeque<Object> pending,
            Object object) {
        if (object != null && objects.add(object)) {
            pending.addLast(object);
        }
    }

    private static void walkKlass(
            long klassPointer,
            HotSpotMemoryLayout layout,
            Set<Class<?>> classes,
            Set<Long> seenKlasses) {
        if (!layout.isPlausibleMetadataPointer(klassPointer)
                || !seenKlasses.add(klassPointer)
                || seenKlasses.size() > MAX_KLASS_COUNT) {
            return;
        }

        Class<?> mirror = layout.classMirror(klassPointer);
        if (mirror != null) {
            classes.add(mirror);
        }

        long child = layout.metadataPointer(klassPointer + layout.jvmLayout.klassSubklassOffset);
        int siblingCount = 0;
        while (layout.isPlausibleMetadataPointer(child) && siblingCount++ < MAX_KLASS_COUNT) {
            walkKlass(child, layout, classes, seenKlasses);
            child = layout.metadataPointer(child + layout.jvmLayout.klassNextSiblingOffset);
        }
    }




    private static HotSpotMemoryLayout memoryLayout() {
        HotSpotMemoryLayout current = memoryLayout;
        if (current != null) {
            return current;
        }
        synchronized (HotSpotMemoryUtility.class) {
            current = memoryLayout;
            if (current == null) {
                current = new HotSpotMemoryLayout();
                memoryLayout = current;
            }
            return current;
        }
    }

    private static final class ReferenceSlot {
        private volatile Object value;
    }

    private static final class HotSpotMemoryLayout {
        private final Unsafe unsafe = UnsafeUtility.UNSAFE;
        private final JVM jvmLayout;
        private final ReferenceSlot referenceSlot = new ReferenceSlot();
        private final long referenceSlotValueOffset;
        private final boolean compressedOops;
        private final long narrowOopBase;
        private final int narrowOopShift;
        private final boolean compressedKlasses;
        private final long narrowKlassBase;
        private final int narrowKlassShift;

        private HotSpotMemoryLayout() {
            jvmLayout = JVM.INSTANCE;
            jvmLayout.requireValid();
            verifyJdk17HotSpot();

            referenceSlotValueOffset = jvmLayout.referenceSlotValueOffset;
            compressedOops = jvmLayout.compressedOops;
            narrowOopBase = jvmLayout.narrowOopBase;
            narrowOopShift = jvmLayout.narrowOopShift;
            compressedKlasses = jvmLayout.compressedKlasses;
            narrowKlassBase = jvmLayout.narrowKlassBase;
            narrowKlassShift = jvmLayout.narrowKlassShift;
        }

        private void verifyJdk17HotSpot() {
            String version = System.getProperty("java.specification.version", "");
            String vmName = System.getProperty("java.vm.name", "");
            if (!"17".equals(version)
                    || !(vmName.contains("HotSpot") || vmName.contains("OpenJDK"))) {
                throw new IllegalStateException(
                        "HotSpotMemoryUtility memory walkers require JDK 17 HotSpot; found "
                                + version + " / " + vmName);
            }
            if (unsafe.addressSize() != Long.BYTES) {
                throw new IllegalStateException("HotSpotMemoryUtility memory walkers require a 64-bit JVM");
            }
        }

        private long klassPointerAt(long objectAddress) {
            if (compressedKlasses) {
                long narrow = Integer.toUnsignedLong(unsafe.getInt(objectAddress + jvmLayout.objectKlassOffset));
                return decodeKlass(narrow, narrowKlassBase, narrowKlassShift);
            }
            return unsafe.getLong(objectAddress + jvmLayout.objectKlassOffset);
        }

        private long decodeKlass(long narrow, long base, int shift) {
            return base + (narrow << shift);
        }

        private long metadataPointer(long address) {
            return unsafe.getLong(address);
        }

        private boolean isPlausibleMetadataPointer(long pointer) {
            return pointer >= 0x10000L
                    && pointer <= 0x00007FFF_FFFFFFFFL
                    && (pointer & (Long.BYTES - 1L)) == 0L;
        }

        private Class<?> classMirror(long klassPointer) {
            try {
                long mirrorReference = javaMirrorReferenceValue(klassPointer);
                Object mirror = referenceFromEncodedOop(mirrorReference);
                return mirror instanceof Class<?> ? (Class<?>) mirror : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private long javaMirrorReferenceValue(long klassPointer) {
            if (!isPlausibleMetadataPointer(klassPointer)) {
                return 0L;
            }
            long mirrorHandle = unsafe.getLong(klassPointer + jvmLayout.klassJavaMirrorOffset);
            if (mirrorHandle < 0x10000L) {
                return 0L;
            }
            return unsafe.getLong(mirrorHandle);
        }

        private long narrowOop(Object value) {
            synchronized (referenceSlot) {
                try {
                    referenceSlot.value = value;
                    return Integer.toUnsignedLong(
                            unsafe.getIntVolatile(referenceSlot, referenceSlotValueOffset));
                } finally {
                    referenceSlot.value = null;
                }
            }
        }

        private long decodeOop(long narrow, long base, int shift) {
            return base + (narrow << shift);
        }

        private Object referenceFromEncodedOop(long rawOop) {
            if (rawOop <= 0L) {
                return null;
            }
            synchronized (referenceSlot) {
                try {
                    if (compressedOops) {
                        long delta = rawOop - narrowOopBase;
                        long alignmentMask = (1L << narrowOopShift) - 1L;
                        if (delta < 0L || (delta & alignmentMask) != 0L) {
                            return null;
                        }
                        long narrowOop = delta >>> narrowOopShift;
                        if (narrowOop > 0xFFFF_FFFFL) {
                            return null;
                        }
                        unsafe.putIntVolatile(
                                referenceSlot, referenceSlotValueOffset, (int) narrowOop);
                    } else {
                        unsafe.putLongVolatile(referenceSlot, referenceSlotValueOffset, rawOop);
                    }
                    return unsafe.getObjectVolatile(referenceSlot, referenceSlotValueOffset);
                } finally {
                    unsafe.putObjectVolatile(referenceSlot, referenceSlotValueOffset, null);
                }
            }
        }

    }



}



