use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex, OnceLock};
use std::thread;
use std::time::Duration;


// Terrain worldgen bindings: the native C++ generator and its streaming cache.
// Kept in its own module on purpose -- `lib.rs` is already large, and these
// bindings have their own unsafe surface (raw chunk pointers, borrowed slices)
// that deserves to be read in isolation.
pub mod terrain_ffi;

const ABI_VERSION: u32 = 8;
const STATUS_OK: i32 = 0;
const STATUS_INVALID_ARGUMENT: i32 = 1;
const STATUS_CPP_FAILURE: i32 = 2;
const STATUS_RUST_PANIC: i32 = 3;
const JOB_PENDING: i32 = 1;
const JOB_RUNNING: i32 = 2;
const JOB_COMPLETE: i32 = 0;
const JOB_FAILED: i32 = 3;
const JOB_CANCELLED: i32 = 4;
const MAX_VOLUME_ELEMENTS: usize = 1024 * 1024;
const QUEUE_CAPACITY: usize = 16;
const MAX_TRACKED_JOBS: usize = 32;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct JpeDensityNode {
    pub opcode: u32,
    pub input_a: u32,
    pub input_b: u32,
    pub reserved: u32,
    pub immediate: f64,

}



#[repr(C)]
#[derive(Clone, Copy)]
pub struct JpeBlendingDataView {
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub min_section_y: i32,
    pub section_count: u32,
    pub density_columns: [*const f32; 16],
}

const DENSITY_NO_INPUT: u32 = u32::MAX;
const DENSITY_CONST: u32 = 1;
const DENSITY_X: u32 = 2;
const DENSITY_Y: u32 = 3;
const DENSITY_Z: u32 = 4;
const DENSITY_ADD: u32 = 5;
const DENSITY_MUL: u32 = 6;
const DENSITY_OPAQUE: u32 = 7;
const DENSITY_SUB: u32 = 8;
const DENSITY_INPUT: u32 = 9;
const DENSITY_DIV: u32 = 10;
const DENSITY_CLAMP: u32 = 11;
const DENSITY_MIN: u32 = 12;
const DENSITY_MAX: u32 = 13;
const DENSITY_NEGATE: u32 = 14;
const DENSITY_ABS: u32 = 15;
const DENSITY_SQUARE: u32 = 16;
const DENSITY_CUBE: u32 = 17;
const DENSITY_SQRT: u32 = 18;
const DENSITY_LOG: u32 = 19;
const DENSITY_RECIPROCAL: u32 = 20;
const DENSITY_SIGN: u32 = 21;
const DENSITY_SQUEEZE: u32 = 22;
const DENSITY_LEAKY_RELU: u32 = 23;
const DENSITY_GRADIENT_CLAMPED_X: u32 = 24;
const DENSITY_GRADIENT_CLAMPED_Y: u32 = 25;
const DENSITY_GRADIENT_CLAMPED_Z: u32 = 26;
const DENSITY_NOISE: u32 = 27;
const DENSITY_NOISE_TRANSPOSED: u32 = 28;
const DENSITY_INTERPOLATED: u32 = 29;
const DENSITY_RANGE_CHOICE: u32 = 30;
const DENSITY_LERP: u32 = 31;
const DENSITY_POW: u32 = 32;
const DENSITY_SPLINE: u32 = 33;
const DENSITY_SLICE_X: u32 = 34;
const DENSITY_SLICE_Y: u32 = 35;
const DENSITY_SLICE_Z: u32 = 36;
const DENSITY_ROUND: u32 = 37;
const DENSITY_INTERVAL_SELECT: u32 = 38;
const DENSITY_FIND_TOP_SURFACE: u32 = 39;
const DENSITY_DISTANCE_TO_POINT: u32 = 40;
const DENSITY_BLEND_DENSITY: u32 = 41;
const DENSITY_END_ISLAND: u32 = 42;
const SPLINE_CONSTANT: u32 = 0;
const SPLINE_MULTIPOINT: u32 = 1;
#[cfg(test)]
static CACHE_TEST_SEQUENCE: AtomicU64 = AtomicU64::new(1);



unsafe extern "C" {
    fn jpe_cpp_density_graph_compile(
        nodes: *const JpeDensityNode, node_count: u32, root_node: u32,
        ir_version: u32, configuration_fingerprint: u64,
    ) -> u64;
    fn jpe_cpp_density_graph_evaluate(
        graph_handle: u64,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        output: *mut f32, output_length: u64,
    ) -> i32;
    fn jpe_cpp_density_graph_release(graph_handle: u64) -> i32;
    fn jpe_cpp_density_graph_evaluate_with_inputs(
        graph_handle: u64, size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        inputs: *const f32, input_count: u32, output: *mut f32, output_length: u64,
    ) -> i32;
    fn jpe_cpp_density_graph_evaluate_multi_with_inputs(
        graph_handle: u64, size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        inputs: *const f32, input_count: u32,
        output_nodes: *const u32, output_count: u32,
        outputs: *mut f32, output_length: u64,
    ) -> i32;
    fn jpe_cpp_density_graph_load_cache(path: *const std::ffi::c_char, cache_key: *const std::ffi::c_char) -> u64;
    fn jpe_cpp_density_graph_save_cache(graph_handle: u64, path: *const std::ffi::c_char, cache_key: *const std::ffi::c_char) -> i32;
    fn jpe_cpp_blend_density_volume(
        height_data: *const JpeBlendingDataView, height_data_count: u32,
        density_data: *const JpeBlendingDataView, density_data_count: u32,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        noise_values: *const f32, outputs: *mut f32, output_count: u64,
    ) -> i32;
    fn jpe_cpp_aquifer_xoroshiro_offsets(
        seed_lo: u64, seed_hi: u64,
        grid_x: i32, grid_y: i32, grid_z: i32,
        offset_x: *mut i32, offset_y: *mut i32, offset_z: *mut i32,
    ) -> i32;
    fn jpe_cpp_aquifer_legacy_offsets(
        factory_seed: u64,
        grid_x: i32, grid_y: i32, grid_z: i32,
        offset_x: *mut i32, offset_y: *mut i32, offset_z: *mut i32,
    ) -> i32;
    fn jpe_cpp_aquifer_grid_centers(
        random_mode: u32, seed_lo: u64, seed_hi: u64,
        min_grid_x: i32, min_grid_y: i32, min_grid_z: i32,
        grid_size_x: u32, grid_size_z: u32, cell_count: u32,
        packed_locations: *mut u64,
    ) -> i32;
    fn jpe_cpp_aquifer_nearest_four(
        random_mode:u32, seed_lo:u64, seed_hi:u64,
        block_x:*const i32, block_y:*const i32, block_z:*const i32, sample_count:u32,
        center_x:*mut i32, center_y:*mut i32, center_z:*mut i32, squared_distances:*mut i32,
    )->i32;


}

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_xoroshiro_offsets(
    seed_lo: u64, seed_hi: u64,
    grid_x: i32, grid_y: i32, grid_z: i32,
    offset_x: *mut i32, offset_y: *mut i32, offset_z: *mut i32,
) -> i32 {
    unsafe {
        jpe_cpp_aquifer_xoroshiro_offsets(
            seed_lo, seed_hi, grid_x, grid_y, grid_z,
            offset_x, offset_y, offset_z,
        )
    }
}

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_legacy_offsets(
    factory_seed: u64,
    grid_x: i32, grid_y: i32, grid_z: i32,
    offset_x: *mut i32, offset_y: *mut i32, offset_z: *mut i32,
) -> i32 {
    unsafe {
        jpe_cpp_aquifer_legacy_offsets(
            factory_seed, grid_x, grid_y, grid_z,
            offset_x, offset_y, offset_z,
        )
    }
}



#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_grid_centers(
    random_mode: u32, seed_lo: u64, seed_hi: u64,
    min_grid_x: i32, min_grid_y: i32, min_grid_z: i32,
    grid_size_x: u32, grid_size_z: u32, cell_count: u32,
    packed_locations: *mut u64,
) -> i32 {
    if random_mode > 1 || grid_size_x == 0 || grid_size_z == 0
        || cell_count == 0 || cell_count as usize > MAX_VOLUME_ELEMENTS
        || packed_locations.is_null()
        || (grid_size_x as u64) * (grid_size_z as u64) > cell_count as u64
        || (cell_count as u64) % ((grid_size_x as u64) * (grid_size_z as u64)) != 0 {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_aquifer_grid_centers(
            random_mode, seed_lo, seed_hi,
            min_grid_x, min_grid_y, min_grid_z,
            grid_size_x, grid_size_z, cell_count, packed_locations,
        )
    }
}

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_nearest_four(
    random_mode:u32,seed_lo:u64,seed_hi:u64,
    block_x:*const i32,block_y:*const i32,block_z:*const i32,sample_count:u32,
    center_x:*mut i32,center_y:*mut i32,center_z:*mut i32,squared_distances:*mut i32,
)->i32 {
    if random_mode>1 || sample_count==0 || sample_count as usize>MAX_VOLUME_ELEMENTS
       || block_x.is_null() || block_y.is_null() || block_z.is_null()
       || center_x.is_null() || center_y.is_null() || center_z.is_null() || squared_distances.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe { jpe_cpp_aquifer_nearest_four(random_mode,seed_lo,seed_hi,
        block_x,block_y,block_z,sample_count,center_x,center_y,center_z,squared_distances) }
}

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_compile(
    nodes: *const JpeDensityNode, node_count: u32, root_node: u32,
    ir_version: u32, configuration_fingerprint: u64,
) -> u64 {
    unsafe {
        jpe_cpp_density_graph_compile(nodes, node_count, root_node, ir_version, configuration_fingerprint)
    }
}

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_evaluate(
    graph_handle: u64,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    output: *mut f32, output_length: u64,
) -> i32 {
    unsafe {
        jpe_cpp_density_graph_evaluate(
            graph_handle, size_x, size_y, size_z,
            min_x, min_y, min_z, step_x, step_y, step_z,
            output, output_length,
        )
    }
}

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_evaluate_with_inputs(
    graph_handle: u64,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    inputs: *const f32, input_count: u32,
    output: *mut f32, output_length: u64,
) -> i32 {
    if input_count > 256 || (input_count > 0 && inputs.is_null()) || output.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_density_graph_evaluate_with_inputs(
            graph_handle, size_x, size_y, size_z,
            min_x, min_y, min_z, step_x, step_y, step_z,
            inputs, input_count, output, output_length,
        )
    }
}

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_evaluate_multi_with_inputs(
    graph_handle: u64,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    inputs: *const f32, input_count: u32,
    output_nodes: *const u32, output_count: u32,
    outputs: *mut f32, output_length: u64,
) -> i32 {
    if input_count > 256 || output_count == 0 || output_count > 256
        || (input_count > 0 && inputs.is_null())
        || output_nodes.is_null() || outputs.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }
    let volume = (size_x as u64).checked_mul(size_y as u64)
        .and_then(|v| v.checked_mul(size_z as u64));
    let Some(volume) = volume else { return STATUS_INVALID_ARGUMENT; };
    let staged_count = input_count as u64 + output_count as u64;
    if volume == 0 || volume > 1_048_576
        || volume.checked_mul(output_count as u64) != Some(output_length)
        || volume.checked_mul(staged_count).map_or(true, |n| n > 16_777_216) {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_density_graph_evaluate_multi_with_inputs(
            graph_handle, size_x, size_y, size_z,
            min_x, min_y, min_z, step_x, step_y, step_z,
            inputs, input_count, output_nodes, output_count, outputs, output_length,
        )
    }
}


#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_blend_density_volume(
    height_data: *const JpeBlendingDataView, height_data_count: u32,
    density_data: *const JpeBlendingDataView, density_data_count: u32,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    noise_values: *const f32, outputs: *mut f32, output_count: u64,
) -> i32 {
    if height_data_count > 256 || density_data_count > 256
        || size_x == 0 || size_y == 0 || size_z == 0
        || step_x == 0 || step_y == 0 || step_z == 0
        || (height_data_count > 0 && height_data.is_null())
        || (density_data_count > 0 && density_data.is_null())
        || noise_values.is_null() || outputs.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }
    let Some(volume) = (size_x as u64).checked_mul(size_y as u64)
        .and_then(|v| v.checked_mul(size_z as u64)) else {
        return STATUS_INVALID_ARGUMENT;
    };
    if volume == 0 || volume > MAX_VOLUME_ELEMENTS as u64 || volume != output_count {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_blend_density_volume(
            height_data, height_data_count, density_data, density_data_count,
            size_x, size_y, size_z, min_x, min_y, min_z, step_x, step_y, step_z,
            noise_values, outputs, output_count,
        )
    }
}

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_release(graph_handle: u64) -> i32 {
    unsafe { jpe_cpp_density_graph_release(graph_handle) }
}


#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_load_cache(
    path: *const std::ffi::c_char, cache_key: *const std::ffi::c_char,
) -> u64 {
    unsafe { jpe_cpp_density_graph_load_cache(path, cache_key) }
}

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_save_cache(
    graph_handle: u64, path: *const std::ffi::c_char, cache_key: *const std::ffi::c_char,
) -> i32 {
    unsafe { jpe_cpp_density_graph_save_cache(graph_handle, path, cache_key) }
}

#[repr(C)]
pub struct JpePerlinLayer {
    pub permutation: *const u8,
    pub offset_x: f64,
    pub offset_y: f64,
    pub offset_z: f64,
    pub xz_scale: f64,
    pub y_scale: f64,
    pub amplitude: f32,
    pub fudge_y_scale: f64,
    pub flags: u32,
    pub reserved: u32,
    /// Un-folded layer frequency; only the shifted-noise kernel reads it.
    pub frequency: f64,
}

