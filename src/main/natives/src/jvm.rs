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
const KLASS_SUBKLASS_ROOT_ONE_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$KlassSubklassRootOne\0";
const KLASS_SUBKLASS_CHILD_ONE_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$KlassSubklassChildOne\0";
const KLASS_SUBKLASS_ROOT_TWO_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$KlassSubklassRootTwo\0";
const KLASS_SUBKLASS_CHILD_TWO_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$KlassSubklassChildTwo\0";
const KLASS_SIBLING_ROOT_ONE_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$KlassSiblingRootOne\0";
const KLASS_SIBLING_CHILD_ONE_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$KlassSiblingChildOne\0";
const KLASS_SIBLING_CHILD_TWO_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$KlassSiblingChildTwo\0";
const KLASS_SIBLING_ROOT_TWO_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$KlassSiblingRootTwo\0";
const KLASS_SIBLING_CHILD_THREE_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$KlassSiblingChildThree\0";
const KLASS_SIBLING_CHILD_FOUR_CLASS_NAME: &[u8] =
    b"io/github/seraphina/jnct/api/JVM$KlassSiblingChildFour\0";
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
const FIELD_CLASS_NAME: &[u8] = b"java/lang/reflect/Field\0";

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
const RESOLVED_METHOD_PROBE_NAME: &[u8] = b"resolvedMethod\0";
const RESOLVED_METHOD_PROBE_DESCRIPTOR: &[u8] = b"(Ljava/lang/reflect/Method;)Ljava/lang/Object;\0";
const METHOD_SLOT_NAME: &[u8] = b"methodSlot\0";
const METHOD_SLOT_DESCRIPTOR: &[u8] = b"(Ljava/lang/reflect/Method;)I\0";
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
const MAX_FIELD_SLOTS_PER_ENTRY: usize = 32;
const MAX_POINTER_CANDIDATES: usize = 16;

#[derive(Clone, Copy)]
struct PointerCandidates {
    offsets: [usize; MAX_POINTER_CANDIDATES],
    count: usize,
}
#[derive(Clone, Copy)]
struct ProbeMethod {
    const_method: usize,
    code_length: usize,
    method_slot: u16,
}
#[derive(Clone, Copy)]
struct FieldExpectation {
    access_flags: u16,
    name_index: u16,
    signature_index: u16,
    offset: u32,
}

struct MethodLayout {
    resolved_method_vmtarget_offset: usize,
    method_const_method_offset: usize,
    const_method_code_offset: usize,
    const_method_code_size_offset: usize,
    first_method: usize,
    probes: [ProbeMethod; 5],
}

struct MethodsLayout {
    klass_offset: usize,
    array: usize,
    methods: [usize; 4],
}

struct FieldLayout {
    klass_offset: usize,
    elements_offset: usize,
    access_flags_offset: usize,
    name_index_offset: usize,
    signature_index_offset: usize,
    low_packed_offset: usize,
    high_packed_offset: usize,
    slots: usize,
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
    metadata_array_length_offset: i64,
    metadata_array_elements_offset: i64,
    metadata_u2_array_elements_offset: i64,
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
            metadata_array_length_offset: 0,
            metadata_array_elements_offset: 0,
            metadata_u2_array_elements_offset: 0,
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

pub unsafe fn create_instance(environment: *mut JNIEnv, arguments: jobject) -> jobject {
    if arguments.is_null() {
        return ptr::null_mut();
    }
    let instance = ((*(*environment)).v1_1.GetObjectArrayElement)(environment, arguments.cast(), 0);
    if instance.is_null() {
        clear_pending_exception(environment);
        return ptr::null_mut();
    }
    let class = find_class(environment, JVM_CLASS_NAME);
    if class.is_null() {
        clear_pending_exception(environment);
        delete_local_reference(environment, instance);
        return ptr::null_mut();
    }
    // JVM.INSTANCE is constructed on the initializing thread before this
    // command is sent. Reuse that shell instead of resolving or allocating
    // JVM here, both of which can wait for class initialization to finish.
    let mut snapshot = Snapshot::empty();
    let mut error = [0u8; MAX_ERROR_LENGTH];
    let error_length = match probe(environment, class, &mut snapshot) {
        Ok(()) => 0,
        Err(message) => {
            log::error(format_args!(
                "createJVM: probe failed: {}",
                core::str::from_utf8(message).unwrap_or("unknown native probe error"),
            ));
            copy_error(&mut error, message)
        }
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

unsafe fn probe(
    environment: *mut JNIEnv,
    jvm_class: jclass,
    snapshot: &mut Snapshot,
) -> Result<(), &'static [u8]> {
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
    let compressed_oops = match object_scale {
        4 => true,
        8 => false,
        _ => return Err(b"Unsupported Java object reference representation\0"),
    };
    if object_base <= 0 || short_base <= 0 {
        return Err(b"Could not resolve Java array base offsets\0");
    }
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
    log::info("createJVM: dynamic Java offsets resolved");

    let reference_slot = ((*(*environment)).v1_1.AllocObject)(environment, reference_slot_class);
    if reference_slot.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not allocate the reference probe\0");
    }
    let object_narrow = reference_encoding(
        environment,
        reference_slot,
        reference_slot_class,
        snapshot.reference_slot_value_offset as usize,
        object_class,
        compressed_oops,
    )?;
    let string_narrow = reference_encoding(
        environment,
        reference_slot,
        reference_slot_class,
        snapshot.reference_slot_value_offset as usize,
        string_class,
        compressed_oops,
    )?;
    let (class_klass_offset, mirror_offset, object_klass, string_klass) =
        find_class_klass_layout(object_class, string_class, object_narrow, string_narrow)?;
    snapshot.class_klass_offset = class_klass_offset as i64;
    snapshot.klass_java_mirror_offset = mirror_offset as i64;
    let object_address = jni_object_address(object_class)
        .ok_or(&b"Could not resolve java.lang.Object class mirror\0"[..])?;
    let string_address = jni_object_address(string_class)
        .ok_or(&b"Could not resolve java.lang.String class mirror\0"[..])?;
    let oop_encoding = derive_encoding(
        object_address,
        string_address,
        object_narrow,
        string_narrow,
        compressed_oops,
    )?;
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
        jni_object_address(object_instance)
            .ok_or(&b"Could not resolve object header probe\0"[..])?,
        jni_object_address(string_instance)
            .ok_or(&b"Could not resolve string header probe\0"[..])?,
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
        jni_object_address(object_array).ok_or(&b"Could not resolve Object[] array probe\0"[..])?,
        jni_object_address(short_array).ok_or(&b"Could not resolve short[] array probe\0"[..])?,
        jni_object_address(int_array).ok_or(&b"Could not resolve int[] array probe\0"[..])?,
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
        jni_object_address(object_array).ok_or(&b"Could not resolve Object[] array probe\0"[..])?,
        jni_object_address(string_value).ok_or(&b"Could not resolve array element probe\0"[..])?,
        oop_encoding,
        snapshot.java_array_length_offset as usize,
    )? as i64;

