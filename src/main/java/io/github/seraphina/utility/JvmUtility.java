package io.github.seraphina.utility;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sun.misc.Unsafe;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("all")
public final class JvmUtility {
    private static final long RECOVERY_PERIOD_MILLIS = 1000L;
    private static final Object LOCK = new Object();
    private static final Logger LOGGER = LogManager.getLogger();

    private static final long CLASS_KLASS_OFFSET = 16L;
    private static final long OBJECT_KLASS_OFFSET = 8L;
    private static final long KLASS_JAVA_MIRROR_OFFSET = 112L;
    private static final long KLASS_SUBKLASS_OFFSET = 128L;
    private static final long KLASS_NEXT_SIBLING_OFFSET = 136L;
    private static final long ARRAY_LENGTH_OFFSET_COMPRESSED_KLASS = 12L;
    private static final long ARRAY_LENGTH_OFFSET_WIDE_KLASS = 16L;
    private static final int MAX_KLASS_COUNT = 1_000_000;
    private static final int MAX_OBJECT_ARRAY_LENGTH = 16_000_000;
    private static final String PROTECTED_THREAD_PREFIX = "!!!sera&thread_";
    private static final Object THREAD_PROTECTION_LOCK = new Object();

    private static final Set<Thread> protectedThreads =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static volatile boolean patched;
    private static ScheduledExecutorService recoveryExecutor;
    private static volatile HotSpotMemoryLayout memoryLayout;