#[repr(C)]
pub struct JpeNoisePlan {
    pub layers: *const JpePerlinLayer,
    pub layer_count: u32,
    pub flags: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct JpeSplineNode {
    pub kind: u32,
    pub coordinate: u32,
    pub constant: f32,
    pub first_point: u32,
    pub point_count: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct JpeSplinePoint {
    pub location: f32,
    pub derivative: f32,
    pub child: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct JpeIntervalSelect {
    pub input_node: u32,
    pub first_threshold: u32,
    pub threshold_count: u32,
    pub first_branch: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct JpeDistancePoint {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub metric: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct JpeBlendInput {
    pub active: u32,
    pub base_cell_x: i32,
    pub base_cell_y: i32,
    pub base_cell_z: i32,
    pub cells_x: u32,
    pub cells_y: u32,
    pub cells_z: u32,
    pub fixed_density: *const f32,
    pub density: *const f32,
    pub density_mask: *const u8,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct JpeGraphProgram {
    pub noise_plans: *const JpeNoisePlan,
    pub noise_plan_count: u32,
    pub splines: *const JpeSplineNode,
    pub spline_count: u32,
    pub spline_points: *const JpeSplinePoint,
    pub spline_point_count: u32,
    pub interval_selects: *const JpeIntervalSelect,
    pub interval_select_count: u32,
    pub interval_thresholds: *const f32,
    pub interval_threshold_count: u32,
    pub interval_branches: *const u32,
    pub interval_branch_count: u32,
    pub distance_points: *const JpeDistancePoint,
    pub distance_point_count: u32,
    pub blend: *const JpeBlendInput,
    pub end_island_permutation: *const u8,
}

impl JpeGraphProgram {
    pub fn empty() -> Self {
        Self {
            noise_plans: core::ptr::null(),
            noise_plan_count: 0,
            splines: core::ptr::null(),
            spline_count: 0,
            spline_points: core::ptr::null(),
            spline_point_count: 0,
            interval_selects: core::ptr::null(),
            interval_select_count: 0,
            interval_thresholds: core::ptr::null(),
            interval_threshold_count: 0,
            interval_branches: core::ptr::null(),
            interval_branch_count: 0,
            distance_points: core::ptr::null(),
            distance_point_count: 0,
            blend: core::ptr::null(),
            end_island_permutation: core::ptr::null(),
        }
    }
}

unsafe extern "C" {
    fn jpe_cpp_density_graph_handle_count() -> u32;
}

/// Live compiled-handle count, for leak checks across long generation runs.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_handle_count() -> u32 {
    unsafe { jpe_cpp_density_graph_handle_count() }
}

unsafe extern "C" {
    fn jpe_cpp_density_graph_compile_program(
        nodes: *const JpeDensityNode, node_count: u32, root_node: u32,
        ir_version: u32, configuration_fingerprint: u64,
        program: *const JpeGraphProgram,
    ) -> u64;
}

/// Compiles a graph together with every payload table it references.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_compile_program(
    nodes: *const JpeDensityNode, node_count: u32, root_node: u32,
    ir_version: u32, configuration_fingerprint: u64,
    program: *const JpeGraphProgram,
) -> u64 {
    unsafe {
        jpe_cpp_density_graph_compile_program(
            nodes, node_count, root_node, ir_version, configuration_fingerprint, program,
        )
    }
}

unsafe extern "C" {
    fn jpe_cpp_density_graph_compile_with_noise(
        nodes: *const JpeDensityNode, node_count: u32, root_node: u32,
        ir_version: u32, configuration_fingerprint: u64,
        noise_plans: *const JpeNoisePlan, noise_plan_count: u32,
    ) -> u64;
}

/// Compiles a graph whose `DENSITY_NOISE` nodes reference `noise_plans` by slot.
/// The C++ side copies every layer and permutation, so the caller may release
/// all source buffers once this returns.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_compile_with_noise(
    nodes: *const JpeDensityNode, node_count: u32, root_node: u32,
    ir_version: u32, configuration_fingerprint: u64,
    noise_plans: *const JpeNoisePlan, noise_plan_count: u32,
) -> u64 {
    unsafe {
        jpe_cpp_density_graph_compile_with_noise(
            nodes, node_count, root_node, ir_version, configuration_fingerprint,
            noise_plans, noise_plan_count,
        )
    }
}


unsafe extern "C" {
    fn jpe_cpp_perlin_add_stack_fused(
        layers: *const JpePerlinLayer, layer_count: u32,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        output: *mut f32, output_length: u64, flags: u32,
    ) -> i32;
    fn jpe_cpp_perlin_add_stack(
        layers: *const JpePerlinLayer, layer_count: u32,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        output: *mut f32, output_length: u64, flags: u32,
    ) -> i32;
    fn jpe_cpp_add_f32(input: *const f32, output: *mut f32, length: u32) -> i32;
    fn jpe_cpp_perlin_add_volume(
        permutation: *const u8,
        size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
        offset_x: f64, offset_y: f64, offset_z: f64,
        xz_scale: f64, y_scale: f64, amplitude: f32,
        output: *mut f32, output_length: u64, flags: u32,
    ) -> i32;
    fn jpe_cpp_perlin_add_volume_scalar_cached_y(
        permutation: *const u8,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        offset_x: f64, offset_y: f64, offset_z: f64,
        xz_scale: f64, y_scale: f64, amplitude: f32,
        output: *mut f32, output_length: u64, flags: u32,
    ) -> i32;
    fn jpe_cpp_perlin_avx2_supported() -> i32;
    fn jpe_cpp_perlin_add_volume_uncached_y(
        permutation: *const u8,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        offset_x: f64, offset_y: f64, offset_z: f64,
        xz_scale: f64, y_scale: f64, amplitude: f32,
        output: *mut f32, output_length: u64, flags: u32,
    ) -> i32;
    fn jpe_cpp_perlin_add_volume_cached_xy(
        permutation: *const u8,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        offset_x: f64, offset_y: f64, offset_z: f64,
        xz_scale: f64, y_scale: f64, amplitude: f32,
        output: *mut f32, output_length: u64, flags: u32,
    ) -> i32;
    fn jpe_cpp_perlin_add_volume_uncached_xy(
        permutation: *const u8,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        offset_x: f64, offset_y: f64, offset_z: f64,
        xz_scale: f64, y_scale: f64, amplitude: f32,
        output: *mut f32, output_length: u64, flags: u32,
    ) -> i32;
}

struct JobState {
    cancelled: AtomicBool,
    status: Mutex<JobStatus>,
    changed: Condvar,
}

enum JobStatus { Pending, Running, Complete, Failed(i32), Cancelled }

struct PerlinTask {
    state: Arc<JobState>,
    permutation: Vec<u8>,
    output_address: usize,
    output_length: usize,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    offset_x: f64, offset_y: f64, offset_z: f64,
    xz_scale: f64, y_scale: f64, amplitude: f32, flags: u32,
}

struct PerlinStackTask {
    state: Arc<JobState>,
    // Borrowed from the caller's shared FFM arena. The Java NativeJob lease
    // keeps descriptors and every permutation alive until this task is terminal.
    layers_address: usize,
    layer_count: u32,
    output_address: usize,
    output_length: usize,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    flags: u32,
}

enum NativeTask {
    Single(PerlinTask),
    Stack(PerlinStackTask),
}

struct Scheduler {
    sender: mpsc::SyncSender<NativeTask>,
    jobs: Mutex<HashMap<u64, Arc<JobState>>>,
    next_id: AtomicU64,
}

static SCHEDULER: OnceLock<Scheduler> = OnceLock::new();
static CONFIGURED_WORKERS: AtomicUsize = AtomicUsize::new(0);
static WORKER_CONFIG_LOCK: Mutex<()> = Mutex::new(());

/// Configures the worker count. Zero selects the process-visible CPU count.
/// Repeating the active setting is safe after startup; changing it after the
/// scheduler starts is rejected because worker threads cannot be resized in place.
#[no_mangle]
pub extern "C" fn jpe_runtime_configure_workers(requested: u32) -> i32 {
    let _guard = WORKER_CONFIG_LOCK.lock().unwrap_or_else(|p| p.into_inner());
    let available = thread::available_parallelism().map(|n| n.get()).unwrap_or(1).max(1);
    let selected = if requested == 0 { available } else { (requested as usize).min(available).max(1) };
    if SCHEDULER.get().is_some() {
        let active = CONFIGURED_WORKERS.load(Ordering::Acquire);
        return if active == selected { selected.min(i32::MAX as usize) as i32 } else { -1 };
    }
    CONFIGURED_WORKERS.store(selected, Ordering::Release);
    selected.min(i32::MAX as usize) as i32
}

fn scheduler() -> &'static Scheduler {
    SCHEDULER.get_or_init(|| {
        let _guard = WORKER_CONFIG_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        let (sender, receiver) = mpsc::sync_channel::<NativeTask>(QUEUE_CAPACITY);
        let receiver = Arc::new(Mutex::new(receiver));
        let detected = thread::available_parallelism().map(|n| n.get()).unwrap_or(1).max(1);
        let configured = CONFIGURED_WORKERS.load(Ordering::Acquire);
        let workers = if configured == 0 { detected } else { configured.min(detected).max(1) };
        CONFIGURED_WORKERS.store(workers, Ordering::Release);
        for worker_index in 0..workers {
            let receiver = Arc::clone(&receiver);
            thread::Builder::new().name(format!("jpe-native-worldgen-{worker_index}"))
                .spawn(move || loop {
                    let task = {
                        let guard = receiver.lock().unwrap_or_else(|p| p.into_inner());
                        guard.recv()
                    };
                    let Ok(task) = task else { break };
                    let state = match &task {
                        NativeTask::Single(task) => Arc::clone(&task.state),
                        NativeTask::Stack(task) => Arc::clone(&task.state),
                    };
                    let mut status = lock_status(&state);
                    if state.cancelled.load(Ordering::Acquire) {
                        *status = JobStatus::Cancelled;
                        drop(status);
                        state.changed.notify_all();
                        continue;
                    }
                    *status = JobStatus::Running;
                    drop(status);
                    // The caller keeps the shared FFM output buffer leased until terminal status.
                    let result = catch_unwind(AssertUnwindSafe(|| unsafe {
                        match task {
                            NativeTask::Single(task) => jpe_cpp_perlin_add_volume(
                                task.permutation.as_ptr(),
                                task.size_x, task.size_y, task.size_z,
                                task.min_x, task.min_y, task.min_z,
                                task.step_x, task.step_y, task.step_z,
                                task.offset_x, task.offset_y, task.offset_z,
                                task.xz_scale, task.y_scale, task.amplitude,
                                task.output_address as *mut f32, task.output_length as u64, task.flags,
                            ),
                            NativeTask::Stack(task) => {
                                jpe_cpp_perlin_add_stack(
                                    task.layers_address as *const JpePerlinLayer, task.layer_count,
                                    task.size_x, task.size_y, task.size_z,
                                    task.min_x, task.min_y, task.min_z,
                                    task.step_x, task.step_y, task.step_z,
                                    task.output_address as *mut f32, task.output_length as u64, task.flags,
                                )
                            }
                        }
                    }));
                    let mut status = lock_status(&state);
                    // Running kernels are not interruptible; cancellation only blocks queued jobs.
                    *status = match result {
                        Ok(STATUS_OK) => JobStatus::Complete,
                        Ok(code) => JobStatus::Failed(code),
                        Err(_) => JobStatus::Failed(STATUS_RUST_PANIC),
                    };
                    drop(status);
                    state.changed.notify_all();
                })
                .expect("failed to start JPE native worker");
        }
        Scheduler { sender, jobs: Mutex::new(HashMap::new()), next_id: AtomicU64::new(1) }
    })
}

fn lock_status(state: &JobState) -> std::sync::MutexGuard<'_, JobStatus> {
    state.status.lock().unwrap_or_else(|p| p.into_inner())
}

#[no_mangle]
pub extern "C" fn jpe_runtime_abi_version() -> u32 { ABI_VERSION }

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_add_f32(input: *const f32, output: *mut f32, length: u32) -> i32 {
    if length == 0 { return STATUS_OK; }
    if input.is_null() || output.is_null() { return STATUS_INVALID_ARGUMENT; }
    match catch_unwind(AssertUnwindSafe(|| unsafe { jpe_cpp_add_f32(input, output, length) })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_perlin_add_volume(
    permutation: *const u8,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    offset_x: f64, offset_y: f64, offset_z: f64,
    xz_scale: f64, y_scale: f64, amplitude: f32,
    output: *mut f32, output_length: u64, flags: u32,
) -> i32 {
    if size_x == 0 || size_y == 0 || size_z == 0 || permutation.is_null() || output.is_null()
        || step_x == 0 || step_y == 0 || step_z == 0 {
        return STATUS_INVALID_ARGUMENT;
    }
    let expected = match (size_x as u64).checked_mul(size_y as u64).and_then(|n| n.checked_mul(size_z as u64)) {
        Some(v) => v, None => return STATUS_INVALID_ARGUMENT,
    };
    if expected != output_length || expected as u128 > MAX_VOLUME_ELEMENTS as u128 { return STATUS_INVALID_ARGUMENT; }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_perlin_add_volume(permutation, size_x, size_y, size_z,
            min_x, min_y, min_z, step_x, step_y, step_z,
            offset_x, offset_y, offset_z, xz_scale, y_scale, amplitude, output, output_length, flags)
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

/// Executes an ordered Perlin layer stack with one native boundary crossing.
/// The descriptor array and every 256-byte permutation remain borrowed for this call only.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_perlin_add_stack(
    layers: *const JpePerlinLayer, layer_count: u32,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    output: *mut f32, output_length: u64, flags: u32,
) -> i32 {
    if layers.is_null() || output.is_null() || layer_count == 0 || layer_count > 64
        || size_x == 0 || size_y == 0 || size_z == 0
        || step_x == 0 || step_y == 0 || step_z == 0 {
        return STATUS_INVALID_ARGUMENT;
    }
    let expected = match (size_x as u64).checked_mul(size_y as u64)
        .and_then(|n| n.checked_mul(size_z as u64)) {
        Some(value) => value,
        None => return STATUS_INVALID_ARGUMENT,
    };
    if expected != output_length || expected as usize > MAX_VOLUME_ELEMENTS {
        return STATUS_INVALID_ARGUMENT;
    }
    let descriptors = unsafe { slice::from_raw_parts(layers, layer_count as usize) };
    if descriptors.iter().any(|layer| layer.permutation.is_null()) {
        return STATUS_INVALID_ARGUMENT;
    }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_perlin_add_stack(
            layers, layer_count, size_x, size_y, size_z,
            min_x, min_y, min_z, step_x, step_y, step_z,
            output, output_length, flags,
        )
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

/// Submits an ordered Perlin stack without copying descriptors or permutations.
/// The caller must keep the descriptor array, each 256-byte permutation, and
/// output_buffer valid and exclusively reserved until the job reaches terminal
/// status and is released. Java enforces this with its NativeJob scratch lease.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_submit_perlin_stack(
    layers: *const JpePerlinLayer, layer_count: u32,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    output_buffer: *mut f32, output_length: u64, flags: u32,
) -> u64 {
    catch_unwind(AssertUnwindSafe(|| {
        if layers.is_null() || output_buffer.is_null() || layer_count == 0 || layer_count > 64
            || size_x == 0 || size_y == 0 || size_z == 0
            || step_x == 0 || step_y == 0 || step_z == 0 {
            return 0;
        }
        let expected = match (size_x as u64).checked_mul(size_y as u64)
            .and_then(|n| n.checked_mul(size_z as u64)) {
            Some(value) => value, None => return 0,
        };
        if expected != output_length || expected as usize > MAX_VOLUME_ELEMENTS { return 0; }
        let source = unsafe { slice::from_raw_parts(layers, layer_count as usize) };
        if source.iter().any(|layer| layer.permutation.is_null()) { return 0; }
        let task = PerlinStackTask {
            state: Arc::new(JobState {
                cancelled: AtomicBool::new(false),
                status: Mutex::new(JobStatus::Pending),
                changed: Condvar::new(),
            }),
            layers_address: layers as usize,
            layer_count,
            output_address: output_buffer as usize,
            output_length: output_length as usize,
            size_x, size_y, size_z,
            min_x, min_y, min_z, step_x, step_y, step_z, flags,
        };
        let runtime = scheduler();
        let id = runtime.next_id.fetch_add(1, Ordering::Relaxed);
        if id == 0 { return 0; }
        let mut jobs = runtime.jobs.lock().unwrap_or_else(|p| p.into_inner());
        if jobs.len() >= MAX_TRACKED_JOBS { return 0; }
        jobs.insert(id, Arc::clone(&task.state));
        if runtime.sender.try_send(NativeTask::Stack(task)).is_err() {
            jobs.remove(&id);
            return 0;
        }
        id
    })).unwrap_or(0)
}

/// Copies the permutation but borrows the caller's writable output buffer until terminal job status.
/// The caller must keep that buffer valid and exclusively reserved until the job is released.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_submit_perlin_volume(
    permutation: *const u8,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    offset_x: f64, offset_y: f64, offset_z: f64,
    xz_scale: f64, y_scale: f64, amplitude: f32,
    output_buffer: *mut f32, output_length: u64, flags: u32,
) -> u64 {
    catch_unwind(AssertUnwindSafe(|| {
        if size_x == 0 || size_y == 0 || size_z == 0 || permutation.is_null() || output_buffer.is_null()
            || step_x == 0 || step_y == 0 || step_z == 0 {
            return 0;
        }
        let expected = match (size_x as u64).checked_mul(size_y as u64).and_then(|n| n.checked_mul(size_z as u64)) {
            Some(v) => v, None => return 0,
        };
        if expected != output_length || expected as u128 > MAX_VOLUME_ELEMENTS as u128 { return 0; }
        let task = unsafe {
            PerlinTask {
                state: Arc::new(JobState {
                    cancelled: AtomicBool::new(false),
                    status: Mutex::new(JobStatus::Pending),
                    changed: Condvar::new(),
                }),
                permutation: slice::from_raw_parts(permutation, 256).to_vec(),
                min_x, min_y, min_z, step_x, step_y, step_z,
                output_address: output_buffer as usize,
                output_length: output_length as usize,
                size_x, size_y, size_z, offset_x, offset_y, offset_z, xz_scale, y_scale, amplitude, flags,
            }
        };
        let runtime = scheduler();
        let id = runtime.next_id.fetch_add(1, Ordering::Relaxed);
        if id == 0 { return 0; }
        let mut jobs = runtime.jobs.lock().unwrap_or_else(|p| p.into_inner());
        if jobs.len() >= MAX_TRACKED_JOBS { return 0; }
        jobs.insert(id, Arc::clone(&task.state));
        if runtime.sender.try_send(NativeTask::Single(task)).is_err() {
            jobs.remove(&id);
            return 0;
        }
        id
    })).unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn jpe_runtime_job_status(job_id: u64) -> i32 {
    let state = {
        let jobs = scheduler().jobs.lock().unwrap_or_else(|p| p.into_inner());
        let Some(state) = jobs.get(&job_id) else { return -1 };
        Arc::clone(state)
    };
    let status = lock_status(&state);
    let result = match &*status {
        JobStatus::Pending => JOB_PENDING,
        JobStatus::Running => JOB_RUNNING,
        JobStatus::Complete => JOB_COMPLETE,
        JobStatus::Failed(_) => JOB_FAILED,
        JobStatus::Cancelled => JOB_CANCELLED,
    };
    result
}

/// Waits for a job to finish, or returns its current status when the timeout expires.
#[no_mangle]
pub extern "C" fn jpe_runtime_wait_job(job_id: u64, timeout_nanos: u64) -> i32 {
    let state = {
        let jobs = scheduler().jobs.lock().unwrap_or_else(|p| p.into_inner());
        let Some(state) = jobs.get(&job_id) else { return -1 };
        Arc::clone(state)
    };
    let status = lock_status(&state);
    let (status, _) = state.changed.wait_timeout_while(
        status,
        Duration::from_nanos(timeout_nanos),
        |status| matches!(status, JobStatus::Pending | JobStatus::Running),
    ).unwrap_or_else(|poisoned| poisoned.into_inner());
    match &*status {
        JobStatus::Pending => JOB_PENDING,
        JobStatus::Running => JOB_RUNNING,
        JobStatus::Complete => JOB_COMPLETE,
        JobStatus::Failed(_) => JOB_FAILED,
        JobStatus::Cancelled => JOB_CANCELLED,
    }
}

#[no_mangle]
pub extern "C" fn jpe_runtime_cancel_job(job_id: u64) -> i32 {
    let state = {
        let jobs = scheduler().jobs.lock().unwrap_or_else(|p| p.into_inner());
        let Some(state) = jobs.get(&job_id) else { return STATUS_INVALID_ARGUMENT };
        Arc::clone(state)
    };
    let mut status = lock_status(&state);
    if matches!(*status, JobStatus::Pending) {
        state.cancelled.store(true, Ordering::Release);
        *status = JobStatus::Cancelled;
        drop(status);
        state.changed.notify_all();
    }
    // A running kernel cannot be interrupted safely while borrowing its output buffer.
    STATUS_OK
}

#[no_mangle]
pub extern "C" fn jpe_runtime_release_job(job_id: u64) -> i32 {
    let mut jobs = scheduler().jobs.lock().unwrap_or_else(|p| p.into_inner());
    let Some(state) = jobs.get(&job_id).cloned() else { return STATUS_INVALID_ARGUMENT };
    let status = lock_status(&state);
    let terminal = matches!(&*status, JobStatus::Complete | JobStatus::Failed(_) | JobStatus::Cancelled);
    drop(status);
    if !terminal { return JOB_RUNNING; }
    jobs.remove(&job_id);
    STATUS_OK
}

#[cfg(test)]
mod tests {

    #[test]
    fn perlin_layer_ffi_layout_matches_c_header() {
        use std::mem::{align_of, offset_of, size_of};
        assert_eq!(size_of::<JpePerlinLayer>(), 80);
        assert_eq!(align_of::<JpePerlinLayer>(), 8);
        assert_eq!(offset_of!(JpePerlinLayer, permutation), 0);
        assert_eq!(offset_of!(JpePerlinLayer, offset_x), 8);
        assert_eq!(offset_of!(JpePerlinLayer, offset_y), 16);
        assert_eq!(offset_of!(JpePerlinLayer, offset_z), 24);
        assert_eq!(offset_of!(JpePerlinLayer, xz_scale), 32);
        assert_eq!(offset_of!(JpePerlinLayer, y_scale), 40);
        assert_eq!(offset_of!(JpePerlinLayer, amplitude), 48);
        assert_eq!(offset_of!(JpePerlinLayer, fudge_y_scale), 56);
        assert_eq!(offset_of!(JpePerlinLayer, flags), 64);
        assert_eq!(offset_of!(JpePerlinLayer, reserved), 68);
        assert_eq!(offset_of!(JpePerlinLayer, frequency), 72);
    }

    use super::*;

    unsafe extern "C" {
        fn jpe_cpp_aquifer_xoroshiro_offsets(
            seed_lo: u64, seed_hi: u64,
            grid_x: i32, grid_y: i32, grid_z: i32,
            offset_x: *mut i32, offset_y: *mut i32, offset_z: *mut i32,
        ) -> i32;
        fn jpe_cpp_aquifer_legacy_offsets(
            factory_seed: u64,
            grid_x: i32, grid_y: i32, grid_z: i32,
            offset_x: *mut i32, offset_y: *mut i32, offset_z: *mut i32,
        ) -> i32;
    }

    #[cfg(feature = "density-graph-benchmark")]
    use std::time::Instant;

    #[cfg(feature = "density-graph-benchmark")]
    unsafe extern "C" {
        fn jpe_cpp_density_graph_evaluate_scalar_reference(
            graph_handle: u64,
            size_x: u32, size_y: u32, size_z: u32,
            min_x: i32, min_y: i32, min_z: i32,
            step_x: i32, step_y: i32, step_z: i32,
            output: *mut f32, output_length: u64,
        ) -> i32;
        fn jpe_cpp_density_graph_set_test_tile_limit(max_samples: u32);
        fn jpe_cpp_blend_density_volume_vector_sort_reference(
            height_data: *const JpeBlendingDataView, height_data_count: u32,
            density_data: *const JpeBlendingDataView, density_data_count: u32,
            size_x: u32, size_y: u32, size_z: u32,
            min_x: i32, min_y: i32, min_z: i32,
            step_x: i32, step_y: i32, step_z: i32,
            noise_values: *const f32, outputs: *mut f32, output_count: u64,
        ) -> i32;
    }

    #[test]
    fn aquifer_xoroshiro_offsets_match_audited_reference_vectors() {
        let cases = [
            (0x0123_4567_89ab_cdef_u64, 0xfedc_ba98_7654_3210_u64,
                1, 2, 3, [4, 0, 1]),
            (0_u64, 0_u64, 0, 0, 0, [0, 2, 7]),
            (0x9e37_79b9_7f4a_7c15_u64, 0x6a09_e667_f3bc_c909_u64,
                -17, -64, 33, [1, 1, 8]),
            (123_456_789_u64, 987_654_321_u64, -2, -1, -3, [5, 7, 2]),
        ];
        for (seed_lo, seed_hi, x, y, z, expected) in cases {
            let mut actual = [i32::MIN; 3];
            assert_eq!(unsafe {
                jpe_cpp_aquifer_xoroshiro_offsets(
                    seed_lo, seed_hi, x, y, z,
                    &mut actual[0], &mut actual[1], &mut actual[2],
                )
            }, 0);
            assert_eq!(actual, expected, "seed=({seed_lo:#x},{seed_hi:#x}) cell=({x},{y},{z})");
        }
        assert_eq!(unsafe {
            jpe_cpp_aquifer_xoroshiro_offsets(
                0, 0, 0, 0, 0,
                std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(),
            )
        }, -1);
    }

    #[test]
    fn aquifer_legacy_offsets_match_audited_reference_vectors() {
        let cases = [
            (0x0123_4567_89ab_cdef_u64, 1, 2, 3, [3, 3, 1]),
            (0_u64, 0, 0, 0, [0, 7, 9]),
            (987_654_321_u64, -17, -64, 33, [0, 0, 3]),
            (0x9e37_79b9_7f4a_7c15_u64, -2, -1, -3, [6, 1, 7]),
        ];
        for (factory_seed, x, y, z, expected) in cases {
            let mut actual = [i32::MIN; 3];
            assert_eq!(unsafe {
                jpe_cpp_aquifer_legacy_offsets(
                    factory_seed, x, y, z,
                    &mut actual[0], &mut actual[1], &mut actual[2],
                )
            }, 0);
            assert_eq!(actual, expected, "factory_seed={factory_seed:#x} cell=({x},{y},{z})");
        }
        assert_eq!(unsafe {
            jpe_cpp_aquifer_legacy_offsets(
                0, 0, 0, 0,
                std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(),
            )
        }, -1);
    }


    #[test]
    fn aquifer_nearest_four_matches_independent_vanilla_order_reference() {
        let cases=[
            (0_u32,0x0123_4567_89ab_cdef_u64,0xfedc_ba98_7654_3210_u64,
             [0,15,-1,-17],[-64,-1,0,23],[0,15,-1,-33]),
            (1_u32,0x9e37_79b9_7f4a_7c15_u64,0_u64,
             [1,16,-16,i32::MIN],[2,-13,25,i32::MAX],[3,-17,31,i32::MIN]),
        ];
        for(mode,seed_lo,seed_hi,xs,ys,zs) in cases {
            let mut ox=[0_i32;16]; let mut oy=[0_i32;16];
            let mut oz=[0_i32;16]; let mut od=[0_i32;16];
            assert_eq!(unsafe { jpe_cpp_aquifer_nearest_four(mode,seed_lo,seed_hi,
                xs.as_ptr(),ys.as_ptr(),zs.as_ptr(),4,ox.as_mut_ptr(),oy.as_mut_ptr(),
                oz.as_mut_ptr(),od.as_mut_ptr()) },STATUS_OK);
            for s in 0..4 {
                let ax=xs[s].wrapping_sub(5)>>4;
                let ay=ys[s].wrapping_add(1).div_euclid(12);
                let az=zs[s].wrapping_sub(5)>>4;
                let mut expected:Vec<(i32,i32,i32,i32)>=Vec::new();
                for dx in 0..=1 { for dy in -1..=1 { for dz in 0..=1 {
                    let gx=ax.wrapping_add(dx); let gy=ay.wrapping_add(dy); let gz=az.wrapping_add(dz);
                    let mut r=[0_i32;3];
                    let status=unsafe { if mode==0 {
                        jpe_cpp_aquifer_xoroshiro_offsets(seed_lo,seed_hi,gx,gy,gz,&mut r[0],&mut r[1],&mut r[2])
                    } else { jpe_cpp_aquifer_legacy_offsets(seed_lo,gx,gy,gz,&mut r[0],&mut r[1],&mut r[2]) }};
                    assert_eq!(status,0);
                    let cx=gx.wrapping_mul(16).wrapping_add(r[0]);
                    let cy=gy.wrapping_mul(12).wrapping_add(r[1]);
                    let cz=gz.wrapping_mul(16).wrapping_add(r[2]);
                    let x= cx.wrapping_sub(xs[s]); let y=cy.wrapping_sub(ys[s]); let z=cz.wrapping_sub(zs[s]);
                    let d=x.wrapping_mul(x).wrapping_add(y.wrapping_mul(y)).wrapping_add(z.wrapping_mul(z));
                    let pos=expected.iter().position(|v|d<=v.3).unwrap_or(expected.len());
                    expected.insert(pos,(cx,cy,cz,d)); if expected.len()>4 { expected.pop(); }
                }}}
                for i in 0..4 { let n=s*4+i;
                    assert_eq!((ox[n],oy[n],oz[n],od[n]),expected[i],"mode={mode} sample={s} neighbor={i}");
                }
            }
        }
        assert_eq!(unsafe { jpe_cpp_aquifer_nearest_four(2,0,0,std::ptr::null(),
            std::ptr::null(),std::ptr::null(),1,std::ptr::null_mut(),std::ptr::null_mut(),
            std::ptr::null_mut(),std::ptr::null_mut()) },STATUS_INVALID_ARGUMENT);
    }

    #[test]
    fn density_graph_noise_node_matches_the_stack_kernel_bit_for_bit() {
        // Deterministic permutation keeps the test reproducible across machines.
        let mut permutation = [0u8; 256];
        let mut state = 0x1234_5678u32;
        for slot in permutation.iter_mut() {
            state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            *slot = (state >> 24) as u8;
        }
        let layer = JpePerlinLayer {
            permutation: permutation.as_ptr(),
            offset_x: 1.5, offset_y: 2.5, offset_z: 3.5,
            xz_scale: 0.25, y_scale: 0.125,
            amplitude: 1.0, fudge_y_scale: 1.0,
            flags: 0, reserved: 0,
            frequency: 0.0,
        };
        let (sx, sy, sz) = (4u32, 8u32, 4u32);
        let (min_x, min_y, min_z) = (0i32, -64i32, 0i32);
        let (step_x, step_y, step_z) = (4i32, 8i32, 4i32);
        let volume = (sx * sy * sz) as usize;

        let mut reference = vec![0.0f32; volume];
        assert_eq!(unsafe {
            jpe_runtime_perlin_add_stack(
                &layer, 1, sx, sy, sz, min_x, min_y, min_z, step_x, step_y, step_z,
                reference.as_mut_ptr(), volume as u64, 0,
            )
        }, 0);

        let nodes = [
            JpeDensityNode {
                opcode: DENSITY_NOISE, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0,
            },
        ];
        let plan = JpeNoisePlan { layers: &layer, layer_count: 1, flags: 0 };
        let handle = unsafe {
            jpe_runtime_density_graph_compile_with_noise(
                nodes.as_ptr(), nodes.len() as u32, 0, 10, 0x5EED, &plan, 1,
            )
        };
        assert_ne!(handle, 0, "noise graph must compile");

        let mut actual = vec![0.0f32; volume];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, sx, sy, sz, min_x, min_y, min_z, step_x, step_y, step_z,
                actual.as_mut_ptr(), volume as u64,
            )
        }, 0);

        let mismatches = reference.iter().zip(actual.iter())
            .filter(|(expected, got)| expected.to_bits() != got.to_bits())
            .count();
        assert_eq!(mismatches, 0, "noise node must match the stack kernel bit for bit");

        // Identical payloads must reuse one handle.
        let again = unsafe {
            jpe_runtime_density_graph_compile_with_noise(
                nodes.as_ptr(), nodes.len() as u32, 0, 10, 0x5EED, &plan, 1,
            )
        };
        assert_eq!(again, handle, "identical noise payloads should reuse the handle");

        // A changed layer must not be served from the cache.
        let changed = JpePerlinLayer {
            permutation: permutation.as_ptr(),
            offset_x: 1.5, offset_y: 2.5, offset_z: 3.5,
            xz_scale: 0.25, y_scale: 0.125,
            amplitude: 0.5, fudge_y_scale: 1.0,
            flags: 0, reserved: 0,
            frequency: 0.0,
        };
        let changed_plan = JpeNoisePlan { layers: &changed, layer_count: 1, flags: 0 };
        let different = unsafe {
            jpe_runtime_density_graph_compile_with_noise(
                nodes.as_ptr(), nodes.len() as u32, 0, 10, 0x5EED, &changed_plan, 1,
            )
        };
        assert_ne!(different, handle, "a changed layer must produce a new handle");

        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(again) }, 0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(different) }, 0);
    }

    #[test]
    fn density_graph_transposed_noise_matches_pointwise_shift_b() {
        let mut permutation = [0u8; 256];
        let mut state = 0x0BAD_F00Du32;
        for slot in permutation.iter_mut() {
            state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            *slot = (state >> 24) as u8;
        }
        // ShiftB: xz_scale 0.25, y_scale 0.25, amplitude 4.0 as vanilla expands it.
        let layer = JpePerlinLayer {
            permutation: permutation.as_ptr(),
            offset_x: 0.0, offset_y: 0.0, offset_z: 0.0,
            xz_scale: 0.25, y_scale: 0.25,
            amplitude: 4.0, fudge_y_scale: 1.0,
            flags: 0, reserved: 0,
            frequency: 0.0,
        };
        let (sx, sy, sz) = (4u32, 8u32, 4u32);
        let (min_x, min_y, min_z) = (0i32, -64i32, 0i32);
        let (step_x, step_y, step_z) = (4i32, 8i32, 4i32);
        let volume = (sx * sy * sz) as usize;

        let nodes = [JpeDensityNode {
            opcode: DENSITY_NOISE_TRANSPOSED, input_a: 0,
            input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0,
        }];
        let plan = JpeNoisePlan { layers: &layer, layer_count: 1, flags: 0 };
        let handle = unsafe {
            jpe_runtime_density_graph_compile_with_noise(
                nodes.as_ptr(), 1, 0, 10, 0x51F7, &plan, 1,
            )
        };
        assert_ne!(handle, 0, "transposed noise graph must compile");

        let mut actual = vec![0.0f32; volume];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, sx, sy, sz, min_x, min_y, min_z, step_x, step_y, step_z,
                actual.as_mut_ptr(), volume as u64,
            )
        }, 0);

        // Independent reference: one 1x1x1 stack call per (x,z) column, at the
        // exact block coordinate the transposed plane would sample.
        let mut mismatches = 0usize;
        for z_index in 0..sz {
            for x_index in 0..sx {
                let block_z = min_z + (z_index as i32) * step_z;
                let block_x = min_x + (x_index as i32) * step_x;
                let mut point = [0.0f32; 1];
                assert_eq!(unsafe {
                    jpe_runtime_perlin_add_stack(
                        &layer, 1, 1, 1, 1,
                        block_z, block_x, 0, 1, 1, 1,
                        point.as_mut_ptr(), 1, 0,
                    )
                }, 0);
                for y_index in 0..sy {
                    let index = (((z_index * sx + x_index) * sy) + y_index) as usize;
                    if actual[index].to_bits() != point[0].to_bits() {
                        mismatches += 1;
                    }
                }
            }
        }
        assert_eq!(mismatches, 0, "every Y lane must equal the (z,x) plane sample");

        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
    }

    #[test]
    fn density_graph_interpolated_reproduces_a_linear_child_bit_for_bit() {
        // X is linear, and 4/8 are powers of two, so every alpha is exact and the
        // trilinear expansion must return the original X value at every voxel.
        let nodes = [
            JpeDensityNode {
                opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0,
            },
            JpeDensityNode {
                opcode: DENSITY_INTERPOLATED, input_a: 0,
                input_b: 4, reserved: 8, immediate: 0.0,
            },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 2, 1, 10, 0x1A7)
        };
        assert_ne!(handle, 0, "interpolated graph must compile");

        let (sx, sy, sz) = (8u32, 16u32, 8u32);
        let volume = (sx * sy * sz) as usize;
        let mut out = vec![0.0f32; volume];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, sx, sy, sz, 0, 0, 0, 1, 1, 1, out.as_mut_ptr(), volume as u64,
            )
        }, 0);

        let mut mismatches = 0usize;
        for z in 0..sz {
            for x in 0..sx {
                for y in 0..sy {
                    let index = (((z * sx + x) * sy) + y) as usize;
                    if out[index].to_bits() != (x as f32).to_bits() {
                        mismatches += 1;
                    }
                }
            }
        }
        assert_eq!(mismatches, 0, "linear child must survive trilinear expansion exactly");
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
    }

    #[test]
    fn density_graph_interpolated_passes_through_on_the_cell_grid() {
        // Step 4 on X/Z and 8 on Y is exactly the cell grid, so the node must
        // forward the child instead of interpolating again.
        let nodes = [
            JpeDensityNode {
                opcode: DENSITY_Y, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0,
            },
            JpeDensityNode {
                opcode: DENSITY_INTERPOLATED, input_a: 0,
                input_b: 4, reserved: 8, immediate: 0.0,
            },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 2, 1, 10, 0x2A2A)
        };
        assert_ne!(handle, 0);
        let (sx, sy, sz) = (2u32, 2u32, 2u32);
        let mut out = [0.0f32; 8];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, sx, sy, sz, 0, 0, 0, 4, 8, 4, out.as_mut_ptr(), 8,
            )
        }, 0);
        // Coordinates are (0,0,0), (0,8,0), (0,0,4), ... in z/x/y order.
        let expected = [0.0f32, 8.0, 0.0, 8.0, 0.0, 8.0, 0.0, 8.0];
        assert_eq!(out, expected, "cell-grid volumes must pass the child through");
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
    }

    #[test]
    fn density_graph_range_choice_matches_vanilla_half_open_interval() {
        let pack = |min: f32, max: f32| {
            let bits = ((min.to_bits() as u64) << 32) | max.to_bits() as u64;
            f64::from_bits(bits)
        };
        // in-range branch 10, out-of-range branch 20, bounds [0, 1).
        let nodes = [
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.5 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 10.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 20.0 },
            JpeDensityNode { opcode: DENSITY_RANGE_CHOICE, input_a: 0,
                input_b: 1, reserved: 2, immediate: pack(0.0, 1.0) },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 4, 3, 10, 0x3C3C)
        };
        assert_ne!(handle, 0);
        let mut out = [0.0f32; 1];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, 1, 1, 1, 0, 0, 0, 1, 1, 1, out.as_mut_ptr(), 1,
            )
        }, 0);
        assert_eq!(out[0], 10.0, "0.5 must select the in-range branch");

        // 1.0 is exclusive, NaN compares false: both must select the out-of-range branch.
        for probe in [1.0f64, f64::NAN] {
            let mut variant = nodes;
            variant[0].immediate = probe;
            let other = unsafe {
                jpe_runtime_density_graph_compile(variant.as_ptr(), 4, 3, 10, 0x3C3C)
            };
            assert_ne!(other, 0);
            let mut value = [0.0f32; 1];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate(
                    other, 1, 1, 1, 0, 0, 0, 1, 1, 1, value.as_mut_ptr(), 1,
                )
            }, 0);
            assert_eq!(value[0], 20.0, "probe {probe} must select the out-of-range branch");
            assert_eq!(unsafe { jpe_runtime_density_graph_release(other) }, 0);
        }
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
    }

    #[test]
    fn density_graph_lerp_and_pow_match_vanilla_arithmetic() {
        // lerp(alpha=0.25, first=2, second=6) = 2 + 0.25*4 = 3
        let lerp_nodes = [
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.25 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 2.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 6.0 },
            JpeDensityNode { opcode: DENSITY_LERP, input_a: 0,
                input_b: 1, reserved: 2, immediate: 0.0 },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(lerp_nodes.as_ptr(), 4, 3, 10, 0x4D4D)
        };
        assert_ne!(handle, 0);
        let mut out = [0.0f32; 1];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, 1, 1, 1, 0, 0, 0, 1, 1, 1, out.as_mut_ptr(), 1,
            )
        }, 0);
        assert_eq!(out[0], 3.0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);

        // pow(base=2, exponent=10) = 1024 through double Math.pow.
        let pow_nodes = [
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 2.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 10.0 },
            JpeDensityNode { opcode: DENSITY_POW, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0 },
        ];
        let power = unsafe {
            jpe_runtime_density_graph_compile(pow_nodes.as_ptr(), 3, 2, 10, 0x5E5E)
        };
        assert_ne!(power, 0);
        let mut value = [0.0f32; 1];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                power, 1, 1, 1, 0, 0, 0, 1, 1, 1, value.as_mut_ptr(), 1,
            )
        }, 0);
        assert_eq!(value[0], 1024.0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(power) }, 0);
    }

    #[test]
    fn density_graph_lerp_endpoints_are_exact() {
        // Vanilla's three lerp samplers all return an endpoint outright at alpha == 0
        // and at alpha == 1 instead of lerping to it. Recomputing the endpoint as
        // first + alpha*(second-first) is off by an ulp once the subtraction rounds:
        // 10.0f + (3.95f - 10.0f) is 3.9499998, not 3.95.
        let endpoint = |alpha: f64, first: f64, second: f64| {
            let nodes = [
                constant_node(alpha),
                constant_node(first),
                constant_node(second),
                JpeDensityNode { opcode: DENSITY_LERP, input_a: 0,
                    input_b: 1, reserved: 2, immediate: 0.0 },
            ];
            let handle = unsafe {
                jpe_runtime_density_graph_compile(nodes.as_ptr(), 4, 3, 10, 0x4E4E)
            };
            assert_ne!(handle, 0);
            let mut out = [0.0f32; 1];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate(
                    handle, 1, 1, 1, 0, 0, 0, 1, 1, 1, out.as_mut_ptr(), 1,
                )
            }, 0);
            assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
            out[0]
        };

        // The exact case observed in a live server: alpha 1 must give 3.95, not 3.9499998.
        assert_eq!(endpoint(1.0, 10.0, 3.95), 3.95f32);
        assert_eq!(endpoint(0.0, 10.0, 3.95), 10.0f32);
        assert_eq!(endpoint(1.0, 1.0e9, 1.0), 1.0f32);
        assert_eq!(endpoint(0.0, 1.0e9, 1.0), 1.0e9f32);
        // alpha == 0 must not form (second - first) either, so a NaN second stays out.
        assert_eq!(endpoint(0.0, 2.5, f64::NAN), 2.5f32);
        assert!(endpoint(1.0, 2.5, f64::NAN).is_nan());
        // Interior values still go through the lerp.
        assert_eq!(endpoint(0.5, 0.0, 4.0), 2.0f32);
    }

    fn constant_node(value: f64) -> JpeDensityNode {
        JpeDensityNode {
            opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
            input_b: DENSITY_NO_INPUT, reserved: 0, immediate: value,
        }
    }

    fn unary_node(opcode: u32, input: u32) -> JpeDensityNode {
        JpeDensityNode {
            opcode, input_a: input, input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0,
        }
    }

    fn binary_node(opcode: u32, a: u32, b: u32) -> JpeDensityNode {
        JpeDensityNode {
            opcode, input_a: a, input_b: b, reserved: 0, immediate: 0.0,
        }
    }

    fn coordinate_node(opcode: u32) -> JpeDensityNode {
        JpeDensityNode {
            opcode, input_a: DENSITY_NO_INPUT, input_b: DENSITY_NO_INPUT,
            reserved: 0, immediate: 0.0,
        }
    }

    fn evaluate_single(handle: u64, x: i32, y: i32, z: i32) -> f32 {
        let mut out = [0.0f32; 1];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(handle, 1, 1, 1, x, y, z, 1, 1, 1, out.as_mut_ptr(), 1)
        }, 0);
        out[0]
    }

    #[test]
    fn density_graph_spline_matches_cubic_interpolation_and_edge_extension() {
        // Graph: 0 = X, 1 = SPLINE(root 0). Spline: multipoint over [0, 10] with
        // constant children 5 and 15.
        let nodes = [coordinate_node(DENSITY_X), unary_node(DENSITY_SPLINE, 0)];
        let spline_nodes = [
            JpeSplineNode { kind: SPLINE_MULTIPOINT, coordinate: 0, constant: 0.0,
                first_point: 0, point_count: 2 },
            JpeSplineNode { kind: SPLINE_CONSTANT, coordinate: 0, constant: 5.0,
                first_point: 0, point_count: 0 },
            JpeSplineNode { kind: SPLINE_CONSTANT, coordinate: 0, constant: 15.0,
                first_point: 0, point_count: 0 },
        ];
        let points = [
            JpeSplinePoint { location: 0.0, derivative: 0.0, child: 1 },
            JpeSplinePoint { location: 10.0, derivative: 0.0, child: 2 },
        ];
        let mut program = JpeGraphProgram::empty();
        program.splines = spline_nodes.as_ptr();
        program.spline_count = spline_nodes.len() as u32;
        program.spline_points = points.as_ptr();
        program.spline_point_count = points.len() as u32;
        let handle = unsafe {
            jpe_runtime_density_graph_compile_program(nodes.as_ptr(), 2, 1, 10, 0x6001, &program)
        };
        assert_ne!(handle, 0, "spline graph must compile");
        assert_eq!(evaluate_single(handle, 0, 0, 0), 5.0);
        assert_eq!(evaluate_single(handle, 10, 0, 0), 15.0);
        // t = 0.5, both derivatives zero: lerp only.
        assert_eq!(evaluate_single(handle, 5, 0, 0), 10.0);
        // Outside the table a zero derivative returns the edge value untouched.
        assert_eq!(evaluate_single(handle, -5, 0, 0), 5.0);
        assert_eq!(evaluate_single(handle, 25, 0, 0), 15.0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);

        // Same table with a non-zero first derivative: the edge must extend linearly.
        let points_sloped = [
            JpeSplinePoint { location: 0.0, derivative: 2.0, child: 1 },
            JpeSplinePoint { location: 10.0, derivative: 0.0, child: 2 },
        ];
        let mut sloped = JpeGraphProgram::empty();
        sloped.splines = spline_nodes.as_ptr();
        sloped.spline_count = spline_nodes.len() as u32;
        sloped.spline_points = points_sloped.as_ptr();
        sloped.spline_point_count = points_sloped.len() as u32;
        let sloped_handle = unsafe {
            jpe_runtime_density_graph_compile_program(nodes.as_ptr(), 2, 1, 10, 0x6002, &sloped)
        };
        assert_ne!(sloped_handle, 0);
        assert_eq!(evaluate_single(sloped_handle, -5, 0, 0), 5.0 + 2.0 * -5.0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(sloped_handle) }, 0);
    }

    #[test]
    fn density_graph_round_matches_each_vanilla_mode() {
        // RoundFunction.roundToInteger over +-2.5 for floor/round/ceil/truncate.
        let expected_pos = [2.0f32, 3.0, 3.0, 2.0];
        let expected_neg = [-3.0f32, -2.0, -2.0, -2.0];
        for (mode, (positive, negative)) in expected_pos.iter().zip(expected_neg.iter()).enumerate() {
            for (input, expected) in [(2.5f64, *positive), (-2.5f64, *negative)] {
                let nodes = [constant_node(input), JpeDensityNode {
                    opcode: DENSITY_ROUND, input_a: 0, input_b: DENSITY_NO_INPUT,
                    reserved: mode as u32, immediate: 0.0,
                }];
                let handle = unsafe {
                    jpe_runtime_density_graph_compile(nodes.as_ptr(), 2, 1, 10, 0x6100 + mode as u64)
                };
                assert_ne!(handle, 0);
                assert_eq!(evaluate_single(handle, 0, 0, 0), expected,
                    "mode {mode} at input {input}");
                assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
            }
        }
    }

    #[test]
    fn density_graph_interval_select_uses_first_threshold_not_below() {
        let nodes = [
            coordinate_node(DENSITY_X),
            constant_node(1.0),
            constant_node(2.0),
            JpeDensityNode {
                opcode: DENSITY_INTERVAL_SELECT, input_a: 0, input_b: DENSITY_NO_INPUT,
                reserved: 0, immediate: 0.0,
            },
        ];
        let selects = [JpeIntervalSelect {
            input_node: 0, first_threshold: 0, threshold_count: 1, first_branch: 0,
        }];
        let thresholds = [0.0f32];
        let branches = [1u32, 2u32];
        let mut program = JpeGraphProgram::empty();
        program.interval_selects = selects.as_ptr();
        program.interval_select_count = 1;
        program.interval_thresholds = thresholds.as_ptr();
        program.interval_threshold_count = 1;
        program.interval_branches = branches.as_ptr();
        program.interval_branch_count = 2;
        let handle = unsafe {
            jpe_runtime_density_graph_compile_program(nodes.as_ptr(), 4, 3, 10, 0x6200, &program)
        };
        assert_ne!(handle, 0);
        assert_eq!(evaluate_single(handle, -1, 0, 0), 1.0);
        // selectSamplerIndex tests `value < threshold`, so the threshold itself
        // belongs to the next branch.
        assert_eq!(evaluate_single(handle, 0, 0, 0), 2.0);
        assert_eq!(evaluate_single(handle, 1, 0, 0), 2.0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
    }

    #[test]
    fn density_graph_slice_pins_the_requested_axis() {
        let nodes = [coordinate_node(DENSITY_Y), JpeDensityNode {
            opcode: DENSITY_SLICE_Y, input_a: 0, input_b: DENSITY_NO_INPUT,
            reserved: 0, immediate: 5.0,
        }];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 2, 1, 10, 0x6300)
        };
        assert_ne!(handle, 0);
        let mut out = [0.0f32; 8];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, 2, 2, 2, 0, 0, 0, 1, 1, 1, out.as_mut_ptr(), 8,
            )
        }, 0);
        assert!(out.iter().all(|value| *value == 5.0),
            "every lane must read the pinned Y, got {out:?}");
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
    }

    #[test]
    fn density_graph_distance_to_point_uses_each_metric() {
        let expected = [5.0f32, 25.0, 7.0, 4.0];
        for (metric, want) in expected.iter().enumerate() {
            let nodes = [JpeDensityNode {
                opcode: DENSITY_DISTANCE_TO_POINT, input_a: 0, input_b: DENSITY_NO_INPUT,
                reserved: 0, immediate: 0.0,
            }];
            let points = [JpeDistancePoint { x: 0, y: 0, z: 0, metric: metric as u32 }];
            let mut program = JpeGraphProgram::empty();
            program.distance_points = points.as_ptr();
            program.distance_point_count = 1;
            let handle = unsafe {
                jpe_runtime_density_graph_compile_program(nodes.as_ptr(), 1, 0, 10, 0x6400 + metric as u64, &program)
            };
            assert_ne!(handle, 0);
            assert_eq!(evaluate_single(handle, 3, 4, 0), *want, "metric {metric}");
            assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
        }
    }

    #[test]
    fn density_graph_find_top_surface_scans_down_to_the_first_positive_probe() {
        // density = 10 - Y, so the highest probe with density > 0 is y = 9.
        let nodes = [
            constant_node(10.0),
            coordinate_node(DENSITY_Y),
            binary_node(DENSITY_SUB, 0, 1),
            constant_node(32.0),
            JpeDensityNode {
                opcode: DENSITY_FIND_TOP_SURFACE, input_a: 2, input_b: 3,
                reserved: 1, immediate: 0.0,
            },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 5, 4, 10, 0x6500)
        };
        assert_ne!(handle, 0);
        assert_eq!(evaluate_single(handle, 0, 0, 0), 9.0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);

        // A lower bound above the surface clamps the answer instead of scanning.
        let mut clamped = nodes;
        clamped[4].immediate = 40.0;
        let clamped_handle = unsafe {
            jpe_runtime_density_graph_compile(clamped.as_ptr(), 5, 4, 10, 0x6501)
        };
        assert_ne!(clamped_handle, 0);
        assert_eq!(evaluate_single(clamped_handle, 0, 0, 0), 40.0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(clamped_handle) }, 0);
    }

    #[test]
    fn density_graph_blend_density_is_identity_when_inactive_and_overrides_on_fixed_data() {
        let nodes = [constant_node(0.25), unary_node(DENSITY_BLEND_DENSITY, 0)];
        // BLEND_DENSITY reads a program-level region, so it needs the program entry.
        let no_blend = JpeGraphProgram::empty();
        let handle = unsafe {
            jpe_runtime_density_graph_compile_program(nodes.as_ptr(), 2, 1, 10, 0x6600, &no_blend)
        };
        assert_ne!(handle, 0);
        assert_eq!(evaluate_single(handle, 0, 0, 0), 0.25);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);

        // One cell of fixed blending data replaces the child outright.
        let cells = 4usize;
        let total = cells * cells * cells;
        let mut fixed = vec![f32::MAX; total];
        let density = vec![0.0f32; total];
        let mask = vec![0u8; total];
        fixed[0] = 0.75;
        let blend = JpeBlendInput {
            active: 1,
            base_cell_x: 0, base_cell_y: 0, base_cell_z: 0,
            cells_x: cells as u32, cells_y: cells as u32, cells_z: cells as u32,
            fixed_density: fixed.as_ptr(), density: density.as_ptr(),
            density_mask: mask.as_ptr(),
        };
        let mut program = JpeGraphProgram::empty();
        program.blend = &blend;
        let blended = unsafe {
            jpe_runtime_density_graph_compile_program(nodes.as_ptr(), 2, 1, 10, 0x6601, &program)
        };
        assert_ne!(blended, 0);
        assert_eq!(evaluate_single(blended, 0, 0, 0), 0.75);
        // A voxel outside the region falls through to the child untouched.
        assert_eq!(evaluate_single(blended, 64, 0, 0), 0.25);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(blended) }, 0);
    }

    #[test]
    fn density_graph_end_island_is_y_invariant_and_within_the_declared_range() {
        let nodes = [coordinate_node(DENSITY_END_ISLAND)];
        let permutation: Vec<u8> = (0..256).map(|value| value as u8).collect();
        let mut program = JpeGraphProgram::empty();
        program.end_island_permutation = permutation.as_ptr();
        let handle = unsafe {
            jpe_runtime_density_graph_compile_program(nodes.as_ptr(), 1, 0, 10, 0x6700, &program)
        };
        assert_ne!(handle, 0, "end island needs a permutation");
        let mut out = [0.0f32; 4];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, 2, 2, 1, 0, 0, 0, 1, 1, 1, out.as_mut_ptr(), 4,
            )
        }, 0);
        // (height - 8) / 128 with height in [-100, 80].
        for value in out {
            assert!(value >= -0.84375 && value <= 0.5625, "out of range: {value}");
        }
        assert_eq!(out[0], out[1], "the value must not depend on Y");
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);

        // Without a permutation the compile must refuse rather than read nothing.
        let refused = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 1, 0, 10, 0x6701)
        };
        assert_eq!(refused, 0);
    }

    fn resident_bytes() -> usize {
        let statm = std::fs::read_to_string("/proc/self/statm").unwrap_or_default();
        let pages: usize = statm.split_whitespace().nth(1)
            .and_then(|value| value.parse().ok()).unwrap_or(0);
        pages * 4096
    }

    #[test]
    fn density_graph_sustained_worldgen_keeps_handles_and_memory_flat() {
        // An overworld-shaped graph: four noise stacks, a transposed shift, two
        // splines, range choice, squeeze, and a trilinear root. The root is
        // INTERPOLATED, so evaluating a full chunk only computes 4x48x4 cells --
        // exactly the vanilla cost model.
        let mut permutation = [0u8; 256];
        let mut state = 0x1234_5678u32;
        for slot in permutation.iter_mut() {
            state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            *slot = (state >> 24) as u8;
        }
        let layer = JpePerlinLayer {
            permutation: permutation.as_ptr(),
            offset_x: 1.5, offset_y: -2.5, offset_z: 0.75,
            xz_scale: 0.25, y_scale: 0.125,
            amplitude: 1.0, fudge_y_scale: 1.0,
            flags: 0, reserved: 0,
            frequency: 0.0,
        };
        let shift_b = JpePerlinLayer { xz_scale: 0.25, y_scale: 0.25, amplitude: 4.0, ..layer };
        let plans = [
            JpeNoisePlan { layers: &layer, layer_count: 1, flags: 0 },
            JpeNoisePlan { layers: &layer, layer_count: 1, flags: 0 },
            JpeNoisePlan { layers: &layer, layer_count: 1, flags: 0 },
            JpeNoisePlan { layers: &shift_b, layer_count: 1, flags: 0 },
        ];

        // Splines: root 0 is a 5-point multipoint over node 0, root 1 is 3-point
        // over node 1. Both use constant children.
        let spline_nodes = [
            JpeSplineNode { kind: SPLINE_MULTIPOINT, coordinate: 0, constant: 0.0, first_point: 0, point_count: 5 },
            JpeSplineNode { kind: SPLINE_MULTIPOINT, coordinate: 1, constant: 0.0, first_point: 5, point_count: 3 },
            JpeSplineNode { kind: SPLINE_CONSTANT, coordinate: 0, constant: 0.0, first_point: 0, point_count: 0 },
            JpeSplineNode { kind: SPLINE_CONSTANT, coordinate: 0, constant: 0.5, first_point: 0, point_count: 0 },
            JpeSplineNode { kind: SPLINE_CONSTANT, coordinate: 0, constant: -0.5, first_point: 0, point_count: 0 },
            JpeSplineNode { kind: SPLINE_CONSTANT, coordinate: 0, constant: 0.25, first_point: 0, point_count: 0 },
            JpeSplineNode { kind: SPLINE_CONSTANT, coordinate: 0, constant: -0.25, first_point: 0, point_count: 0 },
        ];
        let points = [
            JpeSplinePoint { location: -1.0, derivative: 0.0, child: 2 },
            JpeSplinePoint { location: -0.5, derivative: 0.0, child: 3 },
            JpeSplinePoint { location: 0.0, derivative: 0.0, child: 4 },
            JpeSplinePoint { location: 0.5, derivative: 0.0, child: 5 },
            JpeSplinePoint { location: 1.0, derivative: 0.0, child: 6 },
            JpeSplinePoint { location: -1.0, derivative: 0.0, child: 2 },
            JpeSplinePoint { location: 0.0, derivative: 0.0, child: 3 },
            JpeSplinePoint { location: 1.0, derivative: 0.0, child: 4 },
        ];

        let pack = |min: f32, max: f32| {
            f64::from_bits(((min.to_bits() as u64) << 32) | max.to_bits() as u64)
        };
        let nodes = [
            JpeDensityNode { opcode: DENSITY_NOISE, input_a: 0, input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_NOISE, input_a: 1, input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_NOISE, input_a: 2, input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_NOISE_TRANSPOSED, input_a: 3, input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_SPLINE, input_a: 0, input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_SPLINE, input_a: 1, input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            coordinate_node(DENSITY_Y),
            constant_node(0.5),
            binary_node(DENSITY_MUL, 5, 7),
            binary_node(DENSITY_ADD, 4, 8),
            constant_node(1.0),
            constant_node(0.0),
            JpeDensityNode { opcode: DENSITY_RANGE_CHOICE, input_a: 9, input_b: 10, reserved: 11, immediate: pack(0.0, 1.0) },
            binary_node(DENSITY_ADD, 2, 3),
            binary_node(DENSITY_MUL, 12, 13),
            constant_node(-0.1),
            binary_node(DENSITY_ADD, 14, 15),
            unary_node(DENSITY_SQUEEZE, 16),
            JpeDensityNode { opcode: DENSITY_INTERPOLATED, input_a: 17, input_b: 4, reserved: 8, immediate: 0.0 },
        ];

        let mut program = JpeGraphProgram::empty();
        program.noise_plans = plans.as_ptr();
        program.noise_plan_count = plans.len() as u32;
        program.splines = spline_nodes.as_ptr();
        program.spline_count = spline_nodes.len() as u32;
        program.spline_points = points.as_ptr();
        program.spline_point_count = points.len() as u32;

        let root = nodes.len() as u32 - 1;
        let first = unsafe {
            jpe_runtime_density_graph_compile_program(
                nodes.as_ptr(), nodes.len() as u32, root, 10, 0x9001, &program,
            )
        };
        assert_ne!(first, 0, "sustained graph must compile");
        let baseline_handles = unsafe { jpe_runtime_density_graph_handle_count() };

        let (sx, sy, sz) = (16u32, 384u32, 16u32);
        let volume = (sx * sy * sz) as usize;
        let mut out = vec![0.0f32; volume];
        let mut reference = vec![0.0f32; volume];

        // First chunk becomes the determinism reference.
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                first, sx, sy, sz, 0, -64, 0, 1, 1, 1, reference.as_mut_ptr(), volume as u64,
            )
        }, 0);

        let before = resident_bytes();
        let chunks = 200u32;
        let start = std::time::Instant::now();
        for index in 0..chunks {
            let chunk_x = (index % 16) as i32 * 16;
            let chunk_z = (index / 16) as i32 * 16;
            // Recompiling the identical program must hand back the same handle.
            let handle = unsafe {
                jpe_runtime_density_graph_compile_program(
                    nodes.as_ptr(), nodes.len() as u32, root, 10, 0x9001, &program,
                )
            };
            assert_eq!(handle, first, "identical programs must reuse one handle");
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate(
                    handle, sx, sy, sz, chunk_x, -64, chunk_z, 1, 1, 1,
                    out.as_mut_ptr(), volume as u64,
                )
            }, 0);
        }
        let elapsed = start.elapsed();
        let after = resident_bytes();
        let handles = unsafe { jpe_runtime_density_graph_handle_count() };

        // Determinism: the origin chunk must reproduce bit for bit.
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                first, sx, sy, sz, 0, -64, 0, 1, 1, 1, out.as_mut_ptr(), volume as u64,
            )
        }, 0);
        assert_eq!(out, reference, "repeated evaluation must be bit-identical");

        let per_chunk = elapsed.as_secs_f64() * 1000.0 / chunks as f64;
        println!(
            "JPE_SUSTAINED chunks={chunks} total_ms={:.2} ms_per_chunk={:.3} handles={handles} rss_delta_kb={}",
            elapsed.as_secs_f64() * 1000.0,
            per_chunk,
            (after as i64 - before as i64) / 1024
        );
        // The handle table is process-global and cargo runs tests in parallel, so
        // other cases can add and release handles while this one loops. A real leak
        // here would show up as roughly one handle per chunk, so a small tolerance
        // still separates "flat" from "leaking".
        assert!(handles <= baseline_handles + 8,
            "handle count grew with chunks: {baseline_handles} -> {handles}");
        assert!(after <= before + 8 * 1024 * 1024, "resident memory grew by more than 8 MiB");
        assert_eq!(unsafe { jpe_runtime_density_graph_release(first) }, 0);
    }

    #[test]
    fn density_graph_compiles_caches_and_evaluates_a_full_volume() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_Y, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 2.0 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0,
                input_b: 2, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_MUL, input_a: 3,
                input_b: 1, reserved: 0, immediate: 0.0 },
        ];
        let first = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4 , 10, 0x1234)
        };
        let second = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4 , 10, 0x1234)
        };
        assert_ne!(first, 0);
        assert_eq!(first, second, "identical graph/config should reuse the cached handle");

        let mut output = [0.0_f32; 4];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                first, 2, 2, 1, 10, 3, 0, 1, 1, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, 0);
        assert_eq!(output, [36.0, 48.0, 39.0, 52.0]);

        // A small volume also checks the same z/x/y layout and coordinate semantics.
        let mut tail_output = [0.0_f32; 33];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                first, 3, 11, 1, 10, 3, 0, 1, 1, 1,
                tail_output.as_mut_ptr(), tail_output.len() as u64,
            )
        }, 0);
        for x in 0..3 {
            for y in 0..11 {
                let index = x * 11 + y;
                assert_eq!(tail_output[index], ((10 + x) as f32 + 2.0) * (3 + y) as f32);
            }
        }

        assert_eq!(unsafe { jpe_runtime_density_graph_release(first) }, 0);
        // The second compile retained the same cached plan.
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                second, 1, 1, 1, 10, 3, 0, 1, 1, 1,
                output.as_mut_ptr(), 1,
            )
        }, 0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(second) }, 0);
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                first, 1, 1, 1, 0, 0, 0, 1, 1, 1,
                output.as_mut_ptr(), 1,
            )
        }, 2);
    }


    #[test]
    fn native_density_plan_cache_reloads_without_java_ir_transfer() {
        use std::ffi::CString;
        let path = std::env::temp_dir().join(format!(
            "jpe-density-plan-cache-{}-{}.bin", std::process::id(),
            CACHE_TEST_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        let path_string = CString::new(path.to_string_lossy().as_bytes()).unwrap();
        let key = CString::new("mc26.3|loader|modset|overworld|strict").unwrap();
        let nodes = [
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 4.0 },
            JpeDensityNode { opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0 },
        ];
        let first = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 3, 2 , 10, 0xCAC0_E001)
        };
        assert_ne!(first, 0);
        assert_eq!(unsafe { jpe_cpp_density_graph_save_cache(first, path_string.as_ptr(), key.as_ptr()) }, 0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(first) }, STATUS_OK);

        let loaded = unsafe { jpe_cpp_density_graph_load_cache(path_string.as_ptr(), key.as_ptr()) };
        assert_ne!(loaded, 0, "C++ should load its persisted plan directly");
        let mut output = [0.0_f32; 1];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                loaded, 1, 1, 1, 3, 0, 0, 1, 1, 1,
                output.as_mut_ptr(), 1,
            )
        }, STATUS_OK);
        assert_eq!(output[0], 7.0);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(loaded) }, STATUS_OK);

        let wrong_key = CString::new("different-modpack-identity").unwrap();
        assert_eq!(unsafe { jpe_cpp_density_graph_load_cache(path_string.as_ptr(), wrong_key.as_ptr()) }, 0,
            "a different worldgen identity must never reuse this plan");

        let mut damaged = std::fs::read(&path).unwrap();
        let middle = damaged.len() / 2;
        damaged[middle] ^= 0x20;
        std::fs::write(&path, damaged).unwrap();
        assert_eq!(unsafe { jpe_cpp_density_graph_load_cache(path_string.as_ptr(), key.as_ptr()) }, 0,
            "corrupt native plan cache must be rejected");
        let _ = std::fs::remove_file(path);
    }

    #[test]
    fn experimental_opaque_node_propagates_nan_without_rejecting_known_graph() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 4.0 },
            JpeDensityNode { opcode: DENSITY_OPAQUE, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 1, immediate: f64::NAN },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0 },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 2 , 10, 0xE771)
        };
        assert_ne!(handle, 0, "experimental graph should reach the native compiler");
        let mut output = [0.0_f32; 2];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, 2, 1, 1, 0, 0, 0, 1, 1, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert!(output.iter().all(|value| value.is_nan()),
            "opaque result must be visible as NaN, not replaced by a guessed value");
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }

    #[test]
    fn density_graph_rounds_every_intermediate_operation_to_f32() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 16_777_216.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: -16_777_216.0 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 2,
                input_b: 3, reserved: 0, immediate: 0.0 },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4 , 10, 0xF32)
        };
        assert_ne!(handle, 0);
        let mut output = [1.0_f32; 1];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, 1, 1, 1, 0, 0, 0, 1, 1, 1,
                output.as_mut_ptr(), 1,
            )
        }, STATUS_OK);
        assert_eq!(output[0], 0.0, "16,777,216 + 1 must round to f32 before the next add");
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }


    #[test]
    fn density_graph_tiled_volume_matches_float32_scalar_reference() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_Y, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_Z, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 2.0 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0,
                input_b: 3, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_MUL, input_a: 4,
                input_b: 1, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.25 },
            JpeDensityNode { opcode: DENSITY_MUL, input_a: 2,
                input_b: 6, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 5,
                input_b: 7, reserved: 0, immediate: 0.0 },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 8 , 10, 0x71_1E)
        };
        assert_ne!(handle, 0);
        // One complete Overworld DensityVolume for a 16x16 chunk:
        // five X/Z lattice points span block coordinates 0..16 at step 4,
        // and 49 Y points span -64..320 at step 8.
        // This is the actual whole-chunk density workload, not a synthetic cube.
        let (sx, sy, sz) = (5usize, 49usize, 5usize);
        let mut output = vec![0.0_f32; sx * sy * sz];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, sx as u32, sy as u32, sz as u32,
                0, -64, 0, 4, 8, 4,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);

        for z in 0..sz {
            for x in 0..sx {
                for y in 0..sy {
                    let index = (z * sx + x) * sy + y;
                    let world_x = (x as i32 * 4) as f32;
                    let world_y = (-64 + y as i32 * 8) as f32;
                    let world_z = (z as i32 * 4) as f32;
                    let shifted_x = world_x + 2.0;
                    let horizontal = shifted_x * world_y;
                    let vertical = world_z * 0.25;
                    let expected = horizontal + vertical;
                    assert_eq!(output[index], expected, "sample mismatch at x={x}, y={y}, z={z}");
                }
            }
        }

        #[cfg(feature = "density-graph-benchmark")]
        {
            let mut scalar_output = vec![0.0_f32; output.len()];
            assert_eq!(unsafe {
                jpe_cpp_density_graph_evaluate_scalar_reference(
                    handle, sx as u32, sy as u32, sz as u32,
                    0, -64, 0, 4, 8, 4,
                    scalar_output.as_mut_ptr(), scalar_output.len() as u64,
                )
            }, STATUS_OK);
            assert_eq!(output, scalar_output, "tiled and scalar evaluators must be bit-identical");
    
            for _ in 0..2 {
                assert_eq!(unsafe {
                    jpe_runtime_density_graph_evaluate(
                        handle, sx as u32, sy as u32, sz as u32,
                        0, -64, 0, 4, 8, 4,
                        output.as_mut_ptr(), output.len() as u64,
                    )
                }, STATUS_OK);
                assert_eq!(unsafe {
                    jpe_cpp_density_graph_evaluate_scalar_reference(
                        handle, sx as u32, sy as u32, sz as u32,
                        0, -64, 0, 4, 8, 4,
                        scalar_output.as_mut_ptr(), scalar_output.len() as u64,
                    )
                }, STATUS_OK);
            }
            let mut tiled_ms = Vec::with_capacity(9);
            let mut scalar_ms = Vec::with_capacity(9);
            for _ in 0..9 {
                let start = Instant::now();
                for _ in 0..4 {
                    assert_eq!(unsafe {
                        jpe_runtime_density_graph_evaluate(
                            handle, sx as u32, sy as u32, sz as u32,
                            0, -64, 0, 4, 8, 4,
                            output.as_mut_ptr(), output.len() as u64,
                        )
                    }, STATUS_OK);
                }
                tiled_ms.push(start.elapsed().as_secs_f64() * 250.0);
    
                let start = Instant::now();
                for _ in 0..4 {
                    assert_eq!(unsafe {
                        jpe_cpp_density_graph_evaluate_scalar_reference(
                            handle, sx as u32, sy as u32, sz as u32,
                            0, -64, 0, 4, 8, 4,
                            scalar_output.as_mut_ptr(), scalar_output.len() as u64,
                        )
                    }, STATUS_OK);
                }
                scalar_ms.push(start.elapsed().as_secs_f64() * 250.0);
            }
            tiled_ms.sort_by(f64::total_cmp);
            scalar_ms.sort_by(f64::total_cmp);
            let tiled_median = tiled_ms[4];
            let scalar_median = scalar_ms[4];
            println!(
                "JPE_DENSITY_GRAPH_TILE_BENCH dims={}x{}x{} nodes={} tiled_median_ms={:.3} scalar_median_ms={:.3} speedup={:.3}x",
                sx, sy, sz, nodes.len(), tiled_median, scalar_median, scalar_median / tiled_median
            );

            // Same-process ABBA comparison isolates the tile ceiling from CI-run
            // variance. Both paths execute the same graph and all 1,225 chunk samples.
            let mut tile_512_ms = Vec::with_capacity(21);
            let mut tile_1225_ms = Vec::with_capacity(21);
            let mut tile_output = vec![0.0_f32; output.len()];
            for round in 0..51 {
                let order = if round % 2 == 0 { [512_u32, 1225_u32] } else { [1225_u32, 512_u32] };
                for tile_limit in order {
                    unsafe { jpe_cpp_density_graph_set_test_tile_limit(tile_limit); }
                    let start = Instant::now();
                    for _ in 0..32 {
                        assert_eq!(unsafe {
                            jpe_runtime_density_graph_evaluate(
                                handle, sx as u32, sy as u32, sz as u32,
                                0, -64, 0, 4, 8, 4,
                                tile_output.as_mut_ptr(), tile_output.len() as u64,
                            )
                        }, STATUS_OK);
                    }
                    let per_call_ms = start.elapsed().as_secs_f64() * 1000.0 / 32.0;
                    if tile_limit == 512 {
                        tile_512_ms.push(per_call_ms);
                    } else {
                        tile_1225_ms.push(per_call_ms);
                    }
                    assert_eq!(tile_output, scalar_output,
                        "tile limit {tile_limit} must preserve exact full-chunk results");
                }
            }
            unsafe { jpe_cpp_density_graph_set_test_tile_limit(0); }
            tile_512_ms.sort_by(f64::total_cmp);
            tile_1225_ms.sort_by(f64::total_cmp);
            let median_512 = tile_512_ms[10];
            let median_1225 = tile_1225_ms[10];
            println!(
                "JPE_DENSITY_GRAPH_TILE_SIZE_ABBA dims={}x{}x{} nodes={} tile512_median_ms={:.6} tile1225_median_ms={:.6} speedup_1225_over_512={:.3}x",
                sx, sy, sz, nodes.len(), median_512, median_1225, median_512 / median_1225
            );
    
    
        }

        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }

    #[cfg(feature = "density-graph-benchmark")]
    #[test]
    fn density_graph_representative_164_node_tile_size_abba_benchmark() {
        use std::time::Instant;

        // Match the captured vanilla Overworld graph's 164-node inventory:
        // 11 constants, 60 arithmetic instructions, and 93 cache/context/opaque
        // boundaries represented by opaque nodes. This avoids an unrealistic
        // 162-ADD chain while retaining the real node-count scratch pressure.
        let mut nodes = Vec::with_capacity(164);
        for index in 0..11 {
            nodes.push(JpeDensityNode {
                opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0,
                immediate: if index == 0 { 0.0 } else { index as f64 * 0.125 },
            });
        }
        let mut previous = 0_u32;
        for index in 0..15 {
            nodes.push(JpeDensityNode {
                opcode: DENSITY_ADD, input_a: previous,
                input_b: (index % 11) as u32, reserved: 0, immediate: 0.0,
            });
            previous = (nodes.len() - 1) as u32;
        }
        for _ in 0..4 {
            nodes.push(JpeDensityNode {
                opcode: DENSITY_MUL, input_a: previous,
                input_b: 0, reserved: 0, immediate: 0.0,
            });
            previous = (nodes.len() - 1) as u32;
        }
        for index in 0..5 {
            nodes.push(JpeDensityNode {
                opcode: DENSITY_MIN, input_a: previous,
                input_b: (index % 11) as u32, reserved: 0, immediate: 0.0,
            });
            previous = (nodes.len() - 1) as u32;
        }
        for index in 0..4 {
            nodes.push(JpeDensityNode {
                opcode: DENSITY_MAX, input_a: previous,
                input_b: (index % 11) as u32, reserved: 0, immediate: 0.0,
            });
            previous = (nodes.len() - 1) as u32;
        }
        for index in 0..12 {
            nodes.push(JpeDensityNode {
                opcode: DENSITY_ADD, input_a: previous,
                input_b: (index % 11) as u32, reserved: 0, immediate: 0.0,
            });
            previous = (nodes.len() - 1) as u32;
        }
        for index in 0..15 {
            nodes.push(JpeDensityNode {
                opcode: DENSITY_MUL, input_a: previous,
                input_b: (index % 11) as u32, reserved: 0, immediate: 0.0,
            });
            previous = (nodes.len() - 1) as u32;
        }
        for _ in 0..5 {
            nodes.push(JpeDensityNode {
                opcode: DENSITY_CLAMP, input_a: previous,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0,
            });
            previous = (nodes.len() - 1) as u32;
        }
        while nodes.len() < 164 {
            nodes.push(JpeDensityNode {
                opcode: DENSITY_OPAQUE, input_a: previous,
                input_b: DENSITY_NO_INPUT, reserved: 1, immediate: f64::NAN,
            });
            previous = (nodes.len() - 1) as u32;
        }
        assert_eq!(nodes.len(), 164);
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32,
                163, 10, 0x1640_1225)
        };
        assert_ne!(handle, 0);

        const SX: usize = 5;
        const SY: usize = 49;
        const SZ: usize = 5;
        let mut output = vec![0.0_f32; SX * SY * SZ];
        let mut scalar = vec![0.0_f32; output.len()];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, SX as u32, SY as u32, SZ as u32,
                -16, -64, 32, 4, 8, 4, output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(unsafe {
            jpe_cpp_density_graph_evaluate_scalar_reference(
                handle, SX as u32, SY as u32, SZ as u32,
                -16, -64, 32, 4, 8, 4, scalar.as_mut_ptr(), scalar.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(output.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
            scalar.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
        assert!(output.iter().all(|value| value.is_nan()));

        let tile_limits = [256_u32, 384, 512, 768, 1024, 1225];
        let mut measurements: [Vec<f64>; 6] =
            std::array::from_fn(|_| Vec::with_capacity(101));
        let mut tile_output = vec![0.0_f32; output.len()];
        for round in 0..101 {
            // Rotate the order each round to balance thermal / frequency drift.
            for step in 0..tile_limits.len() {
                let index = (step + round) % tile_limits.len();
                let tile_limit = tile_limits[index];
                unsafe { jpe_cpp_density_graph_set_test_tile_limit(tile_limit); }
                let start = Instant::now();
                for _ in 0..24 {
                    assert_eq!(unsafe {
                        jpe_runtime_density_graph_evaluate(
                            handle, SX as u32, SY as u32, SZ as u32,
                            -16, -64, 32, 4, 8, 4,
                            tile_output.as_mut_ptr(), tile_output.len() as u64,
                        )
                    }, STATUS_OK);
                }
                measurements[index].push(start.elapsed().as_secs_f64() * 1000.0 / 24.0);
                assert!(
                    tile_output.iter().zip(&scalar).all(|(actual, expected)| actual.to_bits() == expected.to_bits()),
                    "tile limit {tile_limit} changed graph output bits"
                );
            }
        }
        unsafe { jpe_cpp_density_graph_set_test_tile_limit(0); }
        let mut report = String::new();
        for (index, tile_limit) in tile_limits.iter().enumerate() {
            measurements[index].sort_by(f64::total_cmp);
            let median = measurements[index][50];
            report.push_str(&format!(" tile{tile_limit}={median:.6}ms"));
        }
        println!(
            "JPE_DENSITY_GRAPH_TILE_SWEEP dims=5x49x5 nodes=164 samples=1225 rounds=101 mix=vanilla-inventory{report}"
        );
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }

    #[test]
    fn density_graph_full_chunk_respects_one_megabyte_scratch_budget() {
        // 600 nodes force the 1 MiB node-value budget to choose a tile smaller
        // than the 1,225-sample Minecraft chunk lattice. This exercises several
        // dynamically sized tiles without changing sample order or float results.
        let mut nodes = Vec::with_capacity(600);
        nodes.push(JpeDensityNode {
            opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
            input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0,
        });
        nodes.push(JpeDensityNode {
            opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
            input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0,
        });
        for index in 2..600 {
            nodes.push(JpeDensityNode {
                opcode: DENSITY_ADD,
                input_a: if index == 2 { 0 } else { (index - 1) as u32 },
                input_b: 1, reserved: 0, immediate: 0.0,
            });
        }
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32,
                (nodes.len() - 1) as u32, 10, 0x1225_0600)
        };
        assert_ne!(handle, 0);

        const SX: usize = 5;
        const SY: usize = 49;
        const SZ: usize = 5;
        let mut output = vec![0.0_f32; SX * SY * SZ];
        // In benchmark builds, override the production ceiling so the 1 MiB
        // node-value budget itself must reduce 1,225 requested samples to 436.
        #[cfg(feature = "density-graph-benchmark")]
        unsafe { jpe_cpp_density_graph_set_test_tile_limit(1225); }
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, SX as u32, SY as u32, SZ as u32,
                -16, -64, 32, 4, 8, 4,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        #[cfg(feature = "density-graph-benchmark")]
        unsafe { jpe_cpp_density_graph_set_test_tile_limit(0); }

        for z in 0..SZ {
            for x in 0..SX {
                for y in 0..SY {
                    let index = (z * SX + x) * SY + y;
                    assert_eq!(output[index], (-16 + x as i32 * 4) as f32 + 598.0,
                        "budget-limited tile mismatch at x={x}, y={y}, z={z}");
                }
            }
        }
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }

    #[test]
    fn density_graph_division_matches_ieee_float32_edges() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 },
            JpeDensityNode { opcode: DENSITY_DIV, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0 },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 3, 2, 10, 0xD1_6)
        };
        assert_ne!(handle, 0);
        // Input-major: numerator [8, 8, 8, 0], denominator [2, -2, +0, +0].
        let inputs = [8.0_f32, 8.0, 8.0, 0.0, 2.0, -2.0, 0.0, 0.0];
        let mut output = [0.0_f32; 4];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_with_inputs(
                handle, 4, 1, 1, 0, 0, 0, 1, 1, 1,
                inputs.as_ptr(), 2, output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(output[0], 4.0);
        assert_eq!(output[1], -4.0);
        assert_eq!(output[2], f32::INFINITY);
        assert!(output[3].is_nan());
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }

    #[test]
    fn density_graph_min_max_match_minecraft_volume_comparison_semantics() {
        let nodes_min = [
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 },
            JpeDensityNode { opcode: DENSITY_MIN, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0 },
        ];
        let handle_min = unsafe {
            jpe_runtime_density_graph_compile(nodes_min.as_ptr(), 3, 2, 10, 0xA110)
        };
        assert_ne!(handle_min, 0);
        let left = [f32::NAN, 1.0, 0.0, -0.0, 2.0];
        let right = [1.0, f32::NAN, -0.0, 0.0, 1.0];
        let mut inputs = [0.0_f32; 10];
        inputs[..5].copy_from_slice(&left);
        inputs[5..].copy_from_slice(&right);
        let mut output_min = [0.0_f32; 5];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_with_inputs(
                handle_min, 5, 1, 1, 0, 0, 0, 1, 1, 1,
                inputs.as_ptr(), 2, output_min.as_mut_ptr(), output_min.len() as u64,
            )
        }, STATUS_OK);
        assert!(output_min[0].is_nan());
        assert_eq!(output_min[1].to_bits(), 1.0_f32.to_bits());
        assert_eq!(output_min[2].to_bits(), 0.0_f32.to_bits());
        assert_eq!(output_min[3].to_bits(), (-0.0_f32).to_bits());
        assert_eq!(output_min[4].to_bits(), 1.0_f32.to_bits());
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle_min) }, STATUS_OK);

        let nodes_max = [
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 },
            JpeDensityNode { opcode: DENSITY_MAX, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0 },
        ];
        let handle_max = unsafe {
            jpe_runtime_density_graph_compile(nodes_max.as_ptr(), 3, 2, 10, 0xA111)
        };
        assert_ne!(handle_max, 0);
        let mut output_max = [0.0_f32; 5];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_with_inputs(
                handle_max, 5, 1, 1, 0, 0, 0, 1, 1, 1,
                inputs.as_ptr(), 2, output_max.as_mut_ptr(), output_max.len() as u64,
            )
        }, STATUS_OK);
        assert!(output_max[0].is_nan());
        assert_eq!(output_max[1].to_bits(), 1.0_f32.to_bits());
        assert_eq!(output_max[2].to_bits(), 0.0_f32.to_bits());
        assert_eq!(output_max[3].to_bits(), (-0.0_f32).to_bits());
        assert_eq!(output_max[4].to_bits(), 2.0_f32.to_bits());
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle_max) }, STATUS_OK);
    }

    #[test]
    fn density_graph_clamp_matches_minecraft_mth_float_semantics() {
        let packed = 0x0000_0000_3f80_0000_u64;
        let nodes = [
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_CLAMP, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: f64::from_bits(packed) },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 2, 1, 10, 0xC1A4)
        };
        assert_ne!(handle, 0);
        let inputs = [-1.0_f32, -0.0, 0.0, 0.5, 2.0, f32::NAN];
        let mut output = [0.0_f32; 6];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_with_inputs(
                handle, 6, 1, 1, 0, 0, 0, 1, 1, 1,
                inputs.as_ptr(), 1, output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(output[0].to_bits(), 0.0_f32.to_bits());
        assert_eq!(output[1].to_bits(), (-0.0_f32).to_bits());
        assert_eq!(output[2].to_bits(), 0.0_f32.to_bits());
        assert_eq!(output[3].to_bits(), 0.5_f32.to_bits());
        assert_eq!(output[4].to_bits(), 1.0_f32.to_bits());
        assert!(output[5].is_nan());
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }

    #[test]
    fn density_graph_executes_unary_float32_ops_with_signed_zero_semantics() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_NEGATE, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_ABS, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_SQUARE, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_CUBE, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4, 10, 0xA85)
        };
        assert_ne!(handle, 0);
        let inputs = [-0.0_f32, -2.0, 3.0, f32::NAN];
        let roots = [1_u32, 2, 3, 4];
        let mut outputs = [0.0_f32; 16];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_multi_with_inputs(
                handle, 4, 1, 1, 0, 0, 0, 1, 1, 1,
                inputs.as_ptr(), 1, roots.as_ptr(), roots.len() as u32,
                outputs.as_mut_ptr(), outputs.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(&outputs[0..3], &[0.0, 2.0, -3.0]);
        assert!(outputs[3].is_nan());
        assert_eq!(&outputs[4..7], &[0.0, 2.0, 3.0]);
        assert!(outputs[7].is_nan());
        assert_eq!(&outputs[8..11], &[0.0, 4.0, 9.0]);
        assert!(outputs[11].is_nan());
        assert_eq!(outputs[12].to_bits(), (-0.0_f32).to_bits());
        assert_eq!(outputs[13], -8.0);
        assert_eq!(outputs[14], 27.0);
        assert!(outputs[15].is_nan());
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }

    #[test]
    fn density_graph_executes_math_unary_ops_and_squeeze_in_float32_order() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_SQRT, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_LOG, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_RECIPROCAL, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_SIGN, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_SQUEEZE, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_LEAKY_RELU, input_a: 0,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.25 },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 5, 10, 0xA86)
        };
        assert_ne!(handle, 0);
        let inputs = [0.25_f32, 4.0, -1.0, 0.0];
        let roots = [1_u32, 2, 3, 4, 5, 6];
        let mut outputs = [0.0_f32; 24];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_multi_with_inputs(
                handle, 4, 1, 1, 0, 0, 0, 1, 1, 1,
                inputs.as_ptr(), 1, roots.as_ptr(), roots.len() as u32,
                outputs.as_mut_ptr(), outputs.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(&outputs[0..2], &[0.5, 2.0]);
        assert!(outputs[2].is_nan());
        assert_eq!(outputs[3].to_bits(), 0.0_f32.to_bits());
        assert!((outputs[4] - 0.25_f32.ln()).abs() < 1.0e-6);
        assert!((outputs[5] - 4.0_f32.ln()).abs() < 1.0e-6);
        assert!(outputs[6].is_nan());
        assert_eq!(outputs[7], f32::NEG_INFINITY);
        assert_eq!(&outputs[8..11], &[4.0, 0.25, -1.0]);
        assert_eq!(outputs[11], f32::INFINITY);
        assert_eq!(&outputs[12..15], &[1.0, 1.0, -1.0]);
        assert_eq!(outputs[15].to_bits(), 0.0_f32.to_bits());
        let expected_quarter = (0.25_f32 / 2.0) - ((0.25_f32 * 0.25_f32 * 0.25_f32) / 24.0);
        let expected_one = (1.0_f32 / 2.0) - (1.0_f32 / 24.0);
        assert_eq!(outputs[16].to_bits(), expected_quarter.to_bits());
        assert_eq!(outputs[17].to_bits(), expected_one.to_bits());
        assert_eq!(outputs[18].to_bits(), (-expected_one).to_bits());
        assert_eq!(outputs[19].to_bits(), 0.0_f32.to_bits());
        assert_eq!(&outputs[20..23], &[0.25, 4.0, -0.25]);
        assert_eq!(outputs[23].to_bits(), 0.0_f32.to_bits());
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }

    #[test]
    fn density_graph_rejects_forward_references_and_bad_volumes() {
        let invalid = [
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 1,
                input_b: 0, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 },
        ];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_compile(invalid.as_ptr(), 2, 0 , 10, 7)
        }, 0);

        let valid = [JpeDensityNode { opcode: DENSITY_CONST,
            input_a: DENSITY_NO_INPUT, input_b: DENSITY_NO_INPUT,
            reserved: 0, immediate: 1.0 }];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(valid.as_ptr(), 1, 0 , 10, 7)
        };
        assert_ne!(handle, 0);
        let mut output = [0.0_f32; 1];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, 1, 1, 1, 0, 0, 0, 0, 1, 1,
                output.as_mut_ptr(), 1,
            )
        }, 1);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
    }

    #[test]
    fn exports_expected_abi_version() { assert_eq!(jpe_runtime_abi_version(), 8); }

    #[test]
    fn worker_configuration_is_idempotent_after_scheduler_start() {
        let _ = scheduler();
        let active = CONFIGURED_WORKERS.load(Ordering::Acquire);
        assert!(active >= 1);
        assert_eq!(jpe_runtime_configure_workers(active as u32), active as i32);
        assert_eq!(jpe_runtime_configure_workers(0), active as i32);
    }

    #[test]
    fn bulk_boundary_calls_cpp_kernel() {
        let input = [0.0_f32, 1.5, -2.0, f32::INFINITY];
        let mut output = [0.0_f32; 4];
        let status = unsafe { jpe_runtime_add_f32(input.as_ptr(), output.as_mut_ptr(), input.len() as u32) };
        assert_eq!(status, STATUS_OK);
        assert_eq!(output, [0.0, 3.0, -4.0, f32::INFINITY]);
    }

    #[test]
    fn perlin_volume_rejects_bad_output_length() {
        let permutation = [0_u8; 256]; let mut output = [0.0_f32; 2];
        let status = unsafe { jpe_cpp_perlin_add_volume(permutation.as_ptr(),
            1, 1, 1, 0, 0, 0, 1, 1, 1,
            0.0, 0.0, 0.0, 1.0, 1.0, 1.0, output.as_mut_ptr(), 2, 0) };
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

    #[test]
    fn async_perlin_job_writes_into_borrowed_output_buffer() {
        let permutation = [1_u8; 256];
        let mut expected = [0.75_f32];
        let mut actual = [0.75_f32];
        let sync = unsafe { jpe_runtime_perlin_add_volume(
            permutation.as_ptr(), 1, 1, 1, 0, 0, 0, 1, 1, 1,
            0.23, 0.41, 0.37, 1.0, 1.0, 1.0, expected.as_mut_ptr(), 1, 0
        ) };
        assert_eq!(sync, STATUS_OK);
        let id = unsafe { jpe_runtime_submit_perlin_volume(
            permutation.as_ptr(), 1, 1, 1, 0, 0, 0, 1, 1, 1,
            0.23, 0.41, 0.37, 1.0, 1.0, 1.0, actual.as_mut_ptr(), 1, 0
        ) };
        assert_ne!(id, 0);
        let status = jpe_runtime_wait_job(id, 2_000_000_000);
        assert_eq!(status, JOB_COMPLETE);
        assert_eq!(actual, expected);
        assert_ne!(actual[0], 0.75_f32);
        assert_eq!(jpe_runtime_release_job(id), STATUS_OK);
    }

    #[test]
    fn batched_perlin_stack_matches_ordered_individual_calls_bit_for_bit() {
        let permutation_a: [u8; 256] = std::array::from_fn(|i| ((i * 37 + 11) & 255) as u8);
        let permutation_b: [u8; 256] = std::array::from_fn(|i| ((i * 19 + 73) & 255) as u8);
        let layers = [
            JpePerlinLayer { permutation: permutation_a.as_ptr(),
                offset_x: 0.13, offset_y: 0.29, offset_z: 0.41,
                xz_scale: 0.75, y_scale: 1.25, amplitude: 0.5,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            },
            JpePerlinLayer { permutation: permutation_b.as_ptr(),
                offset_x: -0.31, offset_y: 0.17, offset_z: 0.23,
                xz_scale: 1.5, y_scale: 0.625, amplitude: -0.125,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            },
        ];
        let mut expected = [0.375_f32; 24];
        let mut actual = expected;
        for layer in &layers {
            let status = unsafe { jpe_cpp_perlin_add_volume(
                layer.permutation, 2, 3, 4, -7, -11, 13, 1, 2, 1,
                layer.offset_x, layer.offset_y, layer.offset_z,
                layer.xz_scale, layer.y_scale, layer.amplitude,
                expected.as_mut_ptr(), expected.len() as u64, 0
            ) };
            assert_eq!(status, STATUS_OK);
        }
        let status = unsafe { jpe_runtime_perlin_add_stack(
            layers.as_ptr(), layers.len() as u32,
            2, 3, 4, -7, -11, 13, 1, 2, 1,
            actual.as_mut_ptr(), actual.len() as u64, 0
        ) };
        assert_eq!(status, STATUS_OK);
        assert_eq!(actual.map(f32::to_bits), expected.map(f32::to_bits));
    }

    #[test]
    fn fused_perlin_stack_matches_ordered_individual_calls_bit_for_bit() {
        let permutation_a: [u8; 256] = std::array::from_fn(|i| ((i * 37 + 11) & 255) as u8);
        let permutation_b: [u8; 256] = std::array::from_fn(|i| ((i * 19 + 73) & 255) as u8);
        let permutation_c: [u8; 256] = std::array::from_fn(|i| ((i * 53 + 29) & 255) as u8);
        let layers = [
            JpePerlinLayer { permutation: permutation_a.as_ptr(),
                offset_x: 0.13, offset_y: 0.29, offset_z: 0.41,
                xz_scale: 0.75, y_scale: 1.25, amplitude: 0.5,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            },
            JpePerlinLayer { permutation: permutation_b.as_ptr(),
                offset_x: -0.31, offset_y: 0.17, offset_z: 0.23,
                xz_scale: 1.5, y_scale: 0.625, amplitude: -0.125,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            },
            JpePerlinLayer { permutation: permutation_c.as_ptr(),
                offset_x: 0.07, offset_y: -0.33, offset_z: 0.19,
                xz_scale: 0.375, y_scale: 2.0, amplitude: 0.0625,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            },
        ];
        let mut expected = [0.375_f32; 60];
        let mut actual = expected;
        for layer in &layers {
            assert_eq!(unsafe { jpe_cpp_perlin_add_volume(
                layer.permutation, 3, 4, 5, -17, -11, 13, 2, 1, 3,
                layer.offset_x, layer.offset_y, layer.offset_z,
                layer.xz_scale, layer.y_scale, layer.amplitude,
                expected.as_mut_ptr(), expected.len() as u64, 0
            ) }, STATUS_OK);
        }
        assert_eq!(unsafe { jpe_cpp_perlin_add_stack_fused(
            layers.as_ptr(), layers.len() as u32,
            3, 4, 5, -17, -11, 13, 2, 1, 3,
            actual.as_mut_ptr(), actual.len() as u64, 0
        ) }, STATUS_OK);
        assert_eq!(actual.map(f32::to_bits), expected.map(f32::to_bits));
    }

    #[test]
    fn smeared_perlin_stack_dispatches_to_fudged_y_kernel() {
        let permutation: [u8; 256] = std::array::from_fn(|i| ((i * 37 + 11) & 255) as u8);
        let mut layer = JpePerlinLayer {
            permutation: permutation.as_ptr(),
            offset_x: 0.13,
            offset_y: 0.29,
            offset_z: 0.41,
            xz_scale: 0.75,
            y_scale: 0.37,
            amplitude: 0.5,
            fudge_y_scale: 0.25,
            flags: 1,
            reserved: 0,
            frequency: 0.0,
        };
        const SX: usize = 5;
        const SY: usize = 49;
        const SZ: usize = 5;
        let mut smeared = vec![0.0_f32; SX * SY * SZ];
        assert_eq!(unsafe {
            jpe_runtime_perlin_add_stack(
                &layer, 1, SX as u32, SY as u32, SZ as u32,
                0, -64, 0, 4, 8, 4,
                smeared.as_mut_ptr(), smeared.len() as u64, 0,
            )
        }, STATUS_OK);
        assert!(smeared.iter().all(|value| value.is_finite()));

        // Independent scalar transcription of SmearedPerlinNoise.addToVolume:
        // alphaY uses raw relativeY, while only the gradient dot products use
        // relativeY - computeFudgeY(originalY, relativeY).
        let gx = [1.0_f32,-1.0,1.0,-1.0,1.0,-1.0,1.0,-1.0,0.0,0.0,0.0,0.0,1.0,0.0,-1.0,0.0];
        let gy = [1.0_f32,1.0,-1.0,-1.0,0.0,0.0,0.0,0.0,1.0,-1.0,1.0,-1.0,1.0,-1.0,1.0,-1.0];
        let gz = [0.0_f32,0.0,0.0,0.0,1.0,1.0,-1.0,-1.0,1.0,1.0,-1.0,-1.0,0.0,1.0,0.0,-1.0];
        let smooth = |v: f32| v * v * v * (v * (v * 6.0 - 15.0) + 10.0);
        let lerp = |a: f32, from: f32, to: f32| from + a * (to - from);
        let perm = |v: i32| permutation[(v as u32 & 255) as usize] as i32;
        let mut expected = vec![0.0_f32; smeared.len()];
        for iz in 0..SZ {
            let block_z = (iz * 4) as f64;
            let z = block_z * layer.xz_scale + layer.offset_z;
            let floor_z = z.floor() as i32;
            let rz = (z - floor_z as f64) as f32;
            let az = smooth(rz);
            for ix in 0..SX {
                let block_x = (ix * 4) as f64;
                let x = block_x * layer.xz_scale + layer.offset_x;
                let floor_x = x.floor() as i32;
                let rx = (x - floor_x as f64) as f32;
                let ax = smooth(rx);
                let x0 = perm(floor_x);
                let x1 = perm(floor_x + 1);
                for iy in 0..SY {
                    let block_y = -64.0 + (iy * 8) as f64;
                    let original_y = block_y * layer.y_scale;
                    let y = original_y + layer.offset_y;
                    let floor_y = y.floor() as i32;
                    let relative_y = y - floor_y as f64;
                    let alpha_y = smooth(relative_y as f32);
                    let fudge_limit = if original_y >= 0.0 && original_y < relative_y {
                        original_y
                    } else {
                        relative_y
                    };
                    let fudge_steps = (fudge_limit / layer.fudge_y_scale + 1.0E-7_f32 as f64).floor() as i32;
                    let fudged_y = (relative_y - fudge_steps as f64 * layer.fudge_y_scale) as f32;
                    let xy00 = perm(x0 + floor_y);
                    let xy01 = perm(x0 + floor_y + 1);
                    let xy10 = perm(x1 + floor_y);
                    let xy11 = perm(x1 + floor_y + 1);
                    // corner()/permuteToGrad performs exactly one permutation
                    // lookup after adding the Z lattice coordinate.
                    let hashes = [
                        xy00 + floor_z, xy10 + floor_z,
                        xy01 + floor_z, xy11 + floor_z,
                        xy00 + floor_z + 1, xy10 + floor_z + 1,
                        xy01 + floor_z + 1, xy11 + floor_z + 1,
                    ];
                    let rxz = [rx, rx - 1.0, rx, rx - 1.0, rx, rx - 1.0, rx, rx - 1.0];
                    let rzz = [rz, rz, rz, rz, rz - 1.0, rz - 1.0, rz - 1.0, rz - 1.0];
                    let mut xz_dot = [0.0_f32; 8];
                    let mut y_grad = [0.0_f32; 8];
                    for k in 0..8 {
                        let g = (permutation[(hashes[k] as u32 & 255) as usize] & 15) as usize;
                        xz_dot[k] = gx[g] * rxz[k] + gz[g] * rzz[k];
                        y_grad[k] = gy[g];
                    }
                    let ry1 = fudged_y - 1.0;
                    let values = [
                        xz_dot[0] + y_grad[0] * fudged_y,
                        xz_dot[1] + y_grad[1] * fudged_y,
                        xz_dot[2] + y_grad[2] * ry1,
                        xz_dot[3] + y_grad[3] * ry1,
                        xz_dot[4] + y_grad[4] * fudged_y,
                        xz_dot[5] + y_grad[5] * fudged_y,
                        xz_dot[6] + y_grad[6] * ry1,
                        xz_dot[7] + y_grad[7] * ry1,
                    ];
                    let lower = lerp(alpha_y,
                        lerp(ax, values[0], values[1]),
                        lerp(ax, values[2], values[3]));
                    let upper = lerp(alpha_y,
                        lerp(ax, values[4], values[5]),
                        lerp(ax, values[6], values[7]));
                    let index = (iz * SX + ix) * SY + iy;
                    expected[index] = layer.amplitude * lerp(az, lower, upper);
                }
            }
        }
        assert_eq!(smeared, expected, "C++ SmearedPerlin must match the independent scalar reference bit-for-bit");

        layer.fudge_y_scale = 0.0;
        layer.flags = 0;
        let mut ordinary = vec![0.0_f32; smeared.len()];
        assert_eq!(unsafe {
            jpe_runtime_perlin_add_stack(
                &layer, 1, SX as u32, SY as u32, SZ as u32,
                0, -64, 0, 4, 8, 4,
                ordinary.as_mut_ptr(), ordinary.len() as u64, 0,
            )
        }, STATUS_OK);
        assert!(ordinary.iter().all(|value| value.is_finite()));
        assert_ne!(smeared, ordinary, "SmearedPerlin must apply its Y-coordinate fudge");
    }

    #[test]
    fn smeared_perlin_multilayer_full_chunk_matches_fused_stack() {
        use std::time::Instant;
        const SX: usize = 5;
        const SY: usize = 49;
        const SZ: usize = 5;
        const LAYERS: usize = 8;
        let permutations: [[u8; 256]; LAYERS] = std::array::from_fn(|layer| {
            std::array::from_fn(|i| ((i * (37 + layer * 6) + 11 + layer * 17) & 255) as u8)
        });
        let layers: [JpePerlinLayer; LAYERS] = std::array::from_fn(|i| JpePerlinLayer {
            permutation: permutations[i].as_ptr(),
            offset_x: 0.13 * (i as f64 + 1.0),
            offset_y: -0.17 * (i as f64 + 1.0),
            offset_z: 0.23 * (i as f64 + 1.0),
            xz_scale: 0.375 * (i as f64 + 1.0),
            y_scale: 0.0625 * (i as f64 + 1.0),
            amplitude: 0.5_f32.powi(i as i32 + 1),
            fudge_y_scale: 0.25 + 0.03125 * i as f64,
            flags: 1,
            reserved: 0,
            frequency: 0.0,
        });
        let mut ordered = vec![0.0_f32; SX * SY * SZ];
        let mut fused = vec![0.0_f32; ordered.len()];
        assert_eq!(unsafe {
            jpe_runtime_perlin_add_stack(
                layers.as_ptr(), LAYERS as u32, SX as u32, SY as u32, SZ as u32,
                0, -64, 0, 4, 8, 4,
                ordered.as_mut_ptr(), ordered.len() as u64, 0,
            )
        }, STATUS_OK);
        assert_eq!(unsafe {
            jpe_cpp_perlin_add_stack_fused(
                layers.as_ptr(), LAYERS as u32, SX as u32, SY as u32, SZ as u32,
                0, -64, 0, 4, 8, 4,
                fused.as_mut_ptr(), fused.len() as u64, 0,
            )
        }, STATUS_OK);
        assert_eq!(ordered, fused,
            "ordered per-layer SmearedPerlin and fused C++ stack must match on a complete chunk lattice");

        let mut ordered_ms = Vec::with_capacity(15);
        let mut fused_ms = Vec::with_capacity(15);
        for _ in 0..15 {
            let start = Instant::now();
            for _ in 0..5 {
                ordered.fill(0.0);
                assert_eq!(unsafe {
                    jpe_runtime_perlin_add_stack(
                        layers.as_ptr(), LAYERS as u32, SX as u32, SY as u32, SZ as u32,
                        0, -64, 0, 4, 8, 4,
                        ordered.as_mut_ptr(), ordered.len() as u64, 0,
                    )
                }, STATUS_OK);
            }
            ordered_ms.push(start.elapsed().as_secs_f64() * 200.0);

            let start = Instant::now();
            for _ in 0..5 {
                fused.fill(0.0);
                assert_eq!(unsafe {
                    jpe_cpp_perlin_add_stack_fused(
                        layers.as_ptr(), LAYERS as u32, SX as u32, SY as u32, SZ as u32,
                        0, -64, 0, 4, 8, 4,
                        fused.as_mut_ptr(), fused.len() as u64, 0,
                    )
                }, STATUS_OK);
            }
            fused_ms.push(start.elapsed().as_secs_f64() * 200.0);
        }
        ordered_ms.sort_by(|a, b| a.total_cmp(b));
        fused_ms.sort_by(|a, b| a.total_cmp(b));
        println!(
            "JPE_SMEARED_PERLIN_FULL_CHUNK dims=5x49x5 layers=8 ordered_median_ms={:.6} fused_median_ms={:.6} fused_speedup={:.3}x",
            ordered_ms[ordered_ms.len() / 2],
            fused_ms[fused_ms.len() / 2],
            ordered_ms[ordered_ms.len() / 2] / fused_ms[fused_ms.len() / 2],
        );
    }

    #[test]
    fn smeared_perlin_wraps_large_x_and_z_coordinates_like_vanilla() {
        let permutation: [u8; 256] = std::array::from_fn(|i| ((i * 37 + 11) & 255) as u8);
        let layer = JpePerlinLayer {
            permutation: permutation.as_ptr(),
            offset_x: 0.13,
            offset_y: 0.29,
            offset_z: 0.41,
            xz_scale: 1.0,
            y_scale: 0.37,
            amplitude: 0.5,
            fudge_y_scale: 0.25,
            flags: 1,
            reserved: 0,
            frequency: 0.0,
        };
        let evaluate = |min_x: i32, min_z: i32| {
            let mut output = [0.0_f32; 1];
            let status = unsafe {
                jpe_runtime_perlin_add_stack(
                    &layer, 1, 1, 1, 1,
                    min_x, -64, min_z, 1, 1, 1,
                    output.as_mut_ptr(), output.len() as u64, 0,
                )
            };
            assert_eq!(status, STATUS_OK);
            output[0].to_bits()
        };

        // Minecraft's Perlin wrap period is 2^25. The large coordinates must
        // fold to the same wrapped X/Z values before offsets and lattice floors.
        let baseline = evaluate(123, -321);
        assert_eq!(evaluate(123 + 33_554_432, -321), baseline,
            "SmearedPerlin X coordinate must use vanilla wrap semantics");
        assert_eq!(evaluate(123, -321 + 33_554_432), baseline,
            "SmearedPerlin Z coordinate must use vanilla wrap semantics");
    }

    #[test]
    fn cached_y_interpolation_matches_uncached_reference_bit_for_bit() {
        let permutations: [[u8; 256]; 3] = std::array::from_fn(|layer| {
            std::array::from_fn(|i| ((i * (31 + layer * 8) + 17 + layer * 13) & 255) as u8)
        });
        let layers: [JpePerlinLayer; 3] = std::array::from_fn(|i| JpePerlinLayer {
            permutation: permutations[i].as_ptr(),
            offset_x: 0.19 * (i as f64 + 1.0),
            offset_y: -0.23 * (i as f64 + 1.0),
            offset_z: 0.37 * (i as f64 + 1.0),
            xz_scale: 0.45 * (i as f64 + 1.0),
            y_scale: 0.7 * (i as f64 + 1.0),
            amplitude: 0.5_f32.powi(i as i32 + 1),
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            });
        for flags in [0_u32, 1_u32] {
            let mut expected = vec![0.375_f32; 5 * 8 * 4];
            let mut actual = expected.clone();
            for layer in &layers {
                assert_eq!(unsafe { jpe_cpp_perlin_add_volume_uncached_y(
                    layer.permutation, 5, 8, 4, -16_777_219, 91, -33,
                    3, -2, 5, layer.offset_x, layer.offset_y, layer.offset_z,
                    layer.xz_scale, layer.y_scale, layer.amplitude,
                    expected.as_mut_ptr(), expected.len() as u64, flags
                ) }, STATUS_OK);
            }
            assert_eq!(unsafe { jpe_cpp_perlin_add_stack(
                layers.as_ptr(), layers.len() as u32,
                5, 8, 4, -16_777_219, 91, -33, 3, -2, 5,
                actual.as_mut_ptr(), actual.len() as u64, flags
            ) }, STATUS_OK);
            assert_eq!(actual.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                       expected.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
        }
    }

    #[test]
    fn perlin_y_cache_native_microbenchmark() {
        use std::time::Instant;
        const SX: usize = 5;
        const SY: usize = 49;
        const SZ: usize = 5;
        const LAYERS: usize = 6;
        const ROUNDS: usize = 21;
        const REPEATS: usize = 5;
        let permutations: [[u8; 256]; LAYERS] = std::array::from_fn(|layer| {
            std::array::from_fn(|i| ((i * (37 + layer * 6) + 11 + layer * 17) & 255) as u8)
        });
        let layers: [JpePerlinLayer; LAYERS] = std::array::from_fn(|i| JpePerlinLayer {
            permutation: permutations[i].as_ptr(),
            offset_x: 0.13 * (i as f64 + 1.0),
            offset_y: -0.17 * (i as f64 + 1.0),
            offset_z: 0.23 * (i as f64 + 1.0),
            xz_scale: 0.375 * (i as f64 + 1.0),
            y_scale: 0.625 * (i as f64 + 1.0),
            amplitude: 0.5_f32.powi(i as i32 + 1),
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            });
        let len = SX * SY * SZ;
        let mut cached = vec![0.125_f32; len];
        let mut uncached = cached.clone();
        let run_cached = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                out.fill(0.125);
                assert_eq!(unsafe { jpe_cpp_perlin_add_stack(
                    layers.as_ptr(), LAYERS as u32,
                    SX as u32, SY as u32, SZ as u32,
                    -16, -64, 32, 4, 8, 4,
                    out.as_mut_ptr(), len as u64, 0
                ) }, STATUS_OK);
            }
        };
        let run_uncached = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                out.fill(0.125);
                for layer in &layers {
                    assert_eq!(unsafe { jpe_cpp_perlin_add_volume_uncached_y(
                        layer.permutation, SX as u32, SY as u32, SZ as u32,
                        -16, -64, 32, 4, 8, 4,
                        layer.offset_x, layer.offset_y, layer.offset_z,
                        layer.xz_scale, layer.y_scale, layer.amplitude,
                        out.as_mut_ptr(), len as u64, 0
                    ) }, STATUS_OK);
                }
            }
        };
        for _ in 0..2 {
            run_cached(&mut cached);
            run_uncached(&mut uncached);
        }
        let mut cached_ns = Vec::with_capacity(ROUNDS);
        let mut uncached_ns = Vec::with_capacity(ROUNDS);
        for round in 0..ROUNDS {
            if round % 2 == 0 {
                let start = Instant::now(); run_cached(&mut cached);
                cached_ns.push(start.elapsed().as_nanos());
                let start = Instant::now(); run_uncached(&mut uncached);
                uncached_ns.push(start.elapsed().as_nanos());
            } else {
                let start = Instant::now(); run_uncached(&mut uncached);
                uncached_ns.push(start.elapsed().as_nanos());
                let start = Instant::now(); run_cached(&mut cached);
                cached_ns.push(start.elapsed().as_nanos());
            }
        }
        assert_eq!(cached.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                   uncached.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
        cached_ns.sort_unstable();
        uncached_ns.sort_unstable();
        let cached_median = cached_ns[ROUNDS / 2];
        let uncached_median = uncached_ns[ROUNDS / 2];
        eprintln!(
            "JPE_PERLIN_Y_CACHE_BENCH dims={}x{}x{} layers={} repeats={} rounds={} uncached_median_ms={:.3} cached_median_ms={:.3} cached_speedup={:.3}x",
            SX, SY, SZ, LAYERS, REPEATS, ROUNDS,
            uncached_median as f64 / 1_000_000.0,
            cached_median as f64 / 1_000_000.0,
            uncached_median as f64 / cached_median as f64
        );
    }

    #[test]
    fn xy_permutation_cache_matches_uncached_xy_reference_bit_for_bit() {
        let permutations: [[u8; 256]; 4] = std::array::from_fn(|layer| {
            std::array::from_fn(|i| ((i * (29 + layer * 10) + 23 + layer * 7) & 255) as u8)
        });
        let layers: [JpePerlinLayer; 4] = std::array::from_fn(|i| JpePerlinLayer {
            permutation: permutations[i].as_ptr(),
            offset_x: 0.11 * (i as f64 + 1.0),
            offset_y: -0.21 * (i as f64 + 1.0),
            offset_z: 0.31 * (i as f64 + 1.0),
            xz_scale: 0.41 * (i as f64 + 1.0),
            y_scale: 0.61 * (i as f64 + 1.0),
            amplitude: 0.5_f32.powi(i as i32 + 1),
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            });
        for flags in [0_u32, 1_u32] {
            let mut expected = vec![0.25_f32; 32 * 64 * 16];
            let mut actual = expected.clone();
            for layer in &layers {
                assert_eq!(unsafe { jpe_cpp_perlin_add_volume_uncached_xy(
                    layer.permutation, 32, 64, 16, -257, -129, 193,
                    1, 1, -1, layer.offset_x, layer.offset_y, layer.offset_z,
                    layer.xz_scale, layer.y_scale, layer.amplitude,
                    expected.as_mut_ptr(), expected.len() as u64, flags
                ) }, STATUS_OK);
            }
            for layer in &layers {
                assert_eq!(unsafe { jpe_cpp_perlin_add_volume_cached_xy(
                    layer.permutation, 32, 64, 16, -257, -129, 193,
                    1, 1, -1, layer.offset_x, layer.offset_y, layer.offset_z,
                    layer.xz_scale, layer.y_scale, layer.amplitude,
                    actual.as_mut_ptr(), actual.len() as u64, flags
                ) }, STATUS_OK);
            }
            assert_eq!(actual.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                       expected.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
        }
    }

    #[test]
    fn perlin_y_cache_native_microbenchmark_large_volume() {
        use std::time::Instant;
        const SX: usize = 5;
        const SY: usize = 49;
        const SZ: usize = 5;
        const LAYERS: usize = 8;
        const ROUNDS: usize = 15;
        const REPEATS: usize = 3;
        let permutations: [[u8; 256]; LAYERS] = std::array::from_fn(|layer| {
            std::array::from_fn(|i| ((i * (37 + layer * 6) + 11 + layer * 17) & 255) as u8)
        });
        let layers: [JpePerlinLayer; LAYERS] = std::array::from_fn(|i| JpePerlinLayer {
            permutation: permutations[i].as_ptr(),
            offset_x: 0.13 * (i as f64 + 1.0),
            offset_y: -0.17 * (i as f64 + 1.0),
            offset_z: 0.23 * (i as f64 + 1.0),
            xz_scale: 0.375 * (i as f64 + 1.0),
            y_scale: 0.625 * (i as f64 + 1.0),
            amplitude: 0.5_f32.powi(i as i32 + 1),
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            });
        let len = SX * SY * SZ;
        let mut cached = vec![0.125_f32; len];
        let mut uncached = cached.clone();
        let run_cached = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                out.fill(0.125);
                assert_eq!(unsafe { jpe_cpp_perlin_add_stack(
                    layers.as_ptr(), LAYERS as u32,
                    SX as u32, SY as u32, SZ as u32,
                    -16, -64, 32, 4, 8, 4,
                    out.as_mut_ptr(), len as u64, 0
                ) }, STATUS_OK);
            }
        };
        let run_uncached = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                out.fill(0.125);
                for layer in &layers {
                    assert_eq!(unsafe { jpe_cpp_perlin_add_volume_uncached_y(
                        layer.permutation, SX as u32, SY as u32, SZ as u32,
                        -16, -64, 32, 4, 8, 4,
                        layer.offset_x, layer.offset_y, layer.offset_z,
                        layer.xz_scale, layer.y_scale, layer.amplitude,
                        out.as_mut_ptr(), len as u64, 0
                    ) }, STATUS_OK);
                }
            }
        };
        for _ in 0..2 {
            run_cached(&mut cached);
            run_uncached(&mut uncached);
        }
        let mut cached_ns = Vec::with_capacity(ROUNDS);
        let mut uncached_ns = Vec::with_capacity(ROUNDS);
        for round in 0..ROUNDS {
            if round % 2 == 0 {
                let start = Instant::now(); run_cached(&mut cached);
                cached_ns.push(start.elapsed().as_nanos());
                let start = Instant::now(); run_uncached(&mut uncached);
                uncached_ns.push(start.elapsed().as_nanos());
            } else {
                let start = Instant::now(); run_uncached(&mut uncached);
                uncached_ns.push(start.elapsed().as_nanos());
                let start = Instant::now(); run_cached(&mut cached);
                cached_ns.push(start.elapsed().as_nanos());
            }
        }
        assert_eq!(cached.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                   uncached.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
        cached_ns.sort_unstable();
        uncached_ns.sort_unstable();
        let cached_median = cached_ns[ROUNDS / 2];
        let uncached_median = uncached_ns[ROUNDS / 2];
        eprintln!(
            "JPE_PERLIN_Y_CACHE_LARGE_BENCH dims={}x{}x{} layers={} repeats={} rounds={} uncached_median_ms={:.3} cached_median_ms={:.3} cached_speedup={:.3}x",
            SX, SY, SZ, LAYERS, REPEATS, ROUNDS,
            uncached_median as f64 / 1_000_000.0,
            cached_median as f64 / 1_000_000.0,
            uncached_median as f64 / cached_median as f64
        );
    }

    #[test]
    fn perlin_xy_cache_native_microbenchmark_large_volume() {
        use std::time::Instant;
        const SX: usize = 5;
        const SY: usize = 49;
        const SZ: usize = 5;
        const LAYERS: usize = 8;
        const ROUNDS: usize = 15;
        const REPEATS: usize = 3;
        let permutations: [[u8; 256]; LAYERS] = std::array::from_fn(|layer| {
            std::array::from_fn(|i| ((i * (37 + layer * 6) + 11 + layer * 17) & 255) as u8)
        });
        let layers: [JpePerlinLayer; LAYERS] = std::array::from_fn(|i| JpePerlinLayer {
            permutation: permutations[i].as_ptr(),
            offset_x: 0.13 * (i as f64 + 1.0),
            offset_y: -0.17 * (i as f64 + 1.0),
            offset_z: 0.23 * (i as f64 + 1.0),
            xz_scale: 0.375 * (i as f64 + 1.0),
            y_scale: 0.625 * (i as f64 + 1.0),
            amplitude: 0.5_f32.powi(i as i32 + 1),
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            });
        let len = SX * SY * SZ;
        let mut cached = vec![0.125_f32; len];
        let mut uncached = cached.clone();
        let run_cached = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                out.fill(0.125);
                for layer in &layers {
                    assert_eq!(unsafe { jpe_cpp_perlin_add_volume_cached_xy(
                        layer.permutation, SX as u32, SY as u32, SZ as u32,
                        -16, -64, 32, 4, 8, 4,
                        layer.offset_x, layer.offset_y, layer.offset_z,
                        layer.xz_scale, layer.y_scale, layer.amplitude,
                        out.as_mut_ptr(), len as u64, 0
                    ) }, STATUS_OK);
                }
            }
        };
        let run_uncached = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                out.fill(0.125);
                for layer in &layers {
                    assert_eq!(unsafe { jpe_cpp_perlin_add_volume_uncached_xy(
                        layer.permutation, SX as u32, SY as u32, SZ as u32,
                        -16, -64, 32, 4, 8, 4,
                        layer.offset_x, layer.offset_y, layer.offset_z,
                        layer.xz_scale, layer.y_scale, layer.amplitude,
                        out.as_mut_ptr(), len as u64, 0
                    ) }, STATUS_OK);
                }
            }
        };
        for _ in 0..2 {
            run_cached(&mut cached);
            run_uncached(&mut uncached);
        }
        let mut cached_ns = Vec::with_capacity(ROUNDS);
        let mut uncached_ns = Vec::with_capacity(ROUNDS);
        for round in 0..ROUNDS {
            if round % 2 == 0 {
                let start = Instant::now(); run_cached(&mut cached);
                cached_ns.push(start.elapsed().as_nanos());
                let start = Instant::now(); run_uncached(&mut uncached);
                uncached_ns.push(start.elapsed().as_nanos());
            } else {
                let start = Instant::now(); run_uncached(&mut uncached);
                uncached_ns.push(start.elapsed().as_nanos());
                let start = Instant::now(); run_cached(&mut cached);
                cached_ns.push(start.elapsed().as_nanos());
            }
        }
        assert_eq!(cached.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                   uncached.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
        cached_ns.sort_unstable();
        uncached_ns.sort_unstable();
        let cached_median = cached_ns[ROUNDS / 2];
        let uncached_median = uncached_ns[ROUNDS / 2];
        eprintln!(
            "JPE_PERLIN_XY_CACHE_LARGE_BENCH dims={}x{}x{} layers={} repeats={} rounds={} uncached_median_ms={:.3} cached_median_ms={:.3} cached_speedup={:.3}x",
            SX, SY, SZ, LAYERS, REPEATS, ROUNDS,
            uncached_median as f64 / 1_000_000.0,
            cached_median as f64 / 1_000_000.0,
            uncached_median as f64 / cached_median as f64
        );
    }

    #[test]
    fn perlin_stack_native_microbenchmark_fixed_workload() {
        use std::time::Instant;

        const SX: usize = 5;
        const SY: usize = 49;
        const SZ: usize = 5;
        const LAYERS: usize = 6;
        const ROUNDS: usize = 21;
        const REPEATS: usize = 5;
        let permutations: [[u8; 256]; LAYERS] = std::array::from_fn(|layer| {
            std::array::from_fn(|i| ((i * (37 + layer * 6) + 11 + layer * 17) & 255) as u8)
        });
        let layers: [JpePerlinLayer; LAYERS] = std::array::from_fn(|i| JpePerlinLayer {
            permutation: permutations[i].as_ptr(),
            offset_x: 0.13 * (i as f64 + 1.0),
            offset_y: -0.17 * (i as f64 + 1.0),
            offset_z: 0.23 * (i as f64 + 1.0),
            xz_scale: 0.375 * (i as f64 + 1.0),
            y_scale: 0.625 * (i as f64 + 1.0),
            amplitude: 0.5_f32.powi(i as i32 + 1),
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            });
        let len = SX * SY * SZ;
        let mut repeated = vec![0.125_f32; len];
        let mut fused = vec![0.125_f32; len];

        let run_repeated = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                out.fill(0.125);
                assert_eq!(unsafe { jpe_cpp_perlin_add_stack(
                    layers.as_ptr(), LAYERS as u32,
                    SX as u32, SY as u32, SZ as u32,
                    -16, -64, 32, 4, 8, 4,
                    out.as_mut_ptr(), len as u64, 0
                ) }, STATUS_OK);
            }
        };
        let run_fused = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                out.fill(0.125);
                assert_eq!(unsafe { jpe_cpp_perlin_add_stack_fused(
                    layers.as_ptr(), LAYERS as u32,
                    SX as u32, SY as u32, SZ as u32,
                    -16, -64, 32, 4, 8, 4,
                    out.as_mut_ptr(), len as u64, 0
                ) }, STATUS_OK);
            }
        };

        // Warm both implementations before timing; alternate order each round.
        for _ in 0..2 {
            run_repeated(&mut repeated);
            run_fused(&mut fused);
        }
        let mut repeated_ns = Vec::with_capacity(ROUNDS);
        let mut fused_ns = Vec::with_capacity(ROUNDS);
        for round in 0..ROUNDS {
            if round % 2 == 0 {
                let start = Instant::now(); run_repeated(&mut repeated);
                repeated_ns.push(start.elapsed().as_nanos());
                let start = Instant::now(); run_fused(&mut fused);
                fused_ns.push(start.elapsed().as_nanos());
            } else {
                let start = Instant::now(); run_fused(&mut fused);
                fused_ns.push(start.elapsed().as_nanos());
                let start = Instant::now(); run_repeated(&mut repeated);
                repeated_ns.push(start.elapsed().as_nanos());
            }
        }
        assert_eq!(repeated.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                   fused.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
        repeated_ns.sort_unstable();
        fused_ns.sort_unstable();
        let repeated_median = repeated_ns[ROUNDS / 2];
        let fused_median = fused_ns[ROUNDS / 2];
        let ratio = repeated_median as f64 / fused_median as f64;
        eprintln!(
            "JPE_PERLIN_STACK_MICROBENCH dims={}x{}x{} layers={} repeats={} rounds={} repeated_median_ms={:.3} fused_median_ms={:.3} fused_speedup={:.3}x",
            SX, SY, SZ, LAYERS, REPEATS, ROUNDS,
            repeated_median as f64 / 1_000_000.0,
            fused_median as f64 / 1_000_000.0,
            ratio
        );
    }

    #[test]
    fn perlin_stack_native_microbenchmark_large_volume() {
        use std::time::Instant;

        const SX: usize = 5;
        const SY: usize = 49;
        const SZ: usize = 5;
        const LAYERS: usize = 8;
        const ROUNDS: usize = 21;
        const REPEATS: usize = 5;
        let permutations: [[u8; 256]; LAYERS] = std::array::from_fn(|layer| {
            std::array::from_fn(|i| ((i * (37 + layer * 6) + 11 + layer * 17) & 255) as u8)
        });
        let layers: [JpePerlinLayer; LAYERS] = std::array::from_fn(|i| JpePerlinLayer {
            permutation: permutations[i].as_ptr(),
            offset_x: 0.13 * (i as f64 + 1.0),
            offset_y: -0.17 * (i as f64 + 1.0),
            offset_z: 0.23 * (i as f64 + 1.0),
            xz_scale: 0.375 * (i as f64 + 1.0),
            y_scale: 0.625 * (i as f64 + 1.0),
            amplitude: 0.5_f32.powi(i as i32 + 1),
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            });
        let len = SX * SY * SZ;
        let mut repeated = vec![0.125_f32; len];
        let mut fused = vec![0.125_f32; len];

        let run_repeated = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                out.fill(0.125);
                assert_eq!(unsafe { jpe_cpp_perlin_add_stack(
                    layers.as_ptr(), LAYERS as u32,
                    SX as u32, SY as u32, SZ as u32,
                    -16, -64, 32, 4, 8, 4,
                    out.as_mut_ptr(), len as u64, 0
                ) }, STATUS_OK);
            }
        };
        let run_fused = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                out.fill(0.125);
                assert_eq!(unsafe { jpe_cpp_perlin_add_stack_fused(
                    layers.as_ptr(), LAYERS as u32,
                    SX as u32, SY as u32, SZ as u32,
                    -16, -64, 32, 4, 8, 4,
                    out.as_mut_ptr(), len as u64, 0
                ) }, STATUS_OK);
            }
        };

        // Warm both implementations before timing; alternate order each round.
        for _ in 0..2 {
            run_repeated(&mut repeated);
            run_fused(&mut fused);
        }
        let mut repeated_ns = Vec::with_capacity(ROUNDS);
        let mut fused_ns = Vec::with_capacity(ROUNDS);
        for round in 0..ROUNDS {
            if round % 2 == 0 {
                let start = Instant::now(); run_repeated(&mut repeated);
                repeated_ns.push(start.elapsed().as_nanos());
                let start = Instant::now(); run_fused(&mut fused);
                fused_ns.push(start.elapsed().as_nanos());
            } else {
                let start = Instant::now(); run_fused(&mut fused);
                fused_ns.push(start.elapsed().as_nanos());
                let start = Instant::now(); run_repeated(&mut repeated);
                repeated_ns.push(start.elapsed().as_nanos());
            }
        }
        assert_eq!(repeated.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                   fused.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
        repeated_ns.sort_unstable();
        fused_ns.sort_unstable();
        let repeated_median = repeated_ns[ROUNDS / 2];
        let fused_median = fused_ns[ROUNDS / 2];
        let ratio = repeated_median as f64 / fused_median as f64;
        eprintln!(
            "JPE_PERLIN_STACK_LARGE_MICROBENCH dims={}x{}x{} layers={} repeats={} rounds={} repeated_median_ms={:.3} fused_median_ms={:.3} fused_speedup={:.3}x",
            SX, SY, SZ, LAYERS, REPEATS, ROUNDS,
            repeated_median as f64 / 1_000_000.0,
            fused_median as f64 / 1_000_000.0,
            ratio
        );
    }

    #[test]
    fn batched_perlin_stack_rejects_invalid_layer_count_and_output_size() {
        let permutation = [0_u8; 256];
        let layer = JpePerlinLayer { permutation: permutation.as_ptr(),
            offset_x: 0.0, offset_y: 0.0, offset_z: 0.0,
            xz_scale: 1.0, y_scale: 1.0, amplitude: 1.0,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            };
        let mut output = [0.0_f32; 2];
        let no_layers = unsafe { jpe_runtime_perlin_add_stack(
            &layer, 0, 1, 1, 1, 0, 0, 0, 1, 1, 1,
            output.as_mut_ptr(), 1, 0
        ) };
        assert_eq!(no_layers, STATUS_INVALID_ARGUMENT);
        let bad_length = unsafe { jpe_runtime_perlin_add_stack(
            &layer, 1, 1, 1, 1, 0, 0, 0, 1, 1, 1,
            output.as_mut_ptr(), 2, 0
        ) };
        assert_eq!(bad_length, STATUS_INVALID_ARGUMENT);
    }

    #[test]
    fn async_perlin_stack_matches_synchronous_stack() {
        let permutation_a: [u8; 256] = std::array::from_fn(|i| ((i * 13 + 7) & 255) as u8);
        let permutation_b: [u8; 256] = std::array::from_fn(|i| ((i * 29 + 3) & 255) as u8);
        let layers = [
            JpePerlinLayer { permutation: permutation_a.as_ptr(),
                offset_x: 0.11, offset_y: 0.27, offset_z: 0.39,
                xz_scale: 0.8, y_scale: 1.2, amplitude: 0.75,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            },
            JpePerlinLayer { permutation: permutation_b.as_ptr(),
                offset_x: -0.21, offset_y: 0.19, offset_z: 0.31,
                xz_scale: 1.1, y_scale: 0.7, amplitude: -0.25,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            },
        ];
        let mut expected = [0.125_f32; 24];
        let mut actual = expected;
        assert_eq!(unsafe { jpe_runtime_perlin_add_stack(
            layers.as_ptr(), 2, 2, 3, 4, -3, -5, 8, 1, 2, 1,
            expected.as_mut_ptr(), 24, 0
        ) }, STATUS_OK);
        let id = unsafe { jpe_runtime_submit_perlin_stack(
            layers.as_ptr(), 2, 2, 3, 4, -3, -5, 8, 1, 2, 1,
            actual.as_mut_ptr(), 24, 0
        ) };
        assert_ne!(id, 0);
        assert_eq!(jpe_runtime_wait_job(id, 2_000_000_000), JOB_COMPLETE);
        assert_eq!(actual.map(f32::to_bits), expected.map(f32::to_bits));
        assert_eq!(jpe_runtime_release_job(id), STATUS_OK);
    }

    #[test]
    fn perlin_stack_validates_all_descriptors_before_mutating_output() {
        let permutation = [0_u8; 256];
        let layers = [
            JpePerlinLayer { permutation: permutation.as_ptr(),
                offset_x: 0.0, offset_y: 0.0, offset_z: 0.0,
                xz_scale: 1.0, y_scale: 1.0, amplitude: 1.0,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            },
            JpePerlinLayer { permutation: std::ptr::null(),
                offset_x: 0.0, offset_y: 0.0, offset_z: 0.0,
                xz_scale: 1.0, y_scale: 1.0, amplitude: 1.0,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            },
        ];
        let mut output = [0.375_f32; 8];
        let before = output;
        let status = unsafe { jpe_cpp_perlin_add_stack(
            layers.as_ptr(), layers.len() as u32,
            2, 2, 2, 0, 0, 0, 1, 1, 1,
            output.as_mut_ptr(), output.len() as u64, 0
        ) };
        assert_eq!(status, STATUS_INVALID_ARGUMENT);
        assert_eq!(output.map(f32::to_bits), before.map(f32::to_bits));
    }

    #[test]
    fn perlin_stack_rejects_invalid_dimensions_without_mutating_output() {
        let permutation = [0_u8; 256];
        let layer = JpePerlinLayer { permutation: permutation.as_ptr(),
            offset_x: 0.0, offset_y: 0.0, offset_z: 0.0,
            xz_scale: 1.0, y_scale: 1.0, amplitude: 1.0,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
                frequency: 0.0,
            };
        let mut output = [0.625_f32; 1];
        let before = output;
        let status = unsafe { jpe_cpp_perlin_add_stack(
            &layer, 1, 1, 1, 1, 0, 0, 0, 0, 1, 1,
            output.as_mut_ptr(), output.len() as u64, 0
        ) };
        assert_eq!(status, STATUS_INVALID_ARGUMENT);
        assert_eq!(output.map(f32::to_bits), before.map(f32::to_bits));
    }

    #[test]
    fn perlin_avx2_matches_scalar_cached_and_benchmarks_run_lengths() {
        use std::time::Instant;
        const SX: usize = 5;
        const SY: usize = 49;
        const SZ: usize = 5;
        const REPEATS: usize = 5;
        const ROUNDS: usize = 21;
        let permutation: [u8; 256] =
            std::array::from_fn(|i| ((i * 37 + 11) & 255) as u8);
        let len = SX * SY * SZ;
        let avx2_available = unsafe { jpe_cpp_perlin_avx2_supported() != 0 };

        // Cover both sides of the dispatch threshold and its boundary.
        // 1/16 is the current AVX2 eligibility limit; larger steps must use scalar.
        for (case, y_scale) in [
            ("long-y-runs-1-64", 0.015625_f64),
            ("long-y-runs-1-32", 0.03125_f64),
            ("threshold-1-16", 0.0625_f64),
            ("scalar-fallback-1-8", 0.125_f64),
            ("scalar-fallback-1-4", 0.25_f64),
            ("scalar-fallback-5-8", 0.625_f64),
        ] {
            let mut vector = vec![0.125_f32; len];
            let mut scalar = vector.clone();
            let run_vector = |out: &mut [f32]| {
                for _ in 0..REPEATS {
                    out.fill(0.125);
                    assert_eq!(unsafe { jpe_cpp_perlin_add_volume(
                        permutation.as_ptr(), SX as u32, SY as u32, SZ as u32,
                        -16, -64, 32, 4, 8, 4,
                        0.13, -0.17, 0.23, 0.375, y_scale, 0.75,
                        out.as_mut_ptr(), len as u64, 0
                    ) }, STATUS_OK);
                }
            };
            let run_scalar = |out: &mut [f32]| {
                for _ in 0..REPEATS {
                    out.fill(0.125);
                    assert_eq!(unsafe { jpe_cpp_perlin_add_volume_scalar_cached_y(
                        permutation.as_ptr(), SX as u32, SY as u32, SZ as u32,
                        -16, -64, 32, 4, 8, 4,
                        0.13, -0.17, 0.23, 0.375, y_scale, 0.75,
                        out.as_mut_ptr(), len as u64, 0
                    ) }, STATUS_OK);
                }
            };
            for _ in 0..2 {
                run_vector(&mut vector);
                run_scalar(&mut scalar);
            }
            let mut vector_ns = Vec::with_capacity(ROUNDS);
            let mut scalar_ns = Vec::with_capacity(ROUNDS);
            for round in 0..ROUNDS {
                if round % 2 == 0 {
                    let start = Instant::now(); run_vector(&mut vector);
                    vector_ns.push(start.elapsed().as_nanos());
                    let start = Instant::now(); run_scalar(&mut scalar);
                    scalar_ns.push(start.elapsed().as_nanos());
                } else {
                    let start = Instant::now(); run_scalar(&mut scalar);
                    scalar_ns.push(start.elapsed().as_nanos());
                    let start = Instant::now(); run_vector(&mut vector);
                    vector_ns.push(start.elapsed().as_nanos());
                }
            }
            assert_eq!(vector.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                       scalar.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
            vector_ns.sort_unstable();
            scalar_ns.sort_unstable();
            let vector_median = vector_ns[ROUNDS / 2];
            let scalar_median = scalar_ns[ROUNDS / 2];
            eprintln!(
                "JPE_PERLIN_AVX2_BENCH case={} avx2_available={} dims={}x{}x{} repeats={} rounds={} scalar_cached_median_ms={:.3} dispatched_median_ms={:.3} speedup={:.3}x",
                case, avx2_available, SX, SY, SZ, REPEATS, ROUNDS,
                scalar_median as f64 / 1_000_000.0,
                vector_median as f64 / 1_000_000.0,
                scalar_median as f64 / vector_median as f64
            );
        }
    }

    #[test]
    fn perlin_avx2_minecraft_profile_workload_microbenchmark() {
        use std::time::Instant;

        // Exercise the common and narrow Y-column shapes observed in the fixed-seed
        // Minecraft profile. These remain kernel microbenchmarks, not end-to-end
        // worldgen claims.
        const SY: usize = 49;
        const REPEATS: usize = 64;
        const ROUNDS: usize = 21;
        let permutation: [u8; 256] =
            std::array::from_fn(|i| ((i * 37 + 11) & 255) as u8);

        for (shape, sx, sz) in [
            ("5x49x5", 5_usize, 5_usize),
            ("2x49x2", 2_usize, 2_usize),
            ("2x49x1", 2_usize, 1_usize),
            ("1x49x2", 1_usize, 2_usize),
            ("1x49x1", 1_usize, 1_usize),
        ] {
            let len = sx * SY * sz;

            // Cases span the step-aware AVX2 dispatch thresholds, including the
            // dominant real stepY=8 workload. Offsets and coordinate ranges
            // deliberately cross lattice floors so the independent-lane
            // branch is exercised.
            for (case, step_y, y_scale) in [
                ("step1-1-64", 1_i32, 1.0 / 64.0),
                ("step1-1-32", 1_i32, 1.0 / 32.0),
                ("step1-1-16", 1_i32, 1.0 / 16.0),
                ("step4-1-256", 4_i32, 1.0 / 256.0),
                ("step4-1-128", 4_i32, 1.0 / 128.0),
                ("step4-1-64", 4_i32, 1.0 / 64.0),
                // Match the dominant real-worldgen vertical step (8 blocks).
                ("step8-1-512", 8_i32, 1.0 / 512.0),
                ("step8-1-256", 8_i32, 1.0 / 256.0),
                ("step8-1-128", 8_i32, 1.0 / 128.0),
                ("step16-1-1024", 16_i32, 1.0 / 1024.0),
                ("step16-1-512", 16_i32, 1.0 / 512.0),
            ] {
                let mut dispatched = vec![0.125_f32; len];
                let mut scalar = dispatched.clone();
                let run_dispatched = |out: &mut [f32]| {
                    for _ in 0..REPEATS {
                        out.fill(0.125);
                        assert_eq!(unsafe { jpe_cpp_perlin_add_volume(
                            permutation.as_ptr(), sx as u32, SY as u32, sz as u32,
                            -32, -64, -32, 4, step_y, 4,
                            0.13, -0.17, 0.23, 0.00390625, y_scale, 0.75,
                            out.as_mut_ptr(), len as u64, 0
                        ) }, STATUS_OK);
                    }
                };
                let run_scalar = |out: &mut [f32]| {
                    for _ in 0..REPEATS {
                        out.fill(0.125);
                        assert_eq!(unsafe { jpe_cpp_perlin_add_volume_scalar_cached_y(
                            permutation.as_ptr(), sx as u32, SY as u32, sz as u32,
                            -32, -64, -32, 4, step_y, 4,
                            0.13, -0.17, 0.23, 0.00390625, y_scale, 0.75,
                            out.as_mut_ptr(), len as u64, 0
                        ) }, STATUS_OK);
                    }
                };

                for _ in 0..2 {
                    run_dispatched(&mut dispatched);
                    run_scalar(&mut scalar);
                }
                let mut dispatched_ns = Vec::with_capacity(ROUNDS);
                let mut scalar_ns = Vec::with_capacity(ROUNDS);
                for round in 0..ROUNDS {
                    if round % 2 == 0 {
                        let start = Instant::now(); run_dispatched(&mut dispatched);
                        dispatched_ns.push(start.elapsed().as_nanos());
                        let start = Instant::now(); run_scalar(&mut scalar);
                        scalar_ns.push(start.elapsed().as_nanos());
                    } else {
                        let start = Instant::now(); run_scalar(&mut scalar);
                        scalar_ns.push(start.elapsed().as_nanos());
                        let start = Instant::now(); run_dispatched(&mut dispatched);
                        dispatched_ns.push(start.elapsed().as_nanos());
                    }
                }
                assert_eq!(dispatched.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                           scalar.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
                dispatched_ns.sort_unstable();
                scalar_ns.sort_unstable();
                let dispatched_median = dispatched_ns[ROUNDS / 2];
                let scalar_median = scalar_ns[ROUNDS / 2];
                eprintln!(
                    "JPE_PERLIN_AVX2_CROSS_FLOOR_SHAPE shape={} case={} avx2_available={} dims={}x{}x{} steps=4x{}x4 repeats={} rounds={} scalar_median_ms={:.3} dispatched_median_ms={:.3} speedup={:.3}x",
                    shape, case, unsafe { jpe_cpp_perlin_avx2_supported() != 0 },
                    sx, SY, sz, step_y, REPEATS, ROUNDS,
                    scalar_median as f64 / 1_000_000.0,
                    dispatched_median as f64 / 1_000_000.0,
                    scalar_median as f64 / dispatched_median as f64
                );
            }
        }
    }

    #[test]
    fn unknown_job_ids_are_rejected() { assert_eq!(jpe_runtime_job_status(u64::MAX), -1); }

    #[test]
    fn density_graph_subtracts_float32_values_across_a_full_volume() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 3.5 },
            JpeDensityNode { opcode: DENSITY_SUB, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0 },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 2 , 10, 0x5AB)
        };
        assert_ne!(handle, 0);
        let mut output = [0.0_f32; 4];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, 2, 2, 1, 10, 3, 0, 1, 1, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, 0);
        assert_eq!(output, [6.5, 6.5, 7.5, 7.5]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }

    #[test]
    fn density_graph_reads_batched_external_volume_inputs() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0 },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 2 , 10, 0x1A9)
        };
        assert_ne!(handle, 0);
        // Input-major layout, with samples ordered ((z * size_x + x) * size_y + y).
        let inputs = [1.0_f32, 2.0, 3.0, 4.0];
        let mut output = [0.0_f32; 4];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_with_inputs(
                handle, 2, 2, 1, 10, 0, 0, 1, 1, 1,
                inputs.as_ptr(), 1, output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(output, [11.0, 12.0, 14.0, 15.0]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }


    #[test]
    fn density_graph_evaluates_multiple_shared_roots_in_one_pass() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_INPUT, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_SUB, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0 },
        ];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 2, 10, 0xA117)
        };
        assert_ne!(handle, 0);
        // Two input-major volumes: each input's stride is volume_length (4), not output_length (12).
        let inputs = [10.0_f32, 20.0, 30.0, 40.0, 1.0, 2.0, 3.0, 4.0];
        let roots = [2_u32, 3_u32, 2_u32];
        let mut outputs = [0.0_f32; 12];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_multi_with_inputs(
                handle, 2, 2, 1, 10, 3, 0, 1, 1, 1,
                inputs.as_ptr(), 2, roots.as_ptr(), roots.len() as u32,
                outputs.as_mut_ptr(), outputs.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(&outputs[0..4], &[11.0, 22.0, 33.0, 44.0]);
        assert_eq!(&outputs[4..8], &[9.0, 18.0, 27.0, 36.0]);
        assert_eq!(&outputs[8..12], &[11.0, 22.0, 33.0, 44.0]);
        let mut oversized = [0.0_f32; 11];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_multi_with_inputs(
                handle, 2, 2, 1, 10, 3, 0, 1, 1, 1,
                inputs.as_ptr(), 2, roots.as_ptr(), roots.len() as u32,
                oversized.as_mut_ptr(), oversized.len() as u64,
            )
        }, STATUS_INVALID_ARGUMENT);
        let bad_roots = [2_u32, u32::MAX];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_multi_with_inputs(
                handle, 2, 2, 1, 10, 3, 0, 1, 1, 1,
                inputs.as_ptr(), 2, bad_roots.as_ptr(), bad_roots.len() as u32,
                outputs.as_mut_ptr(), 8,
            )
        }, STATUS_INVALID_ARGUMENT);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
    }



    #[test]
    fn blender_density_volume_preserves_empty_fallback_and_fixed_density_override() {
        let noise = [7.25_f32, -3.5_f32];
        let mut output = [0.0_f32; 2];
        assert_eq!(unsafe {
            jpe_runtime_blend_density_volume(
                std::ptr::null(), 0, std::ptr::null(), 0,
                1, 2, 1, 0, -32, 0, 1, 1, 1,
                noise.as_ptr(), output.as_mut_ptr(), 2,
            )
        }, STATUS_OK);
        assert_eq!(output, noise);

        let mut column = vec![0.0_f32; 16];
        column[3] = 5.0;
        let mut density_columns = [std::ptr::null(); 16];
        density_columns[3] = column.as_ptr();
        let view = JpeBlendingDataView {
            chunk_x: 0, chunk_z: 0, min_section_y: -4, section_count: 8,
            density_columns,
        };
        let one_noise = [7.25_f32];
        let mut one_output = [0.0_f32; 1];
        assert_eq!(unsafe {
            jpe_runtime_blend_density_volume(
                &view, 1, std::ptr::null(), 0,
                1, 1, 1, 0, -32, 0, 1, 1, 1,
                one_noise.as_ptr(), one_output.as_mut_ptr(), 1,
            )
        }, STATUS_OK);
        assert_eq!(one_output[0].to_bits(), 0.5_f32.to_bits());
    }

    #[test]
    fn blender_density_volume_executes_native_neighborhood_weighting() {
        let mut column = vec![0.0_f32; 16];
        column[3] = 10.0;
        let mut density_columns = [std::ptr::null(); 16];
        density_columns[3] = column.as_ptr();
        let view = JpeBlendingDataView {
            chunk_x: 0, chunk_z: 0, min_section_y: -4, section_count: 8,
            density_columns,
        };
        let noise = [4.0_f32];
        let mut output = [0.0_f32; 1];
        assert_eq!(unsafe {
            jpe_runtime_blend_density_volume(
                std::ptr::null(), 0, &view, 1,
                1, 1, 1, 4, -32, 0, 1, 1, 1,
                noise.as_ptr(), output.as_mut_ptr(), 1,
            )
        }, STATUS_OK);
        assert_eq!(output[0].to_bits(), 2.0_f32.to_bits());
    }

    #[test]
    fn blender_density_volume_uses_minecraft_float32_distance_before_double_weighting() {
        let mut first_column = vec![0.0_f32; 16];
        let mut second_column = vec![0.0_f32; 16];
        // Query cell (1, -4, 1): these points are at sqrt(2) and 1 respectively.
        // The raw density values are scaled by BlendingData's 0.1f factor.
        first_column[3] = 10.0;
        second_column[3] = 30.0;
        let mut density_columns = [std::ptr::null(); 16];
        density_columns[3] = first_column.as_ptr();  // (0, 0)
        density_columns[4] = second_column.as_ptr(); // (0, 1)
        let view = JpeBlendingDataView {
            chunk_x: 0, chunk_z: 0, min_section_y: -4, section_count: 8,
            density_columns,
        };
        let noise = [-11.181_111_f32];
        let mut output = [0.0_f32; 1];
        assert_eq!(unsafe {
            jpe_runtime_blend_density_volume(
                std::ptr::null(), 0, &view, 1,
                1, 1, 1, 4, -32, 4, 1, 1, 1,
                noise.as_ptr(), output.as_mut_ptr(), 1,
            )
        }, STATUS_OK);
        // Mth.length(float,float,float) rounds sqrt(2) to float32 before
        // widening for the double-precision inverse-fourth-power weighting.
        assert_eq!(output[0].to_bits(), 0xbfff31b0);
    }

    #[test]
    fn blender_density_volume_ordered_bucket_merge_matches_vanilla_full_chunk_reference() {
        // Nine neighboring legacy chunks, all 16 density columns populated.
        // The 5 x 49 x 5 volume mirrors the full Minecraft density lattice shape.
        let mut backing: Vec<Vec<f32>> = Vec::new();
        for view in 0..9 {
            for column in 0..16 {
                let values = (0..16).map(|y| {
                    let raw = ((view * 37 + column * 19 + y * 11) % 97) as f32;
                    (raw - 31.0) * 0.137_f32
                }).collect();
                backing.push(values);
            }
        }
        let views: Vec<JpeBlendingDataView> = (0..9).map(|view| {
            let density_columns = std::array::from_fn(|column| {
                backing[view * 16 + column].as_ptr()
            });
            JpeBlendingDataView {
                chunk_x: (view % 3) as i32,
                chunk_z: (view / 3) as i32,
                min_section_y: -4,
                section_count: 8,
                density_columns,
            }
        }).collect();

        const SIZE_X: usize = 5;
        const SIZE_Y: usize = 49;
        const SIZE_Z: usize = 5;
        let count = SIZE_X * SIZE_Y * SIZE_Z;
        let noise: Vec<f32> = (0..count).map(|i| {
            ((i * 29 % 211) as f32 - 103.0) * 0.03125_f32
        }).collect();
        let mut actual = vec![0.0_f32; count];
        assert_eq!(unsafe {
            jpe_runtime_blend_density_volume(
                std::ptr::null(), 0, views.as_ptr(), views.len() as u32,
                SIZE_X as u32, SIZE_Y as u32, SIZE_Z as u32,
                52, -64, 52, 4, 8, 4,
                noise.as_ptr(), actual.as_mut_ptr(), count as u64,
            )
        }, STATUS_OK);

        let column_x = |column: usize| -> i32 {
            if column < 7 {
                (3 - column as i32).max(0)
            } else {
                4 - (4 - (column as i32 - 7)).max(0)
            }
        };
        let column_z = |column: usize| -> i32 {
            if column < 7 {
                (column as i32 - 3).max(0)
            } else {
                4 - ((column as i32 - 7) - 4).max(0)
            }
        };
        let mut expected = vec![0.0_f32; count];
        for z in 0..SIZE_Z {
            for x in 0..SIZE_X {
                for y in 0..SIZE_Y {
                    let index = (z * SIZE_X + x) * SIZE_Y + y;
                    let block_x = 52 + x as i32 * 4;
                    let block_y = -64 + y as i32 * 8;
                    let block_z = 52 + z as i32 * 4;
                    let cell_x = block_x.div_euclid(4);
                    let cell_y = block_y / 8; // Java integer division truncates toward zero.
                    let cell_z = block_z.div_euclid(4);
                    let mut total_weight = 0.0_f64;
                    let mut weighted_density = 0.0_f64;
                    let mut closest = f64::INFINITY;

                    // Vanilla order: fastutil map iteration, column order, then ascending Y.
                    for (view_index, view) in views.iter().enumerate() {
                        let origin_x = view.chunk_x * 4;
                        let origin_z = view.chunk_z * 4;
                        for column in 0..16 {
                            let point_x = origin_x + column_x(column);
                            let point_z = origin_z + column_z(column);
                            for point_y_index in 0..16 {
                                let point_y = -7 + point_y_index as i32;
                                if point_y < cell_y - 1 || point_y >= cell_y + 1 {
                                    continue;
                                }
                                let dx = (cell_x - point_x) as f32;
                                let dy = ((cell_y - point_y) * 2) as f32;
                                let dz = (cell_z - point_z) as f32;
                                let squared = (dx * dx + dy * dy) + dz * dz;
                                let distance = (squared as f64).sqrt() as f32 as f64;
                                if distance > 2.0 { continue; }
                                closest = closest.min(distance);
                                let weight = 1.0 / (distance * distance * distance * distance);
                                let density = backing[view_index * 16 + column][point_y_index] * 0.1_f32;
                                weighted_density += density as f64 * weight;
                                total_weight += weight;
                            }
                        }
                    }
                    if closest.is_infinite() {
                        expected[index] = noise[index];
                    } else {
                        let average = weighted_density / total_weight;
                        let alpha = (closest / 3.0).clamp(0.0, 1.0);
                        expected[index] = (average + alpha * (noise[index] as f64 - average)) as f32;
                    }
                }
            }
        }

        for index in 0..count {
            assert_eq!(
                actual[index].to_bits(), expected[index].to_bits(),
                "full-chunk Blender parity mismatch at z/x/y flattened index {index}"
            );
        }
    }

    #[cfg(feature = "density-graph-benchmark")]
    #[test]
    fn blender_density_volume_full_chunk_microbenchmark() {
        use std::hint::black_box;
        use std::time::Instant;

        // Mirror the correctness fixture: nine neighboring legacy chunks,
        // all density columns populated, and Minecraft's complete 5x49x5 lattice.
        const SIZE_X: usize = 5;
        const SIZE_Y: usize = 49;
        const SIZE_Z: usize = 5;
        const REPEATS: usize = 3;
        const ROUNDS: usize = 21;
        let mut backing: Vec<Vec<f32>> = Vec::with_capacity(9 * 16);
        for view in 0..9 {
            for column in 0..16 {
                backing.push((0..48).map(|y| {
                    let raw = ((view * 37 + column * 19 + y * 11) % 97) as f32;
                    (raw - 31.0) * 0.137_f32
                }).collect());
            }
        }
        let views: Vec<JpeBlendingDataView> = (0..9).map(|view| {
            let density_columns = std::array::from_fn(|column| {
                backing[view * 16 + column].as_ptr()
            });
            JpeBlendingDataView {
                chunk_x: (view % 3) as i32,
                chunk_z: (view / 3) as i32,
                min_section_y: -4,
                section_count: 24,
                density_columns,
            }
        }).collect();
        let count = SIZE_X * SIZE_Y * SIZE_Z;
        let noise: Vec<f32> = (0..count).map(|i| {
            ((i * 29 % 211) as f32 - 103.0) * 0.03125_f32
        }).collect();
        let mut heap_output = vec![0.0_f32; count];
        let mut sort_output = vec![0.0_f32; count];

        let run_heap = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                assert_eq!(unsafe {
                    jpe_runtime_blend_density_volume(
                        std::ptr::null(), 0, views.as_ptr(), views.len() as u32,
                        SIZE_X as u32, SIZE_Y as u32, SIZE_Z as u32,
                        52, -64, 52, 4, 8, 4,
                        noise.as_ptr(), out.as_mut_ptr(), count as u64,
                    )
                }, STATUS_OK);
                black_box(out[count / 2]);
            }
        };
        let run_vector_sort = |out: &mut [f32]| {
            for _ in 0..REPEATS {
                assert_eq!(unsafe {
                    jpe_cpp_blend_density_volume_vector_sort_reference(
                        std::ptr::null(), 0, views.as_ptr(), views.len() as u32,
                        SIZE_X as u32, SIZE_Y as u32, SIZE_Z as u32,
                        52, -64, 52, 4, 8, 4,
                        noise.as_ptr(), out.as_mut_ptr(), count as u64,
                    )
                }, STATUS_OK);
                black_box(out[count / 2]);
            }
        };

        // Warm both implementations, then alternate order to reduce drift.
        for _ in 0..2 {
            run_heap(&mut heap_output);
            run_vector_sort(&mut sort_output);
        }
        let mut heap_samples = Vec::with_capacity(ROUNDS);
        let mut sort_samples = Vec::with_capacity(ROUNDS);
        for round in 0..ROUNDS {
            if round % 2 == 0 {
                let start = Instant::now();
                run_heap(&mut heap_output);
                heap_samples.push(start.elapsed().as_nanos());
                let start = Instant::now();
                run_vector_sort(&mut sort_output);
                sort_samples.push(start.elapsed().as_nanos());
            } else {
                let start = Instant::now();
                run_vector_sort(&mut sort_output);
                sort_samples.push(start.elapsed().as_nanos());
                let start = Instant::now();
                run_heap(&mut heap_output);
                heap_samples.push(start.elapsed().as_nanos());
            }
        }
        assert_eq!(
            heap_output.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
            sort_output.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
            "linked-heap and vector-sort Blender outputs must be bit-identical"
        );
        heap_samples.sort_unstable();
        sort_samples.sort_unstable();
        let heap_median = heap_samples[ROUNDS / 2];
        let sort_median = sort_samples[ROUNDS / 2];
        let heap_p95 = heap_samples[(ROUNDS * 95 / 100).min(ROUNDS - 1)];
        let sort_p95 = sort_samples[(ROUNDS * 95 / 100).min(ROUNDS - 1)];
        eprintln!(
            "JPE_BLENDER_FULL_CHUNK_BENCH dims={}x{}x{} views={} points={} repeats={} rounds={} dense_grid_heap_median_ms={:.3} dense_grid_heap_p95_ms={:.3} vector_sort_median_ms={:.3} vector_sort_p95_ms={:.3} speedup={:.3}x",
            SIZE_X, SIZE_Y, SIZE_Z, views.len(), backing.len() * 48,
            REPEATS, ROUNDS,
            heap_median as f64 / 1_000_000.0,
            heap_p95 as f64 / 1_000_000.0,
            sort_median as f64 / 1_000_000.0,
            sort_p95 as f64 / 1_000_000.0,
            sort_median as f64 / heap_median as f64
        );
    }

    #[test]
    fn blender_density_volume_sparse_spatial_fallback_matches_dense_local_result() {
        let near_columns: Vec<Vec<f32>> = (0..16).map(|column| {
            (0..16).map(|y| ((column * 17 + y * 13) % 101) as f32 - 37.0).collect()
        }).collect();
        let far_columns: Vec<Vec<f32>> = (0..16).map(|column| {
            (0..16).map(|y| ((column * 29 + y * 7) % 89) as f32 + 11.0).collect()
        }).collect();
        let near_view = JpeBlendingDataView {
            chunk_x: 0,
            chunk_z: 0,
            min_section_y: -4,
            section_count: 8,
            density_columns: std::array::from_fn(|column| near_columns[column].as_ptr()),
        };
        let far_view = JpeBlendingDataView {
            chunk_x: 100_000,
            chunk_z: -100_000,
            min_section_y: -4,
            section_count: 8,
            density_columns: std::array::from_fn(|column| far_columns[column].as_ptr()),
        };
        let views = [near_view, far_view];
        let noise = [0.375_f32];
        let mut dense_output = [0.0_f32];
        let mut sparse_output = [0.0_f32];

        // The single local view fits the direct-address grid. Adding a very
        // distant view forces the bounded sparse-hash fallback, but that view
        // cannot contribute to this query's 5x5x2 neighborhood.
        assert_eq!(unsafe {
            jpe_runtime_blend_density_volume(
                std::ptr::null(), 0, &near_view, 1,
                1, 1, 1, 8, -32, 8, 1, 1, 1,
                noise.as_ptr(), dense_output.as_mut_ptr(), 1,
            )
        }, STATUS_OK);
        assert_eq!(unsafe {
            jpe_runtime_blend_density_volume(
                std::ptr::null(), 0, views.as_ptr(), views.len() as u32,
                1, 1, 1, 8, -32, 8, 1, 1, 1,
                noise.as_ptr(), sparse_output.as_mut_ptr(), 1,
            )
        }, STATUS_OK);
        assert_eq!(sparse_output[0].to_bits(), dense_output[0].to_bits());
        assert_ne!(sparse_output[0].to_bits(), noise[0].to_bits());
    }

    #[test]
    fn blender_density_volume_handles_negative_coordinates_and_boundary_fallback() {
        let mut column = vec![0.0_f32; 16];
        column[3] = 12.5;
        let mut density_columns = [std::ptr::null(); 16];
        density_columns[11] = column.as_ptr();
        let view = JpeBlendingDataView {
            chunk_x: -1, chunk_z: -1, min_section_y: -4, section_count: 8,
            density_columns,
        };
        let noise = [9.0_f32];
        let mut output = [0.0_f32; 1];
        assert_eq!(unsafe {
            jpe_runtime_blend_density_volume(
                &view, 1, std::ptr::null(), 0,
                1, 1, 1, 0, -32, 0, 1, 1, 1,
                noise.as_ptr(), output.as_mut_ptr(), 1,
            )
        }, STATUS_OK);
        assert_eq!(output[0].to_bits(), 1.25_f32.to_bits());
    }

    #[test]
    fn density_graph_executes_integer_clamped_gradient_without_float_coordinate_loss() {
        let packed = f64::from_bits(0x3e80_0000_3f00_0000);
        let nodes = [JpeDensityNode { opcode: DENSITY_GRADIENT_CLAMPED_Y,
            input_a: 1, input_b: 1, reserved: 2, immediate: packed }];
        let handle = unsafe { jpe_runtime_density_graph_compile(nodes.as_ptr(), 1, 0, 10, 0x6A4D) };
        assert_ne!(handle, 0);
        let mut output = [0.0_f32; 4];
        assert_eq!(unsafe { jpe_runtime_density_graph_evaluate(handle, 1, 4, 1,
            0, 0, 0, 1, 1, 1, output.as_mut_ptr(), 4) }, STATUS_OK);
        assert_eq!(output, [0.25, 0.25, 0.75, 0.75]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);

        let wrap_nodes = [JpeDensityNode { opcode: DENSITY_GRADIENT_CLAMPED_Y,
            input_a: i32::MAX as u32, input_b: i32::MIN as u32,
            reserved: i32::MAX as u32, immediate: packed }];
        let wrap_handle = unsafe { jpe_runtime_density_graph_compile(wrap_nodes.as_ptr(), 1, 0, 10, 0x6A4E) };
        assert_ne!(wrap_handle, 0);
        let mut wrapped = [0.0_f32; 1];
        assert_eq!(unsafe { jpe_runtime_density_graph_evaluate(wrap_handle, 1, 1, 1,
            0, i32::MIN, 0, 1, 1, 1, wrapped.as_mut_ptr(), 1) }, STATUS_OK);
        assert_eq!(wrapped[0].to_bits(), 0.75_f32.to_bits());
        assert_eq!(unsafe { jpe_runtime_density_graph_release(wrap_handle) }, STATUS_OK);

        let precision_nodes = [JpeDensityNode { opcode: DENSITY_GRADIENT_CLAMPED_Y,
            input_a: 16_777_216, input_b: 16_777_217, reserved: 16_777_217,
            immediate: f64::from_bits(0x3f80_0000_3f80_0000) }];
        let precision_handle = unsafe { jpe_runtime_density_graph_compile(precision_nodes.as_ptr(), 1, 0, 10, 0x6A4F) };
        assert_ne!(precision_handle, 0);
        let mut precise = [0.0_f32; 1];
        assert_eq!(unsafe { jpe_runtime_density_graph_evaluate(precision_handle, 1, 1, 1,
            0, 16_777_217, 0, 1, 1, 1, precise.as_mut_ptr(), 1) }, STATUS_OK);
        assert_eq!(precise[0].to_bits(), 2.0_f32.to_bits());
        assert_eq!(unsafe { jpe_runtime_density_graph_release(precision_handle) }, STATUS_OK);
    }

}


