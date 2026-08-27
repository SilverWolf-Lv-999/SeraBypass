use core::ptr;

use jni_sys::{
    JNIEnv, jboolean, jclass, jint, jmethodID, jobject, jobjectArray, jsize, jstring, jvalue,
};

const JNCT_CLASS_NAME: &[u8] = b"io/github/seraphina/jnct/JNCT\0";
const CLASS_CLASS_NAME: &[u8] = b"java/lang/Class\0";
const CLASS_LOADER_CLASS_NAME: &[u8] = b"java/lang/ClassLoader\0";
const STRING_CLASS_NAME: &[u8] = b"java/lang/String\0";
const INPUT_STREAM_CLASS_NAME: &[u8] = b"java/io/InputStream\0";

const LOAD_CLASS_METHOD_NAME: &[u8] = b"loadClass\0";
const LOAD_CLASS_METHOD_DESCRIPTOR: &[u8] = b"(Ljava/lang/String;)Ljava/lang/Class;\0";
const GET_CLASS_LOADER_METHOD_NAME: &[u8] = b"getClassLoader\0";
const GET_CLASS_LOADER_METHOD_DESCRIPTOR: &[u8] = b"()Ljava/lang/ClassLoader;\0";
const GET_RESOURCE_AS_STREAM_METHOD_NAME: &[u8] = b"getResourceAsStream\0";
const GET_RESOURCE_AS_STREAM_METHOD_DESCRIPTOR: &[u8] = b"(Ljava/lang/String;)Ljava/io/InputStream;\0";
const STRING_REPLACE_METHOD_NAME: &[u8] = b"replace\0";
const STRING_REPLACE_METHOD_DESCRIPTOR: &[u8] = b"(CC)Ljava/lang/String;\0";
const STRING_CONCAT_METHOD_NAME: &[u8] = b"concat\0";
const STRING_CONCAT_METHOD_DESCRIPTOR: &[u8] = b"(Ljava/lang/String;)Ljava/lang/String;\0";
const READ_ALL_BYTES_METHOD_NAME: &[u8] = b"readAllBytes\0";
const READ_ALL_BYTES_METHOD_DESCRIPTOR: &[u8] = b"()[B\0";
const CLOSE_METHOD_NAME: &[u8] = b"close\0";
const CLOSE_METHOD_DESCRIPTOR: &[u8] = b"()V\0";
const INITIALIZER_METHOD_NAME: &[u8] = b"<init>\0";
const INITIALIZER_METHOD_DESCRIPTOR: &[u8] = b"()V\0";
const DEFINE_CLASS_METHOD_NAME: &[u8] = b"defineClass0\0";
const DEFINE_CLASS_METHOD_DESCRIPTOR: &[u8] = b"(Ljava/lang/ClassLoader;Ljava/lang/Class;Ljava/lang/String;[BIILjava/security/ProtectionDomain;ZILjava/lang/Object;)Ljava/lang/Class;\0";

const CLASS_FILE_SUFFIX: &[u8] = b".class\0";
const RESOURCE_ROOT: &[u8] = b"/\0";

const HIDDEN_CLASS_FLAG: jint = 2;
const STRONG_HIDDEN_CLASS_FLAG: jint = 4;
const HIDDEN_CLASS_FLAGS: jint = HIDDEN_CLASS_FLAG | STRONG_HIDDEN_CLASS_FLAG;

const ERROR_INVALID_ARGUMENTS: &[u8] = b"Invalid defineHiddenClass arguments\0";
const ERROR_CLASS_LOADING: &[u8] = b"Unable to load the requested hidden class\0";
const ERROR_CLASS_RESOURCE: &[u8] = b"Unable to read the requested class resource\0";
const ERROR_CLASS_DEFINITION: &[u8] = b"Unable to define the hidden class\0";
const ERROR_INSTANCE_CREATION: &[u8] = b"Unable to construct the hidden class\0";

const LOCAL_REFERENCE_CAPACITY: usize = 24;

#[derive(Clone, Copy)]
enum HiddenClassError {
    InvalidArguments,
    ClassLoading,
    ClassResource,
    ClassDefinition,
    InstanceCreation,
}

