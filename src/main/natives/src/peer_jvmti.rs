#![allow(unsafe_op_in_unsafe_fn)]

use core::cell::UnsafeCell;
use core::ffi::c_void;
use core::mem::{size_of, transmute};
use core::ptr;
use core::sync::atomic::{AtomicBool, Ordering};

use jni_sys::{JNI_OK, JavaVM, jint, jsize};

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

const JVMTI_MAGIC: u32 = 0x71EE;
const JVMTI_BAD_MAGIC: u32 = 0xDEAD;
const JVMTI_DISPOSED_MAGIC: u32 = 0xDEFC;
const JVMTI_CLEARED_MAGIC: u32 = 0;
const JVMTI_ERROR_NONE: i32 = 0;
const JVMTI_ERROR_ACCESS_DENIED: i32 = 111;
const JVMTI_VERSIONS: [jint; 3] = [0x3001_0200, 0x3001_0100, 0x3001_0000];

// These are zero-based slots in jvmtiInterface_1_.  Keep this list in sync with
// the JVMTI ABI instead of using a guessed table index for output-bearing calls.
const JVMTI_SET_EVENT_NOTIFICATION_MODE_SLOT: usize = 1;
const JVMTI_SET_BREAKPOINT_SLOT: usize = 37;
const JVMTI_SET_FIELD_ACCESS_WATCH_SLOT: usize = 40;
const JVMTI_SET_FIELD_MODIFICATION_WATCH_SLOT: usize = 42;
const JVMTI_SET_NATIVE_METHOD_PREFIX_SLOT: usize = 72;
const JVMTI_SET_NATIVE_METHOD_PREFIXES_SLOT: usize = 73;
const JVMTI_REDEFINE_CLASSES_SLOT: usize = 86;
const JVMTI_GET_OBJECTS_WITH_TAGS_SLOT: usize = 113;
const JVMTI_SET_JNI_FUNCTION_TABLE_SLOT: usize = 119;
const JVMTI_SET_EVENT_CALLBACKS_SLOT: usize = 121;
const JVMTI_DISPOSE_ENVIRONMENT_SLOT: usize = 126;
const JVMTI_ADD_CAPABILITIES_SLOT: usize = 141;
const JVMTI_RELINQUISH_CAPABILITIES_SLOT: usize = 142;
const JVMTI_RETRANSFORM_CLASSES_SLOT: usize = 151;

const MAX_CHAIN_STEPS: usize = 64;
const MAX_LAYOUT_CANDIDATES: usize = 512;
const MAX_VTABLE_SLOTS: usize = 256;
const MAX_TRACKED_ALIENS: usize = 128;
const MAX_SECTION_SIZE: usize = 16 * 1024 * 1024;
const HEAD_RETRY_MILLIS: u64 = 5_000;
const RECOVERY_PERIOD_MILLIS: u32 = 1_000;

const MEM_COMMIT: u32 = 0x1000;
const MEM_RESERVE: u32 = 0x2000;
const MEM_RELEASE: u32 = 0x8000;
const PAGE_READWRITE: u32 = 0x04;
const PAGE_EXECUTE: u32 = 0x10;
const PAGE_EXECUTE_READ: u32 = 0x20;
const PAGE_EXECUTE_READWRITE: u32 = 0x40;
const PAGE_EXECUTE_WRITECOPY: u32 = 0x80;
const PAGE_GUARD: u32 = 0x100;

const IMAGE_DOS_SIGNATURE: u16 = 0x5A4D;
const IMAGE_NT_SIGNATURE: u32 = 0x4550;
const IMAGE_SCN_MEM_READ: u32 = 0x4000_0000;
const IMAGE_SCN_MEM_WRITE: u32 = 0x8000_0000;
const POINTER_SIZE: usize = size_of::<usize>();

#[repr(C)]
struct MemoryBasicInformation {
    base_address: *mut c_void,
    allocation_base: *mut c_void,
    allocation_protect: u32,
    _padding: u32,
    region_size: usize,
    state: u32,
    protect: u32,
    type_: u32,
}

#[derive(Clone, Copy)]
struct Layout {
    external_offset: usize,
    magic_offset: usize,
    next_offset: usize,
}

#[derive(Clone, Copy)]
struct ShadowTable {
    base: usize,
    table: usize,
    slots: usize,
    active: bool,
}

