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
    public static JVM getInstance() {
        return Holder.INSTANCE;
    }

    private static JVM initialize() {
        JVM instance = new JVM();
        Object result = JNCT.ivk("createJVM", instance);
        if (result != instance) {
            throw new IllegalStateException("The native JVM layout probe returned a different instance");
        }
        return instance;
    }

    private static final class Holder {
        private static final JVM INSTANCE = initialize();
    }

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
    /** The first u2 element of the native HotSpot Array&lt;u2&gt; used by InstanceKlass fields. */
    public final long metadataU2ArrayElementsOffset;

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
     * so that the snapshot can be created while this class's static initializer
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
        metadataU2ArrayElementsOffset = 0L;
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

    private static class KlassSubklassRootOne {
    }

    private static final class KlassSubklassChildOne extends KlassSubklassRootOne {
    }

    private static class KlassSubklassRootTwo {
    }

    private static final class KlassSubklassChildTwo extends KlassSubklassRootTwo {
    }

    private static class KlassSiblingRootOne {
    }

    private static final class KlassSiblingChildOne extends KlassSiblingRootOne {
    }

    private static final class KlassSiblingChildTwo extends KlassSiblingRootOne {
    }

    private static class KlassSiblingRootTwo {
    }

    private static final class KlassSiblingChildThree extends KlassSiblingRootTwo {
    }

    private static final class KlassSiblingChildFour extends KlassSiblingRootTwo {
    }

    private static class VtableLayoutProbe {
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
        private static final java.lang.invoke.VarHandle METHOD_SLOT = methodSlotHandle();
        private static final java.lang.invoke.MethodHandle INTERNAL_MEMBER_NAME =
                internalMemberNameHandle();
        private static final java.lang.invoke.VarHandle MEMBER_NAME_METHOD =
                memberNameMethodHandle();

        private static Object resolvedMethod(java.lang.reflect.Method method) throws Throwable {
            java.lang.invoke.MethodHandle direct =
                    io.github.seraphina.utility.UnsafeUtility.TRUSTED_LOOKUP.unreflect(method);
            Object memberName = INTERNAL_MEMBER_NAME.invoke(direct);
            return MEMBER_NAME_METHOD.get(memberName);
        }

        private static int methodSlot(java.lang.reflect.Method method) {
            return (int) METHOD_SLOT.get(method);
        }

        private static java.lang.invoke.VarHandle methodSlotHandle() {
            try {
                return io.github.seraphina.utility.UnsafeUtility.TRUSTED_LOOKUP.findVarHandle(
                        java.lang.reflect.Method.class, "slot", int.class);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(
                        "Could not resolve java.lang.reflect.Method.slot", exception);
            }
        }

        private static java.lang.invoke.MethodHandle internalMemberNameHandle() {
            try {
                java.lang.reflect.Method method =
                        java.lang.invoke.MethodHandle.class.getDeclaredMethod("internalMemberName");
                return io.github.seraphina.utility.UnsafeUtility.TRUSTED_LOOKUP
                        .unreflect(method)
                        .asType(java.lang.invoke.MethodType.methodType(
                                Object.class, java.lang.invoke.MethodHandle.class));
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(
                        "Could not resolve MethodHandle.internalMemberName", exception);
            }
        }

        private static java.lang.invoke.VarHandle memberNameMethodHandle() {
            try {
                Class<?> memberNameClass = Class.forName("java.lang.invoke.MemberName");
                java.lang.reflect.Field field = memberNameClass.getDeclaredField("method");
                return io.github.seraphina.utility.UnsafeUtility.TRUSTED_LOOKUP.findVarHandle(
                        memberNameClass, "method", field.getType());
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(
                        "Could not resolve MemberName.method", exception);
            }
        }
    }
}