impl HiddenClassError {
    const fn message(self) -> &'static [u8] {
        match self {
            Self::InvalidArguments => ERROR_INVALID_ARGUMENTS,
            Self::ClassLoading => ERROR_CLASS_LOADING,
            Self::ClassResource => ERROR_CLASS_RESOURCE,
            Self::ClassDefinition => ERROR_CLASS_DEFINITION,
            Self::InstanceCreation => ERROR_INSTANCE_CREATION,
        }
    }

    const fn description(self) -> &'static str {
        match self {
            Self::InvalidArguments => "Invalid defineHiddenClass arguments",
            Self::ClassLoading => "Unable to load the requested hidden class",
            Self::ClassResource => "Unable to read the requested class resource",
            Self::ClassDefinition => "Unable to define the hidden class",
            Self::InstanceCreation => "Unable to construct the hidden class",
        }
    }
}

struct LocalReferences {
    environment: *mut JNIEnv,
    references: [jobject; LOCAL_REFERENCE_CAPACITY],
    count: usize,
}

impl LocalReferences {
    const fn new(environment: *mut JNIEnv) -> Self {
        Self {
            environment,
            references: [ptr::null_mut(); LOCAL_REFERENCE_CAPACITY],
            count: 0,
        }
    }

    unsafe fn add(&mut self, reference: jobject) -> jobject {
        if reference.is_null() {
            return reference;
        }

        if self.count == self.references.len() {
            unsafe {
                delete_local_reference(self.environment, reference);
            }
            return ptr::null_mut();
        }

        self.references[self.count] = reference;
        self.count += 1;
        reference
    }

    unsafe fn release(&mut self, reference: jobject) -> jobject {
        for tracked_reference in &mut self.references[..self.count] {
            if *tracked_reference == reference {
                *tracked_reference = ptr::null_mut();
                break;
            }
        }
        reference
    }
}

impl Drop for LocalReferences {
    fn drop(&mut self) {
        for reference in self.references[..self.count].iter().rev() {
            if !reference.is_null() {
                unsafe {
                    delete_local_reference(self.environment, *reference);
                }
            }
        }
    }
}

pub unsafe fn define_hidden_class_instance(
    environment: *mut JNIEnv,
    arguments: jobject,
) -> jobject {
    let mut local_references = LocalReferences::new(environment);
    let result = unsafe {
        define_hidden_class_instance_inner(environment, arguments, &mut local_references)
    };

    match result {
        Ok(instance) => unsafe { local_references.release(instance) },
        Err(error) => {
            unsafe {
                clear_pending_exception(environment);
            }
            crate::log::error(error.description());
            unsafe { new_string(environment, error.message()) }
        }
    }
}

