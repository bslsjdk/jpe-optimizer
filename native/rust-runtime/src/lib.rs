use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Mutex, OnceLock};
use std::thread;
use std::time::Duration;

const ABI_VERSION: u32 = 2;
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

unsafe extern "C" {
    fn jpe_cpp_add_f32(input: *const f32, output: *mut f32, length: u32) -> i32;
    fn jpe_cpp_perlin_add_volume(
        permutation: *const u8, block_x: *const i32, block_y: *const i32, block_z: *const i32,
        size_x: u32, size_y: u32, size_z: u32,
        offset_x: f64, offset_y: f64, offset_z: f64,
        xz_scale: f64, y_scale: f64, amplitude: f32,
        output: *mut f32, output_length: u64, flags: u32,
    ) -> i32;
}

struct JobState {
    cancelled: AtomicBool,
    status: Mutex<JobStatus>,
}

enum JobStatus { Pending, Running, Complete(Vec<f32>), Failed(i32), Cancelled }

struct PerlinTask {
    state: Arc<JobState>,
    permutation: Vec<u8>,
    block_x: Vec<i32>,
    block_y: Vec<i32>,
    block_z: Vec<i32>,
    output: Vec<f32>,
    size_x: u32, size_y: u32, size_z: u32,
    offset_x: f64, offset_y: f64, offset_z: f64,
    xz_scale: f64, y_scale: f64, amplitude: f32, flags: u32,
}

struct Scheduler {
    sender: mpsc::SyncSender<PerlinTask>,
    jobs: Mutex<HashMap<u64, Arc<JobState>>>,
    next_id: AtomicU64,
}

static SCHEDULER: OnceLock<Scheduler> = OnceLock::new();