#[cfg(test)]
mod terrain_link_tests {
    use crate::terrain_ffi::{
        native_terrain_abi, native_default_config, TerrainConfig, TerrainEngine,
        CHUNK_BLOCKS_DEFAULT_HEIGHT, TERRAIN_CONFIG_BYTES, TERRAIN_STAGE_ALL,
        TERRAIN_STREAM_ABI_VERSION,
    };

    /// The two ABI numbers are maintained by hand on both sides. If they ever
    /// drift, every other test in this module is meaningless.
    #[test]
    fn terrain_abi_versions_match() {
        let abi = native_terrain_abi();
        assert_eq!(
            abi.stream, TERRAIN_STREAM_ABI_VERSION,
            "Rust expects stream ABI {}, C++ reports {}",
            TERRAIN_STREAM_ABI_VERSION, abi.stream
        );
        assert!(
            abi.generator >= 1,
            "generator ABI too old: {}",
            abi.generator
        );
    }

    /// Field order and widths must match `JpeTerrainConfig` byte for byte. A
    /// mismatch here does not fail loudly -- it produces a config with a
    /// plausible-looking seed and a nonsense height.
    #[test]
    fn rust_defaults_match_cpp_defaults() {
        use std::mem::{align_of, size_of};
        assert_eq!(size_of::<TerrainConfig>(), TERRAIN_CONFIG_BYTES);
        assert_eq!(align_of::<TerrainConfig>(), 8);

        let ours = TerrainConfig::overworld(0);
        let theirs = native_default_config();
        assert_eq!(ours.min_y, theirs.min_y, "min_y drifted");
        assert_eq!(ours.height, theirs.height, "height drifted");
        assert_eq!(ours.sea_level, theirs.sea_level, "sea_level drifted");
        assert_eq!(ours.lava_level, theirs.lava_level, "lava_level drifted");
        assert_eq!(ours.stages, theirs.stages, "stages drifted");
    }

