use core::ffi::c_void;
use core::mem::size_of;
use core::ptr;

use crate::log;
use jni_sys::{JNIEnv, jclass, jint, jlong, jmethodID, jobject, jshort, jvalue};

const JVM_CLASS_NAME: &[u8] = b"io/github/seraphina/jnct/api/JVM\0";
const OBJECT_CLASS_NAME: &[u8] = b"java/lang/Object\0";
const STRING_CLASS_NAME: &[u8] = b"java/lang/String\0";
const CLASS_CLASS_NAME: &[u8] = b"java/lang/Class\0";
const UNSAFE_CLASS_NAME: &[u8] = b"sun/misc/Unsafe\0";
const DIRECT_METHOD_HANDLE_CLASS_NAME: &[u8] = b"java/lang/invoke/DirectMethodHandle\0";
const MEMBER_NAME_CLASS_NAME: &[u8] = b"java/lang/invoke/MemberName\0";
const CONSTANT_POOL_CLASS_NAME: &[u8] = b"jdk/internal/reflect/ConstantPool\0";
const NATIVE_PROBE_CLASS_NAME: &[u8] = b"io/github/seraphina/jnct/api/JVM$NativeProbe\0";
const REFERENCE_SLOT_CLASS_NAME: &[u8] = b"io/github/seraphina/jnct/api/JVM$ReferenceSlot\0";
const KLASS_PARENT_CLASS_NAME: &[u8] = b"io/github/seraphina/jnct/api/JVM$KlassLayoutParent\0";
const KLASS_CHILD_CLASS_NAME: &[u8] = b"io/github/seraphina/jnct/api/JVM$KlassLayoutChild\0";
const KLASS_SIBLING_CLASS_NAME: &[u8] = b"io/github/seraphina/jnct/api/JVM$KlassLayoutSibling\0";
const VTABLE_PROBE_CLASS_NAME: &[u8] = b"io/github/seraphina/jnct/api/JVM$VtableLayoutProbe\0";
const METHOD_TABLE_PROBE_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$MethodTableLayoutProbe\0";
const FIELD_TABLE_PROBE_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$FieldTableLayoutProbe\0";
const FIELD_COUNT_EXTENDED_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$FieldCountExtendedLayoutProbe\0";
const FIELD_COUNT_STATIC_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$FieldCountStaticLayoutProbe\0";

const FIELD_VALUE_NAME: &[u8] = b"value\0";
const FIELD_MEMBER_NAME: &[u8] = b"member\0";
const FIELD_METHOD_NAME: &[u8] = b"method\0";
const FIELD_REFLECTION_DATA_NAME: &[u8] = b"reflectionData\0";
const FIELD_THE_UNSAFE_NAME: &[u8] = b"theUnsafe\0";

const OBJECT_DESCRIPTOR: &[u8] = b"Ljava/lang/Object;\0";
const LONG_DESCRIPTOR: &[u8] = b"J\0";
const INT_DESCRIPTOR: &[u8] = b"I\0";
const BOOLEAN_DESCRIPTOR: &[u8] = b"Z\0";
const STRING_DESCRIPTOR: &[u8] = b"Ljava/lang/String;\0";
const UNSAFE_DESCRIPTOR: &[u8] = b"Lsun/misc/Unsafe;\0";
const FIELD_DESCRIPTOR: &[u8] = b"Ljava/lang/reflect/Field;\0";

const GET_DECLARED_FIELD_NAME: &[u8] = b"getDeclaredField\0";
const GET_DECLARED_FIELD_DESCRIPTOR: &[u8] = b"(Ljava/lang/String;)Ljava/lang/reflect/Field;\0";
const GET_CONSTANT_POOL_NAME: &[u8] = b"getConstantPool\0";
const GET_CONSTANT_POOL_DESCRIPTOR: &[u8] = b"()Ljdk/internal/reflect/ConstantPool;\0";
const GET_SIZE_NAME: &[u8] = b"getSize\0";
const GET_SIZE_DESCRIPTOR: &[u8] = b"()I\0";
const GET_UTF8_AT_NAME: &[u8] = b"getUTF8At\0";
const GET_UTF8_AT_DESCRIPTOR: &[u8] = b"(I)Ljava/lang/String;\0";
const OBJECT_FIELD_OFFSET_NAME: &[u8] = b"objectFieldOffset\0";
const OBJECT_FIELD_OFFSET_DESCRIPTOR: &[u8] = b"(Ljava/lang/reflect/Field;)J\0";
const STATIC_FIELD_OFFSET_NAME: &[u8] = b"staticFieldOffset\0";
const STATIC_FIELD_OFFSET_DESCRIPTOR: &[u8] = b"(Ljava/lang/reflect/Field;)J\0";
const ADDRESS_SIZE_NAME: &[u8] = b"addressSize\0";
const ADDRESS_SIZE_DESCRIPTOR: &[u8] = b"()I\0";
const ARRAY_BASE_OFFSET_NAME: &[u8] = b"arrayBaseOffset\0";
const ARRAY_BASE_OFFSET_DESCRIPTOR: &[u8] = b"(Ljava/lang/Class;)I\0";
const ARRAY_INDEX_SCALE_NAME: &[u8] = b"arrayIndexScale\0";
const ARRAY_INDEX_SCALE_DESCRIPTOR: &[u8] = b"(Ljava/lang/Class;)I\0";
const DIRECT_PROBE_NAME: &[u8] = b"direct\0";
const DIRECT_PROBE_DESCRIPTOR: &[u8] =
    b"(Ljava/lang/reflect/Method;)Ljava/lang/invoke/MethodHandle;\0";
const INITIALIZER_NAME: &[u8] = b"<init>\0";
const INITIALIZER_DESCRIPTOR: &[u8] = b"()V\0";
const STATIC_VOID_DESCRIPTOR: &[u8] = b"()V\0";
const STATIC_INT_DESCRIPTOR: &[u8] = b"()I\0";
const STATIC_OBJECT_DESCRIPTOR: &[u8] = b"()Ljava/lang/Object;\0";
const INSTANCE_INT_DESCRIPTOR: &[u8] = b"()I\0";

const FIELD_NAMES: [&[u8]; 3] = [
    b"staticField\0",
    b"instanceLongField\0",
    b"instanceObjectField\0",
];
const FIELD_DESCRIPTORS: [&[u8]; 3] = [b"I\0", b"J\0", OBJECT_DESCRIPTOR];
const PROBE_NAMES: [&[u8]; 5] = [
    b"layoutProbe\0",
    b"layoutProbeOne\0",
    b"layoutProbeNull\0",
    b"layoutProbeConstant\0",
    b"layoutProbeShort\0",
];
const PROBE_DESCRIPTORS: [&[u8]; 5] = [
    STATIC_INT_DESCRIPTOR,
    STATIC_INT_DESCRIPTOR,
    STATIC_OBJECT_DESCRIPTOR,
    STATIC_INT_DESCRIPTOR,
    STATIC_INT_DESCRIPTOR,
];
const PROBE_BYTECODES: [&[u8]; 5] = [
    &[0x03, 0xac],
    &[0x04, 0xac],
    &[0x01, 0xb0],
    &[0x10, 0x2a, 0xac],
    &[0x11, 0x01, 0x2c, 0xac],
];

const MAX_METHOD_SCAN_BYTES: usize = 160;
const MAX_CONST_METHOD_SCAN_BYTES: usize = 256;
const MAX_KLASS_SCAN_BYTES: usize = 4096;
const MAX_ERROR_LENGTH: usize = 192;
const FIELD_SLOTS: usize = 6;

#[derive(Clone, Copy)]
struct ProbeMethod {
    method: usize,
    const_method: usize,
    code_offset: usize,
    code_length: usize,
}
#[derive(Clone, Copy)]
struct FieldExpectation {
    access_flags: u16,
    offset: u32,
}
#[derive(Clone, Copy)]
struct Encoding {
    base: i64,
    shift: i32,
    compressed: bool,
}

#[derive(Clone, Copy)]
struct Snapshot {
    resolved_method_name_vmtarget_offset: i64,
    method_const_method_offset: i64,
    method_vtable_index_offset: i64,
    const_method_constants_offset: i64,
    const_method_code_size_offset: i64,
    const_method_name_index_offset: i64,
    const_method_method_idnum_offset: i64,
    const_method_original_method_idnum_offset: i64,
    const_method_code_offset: i64,
    constant_pool_length_offset: i64,
    constant_pool_entries_offset: i64,
    java_array_length_offset: i64,
    java_array_elements_offset: i64,
    short_array_elements_offset: i64,
    symbol_length_offset: i64,
    symbol_body_offset: i64,
    class_klass_offset: i64,
    object_klass_offset: i64,
    klass_java_mirror_offset: i64,
    klass_subklass_offset: i64,
    klass_next_sibling_offset: i64,
    direct_method_handle_member_offset: i64,
    member_name_resolved_method_offset: i64,
    reference_slot_value_offset: i64,
    narrow_oop_base: i64,
    narrow_oop_shift: i32,
    compressed_oops: bool,
    narrow_klass_base: i64,
    narrow_klass_shift: i32,
    compressed_klasses: bool,
    klass_word_size: i64,
    vtable_start_offset: i64,
    methods_offset: i64,
    fields_offset: i64,
    java_fields_count_offset: i64,
    class_reflection_data_offset: i64,
    metadata_address_prefix: i64,
    field_access_flags_offset: i32,
    field_name_index_offset: i32,
    field_signature_index_offset: i32,
    field_low_packed_offset: i32,
    field_high_packed_offset: i32,
    field_slots: i32,
    address_size: i32,
}

