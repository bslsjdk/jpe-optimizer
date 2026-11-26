use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex, OnceLock};
use std::thread;
use std::time::Duration;

const ABI_VERSION: u32 = 7;
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
struct JpeDensityNode {
    opcode: u32,
    input_a: u32,
    input_b: u32,
    reserved: u32,
    immediate: f64,
    auxiliary: f64,
    tertiary: f64,
}

const _: [(); 40] = [(); std::mem::size_of::<JpeDensityNode>()];

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct JpeAquiferFluidStatus {
    pub fluid_level: i32,
    pub fluid_type: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct JpeAquiferFluidProbe {
    pub status: JpeAquiferFluidStatus,
    pub valid: u32,
    pub fluid_type_is_air: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct JpeAquiferFluidStatusInput {
    pub block_y: i32,
    pub global_fluid: JpeAquiferFluidStatus,
    pub surface_level: [i32; 13],
    pub global_fluid_at_surface: [JpeAquiferFluidProbe; 13],
    pub exclusion_noise: f32,
    pub floodedness_noise: f32,
    pub fluid_level_spread_noise: f32,
    pub lava_noise: f32,
    pub lava_type: u32,
}

const _: [(); 16] = [(); std::mem::size_of::<JpeAquiferFluidProbe>()];
const _: [(); 292] = [(); std::mem::size_of::<JpeAquiferFluidStatusInput>()];
const _: [(); 4] = [(); std::mem::offset_of!(JpeAquiferFluidStatusInput, global_fluid)];
const _: [(); 12] = [(); std::mem::offset_of!(JpeAquiferFluidStatusInput, surface_level)];
const _: [(); 64] = [(); std::mem::offset_of!(JpeAquiferFluidStatusInput, global_fluid_at_surface)];
const _: [(); 272] = [(); std::mem::offset_of!(JpeAquiferFluidStatusInput, exclusion_noise)];
const _: [(); 288] = [(); std::mem::offset_of!(JpeAquiferFluidStatusInput, lava_type)];

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct JpeAquiferContextCreateInfo {
    pub density_graph_handles: [u64; 6],
    pub world_fingerprint: [u8; 32],
    pub semantic_fingerprint: u64,
    pub seed_lo: u64,
    pub seed_hi: u64,
    pub random_mode: u32,
    pub air_type: u32,
    pub water_type: u32,
    pub lava_type: u32,
    pub max_status_cache_entries: u32,
    pub reserved: u32,
}

const _: [(); 128] = [(); std::mem::size_of::<JpeAquiferContextCreateInfo>()];
const _: [(); 48] = [(); std::mem::offset_of!(JpeAquiferContextCreateInfo, world_fingerprint)];
const _: [(); 80] = [(); std::mem::offset_of!(JpeAquiferContextCreateInfo, semantic_fingerprint)];
const _: [(); 104] = [(); std::mem::offset_of!(JpeAquiferContextCreateInfo, random_mode)];
const _: [(); 120] = [(); std::mem::offset_of!(JpeAquiferContextCreateInfo, max_status_cache_entries)];

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct JpeAquiferCoreInput {
    pub block_y: i32,
    pub density: f64,
    pub barrier_noise: f32,
    pub distance_sqr: [i32; 4],
    pub closest: [JpeAquiferFluidStatus; 4],
    pub global_fluid_below_type: u32,
    pub air_type: u32,
    pub water_type: u32,
    pub lava_type: u32,
    pub debug_disable_fluid_generation: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct JpeAquiferCoreOutput {
    pub has_block_state: u32,
    pub block_state_type: u32,
    pub should_schedule_fluid_update: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
struct JpeAquiferCellLocation {
    x: i32,
    y: i32,
    z: i32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct JpeAquiferNearestFour {
    index: [u32; 4],
    distance_sqr: [i32; 4],
}

const _: [(); 8] = [(); std::mem::size_of::<JpeAquiferFluidStatus>()];
const _: [(); 88] = [(); std::mem::size_of::<JpeAquiferCoreInput>()];
const _: [(); 12] = [(); std::mem::size_of::<JpeAquiferCoreOutput>()];
const _: [(); 12] = [(); std::mem::size_of::<JpeAquiferCellLocation>()];
const _: [(); 32] = [(); std::mem::size_of::<JpeAquiferNearestFour>()];

const DENSITY_NO_INPUT: u32 = u32::MAX;
const DENSITY_CONST: u32 = 1;
const DENSITY_X: u32 = 2;
const DENSITY_Y: u32 = 3;
const DENSITY_Z: u32 = 4;
const DENSITY_ADD: u32 = 5;
const DENSITY_MUL: u32 = 6;
const DENSITY_SUB: u32 = 8;
const DENSITY_ABS: u32 = 12;
const DENSITY_CLAMP: u32 = 22;
const DENSITY_PERLIN_STACK_WARP: u32 = 31;
const DENSITY_SLICE: u32 = 32;

unsafe extern "C" {
    fn jpe_cpp_density_graph_compile(
        nodes: *const JpeDensityNode, node_count: u32, root_node: u32,
        configuration_fingerprint: u64,
    ) -> u64;
    fn jpe_cpp_density_graph_evaluate(
        graph_handle: u64,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        output: *mut f64, output_length: u64,
    ) -> i32;
    fn jpe_cpp_density_graph_evaluate_f32(
        graph_handle: u64,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        output: *mut f32, output_length: u64,
    ) -> i32;
    fn jpe_cpp_density_graph_release(graph_handle: u64) -> i32;
    fn jpe_cpp_density_graph_evaluate_points(
        graph_handle: u64,
        point_x: *const i32, point_y: *const i32, point_z: *const i32,
        point_count: u32, output: *mut f64, output_length: u64,
    ) -> i32;
    fn jpe_cpp_density_graph_evaluate_points_f32(
        graph_handle: u64,
        point_x: *const i32, point_y: *const i32, point_z: *const i32,
        point_count: u32, output: *mut f32, output_length: u64,
    ) -> i32;
    fn jpe_cpp_density_graph_evaluate_multi_points(
        graph_handles: *const u64, graph_count: u32,
        point_x: *const i32, point_y: *const i32, point_z: *const i32,
        points_per_graph: u32, output: *mut f64, output_length: u64,
    ) -> i32;
    fn jpe_cpp_density_graph_evaluate_multi_points_f32(
        graph_handles: *const u64, graph_count: u32,
        point_x: *const i32, point_y: *const i32, point_z: *const i32,
        points_per_graph: u32, output: *mut f32, output_length: u64,
    ) -> i32;
    fn jpe_cpp_aquifer_compute_substance_core(
        input: *const JpeAquiferCoreInput,
        output: *mut JpeAquiferCoreOutput,
    ) -> i32;
    fn jpe_cpp_aquifer_compute_fluid_status_batch(
        inputs: *const JpeAquiferFluidStatusInput,
        count: u32,
        outputs: *mut JpeAquiferFluidStatus,
    ) -> i32;
    fn jpe_cpp_aquifer_context_create(info: *const JpeAquiferContextCreateInfo) -> u64;
    fn jpe_cpp_aquifer_context_destroy(context_handle: u64) -> i32;
    fn jpe_cpp_aquifer_context_evaluate_samplers_f32(
        context_handle: u64,
        expected_world_fingerprint: *const u8,
        expected_semantic_fingerprint: u64,
        point_x: *const i32, point_y: *const i32, point_z: *const i32,
        points_per_sampler: u32, output: *mut f32, output_length: u64,
    ) -> i32;
    fn jpe_cpp_aquifer_context_compute_fluid_status_batch(
        context_handle: u64,
        expected_world_fingerprint: *const u8,
        expected_semantic_fingerprint: u64,
        cache_keys: *const u64,
        inputs: *const JpeAquiferFluidStatusInput,
        count: u32,
        outputs: *mut JpeAquiferFluidStatus,
    ) -> i32;
    fn jpe_cpp_aquifer_context_configure_global_fluid_picker(
        context_handle: u64,
        expected_world_fingerprint: *const u8,
        expected_semantic_fingerprint: u64,
        sea_level: i32,
        min_y: i32,
        default_fluid_type: u32,
        default_fluid_is_air: u32,
        debug_disable_fluid_generation: u32,
    ) -> i32;
    fn jpe_cpp_aquifer_context_compute_fluid_status_points_batch(
        context_handle: u64,
        expected_world_fingerprint: *const u8,
        expected_semantic_fingerprint: u64,
        cache_keys: *const u64,
        point_x: *const i32, point_y: *const i32, point_z: *const i32,
        count: u32,
        outputs: *mut JpeAquiferFluidStatus,
    ) -> i32;
    fn jpe_cpp_aquifer_context_compute_substance_volume_f32(
        context_handle: u64,
        expected_world_fingerprint: *const u8,
        expected_semantic_fingerprint: u64,
        skip_sampling_above_y: i32,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        density_values: *const f32,
        density_count: u32,
        outputs: *mut JpeAquiferCoreOutput,
    ) -> i32;
    fn jpe_cpp_aquifer_context_compute_density_and_substance_volume_f32(
        context_handle: u64,
        expected_world_fingerprint: *const u8,
        expected_semantic_fingerprint: u64,
        final_density_graph_handle: u64,
        skip_sampling_above_y: i32,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        outputs: *mut JpeAquiferCoreOutput,
    ) -> i32;
    fn jpe_cpp_aquifer_compute_substance_batch(
        inputs: *const JpeAquiferCoreInput,
        count: u32,
        outputs: *mut JpeAquiferCoreOutput,
    ) -> i32;
    fn jpe_runtime_aquifer_find_nearest_four(
        block_x: i32, block_y: i32, block_z: i32,
        candidates: *const JpeAquiferCellLocation, candidate_count: u32,
        output: *mut JpeAquiferNearestFour,
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
        random_mode: u32, seed_lo: u64, seed_hi: u64,
        block_x: *const i32, block_y: *const i32, block_z: *const i32,
        sample_count: u32,
        center_x: *mut i32, center_y: *mut i32, center_z: *mut i32,
        squared_distances: *mut i32,
    ) -> i32;
    fn jpe_cpp_aquifer_status_cache_create(maximum_entries: u32) -> u64;
    fn jpe_cpp_aquifer_status_cache_destroy(handle: u64) -> i32;
    fn jpe_cpp_aquifer_status_cache_lookup_batch(
        handle: u64, keys: *const u64, count: u32,
        statuses: *mut JpeAquiferFluidStatus, found: *mut u8,
    ) -> i32;
    fn jpe_cpp_aquifer_status_cache_insert_batch(
        handle: u64, keys: *const u64,
        statuses: *const JpeAquiferFluidStatus, count: u32,
    ) -> i32;
    fn jpe_cpp_aquifer_status_cache_insert_batch_evict(
        handle: u64, keys: *const u64,
        statuses: *const JpeAquiferFluidStatus, count: u32,
    ) -> i32;
}

/// Creates a fixed-capacity, per-Aquifer native FluidStatus cache.
#[no_mangle]
pub extern "C" fn jpe_runtime_aquifer_status_cache_create(maximum_entries: u32) -> u64 {
    if maximum_entries == 0 || maximum_entries as usize > MAX_VOLUME_ELEMENTS {
        return 0;
    }
    unsafe { jpe_cpp_aquifer_status_cache_create(maximum_entries) }
}

/// Destroys a native FluidStatus cache handle.
#[no_mangle]
pub extern "C" fn jpe_runtime_aquifer_status_cache_destroy(handle: u64) -> i32 {
    if handle == 0 { return STATUS_INVALID_ARGUMENT; }
    unsafe { jpe_cpp_aquifer_status_cache_destroy(handle) }
}

/// Looks up a batch of packed Aquifer cell-center keys.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_status_cache_lookup_batch(
    handle: u64, keys: *const u64, count: u32,
    statuses: *mut JpeAquiferFluidStatus, found: *mut u8,
) -> i32 {
    if handle == 0 || keys.is_null() || statuses.is_null() || found.is_null()
        || count == 0 || count as usize > MAX_VOLUME_ELEMENTS {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe { jpe_cpp_aquifer_status_cache_lookup_batch(handle, keys, count, statuses, found) }
}

/// Inserts missing statuses. Existing keys are left unchanged (first-writer-wins).
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_status_cache_insert_batch(
    handle: u64, keys: *const u64,
    statuses: *const JpeAquiferFluidStatus, count: u32,
) -> i32 {
    if handle == 0 || keys.is_null() || statuses.is_null()
        || count == 0 || count as usize > MAX_VOLUME_ELEMENTS {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe { jpe_cpp_aquifer_status_cache_insert_batch(handle, keys, statuses, count) }
}


/// Inserts world-context statuses with bounded round-robin replacement. This is intentionally
/// separate from the transactional cache API, whose capacity-rejection semantics remain stable.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_status_cache_insert_batch_evict(
    handle: u64, keys: *const u64,
    statuses: *const JpeAquiferFluidStatus, count: u32,
) -> i32 {
    if handle == 0 || keys.is_null() || statuses.is_null()
        || count == 0 || count as usize > MAX_VOLUME_ELEMENTS {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe { jpe_cpp_aquifer_status_cache_insert_batch_evict(handle, keys, statuses, count) }
}

/// Compiles and retains a density graph through the stable Rust cdylib ABI.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_compile(
    nodes: *const JpeDensityNode,
    node_count: u32,
    root_node: u32,
    configuration_fingerprint: u64,
) -> u64 {
    if nodes.is_null() || node_count == 0 || node_count > 65_536 || root_node >= node_count {
        return 0;
    }
    unsafe {
        jpe_cpp_density_graph_compile(nodes, node_count, root_node, configuration_fingerprint)
    }
}

/// Evaluates a regular density volume using f64 intermediates.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_evaluate(
    graph_handle: u64,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    output: *mut f64, output_length: u64,
) -> i32 {
    if graph_handle == 0 || output.is_null()
        || size_x == 0 || size_y == 0 || size_z == 0
        || step_x == 0 || step_y == 0 || step_z == 0 {
        return STATUS_INVALID_ARGUMENT;
    }
    let Some(length) = (size_x as u64)
        .checked_mul(size_y as u64)
        .and_then(|xy| xy.checked_mul(size_z as u64)) else {
            return STATUS_INVALID_ARGUMENT;
        };
    if length > MAX_VOLUME_ELEMENTS as u64 || output_length != length {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_density_graph_evaluate(
            graph_handle, size_x, size_y, size_z,
            min_x, min_y, min_z, step_x, step_y, step_z,
            output, output_length,
        )
    }
}

/// Evaluates a regular density volume using IEEE-754 f32 intermediates.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_evaluate_f32(
    graph_handle: u64,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    output: *mut f32, output_length: u64,
) -> i32 {
    if graph_handle == 0 || output.is_null()
        || size_x == 0 || size_y == 0 || size_z == 0
        || step_x == 0 || step_y == 0 || step_z == 0 {
        return STATUS_INVALID_ARGUMENT;
    }
    let Some(length) = (size_x as u64)
        .checked_mul(size_y as u64)
        .and_then(|xy| xy.checked_mul(size_z as u64)) else {
            return STATUS_INVALID_ARGUMENT;
        };
    if length > MAX_VOLUME_ELEMENTS as u64 || output_length != length {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_density_graph_evaluate_f32(
            graph_handle, size_x, size_y, size_z,
            min_x, min_y, min_z, step_x, step_y, step_z,
            output, output_length,
        )
    }
}

/// Releases one retained compiled density graph reference.
#[no_mangle]
pub extern "C" fn jpe_runtime_density_graph_release(graph_handle: u64) -> i32 {
    if graph_handle == 0 {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe { jpe_cpp_density_graph_release(graph_handle) }
}

/// Evaluates a batch of non-grid-aligned density graph points through the Rust cdylib ABI.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_evaluate_points(
    graph_handle: u64,
    point_x: *const i32, point_y: *const i32, point_z: *const i32,
    point_count: u32, output: *mut f64, output_length: u64,
) -> i32 {
    if graph_handle == 0 || point_x.is_null() || point_y.is_null() || point_z.is_null()
        || output.is_null() || point_count == 0
        || point_count as usize > MAX_VOLUME_ELEMENTS
        || output_length != point_count as u64 {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_density_graph_evaluate_points(
            graph_handle, point_x, point_y, point_z, point_count, output, output_length,
        )
    }
}

/// f32 variant of the arbitrary-point density graph C ABI.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_evaluate_points_f32(
    graph_handle: u64,
    point_x: *const i32, point_y: *const i32, point_z: *const i32,
    point_count: u32, output: *mut f32, output_length: u64,
) -> i32 {
    if graph_handle == 0 || point_x.is_null() || point_y.is_null() || point_z.is_null()
        || output.is_null() || point_count == 0
        || point_count as usize > MAX_VOLUME_ELEMENTS
        || output_length != point_count as u64 {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_density_graph_evaluate_points_f32(
            graph_handle, point_x, point_y, point_z, point_count, output, output_length,
        )
    }
}

/// Evaluates multiple immutable density graphs over independent arbitrary-point batches.
/// Coordinates and outputs use sampler-major [graph][point] order.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_evaluate_multi_points(
    graph_handles: *const u64,
    graph_count: u32,
    point_x: *const i32, point_y: *const i32, point_z: *const i32,
    points_per_graph: u32,
    output: *mut f64,
    output_length: u64,
) -> i32 {
    if graph_handles.is_null() || point_x.is_null() || point_y.is_null()
        || point_z.is_null() || output.is_null()
        || graph_count == 0 || graph_count > 64 || points_per_graph == 0 {
        return STATUS_INVALID_ARGUMENT;
    }
    let Some(total) = (graph_count as u64).checked_mul(points_per_graph as u64) else {
        return STATUS_INVALID_ARGUMENT;
    };
    if total > MAX_VOLUME_ELEMENTS as u64 || output_length != total {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_density_graph_evaluate_multi_points(
            graph_handles, graph_count, point_x, point_y, point_z,
            points_per_graph, output, output_length,
        )
    }
}

/// f32 version used by the Minecraft 26.3 density pipeline.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_density_graph_evaluate_multi_points_f32(
    graph_handles: *const u64,
    graph_count: u32,
    point_x: *const i32, point_y: *const i32, point_z: *const i32,
    points_per_graph: u32,
    output: *mut f32,
    output_length: u64,
) -> i32 {
    if graph_handles.is_null() || point_x.is_null() || point_y.is_null()
        || point_z.is_null() || output.is_null()
        || graph_count == 0 || graph_count > 64 || points_per_graph == 0 {
        return STATUS_INVALID_ARGUMENT;
    }
    let Some(total) = (graph_count as u64).checked_mul(points_per_graph as u64) else {
        return STATUS_INVALID_ARGUMENT;
    };
    if total > MAX_VOLUME_ELEMENTS as u64 || output_length != total {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_density_graph_evaluate_multi_points_f32(
            graph_handles, graph_count, point_x, point_y, point_z,
            points_per_graph, output, output_length,
        )
    }
}