    log::info("createJVM: array layout resolved");
    let method_layout = locate_method_layout(environment, jvm_class)?;
    snapshot.resolved_method_name_vmtarget_offset =
        method_layout.resolved_method_vmtarget_offset as i64;
    snapshot.method_const_method_offset = method_layout.method_const_method_offset as i64;
    snapshot.const_method_code_offset = method_layout.const_method_code_offset as i64;
    snapshot.const_method_code_size_offset = method_layout.const_method_code_size_offset as i64;
    let (method_idnum_offset, original_method_idnum_offset) =
        locate_const_method_id_offsets(&method_layout.probes)?;
    snapshot.const_method_method_idnum_offset = method_idnum_offset as i64;
    snapshot.const_method_original_method_idnum_offset = original_method_idnum_offset as i64;
    log::info("createJVM: const method layout resolved");
    snapshot.method_vtable_index_offset =
        locate_vtable_layout(environment, class_klass_offset)? as i64;
    log::info("createJVM: vtable index layout resolved");
    snapshot.vtable_start_offset =
        locate_vtable_start(environment, snapshot, class_klass_offset)? as i64;
    snapshot.metadata_address_prefix = (method_layout.first_method >> 32) as i64;
    let constant_pool_class = find_class(environment, JVM_CLASS_NAME);
    if constant_pool_class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve the JVM constant-pool probe class\0");
    }
    let pool_layout =
        locate_constant_pool_layout(environment, constant_pool_class, &method_layout.probes)?;
    snapshot.const_method_constants_offset = pool_layout.0 as i64;
    snapshot.constant_pool_length_offset = pool_layout.1 as i64;
    snapshot.constant_pool_entries_offset = pool_layout.2 as i64;
    snapshot.symbol_length_offset = pool_layout.3 as i64;
    snapshot.symbol_body_offset = pool_layout.4 as i64;
    snapshot.const_method_name_index_offset = pool_layout.5 as i64;

    log::info("createJVM: method layout resolved");
    let (klass_subklass_offset, klass_next_sibling_offset) =
        locate_klass_hierarchy_layout(environment, class_klass_offset)?;
    snapshot.klass_subklass_offset = klass_subklass_offset as i64;
    snapshot.klass_next_sibling_offset = klass_next_sibling_offset as i64;
    let methods_layout = locate_methods_offset(environment, class_klass_offset)?;
    let (metadata_length_offset, metadata_elements_offset) = locate_metadata_array_layout(
        methods_layout.array,
        methods_layout.methods.len(),
        &methods_layout.methods,
    )?;
    snapshot.metadata_array_length_offset = metadata_length_offset as i64;
    snapshot.metadata_array_elements_offset = metadata_elements_offset as i64;
    snapshot.methods_offset = methods_layout.klass_offset as i64;
    log::info("createJVM: methods table resolved");
    let field_layout =
        locate_fields_layout(environment, metadata_length_offset, class_klass_offset)?;
    snapshot.metadata_u2_array_elements_offset = field_layout.elements_offset as i64;
    snapshot.fields_offset = field_layout.klass_offset as i64;
    snapshot.field_access_flags_offset = field_layout.access_flags_offset as i32;
    snapshot.field_name_index_offset = field_layout.name_index_offset as i32;
    snapshot.field_signature_index_offset = field_layout.signature_index_offset as i32;
    snapshot.field_low_packed_offset = field_layout.low_packed_offset as i32;
    snapshot.field_high_packed_offset = field_layout.high_packed_offset as i32;
    snapshot.field_slots = field_layout.slots as i32;
    log::info("createJVM: fields table resolved");
    let java_fields_count_offset =
        locate_java_fields_count_offset(environment, class_klass_offset)?;
    snapshot.java_fields_count_offset = java_fields_count_offset as i64;
    log::info("createJVM: field count resolved");
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
    sl!(
        b"metadataArrayLengthOffset\0",
        snapshot.metadata_array_length_offset
    );
    sl!(
        b"metadataArrayElementsOffset\0",
        snapshot.metadata_array_elements_offset
    );
    sl!(
        b"metadataU2ArrayElementsOffset\0",
        snapshot.metadata_u2_array_elements_offset
    );
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
    sl!(
        b"arrayLengthOffset\0",
        snapshot.metadata_array_length_offset
    );
    sl!(
        b"arrayElementsOffset\0",
        snapshot.metadata_array_elements_offset
    );
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
    let args = if args.is_empty() {
        ptr::null()
    } else {
        args.as_ptr()
    };
    ((*(*environment)).v1_1.CallIntMethodA)(environment, object, method, args)
}
unsafe fn call_static_int_method_a(
    environment: *mut JNIEnv,
    class: jclass,
    method: jmethodID,
    args: &[jvalue],
) -> jint {
    let args = if args.is_empty() {
        ptr::null()
    } else {
        args.as_ptr()
    };
    ((*(*environment)).v1_1.CallStaticIntMethodA)(environment, class, method, args)
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
unsafe fn jni_object_address(object: jobject) -> Option<usize> {
    if object.is_null() {
        return None;
    }
    let address = read_pointer(object as usize)?;
    if plausible_pointer(address) {
        Some(address)
    } else {
        None
    }
}
fn plausible_pointer(value: usize) -> bool {
    value >= 0x10000 && value <= 0x0000_7fff_ffff_ffff && value & 7 == 0
}

unsafe fn class_klass_pointer(class: jclass, class_klass_offset: usize) -> usize {
    jni_object_address(class)
        .and_then(|address| read_pointer(address + class_klass_offset))
        .unwrap_or(0)
}
unsafe fn find_class_klass_layout(
    object_class: jclass,
    string_class: jclass,
    _object_narrow: usize,
    _string_narrow: usize,
) -> Result<(usize, usize, usize, usize), &'static [u8]> {
    let object_address = jni_object_address(object_class)
        .ok_or(&b"Could not resolve java.lang.Object class mirror\0"[..])?;
    let string_address = jni_object_address(string_class)
        .ok_or(&b"Could not resolve java.lang.String class mirror\0"[..])?;
    let mut found = None;
    for class_offset in (0..128).step_by(8) {
        let object_klass = read_pointer(object_address + class_offset).unwrap_or(0);
        let string_klass = read_pointer(string_address + class_offset).unwrap_or(0);
        if !plausible_pointer(object_klass)
            || !plausible_pointer(string_klass)
            || object_klass == string_klass
        {
            continue;
        }
        for mirror_offset in (0..512).step_by(8) {
            let object_handle = read_pointer(object_klass + mirror_offset).unwrap_or(0);
            let string_handle = read_pointer(string_klass + mirror_offset).unwrap_or(0);
            if !plausible_pointer(object_handle) || !plausible_pointer(string_handle) {
                continue;
            }
            // Klass::_java_mirror is an OopHandle.  The handle itself stores
            // a full-width oop even when ordinary Java references use
            // compressed oops, so compare it with the resolved JNI object.
            if read_pointer(object_handle) != Some(object_address)
                || read_pointer(string_handle) != Some(string_address)
            {
                continue;
            }
            if found.is_some() {
                return Err(b"Klass and java mirror layout is ambiguous\0");
            }
            found = Some((class_offset, mirror_offset, object_klass, string_klass));
        }
    }
    found.ok_or(&b"Could not dynamically locate Klass and java mirror offsets\0"[..])
}
unsafe fn reference_encoding(
    environment: *mut JNIEnv,
    slot: jobject,
    slot_class: jclass,
    offset: usize,
    value: jobject,
    compressed: bool,
) -> Result<usize, &'static [u8]> {
    let field = field_id(environment, slot_class, FIELD_VALUE_NAME, OBJECT_DESCRIPTOR);
    if field.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve reference probe field\0");
    }
    ((*(*environment)).v1_1.SetObjectField)(environment, slot, field, value);
    let result = jni_object_address(slot)
        .and_then(|address| {
            if compressed {
                read_u32(address + offset).map(|value| value as usize)
            } else {
                read_pointer(address + offset)
            }
        })
        .ok_or(&b"Could not read a Java object reference\0"[..]);
    ((*(*environment)).v1_1.SetObjectField)(environment, slot, field, ptr::null_mut());
    result
}
fn derive_encoding(
    raw_a: usize,
    raw_b: usize,
    narrow_a: usize,
    narrow_b: usize,
    compressed: bool,
) -> Result<Encoding, &'static [u8]> {
    if raw_a == 0 || raw_b == 0 || narrow_a == narrow_b {
        return Err(b"Could not derive compressed OOP encoding\0");
    }
    if !compressed {
        if raw_a != narrow_a || raw_b != narrow_b {
            return Err(b"Could not verify uncompressed OOP encoding\0");
        }
        return Ok(Encoding {
            base: 0,
            shift: 0,
            compressed: false,
        });
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
            if let Ok(encoding) = derive_encoding(
                object_klass,
                string_klass,
                object_narrow,
                string_narrow,
                true,
            ) {
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
    let array_address =
        jni_object_address(array.cast()).ok_or(&b"Could not resolve short[] array probe\0"[..])?;
    let value: jshort = 0x3579;
    ((*(*environment)).v1_1.SetShortArrayRegion)(environment, array, 0, 1, &value);
    for offset in (length_offset + 4..64).step_by(2) {
        if read_u16(array_address + offset) == Some(value as u16) {
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
unsafe fn reflected_method_pointer(environment: *mut JNIEnv, reflected: jobject) -> Option<usize> {
    let jmethod_id = ((*(*environment)).v1_2.FromReflectedMethod)(environment, reflected) as usize;
    if !plausible_pointer(jmethod_id) {
        return None;
    }
    read_pointer(jmethod_id).filter(|method| plausible_pointer(*method))
}
unsafe fn locate_method_layout(
    environment: *mut JNIEnv,
    class: jclass,
) -> Result<MethodLayout, &'static [u8]> {
    let probe_class = find_class(environment, NATIVE_PROBE_CLASS_NAME);
    if class.is_null() || probe_class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve method layout probes\0");
    }
    let resolved_method = static_method_id(
        environment,
        probe_class,
        RESOLVED_METHOD_PROBE_NAME,
        RESOLVED_METHOD_PROBE_DESCRIPTOR,
    );
    let method_slot_method = static_method_id(
        environment,
        probe_class,
        METHOD_SLOT_NAME,
        METHOD_SLOT_DESCRIPTOR,
    );
    if resolved_method.is_null() || method_slot_method.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve native method layout helpers\0");
    }
    let mut probes = [ProbeMethod {
        const_method: 0,
        code_length: 0,
        method_slot: 0,
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
        let method_slot = call_static_int_method_a(
            environment,
            probe_class,
            method_slot_method,
            &[jvalue_object(reflected)],
        );
        if has_pending_exception(environment) {
            clear_pending_exception(environment);
            delete_local_reference(environment, reflected);
            return Err(b"Could not read java.lang.reflect.Method.slot\0");
        }
        if method_slot < 0 || method_slot > u16::MAX as i32 {
            delete_local_reference(environment, reflected);
            return Err(b"Invalid java.lang.reflect.Method.slot value\0");
        }
        let resolved = call_static_object_method_a(
            environment,
            probe_class,
            resolved_method,
            &[jvalue_object(reflected)],
        );
        if resolved.is_null() || has_pending_exception(environment) {
            clear_pending_exception(environment);
            delete_local_reference(environment, reflected);
            return Err(b"Could not resolve MethodHandle.internalMemberName\0");
        }
        let method_pointer = reflected_method_pointer(environment, reflected)
            .ok_or(&b"Could not resolve a HotSpot Method pointer\0"[..])?;
        if first_method == 0 {
            first_method = method_pointer;
        }
        if resolved.is_null() || !plausible_pointer(method_pointer) {
            return Err(b"Could not resolve a HotSpot Method pointer\0");
        }
        let resolved_address = jni_object_address(resolved)
            .ok_or(&b"Could not resolve java.lang.invoke.MemberName.method\0"[..])?;
        let vmtarget = find_pointer_offset(resolved_address, method_pointer, 128)?;
        if resolved_offset == usize::MAX {
            resolved_offset = vmtarget;
        } else if resolved_offset != vmtarget {
            return Err(b"Inconsistent ResolvedMethodName layout\0");
        }
        let (const_method, current_const_offset, current_code_offset) =
            locate_const_method(method_pointer, PROBE_BYTECODES[index])?;
        if const_offset == usize::MAX {
            const_offset = current_const_offset;
        } else if const_offset != current_const_offset {
            return Err(b"Inconsistent Method/ConstMethod layout\0");
        }
        if code_offset == usize::MAX {
            code_offset = current_code_offset;
        } else if code_offset != current_code_offset {
            return Err(b"Inconsistent ConstMethod code layout\0");
        }
        probes[index] = ProbeMethod {
            const_method,
            code_length: PROBE_BYTECODES[index].len(),
            method_slot: method_slot as u16,
        };
        delete_local_reference(environment, reflected);
        delete_local_reference(environment, resolved);
    }
    let code_size_offset = find_common_u16_offset(&probes, |probe| probe.code_length as u16)?;
    delete_local_reference(environment, probe_class.cast());
    Ok(MethodLayout {
        resolved_method_vmtarget_offset: resolved_offset,
        method_const_method_offset: const_offset,
        const_method_code_offset: code_offset,
        const_method_code_size_offset: code_size_offset,
        first_method,
        probes,
    })
}
unsafe fn locate_const_method_id_offsets(
    probes: &[ProbeMethod; 5],
) -> Result<(usize, usize), &'static [u8]> {
    let mut candidates = [usize::MAX; 8];
    let mut candidate_count = 0usize;
    for offset in (0..64).step_by(2) {
        if probes
            .iter()
            .all(|probe| read_u16(probe.const_method + offset) == Some(probe.method_slot))
        {
            if candidate_count < candidates.len() {
                candidates[candidate_count] = offset;
            }
            candidate_count += 1;
        }
    }
    if candidate_count < 2 {
        return Err(b"Could not locate ConstMethod idnum fields\0");
    }
    Ok((candidates[0], candidates[1]))
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
    class_klass_offset: usize,
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
        methods[index] = reflected_method_pointer(environment, reflected).unwrap_or(0);
        delete_local_reference(environment, reflected);
    }
    let klass = class_klass_pointer(class, class_klass_offset);
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
    class_klass_offset: usize,
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
        methods[index] = reflected_method_pointer(environment, reflected).unwrap_or(0);
        delete_local_reference(environment, reflected);
    }
    let klass = class_klass_pointer(class, class_klass_offset);
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
    class: jclass,
    probes: &[ProbeMethod; 5],
) -> Result<(usize, usize, usize, usize, usize, usize), &'static [u8]> {
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
        if !bytes_equal(symbol + body, &expected[..expected.len() - 1]) {
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
        && bytes_equal(symbol + body_offset, &expected[..expected.len() - 1])
}