impl Snapshot {
    const fn empty() -> Self {
        Self {
            resolved_method_name_vmtarget_offset: 0,
            method_const_method_offset: 0,
            method_vtable_index_offset: 0,
            const_method_constants_offset: 0,
            const_method_code_size_offset: 0,
            const_method_name_index_offset: 0,
            const_method_method_idnum_offset: 0,
            const_method_original_method_idnum_offset: 0,
            const_method_code_offset: 0,
            constant_pool_length_offset: 0,
            constant_pool_entries_offset: 0,
            java_array_length_offset: 0,
            java_array_elements_offset: 0,
            short_array_elements_offset: 0,
            symbol_length_offset: 0,
            symbol_body_offset: 0,
            class_klass_offset: 0,
            object_klass_offset: 0,
            klass_java_mirror_offset: 0,
            klass_subklass_offset: 0,
            klass_next_sibling_offset: 0,
            direct_method_handle_member_offset: 0,
            member_name_resolved_method_offset: 0,
            reference_slot_value_offset: 0,
            narrow_oop_base: 0,
            narrow_oop_shift: 0,
            compressed_oops: false,
            narrow_klass_base: 0,
            narrow_klass_shift: 0,
            compressed_klasses: false,
            klass_word_size: 0,
            vtable_start_offset: 0,
            methods_offset: 0,
            fields_offset: 0,
            java_fields_count_offset: 0,
            class_reflection_data_offset: 0,
            metadata_address_prefix: 0,
            field_access_flags_offset: 0,
            field_name_index_offset: 0,
            field_signature_index_offset: 0,
            field_low_packed_offset: 0,
            field_high_packed_offset: 0,
            field_slots: 0,
            address_size: 0,
        }
    }
}

pub unsafe fn create_instance(environment: *mut JNIEnv) -> jobject {
    log::info("createJVM: entered native create_instance");
    let class = find_class(environment, JVM_CLASS_NAME);
    if class.is_null() {
        clear_pending_exception(environment);
        return ptr::null_mut();
    }
    let instance = ((*(*environment)).v1_1.AllocObject)(environment, class);
    if instance.is_null() {
        clear_pending_exception(environment);
        delete_local_reference(environment, class.cast());
        return ptr::null_mut();
    }
    log::info("createJVM: begin probe");
    let mut snapshot = Snapshot::empty();
    let mut error = [0u8; MAX_ERROR_LENGTH];
    let error_length = match probe(environment, &mut snapshot) {
        Ok(()) => 0,
        Err(message) => copy_error(&mut error, message),
    };
    if let Err(_message) = populate_instance(
        environment,
        instance,
        class,
        &snapshot,
        &error,
        error_length,
    ) {
        log::error("Could not populate JVM layout snapshot");
        clear_pending_exception(environment);
    }
    delete_local_reference(environment, class.cast());
    instance
}

unsafe fn probe(environment: *mut JNIEnv, snapshot: &mut Snapshot) -> Result<(), &'static [u8]> {
    log::info("createJVM: resolve core classes");
    let unsafe_class = find_class(environment, UNSAFE_CLASS_NAME);
    let object_class = find_class(environment, OBJECT_CLASS_NAME);
    let string_class = find_class(environment, STRING_CLASS_NAME);
    let class_class = find_class(environment, CLASS_CLASS_NAME);
    let reference_slot_class = find_class(environment, REFERENCE_SLOT_CLASS_NAME);
    if unsafe_class.is_null()
        || object_class.is_null()
        || string_class.is_null()
        || class_class.is_null()
        || reference_slot_class.is_null()
    {
        clear_pending_exception(environment);
        return Err(b"Could not resolve the core JVM probe classes\0");
    }
    let unsafe_object = static_object_field(
        environment,
        unsafe_class,
        FIELD_THE_UNSAFE_NAME,
        UNSAFE_DESCRIPTOR,
    );
    if unsafe_object.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not obtain sun.misc.Unsafe\0");
    }
    let address_size_method = method_id(
        environment,
        unsafe_class,
        ADDRESS_SIZE_NAME,
        ADDRESS_SIZE_DESCRIPTOR,
    );
    snapshot.address_size = call_int_method(environment, unsafe_object, address_size_method);
    if snapshot.address_size != 8 {
        return Err(b"The JVM layout probe requires a 64-bit HotSpot VM\0");
    }
    log::info("createJVM: core offsets resolved");
    let object_array_class = find_class(environment, b"[Ljava/lang/Object;\0");
    let short_array_class = find_class(environment, b"[S\0");
    if object_array_class.is_null() || short_array_class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve Java array probe classes\0");
    }
    let scale_method = method_id(
        environment,
        unsafe_class,
        ARRAY_INDEX_SCALE_NAME,
        ARRAY_INDEX_SCALE_DESCRIPTOR,
    );
    let base_method = method_id(
        environment,
        unsafe_class,
        ARRAY_BASE_OFFSET_NAME,
        ARRAY_BASE_OFFSET_DESCRIPTOR,
    );
    let object_scale = call_int_method_a(
        environment,
        unsafe_object,
        scale_method,
        &[jvalue_class(object_array_class)],
    );
    let object_base = call_int_method_a(
        environment,
        unsafe_object,
        base_method,
        &[jvalue_class(object_array_class)],
    );
    let short_base = call_int_method_a(
        environment,
        unsafe_object,
        base_method,
        &[jvalue_class(short_array_class)],
    );
    if object_scale != 4 || object_base <= 0 || short_base <= 0 {
        return Err(b"Unsupported Java object reference representation\0");
    }
    log::info("createJVM: resolve dynamic Java offsets");
    snapshot.reference_slot_value_offset = object_field_offset(
        environment,
        unsafe_object,
        reference_slot_class,
        FIELD_VALUE_NAME,
    )?;
    snapshot.direct_method_handle_member_offset = object_field_offset_by_class(
        environment,
        unsafe_object,
        DIRECT_METHOD_HANDLE_CLASS_NAME,
        FIELD_MEMBER_NAME,
    )?;
    snapshot.member_name_resolved_method_offset = object_field_offset_by_class(
        environment,
        unsafe_object,
        MEMBER_NAME_CLASS_NAME,
        FIELD_METHOD_NAME,
    )?;
    snapshot.class_reflection_data_offset = object_field_offset(
        environment,
        unsafe_object,
        class_class,
        FIELD_REFLECTION_DATA_NAME,
    )?;

    let reference_slot = ((*(*environment)).v1_1.AllocObject)(environment, reference_slot_class);
    if reference_slot.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not allocate the reference probe\0");
    }
    let object_klass = class_klass_pointer(object_class);
    let string_klass = class_klass_pointer(string_class);
    let (class_klass_offset, mirror_offset) =
        find_class_klass_layout(object_class, string_class, object_klass, string_klass, true)?;
    snapshot.class_klass_offset = class_klass_offset as i64;
    snapshot.klass_java_mirror_offset = mirror_offset as i64;
    let object_mirror = read_class_mirror(object_klass, mirror_offset, true)?;
    let string_mirror = read_class_mirror(string_klass, mirror_offset, true)?;
    let object_narrow = reference_encoding(
        environment,
        reference_slot,
        reference_slot_class,
        snapshot.reference_slot_value_offset as usize,
        object_class,
    )?;
    let string_narrow = reference_encoding(
        environment,
        reference_slot,
        reference_slot_class,
        snapshot.reference_slot_value_offset as usize,
        string_class,
    )?;
    let oop_encoding = derive_encoding(object_mirror, string_mirror, object_narrow, string_narrow)?;
    snapshot.narrow_oop_base = oop_encoding.base;
    snapshot.narrow_oop_shift = oop_encoding.shift;
    snapshot.compressed_oops = oop_encoding.compressed;

    log::info("createJVM: oop encoding resolved");
    let object_instance = ((*(*environment)).v1_1.AllocObject)(environment, object_class);
    let string_instance =
        ((*(*environment)).v1_1.NewStringUTF)(environment, b"jvm-layout\0".as_ptr().cast());
    if object_instance.is_null() || string_instance.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not allocate object header probes\0");
    }
    let (object_klass_offset, klass_encoding) = find_object_klass_layout(
        object_instance as usize,
        string_instance as usize,
        object_klass,
        string_klass,
    )?;
    snapshot.object_klass_offset = object_klass_offset as i64;
    snapshot.compressed_klasses = klass_encoding.compressed;
    snapshot.narrow_klass_base = klass_encoding.base;
    snapshot.narrow_klass_shift = klass_encoding.shift;
    snapshot.klass_word_size = if klass_encoding.compressed { 4 } else { 8 };

    let object_array =
        ((*(*environment)).v1_1.NewObjectArray)(environment, 1, object_class, ptr::null_mut());
    let short_array = ((*(*environment)).v1_1.NewShortArray)(environment, 1);
    let int_array = ((*(*environment)).v1_1.NewIntArray)(environment, 1);
    if object_array.is_null() || short_array.is_null() || int_array.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not allocate Java array probes\0");
    }
    snapshot.java_array_length_offset = find_java_array_length_offset(
        object_array as usize,
        short_array as usize,
        int_array as usize,
    )? as i64;
    snapshot.short_array_elements_offset = find_short_array_elements_offset(
        environment,
        short_array,
        snapshot.java_array_length_offset as usize,
    )? as i64;
    let string_value =
        ((*(*environment)).v1_1.NewStringUTF)(environment, b"array-value\0".as_ptr().cast());
    ((*(*environment)).v1_1.SetObjectArrayElement)(environment, object_array, 0, string_value);
    snapshot.java_array_elements_offset = find_object_array_elements_offset(
        object_array as usize,
        string_value as usize,
        oop_encoding,
        snapshot.java_array_length_offset as usize,
    )? as i64;

    log::info("createJVM: array layout resolved");
    let method_layout = locate_method_layout(environment)?;
    snapshot.resolved_method_name_vmtarget_offset = method_layout.0 as i64;
    snapshot.method_const_method_offset = method_layout.1 as i64;
    snapshot.const_method_code_offset = method_layout.2 as i64;
    snapshot.const_method_code_size_offset = method_layout.3 as i64;
    snapshot.const_method_name_index_offset = method_layout.4 as i64;
    snapshot.const_method_method_idnum_offset = method_layout.2.saturating_sub(10) as i64;
    snapshot.const_method_original_method_idnum_offset = method_layout.2.saturating_sub(2) as i64;
    snapshot.method_vtable_index_offset = locate_vtable_layout(environment, snapshot)? as i64;
    snapshot.vtable_start_offset = locate_vtable_start(environment, snapshot)? as i64;
    snapshot.metadata_address_prefix = (method_layout.5 >> 32) as i64;
    let pool_layout = locate_constant_pool_layout(environment, &method_layout.6)?;
    snapshot.const_method_constants_offset = pool_layout.0 as i64;
    snapshot.constant_pool_length_offset = pool_layout.1 as i64;
    snapshot.constant_pool_entries_offset = pool_layout.2 as i64;
    snapshot.symbol_length_offset = pool_layout.3 as i64;
    snapshot.symbol_body_offset = pool_layout.4 as i64;
    snapshot.const_method_name_index_offset = pool_layout.5 as i64;

    log::info("createJVM: method layout resolved");
    let parent_class = find_class(environment, KLASS_PARENT_CLASS_NAME);
    let child_class = find_class(environment, KLASS_CHILD_CLASS_NAME);
    let sibling_class = find_class(environment, KLASS_SIBLING_CLASS_NAME);
    if parent_class.is_null() || child_class.is_null() || sibling_class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve Klass hierarchy probes\0");
    }
    let parent_klass = class_klass_pointer(parent_class);
    let child_klass = class_klass_pointer(child_class);
    let sibling_klass = class_klass_pointer(sibling_class);
    snapshot.klass_subklass_offset =
        find_pointer_offset(parent_klass, child_klass, MAX_KLASS_SCAN_BYTES)? as i64;
    snapshot.klass_next_sibling_offset =
        find_pointer_offset(child_klass, sibling_klass, MAX_KLASS_SCAN_BYTES)? as i64;
    log::info("createJVM: klass hierarchy resolved");
    snapshot.methods_offset = locate_methods_offset(environment)? as i64;
    log::info("createJVM: methods table resolved");
    let field_layout = locate_fields_layout(environment)?;
    snapshot.fields_offset = field_layout.0 as i64;
    snapshot.field_access_flags_offset = field_layout.1 as i32;
    snapshot.field_name_index_offset = field_layout.2 as i32;
    snapshot.field_signature_index_offset = field_layout.3 as i32;
    snapshot.field_low_packed_offset = field_layout.4 as i32;
    snapshot.field_high_packed_offset = field_layout.5 as i32;
    snapshot.field_slots = field_layout.6 as i32;
    log::info("createJVM: fields table resolved");
    snapshot.java_fields_count_offset = locate_java_fields_count_offset(environment)? as i64;
    let _ = (
        reference_slot,
        object_instance,
        string_instance,
        object_array,
        short_array,
        int_array,
        string_value,
        unsafe_object,
        unsafe_class,
        object_class,
        string_class,
        class_class,
        reference_slot_class,
    );
    Ok(())
}