struct State {
    java_vm: *mut JavaVM,
    our_env: usize,
    head_ptr: usize,
    external_offset: usize,
    magic_offset: usize,
    next_offset: usize,
    own_shadow_table: usize,
    own_original_functions: usize,
    own_slots: usize,
    initialized: bool,
    next_head_retry: u64,
    shadows: [ShadowTable; MAX_TRACKED_ALIENS],
    shadow_count: usize,
}

impl State {
    const EMPTY_SHADOW: ShadowTable = ShadowTable {
        base: 0,
        table: 0,
        slots: 0,
        active: false,
    };

    const fn new() -> Self {
        Self {
            java_vm: ptr::null_mut(),
            our_env: 0,
            head_ptr: 0,
            external_offset: 0,
            magic_offset: 0,
            next_offset: 0,
            own_shadow_table: 0,
            own_original_functions: 0,
            own_slots: 0,
            initialized: false,
            next_head_retry: 0,
            shadows: [Self::EMPTY_SHADOW; MAX_TRACKED_ALIENS],
            shadow_count: 0,
        }
    }
}

struct StateCell(UnsafeCell<State>);
unsafe impl Sync for StateCell {}

static LOCKED: AtomicBool = AtomicBool::new(false);
static RUNNING: AtomicBool = AtomicBool::new(false);
static STATE: StateCell = StateCell(UnsafeCell::new(State::new()));

#[link(name = "kernel32")]
unsafe extern "system" {
    fn CloseHandle(object: *mut c_void) -> i32;
    fn CreateThread(
        thread_attributes: *mut c_void,
        stack_size: usize,
        start_address: Option<unsafe extern "system" fn(*mut c_void) -> u32>,
        parameter: *mut c_void,
        creation_flags: u32,
        thread_id: *mut u32,
    ) -> *mut c_void;
    fn GetCurrentProcess() -> *mut c_void;
    fn GetModuleHandleW(module_name: *const u16) -> *mut c_void;
    fn GetProcAddress(module: *mut c_void, procedure_name: *const u8) -> *mut c_void;
    fn GetTickCount64() -> u64;
    fn ReadProcessMemory(
        process: *mut c_void,
        base_address: *const c_void,
        buffer: *mut c_void,
        size: usize,
        read: *mut usize,
    ) -> i32;
    fn Sleep(milliseconds: u32);
    fn VirtualAlloc(
        address: *mut c_void,
        size: usize,
        allocation_type: u32,
        protect: u32,
    ) -> *mut c_void;
    fn VirtualFree(address: *mut c_void, size: usize, free_type: u32) -> i32;
    fn VirtualQuery(
        address: *const c_void,
        buffer: *mut MemoryBasicInformation,
        length: usize,
    ) -> usize;
    fn WriteProcessMemory(
        process: *mut c_void,
        base_address: *mut c_void,
        buffer: *const c_void,
        size: usize,
        written: *mut usize,
    ) -> i32;
}

#[inline]
fn state() -> &'static mut State {
    // SAFETY: every caller holds LOCKED; the state is private to this module.
    unsafe { &mut *STATE.0.get() }
}

fn lock() {
    while LOCKED
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        core::hint::spin_loop();
    }
}

fn unlock() {
    LOCKED.store(false, Ordering::Release);
}

fn current_tick() -> u64 {
    unsafe { GetTickCount64() }
}

unsafe fn read_bytes(address: usize, destination: *mut u8, length: usize) -> bool {
    if address == 0 || destination.is_null() || length == 0 {
        return false;
    }
    let mut transferred = 0usize;
    unsafe {
        ReadProcessMemory(
            GetCurrentProcess(),
            address as *const c_void,
            destination.cast(),
            length,
            &mut transferred,
        ) != 0
            && transferred == length
    }
}

unsafe fn read_pointer(address: usize) -> Option<usize> {
    let mut value = 0usize;
    if unsafe { read_bytes(address, (&mut value as *mut usize).cast(), POINTER_SIZE) } {
        Some(value)
    } else {
        None
    }
}

unsafe fn read_u16(address: usize) -> Option<u16> {
    let mut value = 0u16;
    if unsafe { read_bytes(address, (&mut value as *mut u16).cast(), size_of::<u16>()) } {
        Some(value)
    } else {
        None
    }
}

unsafe fn read_u32(address: usize) -> Option<u32> {
    let mut value = 0u32;
    if unsafe { read_bytes(address, (&mut value as *mut u32).cast(), size_of::<u32>()) } {
        Some(value)
    } else {
        None
    }
}

