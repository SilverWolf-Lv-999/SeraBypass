#![no_std]
#![cfg_attr(not(test), no_main)]

#[cfg(test)]
extern crate alloc;

pub mod log;
mod peer_jvmti;

use core::ffi::{c_char, c_void};
use jni_sys::{JNI_ERR, JNI_OK, JNI_VERSION_1_8, JNIEnv, JNINativeMethod, JavaVM, jint, jsize};

const DLL_PROCESS_ATTACH: u32 = 1;
const JVM_DLL_NAME: &[u16] = &[
    b'j' as u16,
    b'v' as u16,
    b'm' as u16,
    b'.' as u16,
    b'd' as u16,
    b'l' as u16,
    b'l' as u16,
    0,
];
const JNI_GET_CREATED_JAVA_VMS: &[u8] = b"JNI_GetCreatedJavaVMs\0";

#[link(name = "kernel32")]
unsafe extern "system" {
    fn DisableThreadLibraryCalls(module: *mut c_void) -> i32;
    fn GetModuleHandleW(module_name: *const u16) -> *mut c_void;
    fn GetProcAddress(module: *mut c_void, procedure_name: *const u8) -> *mut c_void;
}
type JniGetCreatedJavaVms = unsafe extern "system" fn(*mut *mut JavaVM, jsize, *mut jsize) -> jint;

#[cfg(not(test))]
#[panic_handler]
fn panic(_: &core::panic::PanicInfo<'_>) -> ! {
    loop {}
}

#[unsafe(no_mangle)]
pub extern "system" fn DllMain(module: *mut c_void, reason: u32, _: *mut c_void) -> i32 {
    if reason == DLL_PROCESS_ATTACH {
        unsafe {
            DisableThreadLibraryCalls(module);
        }
    }
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn sera_bypass_register_natives(
    class_name: *const c_char,
    method_name: *const c_char,
    method_descriptor: *const c_char,
) -> jint {
    if class_name.is_null() || method_name.is_null() || method_descriptor.is_null() {
        return JNI_ERR;
    }

    unsafe {
        let environment = match get_current_environment() {
            Some(environment) => environment,
            None => return JNI_ERR,
        };
        let class = ((*(*environment)).v1_1.FindClass)(environment, class_name);
        if class.is_null() {
            return JNI_ERR;
        }

        let native_method = JNINativeMethod {
            name: method_name.cast_mut(),
            signature: method_descriptor.cast_mut(),
            fnPtr: native_say_hello as *const () as *mut c_void,
        };
        ((*(*environment)).v1_1.RegisterNatives)(environment, class, &native_method, 1)
    }
}

unsafe fn get_current_environment() -> Option<*mut JNIEnv> {
    let jvm_module = unsafe { GetModuleHandleW(JVM_DLL_NAME.as_ptr()) };
    if jvm_module.is_null() {
        return None;
    }

    let get_created_java_vms_address =
        unsafe { GetProcAddress(jvm_module, JNI_GET_CREATED_JAVA_VMS.as_ptr()) };
    if get_created_java_vms_address.is_null() {
        return None;
    }
    let get_created_java_vms: JniGetCreatedJavaVms =
        unsafe { core::mem::transmute(get_created_java_vms_address) };

    let mut java_vm: *mut JavaVM = core::ptr::null_mut();
    let mut java_vm_count: jsize = 0;
    if unsafe { get_created_java_vms(&mut java_vm, 1, &mut java_vm_count) } != JNI_OK
        || java_vm.is_null()
        || java_vm_count != 1
    {
        return None;
    }

    let mut environment: *mut c_void = core::ptr::null_mut();
    if unsafe { ((*(*java_vm)).v1_2.GetEnv)(java_vm, &mut environment, JNI_VERSION_1_8) } != JNI_OK
        || environment.is_null()
    {
        return None;
    }

    Some(environment.cast::<JNIEnv>())
}

extern "system" fn native_say_hello(_: *mut c_void, _: *mut c_void) {
    printf!("hello");
}
