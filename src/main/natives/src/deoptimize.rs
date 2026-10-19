use core::ffi::c_void;
use core::ptr;

use jni_sys::{JNI_OK, JNIEnv, JavaVM, jint, jobject};

const JVMTI_VERSION_1_2: jint = 0x3001_0200;
const JVMTI_ERROR_NONE: jint = 0;
const JVMTI_GET_CAPABILITIES_SLOT: usize = 88;
const JVMTI_GET_POTENTIAL_CAPABILITIES_SLOT: usize = 139;
const JVMTI_ADD_CAPABILITIES_SLOT: usize = 141;
const JVMTI_RETRANSFORM_CLASSES_SLOT: usize = 151;
const JVMTI_CAN_RETRANSFORM_CLASSES_WORD: usize = 1;
const JVMTI_CAN_RETRANSFORM_CLASSES_BIT: u32 = 1 << 5;

type AddCapabilities = unsafe extern "system" fn(*mut c_void, *const JvmtiCapabilities) -> jint;
type GetCapabilities = unsafe extern "system" fn(*mut c_void, *mut JvmtiCapabilities) -> jint;
type RetransformClasses = unsafe extern "system" fn(*mut c_void, jint, *const jobject) -> jint;

#[repr(C)]
struct JvmtiCapabilities {
    words: [u32; 4],
}

pub unsafe fn deoptimize_classes(environment: *mut JNIEnv, arguments: jobject) -> jobject {
    if arguments.is_null() {
        return result(environment, b"deoptimizeClasses requires a Class[]\0");
    }

    let classes = ((*(*environment)).v1_1.GetObjectArrayElement)(environment, arguments, 0);
    if classes.is_null() {
        return result(environment, b"deoptimizeClasses requires a Class[]\0");
    }

    let class_count = ((*(*environment)).v1_1.GetArrayLength)(environment, classes);
    if class_count <= 0 {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        return result(environment, b"deoptimizeClasses requires at least one class\0");
    }

    let mut java_vm: *mut JavaVM = ptr::null_mut();
    if ((*(*environment)).v1_2.GetJavaVM)(environment, &mut java_vm) != JNI_OK
        || java_vm.is_null()
    {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        return result(environment, b"deoptimizeClasses could not resolve JavaVM\0");
    }

    let mut jvmti_environment: *mut c_void = ptr::null_mut();
    if ((*(*java_vm)).v1_2.GetEnv)(
        java_vm,
        &mut jvmti_environment,
        JVMTI_VERSION_1_2,
    ) != JNI_OK
        || jvmti_environment.is_null()
    {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        return result(environment, b"deoptimizeClasses could not resolve JVMTI\0");
    }

    let functions = ptr::read_unaligned(jvmti_environment.cast::<*const usize>());
    if functions.is_null() {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        return result(environment, b"deoptimizeClasses received an invalid JVMTI table\0");
    }

    let get_potential_capabilities =
        function::<GetCapabilities>(functions, JVMTI_GET_POTENTIAL_CAPABILITIES_SLOT);
    let mut potential = JvmtiCapabilities { words: [0; 4] };
    let potential_result = get_potential_capabilities(jvmti_environment, &mut potential);
    if potential_result != JVMTI_ERROR_NONE {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        return error_result(
            environment,
            b"deoptimizeClasses could not read potential capabilities\0",
            potential_result,
        );
    }

    let retransform_bit =
        potential.words[JVMTI_CAN_RETRANSFORM_CLASSES_WORD] & JVMTI_CAN_RETRANSFORM_CLASSES_BIT;
    if retransform_bit == 0 {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        return result(environment, b"deoptimizeClasses cannot retransform classes\0");
    }

    let add_capabilities = function::<AddCapabilities>(functions, JVMTI_ADD_CAPABILITIES_SLOT);
    let capabilities = JvmtiCapabilities {
        words: [0, retransform_bit, 0, 0],
    };
    let add_result = add_capabilities(jvmti_environment, &capabilities);
    if add_result != JVMTI_ERROR_NONE {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        return error_result(
            environment,
            b"deoptimizeClasses could not add retransform capability\0",
            add_result,
        );
    }

    let get_capabilities = function::<GetCapabilities>(functions, JVMTI_GET_CAPABILITIES_SLOT);
    let mut active = JvmtiCapabilities { words: [0; 4] };
    let active_result = get_capabilities(jvmti_environment, &mut active);
    if active_result != JVMTI_ERROR_NONE
        || active.words[JVMTI_CAN_RETRANSFORM_CLASSES_WORD] & JVMTI_CAN_RETRANSFORM_CLASSES_BIT == 0
    {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
        return error_result(
            environment,
            b"deoptimizeClasses did not receive retransform capability\0",
            active_result,
        );
    }

    let retransform_classes =
        function::<RetransformClasses>(functions, JVMTI_RETRANSFORM_CLASSES_SLOT);
    for index in 0..class_count {
        let class = ((*(*environment)).v1_1.GetObjectArrayElement)(environment, classes, index);
        if class.is_null() {
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
            return result(environment, b"deoptimizeClasses found a null class\0");
        }
        let retransform_result = retransform_classes(jvmti_environment, 1, &class);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, class);
        if retransform_result != JVMTI_ERROR_NONE {
            ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
            return error_result(
                environment,
                b"deoptimizeClasses could not retransform classes\0",
                retransform_result,
            );
        }
    }

    ((*(*environment)).v1_1.DeleteLocalRef)(environment, classes);
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