/// Creates a world-scoped Aquifer context and retains its six immutable sampler plans.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_context_create(
    info: *const JpeAquiferContextCreateInfo,
) -> u64 {
    if info.is_null() {
        return 0;
    }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_aquifer_context_create(info)
    })) {
        Ok(handle) => handle,
        Err(_) => 0,
    }
}

/// Computes and caches Aquifer FluidStatus values under this world's native context.
/// Duplicate keys obey first-writer-wins semantics. Outputs are untouched on failure.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_context_compute_fluid_status_batch(
    context_handle: u64,
    expected_world_fingerprint: *const u8,
    expected_semantic_fingerprint: u64,
    cache_keys: *const u64,
    inputs: *const JpeAquiferFluidStatusInput,
    count: u32,
    outputs: *mut JpeAquiferFluidStatus,
) -> i32 {
    const MAX_AQUIFER_STATUS_BATCH: u32 = 65_536;
    if context_handle == 0 || expected_world_fingerprint.is_null()
        || expected_semantic_fingerprint == 0 || cache_keys.is_null()
        || inputs.is_null() || outputs.is_null() || count == 0
        || count > MAX_AQUIFER_STATUS_BATCH {
        return STATUS_INVALID_ARGUMENT;
    }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_aquifer_context_compute_fluid_status_batch(
            context_handle, expected_world_fingerprint, expected_semantic_fingerprint,
            cache_keys, inputs, count, outputs,
        )
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

/// Installs the immutable global FluidPicker rule for this world's context.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_context_configure_global_fluid_picker(
    context_handle: u64,
    expected_world_fingerprint: *const u8,
    expected_semantic_fingerprint: u64,
    sea_level: i32,
    min_y: i32,
    default_fluid_type: u32,
    default_fluid_is_air: u32,
    debug_disable_fluid_generation: u32,
) -> i32 {
    if context_handle == 0 || expected_world_fingerprint.is_null()
        || expected_semantic_fingerprint == 0 || default_fluid_is_air > 1
        || debug_disable_fluid_generation > 1 {
        return STATUS_INVALID_ARGUMENT;
    }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_aquifer_context_configure_global_fluid_picker(
            context_handle, expected_world_fingerprint, expected_semantic_fingerprint,
            sea_level, min_y, default_fluid_type, default_fluid_is_air,
            debug_disable_fluid_generation,
        )
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

/// Computes and caches FluidStatus values with native sampler graphs and the vanilla picker.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_context_compute_fluid_status_points_batch(
    context_handle: u64,
    expected_world_fingerprint: *const u8,
    expected_semantic_fingerprint: u64,
    cache_keys: *const u64,
    point_x: *const i32,
    point_y: *const i32,
    point_z: *const i32,
    count: u32,
    outputs: *mut JpeAquiferFluidStatus,
) -> i32 {
    const MAX_AQUIFER_STATUS_BATCH: u32 = 65_536;
    if context_handle == 0 || expected_world_fingerprint.is_null()
        || expected_semantic_fingerprint == 0 || cache_keys.is_null()
        || point_x.is_null() || point_y.is_null() || point_z.is_null()
        || outputs.is_null() || count == 0 || count > MAX_AQUIFER_STATUS_BATCH {
        return STATUS_INVALID_ARGUMENT;
    }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_aquifer_context_compute_fluid_status_points_batch(
            context_handle, expected_world_fingerprint, expected_semantic_fingerprint,
            cache_keys, point_x, point_y, point_z, count, outputs,
        )
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

/// Runs the full native computeSubstance pass over a DensityVolume.
/// Linear ordering matches DensityVolume.indexUnchecked: Y, then X, then Z.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_context_compute_substance_volume_f32(
    context_handle: u64,
    expected_world_fingerprint: *const u8,
    expected_semantic_fingerprint: u64,
    skip_sampling_above_y: i32,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    density_values: *const f32,
    density_count: u32,
    outputs: *mut JpeAquiferCoreOutput,
) -> i32 {
    const MAX_VOLUME_VOXELS: u64 = 1_048_576;
    let Some(total) = (size_x as u64)
        .checked_mul(size_y as u64)
        .and_then(|n| n.checked_mul(size_z as u64)) else {
        return STATUS_INVALID_ARGUMENT;
    };
    if context_handle == 0 || expected_world_fingerprint.is_null()
        || expected_semantic_fingerprint == 0 || size_x == 0 || size_y == 0 || size_z == 0
        || step_x <= 0 || step_y <= 0 || step_z <= 0
        || density_values.is_null() || outputs.is_null()
        || total == 0 || total > MAX_VOLUME_VOXELS || density_count as u64 != total {
        return STATUS_INVALID_ARGUMENT;
    }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_aquifer_context_compute_substance_volume_f32(
            context_handle, expected_world_fingerprint, expected_semantic_fingerprint,
            skip_sampling_above_y, size_x, size_y, size_z,
            min_x, min_y, min_z, step_x, step_y, step_z,
            density_values, density_count, outputs,
        )
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}


/// Evaluates the final density graph and Aquifer decisions in one native call.
/// The density volume never crosses through Java-owned memory.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_context_compute_density_and_substance_volume_f32(
    context_handle: u64,
    expected_world_fingerprint: *const u8,
    expected_semantic_fingerprint: u64,
    final_density_graph_handle: u64,
    skip_sampling_above_y: i32,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    outputs: *mut JpeAquiferCoreOutput,
) -> i32 {
    const MAX_VOLUME_VOXELS: u64 = 1_048_576;
    let Some(total) = (size_x as u64)
        .checked_mul(size_y as u64)
        .and_then(|n| n.checked_mul(size_z as u64)) else {
        return STATUS_INVALID_ARGUMENT;
    };
    if context_handle == 0 || expected_world_fingerprint.is_null()
        || expected_semantic_fingerprint == 0 || final_density_graph_handle == 0
        || outputs.is_null() || size_x == 0 || size_y == 0 || size_z == 0
        || step_x <= 0 || step_y <= 0 || step_z <= 0
        || total == 0 || total > MAX_VOLUME_VOXELS {
        return STATUS_INVALID_ARGUMENT;
    }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_aquifer_context_compute_density_and_substance_volume_f32(
            context_handle, expected_world_fingerprint, expected_semantic_fingerprint,
            final_density_graph_handle, skip_sampling_above_y,
            size_x, size_y, size_z,
            min_x, min_y, min_z, step_x, step_y, step_z, outputs,
        )
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

/// Destroys a world-scoped Aquifer context after all borrowed volume calls have finished.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_context_destroy(context_handle: u64) -> i32 {
    if context_handle == 0 {
        return STATUS_INVALID_ARGUMENT;
    }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_aquifer_context_destroy(context_handle)
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

/// Evaluates six sampler graphs using sampler-major independent coordinate arrays.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_context_evaluate_samplers_f32(
    context_handle: u64,
    expected_world_fingerprint: *const u8,
    expected_semantic_fingerprint: u64,
    point_x: *const i32, point_y: *const i32, point_z: *const i32,
    points_per_sampler: u32, output: *mut f32, output_length: u64,
) -> i32 {
    if context_handle == 0 || expected_world_fingerprint.is_null()
        || expected_semantic_fingerprint == 0 || point_x.is_null() || point_y.is_null()
        || point_z.is_null() || output.is_null() || points_per_sampler == 0 {
        return STATUS_INVALID_ARGUMENT;
    }
    let Some(total) = (points_per_sampler as u64).checked_mul(6) else {
        return STATUS_INVALID_ARGUMENT;
    };
    if total > MAX_VOLUME_ELEMENTS as u64 || output_length != total {
        return STATUS_INVALID_ARGUMENT;
    }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_aquifer_context_evaluate_samplers_f32(
            context_handle, expected_world_fingerprint, expected_semantic_fingerprint,
            point_x, point_y, point_z, points_per_sampler, output, output_length,
        )
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

/// Computes Minecraft 26.3 Aquifer FluidStatus values from native sampler results
/// and pre-resolved opaque FluidPicker tokens. The whole batch is validated before writes.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_compute_fluid_status_batch(
    inputs: *const JpeAquiferFluidStatusInput,
    count: u32,
    outputs: *mut JpeAquiferFluidStatus,
) -> i32 {
    if inputs.is_null() || outputs.is_null() || count == 0
        || count as usize > MAX_VOLUME_ELEMENTS {
        return STATUS_INVALID_ARGUMENT;
    }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_aquifer_compute_fluid_status_batch(inputs, count, outputs)
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

/// Runs the scalar Aquifer decision kernel. This wrapper is exported from the Rust cdylib.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_compute_substance_core(
    input: *const JpeAquiferCoreInput,
    output: *mut JpeAquiferCoreOutput,
) -> i32 {
    if input.is_null() || output.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe { jpe_cpp_aquifer_compute_substance_core(input, output) }
}

/// Runs a complete Aquifer decision batch through one C ABI call.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_compute_substance_batch(
    inputs: *const JpeAquiferCoreInput,
    count: u32,
    outputs: *mut JpeAquiferCoreOutput,
) -> i32 {
    if inputs.is_null() || outputs.is_null() || count == 0 || count as usize > MAX_VOLUME_ELEMENTS {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe { jpe_cpp_aquifer_compute_substance_batch(inputs, count, outputs) }
}

/// Returns the exact Minecraft 26.3 Aquifer positional offsets for XOROSHIRO mode.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_xoroshiro_offsets(
    seed_lo: u64, seed_hi: u64,
    grid_x: i32, grid_y: i32, grid_z: i32,
    offset_x: *mut i32, offset_y: *mut i32, offset_z: *mut i32,
) -> i32 {
    if offset_x.is_null() || offset_y.is_null() || offset_z.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_aquifer_xoroshiro_offsets(
            seed_lo, seed_hi, grid_x, grid_y, grid_z,
            offset_x, offset_y, offset_z,
        )
    }
}

/// Returns the exact Minecraft 26.3 Aquifer positional offsets for Legacy mode.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_legacy_offsets(
    factory_seed: u64,
    grid_x: i32, grid_y: i32, grid_z: i32,
    offset_x: *mut i32, offset_y: *mut i32, offset_z: *mut i32,
) -> i32 {
    if offset_x.is_null() || offset_y.is_null() || offset_z.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_aquifer_legacy_offsets(
            factory_seed, grid_x, grid_y, grid_z,
            offset_x, offset_y, offset_z,
        )
    }
}

/// Precomputes a packed Aquifer cell-location cache in vanilla x-fast, then z, then y order.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_grid_centers(
    random_mode: u32, seed_lo: u64, seed_hi: u64,
    min_grid_x: i32, min_grid_y: i32, min_grid_z: i32,
    grid_size_x: u32, grid_size_z: u32, cell_count: u32,
    packed_locations: *mut u64,
) -> i32 {
    if random_mode > 1 || grid_size_x == 0 || grid_size_z == 0
        || cell_count == 0 || cell_count as usize > MAX_VOLUME_ELEMENTS
        || packed_locations.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }
    let Some(layer_size) = (grid_size_x as u64).checked_mul(grid_size_z as u64) else {
        return STATUS_INVALID_ARGUMENT;
    };
    if layer_size == 0 || layer_size > cell_count as u64
        || (cell_count as u64) % layer_size != 0 {
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

/// Computes each voxel's four nearest Aquifer centers and Java-int squared distances.
/// Output arrays each contain sample_count * 4 entries in sample-major order.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_aquifer_nearest_four(
    random_mode: u32, seed_lo: u64, seed_hi: u64,
    block_x: *const i32, block_y: *const i32, block_z: *const i32,
    sample_count: u32,
    center_x: *mut i32, center_y: *mut i32, center_z: *mut i32,
    squared_distances: *mut i32,
) -> i32 {
    if random_mode > 1 || sample_count == 0 || sample_count as usize > MAX_VOLUME_ELEMENTS
        || block_x.is_null() || block_y.is_null() || block_z.is_null()
        || center_x.is_null() || center_y.is_null() || center_z.is_null()
        || squared_distances.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }
    unsafe {
        jpe_cpp_aquifer_nearest_four(
            random_mode, seed_lo, seed_hi,
            block_x, block_y, block_z, sample_count,
            center_x, center_y, center_z, squared_distances,
        )
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct JpePerlinLayer {
    pub permutation: *const u8,
    pub offset_x: f64,
    pub offset_y: f64,
    pub offset_z: f64,
    pub xz_scale: f64,
    pub y_scale: f64,
    pub amplitude: f32,
}

unsafe extern "C" {
    fn jpe_cpp_perlin_stack_prepare(layers: *const JpePerlinLayer, layer_count: u32) -> u64;
    fn jpe_cpp_perlin_stack_evaluate(
        handle: u64, xz_scale: f64, y_scale: f64, amplitude: f32,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        output: *mut f32, output_length: u64, flags: u32,
    ) -> i32;
    fn jpe_cpp_perlin_stack_release(handle: u64) -> i32;
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
    fn jpe_cpp_perlin_sample_point(
        permutation: *const u8,
        x: i32, y: i32, z: i32,
        offset_x: f64, offset_y: f64, offset_z: f64,
        xz_scale: f64, y_scale: f64, amplitude: f32,
        output: *mut f32,
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


/// Copies immutable layer permutations and base octave parameters into a native
/// prepared plan. The caller may release its source descriptors immediately.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_prepare_perlin_stack(
    layers: *const JpePerlinLayer, layer_count: u32,
) -> u64 {
    catch_unwind(AssertUnwindSafe(|| {
        if layers.is_null() || layer_count == 0 || layer_count > 64 { return 0; }
        let descriptors = unsafe { slice::from_raw_parts(layers, layer_count as usize) };
        if descriptors.iter().any(|layer| layer.permutation.is_null()) { return 0; }
        unsafe { jpe_cpp_perlin_stack_prepare(layers, layer_count) }
    })).unwrap_or(0)
}

/// Evaluates a prepared stack. No layer descriptors or permutation arrays cross
/// the language boundary on this per-volume path.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_evaluate_prepared_perlin_stack(
    handle: u64, xz_scale: f64, y_scale: f64, amplitude: f32,
    size_x: u32, size_y: u32, size_z: u32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    output: *mut f32, output_length: u64, flags: u32,
) -> i32 {
    if handle == 0 || output.is_null() || size_x == 0 || size_y == 0 || size_z == 0
        || step_x == 0 || step_y == 0 || step_z == 0 { return STATUS_INVALID_ARGUMENT; }
    let expected = match (size_x as u64).checked_mul(size_y as u64)
        .and_then(|n| n.checked_mul(size_z as u64)) {
        Some(value) => value, None => return STATUS_INVALID_ARGUMENT,
    };
    if expected != output_length || expected > MAX_VOLUME_ELEMENTS as u64 {
        return STATUS_INVALID_ARGUMENT;
    }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_perlin_stack_evaluate(handle, xz_scale, y_scale, amplitude,
            size_x, size_y, size_z, min_x, min_y, min_z,
            step_x, step_y, step_z, output, output_length, flags)
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(code) => code,
        Err(_) => STATUS_RUST_PANIC,
    }
}

