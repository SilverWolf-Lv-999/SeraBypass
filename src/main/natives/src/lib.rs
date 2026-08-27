#![no_std]
#![cfg_attr(not(test), no_main)]

#[cfg(test)]
extern crate alloc;

pub mod log;
mod peer_jvmti;
mod klass;

use core::ffi::{c_char, c_void};
use core::ptr;
use jni_sys::{
    JNI_ERR, JNI_OK, JNI_VERSION_1_8, JNIEnv, JNINativeMethod, JavaVM, jclass, jfieldID, jint,
    jobject, jsize, jstring,
};

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
const JNCT_CLASS_NAME: &[u8] = b"io/github/seraphina/jnct/JNCT\0";
const JNCT_CMD_FIELD_NAME: &[u8] = b"cmd\0";
const JNCT_CMD_FIELD_DESCRIPTOR: &[u8] = b"Ljava/lang/String;\0";
const JNCT_ARGS_FIELD_NAME: &[u8] = b"args\0";
const JNCT_ARGS_FIELD_DESCRIPTOR: &[u8] = b"[Ljava/lang/Object;\0";
const JNCT_RESULT_FIELD_NAME: &[u8] = b"result\0";
const JNCT_RESULT_FIELD_DESCRIPTOR: &[u8] = b"Ljava/lang/Object;\0";
const HELLO_COMMAND: &[u8] = b"hello";
const HELLO_RESULT: &[u8] = b"Hello\0";
const UNKNOWN_COMMAND_RESULT: &[u8] = b"Unknown JNCT command\0";
const WORKER_SLEEP_MILLIS: u32 = 1;

type JniGetCreatedJavaVms = unsafe extern "system" fn(*mut *mut JavaVM, jsize, *mut jsize) -> jint;
type ThreadStartRoutine = unsafe extern "system" fn(*mut c_void) -> u32;

struct JnctFields {
    class: jclass,
    cmd: jfieldID,
    args: jfieldID,
    result: jfieldID,
}

#[link(name = "kernel32")]
unsafe extern "system" {
    fn CloseHandle(object: *mut c_void) -> i32;
    fn CreateThread(
        thread_attributes: *mut c_void,
        stack_size: usize,
        start_address: Option<ThreadStartRoutine>,
        parameter: *mut c_void,
        creation_flags: u32,
        thread_id: *mut u32,
    ) -> *mut c_void;
    fn DisableThreadLibraryCalls(module: *mut c_void) -> i32;
    fn GetModuleHandleW(module_name: *const u16) -> *mut c_void;
    fn GetProcAddress(module: *mut c_void, procedure_name: *const u8) -> *mut c_void;
    fn Sleep(milliseconds: u32);
}

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

            let worker_thread = CreateThread(
                ptr::null_mut(),
                0,
                Some(jnct_worker),
                ptr::null_mut(),
                0,
                ptr::null_mut(),
            );
            if !worker_thread.is_null() {
                CloseHandle(worker_thread);
            }
        }
    }
    1
}

unsafe extern "system" fn jnct_worker(_: *mut c_void) -> u32 {
    let java_vm = loop {
        if let Some(java_vm) = get_current_java_vm() {
            break java_vm;
        }
        unsafe {
            Sleep(WORKER_SLEEP_MILLIS);
        }
    };

    let environment = match unsafe { attach_current_thread(java_vm) } {
        Some(environment) => environment,
        None => return 0,
    };

    let fields = loop {
        if let Some(fields) = unsafe { resolve_jnct_fields(environment) } {
            break fields;
        }
        unsafe {
            Sleep(WORKER_SLEEP_MILLIS);
        }
    };

    loop {
        unsafe {
            process_jnct_command(environment, &fields);
            Sleep(WORKER_SLEEP_MILLIS);
        }
    }
}

unsafe fn process_jnct_command(environment: *mut JNIEnv, fields: &JnctFields) {
    let command =
        ((*(*environment)).v1_1.GetStaticObjectField)(environment, fields.class, fields.cmd);
    if command.is_null() {
        clear_pending_exception(environment);
        return;
    }

    let arguments =
        ((*(*environment)).v1_1.GetStaticObjectField)(environment, fields.class, fields.args);
    if has_pending_exception(environment) {
        clear_pending_exception(environment);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, command);
        return;
    }

    let result = dispatch_jnct_command(environment, command.cast(), arguments);
    if !result.is_null() {
        ((*(*environment)).v1_1.SetStaticObjectField)(
            environment,
            fields.class,
            fields.result,
            result,
        );
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, result);
    }

    if !arguments.is_null() {
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, arguments);
    }
    ((*(*environment)).v1_1.DeleteLocalRef)(environment, command);

    wait_for_command_clear(environment, fields);
}