fn scheduler() -> &'static Scheduler {
    SCHEDULER.get_or_init(|| {
        let (sender, receiver) = mpsc::sync_channel::<PerlinTask>(QUEUE_CAPACITY);
        let receiver = Arc::new(Mutex::new(receiver));
        let workers = thread::available_parallelism().map(|n| n.get()).unwrap_or(1).clamp(1, 8);
        for worker_index in 0..workers {
            let receiver = Arc::clone(&receiver);
            thread::Builder::new().name(format!("jpe-native-worldgen-{worker_index}"))
                .spawn(move || loop {
                    let task = {
                        let guard = receiver.lock().unwrap_or_else(|p| p.into_inner());
                        guard.recv()
                    };
                    let Ok(mut task) = task else { break };
                    if task.state.cancelled.load(Ordering::Acquire) {
                        *lock_status(&task.state) = JobStatus::Cancelled;
                        continue;
                    }
                    *lock_status(&task.state) = JobStatus::Running;
                    let result = catch_unwind(AssertUnwindSafe(|| unsafe {
                        jpe_cpp_perlin_add_volume(
                            task.permutation.as_ptr(),
                            task.block_x.as_ptr(), task.block_y.as_ptr(), task.block_z.as_ptr(),
                            task.size_x, task.size_y, task.size_z,
                            task.offset_x, task.offset_y, task.offset_z,
                            task.xz_scale, task.y_scale, task.amplitude,
                            task.output.as_mut_ptr(), task.output.len() as u64, task.flags,
                        )
                    }));
                    let mut status = lock_status(&task.state);
                    if task.state.cancelled.load(Ordering::Acquire) {
                        *status = JobStatus::Cancelled;
                    } else {
                        *status = match result {
                            Ok(STATUS_OK) => JobStatus::Complete(task.output),
                            Ok(code) => JobStatus::Failed(code),
                            Err(_) => JobStatus::Failed(STATUS_RUST_PANIC),
                        };
                    }
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
    permutation: *const u8, block_x: *const i32, block_y: *const i32, block_z: *const i32,
    size_x: u32, size_y: u32, size_z: u32,
    offset_x: f64, offset_y: f64, offset_z: f64,
    xz_scale: f64, y_scale: f64, amplitude: f32,
    output: *mut f32, output_length: u64, flags: u32,
) -> i32 {
    if size_x == 0 || size_y == 0 || size_z == 0 || permutation.is_null()
        || block_x.is_null() || block_y.is_null() || block_z.is_null() || output.is_null() {
        return STATUS_INVALID_ARGUMENT;
    }
    let expected = match (size_x as u64).checked_mul(size_y as u64).and_then(|n| n.checked_mul(size_z as u64)) {
        Some(v) => v, None => return STATUS_INVALID_ARGUMENT,
    };
    if expected != output_length || expected as u128 > MAX_VOLUME_ELEMENTS as u128 { return STATUS_INVALID_ARGUMENT; }
    match catch_unwind(AssertUnwindSafe(|| unsafe {
        jpe_cpp_perlin_add_volume(permutation, block_x, block_y, block_z, size_x, size_y, size_z,
            offset_x, offset_y, offset_z, xz_scale, y_scale, amplitude, output, output_length, flags)
    })) {
        Ok(STATUS_OK) => STATUS_OK,
        Ok(STATUS_INVALID_ARGUMENT) => STATUS_INVALID_ARGUMENT,
        Ok(_) => STATUS_CPP_FAILURE,
        Err(_) => STATUS_RUST_PANIC,
    }
}

/// Copies all inputs into Rust-owned memory before returning. Workers never retain caller pointers.
#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_submit_perlin_volume(
    permutation: *const u8, block_x: *const i32, block_y: *const i32, block_z: *const i32,
    size_x: u32, size_y: u32, size_z: u32,
    offset_x: f64, offset_y: f64, offset_z: f64,
    xz_scale: f64, y_scale: f64, amplitude: f32,
    initial_output: *const f32, output_length: u64, flags: u32,
) -> u64 {
    catch_unwind(AssertUnwindSafe(|| {
        if size_x == 0 || size_y == 0 || size_z == 0 || permutation.is_null()
            || block_x.is_null() || block_y.is_null() || block_z.is_null() || initial_output.is_null() {
            return 0;
        }
        let expected = match (size_x as u64).checked_mul(size_y as u64).and_then(|n| n.checked_mul(size_z as u64)) {
            Some(v) => v, None => return 0,
        };
        if expected != output_length || expected as u128 > MAX_VOLUME_ELEMENTS as u128 { return 0; }
        let task = unsafe {
            PerlinTask {
                state: Arc::new(JobState { cancelled: AtomicBool::new(false), status: Mutex::new(JobStatus::Pending) }),
                permutation: slice::from_raw_parts(permutation, 256).to_vec(),
                block_x: slice::from_raw_parts(block_x, size_x as usize).to_vec(),
                block_y: slice::from_raw_parts(block_y, size_y as usize).to_vec(),
                block_z: slice::from_raw_parts(block_z, size_z as usize).to_vec(),
                output: slice::from_raw_parts(initial_output, output_length as usize).to_vec(),
                size_x, size_y, size_z, offset_x, offset_y, offset_z, xz_scale, y_scale, amplitude, flags,
            }
        };
        let runtime = scheduler();
        let id = runtime.next_id.fetch_add(1, Ordering::Relaxed);
        if id == 0 { return 0; }
        let mut jobs = runtime.jobs.lock().unwrap_or_else(|p| p.into_inner());
        if jobs.len() >= MAX_TRACKED_JOBS { return 0; }
        jobs.insert(id, Arc::clone(&task.state));
        if runtime.sender.try_send(task).is_err() {
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
    match &*lock_status(&state) {
        JobStatus::Pending => JOB_PENDING,
        JobStatus::Running => JOB_RUNNING,
        JobStatus::Complete(_) => JOB_COMPLETE,
        JobStatus::Failed(_) => JOB_FAILED,
        JobStatus::Cancelled => JOB_CANCELLED,
    }
}

#[no_mangle]
pub unsafe extern "C" fn jpe_runtime_copy_job_output(job_id: u64, destination: *mut f32, capacity: u64) -> i32 {
    if destination.is_null() { return STATUS_INVALID_ARGUMENT; }
    let state = {
        let jobs = scheduler().jobs.lock().unwrap_or_else(|p| p.into_inner());
        let Some(state) = jobs.get(&job_id) else { return STATUS_INVALID_ARGUMENT };
        Arc::clone(state)
    };
    match &*lock_status(&state) {
        JobStatus::Complete(output) => {
            if capacity < output.len() as u64 { return STATUS_INVALID_ARGUMENT; }
            unsafe { slice::from_raw_parts_mut(destination, output.len()).copy_from_slice(output); }
            STATUS_OK
        }
        JobStatus::Pending | JobStatus::Running => JOB_PENDING,
        JobStatus::Failed(code) => *code,
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
    state.cancelled.store(true, Ordering::Release);
    let mut status = lock_status(&state);
    if matches!(*status, JobStatus::Pending) { *status = JobStatus::Cancelled; }
    STATUS_OK
}

#[no_mangle]
pub extern "C" fn jpe_runtime_release_job(job_id: u64) -> i32 {
    let mut jobs = scheduler().jobs.lock().unwrap_or_else(|p| p.into_inner());
    let Some(state) = jobs.get(&job_id) else { return STATUS_INVALID_ARGUMENT };
    let terminal = matches!(*lock_status(state), JobStatus::Complete(_) | JobStatus::Failed(_) | JobStatus::Cancelled);
    if !terminal { return JOB_RUNNING; }
    jobs.remove(&job_id);
    STATUS_OK
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exports_expected_abi_version() { assert_eq!(jpe_runtime_abi_version(), 2); }

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
        let permutation = [0_u8; 256]; let axis = [0_i32]; let mut output = [0.0_f32; 2];
        let status = unsafe { jpe_cpp_perlin_add_volume(permutation.as_ptr(), axis.as_ptr(), axis.as_ptr(), axis.as_ptr(),
            1, 1, 1, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, output.as_mut_ptr(), 2, 0) };
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
    fn async_perlin_job_returns_owned_result() {
        let permutation = [0_u8; 256]; let axis = [0_i32]; let initial = [0.0_f32];
        let id = unsafe { jpe_runtime_submit_perlin_volume(permutation.as_ptr(), axis.as_ptr(), axis.as_ptr(),
            axis.as_ptr(), 1, 1, 1, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, initial.as_ptr(), 1, 0) };
        assert_ne!(id, 0);
        for _ in 0..100 {
            let status = jpe_runtime_job_status(id);
            if status == JOB_COMPLETE {
                let mut output = [99.0_f32];
                assert_eq!(unsafe { jpe_runtime_copy_job_output(id, output.as_mut_ptr(), 1) }, STATUS_OK);
                assert_eq!(output[0], 0.0);
                assert_eq!(jpe_runtime_release_job(id), STATUS_OK);
                return;
            }
            assert!(status == JOB_PENDING || status == JOB_RUNNING);
            thread::sleep(Duration::from_millis(2));
        }
        let _ = jpe_runtime_release_job(id);
        panic!("native Perlin job did not complete");
    }

    #[test]
    fn unknown_job_ids_are_rejected() { assert_eq!(jpe_runtime_job_status(u64::MAX), -1); }
}