#[no_mangle]
pub extern "C" fn jpe_runtime_release_prepared_perlin_stack(handle: u64) -> i32 {
    match catch_unwind(AssertUnwindSafe(|| unsafe { jpe_cpp_perlin_stack_release(handle) })) {
        Ok(code) => code,
        Err(_) => STATUS_RUST_PANIC,
    }
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
    use super::*;

    #[test]
    fn aquifer_status_cache_batches_preserve_first_inserted_status() {
        let handle = jpe_runtime_aquifer_status_cache_create(8);
        assert_ne!(handle, 0);

        let keys = [0_u64, 42, u64::MAX, 42];
        let input = [
            JpeAquiferFluidStatus { fluid_level: -64, fluid_type: 3 },
            JpeAquiferFluidStatus { fluid_level: 80, fluid_type: 7 },
            JpeAquiferFluidStatus { fluid_level: i32::MIN, fluid_type: u32::MAX },
            // A duplicate must not overwrite the first cached FluidStatus.
            JpeAquiferFluidStatus { fluid_level: 999, fluid_type: 99 },
        ];

        let mut before = [JpeAquiferFluidStatus { fluid_level: 123, fluid_type: 456 }; 4];
        let mut found = [9_u8; 4];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_status_cache_lookup_batch(
                handle, keys.as_ptr(), keys.len() as u32,
                before.as_mut_ptr(), found.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(found, [0, 0, 0, 0]);
        assert!(before.iter().all(|v| v.fluid_level == 123 && v.fluid_type == 456));

        assert_eq!(unsafe {
            jpe_runtime_aquifer_status_cache_insert_batch(
                handle, keys.as_ptr(), input.as_ptr(), keys.len() as u32,
            )
        }, STATUS_OK);

        let query_keys = [u64::MAX, 42, 0, 777];
        let mut output = [JpeAquiferFluidStatus::default(); 4];
        let mut found = [0_u8; 4];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_status_cache_lookup_batch(
                handle, query_keys.as_ptr(), query_keys.len() as u32,
                output.as_mut_ptr(), found.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(found, [1, 1, 1, 0]);
        assert_eq!(output[0].fluid_level, i32::MIN);
        assert_eq!(output[0].fluid_type, u32::MAX);
        assert_eq!(output[1].fluid_level, 80);
        assert_eq!(output[1].fluid_type, 7);
        assert_eq!(output[2].fluid_level, -64);
        assert_eq!(output[2].fluid_type, 3);

        assert_eq!(jpe_runtime_aquifer_status_cache_destroy(handle), STATUS_OK);
        assert_eq!(jpe_runtime_aquifer_status_cache_destroy(handle), STATUS_INVALID_ARGUMENT);
        assert_eq!(jpe_runtime_aquifer_status_cache_create(0), 0);
    }


    #[test]
    fn aquifer_world_status_cache_evicts_old_entries_without_losing_batch_values() {
        let handle = jpe_runtime_aquifer_status_cache_create(2);
        assert_ne!(handle, 0);
        let keys = [10_u64, 11, 12];
        let statuses = [
            JpeAquiferFluidStatus { fluid_level: 10, fluid_type: 1 },
            JpeAquiferFluidStatus { fluid_level: 11, fluid_type: 2 },
            JpeAquiferFluidStatus { fluid_level: 12, fluid_type: 3 },
        ];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_status_cache_insert_batch_evict(
                handle, keys.as_ptr(), statuses.as_ptr(), 3,
            )
        }, STATUS_OK);

        let query_keys = [10_u64, 11, 12];
        let mut output = [JpeAquiferFluidStatus::default(); 3];
        let mut found = [0_u8; 3];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_status_cache_lookup_batch(
                handle, query_keys.as_ptr(), 3, output.as_mut_ptr(), found.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(found.iter().filter(|&&value| value == 1).count(), 2);
        assert_eq!(output[2].fluid_level, 12);
        assert_eq!(output[2].fluid_type, 3);

        // Existing keys remain first-writer-wins while a fresh key enters the bounded cache.
        let replacement_key = [12_u64];
        let replacement_status = [JpeAquiferFluidStatus { fluid_level: 999, fluid_type: 9 }];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_status_cache_insert_batch_evict(
                handle, replacement_key.as_ptr(), replacement_status.as_ptr(), 1,
            )
        }, STATUS_OK);
        let mut retained = [JpeAquiferFluidStatus::default(); 1];
        let mut retained_found = [0_u8; 1];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_status_cache_lookup_batch(
                handle, replacement_key.as_ptr(), 1,
                retained.as_mut_ptr(), retained_found.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(retained_found, [1]);
        assert_eq!(retained[0].fluid_level, 12);
        assert_eq!(retained[0].fluid_type, 3);
        assert_eq!(jpe_runtime_aquifer_status_cache_destroy(handle), STATUS_OK);
    }

    #[test]
    fn aquifer_status_cache_rejects_capacity_overflow_transactionally() {
        let handle = jpe_runtime_aquifer_status_cache_create(3);
        assert_ne!(handle, 0);
        let keys = [10_u64, 11];
        let statuses = [
            JpeAquiferFluidStatus { fluid_level: 32, fluid_type: 1 },
            JpeAquiferFluidStatus { fluid_level: 64, fluid_type: 2 },
        ];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_status_cache_insert_batch(
                handle, keys.as_ptr(), statuses.as_ptr(), 2,
            )
        }, STATUS_OK);

        // Only one free slot remains. The two-key batch must not partially commit key 12.
        let overflow_keys = [12_u64, 13];
        let overflow_statuses = [
            JpeAquiferFluidStatus { fluid_level: 96, fluid_type: 3 },
            JpeAquiferFluidStatus { fluid_level: 128, fluid_type: 4 },
        ];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_status_cache_insert_batch(
                handle, overflow_keys.as_ptr(), overflow_statuses.as_ptr(), 2,
            )
        }, STATUS_CPP_FAILURE);

        let query_keys = [10_u64, 11, 12, 13];
        let mut output = [JpeAquiferFluidStatus::default(); 4];
        let mut found = [0_u8; 4];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_status_cache_lookup_batch(
                handle, query_keys.as_ptr(), 4, output.as_mut_ptr(), found.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(found, [1, 1, 0, 0]);
        assert_eq!(jpe_runtime_aquifer_status_cache_destroy(handle), STATUS_OK);
    }

    #[test]
    fn aquifer_xoroshiro_offsets_match_audited_reference_vectors() {
        let cases = [
            (0x0123_4567_89ab_cdef_u64, 0xfedc_ba98_7654_3210_u64, 1, 2, 3, [4, 0, 1]),
            (0_u64, 0_u64, 0, 0, 0, [0, 2, 7]),
            (0x9e37_79b9_7f4a_7c15_u64, 0x6a09_e667_f3bc_c909_u64, -17, -64, 33, [1, 1, 8]),
            (123_456_789_u64, 987_654_321_u64, -2, -1, -3, [5, 7, 2]),
        ];
        for (seed_lo, seed_hi, x, y, z, expected) in cases {
            let mut actual = [i32::MIN; 3];
            assert_eq!(unsafe {
                jpe_runtime_aquifer_xoroshiro_offsets(
                    seed_lo, seed_hi, x, y, z,
                    &mut actual[0], &mut actual[1], &mut actual[2],
                )
            }, STATUS_OK);
            assert_eq!(actual, expected);
        }
        assert_eq!(unsafe {
            jpe_runtime_aquifer_xoroshiro_offsets(
                0, 0, 0, 0, 0,
                std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(),
            )
        }, STATUS_INVALID_ARGUMENT);
    }

    #[test]
    fn aquifer_legacy_offsets_match_audited_reference_vectors() {
        let cases = [
            (0x0123_4567_89ab_cdef_u64, 1, 2, 3, [3, 3, 1]),
            (0_u64, 0, 0, 0, [0, 7, 9]),
            (987_654_321_u64, -17, -64, 33, [0, 0, 3]),
            (0x9e37_79b9_7f4a_7c15_u64, -2, -1, -3, [6, 1, 7]),
        ];
        for (seed, x, y, z, expected) in cases {
            let mut actual = [i32::MIN; 3];
            assert_eq!(unsafe {
                jpe_runtime_aquifer_legacy_offsets(
                    seed, x, y, z,
                    &mut actual[0], &mut actual[1], &mut actual[2],
                )
            }, STATUS_OK);
            assert_eq!(actual, expected);
        }
        assert_eq!(unsafe {
            jpe_runtime_aquifer_legacy_offsets(
                0, 0, 0, 0,
                std::ptr::null_mut(), std::ptr::null_mut(), std::ptr::null_mut(),
            )
        }, STATUS_INVALID_ARGUMENT);
    }

    #[test]
    fn aquifer_nearest_four_matches_independent_vanilla_candidate_order() {
        let cases = [
            (0_u32, 0x0123_4567_89ab_cdef_u64, 0xfedc_ba98_7654_3210_u64,
                [0, 15, -1, -17], [-64, -1, 0, 23], [0, 15, -1, -33]),
            (1_u32, 0x9e37_79b9_7f4a_7c15_u64, 0_u64,
                [1, 16, -16, i32::MIN], [2, -13, 25, i32::MAX], [3, -17, 31, i32::MIN]),
        ];
        for (mode, seed_lo, seed_hi, xs, ys, zs) in cases {
            let mut cx = [0_i32; 16];
            let mut cy = [0_i32; 16];
            let mut cz = [0_i32; 16];
            let mut distances = [0_i32; 16];
            assert_eq!(unsafe {
                jpe_runtime_aquifer_nearest_four(
                    mode, seed_lo, seed_hi,
                    xs.as_ptr(), ys.as_ptr(), zs.as_ptr(), 4,
                    cx.as_mut_ptr(), cy.as_mut_ptr(), cz.as_mut_ptr(), distances.as_mut_ptr(),
                )
            }, STATUS_OK);

            for sample in 0..4 {
                let anchor_x = xs[sample].wrapping_sub(5) >> 4;
                let anchor_y = ys[sample].wrapping_add(1).div_euclid(12);
                let anchor_z = zs[sample].wrapping_sub(5) >> 4;
                let mut expected: Vec<(i32, i32, i32, i32)> = Vec::new();
                for dx in 0..=1 {
                    for dy in -1..=1 {
                        for dz in 0..=1 {
                            let gx = anchor_x.wrapping_add(dx);
                            let gy = anchor_y.wrapping_add(dy);
                            let gz = anchor_z.wrapping_add(dz);
                            let mut offsets = [0_i32; 3];
                            let status = unsafe {
                                if mode == 0 {
                                    jpe_runtime_aquifer_xoroshiro_offsets(
                                        seed_lo, seed_hi, gx, gy, gz,
                                        &mut offsets[0], &mut offsets[1], &mut offsets[2],
                                    )
                                } else {
                                    jpe_runtime_aquifer_legacy_offsets(
                                        seed_lo, gx, gy, gz,
                                        &mut offsets[0], &mut offsets[1], &mut offsets[2],
                                    )
                                }
                            };
                            assert_eq!(status, STATUS_OK);
                            let x = gx.wrapping_mul(16).wrapping_add(offsets[0]);
                            let y = gy.wrapping_mul(12).wrapping_add(offsets[1]);
                            let z = gz.wrapping_mul(16).wrapping_add(offsets[2]);
                            let ddx = x.wrapping_sub(xs[sample]);
                            let ddy = y.wrapping_sub(ys[sample]);
                            let ddz = z.wrapping_sub(zs[sample]);
                            let d = ddx.wrapping_mul(ddx)
                                .wrapping_add(ddy.wrapping_mul(ddy))
                                .wrapping_add(ddz.wrapping_mul(ddz));
                            let at = expected.iter().position(|item| d <= item.3)
                                .unwrap_or(expected.len());
                            expected.insert(at, (x, y, z, d));
                            if expected.len() > 4 { expected.pop(); }
                        }
                    }
                }
                for neighbor in 0..4 {
                    let index = sample * 4 + neighbor;
                    assert_eq!(
                        (cx[index], cy[index], cz[index], distances[index]),
                        expected[neighbor],
                        "mode={mode} sample={sample} neighbor={neighbor}",
                    );
                }
            }
        }

        assert_eq!(unsafe {
            jpe_runtime_aquifer_nearest_four(
                2, 0, 0, std::ptr::null(), std::ptr::null(), std::ptr::null(), 1,
                std::ptr::null_mut(), std::ptr::null_mut(),
                std::ptr::null_mut(), std::ptr::null_mut(),
            )
        }, STATUS_INVALID_ARGUMENT);
    }

    #[test]
    fn aquifer_grid_center_cache_matches_random_offsets_and_vanilla_packing() {
        const SIZE_X: u32 = 5;
        const SIZE_Z: u32 = 5;
        const SIZE_Y: u32 = 33;
        const COUNT: usize = (SIZE_X * SIZE_Z * SIZE_Y) as usize;
        for mode in 0..=1_u32 {
            let seed_lo = 0x0123_4567_89ab_cdef_u64;
            let seed_hi = 0xfedc_ba98_7654_3210_u64;
            let min_x = -2;
            let min_y = -17;
            let min_z = 3;
            let mut actual = vec![0_u64; COUNT];
            assert_eq!(unsafe {
                jpe_runtime_aquifer_grid_centers(
                    mode, seed_lo, seed_hi, min_x, min_y, min_z,
                    SIZE_X, SIZE_Z, COUNT as u32, actual.as_mut_ptr(),
                )
            }, STATUS_OK);

            for index in 0..COUNT {
                let x_index = (index as u32) % SIZE_X;
                let z_index = ((index as u32) / SIZE_X) % SIZE_Z;
                let y_index = (index as u32) / (SIZE_X * SIZE_Z);
                let gx = min_x.wrapping_add(x_index as i32);
                let gy = min_y.wrapping_add(y_index as i32);
                let gz = min_z.wrapping_add(z_index as i32);
                let mut offsets = [0_i32; 3];
                let status = unsafe {
                    if mode == 0 {
                        jpe_runtime_aquifer_xoroshiro_offsets(
                            seed_lo, seed_hi, gx, gy, gz,
                            &mut offsets[0], &mut offsets[1], &mut offsets[2],
                        )
                    } else {
                        jpe_runtime_aquifer_legacy_offsets(
                            seed_lo, gx, gy, gz,
                            &mut offsets[0], &mut offsets[1], &mut offsets[2],
                        )
                    }
                };
                assert_eq!(status, STATUS_OK);
                let x = gx.wrapping_mul(16).wrapping_add(offsets[0]);
                let y = gy.wrapping_mul(12).wrapping_add(offsets[1]);
                let z = gz.wrapping_mul(16).wrapping_add(offsets[2]);
                let expected = (((x as u32 as u64) & 0x3ff_ffff) << 38)
                    | (((z as u32 as u64) & 0x3ff_ffff) << 12)
                    | ((y as u32 as u64) & 0xfff);
                assert_eq!(actual[index], expected, "mode={mode} cache_index={index}");
            }
        }

        let mut untouched = [0xfeed_face_dead_beef_u64; 4];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_grid_centers(
                2, 0, 0, 0, 0, 0, 2, 2, 4, untouched.as_mut_ptr(),
            )
        }, STATUS_INVALID_ARGUMENT);
        assert!(untouched.iter().all(|value| *value == 0xfeed_face_dead_beef));
    }

    #[test]
    fn density_graph_compiles_caches_and_evaluates_a_full_volume() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_Y, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 2.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0,
                input_b: 2, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_MUL, input_a: 3,
                input_b: 1, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
        ];
        let first = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4, 0x1234)
        };
        let second = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4, 0x1234)
        };
        assert_ne!(first, 0);
        assert_eq!(first, second, "identical graph/config should reuse the cached handle");

        let mut output = [0.0_f64; 4];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                first, 2, 2, 1, 10, 3, 0, 1, 1, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, 0);
        assert_eq!(output, [36.0, 48.0, 39.0, 52.0]);

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
    fn density_graph_evaluates_arbitrary_points_in_input_order_f64_and_f32() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_Y, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_Z, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 2.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0,
                input_b: 3, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_MUL, input_a: 4,
                input_b: 1, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 5,
                input_b: 2, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 6, 0xA91F_263)
        };
        assert_ne!(graph, 0);

        let xs = [17_i32, -31, 0, 2048, i32::MIN, i32::MAX, -5];
        let ys = [-9_i32, 12, 0, -64, 17, -3, 29];
        let zs = [3_i32, -7, 41, -128, -11, 23, i32::MAX];
        let mut actual = [f64::NAN; 7];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_points(
                graph, xs.as_ptr(), ys.as_ptr(), zs.as_ptr(), 7,
                actual.as_mut_ptr(), 7,
            )
        }, STATUS_OK);
        for i in 0..7 {
            let expected = (xs[i] as f64 + 2.0) * ys[i] as f64 + zs[i] as f64;
            assert_eq!(actual[i], expected, "f64 arbitrary point index={i}");
        }

        let mut actual_f32 = [f32::NAN; 7];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_points_f32(
                graph, xs.as_ptr(), ys.as_ptr(), zs.as_ptr(), 7,
                actual_f32.as_mut_ptr(), 7,
            )
        }, STATUS_OK);
        for i in 0..7 {
            let expected = (xs[i] as f32 + 2.0_f32) * ys[i] as f32 + zs[i] as f32;
            assert_eq!(actual_f32[i].to_bits(), expected.to_bits(), "f32 arbitrary point index={i}");
        }

        // All input and output lengths are exact, and the C ABI rejects partial batches.
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_points(
                graph, xs.as_ptr(), ys.as_ptr(), zs.as_ptr(), 7,
                actual.as_mut_ptr(), 6,
            )
        }, STATUS_INVALID_ARGUMENT);
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_points(
                graph, std::ptr::null(), ys.as_ptr(), zs.as_ptr(), 7,
                actual.as_mut_ptr(), 7,
            )
        }, STATUS_INVALID_ARGUMENT);

        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_evaluates_multiple_samplers_and_point_sets_in_one_call() {
        let add_nodes = [
            JpeDensityNode { opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_Y, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
        ];
        let mul_nodes = [
            JpeDensityNode { opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_Y, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_MUL, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
        ];
        let add_graph = unsafe {
            jpe_runtime_density_graph_compile(add_nodes.as_ptr(), 3, 2, 0xA91F_001)
        };
        let mul_graph = unsafe {
            jpe_runtime_density_graph_compile(mul_nodes.as_ptr(), 3, 2, 0xA91F_002)
        };
        assert_ne!(add_graph, 0);
        assert_ne!(mul_graph, 0);

        // Two sampler-major point sets. Each sampler receives distinct coordinates.
        let handles = [add_graph, mul_graph];
        let xs = [2_i32, -4, 7, 8];
        let ys = [3_i32, 5, 6, -3];
        let zs = [0_i32, 1, 2, 0];
        let mut output64 = [f64::NAN; 4];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_multi_points(
                handles.as_ptr(), 2,
                xs.as_ptr(), ys.as_ptr(), zs.as_ptr(),
                2, output64.as_mut_ptr(), 4,
            )
        }, STATUS_OK);
        assert_eq!(output64, [5.0, 1.0, 42.0, -24.0]);

        let mut output32 = [f32::NAN; 4];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_multi_points_f32(
                handles.as_ptr(), 2,
                xs.as_ptr(), ys.as_ptr(), zs.as_ptr(),
                2, output32.as_mut_ptr(), 4,
            )
        }, STATUS_OK);
        assert_eq!(output32.map(f32::to_bits), [5.0_f32, 1.0, 42.0, -24.0].map(f32::to_bits));

        // Every handle is resolved before the evaluator writes output.
        let invalid_handles = [add_graph, 0_u64];
        let mut untouched = [123.25_f64; 4];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_multi_points(
                invalid_handles.as_ptr(), 2,
                xs.as_ptr(), ys.as_ptr(), zs.as_ptr(),
                2, untouched.as_mut_ptr(), 4,
            )
        }, STATUS_CPP_FAILURE);
        assert_eq!(untouched, [123.25_f64; 4]);

        assert_eq!(unsafe { jpe_runtime_density_graph_release(add_graph) }, STATUS_OK);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(mul_graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_rejects_forward_references_and_bad_volumes() {
        let invalid = [
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 1,
                input_b: 0, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 , auxiliary: 0.0, tertiary: 0.0},
        ];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_compile(invalid.as_ptr(), 2, 0, 7)
        }, 0);

        let valid = [JpeDensityNode { opcode: DENSITY_CONST,
            input_a: DENSITY_NO_INPUT, input_b: DENSITY_NO_INPUT,
            reserved: 0, immediate: 1.0 , auxiliary: 0.0, tertiary: 0.0}];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(valid.as_ptr(), 1, 0, 7)
        };
        assert_ne!(handle, 0);
        let mut output = [0.0_f64; 1];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                handle, 1, 1, 1, 0, 0, 0, 0, 1, 1,
                output.as_mut_ptr(), 1,
            )
        }, 1);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, 0);
    }

    #[test]
    fn exports_expected_abi_version() { assert_eq!(jpe_runtime_abi_version(), 7); }

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
                xz_scale: 0.75, y_scale: 1.25, amplitude: 0.5 },
            JpePerlinLayer { permutation: permutation_b.as_ptr(),
                offset_x: -0.31, offset_y: 0.17, offset_z: 0.23,
                xz_scale: 1.5, y_scale: 0.625, amplitude: -0.125 },
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
                xz_scale: 0.75, y_scale: 1.25, amplitude: 0.5 },
            JpePerlinLayer { permutation: permutation_b.as_ptr(),
                offset_x: -0.31, offset_y: 0.17, offset_z: 0.23,
                xz_scale: 1.5, y_scale: 0.625, amplitude: -0.125 },
            JpePerlinLayer { permutation: permutation_c.as_ptr(),
                offset_x: 0.07, offset_y: -0.33, offset_z: 0.19,
                xz_scale: 0.375, y_scale: 2.0, amplitude: 0.0625 },
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
        });
        for flags in [0_u32, 1_u32] {
            let mut expected = vec![0.375_f32; 5 * 7 * 4];
            let mut actual = expected.clone();
            for layer in &layers {
                assert_eq!(unsafe { jpe_cpp_perlin_add_volume_uncached_y(
                    layer.permutation, 5, 7, 4, -16_777_219, 91, -33,
                    3, -2, 5, layer.offset_x, layer.offset_y, layer.offset_z,
                    layer.xz_scale, layer.y_scale, layer.amplitude,
                    expected.as_mut_ptr(), expected.len() as u64, flags
                ) }, STATUS_OK);
            }
            assert_eq!(unsafe { jpe_cpp_perlin_add_stack(
                layers.as_ptr(), layers.len() as u32,
                5, 7, 4, -16_777_219, 91, -33, 3, -2, 5,
                actual.as_mut_ptr(), actual.len() as u64, flags
            ) }, STATUS_OK);
            assert_eq!(actual.iter().map(|v| v.to_bits()).collect::<Vec<_>>(),
                       expected.iter().map(|v| v.to_bits()).collect::<Vec<_>>());
        }
    }

    #[test]
    fn perlin_y_cache_native_microbenchmark() {
        use std::time::Instant;
        const SX: usize = 24;
        const SY: usize = 32;
        const SZ: usize = 24;
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
                    -128, -64, 96, 1, 1, 1,
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
                        -128, -64, 96, 1, 1, 1,
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
        const SX: usize = 32;
        const SY: usize = 64;
        const SZ: usize = 32;
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
                    -256, -128, 192, 1, 1, 1,
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
                        -256, -128, 192, 1, 1, 1,
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
        const SX: usize = 32;
        const SY: usize = 64;
        const SZ: usize = 32;
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
                        -256, -128, 192, 1, 1, 1,
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
                        -256, -128, 192, 1, 1, 1,
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

        const SX: usize = 24;
        const SY: usize = 32;
        const SZ: usize = 24;
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
                    -128, -64, 96, 1, 1, 1,
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
                    -128, -64, 96, 1, 1, 1,
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

        const SX: usize = 32;
        const SY: usize = 64;
        const SZ: usize = 32;
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
                    -128, -64, 96, 1, 1, 1,
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
                    -128, -64, 96, 1, 1, 1,
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
            xz_scale: 1.0, y_scale: 1.0, amplitude: 1.0 };
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
                xz_scale: 0.8, y_scale: 1.2, amplitude: 0.75 },
            JpePerlinLayer { permutation: permutation_b.as_ptr(),
                offset_x: -0.21, offset_y: 0.19, offset_z: 0.31,
                xz_scale: 1.1, y_scale: 0.7, amplitude: -0.25 },
        ];
        let mut expected = [0.125_f32; 24];
        let mut actual = expected;
        assert_eq!(unsafe { jpe_runtime_perlin_add_stack(
            layers.as_ptr(), 2, 2, 3, 4, -3, -5, 7, 1, 2, 1,
            expected.as_mut_ptr(), 24, 0
        ) }, STATUS_OK);
        let id = unsafe { jpe_runtime_submit_perlin_stack(
            layers.as_ptr(), 2, 2, 3, 4, -3, -5, 7, 1, 2, 1,
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
                xz_scale: 1.0, y_scale: 1.0, amplitude: 1.0 },
            JpePerlinLayer { permutation: std::ptr::null(),
                offset_x: 0.0, offset_y: 0.0, offset_z: 0.0,
                xz_scale: 1.0, y_scale: 1.0, amplitude: 1.0 },
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
            xz_scale: 1.0, y_scale: 1.0, amplitude: 1.0 };
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
        const SX: usize = 32;
        const SY: usize = 64;
        const SZ: usize = 32;
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
                        -256, -128, 192, 1, 1, 1,
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
                        -256, -128, 192, 1, 1, 1,
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
    fn density_graph_slice_fixes_axis_and_supports_nested_slices() {
        let nodes = [
            JpeDensityNode { opcode: 30, input_a: 2, input_b: DENSITY_NO_INPUT,
                reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_SLICE, input_a: 0,
                input_b: 1, reserved: 0, immediate: 5.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_SLICE, input_a: 1,
                input_b: 0, reserved: 0, immediate: 11.0, auxiliary: 0.0, tertiary: 0.0 },
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 2, 0x51_1CE)
        };
        assert_ne!(graph, 0);
        let mut actual = [0.0_f32; 24];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 2, 3, 4, -3, -5, 7, 1, 2, 1,
                actual.as_mut_ptr(), actual.len() as u64,
            )
        }, STATUS_OK);
        let mut out = 0usize;
        for z in 7..11 {
            let expected = (11 * 11 + 5 * 5 + z * z) as f32;
            for _x in 0..2 {
                for _y in 0..3 {
                    assert_eq!(actual[out], expected);
                    out += 1;
                }
            }
        }
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_slice_runs_prepared_perlin_at_a_fixed_axis_coordinate() {
        let permutation: [u8; 256] = std::array::from_fn(|i| ((i * 31 + 13) & 255) as u8);
        let layer = JpePerlinLayer {
            permutation: permutation.as_ptr(),
            offset_x: 0.17, offset_y: -0.11, offset_z: 0.29,
            xz_scale: 0.5, y_scale: 0.75, amplitude: 0.8,
        };
        let stack = unsafe { jpe_runtime_prepare_perlin_stack(&layer, 1) };
        assert_ne!(stack, 0);
        let plain_nodes = [JpeDensityNode {
            opcode: 7, input_a: DENSITY_NO_INPUT, input_b: DENSITY_NO_INPUT,
            reserved: 1.0_f32.to_bits(), immediate: stack as f64,
            auxiliary: 0.8, tertiary: 1.1,
        }];
        let sliced_nodes = [
            plain_nodes[0],
            JpeDensityNode { opcode: DENSITY_SLICE, input_a: 0,
                input_b: 1, reserved: 0, immediate: 5.0, auxiliary: 0.0, tertiary: 0.0 },
        ];
        let plain = unsafe {
            jpe_runtime_density_graph_compile(plain_nodes.as_ptr(), 1, 0, 0x51_1CE_2)
        };
        let sliced = unsafe {
            jpe_runtime_density_graph_compile(sliced_nodes.as_ptr(), 2, 1, 0x51_1CE_2)
        };
        assert_ne!(plain, 0);
        assert_ne!(sliced, 0);
        assert_eq!(jpe_runtime_release_prepared_perlin_stack(stack), STATUS_OK);

        let mut reference = [0.0_f32; 24];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                plain, 2, 3, 4, -3, 5, 7, 1, 1, 1,
                reference.as_mut_ptr(), reference.len() as u64,
            )
        }, STATUS_OK);
        let mut actual = [0.0_f32; 24];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                sliced, 2, 3, 4, -3, -5, 7, 1, 2, 1,
                actual.as_mut_ptr(), actual.len() as u64,
            )
        }, STATUS_OK);
        for z in 0..4 {
            for x in 0..2 {
                for y in 0..3 {
                    let sliced_index = (z * 2 + x) * 3 + y;
                    let reference_index = (z * 2 + x) * 3;
                    assert_eq!(actual[sliced_index].to_bits(), reference[reference_index].to_bits());
                }
            }
        }
        assert_eq!(unsafe { jpe_runtime_density_graph_release(plain) }, STATUS_OK);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(sliced) }, STATUS_OK);
    }

    #[test]
    fn perlin_point_sampler_matches_single_voxel_volume_bit_for_bit() {
        let permutation: [u8; 256] = std::array::from_fn(|i| ((i * 73 + 19) & 255) as u8);
        let cases = [
            (-33, -17, -9), (-1, 0, 1), (0, 0, 0), (1, 2, 3),
            (15, 31, 47), (127, -128, 255), (-1024, 2048, -4096),
            (i32::MIN, i32::MAX, -1), (i32::MAX, i32::MIN, 1),
        ];
        let scales = [
            (0.375_f64, 0.625_f64, 0.13_f64, -0.17_f64, 0.23_f64, 0.75_f32),
            (1.25_f64, 0.875_f64, -0.19_f64, 0.11_f64, -0.07_f64, -0.25_f32),
            (0.8_f64, 1.2_f64, 1.0e-7_f64, -1.0e-7_f64, 0.333_f64, 1.0_f32),
        ];
        for &(x, y, z) in &cases {
            for &(xz_scale, y_scale, offset_x, offset_y, offset_z, amplitude) in &scales {
                let mut point = [0.125_f32];
                let mut volume = [0.125_f32];
                assert_eq!(unsafe {
                    jpe_cpp_perlin_sample_point(
                        permutation.as_ptr(), x, y, z,
                        offset_x, offset_y, offset_z, xz_scale, y_scale,
                        amplitude, point.as_mut_ptr(),
                    )
                }, STATUS_OK);
                assert_eq!(unsafe {
                    jpe_cpp_perlin_add_volume(
                        permutation.as_ptr(), 1, 1, 1,
                        x, y, z, 1, 1, 1,
                        offset_x, offset_y, offset_z, xz_scale, y_scale,
                        amplitude, volume.as_mut_ptr(), 1, 0,
                    )
                }, STATUS_OK);
                assert_eq!(point[0].to_bits(), volume[0].to_bits(),
                    "point/volume mismatch at ({x}, {y}, {z}) with scales ({xz_scale}, {y_scale})");
            }
        }
    }

    #[test]
    fn density_graph_executes_domain_warped_perlin_with_per_voxel_shift_inputs() {
        let permutation_a: [u8; 256] = std::array::from_fn(|i| ((i * 41 + 7) & 255) as u8);
        let permutation_b: [u8; 256] = std::array::from_fn(|i| ((i * 17 + 29) & 255) as u8);
        let layers = [
            JpePerlinLayer {
                permutation: permutation_a.as_ptr(),
                offset_x: 0.13, offset_y: -0.17, offset_z: 0.23,
                xz_scale: 0.375, y_scale: 0.625, amplitude: 0.75,
            },
            JpePerlinLayer {
                permutation: permutation_b.as_ptr(),
                offset_x: -0.19, offset_y: 0.11, offset_z: -0.07,
                xz_scale: 1.25, y_scale: 0.875, amplitude: -0.25,
            },
        ];
        let stack = unsafe { jpe_runtime_prepare_perlin_stack(layers.as_ptr(), layers.len() as u32) };
        assert_ne!(stack, 0);

        let nodes = [
            JpeDensityNode { opcode: DENSITY_X, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.01, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_MUL, input_a: 0,
                input_b: 1, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_Y, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: -0.02, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_MUL, input_a: 3,
                input_b: 4, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_Z, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.03, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_MUL, input_a: 6,
                input_b: 7, reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: DENSITY_PERLIN_STACK_WARP, input_a: 2,
                input_b: 5, reserved: 8, immediate: stack as f64,
                auxiliary: 0.8, tertiary: 1.2 },
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 9, 0xD0A1_263)
        };
        assert_ne!(graph, 0);
        assert_eq!(jpe_runtime_release_prepared_perlin_stack(stack), STATUS_OK);

        let mut expected = [0.0_f32; 24];
        let mut out = 0usize;
        for z in 0..4 {
            let world_z = 7 + z;
            for x in 0..2 {
                let world_x = -3 + x;
                for y in 0..3 {
                    let world_y = -5 + y * 2;
                    // Match the F32 density DAG's shift multiplication before
                    // promoting the resulting shift values to the Perlin sampler.
                    let shift_x = (world_x as f32) * 0.01_f32;
                    let shift_y = (world_y as f32) * -0.02_f32;
                    let shift_z = (world_z as f32) * 0.03_f32;
                    let direct_layers = [
                        JpePerlinLayer {
                            offset_x: layers[0].offset_x + shift_x as f64 * layers[0].xz_scale,
                            offset_y: layers[0].offset_y + shift_y as f64 * layers[0].y_scale,
                            offset_z: layers[0].offset_z + shift_z as f64 * layers[0].xz_scale,
                            xz_scale: 0.8 * layers[0].xz_scale,
                            y_scale: 1.2 * layers[0].y_scale,
                            ..layers[0]
                        },
                        JpePerlinLayer {
                            offset_x: layers[1].offset_x + shift_x as f64 * layers[1].xz_scale,
                            offset_y: layers[1].offset_y + shift_y as f64 * layers[1].y_scale,
                            offset_z: layers[1].offset_z + shift_z as f64 * layers[1].xz_scale,
                            xz_scale: 0.8 * layers[1].xz_scale,
                            y_scale: 1.2 * layers[1].y_scale,
                            ..layers[1]
                        },
                    ];
                    assert_eq!(unsafe {
                        jpe_runtime_perlin_add_stack(
                            direct_layers.as_ptr(), 2, 1, 1, 1,
                            world_x, world_y, world_z, 1, 1, 1,
                            &mut expected[out], 1, 0,
                        )
                    }, STATUS_OK);
                    out += 1;
                }
            }
        }

        let mut actual = [0.0_f32; 24];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 2, 3, 4, -3, -5, 7, 1, 2, 1,
                actual.as_mut_ptr(), actual.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(actual.map(f32::to_bits), expected.map(f32::to_bits));
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_executes_prepared_perlin_as_a_volume_node_and_retains_it() {
        let permutation: [u8; 256] = std::array::from_fn(|i| ((i * 37 + 11) & 255) as u8);
        let layer = JpePerlinLayer {
            permutation: permutation.as_ptr(),
            offset_x: 0.13, offset_y: -0.17, offset_z: 0.23,
            xz_scale: 0.375, y_scale: 0.625, amplitude: 0.75,
        };
        let stack = unsafe { jpe_runtime_prepare_perlin_stack(&layer, 1) };
        assert_ne!(stack, 0);

        let nodes = [
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.125 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: 7, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0.875_f32.to_bits(),
                immediate: stack as f64 , auxiliary: f64::from_bits(0x3fb999999999999b),
                tertiary: f64::from_bits(0x3fc9999999999999)},
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0, input_b: 1,
                reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 2, 0xD3517A)
        };
        assert_ne!(graph, 0);

        // The compiled graph owns a retained native stack reference.
        assert_eq!(jpe_runtime_release_prepared_perlin_stack(stack), STATUS_OK);
        let mut actual = [0.0_f64; 24];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                graph, 2, 3, 4, -3, -5, 7, 1, 2, 1,
                actual.as_mut_ptr(), actual.len() as u64,
            )
        }, STATUS_OK);

        let sampler_xz = f64::from_bits(0x3fb999999999999b);
        let sampler_y = f64::from_bits(0x3fc9999999999999);
        let mut expected = [0.0_f32; 24];
        let direct_layer = JpePerlinLayer {
            xz_scale: layer.xz_scale * sampler_xz,
            y_scale: layer.y_scale * sampler_y,
            amplitude: layer.amplitude * 0.875,
            ..layer
        };
        assert_eq!(unsafe {
            jpe_runtime_perlin_add_stack(
                &direct_layer, 1, 2, 3, 4, -3, -5, 7, 1, 2, 1,
                expected.as_mut_ptr(), expected.len() as u64, 0,
            )
        }, STATUS_OK);
        for (actual, expected) in actual.iter().zip(expected.iter()) {
            assert_eq!(*actual, (*expected as f64) + 0.125);
        }

        let mut actual_f32 = [0.0_f32; 24];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 2, 3, 4, -3, -5, 7, 1, 2, 1,
                actual_f32.as_mut_ptr(), actual_f32.len() as u64,
            )
        }, STATUS_OK);
        for (actual, expected) in actual_f32.iter().zip(expected.iter()) {
            assert_eq!(*actual, *expected + 0.125);
        }
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_f32_preserves_single_precision_intermediate_rounding() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 16_777_216.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0, input_b: 1,
                reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: -16_777_216.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 2, input_b: 3,
                reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4, 0xF32_263)
        };
        assert_ne!(graph, 0);

        let mut output = [f32::NAN; 4];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 2, 2, 1, 0, 0, 0, 1, 1, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(output, [0.0; 4]);

        let mut double_output = [f64::NAN; 4];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                graph, 2, 2, 1, 0, 0, 0, 1, 1, 1,
                double_output.as_mut_ptr(), double_output.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(double_output, [1.0; 4]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_f32_preserves_nan_and_signed_zero_for_min_max_sign() {
        let cases: [([JpeDensityNode; 3], u32, fn(f32) -> bool); 3] = [
            ([
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: f64::NAN , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: 10, input_a: 0, input_b: 1, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            ], 2, |v| v.is_nan()),
            ([
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: -0.0 , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: 11, input_a: 0, input_b: 1, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            ], 2, |v| v == 0.0 && !v.is_sign_negative()),
            ([
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: -0.0 , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: 20, input_a: 0, input_b: DENSITY_NO_INPUT,
                    reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            ], 1, |v| v == 0.0 && v.is_sign_negative()),
        ];
        for (nodes, root, check) in cases {
            let graph = unsafe {
                jpe_runtime_density_graph_compile(
                    nodes.as_ptr(), nodes.len() as u32, root, 0xF32_264 + root as u64,
                )
            };
            assert_ne!(graph, 0);
            let mut output = [1.0_f32; 1];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate_f32(
                    graph, 1, 1, 1, 0, 0, 0, 1, 1, 1,
                    output.as_mut_ptr(), 1,
                )
            }, STATUS_OK);
            assert!(check(output[0]), "unexpected f32 native value: {:?}", output[0]);
            assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
        }
    }

    #[test]
    fn density_graph_f32_executes_round_floor_multiple_and_log() {
        let round_nodes = [
            JpeDensityNode { opcode: 2, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 2.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: 28, input_a: 0, input_b: 1,
                reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
        ];
        let round_graph = unsafe {
            jpe_runtime_density_graph_compile(round_nodes.as_ptr(), round_nodes.len() as u32, 2, 0xF32_270)
        };
        assert_ne!(round_graph, 0);
        let mut rounded = [f32::NAN; 2];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                round_graph, 2, 1, 1, -1, 0, 0, 3, 1, 1,
                rounded.as_mut_ptr(), rounded.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(rounded, [-2.0, 2.0]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(round_graph) }, STATUS_OK);

        let e = std::f32::consts::E;
        let log_nodes = [
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: e as f64 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: 29, input_a: 0, input_b: DENSITY_NO_INPUT,
                reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
        ];
        let log_graph = unsafe {
            jpe_runtime_density_graph_compile(log_nodes.as_ptr(), log_nodes.len() as u32, 1, 0xF32_271)
        };
        assert_ne!(log_graph, 0);
        let mut logged = [f32::NAN; 1];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                log_graph, 1, 1, 1, 0, 0, 0, 1, 1, 1,
                logged.as_mut_ptr(), 1,
            )
        }, STATUS_OK);
        assert_eq!(logged[0], (e as f64).ln() as f32);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(log_graph) }, STATUS_OK);
    }

    #[test]
    fn aquifer_fluid_status_matches_minecraft_surface_flooding_and_lava_rules() {
        const AIR: u32 = 0;
        const WATER: u32 = 1;
        const LAVA: u32 = 2;
        let air_probe = JpeAquiferFluidProbe {
            status: JpeAquiferFluidStatus { fluid_level: i32::MIN, fluid_type: AIR },
            valid: 1,
            fluid_type_is_air: 1,
        };
        let mut base = JpeAquiferFluidStatusInput {
            block_y: -1,
            global_fluid: JpeAquiferFluidStatus { fluid_level: 50, fluid_type: WATER },
            surface_level: [-5; 13],
            global_fluid_at_surface: [air_probe; 13],
            exclusion_noise: 0.0,
            floodedness_noise: -0.5,
            fluid_level_spread_noise: 0.5,
            lava_noise: 0.31,
            lava_type: LAVA,
        };
        // Center surface is below the top of the aquifer cell, and the center
        // global FluidPicker status is non-air. Other twelve results are air.
        base.surface_level[0] = 5;
        base.global_fluid_at_surface[0] = JpeAquiferFluidProbe {
            status: JpeAquiferFluidStatus { fluid_level: 15, fluid_type: WATER },
            valid: 1,
            fluid_type_is_air: 0,
        };

        let mut output = [JpeAquiferFluidStatus::default(); 1];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_fluid_status_batch(&base, 1, output.as_mut_ptr())
        }, STATUS_OK);
        // floorDiv(-1,40)=-1 => middleY=-20; spread 0.5*10f quantizes
        // to +3; target=-17; the lower surface wins; lava noise then selects lava.
        assert_eq!(output[0].fluid_level, -17);
        assert_eq!(output[0].fluid_type, LAVA);

        // A fully flooded cell uses the global fluid level and never applies
        // the underground lava override when the result is above -10.
        let fully_flooded = JpeAquiferFluidStatusInput {
            floodedness_noise: 1.0,
            lava_noise: 1.0,
            ..base
        };
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_fluid_status_batch(
                &fully_flooded, 1, output.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(output[0], fully_flooded.global_fluid);

        // Exclusion noise suppresses both floodedness modes and returns the
        // dimension-wide below-min-Y sentinel without changing the FluidType.
        let excluded = JpeAquiferFluidStatusInput {
            exclusion_noise: 0.01,
            lava_noise: 1.0,
            ..base
        };
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_fluid_status_batch(
                &excluded, 1, output.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(output[0].fluid_level, -32512);
        assert_eq!(output[0].fluid_type, WATER);

        // Vanilla's first center-cell shortcut returns the global status
        // before reading any surface FluidPicker probes.
        let mut center_shortcut = base;
        center_shortcut.surface_level[0] = -100;
        for probe in &mut center_shortcut.global_fluid_at_surface {
            probe.valid = 0;
        }
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_fluid_status_batch(
                &center_shortcut, 1, output.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(output[0], center_shortcut.global_fluid);

        // The first raised surface with non-air global fluid returns that exact
        // FluidStatus immediately, preserving vanilla's 13-probe short circuit.
        let mut raised_surface = base;
        raised_surface.block_y = 0;
        raised_surface.surface_level[0] = 0;
        raised_surface.surface_level[1] = -5;
        raised_surface.global_fluid_at_surface[0] = air_probe;
        raised_surface.global_fluid_at_surface[1] = JpeAquiferFluidProbe {
            status: JpeAquiferFluidStatus { fluid_level: 20, fluid_type: LAVA },
            valid: 1,
            fluid_type_is_air: 0,
        };
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_fluid_status_batch(
                &raised_surface, 1, output.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(output[0].fluid_level, 20);
        assert_eq!(output[0].fluid_type, LAVA);

        // An invalid required probe is rejected transactionally.
        let mut invalid = base;
        invalid.global_fluid_at_surface[1].valid = 0;
        let sentinel = JpeAquiferFluidStatus { fluid_level: 123, fluid_type: 456 };
        output[0] = sentinel;
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_fluid_status_batch(
                &invalid, 1, output.as_mut_ptr(),
            )
        }, STATUS_INVALID_ARGUMENT);
        assert_eq!(output[0], sentinel);

        // Input and output ranges may not alias.
        #[repr(C)]
        union StatusOverlap {
            input: JpeAquiferFluidStatusInput,
            output: JpeAquiferFluidStatus,
        }
        let mut overlap = StatusOverlap { input: base };
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_fluid_status_batch(
                std::ptr::addr_of!(overlap.input), 1,
                std::ptr::addr_of_mut!(overlap.output),
            )
        }, STATUS_INVALID_ARGUMENT);
    }

    #[test]
    fn aquifer_core_preserves_density_and_flowing_update_decisions() {
        const AIR: u32 = 0;
        const WATER: u32 = 1;
        const LAVA: u32 = 2;
        let water = JpeAquiferFluidStatus { fluid_level: 20, fluid_type: WATER };
        let lava = JpeAquiferFluidStatus { fluid_level: 20, fluid_type: LAVA };
        let air = JpeAquiferFluidStatus { fluid_level: -64, fluid_type: WATER };
        let base = JpeAquiferCoreInput {
            block_y: 10,
            density: -1.0,
            barrier_noise: 0.0,
            distance_sqr: [0, 1, 4, 9],
            closest: [water, water, water, water],
            global_fluid_below_type: AIR,
            air_type: AIR,
            water_type: WATER,
            lava_type: LAVA,
            debug_disable_fluid_generation: 0,
        };

        // Positive density means terrain wins and no aquifer state is returned.
        let mut positive = base;
        positive.density = 0.25;
        let mut output = JpeAquiferCoreOutput::default();
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_substance_core(&positive, &mut output)
        }, STATUS_OK);
        assert_eq!(output.has_block_state, 0);
        assert_eq!(output.should_schedule_fluid_update, 0);

        // Equal fluid statuses have no pressure barrier. Water above a global
        // lava layer schedules a fluid update, matching the special adjacency case.
        let mut adjacency = base;
        adjacency.global_fluid_below_type = LAVA;
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_substance_core(&adjacency, &mut output)
        }, STATUS_OK);
        assert_eq!(output.has_block_state, 1);
        assert_eq!(output.block_state_type, WATER);
        assert_eq!(output.should_schedule_fluid_update, 1);

        // At similarity zero, the closest fluid is retained but a nearby
        // unequal status requests a fluid update.
        let mut threshold = base;
        threshold.distance_sqr = [0, 25, 100, 144];
        threshold.closest = [water, lava, water, water];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_substance_core(&threshold, &mut output)
        }, STATUS_OK);
        assert_eq!(output.has_block_state, 1);
        assert_eq!(output.block_state_type, WATER);
        assert_eq!(output.should_schedule_fluid_update, 1);

        // Debug fluid suppression changes the returned block to air without
        // suppressing the aquifer's fluid-update decision.
        let mut debug = adjacency;
        debug.debug_disable_fluid_generation = 1;
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_substance_core(&debug, &mut output)
        }, STATUS_OK);
        assert_eq!(output.has_block_state, 1);
        assert_eq!(output.block_state_type, AIR);
        assert_eq!(output.should_schedule_fluid_update, 1);

        // Keep a non-fluid status fixture available to guard at(y) semantics.
        assert_eq!(air.fluid_type, WATER);
    }

    #[test]
    fn aquifer_batch_matches_scalar_and_rejects_invalid_batches_transactionally() {
        const AIR: u32 = 0;
        const WATER: u32 = 1;
        const LAVA: u32 = 2;
        let water = JpeAquiferFluidStatus { fluid_level: 20, fluid_type: WATER };
        let lava = JpeAquiferFluidStatus { fluid_level: 20, fluid_type: LAVA };
        let base = JpeAquiferCoreInput {
            block_y: 10,
            density: -1.0,
            barrier_noise: 0.0,
            distance_sqr: [0, 1, 4, 9],
            closest: [water, water, water, water],
            global_fluid_below_type: AIR,
            air_type: AIR,
            water_type: WATER,
            lava_type: LAVA,
            debug_disable_fluid_generation: 0,
        };
        let inputs = [
            base,
            JpeAquiferCoreInput { density: 0.25, ..base },
            JpeAquiferCoreInput {
                closest: [water, lava, water, water],
                global_fluid_below_type: LAVA,
                ..base
            },
            JpeAquiferCoreInput {
                debug_disable_fluid_generation: 1,
                ..base
            },
        ];
        let mut batch = [JpeAquiferCoreOutput::default(); 4];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_substance_batch(
                inputs.as_ptr(), inputs.len() as u32, batch.as_mut_ptr(),
            )
        }, STATUS_OK);

        for index in 0..inputs.len() {
            let mut scalar = JpeAquiferCoreOutput::default();
            assert_eq!(unsafe {
                jpe_runtime_aquifer_compute_substance_core(&inputs[index], &mut scalar)
            }, STATUS_OK);
            assert_eq!(batch[index].has_block_state, scalar.has_block_state, "voxel {index}");
            assert_eq!(batch[index].block_state_type, scalar.block_state_type, "voxel {index}");
            assert_eq!(
                batch[index].should_schedule_fluid_update,
                scalar.should_schedule_fluid_update,
                "voxel {index}",
            );
        }

        // Invalid data anywhere in a batch must not leave earlier output slots
        // partially overwritten.
        let mut invalid = inputs;
        invalid[3].distance_sqr[2] = -1;
        let sentinel = JpeAquiferCoreOutput {
            has_block_state: 77,
            block_state_type: 88,
            should_schedule_fluid_update: 99,
        };
        let mut untouched = [sentinel; 4];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_substance_batch(
                invalid.as_ptr(), invalid.len() as u32, untouched.as_mut_ptr(),
            )
        }, STATUS_INVALID_ARGUMENT);
        assert!(untouched.iter().all(|value| {
            value.has_block_state == 77
                && value.block_state_type == 88
                && value.should_schedule_fluid_update == 99
        }));

        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_substance_batch(
                inputs.as_ptr(), 0, batch.as_mut_ptr(),
            )
        }, STATUS_INVALID_ARGUMENT);

        // The native volume API rejects aliased input/output spans before it writes.
        #[repr(C)]
        union OverlappingAquiferBuffer {
            input: JpeAquiferCoreInput,
            output: JpeAquiferCoreOutput,
        }
        let mut overlap = OverlappingAquiferBuffer { input: base };
        let input_ptr = std::ptr::addr_of!(overlap.input);
        let output_ptr = std::ptr::addr_of_mut!(overlap.output);
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_substance_batch(input_ptr, 1, output_ptr)
        }, STATUS_INVALID_ARGUMENT);
        assert_eq!(unsafe { (*input_ptr).block_y }, base.block_y);
    }

    #[test]
    fn aquifer_context_retains_six_sampler_graphs_and_evaluates_one_batch() {
        let mut graph_handles = [0_u64; 6];
        for sampler in 0..6_u32 {
            let nodes = [
                JpeDensityNode {
                    opcode: DENSITY_X,
                    input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT,
                    reserved: 0,
                    immediate: 0.0,
                    auxiliary: 0.0,
                    tertiary: 0.0,
                },
                JpeDensityNode {
                    opcode: DENSITY_CONST,
                    input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT,
                    reserved: 0,
                    immediate: sampler as f64,
                    auxiliary: 0.0,
                    tertiary: 0.0,
                },
                JpeDensityNode {
                    opcode: DENSITY_ADD,
                    input_a: 0,
                    input_b: 1,
                    reserved: 0,
                    immediate: 0.0,
                    auxiliary: 0.0,
                    tertiary: 0.0,
                },
            ];
            graph_handles[sampler as usize] = unsafe {
                jpe_runtime_density_graph_compile(
                    nodes.as_ptr(), nodes.len() as u32, 2, 0xA9A1_F3A0 + sampler as u64,
                )
            };
            assert_ne!(graph_handles[sampler as usize], 0, "sampler {sampler} compile failed");
        }

        let info = JpeAquiferContextCreateInfo {
            density_graph_handles: graph_handles,
            world_fingerprint: [0x5A; 32],
            semantic_fingerprint: 0xA91F_2630_0001,
            seed_lo: 0x0123_4567_89ab_cdef,
            seed_hi: 0xfedc_ba98_7654_3210,
            random_mode: 0,
            air_type: 100,
            water_type: 200,
            lava_type: 300,
            max_status_cache_entries: 256,
            reserved: 0,
        };
        let mut invalid_info = info;
        invalid_info.random_mode = 2;
        assert_eq!(unsafe { jpe_runtime_aquifer_context_create(&invalid_info) }, 0);
        invalid_info = info;
        invalid_info.world_fingerprint = [0; 32];
        assert_eq!(unsafe { jpe_runtime_aquifer_context_create(&invalid_info) }, 0);
        invalid_info = info;
        invalid_info.density_graph_handles[5] = 0;
        assert_eq!(unsafe { jpe_runtime_aquifer_context_create(&invalid_info) }, 0);
        invalid_info = info;
        invalid_info.air_type = invalid_info.water_type;
        assert_eq!(unsafe { jpe_runtime_aquifer_context_create(&invalid_info) }, 0);
        invalid_info = info;
        invalid_info.max_status_cache_entries = 0;
        assert_eq!(unsafe { jpe_runtime_aquifer_context_create(&invalid_info) }, 0);

        let context = unsafe { jpe_runtime_aquifer_context_create(&info) };
        assert_ne!(context, 0, "Aquifer context creation failed");

        // Drop the caller's graph references. The context must keep each immutable
        // sampler plan alive until its own destruction.
        for graph in graph_handles {
            assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
        }

        let xs = [
            1, 2, 3,
            4, 5, 6,
            7, 8, 9,
            10, 11, 12,
            13, 14, 15,
            16, 17, 18,
        ];
        let ys = [0_i32; 18];
        let zs = [0_i32; 18];
        let expected_fingerprint = info.world_fingerprint;
        let expected_semantic_fingerprint = info.semantic_fingerprint;
        let mut output = [f32::NAN; 18];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_evaluate_samplers_f32(
                context, expected_fingerprint.as_ptr(), expected_semantic_fingerprint,
                xs.as_ptr(), ys.as_ptr(), zs.as_ptr(),
                3, output.as_mut_ptr(), output.len() as u64 - 1,
            )
        }, STATUS_INVALID_ARGUMENT);
        assert!(output.iter().all(|value| value.is_nan()));
        let wrong_fingerprint = [0xA5_u8; 32];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_evaluate_samplers_f32(
                context, wrong_fingerprint.as_ptr(), expected_semantic_fingerprint,
                xs.as_ptr(), ys.as_ptr(), zs.as_ptr(),
                3, output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_INVALID_ARGUMENT);
        assert!(output.iter().all(|value| value.is_nan()));
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_evaluate_samplers_f32(
                context, expected_fingerprint.as_ptr(), expected_semantic_fingerprint ^ 1,
                xs.as_ptr(), ys.as_ptr(), zs.as_ptr(),
                3, output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_INVALID_ARGUMENT);
        assert!(output.iter().all(|value| value.is_nan()));
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_evaluate_samplers_f32(
                u64::MAX, expected_fingerprint.as_ptr(), expected_semantic_fingerprint,
                xs.as_ptr(), ys.as_ptr(), zs.as_ptr(),
                3, output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_INVALID_ARGUMENT);
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_evaluate_samplers_f32(
                context, expected_fingerprint.as_ptr(), expected_semantic_fingerprint,
                xs.as_ptr(), ys.as_ptr(), zs.as_ptr(),
                0, output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_INVALID_ARGUMENT);

        #[repr(C)]
        union AquiferContextOverlap {
            coordinates: [i32; 18],
            values: [f32; 18],
        }
        let mut overlap = AquiferContextOverlap { coordinates: xs };
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_evaluate_samplers_f32(
                context,
                expected_fingerprint.as_ptr(),
                expected_semantic_fingerprint,
                std::ptr::addr_of!(overlap.coordinates).cast::<i32>(),
                ys.as_ptr(),
                zs.as_ptr(),
                3,
                std::ptr::addr_of_mut!(overlap.values).cast::<f32>(),
                18,
            )
        }, STATUS_INVALID_ARGUMENT);

        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_evaluate_samplers_f32(
                context, expected_fingerprint.as_ptr(), expected_semantic_fingerprint,
                xs.as_ptr(), ys.as_ptr(), zs.as_ptr(),
                3, output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        for sampler in 0..6 {
            for point in 0..3 {
                let index = sampler * 3 + point;
                let expected = xs[index] as f32 + sampler as f32;
                assert_eq!(
                    output[index].to_bits(), expected.to_bits(),
                    "sampler {sampler} point {point}"
                );
            }
        }

        let air_probe = JpeAquiferFluidProbe {
            status: JpeAquiferFluidStatus { fluid_level: i32::MIN, fluid_type: info.air_type },
            valid: 0,
            fluid_type_is_air: 1,
        };
        let status_input = JpeAquiferFluidStatusInput {
            block_y: 0,
            global_fluid: JpeAquiferFluidStatus { fluid_level: 40, fluid_type: info.water_type },
            surface_level: [-100; 13],
            global_fluid_at_surface: [air_probe; 13],
            exclusion_noise: 0.0,
            floodedness_noise: 0.0,
            fluid_level_spread_noise: 0.0,
            lava_noise: 0.0,
            lava_type: info.lava_type,
        };
        let conflicting_input = JpeAquiferFluidStatusInput {
            global_fluid: JpeAquiferFluidStatus { fluid_level: 90, fluid_type: info.lava_type },
            ..status_input
        };
        let status_keys = [0x1122_3344_5566_7788_u64, 0x1122_3344_5566_7788_u64];
        let status_inputs = [status_input, conflicting_input];
        let mut statuses = [JpeAquiferFluidStatus::default(); 2];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_fluid_status_batch(
                context, expected_fingerprint.as_ptr(), expected_semantic_fingerprint,
                status_keys.as_ptr(), status_inputs.as_ptr(), 2, statuses.as_mut_ptr(),
            )
        }, STATUS_OK);
        let first_writer = JpeAquiferFluidStatus { fluid_level: 40, fluid_type: info.water_type };
        assert_eq!(statuses, [first_writer; 2]);

        // A later conflicting value for an existing key must not replace the first writer.
        let later_key = [status_keys[0]];
        let later_input = [conflicting_input];
        let mut later_output = [JpeAquiferFluidStatus::default(); 1];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_fluid_status_batch(
                context, expected_fingerprint.as_ptr(), expected_semantic_fingerprint,
                later_key.as_ptr(), later_input.as_ptr(), 1, later_output.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(later_output[0], first_writer);

        let invalid_status_input = [JpeAquiferFluidStatusInput {
            surface_level: [0; 13],
            ..status_input
        }];
        let new_key = [0x8877_6655_4433_2211_u64];
        let sentinel_status = JpeAquiferFluidStatus { fluid_level: 123, fluid_type: 456 };
        later_output[0] = sentinel_status;
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_fluid_status_batch(
                context, expected_fingerprint.as_ptr(), expected_semantic_fingerprint,
                new_key.as_ptr(), invalid_status_input.as_ptr(), 1, later_output.as_mut_ptr(),
            )
        }, STATUS_INVALID_ARGUMENT);
        assert_eq!(later_output[0], sentinel_status);

        let wrong_world = [0xA5_u8; 32];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_fluid_status_batch(
                context, wrong_world.as_ptr(), expected_semantic_fingerprint,
                new_key.as_ptr(), status_inputs.as_ptr(), 1, later_output.as_mut_ptr(),
            )
        }, STATUS_INVALID_ARGUMENT);
        assert_eq!(later_output[0], sentinel_status);

        let wrong_lava_token = [JpeAquiferFluidStatusInput {
            lava_type: info.lava_type + 1,
            ..status_input
        }];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_fluid_status_batch(
                context, expected_fingerprint.as_ptr(), expected_semantic_fingerprint,
                new_key.as_ptr(), wrong_lava_token.as_ptr(), 1, later_output.as_mut_ptr(),
            )
        }, STATUS_INVALID_ARGUMENT);
        assert_eq!(later_output[0], sentinel_status);

        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_configure_global_fluid_picker(
                context, info.world_fingerprint.as_ptr(), info.semantic_fingerprint,
                63, -2032, info.water_type, 0, 0,
            )
        }, STATUS_OK);
        // Context picker configuration is immutable for this world.
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_configure_global_fluid_picker(
                context, info.world_fingerprint.as_ptr(), info.semantic_fingerprint,
                64, -2032, info.water_type, 0, 0,
            )
        }, STATUS_INVALID_ARGUMENT);

        // The six compiled graphs are now sampled entirely inside C++:
        // graph 4 (exclusion) is positive, so the status is the vanilla
        // WAY_BELOW_MIN_Y sentinel while preserving the global water type.
        // Keep every 13-probe surface above the cell top so the test reaches
        // the positive exclusion-noise branch instead of vanilla's earlier
        // global-FluidPicker surface shortcut. BlockPos.asLong(64, 0, 0).
        let point_keys = [64_u64 << 38, 64_u64 << 38];
        let point_x = [64_i32, 64_i32];
        let point_y = [0_i32, 0_i32];
        let point_z = [0_i32, 0_i32];
        let mut point_statuses = [JpeAquiferFluidStatus::default(); 2];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_fluid_status_points_batch(
                context, info.world_fingerprint.as_ptr(), info.semantic_fingerprint,
                point_keys.as_ptr(), point_x.as_ptr(), point_y.as_ptr(), point_z.as_ptr(),
                2, point_statuses.as_mut_ptr(),
            )
        }, STATUS_OK);
        let excluded = JpeAquiferFluidStatus {
            fluid_level: -32512,
            fluid_type: info.water_type,
        };
        assert_eq!(point_statuses, [excluded; 2]);

        // The packed key is verified against (x,y,z) before any caller output is written.
        let wrong_point_keys = [1_u64, 1_u64];
        let untouched = [JpeAquiferFluidStatus { fluid_level: 123, fluid_type: 456 }; 2];
        point_statuses = untouched;
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_fluid_status_points_batch(
                context, info.world_fingerprint.as_ptr(), info.semantic_fingerprint,
                wrong_point_keys.as_ptr(), point_x.as_ptr(), point_y.as_ptr(), point_z.as_ptr(),
                2, point_statuses.as_mut_ptr(),
            )
        }, STATUS_INVALID_ARGUMENT);
        assert_eq!(point_statuses, untouched);

        // The full volume pass returns above-surface global fluid directly,
        // skips positive-density voxels, and executes one negative-density voxel
        // through native nearest-four, FluidStatus, barrier and core decisions.
        let volume_densities = [-1.0_f32, 1.0_f32];
        let mut volume_outputs = [JpeAquiferCoreOutput::default(); 2];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_substance_volume_f32(
                context, expected_fingerprint.as_ptr(), expected_semantic_fingerprint,
                -1, 2, 1, 1, 64, 0, 0, 1, 1, 1,
                volume_densities.as_ptr(), 2, volume_outputs.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(volume_outputs[0].has_block_state, 1);
        assert_eq!(volume_outputs[0].block_state_type, info.water_type);
        assert_eq!(volume_outputs[0].should_schedule_fluid_update, 0);
        assert_eq!(volume_outputs[1].has_block_state, 0);
        assert_eq!(volume_outputs[1].block_state_type, info.air_type);
        assert_eq!(volume_outputs[1].should_schedule_fluid_update, 0);

        let active_density = [-1.0_f32];
        let mut active_output = [JpeAquiferCoreOutput::default(); 1];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_substance_volume_f32(
                context, expected_fingerprint.as_ptr(), expected_semantic_fingerprint,
                0, 1, 1, 1, 64, 0, 0, 1, 1, 1,
                active_density.as_ptr(), 1, active_output.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(active_output[0].has_block_state, 1);
        assert_eq!(active_output[0].block_state_type, info.air_type);
        assert_eq!(active_output[0].should_schedule_fluid_update, 0);

        // Invalid volume dimensions are rejected before touching output.
        let untouched_volume = [JpeAquiferCoreOutput {
            has_block_state: 77,
            block_state_type: 88,
            should_schedule_fluid_update: 99,
        }; 1];
        active_output = untouched_volume;
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_substance_volume_f32(
                context, expected_fingerprint.as_ptr(), expected_semantic_fingerprint,
                0, 2, 1, 1, 64, 0, 0, 1, 1, 1,
                active_density.as_ptr(), 1, active_output.as_mut_ptr(),
            )
        }, STATUS_INVALID_ARGUMENT);
        assert_eq!(active_output[0].has_block_state, 77);
        assert_eq!(active_output[0].block_state_type, 88);
        assert_eq!(active_output[0].should_schedule_fluid_update, 99);

        assert_eq!(unsafe { jpe_runtime_aquifer_context_destroy(context) }, STATUS_OK);
        assert_eq!(unsafe { jpe_runtime_aquifer_context_destroy(context) }, STATUS_INVALID_ARGUMENT);
    }

    #[test]
    fn aquifer_context_generates_status_from_native_graphs_and_picker() {
        let graph_values = [0.0_f64, -0.2, 0.0, 0.0, 0.0, 10.0];
        let mut handles = [0_u64; 6];
        for i in 0..6_u32 {
            let node = [JpeDensityNode {
                opcode: DENSITY_CONST,
                input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT,
                reserved: 0,
                immediate: graph_values[i as usize],
                auxiliary: 0.0,
                tertiary: 0.0,
            }];
            handles[i as usize] = unsafe {
                jpe_runtime_density_graph_compile(
                    node.as_ptr(), 1, 0, 0xA91F_2632_0000 + i as u64,
                )
            };
            assert_ne!(handles[i as usize], 0);
        }
        let fingerprint = [0x6B_u8; 32];
        let info = JpeAquiferContextCreateInfo {
            density_graph_handles: handles,
            world_fingerprint: fingerprint,
            semantic_fingerprint: 0xA91F_2632_0001,
            seed_lo: 11,
            seed_hi: 22,
            random_mode: 0,
            air_type: 100,
            water_type: 200,
            lava_type: 300,
            max_status_cache_entries: 256,
            reserved: 0,
        };
        let context = unsafe { jpe_runtime_aquifer_context_create(&info) };
        assert_ne!(context, 0);
        for handle in handles {
            assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
        }
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_configure_global_fluid_picker(
                context, fingerprint.as_ptr(), info.semantic_fingerprint,
                63, -2032, 200, 0, 0,
            )
        }, STATUS_OK);
        // Reconfiguration with different worldgen parameters must fail closed.
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_configure_global_fluid_picker(
                context, fingerprint.as_ptr(), info.semantic_fingerprint,
                64, -2032, 200, 0, 0,
            )
        }, STATUS_INVALID_ARGUMENT);

        let keys = [0_u64, 0_u64];
        let xs = [0_i32, 0_i32];
        let ys = [0_i32, 0_i32];
        let zs = [0_i32, 0_i32];
        let mut output = [JpeAquiferFluidStatus::default(); 2];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_fluid_status_points_batch(
                context, fingerprint.as_ptr(), info.semantic_fingerprint,
                keys.as_ptr(), xs.as_ptr(), ys.as_ptr(), zs.as_ptr(),
                2, output.as_mut_ptr(),
            )
        }, STATUS_OK);
        let expected = JpeAquiferFluidStatus { fluid_level: 10, fluid_type: 200 };
        assert_eq!(output, [expected; 2]);

        // Packed BlockPos keys are validated against all three coordinate arrays.
        let wrong_key = [1_u64, 1_u64];
        let sentinel = [JpeAquiferFluidStatus { fluid_level: 123, fluid_type: 456 }; 2];
        output = sentinel;
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_fluid_status_points_batch(
                context, fingerprint.as_ptr(), info.semantic_fingerprint,
                wrong_key.as_ptr(), xs.as_ptr(), ys.as_ptr(), zs.as_ptr(),
                2, output.as_mut_ptr(),
            )
        }, STATUS_INVALID_ARGUMENT);
        assert_eq!(output, sentinel);
        assert_eq!(unsafe { jpe_runtime_aquifer_context_destroy(context) }, STATUS_OK);

        // Debug fluid generation returns vanilla's empty global status and below-min-Y sentinel.
        let mut debug_handles = [0_u64; 6];
        for i in 0..6_u32 {
            let node = [JpeDensityNode {
                opcode: DENSITY_CONST,
                input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT,
                reserved: 0,
                immediate: graph_values[i as usize],
                auxiliary: 0.0,
                tertiary: 0.0,
            }];
            debug_handles[i as usize] = unsafe {
                jpe_runtime_density_graph_compile(
                    node.as_ptr(), 1, 0, 0xA91F_2633_0000 + i as u64,
                )
            };
            assert_ne!(debug_handles[i as usize], 0);
        }
        let debug_info = JpeAquiferContextCreateInfo {
            density_graph_handles: debug_handles,
            world_fingerprint: [0x6C; 32],
            semantic_fingerprint: 0xA91F_2633_0001,
            ..info
        };
        let debug_context = unsafe { jpe_runtime_aquifer_context_create(&debug_info) };
        assert_ne!(debug_context, 0);
        for handle in debug_handles {
            assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
        }
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_configure_global_fluid_picker(
                debug_context, debug_info.world_fingerprint.as_ptr(),
                debug_info.semantic_fingerprint, 63, -2032, 200, 0, 1,
            )
        }, STATUS_OK);
        let debug_key = [0_u64];
        let debug_x = [0_i32];
        let debug_y = [0_i32];
        let debug_z = [0_i32];
        let mut debug_output = [JpeAquiferFluidStatus::default(); 1];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_fluid_status_points_batch(
                debug_context, debug_info.world_fingerprint.as_ptr(),
                debug_info.semantic_fingerprint, debug_key.as_ptr(),
                debug_x.as_ptr(), debug_y.as_ptr(), debug_z.as_ptr(),
                1, debug_output.as_mut_ptr(),
            )
        }, STATUS_OK);
        assert_eq!(debug_output[0].fluid_level, -32512);
        assert_eq!(debug_output[0].fluid_type, debug_info.air_type);
        assert_eq!(unsafe { jpe_runtime_aquifer_context_destroy(debug_context) }, STATUS_OK);
    }

    #[test]
    fn aquifer_fused_density_volume_matches_split_native_path() {
        let sampler_nodes = [JpeDensityNode {
            opcode: DENSITY_CONST,
            input_a: DENSITY_NO_INPUT,
            input_b: DENSITY_NO_INPUT,
            reserved: 0,
            immediate: 0.0,
            auxiliary: 0.0,
            tertiary: 0.0,
        }];
        let mut sampler_handles = [0_u64; 6];
        for i in 0..6_u32 {
            sampler_handles[i as usize] = unsafe {
                jpe_runtime_density_graph_compile(
                    sampler_nodes.as_ptr(), 1, 0, 0xF053_0000 + i as u64,
                )
            };
            assert_ne!(sampler_handles[i as usize], 0);
        }
        let final_density_nodes = [JpeDensityNode {
            opcode: DENSITY_CONST,
            input_a: DENSITY_NO_INPUT,
            input_b: DENSITY_NO_INPUT,
            reserved: 0,
            immediate: -0.2,
            auxiliary: 0.0,
            tertiary: 0.0,
        }];
        let final_density_graph = unsafe {
            jpe_runtime_density_graph_compile(
                final_density_nodes.as_ptr(), 1, 0, 0xF053_1000,
            )
        };
        assert_ne!(final_density_graph, 0);

        let fingerprint = [0xF0_u8; 32];
        let info = JpeAquiferContextCreateInfo {
            density_graph_handles: sampler_handles,
            world_fingerprint: fingerprint,
            semantic_fingerprint: 0xF053_2000,
            seed_lo: 101,
            seed_hi: 202,
            random_mode: 0,
            air_type: 100,
            water_type: 200,
            lava_type: 300,
            max_status_cache_entries: 64,
            reserved: 0,
        };
        let context = unsafe { jpe_runtime_aquifer_context_create(&info) };
        assert_ne!(context, 0);
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_configure_global_fluid_picker(
                context, fingerprint.as_ptr(), info.semantic_fingerprint,
                63, -64, 200, 0, 0,
            )
        }, STATUS_OK);

        let mut fused = [JpeAquiferCoreOutput::default(); 8];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_density_and_substance_volume_f32(
                context, fingerprint.as_ptr(), info.semantic_fingerprint,
                final_density_graph, 0,
                2, 2, 2, -16, -64, 32, 1, 1, 1,
                fused.as_mut_ptr(),
            )
        }, STATUS_OK);

        let split_density = [-0.2_f32; 8];
        let mut split = [JpeAquiferCoreOutput::default(); 8];
        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_compute_substance_volume_f32(
                context, fingerprint.as_ptr(), info.semantic_fingerprint,
                0,
                2, 2, 2, -16, -64, 32, 1, 1, 1,
                split_density.as_ptr(), split_density.len() as u32,
                split.as_mut_ptr(),
            )
        }, STATUS_OK);
        for index in 0..8 {
            assert_eq!(fused[index].has_block_state, split[index].has_block_state);
            assert_eq!(fused[index].block_state_type, split[index].block_state_type);
            assert_eq!(
                fused[index].should_schedule_fluid_update,
                split[index].should_schedule_fluid_update,
            );
        }

        assert_eq!(unsafe {
            jpe_runtime_aquifer_context_destroy(context)
        }, STATUS_OK);
        for handle in sampler_handles {
            assert_eq!(unsafe { jpe_runtime_density_graph_release(handle) }, STATUS_OK);
        }
        assert_eq!(unsafe {
            jpe_runtime_density_graph_release(final_density_graph)
        }, STATUS_OK);
    }

    #[test]
    fn aquifer_nearest_four_matches_minecraft_tie_order_for_negative_coordinates() {
        let candidates = [
            JpeAquiferCellLocation { x: -95, y: 64, z: -100 }, // d2=25
            JpeAquiferCellLocation { x: -105, y: 64, z: -100 }, // d2=25, later tie wins
            JpeAquiferCellLocation { x: -100, y: 67, z: -100 }, // d2=9
            JpeAquiferCellLocation { x: -100, y: 64, z: -96 }, // d2=16
            JpeAquiferCellLocation { x: -99, y: 64, z: -100 }, // d2=1
            JpeAquiferCellLocation { x: -100, y: 65, z: -100 }, // d2=1, later tie wins
            JpeAquiferCellLocation { x: -98, y: 64, z: -100 }, // d2=4
            JpeAquiferCellLocation { x: -100, y: 64, z: -98 }, // d2=4, later tie wins
            JpeAquiferCellLocation { x: -90, y: 64, z: -100 },
            JpeAquiferCellLocation { x: -100, y: 74, z: -100 },
            JpeAquiferCellLocation { x: -100, y: 64, z: -90 },
            JpeAquiferCellLocation { x: -110, y: 64, z: -100 },
        ];
        let mut output = JpeAquiferNearestFour::default();
        assert_eq!(unsafe {
            jpe_runtime_aquifer_find_nearest_four(
                -100, 64, -100, candidates.as_ptr(), candidates.len() as u32, &mut output,
            )
        }, STATUS_OK);
        assert_eq!(output.index, [5, 4, 7, 6]);
        assert_eq!(output.distance_sqr, [1, 1, 4, 4]);

        // The native helper deliberately accepts exactly Minecraft's twelve
        // candidate cells; partial neighborhoods are not silently accepted.
        assert_eq!(unsafe {
            jpe_runtime_aquifer_find_nearest_four(
                -100, 64, -100, candidates.as_ptr(), 11, &mut output,
            )
        }, STATUS_INVALID_ARGUMENT);

        // Extreme coordinates must be rejected before signed multiplication;
        // this used to risk overflowing int64 before the int32-range check.
        let mut extreme = candidates;
        extreme[0].x = i32::MIN;
        assert_eq!(unsafe {
            jpe_runtime_aquifer_find_nearest_four(
                i32::MAX, 64, -100, extreme.as_ptr(), 12, &mut output,
            )
        }, STATUS_INVALID_ARGUMENT);
    }

    #[test]
    fn aquifer_core_applies_lava_water_pressure_and_barrier_noise() {
        const AIR: u32 = 0;
        const WATER: u32 = 1;
        const LAVA: u32 = 2;
        let water = JpeAquiferFluidStatus { fluid_level: 30, fluid_type: WATER };
        let lava = JpeAquiferFluidStatus { fluid_level: 30, fluid_type: LAVA };
        let input = JpeAquiferCoreInput {
            block_y: 10,
            density: -0.5,
            barrier_noise: 0.0,
            distance_sqr: [0, 1, 4, 9],
            closest: [water, lava, water, water],
            global_fluid_below_type: AIR,
            air_type: AIR,
            water_type: WATER,
            lava_type: LAVA,
            debug_disable_fluid_generation: 0,
        };
        let mut output = JpeAquiferCoreOutput::default();
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_substance_core(&input, &mut output)
        }, STATUS_OK);
        assert_eq!(output.has_block_state, 0,
            "water/lava interface pressure should leave a solid barrier");

        // A large positive barrier-noise sample raises pressure enough to
        // suppress fluid even when both nearby statuses are the same fluid.
        let same_status = JpeAquiferFluidStatus { fluid_level: 30, fluid_type: WATER };
        let noisy = JpeAquiferCoreInput {
            block_y: 10,
            density: -0.5,
            barrier_noise: 1.0,
            distance_sqr: [0, 1, 4, 9],
            closest: [
                JpeAquiferFluidStatus { fluid_level: 30, fluid_type: WATER },
                JpeAquiferFluidStatus { fluid_level: 20, fluid_type: WATER },
                same_status,
                same_status,
            ],
            global_fluid_below_type: AIR,
            air_type: AIR,
            water_type: WATER,
            lava_type: LAVA,
            debug_disable_fluid_generation: 0,
        };
        assert_eq!(unsafe {
            jpe_runtime_aquifer_compute_substance_core(&noisy, &mut output)
        }, STATUS_OK);
        assert_eq!(output.has_block_state, 0);
    }

    #[test]
    fn density_graph_f32_executes_all_distance_metrics() {
        let expected = [4.0_f32, 5.0_f32, 25.0_f32, 7.0_f32];
        for metric in 0..4_u32 {
            let nodes = [JpeDensityNode {
                opcode: 30,
                input_a: metric,
                input_b: DENSITY_NO_INPUT,
                reserved: 0,
                immediate: 3.0,
                auxiliary: 4.0,
                tertiary: 0.0,
            }];
            let graph = unsafe {
                jpe_runtime_density_graph_compile(
                    nodes.as_ptr(), nodes.len() as u32, 0, 0xF32_272 + metric as u64,
                )
            };
            assert_ne!(graph, 0, "distance metric {} graph rejected", metric);
            let mut output = [f32::NAN; 1];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate_f32(
                    graph, 1, 1, 1, 0, 0, 0, 1, 1, 1,
                    output.as_mut_ptr(), 1,
                )
            }, STATUS_OK);
            assert_eq!(output[0], expected[metric as usize], "metric {}", metric);
            assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
        }
    }

    #[test]
    fn density_graph_distance_to_point_preserves_negative_chunk_coordinates_and_volume_order() {
        let nodes = [JpeDensityNode {
            opcode: 30,
            input_a: 3, // Manhattan
            input_b: DENSITY_NO_INPUT,
            reserved: 0,
            immediate: 0.0,
            auxiliary: 0.0,
            tertiary: 0.0,
        }];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 1, 0, 0xD157_A11CE)
        };
        assert_ne!(graph, 0);
        let mut output = [f32::NAN; 8];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 2, 2, 2, -1, -1, -1, 1, 1, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        // Native density-volume layout is z, then x, then y.
        assert_eq!(output, [3.0, 2.0, 2.0, 1.0, 2.0, 1.0, 1.0, 0.0]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_distance_axis_hoist_preserves_all_metrics_and_volume_order() {
        let xs = [-5.0_f32, -2.0, 1.0];
        let ys = [-4.0_f32, -1.0];
        let zs = [-6.0_f32, -2.0];
        let point = (-2.0_f32, 1.0_f32, -1.0_f32);

        for metric in 0..4_u32 {
            let nodes = [JpeDensityNode {
                opcode: 30,
                input_a: metric,
                input_b: DENSITY_NO_INPUT,
                reserved: 0,
                immediate: point.0 as f64,
                auxiliary: point.1 as f64,
                tertiary: point.2 as f64,
            }];
            let graph = unsafe {
                jpe_runtime_density_graph_compile(
                    nodes.as_ptr(), 1, 0, 0xD157_A120 + metric as u64,
                )
            };
            assert_ne!(graph, 0, "distance metric {} graph rejected", metric);

            let mut expected = Vec::with_capacity(12);
            for z in zs {
                let dz = z - point.2;
                for x in xs {
                    let dx = x - point.0;
                    for y in ys {
                        let dy = y - point.1;
                        let ax = dx.abs();
                        let ay = dy.abs();
                        let az = dz.abs();
                        let value = match metric {
                            0 => ax.max(ay.max(az)),
                            1 => (dx * dx + dy * dy + dz * dz).sqrt(),
                            2 => dx * dx + dy * dy + dz * dz,
                            _ => (ax + ay) + az,
                        };
                        expected.push(value);
                    }
                }
            }

            let mut output_f32 = [f32::NAN; 12];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate_f32(
                    graph, 3, 2, 2, -5, -4, -6, 3, 3, 4,
                    output_f32.as_mut_ptr(), output_f32.len() as u64,
                )
            }, STATUS_OK);
            for index in 0..expected.len() {
                assert_eq!(
                    output_f32[index].to_bits(),
                    expected[index].to_bits(),
                    "f32 distance metric {}, voxel {}",
                    metric,
                    index,
                );
            }

            let mut output_f64 = [f64::NAN; 12];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate(
                    graph, 3, 2, 2, -5, -4, -6, 3, 3, 4,
                    output_f64.as_mut_ptr(), output_f64.len() as u64,
                )
            }, STATUS_OK);
            for index in 0..expected.len() {
                assert_eq!(
                    output_f64[index].to_bits(),
                    (expected[index] as f64).to_bits(),
                    "f64 distance metric {}, voxel {}",
                    metric,
                    index,
                );
            }
            assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
        }
    }

    #[test]
    fn density_graph_f32_executes_multi_threshold_interval_select() {
        let nodes = [
            JpeDensityNode { opcode: 2, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 10.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 20.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: -10.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: 27, input_a: 0, input_b: 2,
                reserved: 3, immediate: f64::NEG_INFINITY, auxiliary: 1.0, tertiary: 0.0},
            JpeDensityNode { opcode: 27, input_a: 0, input_b: 1,
                reserved: 4, immediate: f64::NEG_INFINITY, auxiliary: 0.0, tertiary: 0.0},
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 5, 0xF32_269)
        };
        assert_ne!(graph, 0);
        let mut output = [f32::NAN; 3];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 3, 1, 1, -1, 0, 0, 1, 1, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(output, [10.0, 20.0, -10.0]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_f32_executes_range_choice_with_exclusive_upper_bound() {
        let nodes = [
            JpeDensityNode { opcode: 2, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 10.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: -10.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: 27, input_a: 0, input_b: 3,
                reserved: 4, immediate: 0.0 , auxiliary: 1.0, tertiary: 0.0},
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 5, 0xF32_268)
        };
        assert_ne!(graph, 0);
        let mut output = [f32::NAN; 2];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 2, 1, 1, 0, 0, 0, 1, 1, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(output, [10.0, -10.0]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_axis_nodes_work_when_only_their_axis_is_prepared() {
        let cases = [
            (DENSITY_X, [-2.0_f32, -2.0, 2.0, 2.0, -2.0, -2.0, 2.0, 2.0]),
            (DENSITY_Y, [5.0_f32, 7.0, 5.0, 7.0, 5.0, 7.0, 5.0, 7.0]),
            (DENSITY_Z, [-3.0_f32, -3.0, -3.0, -3.0, 3.0, 3.0, 3.0, 3.0]),
        ];
        for (index, (opcode, expected)) in cases.into_iter().enumerate() {
            let nodes = [JpeDensityNode {
                opcode,
                input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT,
                reserved: 0,
                immediate: 0.0,
                auxiliary: 0.0,
                tertiary: 0.0,
            }];
            let graph = unsafe {
                jpe_runtime_density_graph_compile(
                    nodes.as_ptr(), 1, 0, 0xA715_0000 + index as u64,
                )
            };
            assert_ne!(graph, 0);
            let mut output = [0.0_f32; 8];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate_f32(
                    graph, 2, 2, 2, -2, 5, -3, 4, 2, 6,
                    output.as_mut_ptr(), output.len() as u64,
                )
            }, STATUS_OK);
            assert_eq!(output, expected);
            assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
        }
    }

    #[test]
    fn density_graph_f32_interpolates_corner_volume_across_exact_cell_boundary() {
        let nodes = [
            JpeDensityNode { opcode: 2, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: 3, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: 4, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 2.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 3.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_MUL, input_a: 1, input_b: 3,
                reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_MUL, input_a: 2, input_b: 4,
                reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0, input_b: 5,
                reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 7, input_b: 6,
                reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: 26, input_a: 8, input_b: 4,
                reserved: 4, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 9, 0xF32_267)
        };
        assert_ne!(graph, 0);
        let mut output = [f32::NAN; 75];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 5, 3, 5, 0, 0, 0, 1, 1, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        let mut index = 0;
        for z in 0..5 {
            for x in 0..5 {
                for y in 0..3 {
                    assert_eq!(output[index], (x + 2 * y + 3 * z) as f32);
                    index += 1;
                }
            }
        }
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_f32_interpolates_each_axis_from_cached_coordinate_maps() {
        // X/Y/Z are linear fields. Interpolation across 4-block cells must
        // reproduce each field at negative, non-boundary coordinates with
        // non-unit voxel steps, independent of the selected source axis.
        for axis in 0..3_u32 {
            let nodes = [
                JpeDensityNode {
                    opcode: DENSITY_X,
                    input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT,
                    reserved: 0,
                    immediate: 0.0,
                    auxiliary: 0.0,
                    tertiary: 0.0,
                },
                JpeDensityNode {
                    opcode: DENSITY_Y,
                    input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT,
                    reserved: 0,
                    immediate: 0.0,
                    auxiliary: 0.0,
                    tertiary: 0.0,
                },
                JpeDensityNode {
                    opcode: DENSITY_Z,
                    input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT,
                    reserved: 0,
                    immediate: 0.0,
                    auxiliary: 0.0,
                    tertiary: 0.0,
                },
                JpeDensityNode {
                    opcode: 26,
                    input_a: axis,
                    input_b: 4,
                    reserved: 4,
                    immediate: 0.0,
                    auxiliary: 0.0,
                    tertiary: 0.0,
                },
            ];
            let graph = unsafe {
                jpe_runtime_density_graph_compile(
                    nodes.as_ptr(), nodes.len() as u32, 3, 0x1A7E_3000 + axis as u64,
                )
            };
            assert_ne!(graph, 0);

            let mut output = [f32::NAN; 27];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate_f32(
                    graph, 3, 3, 3, -5, -5, -5, 2, 2, 2,
                    output.as_mut_ptr(), output.len() as u64,
                )
            }, STATUS_OK);

            let expected_axis = [-5.0_f32, -3.0_f32, -1.0_f32];
            for z in 0..3 {
                for x in 0..3 {
                    for y in 0..3 {
                        let selected = match axis {
                            0 => x,
                            1 => y,
                            _ => z,
                        };
                        let index = (z * 3 + x) * 3 + y;
                        assert_eq!(
                            output[index].to_bits(),
                            expected_axis[selected].to_bits(),
                            "interpolated axis {}, voxel {}",
                            axis,
                            index,
                        );
                    }
                }
            }
            assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
        }
    }

    #[test]
    fn density_graph_f32_executes_lerp_and_squeeze() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.25 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.5 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: 24, input_a: 0, input_b: 1,
                reserved: 2, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: 23, input_a: 3, input_b: DENSITY_NO_INPUT,
                reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4, 0xF32_265)
        };
        assert_ne!(graph, 0);
        let mut output = [f32::NAN; 2];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 2, 1, 1, 0, 0, 0, 1, 1, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        let lerped = 0.5_f32 + 0.25_f32 * (1.0_f32 - 0.5_f32);
        let expected = (lerped * 0.5_f32 - (lerped * lerped * lerped) / 24.0_f32).clamp(-1.0, 1.0);
        assert_eq!(output, [expected; 2]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_f32_executes_clamped_axis_gradient() {
        let nodes = [
            JpeDensityNode { opcode: 25, input_a: 1, input_b: 0,
                reserved: 0.0_f32.to_bits(), immediate: -64.0 , auxiliary: -40.0, tertiary: 1.0},
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 0, 0xF32_266)
        };
        assert_ne!(graph, 0);
        let mut output = [f32::NAN; 3];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 1, 3, 1, 0, -64, 0, 1, 12, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(output, [0.0, 0.5, 1.0]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_f32_executes_repeat_and_mirrored_repeat_gradients() {
        let expected = [
            (1_u32, [0.75_f32, 0.0, 0.25, 0.5, 0.75, 0.0, 0.25, 0.5, 0.75, 0.0, 0.25]),
            (2_u32, [0.25_f32, 0.0, 0.25, 0.5, 0.75, 1.0, 0.75, 0.5, 0.25, 0.0, 0.25]),
        ];
        for (tiling, values) in expected {
            let nodes = [JpeDensityNode {
                opcode: 25,
                input_a: 0, // X axis
                input_b: tiling,
                reserved: 0.0_f32.to_bits(),
                immediate: 0.0,
                auxiliary: 4.0,
                tertiary: 1.0,
            }];
            let graph = unsafe {
                jpe_runtime_density_graph_compile(
                    nodes.as_ptr(), 1, 0, 0x6AAD_0000 + tiling as u64,
                )
            };
            assert_ne!(graph, 0);
            let mut output = [f32::NAN; 11];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate_f32(
                    graph, 11, 1, 1, -1, 0, 0, 1, 1, 1,
                    output.as_mut_ptr(), output.len() as u64,
                )
            }, STATUS_OK);
            assert_eq!(output, values, "tiling mode {}", tiling);
            assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
        }
    }

    #[test]
    fn density_graph_f32_gradient_reuses_only_selected_axis_for_full_volume() {
        // This exercises the 3D Z/X/Y output layout with non-unit steps and
        // negative world coordinates. The expected value depends on only one
        // selected axis; the other dimensions must be broadcast unchanged.
        let axis_values = [
            [-1.0_f32, -0.75_f32, 0.0_f32],
            [-1.0_f32, -0.5_f32, 0.0_f32],
            [-1.0_f32, -0.25_f32, 0.75_f32],
        ];
        for axis in 0..3_u32 {
            let nodes = [JpeDensityNode {
                opcode: 25,
                input_a: axis,
                input_b: 0, // CLAMP_TO_EDGE
                reserved: (-1.0_f32).to_bits(),
                immediate: -4.0,
                auxiliary: 4.0,
                tertiary: 1.0,
            }];
            let graph = unsafe {
                jpe_runtime_density_graph_compile(
                    nodes.as_ptr(), 1, 0, 0x6AAD_2000 + axis as u64,
                )
            };
            assert_ne!(graph, 0);

            let mut output = [f32::NAN; 27];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate_f32(
                    graph, 3, 3, 3, -6, -4, -5, 3, 2, 4,
                    output.as_mut_ptr(), output.len() as u64,
                )
            }, STATUS_OK);

            let mut expected = Vec::with_capacity(27);
            for z in 0..3 {
                for x in 0..3 {
                    for y in 0..3 {
                        let selected = match axis {
                            0 => x,
                            1 => y,
                            _ => z,
                        };
                        expected.push(axis_values[axis as usize][selected]);
                    }
                }
            }
            for index in 0..output.len() {
                assert_eq!(
                    output[index].to_bits(),
                    expected[index].to_bits(),
                    "gradient axis {}, voxel {}",
                    axis,
                    index,
                );
            }
            assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
        }
    }

    #[test]
    fn density_graph_f32_gradient_matches_java_for_descending_ranges() {
        let cases = [
            (0_u32, [1.0_f32, 1.0, 0.75, 0.5, 0.25, 0.0, 0.0]),
            (1_u32, [0.25_f32, 0.0, 0.75, 0.5, 0.25, 0.0, 0.75]),
            (2_u32, [0.75_f32, 1.0, 0.75, 0.5, 0.25, 0.0, 0.25]),
        ];
        for (tiling, expected) in cases {
            let nodes = [JpeDensityNode {
                opcode: 25,
                input_a: 0, // X axis
                input_b: tiling,
                reserved: 0.0_f32.to_bits(),
                immediate: 4.0,
                auxiliary: 0.0,
                tertiary: 1.0,
            }];
            let graph = unsafe {
                jpe_runtime_density_graph_compile(
                    nodes.as_ptr(), 1, 0, 0x6AAD_1000 + tiling as u64,
                )
            };
            assert_ne!(graph, 0);
            let mut output = [f32::NAN; 7];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate_f32(
                    graph, 7, 1, 1, -1, 0, 0, 1, 1, 1,
                    output.as_mut_ptr(), output.len() as u64,
                )
            }, STATUS_OK);
            assert_eq!(output, expected, "descending gradient tiling mode {}", tiling);
            assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
        }
    }

    #[test]
    fn density_graph_f32_executes_round_with_per_voxel_multiple() {
        let nodes = [
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 5.5,
                auxiliary: 0.0, tertiary: 0.0 },
            JpeDensityNode { opcode: 25, input_a: 0, input_b: 0,
                reserved: 1.0_f32.to_bits(), immediate: 0.0,
                auxiliary: 2.0, tertiary: 2.0 },
            JpeDensityNode { opcode: 28, input_a: 0, input_b: 1,
                reserved: 0, immediate: 0.0, auxiliary: 0.0, tertiary: 0.0 },
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 2, 0xF32_271)
        };
        assert_ne!(graph, 0);
        let mut output = [f32::NAN; 3];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 3, 1, 1, 0, 0, 0, 1, 1, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert_eq!(output, [5.0, 4.5, 4.0]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_writes_reused_root_register_directly_to_output() {
        // The register allocator reuses the first slot for the final ADD after
        // two completed branches. The root slot is therefore also used by
        // earlier nodes, and must still produce the final volume correctly.
        let nodes = [
            JpeDensityNode {
                opcode: DENSITY_CONST,
                input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT,
                reserved: 0,
                immediate: 1.0,
                auxiliary: 0.0,
                tertiary: 0.0,
            },
            JpeDensityNode {
                opcode: DENSITY_ABS,
                input_a: 0,
                input_b: DENSITY_NO_INPUT,
                reserved: 0,
                immediate: 0.0,
                auxiliary: 0.0,
                tertiary: 0.0,
            },
            JpeDensityNode {
                opcode: DENSITY_CONST,
                input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT,
                reserved: 0,
                immediate: 2.0,
                auxiliary: 0.0,
                tertiary: 0.0,
            },
            JpeDensityNode {
                opcode: DENSITY_ABS,
                input_a: 2,
                input_b: DENSITY_NO_INPUT,
                reserved: 0,
                immediate: 0.0,
                auxiliary: 0.0,
                tertiary: 0.0,
            },
            JpeDensityNode {
                opcode: DENSITY_ADD,
                input_a: 1,
                input_b: 3,
                reserved: 0,
                immediate: 0.0,
                auxiliary: 0.0,
                tertiary: 0.0,
            },
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4, 0xD1EC7_0001)
        };
        assert_ne!(graph, 0);
        let mut output = [f32::NAN; 24];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate_f32(
                graph, 2, 3, 4, -3, -5, 7, 1, 2, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert!(output.iter().all(|value| *value == 3.0));
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_recycles_buffers_for_unary_binary_and_clamp_nodes() {
        let nodes = [
            // This value has no consumers and must release its slot immediately.
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 999.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 2.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: -3.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_SUB, input_a: 1, input_b: 2,
                reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_ABS, input_a: 3,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            JpeDensityNode { opcode: DENSITY_CLAMP, input_a: 4,
                input_b: 0.0_f32.to_bits(), reserved: 4.0_f32.to_bits(), immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 5, 0xD3517B)
        };
        assert_ne!(graph, 0);
        let mut output = [0.0_f64; 24];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                graph, 2, 3, 4, -3, -5, 7, 1, 2, 1,
                output.as_mut_ptr(), output.len() as u64,
            )
        }, STATUS_OK);
        assert!(output.iter().all(|value| *value == 4.0));
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_does_not_retain_unreachable_perlin_nodes() {
        // A dead Perlin node with a deliberately invalid process-local handle
        // must not prevent compiling or evaluating the reachable constant root.
        let nodes = [
            JpeDensityNode { opcode: 7, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 1.0_f32.to_bits(),
                immediate: 0xBAD_u64 as f64, auxiliary: 1.0, tertiary: 1.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 2.5,
                auxiliary: 0.0, tertiary: 0.0 },
        ];
        let graph = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 1, 0xD35182)
        };
        assert_ne!(graph, 0);
        let mut output = [0.0_f64; 1];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_evaluate(
                graph, 1, 1, 1, 0, 0, 0, 1, 1, 1,
                output.as_mut_ptr(), 1,
            )
        }, STATUS_OK);
        assert_eq!(output, [2.5]);
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
    }

    #[test]
    fn density_graph_matches_java_nan_and_signed_zero_semantics() {
        let cases: [([JpeDensityNode; 3], u32, fn(f64) -> bool); 3] = [
            ([
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: f64::NAN , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: 10, input_a: 0, input_b: 1, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            ], 2, |v| v.is_nan()),
            ([
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: -0.0 , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: 11, input_a: 0, input_b: 1, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            ], 2, |v| v == 0.0 && !v.is_sign_negative()),
            ([
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: -0.0 , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: 20, input_a: 0, input_b: DENSITY_NO_INPUT,
                    reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
                JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                    input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.0 , auxiliary: 0.0, tertiary: 0.0},
            ], 1, |v| v == 0.0 && v.is_sign_negative()),
        ];
        for (nodes, root, check) in cases {
            let graph = unsafe {
                jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, root, 0xD35180 + root as u64)
            };
            assert_ne!(graph, 0);
            let mut output = [1.0_f64; 1];
            assert_eq!(unsafe {
                jpe_runtime_density_graph_evaluate(
                    graph, 1, 1, 1, 0, 0, 0, 1, 1, 1,
                    output.as_mut_ptr(), 1,
                )
            }, STATUS_OK);
            assert!(check(output[0]), "unexpected native value: {:?}", output[0]);
            assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
        }
    }

    #[test]
    fn prepared_perlin_stack_matches_direct_stack_and_reuses_plan() {
        let permutation_a: [u8; 256] = std::array::from_fn(|i| ((i * 13 + 7) & 255) as u8);
        let permutation_b: [u8; 256] = std::array::from_fn(|i| ((i * 29 + 3) & 255) as u8);
        let source = [
            JpePerlinLayer { permutation: permutation_a.as_ptr(),
                offset_x: 0.11, offset_y: 0.27, offset_z: 0.39,
                xz_scale: 0.5, y_scale: 0.5, amplitude: 0.75 },
            JpePerlinLayer { permutation: permutation_b.as_ptr(),
                offset_x: -0.21, offset_y: 0.19, offset_z: 0.31,
                xz_scale: 1.25, y_scale: 1.25, amplitude: -0.25 },
        ];
        let handle = unsafe { jpe_runtime_prepare_perlin_stack(source.as_ptr(), source.len() as u32) };
        assert_ne!(handle, 0);
        for (xz_scale, y_scale, amplitude) in [(0.8, 1.2, 0.75_f32), (1.1, 0.7, -0.25_f32)] {
            let direct_layers = [
                JpePerlinLayer { xz_scale: xz_scale * source[0].xz_scale,
                    y_scale: y_scale * source[0].y_scale, amplitude: amplitude * source[0].amplitude,
                    ..source[0] },
                JpePerlinLayer { xz_scale: xz_scale * source[1].xz_scale,
                    y_scale: y_scale * source[1].y_scale, amplitude: amplitude * source[1].amplitude,
                    ..source[1] },
            ];
            let mut expected = [0.125_f32; 24];
            let mut actual = expected;
            assert_eq!(unsafe { jpe_runtime_perlin_add_stack(
                direct_layers.as_ptr(), 2, 2, 3, 4, -3, -5, 7, 1, 2, 1,
                expected.as_mut_ptr(), 24, 0
            ) }, STATUS_OK);
            assert_eq!(unsafe { jpe_runtime_evaluate_prepared_perlin_stack(
                handle, xz_scale, y_scale, amplitude,
                2, 3, 4, -3, -5, 7, 1, 2, 1,
                actual.as_mut_ptr(), 24, 0
            ) }, STATUS_OK);
            assert_eq!(actual.map(f32::to_bits), expected.map(f32::to_bits));
        }
        assert_eq!(jpe_runtime_release_prepared_perlin_stack(handle), STATUS_OK);
        assert_eq!(jpe_runtime_release_prepared_perlin_stack(handle), 2);
    }

    #[test]
    fn unknown_job_ids_are_rejected() { assert_eq!(jpe_runtime_job_status(u64::MAX), -1); }
}