unsafe fn write_bytes(address: usize, source: *const u8, length: usize) -> bool {
    if address == 0 || source.is_null() || length == 0 {
        return false;
    }
    let mut transferred = 0usize;
    unsafe {
        WriteProcessMemory(
            GetCurrentProcess(),
            address as *mut c_void,
            source.cast(),
            length,
            &mut transferred,
        ) != 0
            && transferred == length
    }
}

unsafe fn write_pointer(address: usize, value: usize) -> bool {
    unsafe { write_bytes(address, (&value as *const usize).cast(), POINTER_SIZE) }
}

unsafe fn write_u32(address: usize, value: u32) -> bool {
    unsafe { write_bytes(address, (&value as *const u32).cast(), size_of::<u32>()) }
}

fn is_executable(address: usize) -> bool {
    if address == 0 {
        return false;
    }
    let mut info = MemoryBasicInformation {
        base_address: ptr::null_mut(),
        allocation_base: ptr::null_mut(),
        allocation_protect: 0,
        _padding: 0,
        region_size: 0,
        state: 0,
        protect: 0,
        type_: 0,
    };
    let queried = unsafe {
        VirtualQuery(
            address as *const c_void,
            &mut info,
            size_of::<MemoryBasicInformation>(),
        )
    };
    if queried != size_of::<MemoryBasicInformation>() || info.state != MEM_COMMIT {
        return false;
    }
    let protection = info.protect & 0xff;
    (protection == PAGE_EXECUTE
        || protection == PAGE_EXECUTE_READ
        || protection == PAGE_EXECUTE_READWRITE
        || protection == PAGE_EXECUTE_WRITECOPY)
        && (info.protect & PAGE_GUARD) == 0
}

unsafe extern "system" fn noop_stub(_: *mut c_void) -> i32 {
    JVMTI_ERROR_NONE
}

unsafe extern "system" fn deny_stub(_: *mut c_void) -> i32 {
    JVMTI_ERROR_ACCESS_DENIED
}

unsafe extern "system" fn set_event_notification_mode_stub(
    _: *mut c_void,
    _: jint,
    _: jint,
    _: *mut c_void,
) -> i32 {
    JVMTI_ERROR_NONE
}

unsafe extern "system" fn empty_get_objects_with_tags_stub(
    _: *mut c_void,
    _: jint,
    _: *const i64,
    count: *mut jint,
    objects: *mut *mut c_void,
    tags: *mut *mut i64,
) -> i32 {
    if !count.is_null() {
        unsafe { *count = 0 };
    }
    if !objects.is_null() {
        unsafe { *objects = ptr::null_mut() };
    }
    if !tags.is_null() {
        unsafe { *tags = ptr::null_mut() };
    }
    JVMTI_ERROR_NONE
}

fn is_likely_functions(functions: usize) -> bool {
    if functions == 0 {
        return false;
    }
    const PROBES: [usize; 5] = [1, 86, 121, 141, 151];
    let mut executable = 0usize;
    for slot in PROBES {
        let value = unsafe { read_pointer(functions + slot * POINTER_SIZE) };
        match value {
            Some(value) if is_executable(value) => executable += 1,
            Some(_) => {}
            None => return false,
        }
    }
    executable >= 4
}

fn discover_vtable_slots(source: usize) -> usize {
    if source == 0 {
        return 0;
    }
    let mut last_valid: isize = -1;
    let mut trailing_invalid = 0usize;
    for slot in 0..MAX_VTABLE_SLOTS {
        let function = unsafe { read_pointer(source + slot * POINTER_SIZE) };
        let valid = matches!(function, Some(value) if value != 0 && is_executable(value));
        if valid {
            last_valid = slot as isize;
            trailing_invalid = 0;
        } else if (slot as isize) > last_valid {
            trailing_invalid += 1;
            if trailing_invalid >= 16 {
                break;
            }
        }
    }
    if last_valid < 1 {
        0
    } else {
        last_valid as usize + 1
    }
}

unsafe fn allocate(size: usize) -> usize {
    if size == 0 {
        return 0;
    }
    unsafe {
        VirtualAlloc(
            ptr::null_mut(),
            size,
            MEM_COMMIT | MEM_RESERVE,
            PAGE_READWRITE,
        ) as usize
    }
}

