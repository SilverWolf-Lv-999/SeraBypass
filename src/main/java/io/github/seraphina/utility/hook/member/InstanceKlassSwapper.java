package io.github.seraphina.utility.hook.member;

import io.github.seraphina.jnct.api.JVM;
import io.github.seraphina.utility.UnsafeUtility;
import org.objectweb.asm.ClassReader;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class InstanceKlassSwapper {
    private static final Object MIRROR_LOCK = new Object();
    private static final Object INSTALL_LOCK = new Object();
    private static final List<InstalledClass> INSTALLED = new ArrayList<>();
    private static final ReferenceSlot MIRROR_SLOT = new ReferenceSlot();

    static List<Class<?>> captureHierarchy(Class<?> target) {
        JVM jvm = JVM.getInstance();
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        List<Class<?>> hierarchy = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        ArrayDeque<Long> pending = new ArrayDeque<>();
        pending.push(unsafe.getLong(target, jvm.classKlassOffset));
        while (!pending.isEmpty()) {
            long klass = pending.pop();
            if (klass == 0L || !visited.add(klass)) {
                continue;
            }
            hierarchy.add(classMirror(klass, jvm));
            List<Long> children = new ArrayList<>();
            long child = unsafe.getLong(klass + jvm.klassSubklassOffset);
            while (child != 0L) {
                if (!visited.contains(child)) {
                    children.add(child);
                }
                child = unsafe.getLong(child + jvm.klassNextSiblingOffset);
            }
            for (int index = children.size() - 1; index >= 0; index--) {
                pending.push(children.get(index));
            }
        }
        return hierarchy;
    }

    static Change swap(List<Class<?>> hierarchy, Map<Class<?>, byte[]> classFiles) {
        JVM jvm = JVM.getInstance();
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        List<SwapRecord> records = new ArrayList<>(hierarchy.size());
        for (Class<?> target : hierarchy) {
            byte[] bytes = classFiles.get(target);
            if (bytes == null) {
                throw new IllegalStateException("No class file was supplied for " + target.getName());
            }
            String internalName = new ClassReader(bytes).getClassName();
            if (!internalName.equals(target.getName().replace('.', '/'))) {
                throw new IllegalStateException(
                        "Class file " + internalName + " does not match " + target.getName());
            }
            long oldKlass = unsafe.getLong(target, jvm.classKlassOffset);
            if (oldKlass == 0L) {
                throw new IllegalStateException("Class has no Klass metadata: " + target.getName());
            }
            long dictionaryLiteral = findDictionaryLiteral(oldKlass, jvm);
            long originalSuper = unsafe.getLong(oldKlass + jvm.klassSuperOffset);
            long classLoaderData = unsafe.getLong(oldKlass + jvm.klassClassLoaderDataOffset);
            if (classLoaderData == 0L) {
                throw new IllegalStateException("Klass has no ClassLoaderData: " + target.getName());
            }
            records.add(new SwapRecord(
                    target,
                    bytes,
                    oldKlass,
                    originalSuper,
                    classLoaderData,
                    dictionaryLiteral,
                    unsafe.getLong(oldKlass + jvm.klassJavaMirrorOffset),
                    captureStaticValues(target)));
        }
        List<HierarchySnapshot> hierarchySnapshots = captureHierarchyChains(records, jvm);
        List<ClassLoaderSnapshot> classLoaderSnapshots =
                captureClassLoaderChains(records, jvm);
        Change change = new Change(records, hierarchySnapshots, classLoaderSnapshots);
        Map<Long, Long> replacements = new HashMap<>();
        try {
            for (SwapRecord record : records) {
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                        record.target, UnsafeUtility.TRUSTED_LOOKUP);
                Class<?> replacement = lookup.defineHiddenClass(
                                record.classFile,
                                false,
                                MethodHandles.Lookup.ClassOption.NESTMATE,
                                MethodHandles.Lookup.ClassOption.STRONG)
                        .lookupClass();
                long replacementKlass = unsafe.getLong(replacement, jvm.classKlassOffset);
                if (replacementKlass == 0L) {
                    throw new IllegalStateException(
                            "Replacement class has no Klass metadata: " + record.target.getName());
                }
                record.replacement = replacement;
                record.replacementKlass = replacementKlass;
                record.replacementMirrorHandle = unsafe.getLong(
                        replacementKlass + jvm.klassJavaMirrorOffset);
                replacements.put(record.oldKlass, replacementKlass);
                long replacementClassLoader =
                        unsafe.getLong(replacementKlass + jvm.klassClassLoaderDataOffset);
                if (replacementClassLoader != record.classLoaderData) {
                    throw new IllegalStateException(
                            "Replacement class did not retain ClassLoaderData: " + record.target.getName());
                }
                long oldName = unsafe.getLong(record.oldKlass + jvm.klassNameOffset);
                long replacementName = unsafe.getLong(replacementKlass + jvm.klassNameOffset);
                record.oldName = oldName;
                record.replacementName = replacementName;
                unsafe.putLong(record.oldKlass + jvm.klassNameOffset, replacementName);
                unsafe.putLong(replacementKlass + jvm.klassNameOffset, oldName);
                unsafe.putLongVolatile(null, record.dictionaryLiteral, replacementKlass);
            }
            for (SwapRecord record : records) {
                Class<?> replacement = record.replacement;
                long replacementKlass = record.replacementKlass;
                applyStaticValues(record.target, replacement, record.staticValues);
                long mirrorHandle = record.mirrorHandle;
                long replacementMirrorHandle = record.replacementMirrorHandle;
                long name = record.oldName;
                int accessFlags = unsafe.getInt(record.oldKlass + jvm.klassAccessFlagsOffset);
                byte initState = unsafe.getByte(
                        record.oldKlass + jvm.instanceKlassInitStateOffset);
                if (mirrorHandle == 0L || replacementMirrorHandle == 0L || name == 0L
                        || mirrorHandle == replacementMirrorHandle
                        || unsafe.getLong(record.target, jvm.classKlassOffset) != record.oldKlass
                        || unsafe.getLong(replacement, jvm.classKlassOffset) != replacementKlass) {
                    throw new IllegalStateException(
                            "Incomplete HotSpot class metadata for " + record.target.getName());
                }
                unsafe.putLong(replacementKlass + jvm.klassJavaMirrorOffset, mirrorHandle);
                unsafe.putLong(record.oldKlass + jvm.klassJavaMirrorOffset, replacementMirrorHandle);
                unsafe.putLong(replacementKlass + jvm.klassNameOffset, name);
                unsafe.putLong(
                        replacementKlass + jvm.klassClassLoaderDataOffset, record.classLoaderData);
                long expectedSuper = replacements.getOrDefault(
                        record.originalSuper, record.originalSuper);
                long replacementSuper = unsafe.getLong(
                        replacementKlass + jvm.klassSuperOffset);
                if (replacementSuper != expectedSuper) {
                    throw new IllegalStateException(
                            "Replacement class resolved an unexpected superclass: "
                                    + record.target.getName()
                                    + " oldSuper=0x" + Long.toHexString(record.originalSuper)
                                    + " expected=0x" + Long.toHexString(expectedSuper)
                                    + " actual=0x" + Long.toHexString(replacementSuper));
                }
                unsafe.putInt(replacementKlass + jvm.klassAccessFlagsOffset, accessFlags);
                unsafe.putByte(
                        replacementKlass + jvm.instanceKlassInitStateOffset, initState);
                clearReflectionData(record.target, jvm);
                clearReflectionData(replacement, jvm);
                unsafe.putLongVolatile(replacement, jvm.classKlassOffset, record.oldKlass);
                unsafe.putLongVolatile(
                        null, record.dictionaryLiteral, replacementKlass);
                unsafe.putLongVolatile(
                        record.target, jvm.classKlassOffset, replacementKlass);
            }
            rebuildHierarchy(change, replacements, jvm);
            rebuildClassLoaderData(change, replacements, jvm);
            for (SwapRecord record : records) {
                clearReflectionData(record.target, jvm);
            }
            return change;
        } catch (Throwable throwable) {
            rollback(change);
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Could not install replacement classes", throwable);
        }
    }

    static void commit(Change change) {
        synchronized (INSTALL_LOCK) {
            for (SwapRecord record : change.records) {
                INSTALLED.add(new InstalledClass(
                        record.target, record.replacement, record.oldKlass));
            }
        }
    }

    static void rollback(Change change) {
        JVM jvm = JVM.getInstance();
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        restoreClassLoaderData(change.classLoaderSnapshots, jvm);
        restoreHierarchy(change.hierarchySnapshots, jvm);
        for (int index = change.records.size() - 1; index >= 0; index--) {
            SwapRecord record = change.records.get(index);
            clearReflectionData(record.target, jvm);
            if (record.replacement != null && record.replacementKlass != 0L) {
                unsafe.putLong(record.oldKlass + jvm.klassJavaMirrorOffset, record.mirrorHandle);
                if (record.replacementMirrorHandle != 0L) {
                    unsafe.putLong(
                            record.replacementKlass + jvm.klassJavaMirrorOffset,
                            record.replacementMirrorHandle);
                }
                unsafe.putLongVolatile(
                        record.replacement, jvm.classKlassOffset, record.replacementKlass);
            }
            unsafe.putLongVolatile(record.target, jvm.classKlassOffset, record.oldKlass);
            unsafe.putLongVolatile(null, record.dictionaryLiteral, record.oldKlass);
            if (record.oldName != 0L) {
                unsafe.putLong(record.oldKlass + jvm.klassNameOffset, record.oldName);
                unsafe.putLong(record.replacementKlass + jvm.klassNameOffset, record.replacementName);
            }
        }
        for (SwapRecord record : change.records) {
            clearReflectionData(record.target, jvm);
        }
    }

    private static List<HierarchySnapshot> captureHierarchyChains(
            List<SwapRecord> records, JVM jvm) {
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        Map<Long, List<Long>> chains = new LinkedHashMap<>();
        for (SwapRecord record : records) {
            if (record.originalSuper != 0L) {
                chains.computeIfAbsent(record.originalSuper, ignored -> new ArrayList<>());
            }
        }
        for (long parent : new ArrayList<>(chains.keySet())) {
            long child = unsafe.getLong(parent + jvm.klassSubklassOffset);
            Set<Long> visited = new HashSet<>();
            while (child != 0L && visited.add(child)) {
                chains.get(parent).add(child);
                child = unsafe.getLong(child + jvm.klassNextSiblingOffset);
            }
        }
        for (SwapRecord record : records) {
            if (record.originalSuper != 0L
                    && !chains.get(record.originalSuper).contains(record.oldKlass)) {
                throw new IllegalStateException(
                        "Klass is missing from its superclass sibling chain: " + record.target.getName());
            }
        }
        List<HierarchySnapshot> snapshots = new ArrayList<>(chains.size());
        for (Map.Entry<Long, List<Long>> entry : chains.entrySet()) {
            snapshots.add(new HierarchySnapshot(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return snapshots;
    }

    private static List<ClassLoaderSnapshot> captureClassLoaderChains(
            List<SwapRecord> records, JVM jvm) {
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        Map<Long, List<Long>> chains = new LinkedHashMap<>();
        for (SwapRecord record : records) {
            chains.computeIfAbsent(record.classLoaderData, ignored -> new ArrayList<>());
        }
        for (long classLoaderData : new ArrayList<>(chains.keySet())) {
            long klass = unsafe.getLong(classLoaderData + jvm.classLoaderDataKlassesOffset);
            Set<Long> visited = new HashSet<>();
            while (klass != 0L && visited.add(klass)) {
                chains.get(classLoaderData).add(klass);
                klass = unsafe.getLong(klass + jvm.klassNextLinkOffset);
            }
        }
        List<ClassLoaderSnapshot> snapshots = new ArrayList<>(chains.size());
        for (Map.Entry<Long, List<Long>> entry : chains.entrySet()) {
            snapshots.add(new ClassLoaderSnapshot(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return snapshots;
    }

    private static void rebuildHierarchy(
            Change change, Map<Long, Long> replacements, JVM jvm) {
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        for (HierarchySnapshot snapshot : change.hierarchySnapshots) {
            long parent = replacements.getOrDefault(snapshot.parent, snapshot.parent);
            List<Long> desired = new ArrayList<>(snapshot.children.size());
            for (long child : snapshot.children) {
                desired.add(replacements.getOrDefault(child, child));
            }
            unsafe.putLongVolatile(
                    null,
                    parent + jvm.klassSubklassOffset,
                    desired.isEmpty() ? 0L : desired.get(0));
            for (int index = 0; index < desired.size(); index++) {
                long next = index + 1 < desired.size() ? desired.get(index + 1) : 0L;
                unsafe.putLongVolatile(
                        null, desired.get(index) + jvm.klassNextSiblingOffset, next);
            }
        }
    }

    private static void rebuildClassLoaderData(
            Change change, Map<Long, Long> replacements, JVM jvm) {
        for (ClassLoaderSnapshot snapshot : change.classLoaderSnapshots) {
            List<Long> desired = new ArrayList<>(snapshot.klasses.size());
            for (long klass : snapshot.klasses) {
                Long replacement = replacements.get(klass);
                if (replacement == null) {
                    desired.add(klass);
                } else {
                    desired.add(replacement);
                    desired.add(klass);
                }
            }
            UnsafeUtility.UNSAFE.putLongVolatile(
                    null,
                    snapshot.classLoaderData + jvm.classLoaderDataKlassesOffset,
                    desired.isEmpty() ? 0L : desired.get(0));
            for (int index = 0; index < desired.size(); index++) {
                long next = index + 1 < desired.size() ? desired.get(index + 1) : 0L;
                UnsafeUtility.UNSAFE.putLongVolatile(
                        null, desired.get(index) + jvm.klassNextLinkOffset, next);
            }
        }
    }

    private static void restoreHierarchy(
            List<HierarchySnapshot> snapshots, JVM jvm) {
        for (HierarchySnapshot snapshot : snapshots) {
            UnsafeUtility.UNSAFE.putLongVolatile(
                    null,
                    snapshot.parent + jvm.klassSubklassOffset,
                    snapshot.children.isEmpty() ? 0L : snapshot.children.get(0));
            for (int index = 0; index < snapshot.children.size(); index++) {
                long next = index + 1 < snapshot.children.size()
                        ? snapshot.children.get(index + 1) : 0L;
                UnsafeUtility.UNSAFE.putLongVolatile(
                        null,
                        snapshot.children.get(index) + jvm.klassNextSiblingOffset,
                        next);
            }
        }
    }

    private static void restoreClassLoaderData(
            List<ClassLoaderSnapshot> snapshots, JVM jvm) {
        for (ClassLoaderSnapshot snapshot : snapshots) {
            UnsafeUtility.UNSAFE.putLongVolatile(
                    null,
                    snapshot.classLoaderData + jvm.classLoaderDataKlassesOffset,
                    snapshot.klasses.isEmpty() ? 0L : snapshot.klasses.get(0));
            for (int index = 0; index < snapshot.klasses.size(); index++) {
                long next = index + 1 < snapshot.klasses.size()
                        ? snapshot.klasses.get(index + 1) : 0L;
                UnsafeUtility.UNSAFE.putLongVolatile(
                        null,
                        snapshot.klasses.get(index) + jvm.klassNextLinkOffset,
                        next);
            }
        }
    }

    private static List<StaticValue> captureStaticValues(Class<?> target) {
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        List<StaticValue> values = new ArrayList<>();
        for (Field field : target.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object base = unsafe.staticFieldBase(field);
            long offset = unsafe.staticFieldOffset(field);
            Object value = readStatic(unsafe, field, base, offset);
            values.add(new StaticValue(field.getName(), value));
        }
        return values;
    }

    private static void applyStaticValues(
            Class<?> target, Class<?> replacement, List<StaticValue> values) {
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        for (StaticValue value : values) {
            try {
                Field targetField = target.getDeclaredField(value.name);
                Field replacementField = replacement.getDeclaredField(value.name);
                long offset = staticFieldOffset(replacement, replacementField, JVM.getInstance());
                writeStatic(unsafe, targetField, target, unsafe.staticFieldOffset(targetField), value.value);
                writeStatic(unsafe, replacementField, replacement, offset, value.value);
            } catch (NoSuchFieldException exception) {
                throw new IllegalStateException(
                        "Replacement class lost static field " + value.name, exception);
            }
        }
    }

    private static long staticFieldOffset(Class<?> klass, Field targetField, JVM jvm) {
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        long klassPointer = unsafe.getLong(klass, jvm.classKlassOffset);
        long fields = unsafe.getLong(klassPointer + jvm.fieldsOffset);
        int fieldSlots = unsafe.getInt(fields + jvm.metadataArrayLengthOffset);
        Field[] declaredFields = klass.getDeclaredFields();
        if (fieldSlots != declaredFields.length * jvm.fieldSlots) {
            throw new IllegalStateException("Field metadata does not match " + klass.getName());
        }
        long elements = fields + jvm.metadataU2ArrayElementsOffset;
        for (int index = 0; index < declaredFields.length; index++) {
            Field field = declaredFields[index];
            if (!field.getName().equals(targetField.getName())
                    || field.getType() != targetField.getType()
                    || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            long fieldInfo = elements + (long) index * jvm.fieldSlots * Short.BYTES;
            int packedOffset = Short.toUnsignedInt(unsafe.getShort(
                    fieldInfo + (long) jvm.fieldLowPackedOffset * Short.BYTES))
                    | Short.toUnsignedInt(unsafe.getShort(
                    fieldInfo + (long) jvm.fieldHighPackedOffset * Short.BYTES)) << Short.SIZE;
            return packedOffset >>> 2;
        }
        throw new IllegalStateException("Could not find static field metadata for " + targetField);
    }

    private static Object readStatic(Unsafe unsafe, Field field, Object base, long offset) {
        Class<?> type = field.getType();
        if (type == boolean.class) return unsafe.getBoolean(base, offset);
        if (type == byte.class) return unsafe.getByte(base, offset);
        if (type == short.class) return unsafe.getShort(base, offset);
        if (type == char.class) return unsafe.getChar(base, offset);
        if (type == int.class) return unsafe.getInt(base, offset);
        if (type == long.class) return unsafe.getLong(base, offset);
        if (type == float.class) return unsafe.getFloat(base, offset);
        if (type == double.class) return unsafe.getDouble(base, offset);
        return unsafe.getObjectVolatile(base, offset);
    }

    private static void writeStatic(
            Unsafe unsafe, Field field, Object base, long offset, Object value) {
        Class<?> type = field.getType();
        if (type == boolean.class) {
            unsafe.putBooleanVolatile(base, offset, (Boolean) value);
        } else if (type == byte.class) {
            unsafe.putByteVolatile(base, offset, (Byte) value);
        } else if (type == short.class) {
            unsafe.putShortVolatile(base, offset, (Short) value);
        } else if (type == char.class) {
            unsafe.putCharVolatile(base, offset, (Character) value);
        } else if (type == int.class) {
            unsafe.putIntVolatile(base, offset, (Integer) value);
        } else if (type == long.class) {
            unsafe.putLongVolatile(base, offset, (Long) value);
        } else if (type == float.class) {
            unsafe.putFloatVolatile(base, offset, (Float) value);
        } else if (type == double.class) {
            unsafe.putDoubleVolatile(base, offset, (Double) value);
        } else {
            unsafe.putObjectVolatile(base, offset, value);
        }
    }

    private static long findDictionaryLiteral(long klass, JVM jvm) {
        Unsafe unsafe = UnsafeUtility.UNSAFE;
        long classLoaderData = unsafe.getLong(klass + jvm.klassClassLoaderDataOffset);
        if (classLoaderData == 0L) {
            throw new IllegalStateException("Klass does not have ClassLoaderData");
        }
        long dictionary = unsafe.getLong(
                classLoaderData + jvm.classLoaderDataDictionaryOffset);
        if (dictionary == 0L) {
            throw new IllegalStateException("ClassLoaderData does not have a dictionary");
        }
        int tableSize = unsafe.getInt(dictionary + jvm.dictionaryTableSizeOffset);
        long buckets = unsafe.getLong(dictionary + jvm.dictionaryBucketsOffset);
        if (tableSize <= 0 || tableSize > 1_048_576 || buckets == 0L) {
            throw new IllegalStateException("Invalid ClassLoaderData dictionary");
        }
        for (int bucketIndex = 0; bucketIndex < tableSize; bucketIndex++) {
            long bucket = buckets
                    + (long) bucketIndex * jvm.dictionaryBucketSize
                    + jvm.hashtableBucketEntryOffset;
            long entry = unsafe.getLong(bucket);
            while (entry != 0L) {
                long literalAddress = entry + jvm.dictionaryEntryLiteralOffset;
                if (unsafe.getLong(literalAddress) == klass) {
                    return literalAddress;
                }
                entry = unsafe.getLong(entry + jvm.basicHashtableEntryNextOffset);
            }
        }
        throw new IllegalStateException("Could not find the loader dictionary entry for Klass");
    }

    private static Class<?> classMirror(long klass, JVM jvm) {
        long mirrorHandle = UnsafeUtility.UNSAFE.getLong(klass + jvm.klassJavaMirrorOffset);
        if (mirrorHandle == 0L) {
            throw new IllegalStateException("Klass does not contain a java mirror handle");
        }
        long rawMirror = UnsafeUtility.UNSAFE.getLong(mirrorHandle);
        if (rawMirror == 0L) {
            throw new IllegalStateException("Klass java mirror is null");
        }
        Object mirror;
        synchronized (MIRROR_LOCK) {
            try {
                if (jvm.compressedOops) {
                    long delta = rawMirror - jvm.narrowOopBase;
                    long mask = (1L << jvm.narrowOopShift) - 1L;
                    if (delta < 0L || (delta & mask) != 0L) {
                        throw new IllegalStateException("Unsupported compressed oop encoding");
                    }
                    long narrow = delta >>> jvm.narrowOopShift;
                    if (narrow > 0xFFFF_FFFFL) {
                        throw new IllegalStateException("Compressed class mirror exceeds 32 bits");
                    }
                    UnsafeUtility.UNSAFE.putIntVolatile(
                            MIRROR_SLOT, jvm.referenceSlotValueOffset, (int) narrow);
                } else {
                    UnsafeUtility.UNSAFE.putLongVolatile(
                            MIRROR_SLOT, jvm.referenceSlotValueOffset, rawMirror);
                }
                mirror = MIRROR_SLOT.value;
            } finally {
                MIRROR_SLOT.value = null;
            }
        }
        if (!(mirror instanceof Class<?> klassMirror)) {
            throw new IllegalStateException("HotSpot java mirror is not a Class instance");
        }
        return klassMirror;
    }

    private static void clearReflectionData(Class<?> target, JVM jvm) {
        UnsafeUtility.UNSAFE.putObjectVolatile(target, jvm.classReflectionDataOffset, null);
    }

    static final class Change {
        private final List<SwapRecord> records;
        private final List<HierarchySnapshot> hierarchySnapshots;
        private final List<ClassLoaderSnapshot> classLoaderSnapshots;

        private Change(
                List<SwapRecord> records,
                List<HierarchySnapshot> hierarchySnapshots,
                List<ClassLoaderSnapshot> classLoaderSnapshots) {
            this.records = records;
            this.hierarchySnapshots = hierarchySnapshots;
            this.classLoaderSnapshots = classLoaderSnapshots;
        }
    }

    private static final class SwapRecord {
        private final Class<?> target;
        private final byte[] classFile;
        private final long oldKlass;
        private final long originalSuper;
        private final long classLoaderData;
        private final long dictionaryLiteral;
        private final long mirrorHandle;
        private final List<StaticValue> staticValues;
        private long oldName;
        private long replacementName;
        private Class<?> replacement;
        private long replacementKlass;
        private long replacementMirrorHandle;

        private SwapRecord(
                Class<?> target,
                byte[] classFile,
                long oldKlass,
                long originalSuper,
                long classLoaderData,
                long dictionaryLiteral,
                long mirrorHandle,
                List<StaticValue> staticValues) {
            this.target = target;
            this.classFile = classFile;
            this.oldKlass = oldKlass;
            this.originalSuper = originalSuper;
            this.classLoaderData = classLoaderData;
            this.dictionaryLiteral = dictionaryLiteral;
            this.mirrorHandle = mirrorHandle;
            this.staticValues = staticValues;
        }
    }

    private static final class ReferenceSlot {
        private volatile Object value;
    }

    private record StaticValue(String name, Object value) {
    }

    private record HierarchySnapshot(long parent, List<Long> children) {
    }

    private record ClassLoaderSnapshot(long classLoaderData, List<Long> klasses) {
    }

    private record InstalledClass(
            Class<?> target,
            Class<?> replacement,
            long oldKlass) {
    }
}