    /// The chunk size is baked into pointer arithmetic on both sides.
    #[test]
    fn chunk_geometry_is_as_documented() {
        let engine = TerrainEngine::overworld(1).expect("create");
        assert_eq!(engine.chunk_blocks(), CHUNK_BLOCKS_DEFAULT_HEIGHT);
        assert_eq!(engine.chunk_bytes(), CHUNK_BLOCKS_DEFAULT_HEIGHT * 2);
    }

    /// The whole point of the cache: reading a chunk through it must equal
    /// generating that chunk directly. Anything else means corrupted terrain.
    #[test]
    fn cached_chunk_matches_direct_chunk() {
        let mut engine = TerrainEngine::overworld(42).expect("create");
        engine.set_budget(32 * 1024 * 1024).expect("budget");
        engine.stream(0, 0, 2, 0).expect("stream");

        let mut direct = vec![0u16; engine.chunk_blocks() as usize];

        for cz in -1..=1i32 {
            for cx in -1..=1i32 {
                engine.generate_chunk(cx, cz, &mut direct).expect("generate");
                let cached = engine.acquire(cx, cz).expect("acquire");
                assert_eq!(direct.len(), cached.len(), "length at {},{}", cx, cz);
                assert!(
                    direct.iter().eq(cached.iter()),
                    "cached chunk differs from direct chunk at {},{}",
                    cx,
                    cz
                );
                engine.release(cx, cz);
            }
        }
    }