unsafe fn populate_instance(
    environment: *mut JNIEnv,
    instance: jobject,
    class: jclass,
    snapshot: &Snapshot,
    error: &[u8; MAX_ERROR_LENGTH],
    error_length: usize,
) -> Result<(), &'static [u8]> {
    macro_rules! sl {
        ($n:expr, $v:expr) => {
            set_long_field(environment, instance, class, $n, $v)?;
        };
    }
    macro_rules! si {
        ($n:expr, $v:expr) => {
            set_int_field(environment, instance, class, $n, $v)?;
        };
    }
    macro_rules! sb {
        ($n:expr, $v:expr) => {
            set_boolean_field(environment, instance, class, $n, $v)?;
        };
    }
    sl!(
        b"resolvedMethodNameVmtargetOffset\0",
        snapshot.resolved_method_name_vmtarget_offset
    );
    sl!(
        b"methodConstMethodOffset\0",
        snapshot.method_const_method_offset
    );
    sl!(
        b"methodVtableIndexOffset\0",
        snapshot.method_vtable_index_offset
    );
    sl!(
        b"constMethodConstantsOffset\0",
        snapshot.const_method_constants_offset
    );
    sl!(
        b"constMethodCodeSizeOffset\0",
        snapshot.const_method_code_size_offset
    );
    sl!(
        b"constMethodNameIndexOffset\0",
        snapshot.const_method_name_index_offset
    );
    sl!(
        b"constMethodMethodIdnumOffset\0",
        snapshot.const_method_method_idnum_offset
    );
    sl!(
        b"constMethodOriginalMethodIdnumOffset\0",
        snapshot.const_method_original_method_idnum_offset
    );
    sl!(
        b"constMethodCodeOffset\0",
        snapshot.const_method_code_offset
    );
    sl!(
        b"constantPoolLengthOffset\0",
        snapshot.constant_pool_length_offset
    );
    sl!(
        b"constantPoolEntriesOffset\0",
        snapshot.constant_pool_entries_offset
    );
    sl!(b"metadataArrayLengthOffset\0", 0);
    sl!(b"metadataArrayElementsOffset\0", 8);
    sl!(
        b"javaArrayLengthOffset\0",
        snapshot.java_array_length_offset
    );
    sl!(
        b"javaArrayElementsOffset\0",
        snapshot.java_array_elements_offset
    );
    sl!(
        b"shortArrayElementsOffset\0",
        snapshot.short_array_elements_offset
    );
    sl!(b"arrayLengthOffset\0", 0);
    sl!(b"arrayElementsOffset\0", 8);
    sl!(b"symbolLengthOffset\0", snapshot.symbol_length_offset);
    sl!(b"symbolBodyOffset\0", snapshot.symbol_body_offset);
    sl!(b"classKlassOffset\0", snapshot.class_klass_offset);
    sl!(b"objectKlassOffset\0", snapshot.object_klass_offset);
    sl!(
        b"klassJavaMirrorOffset\0",
        snapshot.klass_java_mirror_offset
    );
    sl!(b"klassSubklassOffset\0", snapshot.klass_subklass_offset);
    sl!(
        b"klassNextSiblingOffset\0",
        snapshot.klass_next_sibling_offset
    );
    sl!(
        b"directMethodHandleMemberOffset\0",
        snapshot.direct_method_handle_member_offset
    );
    sl!(
        b"memberNameResolvedMethodOffset\0",
        snapshot.member_name_resolved_method_offset
    );
    sl!(
        b"referenceSlotValueOffset\0",
        snapshot.reference_slot_value_offset
    );
    sl!(b"narrowOopBase\0", snapshot.narrow_oop_base);
    si!(b"narrowOopShift\0", snapshot.narrow_oop_shift);
    sb!(b"compressedOops\0", snapshot.compressed_oops);
    sl!(b"narrowKlassBase\0", snapshot.narrow_klass_base);
    si!(b"narrowKlassShift\0", snapshot.narrow_klass_shift);
    sb!(b"compressedKlasses\0", snapshot.compressed_klasses);
    sl!(b"klassWordSize\0", snapshot.klass_word_size);
    sl!(b"vtableStartOffset\0", snapshot.vtable_start_offset);
    sl!(b"methodsOffset\0", snapshot.methods_offset);
    sl!(b"fieldsOffset\0", snapshot.fields_offset);
    sl!(
        b"javaFieldsCountOffset\0",
        snapshot.java_fields_count_offset
    );
    sl!(
        b"classReflectionDataOffset\0",
        snapshot.class_reflection_data_offset
    );
    sl!(b"metadataAddressPrefix\0", snapshot.metadata_address_prefix);
    si!(
        b"fieldAccessFlagsOffset\0",
        snapshot.field_access_flags_offset
    );
    si!(b"fieldNameIndexOffset\0", snapshot.field_name_index_offset);
    si!(
        b"fieldSignatureIndexOffset\0",
        snapshot.field_signature_index_offset
    );
    si!(b"fieldLowPackedOffset\0", snapshot.field_low_packed_offset);
    si!(
        b"fieldHighPackedOffset\0",
        snapshot.field_high_packed_offset
    );
    si!(b"fieldSlots\0", snapshot.field_slots);
    si!(b"addressSize\0", snapshot.address_size);
    sb!(b"valid\0", error_length == 0);
    let error_string = if error_length == 0 {
        ptr::null_mut()
    } else {
        let length = error_length.min(MAX_ERROR_LENGTH - 1);
        let mut text = [0u8; MAX_ERROR_LENGTH];
        text[..length].copy_from_slice(&error[..length]);
        text[length] = 0;
        ((*(*environment)).v1_1.NewStringUTF)(environment, text.as_ptr().cast())
    };
    let field = field_id(environment, class, b"errorMessage\0", STRING_DESCRIPTOR);
    if field.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not populate JVM.errorMessage\0");
    }
    ((*(*environment)).v1_1.SetObjectField)(environment, instance, field, error_string);
    if !error_string.is_null() {
        delete_local_reference(environment, error_string);
    }
    Ok(())
}

