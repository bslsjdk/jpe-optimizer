use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{mpsc, Arc, Condvar, Mutex, OnceLock};
use std::thread;
use std::time::Duration;

const ABI_VERSION: u32 = 6;
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
}

const DENSITY_NO_INPUT: u32 = u32::MAX;
const DENSITY_CONST: u32 = 1;
const DENSITY_X: u32 = 2;
const DENSITY_Y: u32 = 3;
const DENSITY_Z: u32 = 4;
const DENSITY_ADD: u32 = 5;
const DENSITY_MUL: u32 = 6;

unsafe extern "C" {
    fn jpe_runtime_density_graph_compile(
        nodes: *const JpeDensityNode, node_count: u32, root_node: u32,
        configuration_fingerprint: u64,
    ) -> u64;
    fn jpe_runtime_density_graph_evaluate(
        graph_handle: u64,
        size_x: u32, size_y: u32, size_z: u32,
        min_x: i32, min_y: i32, min_z: i32,
        step_x: i32, step_y: i32, step_z: i32,
        output: *mut f64, output_length: u64,
    ) -> i32;
    fn jpe_runtime_density_graph_release(graph_handle: u64) -> i32;
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
    fn density_graph_rejects_forward_references_and_bad_volumes() {
        let invalid = [
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 1,
                input_b: 0, reserved: 0, immediate: 0.0 },
            JpeDensityNode { opcode: DENSITY_CONST, input_a: DENSITY_NO_INPUT,
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 1.0 },
        ];
        assert_eq!(unsafe {
            jpe_runtime_density_graph_compile(invalid.as_ptr(), 2, 0, 7)
        }, 0);

        let valid = [JpeDensityNode { opcode: DENSITY_CONST,
            input_a: DENSITY_NO_INPUT, input_b: DENSITY_NO_INPUT,
            reserved: 0, immediate: 1.0 }];
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
    fn exports_expected_abi_version() { assert_eq!(jpe_runtime_abi_version(), 6); }

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
        let mut expected = [0.0_f32; 24];
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
                input_b: DENSITY_NO_INPUT, reserved: 0, immediate: 0.125 },
            JpeDensityNode { opcode: 7, input_a: 1.0_f32.to_bits(),
                input_b: 1.0_f32.to_bits(), reserved: 1.0_f32.to_bits(),
                immediate: stack as f64 },
            JpeDensityNode { opcode: DENSITY_ADD, input_a: 0, input_b: 1,
                reserved: 0, immediate: 0.0 },
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

        let mut expected = [0.0_f32; 24];
        let direct_layer = JpePerlinLayer { ..layer };
        assert_eq!(unsafe {
            jpe_runtime_perlin_add_stack(
                &direct_layer, 1, 2, 3, 4, -3, -5, 7, 1, 2, 1,
                expected.as_mut_ptr(), expected.len() as u64, 0,
            )
        }, STATUS_OK);
        for (actual, expected) in actual.iter().zip(expected.iter()) {
            assert_eq!(*actual, (*expected as f64) + 0.125);
        }
        assert_eq!(unsafe { jpe_runtime_density_graph_release(graph) }, STATUS_OK);
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