    /// Streaming a second, adjacent window must cost less than the window
    /// itself: only the difference is generated. This is the property that
    /// makes continuous loading cheap, so it is asserted rather than assumed.
    #[test]
    fn moving_the_window_generates_only_the_difference() {
        let mut engine = TerrainEngine::overworld(7).expect("create");
        engine.set_budget(96 * 1024 * 1024).expect("budget");

        let radius = 4i32;
        // stream_window fills a disc of radius `radius`, not a square, so the
        // first pass generates exactly the lattice points inside it (49 for
        // r = 4); the prefetch ring is only used when the caller asks for one.
        let window = (-radius..=radius)
            .flat_map(|dz| (-radius..=radius).map(move |dx| (dx, dz)))
            .filter(|&(dx, dz)| dx * dx + dz * dz <= radius * radius)
            .count() as i32;
        let first = engine.stream(0, 0, radius, 0).expect("first stream");
        assert_eq!(first, window, "first stream should generate the whole window");

        // One chunk to the right: a column leaves and a column enters.
        let second = engine.stream(1, 0, radius, 0).expect("second stream");
        let column = (2 * radius + 1) as i32;
        assert!(
            second <= column,
            "moving one chunk generated {} chunks, expected at most {}",
            second,
            column
        );
    }

    /// The budget must hold: resident never exceeds capacity, no matter how
    /// far the window travels.
    #[test]
    fn resident_never_exceeds_capacity() {
        let mut engine = TerrainEngine::overworld(11).expect("create");
        // Two chunks' worth: the cache must evict on every move.
        let per_chunk = engine.chunk_bytes();
        engine.set_budget(per_chunk * 2).expect("budget");

        for step in 0..6i32 {
            engine.stream(step, 0, 3, 0).expect("stream");
            let stats = engine.stats().expect("stats");
            assert!(
                stats.resident <= stats.capacity,
                "resident {} > capacity {} at step {}",
                stats.resident,
                stats.capacity,
                step
            );
        }
    }

    /// A budget smaller than one chunk disables the cache outright instead of
    /// pretending to work. acquire must then fail rather than return garbage.
    #[test]
    fn budget_below_one_chunk_disables_the_cache() {
        let mut engine = TerrainEngine::overworld(3).expect("create");
        let per_chunk = engine.chunk_bytes();
        engine.set_budget(per_chunk - 1).expect("budget accepted");

        assert!(
            engine.acquire(0, 0).is_none(),
            "acquire succeeded with a budget of zero chunks"
        );
    }
}