unsafe fn locate_methods_offset(
    environment: *mut JNIEnv,
    class_klass_offset: usize,
) -> Result<MethodsLayout, &'static [u8]> {
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
            delete_local_reference(environment, class.cast());
            return Err(b"Could not reflect method table probe\0");
        }
        methods[index] = reflected_method_pointer(environment, reflected).unwrap_or(0);
        delete_local_reference(environment, reflected);
    }
    let initializer_id = method_id(environment, class, INITIALIZER_NAME, INITIALIZER_DESCRIPTOR);
    methods[3] = if initializer_id.is_null() {
        0
    } else {
        read_pointer(initializer_id as usize).unwrap_or(0)
    };
    if methods.iter().any(|method| *method == 0) {
        delete_local_reference(environment, class.cast());
        return Err(b"Could not resolve method table probe pointers\0");
    }
    let klass = class_klass_pointer(class, class_klass_offset);
    for klass_offset in (0..MAX_KLASS_SCAN_BYTES).step_by(8) {
        let array = read_pointer(klass + klass_offset).unwrap_or(0);
        if !plausible_pointer(array) {
            continue;
        }
        for length_offset in (0..32).step_by(4) {
            if read_u32(array + length_offset) != Some(methods.len() as u32) {
                continue;
            }
            for elements_offset in (0..32).step_by(8) {
                if elements_offset < length_offset + size_of::<u32>()
                    || !methods.iter().all(|method| {
                        contains_pointer(array + elements_offset, methods.len(), *method)
                    })
                {
                    continue;
                }
                // The length is four, so all four expected pointers must be the
                // complete array contents.  This rejects a coincidental match
                // in a neighbouring metadata object.
                if (0..methods.len()).all(|index| {
                    read_pointer(array + elements_offset + index * size_of::<usize>())
                        .is_some_and(|value| methods.contains(&value))
                }) {
                    delete_local_reference(environment, class.cast());
                    return Ok(MethodsLayout {
                        klass_offset,
                        array,
                        methods,
                    });
                }
            }
        }
    }
    delete_local_reference(environment, class.cast());
    Err(b"Could not dynamically locate InstanceKlass methods\0")
}
unsafe fn contains_pointer(address: usize, count: usize, value: usize) -> bool {
    (0..count).any(|index| read_pointer(address + index * size_of::<usize>()) == Some(value))
}
unsafe fn locate_metadata_array_layout(
    array: usize,
    expected_count: usize,
    expected_elements: &[usize; 4],
) -> Result<(usize, usize), &'static [u8]> {
    let mut found = None;
    for length_offset in (0..32).step_by(4) {
        if read_u32(array + length_offset) != Some(expected_count as u32) {
            continue;
        }
        for elements_offset in (0..32).step_by(8) {
            if elements_offset < length_offset + size_of::<u32>() {
                continue;
            }
            // HotSpot orders Method entries by method id, not by the order in
            // which the reflection probes were collected.  Match the complete
            // array as a permutation of the expected pointers instead of
            // assuming a reflection order that is not part of the VM contract.
            let matches = expected_elements.iter().all(|expected| {
                (0..expected_count).any(|index| {
                    read_pointer(array + elements_offset + index * size_of::<usize>())
                        == Some(*expected)
                })
            }) && (0..expected_count).all(|index| {
                read_pointer(array + elements_offset + index * size_of::<usize>())
                    .is_some_and(|value| expected_elements.contains(&value))
            });
            if !matches {
                continue;
            }
            if found.is_some() {
                return Err(b"Ambiguous HotSpot metadata array layout\0");
            }
            found = Some((length_offset, elements_offset));
        }
    }
    found.ok_or(&b"Could not locate HotSpot metadata array layout\0"[..])
}
unsafe fn locate_fields_layout(
    environment: *mut JNIEnv,
    metadata_length_offset: usize,
    class_klass_offset: usize,
) -> Result<FieldLayout, &'static [u8]> {
    let class = find_class(environment, FIELD_TABLE_PROBE_CLASS_NAME);
    let unsafe_class = find_class(environment, UNSAFE_CLASS_NAME);
    let field_class = find_class(environment, FIELD_CLASS_NAME);
    if class.is_null() || unsafe_class.is_null() || field_class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve field table probe\0");
    }
    let unsafe_object = static_object_field(
        environment,
        unsafe_class,
        FIELD_THE_UNSAFE_NAME,
        UNSAFE_DESCRIPTOR,
    );
    if unsafe_object.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not obtain Unsafe for field table probe\0");
    }
    let modifiers_method = method_id(environment, field_class, b"getModifiers\0", b"()I\0");
    let mut expectations = [FieldExpectation {
        access_flags: 0,
        name_index: 0,
        signature_index: 0,
        offset: 0,
    }; 3];
    for index in 0..expectations.len() {
        let field = if index == 0 {
            static_field_id(
                environment,
                class,
                FIELD_NAMES[index],
                FIELD_DESCRIPTORS[index],
            )
        } else {
            field_id(
                environment,
                class,
                FIELD_NAMES[index],
                FIELD_DESCRIPTORS[index],
            )
        };
        if field.is_null() {
            clear_pending_exception(environment);
            return Err(b"Could not resolve field layout probe field\0");
        }
        let reflected =
            ((*(*environment)).v1_2.ToReflectedField)(environment, class, field, index == 0);
        if reflected.is_null() {
            clear_pending_exception(environment);
            return Err(b"Could not reflect field layout probe field\0");
        }
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
        if has_pending_exception(environment) {
            clear_pending_exception(environment);
            return Err(b"Could not inspect field layout probe field\0");
        }
        expectations[index] = FieldExpectation {
            access_flags: modifiers,
            name_index: locate_constant_pool_utf8_index(environment, class, FIELD_NAMES[index])?,
            signature_index: locate_constant_pool_utf8_index(
                environment,
                class,
                FIELD_DESCRIPTORS[index],
            )?,
            offset,
        };
        delete_local_reference(environment, reflected);
        delete_local_reference(environment, unsafe_class_object.cast());
    }
    let klass = class_klass_pointer(class, class_klass_offset);
    for klass_offset in (0..MAX_KLASS_SCAN_BYTES).step_by(8) {
        let array = read_pointer(klass + klass_offset).unwrap_or(0);
        if !plausible_pointer(array) {
            continue;
        }
        if let Some(layout) = match_field_table(array, metadata_length_offset, &expectations) {
            delete_local_reference(environment, class.cast());
            delete_local_reference(environment, unsafe_class.cast());
            delete_local_reference(environment, unsafe_object);
            delete_local_reference(environment, field_class.cast());
            return Ok(FieldLayout {
                klass_offset,
                elements_offset: layout.0,
                access_flags_offset: layout.1,
                name_index_offset: layout.2,
                signature_index_offset: layout.3,
                low_packed_offset: layout.4,
                high_packed_offset: layout.5,
                slots: layout.6,
            });
        }
    }
    delete_local_reference(environment, class.cast());
    delete_local_reference(environment, unsafe_class.cast());
    delete_local_reference(environment, unsafe_object);
    delete_local_reference(environment, field_class.cast());
    Err(b"Could not dynamically locate InstanceKlass fields\0")
}
unsafe fn locate_constant_pool_utf8_index(
    environment: *mut JNIEnv,
    class: jclass,
    expected: &[u8],
) -> Result<u16, &'static [u8]> {
    let class_class = find_class(environment, CLASS_CLASS_NAME);
    let pool_class = find_class(environment, CONSTANT_POOL_CLASS_NAME);
    if class_class.is_null() || pool_class.is_null() {
        clear_pending_exception(environment);
        return Err(b"Could not resolve ConstantPool field probe\0");
    }
    let pool_method = method_id(
        environment,
        class_class,
        GET_CONSTANT_POOL_NAME,
        GET_CONSTANT_POOL_DESCRIPTOR,
    );
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
        return Err(b"Could not obtain field probe ConstantPool\0");
    }
    let pool_size = call_int_method(environment, pool, size_method) as usize;
    if pool_size == 0 || pool_size > u16::MAX as usize {
        delete_local_reference(environment, pool);
        delete_local_reference(environment, class_class.cast());
        delete_local_reference(environment, pool_class.cast());
        return Err(b"Invalid field probe ConstantPool size\0");
    }
    let mut result = None;
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
            && length as usize == expected.len().saturating_sub(1)
            && bytes_equal_raw(chars.cast(), expected, length as usize);
        if !chars.is_null() {
            ((*(*environment)).v1_1.ReleaseStringUTFChars)(
                environment,
                text as jni_sys::jstring,
                chars,
            );
        }
        delete_local_reference(environment, text);
        if matches {
            result = Some(cp_index as u16);
            break;
        }
    }
    delete_local_reference(environment, pool);
    delete_local_reference(environment, class_class.cast());
    delete_local_reference(environment, pool_class.cast());
    result.ok_or(&b"Could not locate field ConstantPool UTF8 entry\0"[..])
}
unsafe fn match_field_table(
    array: usize,
    metadata_length_offset: usize,
    expectations: &[FieldExpectation; 3],
) -> Option<(usize, usize, usize, usize, usize, usize, usize)> {
    if !plausible_pointer(array) {
        return None;
    }
    let mut candidate = None;
    // Array<T> places its inline data according to T's alignment.  The
    // methods array is Array<Method*> and therefore has a different element
    // offset from InstanceKlass::_fields, which is Array<u2>.  Probe the
    // small header area instead of reusing the pointer-array offset.
    for metadata_elements_offset in (0..32).step_by(2) {
        if metadata_elements_offset < metadata_length_offset + size_of::<u32>() {
            continue;
        }
        for slots in 1..=MAX_FIELD_SLOTS_PER_ENTRY {
            if read_u32(array + metadata_length_offset) != Some((expectations.len() * slots) as u32)
            {
                continue;
            }
            // Use the dynamically found name indices to associate physical
            // rows with our expectations before matching columns whose values
            // may not be unique (for example, private access flags).
            for name_offset in 0..slots {
                let mut row_expectation = [usize::MAX; 3];
                let mut used = 0u8;
                let mut rows_valid = true;
                for row in 0..expectations.len() {
                    let base = array + metadata_elements_offset + row * slots * size_of::<u16>();
                    let name = read_u16(base + name_offset * size_of::<u16>()).unwrap_or(u16::MAX);
                    let mut matched = usize::MAX;
                    for (index, expected) in expectations.iter().enumerate() {
                        if used & (1 << index) == 0 && expected.name_index == name {
                            matched = index;
                            break;
                        }
                    }
                    if matched == usize::MAX {
                        rows_valid = false;
                        break;
                    }
                    used |= 1 << matched;
                    row_expectation[row] = matched;
                }
                if !rows_valid || used != (1 << expectations.len()) - 1 {
                    continue;
                }
                let name_column = locate_field_column(
                    array,
                    metadata_elements_offset,
                    slots,
                    &row_expectation,
                    expectations,
                    FieldColumn::Name,
                );
                if name_column != Some(name_offset) {
                    continue;
                }
                let signature_candidate = locate_field_column(
                    array,
                    metadata_elements_offset,
                    slots,
                    &row_expectation,
                    expectations,
                    FieldColumn::Signature,
                );
                let access_candidate = locate_field_column(
                    array,
                    metadata_elements_offset,
                    slots,
                    &row_expectation,
                    expectations,
                    FieldColumn::Access,
                );
                let low_candidate = locate_field_column(
                    array,
                    metadata_elements_offset,
                    slots,
                    &row_expectation,
                    expectations,
                    FieldColumn::PackedLow,
                );
                let (Some(signature_offset), Some(access_offset), Some(low_offset)) =
                    (signature_candidate, access_candidate, low_candidate)
                else {
                    continue;
                };
                // The packed high word is commonly zero for ordinary Java fields,
                // and the initval column is also commonly zero.  Resolve it as
                // the word immediately following the uniquely identified low
                // word instead of treating those two zero-filled columns as
                // independent candidates.
                let high_offset = low_offset + 1;
                let high_matches = high_offset < slots
                    && (0..expectations.len()).all(|row| {
                        matches_field_column(
                            array,
                            metadata_elements_offset,
                            slots,
                            row_expectation[row],
                            expectations,
                            row,
                            high_offset,
                            FieldColumn::PackedHigh,
                        )
                    });
                if !high_matches {
                    continue;
                }
                let offsets = [
                    access_offset,
                    name_offset,
                    signature_offset,
                    low_offset,
                    high_offset,
                ];
                let mut distinct = true;
                for left in 0..offsets.len() {
                    for right in (left + 1)..offsets.len() {
                        if offsets[left] == offsets[right] {
                            distinct = false;
                        }
                    }
                }
                if !distinct {
                    continue;
                }
                let layout = (
                    metadata_elements_offset,
                    access_offset,
                    name_offset,
                    signature_offset,
                    low_offset,
                    high_offset,
                    slots,
                );
                if candidate.is_some() {
                    return None;
                }
                candidate = Some(layout);
            }
        }
    }
    candidate
}

