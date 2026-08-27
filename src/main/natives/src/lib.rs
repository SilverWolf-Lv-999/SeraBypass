use std::ffi::c_void;

/// JNI smoke-test entry point invoked by `SeraBypass.sayHello()`.
///
/// `extern "system"` maps to the JNI calling convention on every supported Windows target.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_seraphina_cpp_SeraBypass_nativeSayHello<'a, 'b>(
    _environment: *mut c_void,
    _class: *mut c_void,
) {
    println!("hello, who are you");
}

#[cfg(test)]
mod tests {
    #[test]
    fn it_works() {
        assert_eq!(2 + 2, 4);
    }
}