unsafe fn find_class(environment: *mut JNIEnv, name: &[u8]) -> jclass {
    ((*(*environment)).v1_1.FindClass)(environment, name.as_ptr().cast())
}
unsafe fn method_id(
    environment: *mut JNIEnv,
    class: jclass,
    name: &[u8],
    descriptor: &[u8],
) -> jmethodID {
    ((*(*environment)).v1_1.GetMethodID)(
        environment,
        class,
        name.as_ptr().cast(),
        descriptor.as_ptr().cast(),
    )
}
unsafe fn static_method_id(
    environment: *mut JNIEnv,
    class: jclass,
    name: &[u8],
    descriptor: &[u8],
) -> jmethodID {
    ((*(*environment)).v1_1.GetStaticMethodID)(
        environment,
        class,
        name.as_ptr().cast(),
        descriptor.as_ptr().cast(),
    )
}
unsafe fn field_id(
    environment: *mut JNIEnv,
    class: jclass,
    name: &[u8],
    descriptor: &[u8],
) -> jni_sys::jfieldID {
    ((*(*environment)).v1_1.GetFieldID)(
        environment,
        class,
        name.as_ptr().cast(),
        descriptor.as_ptr().cast(),
    )
}
unsafe fn static_field_id(
    environment: *mut JNIEnv,
    class: jclass,
    name: &[u8],
    descriptor: &[u8],
) -> jni_sys::jfieldID {
    ((*(*environment)).v1_1.GetStaticFieldID)(
        environment,
        class,
        name.as_ptr().cast(),
        descriptor.as_ptr().cast(),
    )
}
unsafe fn static_object_field(
    environment: *mut JNIEnv,
    class: jclass,
    name: &[u8],
    descriptor: &[u8],
) -> jobject {
    let field = static_field_id(environment, class, name, descriptor);
    if field.is_null() {
        ptr::null_mut()
    } else {
        ((*(*environment)).v1_1.GetStaticObjectField)(environment, class, field)
    }
}
unsafe fn call_int_method(environment: *mut JNIEnv, object: jobject, method: jmethodID) -> jint {
    ((*(*environment)).v1_1.CallIntMethodA)(environment, object, method, ptr::null())
}
unsafe fn call_int_method_a(
    environment: *mut JNIEnv,
    object: jobject,
    method: jmethodID,
    args: &[jvalue],
) -> jint {
    ((*(*environment)).v1_1.CallIntMethodA)(environment, object, method, args.as_ptr())
}
unsafe fn call_long_method_a(
    environment: *mut JNIEnv,
    object: jobject,
    method: jmethodID,
    args: &[jvalue],
) -> jlong {
    ((*(*environment)).v1_1.CallLongMethodA)(environment, object, method, args.as_ptr())
}
unsafe fn call_object_method_a(
    environment: *mut JNIEnv,
    object: jobject,
    method: jmethodID,
    args: &[jvalue],
) -> jobject {
    let args = if args.is_empty() {
        ptr::null()
    } else {
        args.as_ptr()
    };
    ((*(*environment)).v1_1.CallObjectMethodA)(environment, object, method, args)
}
unsafe fn call_static_object_method_a(
    environment: *mut JNIEnv,
    class: jclass,
    method: jmethodID,
    args: &[jvalue],
) -> jobject {
    let args = if args.is_empty() {
        ptr::null()
    } else {
        args.as_ptr()
    };
    ((*(*environment)).v1_1.CallStaticObjectMethodA)(environment, class, method, args)
}
unsafe fn object_field_offset(
    environment: *mut JNIEnv,
    unsafe_object: jobject,
    class: jclass,
    name: &[u8],
) -> Result<i64, &'static [u8]> {
    let field = get_declared_field(environment, class, name);
    if field.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve a Java field\0");
    }
    let unsafe_class = ((*(*environment)).v1_1.GetObjectClass)(environment, unsafe_object);
    let method = method_id(
        environment,
        unsafe_class,
        OBJECT_FIELD_OFFSET_NAME,
        OBJECT_FIELD_OFFSET_DESCRIPTOR,
    );
    let result = call_long_method_a(environment, unsafe_object, method, &[jvalue_object(field)]);
    delete_local_reference(environment, field);
    delete_local_reference(environment, unsafe_class.cast());
    if has_pending_exception(environment) {
        clear_pending_exception(environment);
        Err(b"Unsafe.objectFieldOffset failed\0")
    } else {
        Ok(result)
    }
}
unsafe fn object_field_offset_by_class(
    environment: *mut JNIEnv,
    unsafe_object: jobject,
    class_name: &[u8],
    name: &[u8],
) -> Result<i64, &'static [u8]> {
    let class = find_class(environment, class_name);
    if class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve an internal Java class\0");
    }
    let result = object_field_offset(environment, unsafe_object, class, name);
    delete_local_reference(environment, class.cast());
    result
}
unsafe fn get_declared_field(environment: *mut JNIEnv, class: jclass, name: &[u8]) -> jobject {
    let class_class = find_class(environment, CLASS_CLASS_NAME);
    let method = method_id(
        environment,
        class_class,
        GET_DECLARED_FIELD_NAME,
        GET_DECLARED_FIELD_DESCRIPTOR,
    );
    let string = ((*(*environment)).v1_1.NewStringUTF)(environment, name.as_ptr().cast());
    let result = call_object_method_a(environment, class.cast(), method, &[jvalue_object(string)]);
    delete_local_reference(environment, string);
    delete_local_reference(environment, class_class.cast());
    result
}
unsafe fn set_long_field(
    environment: *mut JNIEnv,
    object: jobject,
    class: jclass,
    name: &[u8],
    value: i64,
) -> Result<(), &'static [u8]> {
    let field = field_id(environment, class, name, LONG_DESCRIPTOR);
    if field.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not populate a JVM long field\0");
    }
    ((*(*environment)).v1_1.SetLongField)(environment, object, field, value);
    Ok(())
}
unsafe fn set_int_field(
    environment: *mut JNIEnv,
    object: jobject,
    class: jclass,
    name: &[u8],
    value: i32,
) -> Result<(), &'static [u8]> {
    let field = field_id(environment, class, name, INT_DESCRIPTOR);
    if field.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not populate a JVM integer field\0");
    }
    ((*(*environment)).v1_1.SetIntField)(environment, object, field, value);
    Ok(())
}
unsafe fn set_boolean_field(
    environment: *mut JNIEnv,
    object: jobject,
    class: jclass,
    name: &[u8],
    value: bool,
) -> Result<(), &'static [u8]> {
    let field = field_id(environment, class, name, BOOLEAN_DESCRIPTOR);
    if field.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not populate a JVM boolean field\0");
    }
    ((*(*environment)).v1_1.SetBooleanField)(environment, object, field, value);
    Ok(())
}
unsafe fn jvalue_object(value: jobject) -> jvalue {
    jvalue { l: value }
}
unsafe fn jvalue_class(value: jclass) -> jvalue {
    jvalue { l: value.cast() }
}
unsafe fn has_pending_exception(environment: *mut JNIEnv) -> bool {
    ((*(*environment)).v1_2.ExceptionCheck)(environment)
}
unsafe fn clear_pending_exception(environment: *mut JNIEnv) {
    if has_pending_exception(environment) {
        ((*(*environment)).v1_1.ExceptionClear)(environment);
    }
}
unsafe fn delete_local_reference(environment: *mut JNIEnv, object: jobject) {
    if !object.is_null() {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, object);
    }
}