unsafe fn define_hidden_class_instance_inner(
    environment: *mut JNIEnv,
    arguments: jobject,
    local_references: &mut LocalReferences,
) -> Result<jobject, HiddenClassError> {
    if arguments.is_null() {
        return Err(HiddenClassError::InvalidArguments);
    }

    let argument_count = unsafe { get_array_length(environment, arguments) };
    if unsafe { has_pending_exception(environment) } || argument_count < 1 {
        return Err(HiddenClassError::InvalidArguments);
    }

    let class_name = unsafe {
        local_references.add(get_object_array_element(
            environment,
            arguments.cast::<_>(),
            0,
        ))
    };
    if class_name.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::InvalidArguments);
    }

    let string_class = unsafe {
        local_references.add(find_class(environment, STRING_CLASS_NAME).cast())
    };
    if string_class.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassLoading);
    }
    if !unsafe { is_instance_of(environment, class_name, string_class.cast()) } {
        return Err(HiddenClassError::InvalidArguments);
    }
    if unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::InvalidArguments);
    }

    let class_class = unsafe {
        local_references.add(find_class(environment, CLASS_CLASS_NAME).cast())
    };
    let class_loader_class = unsafe {
        local_references.add(find_class(environment, CLASS_LOADER_CLASS_NAME).cast())
    };
    if class_class.is_null()
        || class_loader_class.is_null()
        || unsafe { has_pending_exception(environment) }
    {
        return Err(HiddenClassError::ClassLoading);
    }

    let get_class_loader = unsafe {
        get_method_id(
            environment,
            class_class.cast(),
            GET_CLASS_LOADER_METHOD_NAME,
            GET_CLASS_LOADER_METHOD_DESCRIPTOR,
        )
    };
    let load_class = unsafe {
        get_method_id(
            environment,
            class_loader_class.cast(),
            LOAD_CLASS_METHOD_NAME,
            LOAD_CLASS_METHOD_DESCRIPTOR,
        )
    };
    if get_class_loader.is_null()
        || load_class.is_null()
        || unsafe { has_pending_exception(environment) }
    {
        return Err(HiddenClassError::ClassLoading);
    }

    let requested_loader = if argument_count > 1 {
        unsafe {
            local_references.add(get_object_array_element(
                environment,
                arguments.cast::<_>(),
                1,
            ))
        }
    } else {
        ptr::null_mut()
    };
    if unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::InvalidArguments);
    }
    if !requested_loader.is_null()
        && !unsafe {
            is_instance_of(
                environment,
                requested_loader,
                class_loader_class.cast(),
            )
        }
    {
        return Err(HiddenClassError::InvalidArguments);
    }
    if unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::InvalidArguments);
    }

    let effective_loader = if requested_loader.is_null() {
        let jnct_class = unsafe {
            local_references.add(find_class(environment, JNCT_CLASS_NAME).cast())
        };
        if jnct_class.is_null() || unsafe { has_pending_exception(environment) } {
            return Err(HiddenClassError::ClassLoading);
        }

        let jnct_loader = unsafe {
            local_references.add(call_object_method_a(
                environment,
                jnct_class,
                get_class_loader,
                &[],
            ))
        };
        if jnct_loader.is_null() || unsafe { has_pending_exception(environment) } {
            return Err(HiddenClassError::ClassLoading);
        }
        jnct_loader
    } else {
        requested_loader
    };

    let lookup_class = unsafe {
        local_references.add(call_object_method_a(
            environment,
            effective_loader,
            load_class,
            &[jvalue {
                l: class_name,
            }],
        ))
    };
    if lookup_class.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassLoading);
    }

    let lookup_loader = unsafe {
        local_references.add(call_object_method_a(
            environment,
            lookup_class,
            get_class_loader,
            &[],
        ))
    };
    if unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassLoading);
    }

    let replace = unsafe {
        get_method_id(
            environment,
            string_class.cast(),
            STRING_REPLACE_METHOD_NAME,
            STRING_REPLACE_METHOD_DESCRIPTOR,
        )
    };
    let concat = unsafe {
        get_method_id(
            environment,
            string_class.cast(),
            STRING_CONCAT_METHOD_NAME,
            STRING_CONCAT_METHOD_DESCRIPTOR,
        )
    };
    if replace.is_null() || concat.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassResource);
    }

    let resource_name = unsafe {
        local_references.add(call_object_method_a(
            environment,
            class_name,
            replace,
            &[
                jvalue { c: b'.' as u16 },
                jvalue { c: b'/' as u16 },
            ],
        ))
    };
    if resource_name.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassResource);
    }

    let class_file_suffix = unsafe { local_references.add(new_string(environment, CLASS_FILE_SUFFIX).cast()) };
    if class_file_suffix.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassResource);
    }

    let resource_name = unsafe {
        local_references.add(call_object_method_a(
            environment,
            resource_name,
            concat,
            &[jvalue {
                l: class_file_suffix,
            }],
        ))
    };
    if resource_name.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassResource);
    }

    let input_stream = if lookup_loader.is_null() {
        let get_resource_as_stream = unsafe {
            get_method_id(
                environment,
                class_class.cast(),
                GET_RESOURCE_AS_STREAM_METHOD_NAME,
                GET_RESOURCE_AS_STREAM_METHOD_DESCRIPTOR,
            )
        };
        if get_resource_as_stream.is_null() || unsafe { has_pending_exception(environment) } {
            return Err(HiddenClassError::ClassResource);
        }

        let resource_root = unsafe { local_references.add(new_string(environment, RESOURCE_ROOT).cast()) };
        if resource_root.is_null() || unsafe { has_pending_exception(environment) } {
            return Err(HiddenClassError::ClassResource);
        }
        let absolute_resource_name = unsafe {
            local_references.add(call_object_method_a(
                environment,
                resource_root,
                concat,
                &[jvalue { l: resource_name }],
            ))
        };
        if absolute_resource_name.is_null() || unsafe { has_pending_exception(environment) } {
            return Err(HiddenClassError::ClassResource);
        }

        unsafe {
            local_references.add(call_object_method_a(
                environment,
                lookup_class,
                get_resource_as_stream,
                &[jvalue {
                    l: absolute_resource_name,
                }],
            ))
        }
    } else {
        let get_resource_as_stream = unsafe {
            get_method_id(
                environment,
                class_loader_class.cast(),
                GET_RESOURCE_AS_STREAM_METHOD_NAME,
                GET_RESOURCE_AS_STREAM_METHOD_DESCRIPTOR,
            )
        };
        if get_resource_as_stream.is_null() || unsafe { has_pending_exception(environment) } {
            return Err(HiddenClassError::ClassResource);
        }

        unsafe {
            local_references.add(call_object_method_a(
                environment,
                lookup_loader,
                get_resource_as_stream,
                &[jvalue { l: resource_name }],
            ))
        }
    };
    if input_stream.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassResource);
    }

    let input_stream_class = unsafe {
        local_references.add(find_class(environment, INPUT_STREAM_CLASS_NAME).cast())
    };
    if input_stream_class.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassResource);
    }

    let read_all_bytes = unsafe {
        get_method_id(
            environment,
            input_stream_class.cast(),
            READ_ALL_BYTES_METHOD_NAME,
            READ_ALL_BYTES_METHOD_DESCRIPTOR,
        )
    };
    let close = unsafe {
        get_method_id(
            environment,
            input_stream_class.cast(),
            CLOSE_METHOD_NAME,
            CLOSE_METHOD_DESCRIPTOR,
        )
    };
    if read_all_bytes.is_null() || close.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassResource);
    }

    let class_bytes = unsafe {
        local_references.add(call_object_method_a(
            environment,
            input_stream,
            read_all_bytes,
            &[],
        ))
    };
    if class_bytes.is_null() || unsafe { has_pending_exception(environment) } {
        unsafe {
            clear_pending_exception(environment);
            call_void_method_a(environment, input_stream, close, &[]);
        }
        return Err(HiddenClassError::ClassResource);
    }

    unsafe {
        call_void_method_a(environment, input_stream, close, &[]);
    }
    if unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassResource);
    }

    let class_byte_length = unsafe { get_array_length(environment, class_bytes) };
    if class_byte_length < 0 || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassResource);
    }

    let define_class = unsafe {
        get_static_method_id(
            environment,
            class_loader_class.cast(),
            DEFINE_CLASS_METHOD_NAME,
            DEFINE_CLASS_METHOD_DESCRIPTOR,
        )
    };
    if define_class.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassDefinition);
    }

    let hidden_class = unsafe {
        local_references.add(call_static_object_method_a(
            environment,
            class_loader_class.cast(),
            define_class,
            &[
                jvalue {
                    l: lookup_loader,
                },
                jvalue {
                    l: lookup_class,
                },
                jvalue { l: class_name },
                jvalue { l: class_bytes },
                jvalue { i: 0 },
                jvalue {
                    i: class_byte_length,
                },
                jvalue { l: ptr::null_mut() },
                jvalue { z: true },
                jvalue {
                    i: HIDDEN_CLASS_FLAGS,
                },
                jvalue { l: ptr::null_mut() },
            ],
        ))
    };
    if hidden_class.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::ClassDefinition);
    }

    let initializer = unsafe {
        get_method_id(
            environment,
            hidden_class.cast(),
            INITIALIZER_METHOD_NAME,
            INITIALIZER_METHOD_DESCRIPTOR,
        )
    };
    if initializer.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::InstanceCreation);
    }

    let instance = unsafe {
        local_references.add(new_object_a(
            environment,
            hidden_class.cast(),
            initializer,
            &[],
        ))
    };
    if instance.is_null() || unsafe { has_pending_exception(environment) } {
        return Err(HiddenClassError::InstanceCreation);
    }

    Ok(instance)
}