unsafe fn copy_vtable(source: usize) -> Option<(usize, usize)> {
    if !is_likely_functions(source) {
        return None;
    }
    let slots = discover_vtable_slots(source);
    if slots < 2 {
        return None;
    }
    let table = unsafe { allocate(slots * POINTER_SIZE) };
    if table == 0 {
        return None;
    }
    for slot in 0..slots {
        let value = match unsafe { read_pointer(source + slot * POINTER_SIZE) } {
            Some(value) => value,
            None => {
                unsafe { VirtualFree(table as *mut c_void, 0, MEM_RELEASE) };
                return None;
            }
        };
        unsafe { ptr::write_volatile((table + slot * POINTER_SIZE) as *mut usize, value) };
    }
    Some((table, slots))
}

fn is_silent_noop_slot(slot: usize) -> bool {
    matches!(
        slot,
        JVMTI_SET_EVENT_NOTIFICATION_MODE_SLOT
            | JVMTI_SET_BREAKPOINT_SLOT
            | JVMTI_SET_FIELD_ACCESS_WATCH_SLOT
            | JVMTI_SET_FIELD_MODIFICATION_WATCH_SLOT
            | JVMTI_SET_NATIVE_METHOD_PREFIX_SLOT
            | JVMTI_SET_NATIVE_METHOD_PREFIXES_SLOT
            | JVMTI_REDEFINE_CLASSES_SLOT
            | JVMTI_SET_JNI_FUNCTION_TABLE_SLOT
            | JVMTI_SET_EVENT_CALLBACKS_SLOT
            | JVMTI_ADD_CAPABILITIES_SLOT
            | JVMTI_RELINQUISH_CAPABILITIES_SLOT
            | JVMTI_RETRANSFORM_CLASSES_SLOT
    )
}

unsafe fn neutralize_external_vtable(table: usize, slots: usize) {
    if table == 0 {
        return;
    }
    let noop = noop_stub as *const () as usize;
    let deny = deny_stub as *const () as usize;
    let set_event_notification = set_event_notification_mode_stub as *const () as usize;
    let empty_get_objects = empty_get_objects_with_tags_stub as *const () as usize;
    for slot in 0..slots {
        let address = table + slot * POINTER_SIZE;
        if unsafe { read_pointer(address) }.unwrap_or(0) == 0 {
            continue;
        }
        let replacement = if slot == JVMTI_SET_EVENT_NOTIFICATION_MODE_SLOT {
            set_event_notification
        } else if slot == JVMTI_GET_OBJECTS_WITH_TAGS_SLOT {
            empty_get_objects
        } else if is_silent_noop_slot(slot) {
            noop
        } else {
            deny
        };
        unsafe { ptr::write_volatile(address as *mut usize, replacement) };
    }
}

unsafe fn protect_own_vtable(table: usize, slots: usize) {
    if table == 0 || JVMTI_DISPOSE_ENVIRONMENT_SLOT >= slots {
        return;
    }
    let address = table + JVMTI_DISPOSE_ENVIRONMENT_SLOT * POINTER_SIZE;
    let noop = noop_stub as *const () as usize;
    if unsafe { read_pointer(address) }.unwrap_or(0) != noop {
        unsafe { ptr::write_volatile(address as *mut usize, noop) };
    }
}

fn is_plausible_base(base: usize, layout: Layout, allow_disposed: bool) -> bool {
    if base < 0x10_000
        || (base & (POINTER_SIZE - 1)) != 0
        || base.checked_add(layout.external_offset).is_none()
    {
        return false;
    }
    if POINTER_SIZE == 8 && base > 0x0000_7FFF_FFFF_FFFF {
        return false;
    }
    let env = base + layout.external_offset;
    let magic_address = match env.checked_add(layout.magic_offset) {
        Some(value) => value,
        None => return false,
    };
    let magic = unsafe { read_u32(magic_address) };
    if magic != Some(JVMTI_MAGIC)
        && !(allow_disposed
            && (magic == Some(JVMTI_BAD_MAGIC)
                || magic == Some(JVMTI_DISPOSED_MAGIC)
                || magic == Some(JVMTI_CLEARED_MAGIC)))
    {
        return false;
    }
    let functions = unsafe { read_pointer(env) };
    matches!(functions, Some(value) if is_likely_functions(value))
}

fn inspect_chain(head: usize, target: usize, layout: Layout) -> (usize, bool) {
    let mut seen = [0usize; MAX_CHAIN_STEPS];
    let mut count = 0usize;
    let mut current = head;
    while count < MAX_CHAIN_STEPS && current != 0 {
        if seen[..count].contains(&current) {
            break;
        }
        if !is_plausible_base(current, layout, false) {
            break;
        }
        seen[count] = current;
        count += 1;
        if current == target {
            return (count, true);
        }
        current = unsafe { read_pointer(current + layout.next_offset) }.unwrap_or(0);
    }
    (count, false)
}