    public static Set<Class<?>> getAllLoaedClasses() {
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

    public static Set<Object> getAllLoaedObjects() {
        HotSpotMemoryLayout layout = memoryLayout();
        Set<Class<?>> classes = getAllLoaedClasses();
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

        long child = layout.metadataPointer(klassPointer + KLASS_SUBKLASS_OFFSET);
        int siblingCount = 0;
        while (layout.isPlausibleMetadataPointer(child) && siblingCount++ < MAX_KLASS_COUNT) {
            walkKlass(child, layout, classes, seenKlasses);
            child = layout.metadataPointer(child + KLASS_NEXT_SIBLING_OFFSET);
        }
    }




    private static HotSpotMemoryLayout memoryLayout() {
        HotSpotMemoryLayout current = memoryLayout;
        if (current != null) {
            return current;
        }
        synchronized (JvmUtility.class) {
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
        private final Unsafe unsafe = JDKUtility.UNSAFE;
        private final ReferenceSlot referenceSlot = new ReferenceSlot();
        private final long referenceSlotValueOffset;
        private final boolean compressedOops;
        private final long narrowOopBase;
        private final int narrowOopShift;
        private final boolean compressedKlasses;
        private final long narrowKlassBase;
        private final int narrowKlassShift;
        private final long arrayLengthOffset;
        private final long klassWordSize;

        private HotSpotMemoryLayout() {
            verifyJdk17HotSpot();
            try {
                Field valueField = ReferenceSlot.class.getDeclaredField("value");
                referenceSlotValueOffset = unsafe.objectFieldOffset(valueField);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not locate the reference slot", exception);
            }

            compressedOops = unsafe.arrayIndexScale(Object[].class) == Integer.BYTES;
            NarrowOopEncoding oopEncoding = compressedOops
                    ? deriveNarrowOopEncoding()
                    : new NarrowOopEncoding(0L, 0);
            narrowOopBase = oopEncoding.base;
            narrowOopShift = oopEncoding.shift;
            ClassEncoding classEncoding = deriveKlassEncoding();
            compressedKlasses = classEncoding.compressed;
            narrowKlassBase = classEncoding.base;
            narrowKlassShift = classEncoding.shift;
            arrayLengthOffset = compressedKlasses
                    ? ARRAY_LENGTH_OFFSET_COMPRESSED_KLASS
                    : ARRAY_LENGTH_OFFSET_WIDE_KLASS;
            klassWordSize = compressedKlasses ? Integer.BYTES : Long.BYTES;

        }

        private void verifyJdk17HotSpot() {
            String version = System.getProperty("java.specification.version", "");
            String vmName = System.getProperty("java.vm.name", "");
            if (!"17".equals(version)
                    || !(vmName.contains("HotSpot") || vmName.contains("OpenJDK"))) {
                throw new IllegalStateException(
                        "JvmUtility memory walkers require JDK 17 HotSpot; found "
                                + version + " / " + vmName);
            }
            if (unsafe.addressSize() != Long.BYTES) {
                throw new IllegalStateException("JvmUtility memory walkers require a 64-bit JVM");
            }
        }

        private ClassEncoding deriveKlassEncoding() {
            Object object = new Object();
            Object string = new String("klass-encoding");
            long objectKlass = klassPointer(Object.class);
            long stringKlass = klassPointer(String.class);
            int[] array = new int[1];
            int compressedLength = unsafe.getInt(array, ARRAY_LENGTH_OFFSET_COMPRESSED_KLASS);
            int wideLength = unsafe.getInt(array, ARRAY_LENGTH_OFFSET_WIDE_KLASS);
            if (compressedLength == 1 && wideLength != 1) {
                // The array length is at offset 12 when the Klass pointer is narrow.
                // The first element is at offset 16 and is deliberately zero.
                long objectNarrow = Integer.toUnsignedLong(unsafe.getInt(object, OBJECT_KLASS_OFFSET));
                long stringNarrow = Integer.toUnsignedLong(unsafe.getInt(string, OBJECT_KLASS_OFFSET));
                long rawDelta = stringKlass - objectKlass;
                long narrowDelta = stringNarrow - objectNarrow;
                if (rawDelta <= 0L || narrowDelta <= 0L || rawDelta % narrowDelta != 0L) {
                    throw new IllegalStateException("Could not derive compressed Klass encoding");
                }
                long scale = rawDelta / narrowDelta;
                if (scale <= 0L || Long.bitCount(scale) != 1) {
                    throw new IllegalStateException("Unsupported compressed Klass scale " + scale);
                }
                int shift = Long.numberOfTrailingZeros(scale);
                long base = objectKlass - (objectNarrow << shift);
                if (decodeKlass(objectNarrow, base, shift) != objectKlass
                        || decodeKlass(stringNarrow, base, shift) != stringKlass) {
                    throw new IllegalStateException("Compressed Klass encoding verification failed");
                }
                return new ClassEncoding(true, base, shift);
            }
            if (wideLength == 1 && compressedLength != 1
                    && unsafe.getLong(object, OBJECT_KLASS_OFFSET) == objectKlass) {
                return new ClassEncoding(false, 0L, 0);
            }
            throw new IllegalStateException("Could not identify the JDK 17 Klass pointer width");

        }

        private long klassPointer(Class<?> klass) {
            long pointer = unsafe.getLong(klass, CLASS_KLASS_OFFSET);
            if (!isPlausibleMetadataPointer(pointer)) {
                throw new IllegalStateException("Invalid java.lang.Class Klass pointer for " + klass);
            }
            return pointer;
        }

        private long klassPointerAt(long objectAddress) {
            if (compressedKlasses) {
                long narrow = Integer.toUnsignedLong(unsafe.getInt(objectAddress + OBJECT_KLASS_OFFSET));
                return decodeKlass(narrow, narrowKlassBase, narrowKlassShift);
            }
            return unsafe.getLong(objectAddress + OBJECT_KLASS_OFFSET);
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
            long mirrorHandle = unsafe.getLong(klassPointer + KLASS_JAVA_MIRROR_OFFSET);
            if (mirrorHandle < 0x10000L) {
                return 0L;
            }
            return unsafe.getLong(mirrorHandle);
        }

        private NarrowOopEncoding deriveNarrowOopEncoding() {
            long objectMirror = javaMirrorReferenceValue(klassPointer(Object.class));
            long stringMirror = javaMirrorReferenceValue(klassPointer(String.class));
            long objectNarrow = narrowOop(Object.class);
            long stringNarrow = narrowOop(String.class);
            long rawDelta = stringMirror - objectMirror;
            long narrowDelta = stringNarrow - objectNarrow;

            if (rawDelta == 0L || narrowDelta == 0L || rawDelta % narrowDelta != 0L) {
                throw new IllegalStateException("Could not derive compressed OOP encoding");
            }

            long scale = rawDelta / narrowDelta;
            if (scale <= 0L || Long.bitCount(scale) != 1) {
                throw new IllegalStateException("Unsupported compressed OOP scale " + scale);
            }

            int shift = Long.numberOfTrailingZeros(scale);
            long base = objectMirror - (objectNarrow << shift);
            if (decodeOop(objectNarrow, base, shift) != objectMirror
                    || decodeOop(stringNarrow, base, shift) != stringMirror) {
                throw new IllegalStateException("Compressed OOP encoding verification failed");
            }
            return new NarrowOopEncoding(base, shift);
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

        private record NarrowOopEncoding(long base, int shift) {
        }

        private record ClassEncoding(boolean compressed, long base, int shift) {
        }
    }

    public static void protectThread(Thread thread) {
        Objects.requireNonNull(thread, "thread");

        synchronized (THREAD_PROTECTION_LOCK) {
            if (protectedThreads.contains(thread)) {
                return;
            }

            String threadName = thread.getName();
            if (threadName.isEmpty()) {
                threadName = "unnamed";
            }
            if (!threadName.startsWith(PROTECTED_THREAD_PREFIX)) {
                thread.setName(PROTECTED_THREAD_PREFIX + threadName);
            }

            protectedThreads.add(thread);
        }
    }

    public static void peerJVMTI() {
        synchronized (LOCK) {
            if (patched) {
                return;
            }
            if (!CPPUtility.initializeJvmti()) {
                LOGGER.debug("Could not initialize the Java JVMTI peer neutralizer");
                return;
            }

            CPPUtility.neutralizeAlienEnvironments();
            CPPUtility.disarmAlienEnvironments();
            CPPUtility.recoverJvmti();
            CPPUtility.disarmAlienEnvironments();

            patched = true;
            recoveryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ninecun-lingtai-jvmti-recovery");
                thread.setDaemon(true);
                return thread;
            });
            recoveryExecutor.scheduleWithFixedDelay(
                    JvmUtility::recoverJvmti,
                    RECOVERY_PERIOD_MILLIS,
                    RECOVERY_PERIOD_MILLIS,
                    TimeUnit.MILLISECONDS
            );
            LOGGER.info("Java JVMTI peer neutralizer initialized");
        }
    }

    public static boolean isJvmtiPatched() {
        return patched;
    }

    public static void shutdownJvmti() {
        synchronized (LOCK) {
            patched = false;
            if (recoveryExecutor != null) {
                recoveryExecutor.shutdownNow();
                recoveryExecutor = null;
            }
            CPPUtility.shutdownJvmti();
        }
    }

    private static void recoverJvmti() {
        if (!patched) {
            return;
        }
        try {
            if (CPPUtility.recoverJvmti() > 0) {
                CPPUtility.disarmAlienEnvironments();
            }
        } catch (Throwable throwable) {
            LOGGER.debug("JVMTI peer neutralizer recovery failed", throwable);
        }
    }
}
