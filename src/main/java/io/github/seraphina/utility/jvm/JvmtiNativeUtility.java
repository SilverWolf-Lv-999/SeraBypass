package io.github.seraphina.utility.jvm;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

import java.util.*;

@SuppressWarnings("all")
final class JvmtiNativeUtility {
    private static final int POINTER_SIZE = Native.POINTER_SIZE;
    private static final int JVMTI_MAGIC = 0x71EE;
    private static final int[] JVMTI_VERSIONS = {0x30010200, 0x30010100, 0x30010000};
    private static final int JVMTI_BAD_MAGIC = 0xDEAD;
    private static final int JVMTI_DISPOSED_MAGIC = 0xDEFC;
    private static final int MAX_CHAIN_STEPS = 64;
    private static final int VTABLE_SCAN_LIMIT = 256;
    private static final int MEM_COMMIT = 0x1000;
    private static final int PAGE_EXECUTE = 0x10;
    private static final int PAGE_EXECUTE_READ = 0x20;
    private static final int PAGE_EXECUTE_READWRITE = 0x40;
    private static final int PAGE_EXECUTE_WRITECOPY = 0x80;
    private static final int IMAGE_DOS_SIGNATURE = 0x5A4D;
    private static final int IMAGE_NT_SIGNATURE = 0x4550;
    private static final int IMAGE_SCN_MEM_READ = 0x40000000;
    private static final int IMAGE_SCN_MEM_WRITE = 0x80000000;
    private static final Object LOCK = new Object();
    private static final Map<String, Function> FUNCTIONS = new HashMap<>();
    private static final Map<Long, Integer> VTABLE_SLOT_COUNTS = new HashMap<>();
    private static final Map<Long, Memory> SHADOW_TABLES = new HashMap<>();
    private static final Set<Long> UNLINKED_BASES = new HashSet<>();
    private static final NoopCallback NOOP_CALLBACK = env -> 0;
    private static final Pointer NOOP_ADDRESS = CallbackReference.getFunctionPointer(NOOP_CALLBACK);

    private static Pointer javaVm;
    private static Pointer ourJvmtiEnv;
    private static Pointer headPointer;
    private static long externalOffset;
    private static long magicOffset;
    private static long nextOffset;
    private static Memory ownShadowTable;
    private static long ownOriginalFunctions;
    private static long nextHeadResolveNanos;
    private static boolean initialized;