fn build_layouts(seed_env: usize, output: &mut [Layout; MAX_LAYOUT_CANDIDATES]) -> usize {
    if seed_env == 0 {
        return 0;
    }
    let mut magic_offsets = [0usize; 65];
    let mut magic_count = 0usize;
    for offset in (0..=0x100).step_by(4) {
        if unsafe { read_u32(seed_env + offset) } == Some(JVMTI_MAGIC)
            && magic_count < magic_offsets.len()
        {
            magic_offsets[magic_count] = offset;
            magic_count += 1;
        }
    }
    let mut count = 0usize;
    for magic_index in 0..magic_count {
        let magic_offset = magic_offsets[magic_index];
        for external_offset in (0..=0x80).step_by(POINTER_SIZE) {
            if seed_env < external_offset {
                continue;
            }
            let base = seed_env - external_offset;
            for next_offset in (0..=0x120).step_by(POINTER_SIZE) {
                let expected = external_offset + magic_offset + 8;
                if next_offset < expected || next_offset > expected + 0x40 {
                    continue;
                }
                let next = unsafe { read_pointer(base + next_offset) }.unwrap_or(0);
                if next != 0 {
                    let candidate = Layout {
                        external_offset,
                        magic_offset,
                        next_offset,
                    };
                    if !is_plausible_base(next, candidate, false) {
                        continue;
                    }
                }
                if count >= output.len() {
                    return count;
                }
                output[count] = Layout {
                    external_offset,
                    magic_offset,
                    next_offset,
                };
                count += 1;
            }
        }
    }

    // Prefer the canonical next-field placement, but do not hard-code it.
    for index in 1..count {
        let candidate = output[index];
        let expected = candidate.external_offset + candidate.magic_offset + 8;
        if candidate.next_offset != expected {
            continue;
        }
        let mut position = index;
        while position > 0 {
            let previous = output[position - 1];
            let previous_expected = previous.external_offset + previous.magic_offset + 8;
            if previous.next_offset == previous_expected {
                break;
            }
            output[position] = previous;
            position -= 1;
        }
        output[position] = candidate;
    }
    count
}

fn layout_rank(layout: Layout, chain_length: usize) -> usize {
    let expected = layout.external_offset + layout.magic_offset + 8;
    chain_length * 10_000
        + if layout.next_offset == expected {
            500
        } else {
            0
        }
}

fn scan_head_candidates(
    seed_env: usize,
    layouts: &[Layout; MAX_LAYOUT_CANDIDATES],
    layout_count: usize,
) -> Option<(usize, Layout)> {
    let jvm = unsafe { GetModuleHandleW(JVM_DLL_NAME.as_ptr()) } as usize;
    if jvm == 0 || layout_count == 0 {
        return None;
    }
    if unsafe { read_u16(jvm) }? != IMAGE_DOS_SIGNATURE {
        return None;
    }
    let nt_offset = unsafe { read_u32(jvm + 0x3c) }? as usize;
    let nt = jvm.checked_add(nt_offset)?;
    if unsafe { read_u32(nt) }? != IMAGE_NT_SIGNATURE {
        return None;
    }
    let section_count = unsafe { read_u16(nt + 6) }? as usize;
    let optional_size = unsafe { read_u16(nt + 20) }? as usize;
    let section_headers = nt.checked_add(24 + optional_size)?;
    let mut target_bases = [0usize; MAX_LAYOUT_CANDIDATES];
    for index in 0..layout_count {
        target_bases[index] = seed_env
            .checked_sub(layouts[index].external_offset)
            .unwrap_or(0);
    }

    let mut best_target: Option<(usize, Layout, usize)> = None;
    for section_index in 0..section_count {
        let header = section_headers + section_index * 40;
        let characteristics = unsafe { read_u32(header + 36) }.unwrap_or(0);
        if characteristics & IMAGE_SCN_MEM_READ == 0 || characteristics & IMAGE_SCN_MEM_WRITE == 0 {
            continue;
        }
        let virtual_address = unsafe { read_u32(header + 12) }.unwrap_or(0) as usize;
        let size = (unsafe { read_u32(header + 8) }.unwrap_or(0) as usize).min(MAX_SECTION_SIZE);
        if size < POINTER_SIZE {
            continue;
        }
        let section_address = match jvm.checked_add(virtual_address) {
            Some(value) => value,
            None => continue,
        };
        let buffer = unsafe { allocate(size) };
        if buffer == 0 {
            continue;
        }
        if !unsafe { read_bytes(section_address, buffer as *mut u8, size) } {
            unsafe { VirtualFree(buffer as *mut c_void, 0, MEM_RELEASE) };
            continue;
        }
        for offset in (0..=(size - POINTER_SIZE)).step_by(POINTER_SIZE) {
            let candidate = unsafe { ptr::read_unaligned((buffer + offset) as *const usize) };
            if candidate == 0 {
                continue;
            }
            for layout_index in 0..layout_count {
                let layout = layouts[layout_index];
                let target = target_bases[layout_index];
                if target == 0 {
                    continue;
                }
                let (chain_count, reaches_target) = inspect_chain(candidate, target, layout);
                if chain_count == 0 {
                    continue;
                }
                let rank = layout_rank(layout, chain_count);
                if reaches_target {
                    if best_target.map_or(true, |entry| rank > entry.2) {
                        best_target = Some((section_address + offset, layout, rank));
                    }
                }
            }
        }
        unsafe { VirtualFree(buffer as *mut c_void, 0, MEM_RELEASE) };
    }
    // Fail closed: a writable jvm.dll pointer that only resembles a JVMTI
    // chain is not enough to justify modifying JVM memory.  Accept a head only
    // when the candidate chain demonstrably reaches our live environment.
    best_target.map(|(head_ptr, layout, _)| (head_ptr, layout))
}

