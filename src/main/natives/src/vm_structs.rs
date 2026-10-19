use core::cell::UnsafeCell;
use core::ffi::c_void;
use core::hint::spin_loop;
use core::ptr;
use core::sync::atomic::{AtomicU8, Ordering};

const VM_STRUCTS_SYMBOL: &[u8] = b"gHotSpotVMStructs\0";
const VM_STRUCT_ENTRY_TYPE_NAME_OFFSET_SYMBOL: &[u8] = b"gHotSpotVMStructEntryTypeNameOffset\0";
const VM_STRUCT_ENTRY_FIELD_NAME_OFFSET_SYMBOL: &[u8] = b"gHotSpotVMStructEntryFieldNameOffset\0";
const VM_STRUCT_ENTRY_IS_STATIC_OFFSET_SYMBOL: &[u8] = b"gHotSpotVMStructEntryIsStaticOffset\0";
const VM_STRUCT_ENTRY_OFFSET_OFFSET_SYMBOL: &[u8] = b"gHotSpotVMStructEntryOffsetOffset\0";
const VM_STRUCT_ENTRY_ADDRESS_OFFSET_SYMBOL: &[u8] = b"gHotSpotVMStructEntryAddressOffset\0";
const VM_STRUCT_ENTRY_ARRAY_STRIDE_SYMBOL: &[u8] = b"gHotSpotVMStructEntryArrayStride\0";
const VM_TYPES_SYMBOL: &[u8] = b"gHotSpotVMTypes\0";
const VM_TYPE_ENTRY_TYPE_NAME_OFFSET_SYMBOL: &[u8] = b"gHotSpotVMTypeEntryTypeNameOffset\0";
const VM_TYPE_ENTRY_SIZE_OFFSET_SYMBOL: &[u8] = b"gHotSpotVMTypeEntrySizeOffset\0";
const VM_TYPE_ENTRY_ARRAY_STRIDE_SYMBOL: &[u8] = b"gHotSpotVMTypeEntryArrayStride\0";
const MAX_NAME_LENGTH: usize = 256;
const MAX_ENTRIES: usize = 1_000_000;
const MAX_CACHED_FIELDS: usize = 64;
const MAX_CACHED_TYPES: usize = 16;
const UNINITIALIZED: u8 = 0;
const INITIALIZING: u8 = 1;
const AVAILABLE: u8 = 2;
const UNAVAILABLE: u8 = 3;
const FNV_OFFSET: u64 = 0xcbf29ce484222325;
const FNV_PRIME: u64 = 0x100000001b3;
const WANTED_FIELDS: [(&[u8], &[u8]); 33] = [
    (b"Klass", b"_name"),
    (b"Klass", b"_super"),
    (b"Klass", b"_next_link"),
    (b"Klass", b"_class_loader_data"),
    (b"Klass", b"_vtable_len"),
    (b"Klass", b"_access_flags"),
    (b"Klass", b"_layout_helper"),
    (b"InstanceKlass", b"_nonstatic_field_size"),
    (b"InstanceKlass", b"_static_field_size"),
    (b"InstanceKlass", b"_nonstatic_oop_map_size"),
    (b"InstanceKlass", b"_itable_len"),
    (b"InstanceKlass", b"_init_state"),
    (b"ConstantPool", b"_pool_holder"),
    (b"ConstantPool", b"_cache"),
    (b"ConstantPool", b"_resolved_klasses"),
    (b"ConstantPoolCache", b"_length"),
    (b"ConstantPoolCache", b"_constant_pool"),
    (b"ConstantPoolCache", b"_resolved_references"),
    (b"ConstantPoolCache", b"_reference_map"),
    (b"ClassLoaderData", b"_class_loader"),
    (b"ClassLoaderData", b"_klasses"),
    (b"ClassLoaderData", b"_dictionary"),
    (b"ClassLoaderData", b"_next"),
    (b"BasicHashtable<mtInternal>", b"_table_size"),
    (b"BasicHashtable<mtInternal>", b"_buckets"),
    (b"HashtableBucket<mtInternal>", b"_entry"),
    (b"BasicHashtableEntry<mtInternal>", b"_hash"),
    (b"BasicHashtableEntry<mtInternal>", b"_next"),
    (b"IntptrHashtableEntry", b"_literal"),
    (b"Method", b"_code"),
    (b"Method", b"_access_flags"),
    (b"ClassLoaderDataGraph", b"_head"),
    (b"ConstantPoolCacheEntry", b"_length"),
];
const WANTED_TYPES: [&[u8]; 2] = [b"ConstantPoolCacheEntry", b"HashtableBucket<mtInternal>"];

