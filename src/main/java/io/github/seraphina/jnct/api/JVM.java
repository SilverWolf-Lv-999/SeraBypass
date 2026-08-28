package io.github.seraphina.jnct.api;

import io.github.seraphina.jnct.JNCT;

/**
 * A snapshot of the HotSpot 17 VM layout discovered by the native JNCT worker.
 *
 * <p>The values in this class are deliberately populated by Rust after the
 * object has been allocated.  No HotSpot layout constant belongs in the Java
 * implementation: the native side probes the running VM and returns the
 * values that are valid for that VM instance.</p>
 */
public final class JVM {
    public static final JVM INSTANCE = (JVM) JNCT.ivk("createJVM");

    public final long resolvedMethodNameVmtargetOffset;
    public final long methodConstMethodOffset;
    public final long methodVtableIndexOffset;

    public final long constMethodConstantsOffset;
    public final long constMethodCodeSizeOffset;
    public final long constMethodNameIndexOffset;
    public final long constMethodMethodIdnumOffset;
    public final long constMethodOriginalMethodIdnumOffset;
    public final long constMethodCodeOffset;

    public final long constantPoolLengthOffset;
    public final long constantPoolEntriesOffset;

    /** The length field of a native HotSpot Array&lt;T&gt;. */
    public final long metadataArrayLengthOffset;
    /** The first element of a native HotSpot Array&lt;T&gt;. */
    public final long metadataArrayElementsOffset;

    /** The length field of a Java array object. */
    public final long javaArrayLengthOffset;
    /** The first element of a Java array object. */
    public final long javaArrayElementsOffset;
    /** The first element of a Java short[] object. */
    public final long shortArrayElementsOffset;

    /** Compatibility alias for native HotSpot metadata arrays. */
    public final long arrayLengthOffset;
    /** Compatibility alias for native HotSpot metadata arrays. */
    public final long arrayElementsOffset;

    public final long symbolLengthOffset;
    public final long symbolBodyOffset;

    public final long classKlassOffset;
    public final long objectKlassOffset;
    public final long klassJavaMirrorOffset;
    public final long klassSubklassOffset;
    public final long klassNextSiblingOffset;

    public final long directMethodHandleMemberOffset;
    public final long memberNameResolvedMethodOffset;
    public final long referenceSlotValueOffset;

    public final long narrowOopBase;
    public final int narrowOopShift;
    public final boolean compressedOops;

    public final long narrowKlassBase;
    public final int narrowKlassShift;
    public final boolean compressedKlasses;
    public final long klassWordSize;

    public final long vtableStartOffset;
    public final long methodsOffset;
    public final long fieldsOffset;
    public final long javaFieldsCountOffset;
    public final long classReflectionDataOffset;
    public final long metadataAddressPrefix;

    public final int fieldAccessFlagsOffset;
    public final int fieldNameIndexOffset;
    public final int fieldSignatureIndexOffset;
    public final int fieldLowPackedOffset;
    public final int fieldHighPackedOffset;
    public final int fieldSlots;

    public final int addressSize;
    public final boolean valid;
    public final String errorMessage;

    /**
     * The native worker uses AllocObject, rather than invoking this constructor,
     * so that JVM.INSTANCE can be created while this class's static initializer
     * is waiting for JNCT.  The assignments also make the Java-side snapshot
     * well-defined if the class is ever instantiated by a debugger or agent.
     */
    private JVM() {
        resolvedMethodNameVmtargetOffset = 0L;
        methodConstMethodOffset = 0L;
        methodVtableIndexOffset = 0L;
        constMethodConstantsOffset = 0L;
        constMethodCodeSizeOffset = 0L;
        constMethodNameIndexOffset = 0L;
        constMethodMethodIdnumOffset = 0L;
        constMethodOriginalMethodIdnumOffset = 0L;
        constMethodCodeOffset = 0L;
        constantPoolLengthOffset = 0L;
        constantPoolEntriesOffset = 0L;
        metadataArrayLengthOffset = 0L;
        metadataArrayElementsOffset = 0L;
        javaArrayLengthOffset = 0L;
        javaArrayElementsOffset = 0L;
        shortArrayElementsOffset = 0L;
        arrayLengthOffset = 0L;
        arrayElementsOffset = 0L;
        symbolLengthOffset = 0L;
        symbolBodyOffset = 0L;
        classKlassOffset = 0L;
        objectKlassOffset = 0L;
        klassJavaMirrorOffset = 0L;
        klassSubklassOffset = 0L;
        klassNextSiblingOffset = 0L;
        directMethodHandleMemberOffset = 0L;
        memberNameResolvedMethodOffset = 0L;
        referenceSlotValueOffset = 0L;
        narrowOopBase = 0L;
        narrowOopShift = 0;
        compressedOops = false;
        narrowKlassBase = 0L;
        narrowKlassShift = 0;
        compressedKlasses = false;
        klassWordSize = 0L;
        vtableStartOffset = 0L;
        methodsOffset = 0L;
        fieldsOffset = 0L;
        javaFieldsCountOffset = 0L;
        classReflectionDataOffset = 0L;
        metadataAddressPrefix = 0L;
        fieldAccessFlagsOffset = 0;
        fieldNameIndexOffset = 0;
        fieldSignatureIndexOffset = 0;
        fieldLowPackedOffset = 0;
        fieldHighPackedOffset = 0;
        fieldSlots = 0;
        addressSize = 0;
        valid = false;
        errorMessage = null;
    }

    public boolean isValid() {
        return valid;
    }

    public void requireValid() {
        if (!valid) {
            throw new IllegalStateException(
                    errorMessage == null ? "The JVM layout snapshot is invalid" : errorMessage);
        }
    }

    // The following methods/classes are intentionally private.  They are
    // reflection targets for the native layout probe and are not part of the
    // public layout contract.
    private static int layoutProbe() {
        return 0;
    }

    private static int layoutProbeOne() {
        return 1;
    }

    private static Object layoutProbeNull() {
        return null;
    }

    private static int layoutProbeConstant() {
        return 42;
    }

    private static int layoutProbeShort() {
        return 300;
    }

    private static class KlassLayoutParent {
    }

    private static final class KlassLayoutChild extends KlassLayoutParent {
    }

    private static final class KlassLayoutSibling extends KlassLayoutParent {
    }

    private static final class VtableLayoutProbe {
        int first() {
            return 1;
        }

        int second() {
            return 2;
        }

        int third() {
            return 3;
        }
    }

    private static final class MethodTableLayoutProbe {
        static void first() {
        }

        static void second() {
        }

        static void third() {
        }
    }

    private static final class FieldTableLayoutProbe {
        private static int staticField;
        private long instanceLongField;
        private Object instanceObjectField;
    }

    private static final class FieldCountExtendedLayoutProbe {
        private static int staticField;
        private long instanceLongField;
        private Object instanceObjectField;
        private int extraInstanceIntField;
    }

    private static final class FieldCountStaticLayoutProbe {
        private static int staticIntField;
        private static long staticLongField;
        private static Object staticObjectField;
    }

    private static final class ReferenceSlot {
        private volatile Object value;
    }

    private static final class NativeProbe {
        private static java.lang.invoke.MethodHandle direct(java.lang.reflect.Method method)
                throws IllegalAccessException {
            return io.github.seraphina.utility.UnsafeUtility.TRUSTED_LOOKUP.unreflect(method);
        }
    }
}


