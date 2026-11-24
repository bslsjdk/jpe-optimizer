use std::panic::{catch_unwind, AssertUnwindSafe};

const ABI_VERSION: u32 = 1;
const STATUS_OK: i32 = 0;
const STATUS_INVALID_ARGUMENT: i32 = 1;
const STATUS_CPP_FAILURE: i32 = 2;
const STATUS_RUST_PANIC: i32 = 3;

unsafe extern "C" {
    fn jpe_cpp_add_f32(input: *const f32, output: *mut f32, length: u32) -> i32;
}

#[no_mangle]
pub extern "C" fn jpe_runtime_abi_version() -> u32 {
    ABI_VERSION
}

/// Bulk FFI boundary. No Minecraft object or Rust-owned allocation crosses it.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_add_f32(
    input: *const f32,
    output: *mut f32,
    length: u32,
) -> i32 {
    if length == 0 {
        return STATUS_OK;
    }
    if input.is_null() || output.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }

    let result = catch_unwind(AssertUnwindSafe(|| {
        // The caller must provide readable/writable buffers containing length floats.
        let input_slice = unsafe { std::slice::from_raw_parts(input, length as usize) };
        unsafe { jpe_cpp_add_f32(input_slice.as_ptr(), output, length) }
    }));

    match result {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exports_expected_abi_version() {
        assert_eq!(jpe_runtime_abi_version(), 1);
    }

    #[test]
    fn bulk_boundary_calls_cpp_kernel() {
        let input = [0.0_f32, 1.5, -2.0, f32::INFINITY];
        let mut output = [0.0_f32; 4];
        let status = unsafe {
            jpe_runtime_add_f32(input.as_ptr(), output.as_mut_ptr(), input.len() as u32)
        };
        assert_eq!(status, STATUS_OK);
        assert_eq!(output[0], 0.0);
        assert_eq!(output[1], 3.0);
        assert_eq!(output[2], -4.0);
        assert_eq!(output[3], f32::INFINITY);
    }

    #[test]
    fn zero_length_accepts_null_buffers() {
        assert_eq!(unsafe { jpe_runtime_add_f32(std::ptr::null(), std::ptr::null_mut(), 0) }, STATUS_OK);
    }

    #[test]
    fn rejects_null_non_empty_buffers() {
        assert_eq!(unsafe { jpe_runtime_add_f32(std::ptr::null(), std::ptr::null_mut(), 1) }, STATUS_INVALID_ARGUMENT);
    }
}