#[repr(C)]
struct MemoryBasicInformation {
    base_address: *mut c_void,
    allocation_base: *mut c_void,
    allocation_protect: u32,
    padding: u32,
    region_size: usize,
    state: u32,
    protect: u32,
    type_: u32,
}
const MEM_COMMIT: u32 = 0x1000;
const PAGE_NOACCESS: u32 = 1;
const PAGE_GUARD: u32 = 0x100;
#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetCurrentProcess() -> *mut c_void;
    fn ReadProcessMemory(
        process: *mut c_void,
        base_address: *const c_void,
        buffer: *mut c_void,
        size: usize,
        read: *mut usize,
    ) -> i32;
    fn VirtualQuery(
        address: *const c_void,
        buffer: *mut MemoryBasicInformation,
        length: usize,
    ) -> usize;
}
unsafe fn is_readable(address: usize, length: usize) -> bool {
    if address == 0 || length == 0 || address.checked_add(length).is_none() {
        return false;
    }
    let mut info = MemoryBasicInformation {
        base_address: ptr::null_mut(),
        allocation_base: ptr::null_mut(),
        allocation_protect: 0,
        padding: 0,
        region_size: 0,
        state: 0,
        protect: 0,
        type_: 0,
    };
    let result = VirtualQuery(
        address as *const c_void,
        &mut info,
        size_of::<MemoryBasicInformation>(),
    );
    result != 0
        && info.state == MEM_COMMIT
        && info.protect & PAGE_NOACCESS == 0
        && info.protect & PAGE_GUARD == 0
        && address >= info.base_address as usize
        && address + length <= (info.base_address as usize).saturating_add(info.region_size)
}
unsafe fn read_bytes(address: usize, destination: *mut u8, length: usize) -> bool {
    if !is_readable(address, length) || destination.is_null() {
        return false;
    }
    let mut read = 0usize;
    ReadProcessMemory(
        GetCurrentProcess(),
        address as *const c_void,
        destination.cast(),
        length,
        &mut read,
    ) != 0
        && read == length
}
unsafe fn read_u8(address: usize) -> Option<u8> {
    let mut value = 0;
    if read_bytes(address, &mut value, 1) {
        Some(value)
    } else {
        None
    }
}
unsafe fn read_u16(address: usize) -> Option<u16> {
    let mut value = 0;
    if read_bytes(address, (&mut value as *mut u16).cast(), 2) {
        Some(value)
    } else {
        None
    }
}
unsafe fn read_u32(address: usize) -> Option<u32> {
    let mut value = 0;
    if read_bytes(address, (&mut value as *mut u32).cast(), 4) {
        Some(value)
    } else {
        None
    }
}
unsafe fn read_u64(address: usize) -> Option<u64> {
    let mut value = 0;
    if read_bytes(address, (&mut value as *mut u64).cast(), 8) {
        Some(value)
    } else {
        None
    }
}
unsafe fn read_pointer(address: usize) -> Option<usize> {
    read_u64(address).map(|value| value as usize)
}
fn plausible_pointer(value: usize) -> bool {
    value >= 0x10000 && value <= 0x0000_7fff_ffff_ffff && value & 7 == 0
}

unsafe fn class_klass_pointer(class: jclass) -> usize {
    read_pointer(class as usize + 16).unwrap_or(0)
}
unsafe fn find_class_klass_layout(
    object_class: jclass,
    string_class: jclass,
    object_klass: usize,
    string_klass: usize,
    compressed_oops: bool,
) -> Result<(usize, usize), &'static [u8]> {
    if !plausible_pointer(object_klass) || !plausible_pointer(string_klass) {
        return Err(b"Could not read java.lang.Class klass pointers\0");
    }
    for class_offset in (0..128).step_by(8) {
        if read_pointer(object_class as usize + class_offset) != Some(object_klass)
            || read_pointer(string_class as usize + class_offset) != Some(string_klass)
        {
            continue;
        }
        for mirror_offset in (0..512).step_by(8) {
            let object_handle = read_pointer(object_klass + mirror_offset).unwrap_or(0);
            let string_handle = read_pointer(string_klass + mirror_offset).unwrap_or(0);
            if !plausible_pointer(object_handle) || !plausible_pointer(string_handle) {
                continue;
            }
            if read_oop_handle(object_handle, compressed_oops).unwrap_or(0) == object_class as usize
                && read_oop_handle(string_handle, compressed_oops).unwrap_or(0)
                    == string_class as usize
            {
                return Ok((class_offset, mirror_offset));
            }
        }
    }
    Err(b"Could not dynamically locate Klass and java mirror offsets\0")
}
unsafe fn read_oop_handle(address: usize, compressed: bool) -> Option<usize> {
    if compressed {
        read_u32(address).map(|v| v as usize)
    } else {
        read_pointer(address)
    }
}
unsafe fn read_class_mirror(
    klass: usize,
    offset: usize,
    compressed: bool,
) -> Result<usize, &'static [u8]> {
    let handle = read_pointer(klass + offset).unwrap_or(0);
    if !plausible_pointer(handle) {
        return Err(b"Klass java mirror handle is not readable\0");
    }
    read_oop_handle(handle, compressed).ok_or(b"Klass java mirror is not readable\0")
}
unsafe fn reference_encoding(
    environment: *mut JNIEnv,
    slot: jobject,
    slot_class: jclass,
    offset: usize,
    value: jobject,
) -> Result<usize, &'static [u8]> {
    let field = field_id(environment, slot_class, FIELD_VALUE_NAME, OBJECT_DESCRIPTOR);
    if field.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve reference probe field\0");
    }
    ((*(*environment)).v1_1.SetObjectField)(environment, slot, field, value);
    let result = read_u32(slot as usize + offset)
        .map(|value| value as usize)
        .ok_or(&b"Could not read a compressed object reference\0"[..]);
    ((*(*environment)).v1_1.SetObjectField)(environment, slot, field, ptr::null_mut());
    result
}
fn derive_encoding(
    raw_a: usize,
    raw_b: usize,
    narrow_a: usize,
    narrow_b: usize,
) -> Result<Encoding, &'static [u8]> {
    if raw_a == 0 || raw_b == 0 || narrow_a == narrow_b {
        return Err(b"Could not derive compressed OOP encoding\0");
    }
    let raw_delta = raw_b as i128 - raw_a as i128;
    let narrow_delta = narrow_b as i128 - narrow_a as i128;
    if raw_delta == 0 || narrow_delta == 0 || raw_delta % narrow_delta != 0 {
        return Err(b"Could not derive compressed OOP encoding\0");
    }
    let scale = (raw_delta / narrow_delta) as i64;
    if scale <= 0 || scale & (scale - 1) != 0 {
        return Err(b"Unsupported compressed OOP scale\0");
    }
    let shift = scale.trailing_zeros() as i32;
    let base = raw_a as i64 - ((narrow_a as i64) << shift);
    if base < 0 {
        return Err(b"Unsupported compressed OOP base\0");
    }
    Ok(Encoding {
        base,
        shift,
        compressed: true,
    })
}
unsafe fn find_object_klass_layout(
    object: usize,
    string: usize,
    object_klass: usize,
    string_klass: usize,
) -> Result<(usize, Encoding), &'static [u8]> {
    for offset in (0..64).step_by(4) {
        let object_narrow = read_u32(object + offset).unwrap_or(0) as usize;
        let string_narrow = read_u32(string + offset).unwrap_or(0) as usize;
        if object_narrow != string_narrow {
            if let Ok(encoding) =
                derive_encoding(object_klass, string_klass, object_narrow, string_narrow)
            {
                if decode_narrow(object_narrow, encoding) == object_klass
                    && decode_narrow(string_narrow, encoding) == string_klass
                {
                    return Ok((offset, encoding));
                }
            }
        }
    }
    for offset in (0..64).step_by(8) {
        if read_pointer(object + offset) == Some(object_klass)
            && read_pointer(string + offset) == Some(string_klass)
        {
            return Ok((
                offset,
                Encoding {
                    base: 0,
                    shift: 0,
                    compressed: false,
                },
            ));
        }
    }
    Err(b"Could not dynamically locate object Klass encoding\0")
}
fn decode_narrow(value: usize, encoding: Encoding) -> usize {
    (encoding.base as usize).wrapping_add(value << encoding.shift)
}
unsafe fn find_java_array_length_offset(
    object_array: usize,
    short_array: usize,
    int_array: usize,
) -> Result<usize, &'static [u8]> {
    for offset in (0..32).step_by(4) {
        if read_u32(object_array + offset) == Some(1)
            && read_u32(short_array + offset) == Some(1)
            && read_u32(int_array + offset) == Some(1)
        {
            return Ok(offset);
        }
    }
    Err(b"Could not locate Java array length offset\0")
}
unsafe fn find_short_array_elements_offset(
    environment: *mut JNIEnv,
    array: jni_sys::jshortArray,
    length_offset: usize,
) -> Result<usize, &'static [u8]> {
    let value: jshort = 0x3579;
    ((*(*environment)).v1_1.SetShortArrayRegion)(environment, array, 0, 1, &value);
    for offset in (length_offset + 4..64).step_by(2) {
        if read_u16(array as usize + offset) == Some(value as u16) {
            return Ok(offset);
        }
    }
    Err(b"Could not locate Java short[] element offset\0")
}
unsafe fn find_object_array_elements_offset(
    object_array: usize,
    string_object: usize,
    encoding: Encoding,
    length_offset: usize,
) -> Result<usize, &'static [u8]> {
    let raw = encode_narrow(string_object, encoding)?;
    if encoding.compressed {
        for offset in (length_offset + 4..64).step_by(4) {
            if read_u32(object_array + offset) == Some(raw as u32) {
                return Ok(offset);
            }
        }
    } else {
        for offset in (length_offset + 4..64).step_by(8) {
            if read_u64(object_array + offset) == Some(raw as u64) {
                return Ok(offset);
            }
        }
    }
    Err(b"Could not locate Java Object[] element offset\0")
}
fn encode_narrow(raw: usize, encoding: Encoding) -> Result<usize, &'static [u8]> {
    if !encoding.compressed {
        return Ok(raw);
    }
    let base = encoding.base as usize;
    if raw < base {
        return Err(b"Could not encode a Java reference\0");
    }
    let delta = raw - base;
    let mask = (1usize << encoding.shift) - 1;
    if delta & mask != 0 {
        return Err(b"Java reference is not aligned for compressed OOPs\0");
    }
    Ok(delta >> encoding.shift)
}