#[derive(Clone, Copy)]
enum FieldColumn {
    Access,
    Name,
    Signature,
    PackedLow,
    PackedHigh,
}
unsafe fn locate_field_column(
    array: usize,
    metadata_elements_offset: usize,
    slots: usize,
    row_expectation: &[usize; 3],
    expectations: &[FieldExpectation; 3],
    column: FieldColumn,
) -> Option<usize> {
    let mut candidate = None;
    for offset in 0..slots {
        let matches = (0..expectations.len()).all(|row| {
            matches_field_column(
                array,
                metadata_elements_offset,
                slots,
                row_expectation[row],
                expectations,
                row,
                offset,
                column,
            )
        });
        if !matches {
            continue;
        }
        if candidate.is_some() {
            return None;
        }
        candidate = Some(offset);
    }
    candidate
}

unsafe fn matches_field_column(
    array: usize,
    metadata_elements_offset: usize,
    slots: usize,
    expected_index: usize,
    expectations: &[FieldExpectation; 3],
    row: usize,
    offset: usize,
    column: FieldColumn,
) -> bool {
    if row >= expectations.len() || expected_index >= expectations.len() || offset >= slots {
        return false;
    }
    let base = array + metadata_elements_offset + row * slots * size_of::<u16>();
    let value = read_u16(base + offset * size_of::<u16>()).unwrap_or(u16::MAX);
    let expected = &expectations[expected_index];
    match column {
        FieldColumn::Access => value == expected.access_flags,
        FieldColumn::Name => value == expected.name_index,
        FieldColumn::Signature => value == expected.signature_index,
        FieldColumn::PackedLow => {
            let packed_offset = ((expected.offset as u64) << 2) | 1;
            (packed_offset & u64::from(u16::MAX)) as u16 == value
        }
        FieldColumn::PackedHigh => {
            let packed_offset = ((expected.offset as u64) << 2) | 1;
            (packed_offset >> 16) as u16 == value
        }
    }
}
unsafe fn locate_java_fields_count_offset(
    environment: *mut JNIEnv,
    class_klass_offset: usize,
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
        class_klass_pointer(classes[0], class_klass_offset),
        class_klass_pointer(classes[1], class_klass_offset),
        class_klass_pointer(classes[2], class_klass_offset),
    ];
    let klasses_are_plausible = klasses.iter().all(|klass| plausible_pointer(*klass));
    if !klasses_are_plausible {
        return Err(b"Could not resolve field count probe Klass pointers\0");
    }
    // _java_fields_count is an InstanceKlass-local field.  Keep this
    // structural probe inside the first metadata page; scanning beyond the
    // object can cross an unmapped page and turn a failed probe into a native
    // access violation.
    for offset in (0..1024).step_by(2) {
        if read_u16(klasses[0] + offset) == Some(3)
            && read_u16(klasses[1] + offset) == Some(4)
            && read_u16(klasses[2] + offset) == Some(3)
        {
            return Ok(offset);
        }
    }
    Err(b"Could not dynamically locate InstanceKlass java fields count\0")
}
unsafe fn locate_klass_hierarchy_layout(
    environment: *mut JNIEnv,
    class_klass_offset: usize,
) -> Result<(usize, usize), &'static [u8]> {
    let classes = [
        find_class(environment, KLASS_SUBKLASS_ROOT_ONE_CLASS_NAME),
        find_class(environment, KLASS_SUBKLASS_CHILD_ONE_CLASS_NAME),
        find_class(environment, KLASS_SUBKLASS_ROOT_TWO_CLASS_NAME),
        find_class(environment, KLASS_SUBKLASS_CHILD_TWO_CLASS_NAME),
        find_class(environment, KLASS_SIBLING_ROOT_ONE_CLASS_NAME),
        find_class(environment, KLASS_SIBLING_CHILD_ONE_CLASS_NAME),
        find_class(environment, KLASS_SIBLING_CHILD_TWO_CLASS_NAME),
        find_class(environment, KLASS_SIBLING_ROOT_TWO_CLASS_NAME),
        find_class(environment, KLASS_SIBLING_CHILD_THREE_CLASS_NAME),
        find_class(environment, KLASS_SIBLING_CHILD_FOUR_CLASS_NAME),
    ];
    if classes.iter().any(|class| class.is_null()) {
        clear_pending_exception(environment);
        return Err(b"Could not resolve Klass hierarchy probes\0");
    }

    let klasses = classes.map(|class| class_klass_pointer(class, class_klass_offset));
    let subklass_one = collect_pointer_offsets(klasses[0], klasses[1], MAX_KLASS_SCAN_BYTES)?;
    let subklass_two = collect_pointer_offsets(klasses[2], klasses[3], MAX_KLASS_SCAN_BYTES)?;

    // HotSpot prepends a newly linked child to its superclass' sibling list,
    // so the second child points at the first one.  The final children make
    // the null-tail check independent of class loading order.
    let sibling_one = collect_pointer_offsets(klasses[6], klasses[5], MAX_KLASS_SCAN_BYTES)?;
    let sibling_two = collect_pointer_offsets(klasses[9], klasses[8], MAX_KLASS_SCAN_BYTES)?;
    let next_sibling_offset = select_pointer_offset_with_null_tails(
        &sibling_one,
        &sibling_two,
        klasses[5],
        klasses[8],
        b"Could not uniquely locate Klass::_next_sibling\0",
    )?;

    // In Klass, _subklass and _next_sibling are adjacent pointer fields.  Use
    // the independently identified sibling field to disambiguate incidental
    // references to the child classes elsewhere in the metadata object.
    let subklass_offset = next_sibling_offset
        .checked_sub(size_of::<usize>())
        .filter(|offset| {
            contains_pointer_offset(&subklass_one, *offset)
                && contains_pointer_offset(&subklass_two, *offset)
                && read_pointer(klasses[1] + *offset) == Some(0)
                && read_pointer(klasses[3] + *offset) == Some(0)
        })
        .ok_or(&b"Could not uniquely locate Klass::_subklass\0"[..])?;

    log::info("createJVM: klass hierarchy resolved");
    Ok((subklass_offset, next_sibling_offset))
}

