use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex, OnceLock};
use std::thread;
use std::time::Duration;

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
        assert_eq!(size_of::<JpePerlinLayer>(), 72);
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
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4 , 8, 0x1234)
        };
        let second = unsafe {
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4 , 8, 0x1234)
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
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 3, 2 , 8, 0xCAC0_E001)
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
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 2 , 8, 0xE771)
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
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 4 , 8, 0xF32)
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
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 8 , 8, 0x71_1E)
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
                163, 8, 0x1640_1225)
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
                (nodes.len() - 1) as u32, 8, 0x1225_0600)
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
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 3, 2, 8, 0xD1_6)
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
            jpe_runtime_density_graph_compile(nodes_min.as_ptr(), 3, 2, 8, 0xA110)
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
            jpe_runtime_density_graph_compile(nodes_max.as_ptr(), 3, 2, 8, 0xA111)
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
            jpe_runtime_density_graph_compile(nodes.as_ptr(), 2, 1, 8, 0xC1A4)
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
    fn density_graph_rejects_forward_references_and_bad_volumes() {
        let invalid = [
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 1,
                input_b: 0, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 },
        ];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_compile(invalid.as_ptr(), 2, 0 , 8, 7)
        }, 0);

        let valid = [JpeDensityNode { opcode: DENSITY_CONST,
            input_a: DENSITY_NO_INPUT, input_b: DENSITY_NO_INPUT,
            reserved: 0, immediate: 1.0 }];
        let handle = unsafe {
            jpe_runtime_density_graph_compile(valid.as_ptr(), 1, 0 , 8, 7)
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
            },
            JpePerlinLayer { permutation: permutation_b.as_ptr(),
                offset_x: -0.31, offset_y: 0.17, offset_z: 0.23,
                xz_scale: 1.5, y_scale: 0.625, amplitude: -0.125,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
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
            },
            JpePerlinLayer { permutation: permutation_b.as_ptr(),
                offset_x: -0.31, offset_y: 0.17, offset_z: 0.23,
                xz_scale: 1.5, y_scale: 0.625, amplitude: -0.125,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
            },
            JpePerlinLayer { permutation: permutation_c.as_ptr(),
                offset_x: 0.07, offset_y: -0.33, offset_z: 0.19,
                xz_scale: 0.375, y_scale: 2.0, amplitude: 0.0625,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
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
            },
            JpePerlinLayer { permutation: permutation_b.as_ptr(),
                offset_x: -0.21, offset_y: 0.19, offset_z: 0.31,
                xz_scale: 1.1, y_scale: 0.7, amplitude: -0.25,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
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
            },
            JpePerlinLayer { permutation: std::ptr::null(),
                offset_x: 0.0, offset_y: 0.0, offset_z: 0.0,
                xz_scale: 1.0, y_scale: 1.0, amplitude: 1.0,
                fudge_y_scale: 0.0, flags: 0, reserved: 0,
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
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 2 , 8, 0x5AB)
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
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 2 , 8, 0x1A9)
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
            jpe_runtime_density_graph_compile(nodes.as_ptr(), nodes.len() as u32, 2, 8, 0xA117)
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

}