    static boolean initializeJvmti() {
        synchronized (LOCK) {
            if (initialized) return true;
            if (POINTER_SIZE != 8 || !System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT).contains("win")) {
                return false;
            }
            javaVm = findCreatedJavaVm();
            ourJvmtiEnv = getJvmtiEnv(javaVm);
            if (ourJvmtiEnv == null) {
                clearInitializationState();
                return false;
            }
            if (!shieldOwnEnvironment()) {
                clearInitializationState();
                return false;
            }
            boolean resolved = resolveHeadPointer(ourJvmtiEnv);
            nextHeadResolveNanos = resolved
                    ? Long.MAX_VALUE : System.nanoTime() + 5_000_000_000L;
            initialized = true;
            return true;
        }
    }

    static int neutralizeAlienEnvironments() {
        synchronized (LOCK) {
            return initialized ? sweepChain() : 0;
        }
    }

    static int recoverJvmti() {
        synchronized (LOCK) {
            if (!initialized) return 0;
            shieldOwnEnvironment();
            if (headPointer == null && System.nanoTime() >= nextHeadResolveNanos) {
                boolean resolved = resolveHeadPointer(ourJvmtiEnv);
                nextHeadResolveNanos = resolved
                        ? Long.MAX_VALUE : System.nanoTime() + 5_000_000_000L;
            }
            if (headPointer != null) healOwnEnvironment();
            return sweepChain();
        }
    }

    static int disarmAlienEnvironments() {
        synchronized (LOCK) {
            if (!initialized) return 0;
            int changed = 0;
            for (long base : new ArrayList<>(UNLINKED_BASES)) {
                if (invalidateMagic(base)) changed++;
                if (shadowVtable(base)) changed++;
            }
            return changed;
        }
    }

    static void shutdownJvmti() {
        synchronized (LOCK) {
            if (ourJvmtiEnv != null && ownOriginalFunctions != 0) {
                writePointer(Pointer.nativeValue(ourJvmtiEnv), ownOriginalFunctions);
            }
            initialized = false;
            javaVm = null;
            ourJvmtiEnv = null;
            headPointer = null;
            externalOffset = 0;
            magicOffset = 0;
            nextOffset = 0;
            ownShadowTable = null;
            ownOriginalFunctions = 0;
            nextHeadResolveNanos = 0;
            SHADOW_TABLES.clear();
            UNLINKED_BASES.clear();
            VTABLE_SLOT_COUNTS.clear();
        }
    }

    private static void clearInitializationState() {
        javaVm = null;
        ourJvmtiEnv = null;
        headPointer = null;
        externalOffset = 0;
        magicOffset = 0;
        nextOffset = 0;
        ownShadowTable = null;
        ownOriginalFunctions = 0;
        nextHeadResolveNanos = 0;
        SHADOW_TABLES.clear();
        UNLINKED_BASES.clear();
        VTABLE_SLOT_COUNTS.clear();
    }

    private static int sweepChain() {
        if (headPointer == null || ourJvmtiEnv == null) return 0;
        long ownBase = Pointer.nativeValue(ourJvmtiEnv) - externalOffset;
        long current = readPointer(Pointer.nativeValue(headPointer));
        long previous = 0;
        int changed = 0;
        for (int step = 0; step < MAX_CHAIN_STEPS && current != 0; step++) {
            if (!isPlausibleBase(current, false)) break;
            long next = readPointer(current + nextOffset);
            if (current != ownBase && !UNLINKED_BASES.contains(current)) {
                long linkAddress = previous == 0
                        ? Pointer.nativeValue(headPointer) : previous + nextOffset;
                if (writePointer(linkAddress, next)) {
                    UNLINKED_BASES.add(current);
                    invalidateMagic(current);
                    shadowVtable(current);
                    changed++;
                    current = next;
                    continue;
                }
            }
            previous = current;
            current = next;
        }
        for (long base : new ArrayList<>(UNLINKED_BASES)) {
            invalidateMagic(base);
            shadowVtable(base);
        }
        return changed;
    }

    private static void healOwnEnvironment() {
        long ownEnv = Pointer.nativeValue(ourJvmtiEnv);
        int magic = readInt(ownEnv + magicOffset);
        if (magic == JVMTI_BAD_MAGIC || magic == JVMTI_DISPOSED_MAGIC || magic == 0) {
            writeInt(ownEnv + magicOffset, JVMTI_MAGIC);
        }
        long ownBase = ownEnv - externalOffset;
        long head = readPointer(Pointer.nativeValue(headPointer));
        if (head != 0 && reachesTarget(head, ownBase,
                new Layout(externalOffset, magicOffset, nextOffset))) {
            return;
        }
        if (writePointer(ownBase + nextOffset, head)) {
            writePointer(Pointer.nativeValue(headPointer), ownBase);
        }
    }

    private static boolean resolveHeadPointer(Pointer seedEnv) {
        List<Layout> layouts = buildLayouts(seedEnv);
        if (layouts.isEmpty()) return false;
        Pointer jvm = invokePointer("kernel32", "GetModuleHandleA", "jvm.dll");
        if (jvm == null) return false;
        byte[] dos = readBytes(Pointer.nativeValue(jvm), 0x40);
        if (dos == null || getInt(dos, 0) != IMAGE_DOS_SIGNATURE) return false;
        int ntOffset = getInt(dos, 0x3C);
        byte[] nt = readBytes(Pointer.nativeValue(jvm) + ntOffset, 0x108);
        if (nt == null || getInt(nt, 0) != IMAGE_NT_SIGNATURE) return false;
        int sections = getShort(nt, 6);
        int optionalSize = getShort(nt, 20);
        long sectionAddress = Pointer.nativeValue(jvm) + ntOffset + 24L + optionalSize;
        List<PeSection> writableSections = new ArrayList<>();
        for (int section = 0; section < sections; section++) {
            byte[] header = readBytes(sectionAddress + section * 40L, 40);
            if (header == null) continue;
            int characteristics = getInt(header, 36);
            if ((characteristics & IMAGE_SCN_MEM_READ) == 0
                    || (characteristics & IMAGE_SCN_MEM_WRITE) == 0) continue;
            int virtualSize = getInt(header, 8);
            int virtualAddress = getInt(header, 12);
            int size = Math.min(Math.max(0, virtualSize), 16 * 1024 * 1024);
            long address = Pointer.nativeValue(jvm) + virtualAddress;
            byte[] bytes = readBytes(address, size);
            if (bytes != null) writableSections.add(new PeSection(address, bytes));
        }
        Pointer fallbackPointer = null;
        Layout fallbackLayout = null;
        int fallbackLength = 0;
        for (Layout layout : layouts) {
            long seedBase = Pointer.nativeValue(seedEnv) - layout.externalOffset;
            for (PeSection section : writableSections) {
                int count = section.bytes.length / POINTER_SIZE;
                for (int index = 0; index < count; index++) {
                    long candidate = POINTER_SIZE == 8
                            ? getLong(section.bytes, index * POINTER_SIZE)
                            : Integer.toUnsignedLong(getInt(section.bytes, index * POINTER_SIZE));
                    if (candidate == 0) continue;
                    ChainInspection inspection = inspectChain(candidate, seedBase, layout);
                    if (inspection.reachesTarget) {
                        headPointer = new Pointer(section.address
                                + (long) index * POINTER_SIZE);
                        externalOffset = layout.externalOffset;
                        magicOffset = layout.magicOffset;
                        nextOffset = layout.nextOffset;
                        return true;
                    }
                    if (inspection.count > fallbackLength) {
                        fallbackPointer = new Pointer(section.address
                                + (long) index * POINTER_SIZE);
                        fallbackLayout = layout;
                        fallbackLength = inspection.count;
                    }
                }
            }
        }
        if (fallbackPointer == null) return false;
        headPointer = fallbackPointer;
        externalOffset = fallbackLayout.externalOffset;
        magicOffset = fallbackLayout.magicOffset;
        nextOffset = fallbackLayout.nextOffset;
        return true;
    }

    private static List<Layout> buildLayouts(Pointer env) {
        List<Layout> result = new ArrayList<>();
        long address = Pointer.nativeValue(env);
        for (long magic = 0; magic <= 0x100; magic += 4) {
            if (readInt(address + magic) != JVMTI_MAGIC) continue;
            for (long external = 0; external <= 0x80; external += POINTER_SIZE) {
                long base = address - external;
                for (long next = 0; next <= 0x120; next += POINTER_SIZE) {
                    long expected = external + magic + 8;
                    if (next < expected || next > expected + 0x40) continue;
                    long nextBase = readPointer(base + next);
                    if (nextBase == 0 || isPlausibleBase(
                            nextBase, new Layout(external, magic, next), false)) {
                        result.add(new Layout(external, magic, next));
                    }
                }
            }
        }
        result.sort(Comparator.comparingInt(layout -> layout.nextOffset
                == layout.externalOffset + layout.magicOffset + 8 ? 0 : 1));
        return result;
    }

    private static boolean reachesTarget(long head, long target, Layout layout) {
        return inspectChain(head, target, layout).reachesTarget;
    }

    private static ChainInspection inspectChain(long head, long target, Layout layout) {
        Set<Long> seen = new HashSet<>();
        long current = head;
        int count = 0;
        while (count < MAX_CHAIN_STEPS && current != 0 && seen.add(current)) {
            if (!isPlausibleBase(current, layout, false)) break;
            count++;
            if (current == target) return new ChainInspection(count, true);
            current = readPointer(current + layout.nextOffset);
        }
        return new ChainInspection(count, false);
    }

    private static boolean isPlausibleBase(long base, boolean allowDisposed) {
        return isPlausibleBase(base,
                new Layout(externalOffset, magicOffset, nextOffset), allowDisposed);
    }

    private static boolean isPlausibleBase(long base, Layout layout, boolean allowDisposed) {
        if (base < 0x10000 || base + layout.externalOffset < base) return false;
        long env = base + layout.externalOffset;
        int magic = readInt(env + layout.magicOffset);
        if (magic != JVMTI_MAGIC && (!allowDisposed || magic != JVMTI_BAD_MAGIC)) return false;
        return isPlausibleFunctions(readPointer(env));
    }

    private static boolean shieldOwnEnvironment() {
        long env = Pointer.nativeValue(ourJvmtiEnv);
        long functions = readPointer(env);
        if (functions == 0 || !isPlausibleFunctions(functions)) return false;
        if (ownShadowTable == null) {
            ownOriginalFunctions = functions;
            ownShadowTable = copyVtable(functions);
            if (ownShadowTable == null) return false;
        }
        neutralizeVtable(ownShadowTable);
        return functions == Pointer.nativeValue(ownShadowTable)
                || writePointer(env, Pointer.nativeValue(ownShadowTable));
    }

    private static boolean shadowVtable(long base) {
        long env = base + externalOffset;
        long functions = readPointer(env);
        if (functions == 0 || !isPlausibleFunctions(functions)) return false;
        Memory shadow = SHADOW_TABLES.get(base);
        if (shadow == null) {
            shadow = copyVtable(functions);
            if (shadow == null) return false;
            SHADOW_TABLES.put(base, shadow);
        }
        neutralizeVtable(shadow);
        return functions == Pointer.nativeValue(shadow)
                || writePointer(env, Pointer.nativeValue(shadow));
    }

    /** Replace every discovered callable JVMTI entry with the same inert stub. */
    private static void neutralizeVtable(Memory table) {
        int slots = (int) (table.size() / POINTER_SIZE);
        for (int slot = 0; slot < slots; slot++) {
            if (table.getPointer((long) slot * POINTER_SIZE) != null) {
                table.setPointer((long) slot * POINTER_SIZE, NOOP_ADDRESS);
            }
        }
    }

    private static Memory copyVtable(long source) {
        int slots = discoverVtableSlots(source);
        if (slots <= 0) return null;
        byte[] bytes = readBytes(source, slots * POINTER_SIZE);
        if (bytes == null) return null;
        Memory memory = new Memory(bytes.length);
        memory.write(0, bytes, 0, bytes.length);
        return memory;
    }

    private static int discoverVtableSlots(long source) {
        Integer cached = VTABLE_SLOT_COUNTS.get(source);
        if (cached != null) return cached;
        int lastValid = -1;
        int trailingInvalid = 0;
        for (int slot = 0; slot < VTABLE_SCAN_LIMIT; slot++) {
            long function = readPointer(source + (long) slot * POINTER_SIZE);
            if (function != 0 && isExecutable(function)) {
                lastValid = slot;
                trailingInvalid = 0;
            } else if (slot > lastValid && ++trailingInvalid >= 16) {
                break;
            }
        }
        int slots = lastValid + 1;
        VTABLE_SLOT_COUNTS.put(source, slots);
        return slots;
    }

    private static boolean invalidateMagic(long base) {
        long address = base + externalOffset + magicOffset;
        int value = readInt(address);
        return value == JVMTI_BAD_MAGIC || (value == JVMTI_MAGIC && writeInt(address, JVMTI_BAD_MAGIC));
    }

    private static boolean isPlausibleFunctions(long address) {
        if (address == 0) return false;
        int slots = discoverVtableSlots(address);
        if (slots < 2) return false;
        int executableSlots = 0;
        for (int slot = 0; slot < slots; slot++) {
            long function = readPointer(address + (long) slot * POINTER_SIZE);
            if (function != 0 && isExecutable(function)) executableSlots++;
        }
        // A real JVMTI table is mostly callable entries with a few reserved
        // null slots. This rejects random data without assuming any slot index.
        return executableSlots >= Math.max(2, slots / 2);
    }

    private static boolean isExecutable(long address) {
        if (address == 0) return false;
        Memory info = new Memory(48);
        Function query = function("kernel32", "VirtualQuery");
        if (query == null) return false;
        try {
            long result = query.invokeLong(new Object[]{new Pointer(address), info, 48L});
            int protection = info.getInt(36) & 0xFF;
            return result != 0 && info.getInt(32) == MEM_COMMIT
                    && (protection == PAGE_EXECUTE || protection == PAGE_EXECUTE_READ
                    || protection == PAGE_EXECUTE_READWRITE || protection == PAGE_EXECUTE_WRITECOPY);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Pointer findCreatedJavaVm() {
        Function function = function("jvm", "JNI_GetCreatedJavaVMs");
        if (function == null) return null;
        Pointer[] vms = new Pointer[1];
        IntByReference count = new IntByReference();
        try {
            int result = function.invokeInt(new Object[]{vms, 1, count.getPointer()});
            return result == 0 && count.getValue() > 0 ? vms[0] : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Pointer getJvmtiEnv(Pointer vm) {
        if (vm == null) return null;
        long table = readPointer(Pointer.nativeValue(vm));
        if (table == 0) return null;
        long getEnv = readPointer(table + 6L * POINTER_SIZE);
        if (getEnv == 0) return null;
        Function getEnvFunction = Function.getFunction(new Pointer(getEnv));
        for (int version : JVMTI_VERSIONS) {
            Memory result = new Memory(POINTER_SIZE);
            try {
                int status = getEnvFunction.invokeInt(new Object[]{vm, result, version});
                Pointer env = status == 0 ? result.getPointer(0) : null;
                if (env != null && Pointer.nativeValue(env) != 0) return env;
            } catch (Throwable ignored) {
                // Try the next supported JVMTI ABI version.
            }
        }
        return null;
    }

    private static long readPointer(long address) {
        byte[] data = readBytes(address, POINTER_SIZE);
        if (data == null) return 0;
        return POINTER_SIZE == 8 ? getLong(data, 0) : Integer.toUnsignedLong(getInt(data, 0));
    }

    private static int readInt(long address) {
        byte[] data = readBytes(address, 4);
        return data == null ? 0 : getInt(data, 0);
    }

    private static byte[] readBytes(long address, int size) {
        Function read = function("kernel32", "ReadProcessMemory");
        Function current = function("kernel32", "GetCurrentProcess");
        if (read == null || current == null || size <= 0) return null;
        Memory buffer = new Memory(size);
        LongByReference count = new LongByReference();
        try {
            int ok = read.invokeInt(new Object[]{current.invokePointer(new Object[0]),
                    new Pointer(address), buffer, (long) size, count.getPointer()});
            return ok != 0 && count.getValue() == size ? buffer.getByteArray(0, size) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean writePointer(long address, long value) {
        Memory data = new Memory(POINTER_SIZE);
        if (POINTER_SIZE == 8) data.setLong(0, value);
        else data.setInt(0, (int) value);
        return writeBytes(address, data, POINTER_SIZE);
    }

    private static boolean writeInt(long address, int value) {
        Memory data = new Memory(4);
        data.setInt(0, value);
        return writeBytes(address, data, 4);
    }

    private static boolean writeBytes(long address, Pointer data, int size) {
        Function write = function("kernel32", "WriteProcessMemory");
        Function current = function("kernel32", "GetCurrentProcess");
        if (write == null || current == null) return false;
        LongByReference count = new LongByReference();
        try {
            int ok = write.invokeInt(new Object[]{current.invokePointer(new Object[0]),
                    new Pointer(address), data, (long) size, count.getPointer()});
            return ok != 0 && count.getValue() == size;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Pointer invokePointer(String library, String name, Object... arguments) {
        Function function = function(library, name);
        if (function == null) return null;
        try {
            return function.invokePointer(arguments);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Function function(String library, String name) {
        String key = library + ':' + name;
        synchronized (FUNCTIONS) {
            Function cached = FUNCTIONS.get(key);
            if (cached != null) return cached;
            try {
                Function value = Function.getFunction(library, name);
                FUNCTIONS.put(key, value);
                return value;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static int getInt(byte[] value, int offset) {
        return (value[offset] & 0xFF) | ((value[offset + 1] & 0xFF) << 8)
                | ((value[offset + 2] & 0xFF) << 16) | ((value[offset + 3] & 0xFF) << 24);
    }

    private static int getShort(byte[] value, int offset) {
        return (value[offset] & 0xFF) | ((value[offset + 1] & 0xFF) << 8);
    }

    private static long getLong(byte[] value, int offset) {
        long result = 0;
        for (int index = 7; index >= 0; index--) result = (result << 8) | (value[offset + index] & 0xFFL);
        return result;
    }

    private interface NoopCallback extends Callback {
        int invoke(Pointer env);
    }

    private record PeSection(long address, byte[] bytes) {
    }

    private record Layout(long externalOffset, long magicOffset, long nextOffset) {
    }

    private record ChainInspection(int count, boolean reachesTarget) {
    }
}