unsafe fn reflected_static_method(
    environment: *mut JNIEnv,
    class: jclass,
    name: &[u8],
    descriptor: &[u8],
) -> jobject {
    let id = static_method_id(environment, class, name, descriptor);
    if id.is_null() {
        ptr::null_mut()
    } else {
        ((*(*environment)).v1_2.ToReflectedMethod)(environment, class, id, true)
    }
}
unsafe fn locate_method_layout(
    environment: *mut JNIEnv,
) -> Result<(usize, usize, usize, usize, usize, usize, [ProbeMethod; 5]), &'static [u8]> {
    let class = find_class(environment, JVM_CLASS_NAME);
    let probe_class = find_class(environment, NATIVE_PROBE_CLASS_NAME);
    if class.is_null() || probe_class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve method layout probes\0");
    }
    let direct = static_method_id(
        environment,
        probe_class,
        DIRECT_PROBE_NAME,
        DIRECT_PROBE_DESCRIPTOR,
    );
    let mut probes = [ProbeMethod {
        method: 0,
        const_method: 0,
        code_offset: 0,
        code_length: 0,
    }; 5];
    let mut resolved_offset = usize::MAX;
    let mut const_offset = usize::MAX;
    let mut code_offset = usize::MAX;
    let mut first_method = 0;
    for index in 0..5 {
        let reflected = reflected_static_method(
            environment,
            class,
            PROBE_NAMES[index],
            PROBE_DESCRIPTORS[index],
        );
        if reflected.is_null() {
            clear_pending_exception(environment);
            return Err(b"Could not reflect a JVM method probe\0");
        }
        let handle = call_static_object_method_a(
            environment,
            probe_class,
            direct,
            &[jvalue_object(reflected)],
        );
        if handle.is_null() {
            clear_pending_exception(environment);
            return Err(b"Could not create a direct method handle\0");
        }
        let handle_class = ((*(*environment)).v1_1.GetObjectClass)(environment, handle);
        let member_field = field_id(
            environment,
            handle_class,
            FIELD_MEMBER_NAME,
            OBJECT_DESCRIPTOR,
        );
        let member = ((*(*environment)).v1_1.GetObjectField)(environment, handle, member_field);
        let member_class = if member.is_null() {
            ptr::null_mut()
        } else {
            ((*(*environment)).v1_1.GetObjectClass)(environment, member)
        };
        let method_field = if member_class.is_null() {
            ptr::null_mut()
        } else {
            field_id(
                environment,
                member_class,
                FIELD_METHOD_NAME,
                OBJECT_DESCRIPTOR,
            )
        };
        let resolved = if method_field.is_null() {
            ptr::null_mut()
        } else {
            ((*(*environment)).v1_1.GetObjectField)(environment, member, method_field)
        };
        let method_pointer =
            ((*(*environment)).v1_2.FromReflectedMethod)(environment, reflected) as usize;
        if first_method == 0 {
            first_method = method_pointer;
        }
        if resolved.is_null() || method_pointer == 0 {
            return Err(b"Could not resolve a HotSpot Method pointer\0");
        }
        let vmtarget = find_pointer_offset(resolved as usize, method_pointer, 128)?;
        if resolved_offset == usize::MAX {
            resolved_offset = vmtarget
        } else if resolved_offset != vmtarget {
            return Err(b"Inconsistent ResolvedMethodName layout\0");
        }
        let (const_method, current_const_offset, current_code_offset) =
            locate_const_method(method_pointer, PROBE_BYTECODES[index])?;
        if const_offset == usize::MAX {
            const_offset = current_const_offset
        } else if const_offset != current_const_offset {
            return Err(b"Inconsistent Method/ConstMethod layout\0");
        }
        if code_offset == usize::MAX {
            code_offset = current_code_offset
        } else if code_offset != current_code_offset {
            return Err(b"Inconsistent ConstMethod code layout\0");
        }
        probes[index] = ProbeMethod {
            method: method_pointer,
            const_method,
            code_offset: current_code_offset,
            code_length: PROBE_BYTECODES[index].len(),
        };
        delete_local_reference(environment, handle);
        delete_local_reference(environment, reflected);
        delete_local_reference(environment, handle_class.cast());
        if !member.is_null() {
            delete_local_reference(environment, member)
        }
        if !member_class.is_null() {
            delete_local_reference(environment, member_class.cast())
        }
        if !resolved.is_null() {
            delete_local_reference(environment, resolved)
        }
    }
    let code_size_offset = find_common_u16_offset(&probes, |probe| probe.code_length as u16)?;
    delete_local_reference(environment, class.cast());
    delete_local_reference(environment, probe_class.cast());
    Ok((
        resolved_offset,
        const_offset,
        code_offset,
        code_size_offset,
        0,
        first_method,
        probes,
    ))
}
unsafe fn locate_const_method(
    method: usize,
    bytecode: &[u8],
) -> Result<(usize, usize, usize), &'static [u8]> {
    for method_offset in (0..MAX_METHOD_SCAN_BYTES).step_by(8) {
        let candidate = read_pointer(method + method_offset).unwrap_or(0);
        if !plausible_pointer(candidate) {
            continue;
        }
        for code_offset in 0..MAX_CONST_METHOD_SCAN_BYTES.saturating_sub(bytecode.len()) {
            if bytes_equal(candidate + code_offset, bytecode) {
                return Ok((candidate, method_offset, code_offset));
            }
        }
    }
    Err(b"Could not locate a method's ConstMethod\0")
}
unsafe fn bytes_equal(address: usize, expected: &[u8]) -> bool {
    if !is_readable(address, expected.len()) {
        return false;
    }
    for (index, value) in expected.iter().enumerate() {
        if read_u8(address + index) != Some(*value) {
            return false;
        }
    }
    true
}
unsafe fn find_common_u16_offset(
    probes: &[ProbeMethod; 5],
    expected: impl Fn(&ProbeMethod) -> u16,
) -> Result<usize, &'static [u8]> {
    let first = probes[0];
    for offset in (0..64).step_by(2) {
        if read_u16(first.const_method + offset) != Some(expected(&first)) {
            continue;
        }
        let mut all = true;
        for probe in probes.iter().skip(1) {
            if read_u16(probe.const_method + offset) != Some(expected(probe)) {
                all = false;
                break;
            }
        }
        if all {
            return Ok(offset);
        }
    }
    Err(b"Could not locate a common ConstMethod u2 field\0")
}