fn resolve_head_pointer(seed_env: usize) -> bool {
    let mut layouts = [Layout {
        external_offset: 0,
        magic_offset: 0,
        next_offset: 0,
    }; MAX_LAYOUT_CANDIDATES];
    let layout_count = build_layouts(seed_env, &mut layouts);
    let Some((head_ptr, layout)) = scan_head_candidates(seed_env, &layouts, layout_count) else {
        return false;
    };
    let current = state();
    current.head_ptr = head_ptr;
    current.external_offset = layout.external_offset;
    current.magic_offset = layout.magic_offset;
    current.next_offset = layout.next_offset;
    true
}

fn find_created_java_vm() -> Option<*mut JavaVM> {
    let module = unsafe { GetModuleHandleW(JVM_DLL_NAME.as_ptr()) };
    if module.is_null() {
        return None;
    }
    let address = unsafe { GetProcAddress(module, JNI_GET_CREATED_JAVA_VMS.as_ptr()) };
    if address.is_null() {
        return None;
    }
    let function: unsafe extern "system" fn(*mut *mut JavaVM, jsize, *mut jsize) -> jint =
        unsafe { transmute(address) };
    let mut vm: *mut JavaVM = ptr::null_mut();
    let mut count: jsize = 0;
    let result = unsafe { function(&mut vm, 1, &mut count) };
    if result == JNI_OK && count > 0 && !vm.is_null() {
        Some(vm)
    } else {
        None
    }
}

unsafe fn get_jvmti_env(vm: *mut JavaVM) -> Option<usize> {
    if vm.is_null() {
        return None;
    }
    for version in JVMTI_VERSIONS {
        let mut environment: *mut c_void = ptr::null_mut();
        let result = unsafe { ((*(*vm)).v1_2.GetEnv)(vm, &mut environment, version) };
        if result == JNI_OK && !environment.is_null() {
            return Some(environment as usize);
        }
    }
    None
}

unsafe fn shield_own_environment() -> bool {
    let current = state();
    if current.our_env == 0 {
        return false;
    }
    let functions = unsafe { read_pointer(current.our_env) }.unwrap_or(0);
    if functions == 0 {
        return false;
    }
    if current.own_shadow_table != 0
        && functions != current.own_shadow_table
        && functions != current.own_original_functions
        && !is_likely_functions(functions)
    {
        return false;
    }
    if current.own_shadow_table == 0 {
        let Some((table, slots)) = (unsafe { copy_vtable(functions) }) else {
            return false;
        };
        current.own_original_functions = functions;
        current.own_shadow_table = table;
        current.own_slots = slots;
    }
    unsafe { protect_own_vtable(current.own_shadow_table, current.own_slots) };
    if functions != current.own_shadow_table {
        unsafe { write_pointer(current.our_env, current.own_shadow_table) }
    } else {
        true
    }
}