unsafe fn find_class(environment: *mut JNIEnv, class_name: &[u8]) -> jclass {
    unsafe { ((*(*environment)).v1_1.FindClass)(environment, class_name.as_ptr().cast()) }
}

unsafe fn get_method_id(
    environment: *mut JNIEnv,
    class: jclass,
    method_name: &[u8],
    method_descriptor: &[u8],
) -> jmethodID {
    unsafe {
        ((*(*environment)).v1_1.GetMethodID)(
            environment,
            class,
            method_name.as_ptr().cast(),
            method_descriptor.as_ptr().cast(),
        )
    }
}

unsafe fn get_static_method_id(
    environment: *mut JNIEnv,
    class: jclass,
    method_name: &[u8],
    method_descriptor: &[u8],
) -> jmethodID {
    unsafe {
        ((*(*environment)).v1_1.GetStaticMethodID)(
            environment,
            class,
            method_name.as_ptr().cast(),
            method_descriptor.as_ptr().cast(),
        )
    }
}

unsafe fn call_object_method_a(
    environment: *mut JNIEnv,
    object: jobject,
    method: jmethodID,
    arguments: &[jvalue],
) -> jobject {
    let arguments = if arguments.is_empty() {
        ptr::null()
    } else {
        arguments.as_ptr()
    };
    unsafe { ((*(*environment)).v1_1.CallObjectMethodA)(environment, object, method, arguments) }
}