#[link(name = "kernel32")]
unsafe extern "system" {
    fn GetModuleHandleW(module_name: *const u16) -> *mut c_void;
    fn GetProcAddress(module: *mut c_void, procedure_name: *const u8) -> *mut c_void;
}

#[derive(Clone, Copy)]
pub struct FieldEntry {
    pub offset: usize,
    pub address: usize,
    pub is_static: bool,
}

#[derive(Clone, Copy)]
struct CachedField {
    key: u64,
    entry: FieldEntry,
    occupied: bool,
}

#[derive(Clone, Copy)]
struct CachedType {
    key: u64,
    size: usize,
    occupied: bool,
}

struct Cache {
    fields: [CachedField; MAX_CACHED_FIELDS],
    field_count: usize,
    types: [CachedType; MAX_CACHED_TYPES],
    type_count: usize,
}

impl Cache {
    const fn empty() -> Self {
        Self {
            fields: [CachedField {
                key: 0,
                entry: FieldEntry {
                    offset: 0,
                    address: 0,
                    is_static: false,
                },
                occupied: false,
            }; MAX_CACHED_FIELDS],
            field_count: 0,
            types: [CachedType {
                key: 0,
                size: 0,
                occupied: false,
            }; MAX_CACHED_TYPES],
            type_count: 0,
        }
    }
}

struct CacheCell(UnsafeCell<Cache>);
unsafe impl Sync for CacheCell {}

static STATE: AtomicU8 = AtomicU8::new(UNINITIALIZED);
static CACHE: CacheCell = CacheCell(UnsafeCell::new(Cache::empty()));

pub fn field(type_name: &[u8], field_name: &[u8]) -> Option<FieldEntry> {
    ensure_initialized();
    if STATE.load(Ordering::Acquire) != AVAILABLE {
        return None;
    }
    let key = hash_pair(type_name, field_name);
    let cache = unsafe { &*CACHE.0.get() };
    for cached in &cache.fields[..cache.field_count] {
        if cached.occupied && cached.key == key {
            return Some(cached.entry);
        }
    }
    None
}

pub fn type_size(type_name: &[u8]) -> Option<usize> {
    ensure_initialized();
    if STATE.load(Ordering::Acquire) != AVAILABLE {
        return None;
    }
    let key = hash_name(type_name);
    let cache = unsafe { &*CACHE.0.get() };
    for cached in &cache.types[..cache.type_count] {
        if cached.occupied && cached.key == key {
            return Some(cached.size);
        }
    }
    None
}

fn ensure_initialized() {
    loop {
        match STATE.load(Ordering::Acquire) {
            AVAILABLE | UNAVAILABLE => return,
            UNINITIALIZED => {
                if STATE
                    .compare_exchange(
                        UNINITIALIZED,
                        INITIALIZING,
                        Ordering::AcqRel,
                        Ordering::Acquire,
                    )
                    .is_ok()
                {
                    let available = unsafe { initialize_cache() };
                    STATE.store(
                        if available { AVAILABLE } else { UNAVAILABLE },
                        Ordering::Release,
                    );
                    return;
                }
            }
            INITIALIZING => spin_loop(),
            _ => return,
        }
    }
}