fn tracked_shadow(base: usize) -> Option<usize> {
    let current = state();
    for index in 0..current.shadow_count {
        let entry = current.shadows[index];
        if entry.active && entry.base == base {
            return Some(index);
        }
    }
    None
}

fn is_sweep_base(base: usize, layout: Layout) -> bool {
    if is_plausible_base(base, layout, false) {
        return true;
    }
    // A previously neutralized environment may still be linked while carrying
    // one of HotSpot's known disposed markers.  Only accept that state for a
    // base we already shadowed; unknown disposed-looking pointers remain a
    // hard stop so a malformed chain cannot make us write arbitrary memory.
    tracked_shadow(base).is_some() && is_plausible_base(base, layout, true)
}

unsafe fn shadow_alien_environment(base: usize) -> bool {
    let current = state();
    let layout = Layout {
        external_offset: current.external_offset,
        magic_offset: current.magic_offset,
        next_offset: current.next_offset,
    };
    if current.our_env == 0 || !is_plausible_base(base, layout, true) {
        return false;
    }
    let external_offset = current.external_offset;
    let env = match base.checked_add(external_offset) {
        Some(value) => value,
        None => return false,
    };
    let functions = unsafe { read_pointer(env) }.unwrap_or(0);
    if functions == 0 {
        return false;
    }

    let entry_index = tracked_shadow(base);
    let (table, slots, index) = if let Some(index) = entry_index {
        let entry = state().shadows[index];
        if entry.table == 0 || entry.slots < 2 {
            return false;
        }
        (entry.table, entry.slots, index)
    } else {
        if state().shadow_count >= MAX_TRACKED_ALIENS {
            return false;
        }
        let Some((table, slots)) = (unsafe { copy_vtable(functions) }) else {
            return false;
        };
        let index = state().shadow_count;
        state().shadows[index] = ShadowTable {
            base,
            table,
            slots,
            active: true,
        };
        state().shadow_count += 1;
        (table, slots, index)
    };

    unsafe { neutralize_external_vtable(table, slots) };
    let installed = functions == table || unsafe { write_pointer(env, table) };
    if installed {
        state().shadows[index].active = true;
    }
    installed
}

unsafe fn invalidate_magic(base: usize) -> bool {
    let external_offset = state().external_offset;
    let magic_offset = state().magic_offset;
    let env = match base.checked_add(external_offset) {
        Some(value) => value,
        None => return false,
    };
    let address = match env.checked_add(magic_offset) {
        Some(value) => value,
        None => return false,
    };
    let value = unsafe { read_u32(address) }.unwrap_or(u32::MAX);
    if value == JVMTI_BAD_MAGIC {
        true
    } else if value == JVMTI_MAGIC || value == JVMTI_DISPOSED_MAGIC || value == 0 {
        unsafe { write_u32(address, JVMTI_BAD_MAGIC) }
    } else {
        false
    }
}

unsafe fn heal_own_environment() {
    let current = state();
    if current.our_env == 0 || current.head_ptr == 0 {
        return;
    }
    let layout = Layout {
        external_offset: current.external_offset,
        magic_offset: current.magic_offset,
        next_offset: current.next_offset,
    };
    let own_base = match current.our_env.checked_sub(current.external_offset) {
        Some(value) => value,
        None => return,
    };
    if !is_plausible_base(own_base, layout, true) {
        return;
    }
    let magic_address = match current.our_env.checked_add(current.magic_offset) {
        Some(value) => value,
        None => return,
    };
    let magic = unsafe { read_u32(magic_address) }.unwrap_or(u32::MAX);
    if magic != JVMTI_MAGIC {
        if magic != JVMTI_BAD_MAGIC && magic != JVMTI_DISPOSED_MAGIC && magic != JVMTI_CLEARED_MAGIC
        {
            return;
        }
        if !unsafe { write_u32(magic_address, JVMTI_MAGIC) } {
            return;
        }
    }
    let head = unsafe { read_pointer(current.head_ptr) }.unwrap_or(0);
    if head != 0 {
        let layout = Layout {
            external_offset: current.external_offset,
            magic_offset: current.magic_offset,
            next_offset: current.next_offset,
        };
        let (_, reaches) = inspect_chain(head, own_base, layout);
        if reaches {
            return;
        }
    }
    if unsafe { write_pointer(own_base + current.next_offset, head) } {
        unsafe { write_pointer(current.head_ptr, own_base) };
    }
}