unsafe fn call_static_object_method_a(
    environment: *mut JNIEnv,
    class: jclass,
    method: jmethodID,
    arguments: &[jvalue],
) -> jobject {
    let arguments = if arguments.is_empty() {
        ptr::null()
    } else {
        arguments.as_ptr()
    };
    unsafe {
        ((*(*environment)).v1_1.CallStaticObjectMethodA)(environment, class, method, arguments)
    }
}

unsafe fn call_void_method_a(
    environment: *mut JNIEnv,
    object: jobject,
    method: jmethodID,
    arguments: &[jvalue],
) {
    let arguments = if arguments.is_empty() {
        ptr::null()
    } else {
        arguments.as_ptr()
    };
    unsafe { ((*(*environment)).v1_1.CallVoidMethodA)(environment, object, method, arguments) }
}

unsafe fn new_object_a(
    environment: *mut JNIEnv,
    class: jclass,
    method: jmethodID,
    arguments: &[jvalue],
) -> jobject {
    let arguments = if arguments.is_empty() {
        ptr::null()
    } else {
        arguments.as_ptr()
    };
    unsafe { ((*(*environment)).v1_1.NewObjectA)(environment, class, method, arguments) }
}

unsafe fn new_string(environment: *mut JNIEnv, value: &[u8]) -> jstring {
    unsafe { ((*(*environment)).v1_1.NewStringUTF)(environment, value.as_ptr().cast()) }
}

unsafe fn get_array_length(environment: *mut JNIEnv, array: jobject) -> jsize {
    unsafe { ((*(*environment)).v1_1.GetArrayLength)(environment, array) }
}

unsafe fn get_object_array_element(
    environment: *mut JNIEnv,
    array: jobjectArray,
    index: jsize,
) -> jobject {
    unsafe { ((*(*environment)).v1_1.GetObjectArrayElement)(environment, array, index) }
}

unsafe fn is_instance_of(environment: *mut JNIEnv, object: jobject, class: jclass) -> jboolean {
    unsafe { ((*(*environment)).v1_1.IsInstanceOf)(environment, object, class) }
}

unsafe fn delete_local_reference(environment: *mut JNIEnv, reference: jobject) {
    unsafe { ((*(*environment)).v1_1.DeleteLocalRef)(environment, reference) }
}

unsafe fn has_pending_exception(environment: *mut JNIEnv) -> bool {
    unsafe { ((*(*environment)).v1_2.ExceptionCheck)(environment) }
}

unsafe fn clear_pending_exception(environment: *mut JNIEnv) {
    if unsafe { has_pending_exception(environment) } {
        unsafe { ((*(*environment)).v1_1.ExceptionClear)(environment) }
    }
}