unsafe fn initialize_cache() -> bool {
    let module = unsafe { GetModuleHandleW(JVM_DLL_NAME.as_ptr()) };
    if module.is_null()
        || unsafe { GetProcAddress(module, VM_STRUCTS_SYMBOL.as_ptr()) }.is_null()
        || unsafe { GetProcAddress(module, VM_TYPES_SYMBOL.as_ptr()) }.is_null()
    {
        return false;
    }
    let entries = match exported_pointer(module, VM_STRUCTS_SYMBOL) {
        Some(value) => value,
        None => return false,
    };
    let offsets = match struct_offsets(module) {
        Some(value) => value,
        None => return false,
    };
    let cache = unsafe { &mut *CACHE.0.get() };
    let mut entry = entries;
    for _ in 0..MAX_ENTRIES {
        let current_type = match read_pointer(entry + offsets.type_name) {
            Some(value) if value != 0 => value,
            _ => break,
        };
        let current_field = match read_pointer(entry + offsets.field_name) {
            Some(value) if value != 0 => value,
            _ => break,
        };
        if is_wanted_field(current_type, current_field) {
            let key = hash_addresses(current_type, current_field);
            let is_static = unsafe { read_u8(entry + offsets.is_static) } != Some(0);
            let field_entry = FieldEntry {
                offset: unsafe { read_usize(entry + offsets.offset) }.unwrap_or(0),
                address: unsafe { read_usize(entry + offsets.address) }.unwrap_or(0),
                is_static,
            };
            if cache.field_count < MAX_CACHED_FIELDS
                && !contains_field(cache, key)
            {
                cache.fields[cache.field_count] = CachedField {
                    key,
                    entry: field_entry,
                    occupied: true,
                };
                cache.field_count += 1;
            }
        }
        entry = match entry.checked_add(offsets.stride) {
            Some(value) => value,
            None => break,
        };
    }

    let type_entries = match exported_pointer(module, VM_TYPES_SYMBOL) {
        Some(value) => value,
        None => return false,
    };
    let type_type_name = match exported_value(module, VM_TYPE_ENTRY_TYPE_NAME_OFFSET_SYMBOL) {
        Some(value) => value,
        None => return false,
    };
    let type_size = match exported_value(module, VM_TYPE_ENTRY_SIZE_OFFSET_SYMBOL) {
        Some(value) => value,
        None => return false,
    };
    let type_stride = match exported_value(module, VM_TYPE_ENTRY_ARRAY_STRIDE_SYMBOL) {
        Some(value) => value,
        None => return false,
    };
    if type_stride < 24 || type_stride > 4096 {
        return false;
    }
    let mut type_entry = type_entries;
    for _ in 0..MAX_ENTRIES {
        let current_type = match read_pointer(type_entry + type_type_name) {
            Some(value) if value != 0 => value,
            _ => break,
        };
        if is_wanted_type(current_type) {
            let key = hash_address(current_type);
            if cache.type_count < MAX_CACHED_TYPES && !contains_type(cache, key) {
                cache.types[cache.type_count] = CachedType {
                    key,
                    size: unsafe { read_usize(type_entry + type_size) }.unwrap_or(0),
                    occupied: true,
                };
                cache.type_count += 1;
            }
        }
        type_entry = match type_entry.checked_add(type_stride) {
            Some(value) => value,
            None => break,
        };
    }
    true
}

unsafe fn struct_offsets(module: *mut c_void) -> Option<StructOffsets> {
    let offsets = StructOffsets {
        type_name: unsafe { exported_value(module, VM_STRUCT_ENTRY_TYPE_NAME_OFFSET_SYMBOL) }?,
        field_name: unsafe { exported_value(module, VM_STRUCT_ENTRY_FIELD_NAME_OFFSET_SYMBOL) }?,
        is_static: unsafe { exported_value(module, VM_STRUCT_ENTRY_IS_STATIC_OFFSET_SYMBOL) }?,
        offset: unsafe { exported_value(module, VM_STRUCT_ENTRY_OFFSET_OFFSET_SYMBOL) }?,
        address: unsafe { exported_value(module, VM_STRUCT_ENTRY_ADDRESS_OFFSET_SYMBOL) }?,
        stride: unsafe { exported_value(module, VM_STRUCT_ENTRY_ARRAY_STRIDE_SYMBOL) }?,
    };
    if valid_offsets(&offsets) {
        Some(offsets)
    } else {
        None
    }
}