unsafe fn dispatch_jnct_command(
    environment: *mut JNIEnv,
    command: jstring,
    _arguments: jobject,
) -> jobject {
    let command_characters =
        ((*(*environment)).v1_1.GetStringUTFChars)(environment, command, ptr::null_mut());
    if command_characters.is_null() {
        clear_pending_exception(environment);
        return ptr::null_mut();
    }

    let command_length = ((*(*environment)).v1_1.GetStringUTFLength)(environment, command);
    let is_hello =
        core::slice::from_raw_parts(command_characters.cast::<u8>(), command_length as usize)
            == HELLO_COMMAND;
    ((*(*environment)).v1_1.ReleaseStringUTFChars)(environment, command, command_characters);

    if is_hello {
        printf!("Hello");
        return ((*(*environment)).v1_1.NewStringUTF)(environment, HELLO_RESULT.as_ptr().cast())
            .cast();
    }

    log::error("Unknown JNCT command");
    ((*(*environment)).v1_1.NewStringUTF)(environment, UNKNOWN_COMMAND_RESULT.as_ptr().cast())
        .cast()
}

unsafe fn wait_for_command_clear(environment: *mut JNIEnv, fields: &JnctFields) {
    loop {
        let command =
            ((*(*environment)).v1_1.GetStaticObjectField)(environment, fields.class, fields.cmd);
        if has_pending_exception(environment) {
            clear_pending_exception(environment);
            return;
        }
        if command.is_null() {
            return;
        }

        ((*(*environment)).v1_1.DeleteLocalRef)(environment, command);
        Sleep(WORKER_SLEEP_MILLIS);
    }
}

unsafe fn resolve_jnct_fields(environment: *mut JNIEnv) -> Option<JnctFields> {
    let local_class =
        ((*(*environment)).v1_1.FindClass)(environment, JNCT_CLASS_NAME.as_ptr().cast());
    if local_class.is_null() {
        clear_pending_exception(environment);
        return None;
    }

    let cmd = ((*(*environment)).v1_1.GetStaticFieldID)(
        environment,
        local_class,
        JNCT_CMD_FIELD_NAME.as_ptr().cast(),
        JNCT_CMD_FIELD_DESCRIPTOR.as_ptr().cast(),
    );
    let args = ((*(*environment)).v1_1.GetStaticFieldID)(
        environment,
        local_class,
        JNCT_ARGS_FIELD_NAME.as_ptr().cast(),
        JNCT_ARGS_FIELD_DESCRIPTOR.as_ptr().cast(),
    );
    let result = ((*(*environment)).v1_1.GetStaticFieldID)(
        environment,
        local_class,
        JNCT_RESULT_FIELD_NAME.as_ptr().cast(),
        JNCT_RESULT_FIELD_DESCRIPTOR.as_ptr().cast(),
    );
    if cmd.is_null() || args.is_null() || result.is_null() || has_pending_exception(environment) {
        clear_pending_exception(environment);
        ((*(*environment)).v1_1.DeleteLocalRef)(environment, local_class);
        return None;
    }

    let class = ((*(*environment)).v1_1.NewGlobalRef)(environment, local_class);
    ((*(*environment)).v1_1.DeleteLocalRef)(environment, local_class);
    if class.is_null() {
        clear_pending_exception(environment);
        return None;
    }

    Some(JnctFields {
        class: class.cast(),
        cmd,
        args,
        result,
    })
}

unsafe fn has_pending_exception(environment: *mut JNIEnv) -> bool {
    ((*(*environment)).v1_2.ExceptionCheck)(environment)
}

unsafe fn clear_pending_exception(environment: *mut JNIEnv) {
    if has_pending_exception(environment) {
        ((*(*environment)).v1_1.ExceptionClear)(environment);
    }
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

unsafe fn get_current_java_vm() -> Option<*mut JavaVM> {
    let jvm_module = GetModuleHandleW(JVM_DLL_NAME.as_ptr());
    if jvm_module.is_null() {
        return None;
    }

    let get_created_java_vms_address =
        GetProcAddress(jvm_module, JNI_GET_CREATED_JAVA_VMS.as_ptr());
    if get_created_java_vms_address.is_null() {
        return None;
    }
    let get_created_java_vms: JniGetCreatedJavaVms =
        core::mem::transmute(get_created_java_vms_address);

    let mut java_vm: *mut JavaVM = ptr::null_mut();
    let mut java_vm_count: jsize = 0;
    if get_created_java_vms(&mut java_vm, 1, &mut java_vm_count) != JNI_OK
        || java_vm.is_null()
        || java_vm_count < 1
    {
        return None;
    }

    Some(java_vm)
}

unsafe fn attach_current_thread(java_vm: *mut JavaVM) -> Option<*mut JNIEnv> {
    let mut environment: *mut c_void = ptr::null_mut();
    if ((*(*java_vm)).v1_4.AttachCurrentThreadAsDaemon)(java_vm, &mut environment, ptr::null_mut())
        != JNI_OK
        || environment.is_null()
    {
        return None;
    }

    Some(environment.cast())
}

unsafe fn get_current_environment() -> Option<*mut JNIEnv> {
    let java_vm = get_current_java_vm()?;
    let mut environment: *mut c_void = ptr::null_mut();
    if ((*(*java_vm)).v1_2.GetEnv)(java_vm, &mut environment, JNI_VERSION_1_8) != JNI_OK
        || environment.is_null()
    {
        return None;
    }

    Some(environment.cast())
}

extern "system" fn native_say_hello(_: *mut c_void, _: *mut c_void) {
    printf!("hello");
}
