use core::ffi::c_void;
use core::ptr;

use jni_sys::{JNI_OK, JNIEnv, JavaVM, jint, jobject};

const JVMTI_VERSION_1_2: jint = 0x3001_0200;
const JVMTI_ERROR_NONE: jint = 0;
const JVMTI_REDEFINE_CLASSES_SLOT: usize = 86;
const JVMTI_GET_CAPABILITIES_SLOT: usize = 88;
const JVMTI_GET_POTENTIAL_CAPABILITIES_SLOT: usize = 139;
const JVMTI_ADD_CAPABILITIES_SLOT: usize = 141;
const JVMTI_CAN_REDEFINE_CLASSES_WORD: usize = 0;
const JVMTI_CAN_REDEFINE_CLASSES_BIT: u32 = 1 << 9;
const JNI_ABORT: jint = 2;

type AddCapabilities = unsafe extern "system" fn(*mut c_void, *const JvmtiCapabilities) -> jint;
type GetCapabilities = unsafe extern "system" fn(*mut c_void, *mut JvmtiCapabilities) -> jint;
type RedefineClasses =
    unsafe extern "system" fn(*mut c_void, jint, *const JvmtiClassDefinition) -> jint;

#[repr(C)]
struct JvmtiCapabilities {
    words: [u32; 4],
}

#[repr(C)]
struct JvmtiClassDefinition {
    klass: jobject,
    class_byte_count: jint,
    class_bytes: *const u8,
}

