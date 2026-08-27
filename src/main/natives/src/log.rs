use core::ffi::c_void;
use core::fmt::{self, Write};

const STD_OUTPUT_HANDLE: i32 = -11;
const STD_ERROR_HANDLE: i32 = -12;
const LINE_ENDING: &str = "\r\n";
const INFO_PREFIX: &str = "[INFO] ";
const ERROR_PREFIX: &str = "[ERROR] ";

#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetStdHandle(std_handle: i32) -> *mut c_void;
    fn WriteFile(
        handle: *mut c_void,
        buffer: *const c_void,
        number_of_bytes_to_write: u32,
        number_of_bytes_written: *mut u32,
        overlapped: *mut c_void,
    ) -> i32;
}

pub fn printf(message: impl fmt::Display) {
    write_message(STD_OUTPUT_HANDLE, "", format_args!("{}", message));
}

pub fn info(message: impl fmt::Display) {
    write_message(STD_OUTPUT_HANDLE, INFO_PREFIX, format_args!("{}", message));
}

pub fn error(message: impl fmt::Display) {
    write_message(STD_ERROR_HANDLE, ERROR_PREFIX, format_args!("{}", message));
}

fn write_message(handle_kind: i32, prefix: &str, message: fmt::Arguments<'_>) {
    unsafe {
        let handle = GetStdHandle(handle_kind);
        if handle.is_null() {
            return;
        }

        let mut writer = ConsoleWriter { handle };
        let _ = writer.write_str(prefix);
        let _ = writer.write_fmt(message);
        let _ = writer.write_str(LINE_ENDING);
    }
}

struct ConsoleWriter {
    handle: *mut c_void,
}

impl Write for ConsoleWriter {
    fn write_str(&mut self, message: &str) -> fmt::Result {
        write_bytes(self.handle, message.as_bytes());
        Ok(())
    }
}

fn write_bytes(handle: *mut c_void, mut message: &[u8]) {
    while !message.is_empty() {
        let chunk_length = core::cmp::min(message.len(), u32::MAX as usize) as u32;
        let mut bytes_written = 0;
        let succeeded = unsafe {
            WriteFile(
                handle,
                message.as_ptr().cast(),
                chunk_length,
                &mut bytes_written,
                core::ptr::null_mut(),
            )
        } != 0;

        if !succeeded || bytes_written == 0 {
            return;
        }

        message = &message[core::cmp::min(bytes_written as usize, message.len())..];
    }
}

#[macro_export]
macro_rules! printf {
    ($($argument:tt)*) => {{
        $crate::log::printf(core::format_args!($($argument)*));
    }};
}

#[macro_export]
macro_rules! info {
    ($($argument:tt)*) => {{
        $crate::log::info(core::format_args!($($argument)*));
    }};
}

#[macro_export]
macro_rules! error {
    ($($argument:tt)*) => {{
        $crate::log::error(core::format_args!($($argument)*));
    }};
}

// #[cfg(test)]
// mod tests {
//     use alloc::string::String;
//
//     use super::{ERROR_PREFIX, INFO_PREFIX, format_message};
//
//     #[test]
//     fn formats_rust_style_placeholders() {
//         let mut output = String::new();
//         format_message(
//             &mut output,
//             "",
//             format_args!("原神是 {}，所有者：{}", "游戏", "米哈游"),
//         );
//         assert_eq!(output, "原神是 游戏，所有者：米哈游\r\n");
//     }
//
//     #[test]
//     fn supports_escaped_braces() {
//         let mut output = String::new();
//         format_message(&mut output, "", format_args!("value: {} {{}}", "one"));
//         assert_eq!(output, "value: one {}\r\n");
//     }
//
//     #[test]
//     fn supports_info_and_error_prefixes() {
//         let mut info_message = String::new();
//         format_message(&mut info_message, INFO_PREFIX, format_args!("ready"));
//         assert_eq!(info_message, "[INFO] ready\r\n");
//
//         let mut error_message = String::new();
//         format_message(&mut error_message, ERROR_PREFIX, format_args!("failed"));
//         assert_eq!(error_message, "[ERROR] failed\r\n");
//     }
// }

#[cfg(test)]
fn format_message<W: Write>(writer: &mut W, prefix: &str, message: fmt::Arguments<'_>) {
    let _ = writer.write_str(prefix);
    let _ = writer.write_fmt(message);
    let _ = writer.write_str(LINE_ENDING);
}