unsafe fn reflected_instance_method(
    environment: *mut JNIEnv,
    class: jclass,
    name: &[u8],
    descriptor: &[u8],
) -> jobject {
    let id = method_id(environment, class, name, descriptor);
    if id.is_null() {
        ptr::null_mut()
    } else {
        ((*(*environment)).v1_2.ToReflectedMethod)(environment, class, id, false)
    }
}
unsafe fn locate_vtable_layout(
    environment: *mut JNIEnv,
    _snapshot: &Snapshot,
) -> Result<usize, &'static [u8]> {
    let class = find_class(environment, VTABLE_PROBE_CLASS_NAME);
    if class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve the vtable probe\0");
    }
    let mut methods = [0usize; 3];
    for (index, name) in [
        b"first\0".as_slice(),
        b"second\0".as_slice(),
        b"third\0".as_slice(),
    ]
    .iter()
    .enumerate()
    {
        let reflected =
            reflected_instance_method(environment, class, name, INSTANCE_INT_DESCRIPTOR);
        if reflected.is_null() {
            clear_pending_exception(environment);
            return Err(b"Could not reflect a vtable method\0");
        }
        methods[index] =
            ((*(*environment)).v1_2.FromReflectedMethod)(environment, reflected) as usize;
        delete_local_reference(environment, reflected);
    }
    let klass = class_klass_pointer(class);
    for method_offset in (0..MAX_METHOD_SCAN_BYTES).step_by(4) {
        let values = [
            read_u32(methods[0] + method_offset).unwrap_or(u32::MAX),
            read_u32(methods[1] + method_offset).unwrap_or(u32::MAX),
            read_u32(methods[2] + method_offset).unwrap_or(u32::MAX),
        ];
        if values.iter().any(|value| *value > 512) {
            continue;
        }
        for start in (0..2048).step_by(8) {
            if values.iter().enumerate().all(|(index, value)| {
                read_pointer(klass + start + (*value as usize) * 8) == Some(methods[index])
            }) {
                delete_local_reference(environment, class.cast());
                return Ok(method_offset);
            }
        }
    }
    Err(b"Could not dynamically locate Method::_vtable_index\0")
}
unsafe fn locate_vtable_start(
    environment: *mut JNIEnv,
    snapshot: &Snapshot,
) -> Result<usize, &'static [u8]> {
    let class = find_class(environment, VTABLE_PROBE_CLASS_NAME);
    if class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve vtable class\0");
    }
    let names = [
        b"first\0".as_slice(),
        b"second\0".as_slice(),
        b"third\0".as_slice(),
    ];
    let mut methods = [0usize; 3];
    for (index, name) in names.iter().enumerate() {
        let reflected =
            reflected_instance_method(environment, class, name, INSTANCE_INT_DESCRIPTOR);
        if reflected.is_null() {
            clear_pending_exception(environment);
            return Err(b"Could not reflect a vtable method\0");
        }
        methods[index] =
            ((*(*environment)).v1_2.FromReflectedMethod)(environment, reflected) as usize;
        delete_local_reference(environment, reflected);
    }
    let klass = class_klass_pointer(class);
    for start in (0..2048).step_by(8) {
        if methods.iter().all(|method| {
            let index = read_u32(*method + snapshot.method_vtable_index_offset as usize)
                .unwrap_or(u32::MAX) as usize;
            index <= 512 && read_pointer(klass + start + index * 8) == Some(*method)
        }) {
            delete_local_reference(environment, class.cast());
            return Ok(start);
        }
    }
    Err(b"Could not dynamically locate Klass vtable start\0")
}

unsafe fn locate_constant_pool_layout(
    environment: *mut JNIEnv,
    probes: &[ProbeMethod; 5],
) -> Result<(usize, usize, usize, usize, usize, usize), &'static [u8]> {
    let class = find_class(environment, JVM_CLASS_NAME);
    let class_class = find_class(environment, CLASS_CLASS_NAME);
    if class.is_null() || class_class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve ConstantPool probes\0");
    }
    let pool_method = method_id(
        environment,
        class_class,
        GET_CONSTANT_POOL_NAME,
        GET_CONSTANT_POOL_DESCRIPTOR,
    );
    let pool_class = find_class(environment, CONSTANT_POOL_CLASS_NAME);
    let size_method = method_id(environment, pool_class, GET_SIZE_NAME, GET_SIZE_DESCRIPTOR);
    let utf_method = method_id(
        environment,
        pool_class,
        GET_UTF8_AT_NAME,
        GET_UTF8_AT_DESCRIPTOR,
    );
    let pool = call_object_method_a(environment, class.cast(), pool_method, &[]);
    if pool.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not obtain the JVM ConstantPool\0");
    }
    let pool_size = call_int_method(environment, pool, size_method) as usize;
    if pool_size == 0 || pool_size > 65535 {
        return Err(b"Invalid JVM ConstantPool size\0");
    }
    let mut names = [0u16; 5];
    for index in 0..5 {
        for cp_index in 1..pool_size {
            let text = call_object_method_a(
                environment,
                pool,
                utf_method,
                &[jvalue { i: cp_index as i32 }],
            );
            if text.is_null() {
                clear_pending_exception(environment);
                continue;
            }
            let length =
                ((*(*environment)).v1_1.GetStringUTFLength)(environment, text as jni_sys::jstring);
            let chars = ((*(*environment)).v1_1.GetStringUTFChars)(
                environment,
                text as jni_sys::jstring,
                ptr::null_mut(),
            );
            let matches = !chars.is_null()
                && length as usize == PROBE_NAMES[index].len() - 1
                && bytes_equal_raw(chars.cast(), PROBE_NAMES[index], length as usize);
            if !chars.is_null() {
                ((*(*environment)).v1_1.ReleaseStringUTFChars)(
                    environment,
                    text as jni_sys::jstring,
                    chars,
                );
            }
            delete_local_reference(environment, text);
            if matches {
                names[index] = cp_index as u16;
                break;
            }
        }
        if names[index] == 0 {
            return Err(b"Could not locate probe method names in ConstantPool\0");
        }
    }
    let mut result = None;
    let const_method = probes[0].const_method;
    for const_offset in (0..32).step_by(8) {
        let pool_address = read_pointer(const_method + const_offset).unwrap_or(0);
        if !plausible_pointer(pool_address) {
            continue;
        }
        for length_offset in (0..128).step_by(4) {
            if read_u32(pool_address + length_offset) != Some(pool_size as u32) {
                continue;
            }
            for entries_offset in (0..128).step_by(8) {
                let symbol = read_pointer(pool_address + entries_offset + names[0] as usize * 8)
                    .unwrap_or(0);
                if !plausible_pointer(symbol) {
                    continue;
                }
                if let Some((symbol_length, symbol_body)) =
                    locate_symbol_layout(symbol, PROBE_NAMES[0])
                {
                    let mut all = true;
                    for index in 1..5 {
                        let other =
                            read_pointer(pool_address + entries_offset + names[index] as usize * 8)
                                .unwrap_or(0);
                        if !plausible_pointer(other)
                            || !symbol_matches(
                                other,
                                PROBE_NAMES[index],
                                symbol_length,
                                symbol_body,
                            )
                        {
                            all = false;
                            break;
                        }
                    }
                    if all {
                        result = Some((
                            const_offset,
                            length_offset,
                            entries_offset,
                            symbol_length,
                            symbol_body,
                        ));
                        break;
                    }
                }
            }
            if result.is_some() {
                break;
            }
        }
        if result.is_some() {
            break;
        }
    }
    let (const_offset, length_offset, entries_offset, symbol_length, symbol_body) =
        result.ok_or(&b"Could not dynamically locate ConstantPool and Symbol layouts\0"[..])?;
    let mut name_index_offset = usize::MAX;
    for offset in (0..64).step_by(2) {
        if (0..5).all(|index| read_u16(probes[index].const_method + offset) == Some(names[index])) {
            name_index_offset = offset;
            break;
        }
    }
    if name_index_offset == usize::MAX {
        return Err(b"Could not locate ConstMethod name index\0");
    }
    delete_local_reference(environment, pool);
    delete_local_reference(environment, class.cast());
    delete_local_reference(environment, class_class.cast());
    delete_local_reference(environment, pool_class.cast());
    Ok((
        const_offset,
        length_offset,
        entries_offset,
        symbol_length,
        symbol_body,
        name_index_offset,
    ))
}
unsafe fn bytes_equal_raw(address: *const u8, expected: &[u8], length: usize) -> bool {
    for index in 0..length {
        if *address.add(index) != expected[index] {
            return false;
        }
    }
    true
}
unsafe fn locate_symbol_layout(symbol: usize, expected: &[u8]) -> Option<(usize, usize)> {
    for body in 2..16 {
        if !bytes_equal(symbol + body, expected) {
            continue;
        }
        for length in (0..body).step_by(2) {
            if read_u16(symbol + length) == Some((expected.len() - 1) as u16) {
                return Some((length, body));
            }
        }
    }
    None
}
unsafe fn symbol_matches(
    symbol: usize,
    expected: &[u8],
    length_offset: usize,
    body_offset: usize,
) -> bool {
    read_u16(symbol + length_offset) == Some((expected.len() - 1) as u16)
        && bytes_equal(symbol + body_offset, expected)
}