pub unsafe fn deoptimize_classes(environment: *mut JNIEnv, arguments: jobject) -> jobject {
    if arguments.is_null() {
        return result(
            environment,
            b"deoptimizeClasses requires Class[] and byte[][]\0",
        );
    }

    let classes = ((*(*environment)).v1_1.GetObjectArrayElement)(environment, arguments, 0);
    let byte_arrays = ((*(*environment)).v1_1.GetObjectArrayElement)(environment, arguments, 1);
    if classes.is_null() || byte_arrays.is_null() {
        if !classes.is_null() {
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        }
        if !byte_arrays.is_null() {
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
        }
        return result(
            environment,
            b"deoptimizeClasses requires Class[] and byte[][]\0",
        );
    }

    let class_count = ((*(*environment)).v1_1.GetArrayLength)(environment, classes);
    let bytes_count = ((*(*environment)).v1_1.GetArrayLength)(environment, byte_arrays);
    if class_count <= 0 || class_count != bytes_count {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
        return result(
            environment,
            b"deoptimizeClasses received mismatched class data\0",
        );
    }

    let mut java_vm: *mut JavaVM = ptr::null_mut();
    if ((*(*environment)).v1_2.GetJavaVM)(environment, &mut java_vm) != JNI_OK || java_vm.is_null()
    {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
        return result(environment, b"deoptimizeClasses could not resolve JavaVM\0");
    }

    let mut jvmti_environment: *mut c_void = ptr::null_mut();
    if ((*(*java_vm)).v1_2.GetEnv)(java_vm, &mut jvmti_environment, JVMTI_VERSION_1_2) != JNI_OK
        || jvmti_environment.is_null()
    {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
        return result(environment, b"deoptimizeClasses could not resolve JVMTI\0");
    }

    let functions = ptr::read_unaligned(jvmti_environment.cast::<*const usize>());
    if functions.is_null() {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
        return result(
            environment,
            b"deoptimizeClasses received an invalid JVMTI table\0",
        );
    }

    let get_potential_capabilities =
        function::<GetCapabilities>(functions, JVMTI_GET_POTENTIAL_CAPABILITIES_SLOT);
    let mut potential = JvmtiCapabilities { words: [0; 4] };
    let potential_result = get_potential_capabilities(jvmti_environment, &mut potential);
    if potential_result != JVMTI_ERROR_NONE {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
        return error_result(
            environment,
            b"deoptimizeClasses could not read potential capabilities\0",
            potential_result,
        );
    }

    let redefine_bit =
        potential.words[JVMTI_CAN_REDEFINE_CLASSES_WORD] & JVMTI_CAN_REDEFINE_CLASSES_BIT;
    if redefine_bit == 0 {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
        return result(environment, b"deoptimizeClasses cannot redefine classes\0");
    }

    let add_capabilities = function::<AddCapabilities>(functions, JVMTI_ADD_CAPABILITIES_SLOT);
    let capabilities = JvmtiCapabilities {
        words: [redefine_bit, 0, 0, 0],
    };
    let add_result = add_capabilities(jvmti_environment, &capabilities);
    if add_result != JVMTI_ERROR_NONE {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
        return error_result(
            environment,
            b"deoptimizeClasses could not add redefine capability\0",
            add_result,
        );
    }

    let get_capabilities = function::<GetCapabilities>(functions, JVMTI_GET_CAPABILITIES_SLOT);
    let mut active = JvmtiCapabilities { words: [0; 4] };
    let active_result = get_capabilities(jvmti_environment, &mut active);
    if active_result != JVMTI_ERROR_NONE
        || active.words[JVMTI_CAN_REDEFINE_CLASSES_WORD] & JVMTI_CAN_REDEFINE_CLASSES_BIT == 0
    {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
        return error_result(
            environment,
            b"deoptimizeClasses did not receive redefine capability\0",
            active_result,
        );
    }

    let redefine_classes = function::<RedefineClasses>(functions, JVMTI_REDEFINE_CLASSES_SLOT);
    for index in 0..class_count {
        let class = ((*(*environment)).v1_1.GetObjectArrayElement)(environment, classes, index);
        let class_bytes =
            ((*(*environment)).v1_1.GetObjectArrayElement)(environment, byte_arrays, index);
        if class.is_null() || class_bytes.is_null() {
            if !class.is_null() {
                ((*(*environment)).v1_1.DeleteLocalRef)(environment, class);
            }
            if !class_bytes.is_null() {
                ((*(*environment)).v1_1.DeleteLocalRef)(environment, class_bytes);
            }
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
            return result(environment, b"deoptimizeClasses found null class data\0");
        }

        let class_byte_count = ((*(*environment)).v1_1.GetArrayLength)(environment, class_bytes);
        if class_byte_count <= 0 {
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, class);
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, class_bytes);
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
            return result(
                environment,
                b"deoptimizeClasses received empty class bytes\0",
            );
        }

        let elements = ((*(*environment)).v1_1.GetByteArrayElements)(
            environment,
            class_bytes,
            ptr::null_mut(),
        );
        if elements.is_null() {
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, class);
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, class_bytes);
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
            return result(
                environment,
                b"deoptimizeClasses could not access class bytes\0",
            );
        }

        let definition = JvmtiClassDefinition {
            klass: class,
            class_byte_count,
            class_bytes: elements.cast::<u8>(),
        };
        let redefine_result = redefine_classes(jvmti_environment, 1, &definition);
        ((*(*environment)).v1_1.ReleaseByteArrayElements)(
            environment,
            class_bytes,
            elements,
            JNI_ABORT,
        );
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, class);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, class_bytes);
        if redefine_result != JVMTI_ERROR_NONE {
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
            return error_result(
                environment,
                b"deoptimizeClasses could not redefine classes\0",
                redefine_result,
            );
        }
    }

    ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
    ((*(*environment)).v1_1.DeleteLocalRef)(environment, byte_arrays);
    result(environment, b"deoptimized\0")
}

unsafe fn function<T: Copy>(functions: *const usize, slot: usize) -> T {
    unsafe { core::mem::transmute_copy(&*functions.add(slot)) }
}

unsafe fn result(environment: *mut JNIEnv, message: &[u8]) -> jobject {
    ((*(*environment)).v1_1.NewStringUTF)(environment, message.as_ptr().cast()).cast()
}

unsafe fn error_result(environment: *mut JNIEnv, message: &[u8], code: jint) -> jobject {
    crate::log::error(format_args!(
        "{} ({})",
        core::str::from_utf8(message).unwrap_or("deoptimizeClasses failed"),
        code
    ));
    result(environment, message)
}