unsafe fn collect_pointer_offsets(
    base: usize,
    target: usize,
    scan: usize,
) -> Result<PointerCandidates, &'static [u8]> {
    if !plausible_pointer(base) || !plausible_pointer(target) {
        return Err(b"Could not inspect a HotSpot metadata object\0");
    }
    let mut candidates = PointerCandidates {
        offsets: [0; MAX_POINTER_CANDIDATES],
        count: 0,
    };
    for offset in (0..scan).step_by(8) {
        if read_pointer(base + offset) != Some(target) {
            continue;
        }
        if candidates.count == MAX_POINTER_CANDIDATES {
            return Err(b"Too many HotSpot metadata pointer candidates\0");
        }
        candidates.offsets[candidates.count] = offset;
        candidates.count += 1;
    }
    Ok(candidates)
}

fn contains_pointer_offset(candidates: &PointerCandidates, offset: usize) -> bool {
    candidates.offsets[..candidates.count].contains(&offset)
}

unsafe fn find_pointer_offset(
    base: usize,
    target: usize,
    scan: usize,
) -> Result<usize, &'static [u8]> {
    let candidates = collect_pointer_offsets(base, target, scan)?;
    match candidates.count {
        0 => Err(b"Could not locate a HotSpot metadata pointer\0"),
        1 => Ok(candidates.offsets[0]),
        _ => Err(b"HotSpot layout candidate was ambiguous\0"),
    }
}

unsafe fn select_pointer_offset_with_null_tails(
    first: &PointerCandidates,
    second: &PointerCandidates,
    tail_one: usize,
    tail_two: usize,
    error: &'static [u8],
) -> Result<usize, &'static [u8]> {
    let mut found = None;
    for index in 0..first.count {
        let offset = first.offsets[index];
        if !contains_pointer_offset(second, offset)
            || read_pointer(tail_one + offset) != Some(0)
            || read_pointer(tail_two + offset) != Some(0)
        {
            continue;
        }
        if found.is_some_and(|existing| existing != offset) {
            return Err(error);
        }
        found = Some(offset);
    }
    found.ok_or(error)
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
        let encoding = derive_encoding(0x1000_0000, 0x1000_1000, 0x2000, 0x2200, true).unwrap();
        assert_eq!(encoding.shift, 3);
        assert_eq!(encoding.base, 0x0fff_f000);
        assert!(encoding.compressed);
    }
}