unsafe fn locate_methods_offset(environment: *mut JNIEnv) -> Result<usize, &'static [u8]> {
    let class = find_class(environment, METHOD_TABLE_PROBE_CLASS_NAME);
    if class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve method table probe\0");
    }
    let names = [
        b"first\0".as_slice(),
        b"second\0".as_slice(),
        b"third\0".as_slice(),
    ];
    let mut methods = [0usize; 4];
    for index in 0..3 {
        let reflected =
            reflected_static_method(environment, class, names[index], STATIC_VOID_DESCRIPTOR);
        if reflected.is_null() {
            clear_pending_exception(environment);
            return Err(b"Could not reflect method table probe\0");
        }
        methods[index] =
            ((*(*environment)).v1_2.FromReflectedMethod)(environment, reflected) as usize;
        delete_local_reference(environment, reflected);
    }
    methods[3] = method_id(environment, class, INITIALIZER_NAME, INITIALIZER_DESCRIPTOR) as usize;
    let klass = class_klass_pointer(class);
    for offset in (0..MAX_KLASS_SCAN_BYTES).step_by(8) {
        let array = read_pointer(klass + offset).unwrap_or(0);
        if !plausible_pointer(array) || read_u32(array) != Some(4) {
            continue;
        }
        if methods
            .iter()
            .all(|method| contains_pointer(array + 8, 4, *method))
        {
            delete_local_reference(environment, class.cast());
            return Ok(offset);
        }
    }
    Err(b"Could not dynamically locate InstanceKlass methods\0")
}
unsafe fn contains_pointer(address: usize, count: usize, value: usize) -> bool {
    (0..count).any(|index| read_pointer(address + index * 8) == Some(value))
}
unsafe fn locate_fields_layout(
    environment: *mut JNIEnv,
) -> Result<(usize, usize, usize, usize, usize, usize, usize), &'static [u8]> {
    let class = find_class(environment, FIELD_TABLE_PROBE_CLASS_NAME);
    let unsafe_class = find_class(environment, UNSAFE_CLASS_NAME);
    if class.is_null() || unsafe_class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve field table probe\0");
    }
    let unsafe_object = static_object_field(
        environment,
        unsafe_class,
        FIELD_THE_UNSAFE_NAME,
        UNSAFE_DESCRIPTOR,
    );
    let field_class = find_class(environment, FIELD_DESCRIPTOR);
    let modifiers_method = method_id(environment, field_class, b"getModifiers\0", b"()I\0");
    let mut expectations = [FieldExpectation {
        access_flags: 0,
        offset: 0,
    }; 3];
    for index in 0..3 {
        let field = field_id(
            environment,
            class,
            FIELD_NAMES[index],
            FIELD_DESCRIPTORS[index],
        );
        let reflected =
            ((*(*environment)).v1_2.ToReflectedField)(environment, class, field, index == 0);
        let unsafe_class_object =
            ((*(*environment)).v1_1.GetObjectClass)(environment, unsafe_object);
        let offset_method = if index == 0 {
            method_id(
                environment,
                unsafe_class_object,
                STATIC_FIELD_OFFSET_NAME,
                STATIC_FIELD_OFFSET_DESCRIPTOR,
            )
        } else {
            method_id(
                environment,
                unsafe_class_object,
                OBJECT_FIELD_OFFSET_NAME,
                OBJECT_FIELD_OFFSET_DESCRIPTOR,
            )
        };
        let offset = call_long_method_a(
            environment,
            unsafe_object,
            offset_method,
            &[jvalue_object(reflected)],
        ) as u32;
        let modifiers = call_int_method(environment, reflected, modifiers_method) as u16;
        expectations[index] = FieldExpectation {
            access_flags: modifiers,
            offset,
        };
        delete_local_reference(environment, reflected);
        delete_local_reference(environment, unsafe_class_object.cast());
    }
    let klass = class_klass_pointer(class);
    for array_offset in (0..MAX_KLASS_SCAN_BYTES).step_by(8) {
        let array = read_pointer(klass + array_offset).unwrap_or(0);
        if !plausible_pointer(array)
            || read_u32(array) != Some((expectations.len() * FIELD_SLOTS) as u32)
        {
            continue;
        }
        if let Some(layout) = match_field_table(array, &expectations) {
            delete_local_reference(environment, class.cast());
            delete_local_reference(environment, unsafe_class.cast());
            delete_local_reference(environment, unsafe_object);
            delete_local_reference(environment, field_class.cast());
            return Ok((
                array_offset,
                layout.0,
                layout.1,
                layout.2,
                layout.3,
                layout.4,
                FIELD_SLOTS,
            ));
        }
    }
    Err(b"Could not dynamically locate InstanceKlass fields\0")
}
unsafe fn match_field_table(
    array: usize,
    expectations: &[FieldExpectation; 3],
) -> Option<(usize, usize, usize, usize, usize)> {
    for access_offset in 0..FIELD_SLOTS {
        for low_offset in 0..FIELD_SLOTS {
            for high_offset in 0..FIELD_SLOTS {
                if low_offset == high_offset
                    || access_offset == low_offset
                    || access_offset == high_offset
                {
                    continue;
                }
                let mut matched = 0;
                for slot_index in 0..3 {
                    let base = array + 8 + slot_index * FIELD_SLOTS * 2;
                    let access = read_u16(base + access_offset * 2).unwrap_or(u16::MAX);
                    let low = read_u16(base + low_offset * 2).unwrap_or(u16::MAX) as u32;
                    let high = read_u16(base + high_offset * 2).unwrap_or(u16::MAX) as u32;
                    let packed = low | high << 16;
                    if expectations.iter().any(|expected| {
                        expected.access_flags == access && packed >> 2 == expected.offset
                    }) {
                        matched += 1;
                    }
                }
                if matched == 3 {
                    return Some((access_offset, 1, 2, low_offset, high_offset));
                }
            }
        }
    }
    None
}
unsafe fn locate_java_fields_count_offset(
    environment: *mut JNIEnv,
) -> Result<usize, &'static [u8]> {
    let classes = [
        find_class(environment, FIELD_TABLE_PROBE_CLASS_NAME),
        find_class(environment, FIELD_COUNT_EXTENDED_CLASS_NAME),
        find_class(environment, FIELD_COUNT_STATIC_CLASS_NAME),
    ];
    if classes.iter().any(|class| class.is_null()) {
        clear_pending_exception(environment);
        return Err(b"Could not resolve field count probes\0");
    }
    let klasses = [
        class_klass_pointer(classes[0]),
        class_klass_pointer(classes[1]),
        class_klass_pointer(classes[2]),
    ];
    for offset in (0..MAX_KLASS_SCAN_BYTES).step_by(2) {
        if read_u16(klasses[0] + offset) == Some(3)
            && read_u16(klasses[1] + offset) == Some(4)
            && read_u16(klasses[2] + offset) == Some(3)
        {
            return Ok(offset);
        }
    }
    Err(b"Could not dynamically locate InstanceKlass java fields count\0")
}
unsafe fn find_pointer_offset(
    base: usize,
    target: usize,
    scan: usize,
) -> Result<usize, &'static [u8]> {
    if !plausible_pointer(base) || !plausible_pointer(target) {
        return Err(b"Could not inspect a HotSpot metadata object\0");
    }
    let mut found = usize::MAX;
    for offset in (0..scan).step_by(8) {
        if read_pointer(base + offset) == Some(target) {
            if found != usize::MAX {
                return Err(b"HotSpot layout candidate was ambiguous\0");
            }
            found = offset;
        }
    }
    if found == usize::MAX {
        Err(b"Could not locate a HotSpot metadata pointer\0")
    } else {
        Ok(found)
    }
}
fn copy_error(destination: &mut [u8; MAX_ERROR_LENGTH], source: &'static [u8]) -> usize {
    let length = source.len().saturating_sub(1).min(destination.len() - 1);
    destination[..length].copy_from_slice(&source[..length]);
    destination[length] = 0;
    length
}

#[cfg(test)]
mod tests {
    use super::derive_encoding;
    #[test]
    fn compressed_oop_encoding_is_derived() {
        let encoding = derive_encoding(0x1000_0000, 0x1000_1000, 0x2000, 0x2200).unwrap();
        assert_eq!(encoding.shift, 3);
        assert_eq!(encoding.base, 0x0fff_f000);
        assert!(encoding.compressed);
    }
}
