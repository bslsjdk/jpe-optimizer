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

struct PerlinStackLayer {
    permutation: Vec<u8>,
    offset_x: f64, offset_y: f64, offset_z: f64,
    xz_scale: f64, y_scale: f64, amplitude: f32,
}

struct PerlinStackTask {
    state: Arc<JobState>,
    layers: Vec<PerlinStackLayer>,
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
                                let descriptors: Vec<JpePerlinLayer> = task.layers.iter().map(|layer| JpePerlinLayer {
                                    permutation: layer.permutation.as_ptr(),
                                    offset_x: layer.offset_x, offset_y: layer.offset_y, offset_z: layer.offset_z,
                                    xz_scale: layer.xz_scale, y_scale: layer.y_scale, amplitude: layer.amplitude,
                                }).collect();
                                jpe_cpp_perlin_add_stack(
                                    descriptors.as_ptr(), descriptors.len() as u32,
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

/// Submits an ordered Perlin stack. Permutations are copied into Rust-owned task memory;
/// output_buffer is borrowed until the job reaches a terminal status and is released.
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
        let mut owned_layers = Vec::with_capacity(layer_count as usize);
        for layer in source {
            if layer.permutation.is_null() { return 0; }
            let permutation = unsafe { slice::from_raw_parts(layer.permutation, 256) }.to_vec();
            owned_layers.push(PerlinStackLayer {
                permutation,
                offset_x: layer.offset_x, offset_y: layer.offset_y, offset_z: layer.offset_z,
                xz_scale: layer.xz_scale, y_scale: layer.y_scale, amplitude: layer.amplitude,
            });
        }
        let task = PerlinStackTask {
            state: Arc::new(JobState {
                cancelled: AtomicBool::new(false),
                status: Mutex::new(JobStatus::Pending),
                changed: Condvar::new(),
            }),
            layers: owned_layers,
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
    fn unknown_job_ids_are_rejected() { assert_eq!(jpe_runtime_job_status(u64::MAX), -1); }
}