fn is_wanted_field(type_address: usize, field_address: usize) -> bool {
    WANTED_FIELDS.iter().any(|(type_name, field_name)| {
        equals_c_string(type_address, type_name) && equals_c_string(field_address, field_name)
    })
}

fn is_wanted_type(type_address: usize) -> bool {
    WANTED_TYPES
        .iter()
        .any(|type_name| equals_c_string(type_address, type_name))
}

fn contains_field(cache: &Cache, key: u64) -> bool {
    cache.fields[..cache.field_count]
        .iter()
        .any(|entry| entry.occupied && entry.key == key)
}

fn contains_type(cache: &Cache, key: u64) -> bool {
    cache.types[..cache.type_count]
        .iter()
        .any(|entry| entry.occupied && entry.key == key)
}

fn hash_pair(type_name: &[u8], field_name: &[u8]) -> u64 {
    let mut hash = hash_name(type_name);
    hash = hash_bytes(hash, field_name.strip_suffix(&[0]).unwrap_or(field_name));
    hash
}

fn hash_addresses(type_address: usize, field_address: usize) -> u64 {
    let mut hash = FNV_OFFSET;
    hash = hash_c_string(hash, type_address);
    hash_c_string(hash, field_address)
}

fn hash_address(type_address: usize) -> u64 {
    hash_c_string(FNV_OFFSET, type_address)
}

fn hash_name(name: &[u8]) -> u64 {
    hash_bytes(FNV_OFFSET, name.strip_suffix(&[0]).unwrap_or(name))
}

fn hash_bytes(mut hash: u64, bytes: &[u8]) -> u64 {
    for byte in bytes {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    hash
}

fn hash_c_string(mut hash: u64, address: usize) -> u64 {
    let mut index = 0;
    while index < MAX_NAME_LENGTH {
        match unsafe { read_u8(address + index) } {
            Some(0) => break,
            Some(byte) => {
                hash ^= byte as u64;
                hash = hash.wrapping_mul(FNV_PRIME);
            }
            None => break,
        }
        index += 1;
    }
    hash
}

fn exported_pointer(module: *mut c_void, name: &[u8]) -> Option<usize> {
    let address = unsafe { GetProcAddress(module, name.as_ptr()) };
    if address.is_null() {
        return None;
    }
    unsafe { read_usize(address as usize) }
}

fn exported_value(module: *mut c_void, name: &[u8]) -> Option<usize> {
    exported_pointer(module, name)
}

fn valid_offsets(offsets: &StructOffsets) -> bool {
    offsets.type_name < 4096
        && offsets.field_name < 4096
        && offsets.is_static < 4096
        && offsets.offset < 4096
        && offsets.address < 4096
        && offsets.stride >= 48
        && offsets.stride <= 4096
}

fn equals_c_string(address: usize, expected: &[u8]) -> bool {
    let expected = expected.strip_suffix(&[0]).unwrap_or(expected);
    if address == 0 || expected.len() >= MAX_NAME_LENGTH {
        return false;
    }
    for (index, byte) in expected.iter().enumerate() {
        if unsafe { read_u8(address + index) } != Some(*byte) {
            return false;
        }
    }
    unsafe { read_u8(address + expected.len()) == Some(0) }
}

unsafe fn read_u8(address: usize) -> Option<u8> {
    if address < 0x10000 {
        return None;
    }
    Some(unsafe { ptr::read_unaligned(address as *const u8) })
}

unsafe fn read_usize(address: usize) -> Option<usize> {
    if address < 0x10000 {
        return None;
    }
    Some(unsafe { ptr::read_unaligned(address as *const usize) })
}

unsafe fn read_pointer(address: usize) -> Option<usize> {
    unsafe { read_usize(address) }
}

struct StructOffsets {
    type_name: usize,
    field_name: usize,
    is_static: usize,
    offset: usize,
    address: usize,
    stride: usize,
}

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
