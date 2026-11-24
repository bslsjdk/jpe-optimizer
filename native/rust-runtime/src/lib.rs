use std::panic::{catch_unwind, AssertUnwindSafe};

const ABI_VERSION: u32 = 1;
const STATUS_OK: i32 = 0;
const STATUS_INVALID_ARGUMENT: i32 = 1;
const STATUS_CPP_FAILURE: i32 = 2;
const STATUS_RUST_PANIC: i32 = 3;

unsafe extern "C" {
    fn jpe_cpp_add_f32(input: *const f32, output: *mut f32, length: u32) -> i32;
    fn jpe_cpp_perlin_add_volume(
        permutation: *const u8,
        block_x: *const i32,
        block_y: *const i32,
        block_z: *const i32,
        size_x: u32,
        size_y: u32,
        size_z: u32,
        offset_x: f64,
        offset_y: f64,
        offset_z: f64,
        xz_scale: f64,
        y_scale: f64,
        amplitude: f32,
        output: *mut f32,
        output_length: u64,
        flags: u32,
    ) -> i32;
}

#[no_mangle]
pub extern "C" fn jpe_runtime_abi_version() -> u32 {
    ABI_VERSION
}

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
    let result = catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_add_f32(input, output, length)
    }));
    match result {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

/// Bulk Perlin volume operation. Caller owns all buffers for the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_perlin_add_volume(
    permutation: *const u8,
    block_x: *const i32,
    block_y: *const i32,
    block_z: *const i32,
    size_x: u32,
    size_y: u32,
    size_z: u32,
    offset_x: f64,
    offset_y: f64,
    offset_z: f64,
    xz_scale: f64,
    y_scale: f64,
    amplitude: f32,
    output: *mut f32,
    output_length: u64,
    flags: u32,
) -> i32 {
    if size_x == 0 || size_y == 0 || size_z == 0
        || permutation.is_null() || block_x.is_null() || block_y.is_null()
        || block_z.is_null() || output.is_null()
    {
        return STATUS_INVALID_ARGUMENT;
    }
    let expected = match (size_x as u64)
        .checked_mul(size_y as u64)
        .and_then(|n| n.checked_mul(size_z as u64)) {
            Some(value) => value,
            None => return STATUS_INVALID_ARGUMENT,
        };
    if expected != output_length {
        return STATUS_INVALID_ARGUMENT;
    }

    let result = catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_perlin_add_volume(
            permutation, block_x, block_y, block_z,
            size_x, size_y, size_z,
            offset_x, offset_y, offset_z, xz_scale, y_scale,
            amplitude, output, output_length, flags,
        )
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
    fn perlin_volume_rejects_bad_output_length() {
        let permutation = [0_u8; 256];
        let axis = [0_i32];
        let mut output = [0.0_f32; 2];
        let status = unsafe {
            jpe_cpp_perlin_add_volume(
                permutation.as_ptr(), axis.as_ptr(), axis.as_ptr(), axis.as_ptr(),
                1, 1, 1, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0,
                output.as_mut_ptr(), 2, 0,
            )
        };
        assert_eq!(status, STATUS_INVALID_ARGUMENT);
    }

    #[test]
    fn zero_length_add_accepts_null_buffers() {
        assert_eq!(unsafe { jpe_runtime_add_f32(std::ptr::null(), std::ptr::null_mut(), 0) }, STATUS_OK);
    }

    #[test]
    fn rejects_null_non_empty_buffers() {
        assert_eq!(unsafe { jpe_runtime_add_f32(std::ptr::null(), std::ptr::null_mut(), 1) }, STATUS_INVALID_ARGUMENT);
    }
}