fn sweep_chain() -> usize {
    let current = state();
    if current.head_ptr == 0 || current.our_env == 0 {
        return 0;
    }
    let layout = Layout {
        external_offset: current.external_offset,
        magic_offset: current.magic_offset,
        next_offset: current.next_offset,
    };
    let own_base = match current.our_env.checked_sub(current.external_offset) {
        Some(value) => value,
        None => return 0,
    };
    let mut current_base = unsafe { read_pointer(current.head_ptr) }.unwrap_or(0);
    let mut previous = 0usize;
    let mut changed = 0usize;
    for _ in 0..MAX_CHAIN_STEPS {
        if current_base == 0 || !is_sweep_base(current_base, layout) {
            break;
        }
        let next = unsafe { read_pointer(current_base + current.next_offset) }.unwrap_or(0);
        if current_base != own_base {
            let link_address = if previous == 0 {
                current.head_ptr
            } else {
                previous + current.next_offset
            };
            if unsafe { write_pointer(link_address, next) } {
                unsafe {
                    invalidate_magic(current_base);
                    shadow_alien_environment(current_base);
                }
                changed += 1;
                current_base = next;
                continue;
            }
        }
        previous = current_base;
        current_base = next;
    }

    let shadow_count = state().shadow_count;
    for index in 0..shadow_count {
        let base = state().shadows[index].base;
        if base != 0 {
            unsafe {
                invalidate_magic(base);
                shadow_alien_environment(base);
            }
        }
    }
    changed
}

fn initialize_locked() -> bool {
    if state().initialized {
        return true;
    }
    if POINTER_SIZE != 8 {
        return false;
    }
    let vm = match find_created_java_vm() {
        Some(value) => value,
        None => return false,
    };
    let env = match unsafe { get_jvmti_env(vm) } {
        Some(value) => value,
        None => return false,
    };
    state().java_vm = vm;
    state().our_env = env;
    if !unsafe { shield_own_environment() } {
        *state() = State::new();
        return false;
    }

    let resolved = resolve_head_pointer(env);
    state().next_head_retry = if resolved {
        u64::MAX
    } else {
        current_tick().saturating_add(HEAD_RETRY_MILLIS)
    };
    state().initialized = true;
    RUNNING.store(true, Ordering::Release);
    unsafe {
        let handle = CreateThread(
            ptr::null_mut(),
            0,
            Some(recovery_thread),
            ptr::null_mut(),
            0,
            ptr::null_mut(),
        );
        if !handle.is_null() {
            CloseHandle(handle);
        }
    }
    if resolved {
        unsafe { heal_own_environment() };
        sweep_chain();
    }
    true
}

pub fn initialize() -> bool {
    lock();
    let result = initialize_locked();
    unlock();
    result
}

pub fn recover() -> usize {
    lock();
    if !state().initialized {
        unlock();
        return 0;
    }
    unsafe { shield_own_environment() };
    if state().head_ptr == 0 && current_tick() >= state().next_head_retry {
        let resolved = resolve_head_pointer(state().our_env);
        state().next_head_retry = if resolved {
            u64::MAX
        } else {
            current_tick().saturating_add(HEAD_RETRY_MILLIS)
        };
    }
    if state().head_ptr != 0 {
        unsafe { heal_own_environment() };
    }
    let changed = sweep_chain();
    unlock();
    changed
}

#[allow(dead_code)]
pub fn shutdown() {
    RUNNING.store(false, Ordering::Release);
    lock();
    // Do not restore or free JVM-owned structures during shutdown.  The JVM or
    // another thread may still hold an environment/table; leaked shadow tables
    // are reclaimed with the DLL and cannot become a use-after-free here.
    *state() = State::new();
    unlock();
}

unsafe extern "system" fn recovery_thread(_: *mut c_void) -> u32 {
    while RUNNING.load(Ordering::Acquire) {
        unsafe { Sleep(RECOVERY_PERIOD_MILLIS) };
        if RUNNING.load(Ordering::Acquire) {
            let changed = recover();
            if changed != 0 {
                crate::info!("JVMTI peer recovery neutralized {} environment(s)", changed);
            }
        }
    }
    0
}

#[cfg(test)]
mod tests {
    use super::{JVMTI_MAGIC, Layout};

    #[test]
    fn canonical_layout_rank_is_higher() {
        let layout = Layout {
            external_offset: 16,
            magic_offset: 8,
            next_offset: 32,
        };
        assert_eq!(super::layout_rank(layout, 2), 20_500);
        assert_eq!(JVMTI_MAGIC, 0x71EE);
    }
}
