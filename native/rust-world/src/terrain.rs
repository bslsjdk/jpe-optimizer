//! Terrain engine boundary.
//!
//! The C++ side already owns terrain math (Perlin/noise/density graph/blender/
//! aquifer) behind ABI v8 in `native/include/jpe_native_api.h`. This module
//! declares that boundary without changing it, and provides a mock so the world
//! engine can be tested with no native library present.
//!
//! Data flow this represents: world scheduler -> terrain request -> terrain
//! data -> chunk management -> mesh build -> renderer. No terrain math is
//! reimplemented here.

/// Must match `jpe_runtime_abi_version()` in the C++ side.
pub const JPE_ABI_VERSION: u32 = 8;

/// Mirrors `JpePerlinLayer` from `native/include/jpe_native_api.h` exactly.
/// Field order and padding are part of the ABI; do not reorder.
#[derive(Copy, Clone, Debug)]
#[repr(C)]
pub struct JpePerlinLayer {
    /// Borrowed 256-byte permutation table, valid for the duration of the call.
    pub permutation: *const u8,
    pub offset_x: f64,
    pub offset_y: f64,
    pub offset_z: f64,
    pub xz_scale: f64,
    pub y_scale: f64,
    pub amplitude: f32,
    pub fudge_y_scale: f64,
    /// bit 0: SmearedPerlinNoise layer.
    pub flags: u32,
    pub reserved: u32,
}

/// Owned description of one noise layer, used to build [`JpePerlinLayer`]s.
#[derive(Clone, Debug)]
pub struct PerlinLayerSpec {
    pub permutation: [u8; 256],
    pub offset_x: f64,
    pub offset_y: f64,
    pub offset_z: f64,
    pub xz_scale: f64,
    pub y_scale: f64,
    pub amplitude: f32,
    pub fudge_y_scale: f64,
    pub smeared: bool,
}

#[cfg(feature = "cpp-bridge")]
impl PerlinLayerSpec {
    fn as_ffi(&self) -> JpePerlinLayer {
        JpePerlinLayer {
            permutation: self.permutation.as_ptr(),
            offset_x: self.offset_x,
            offset_y: self.offset_y,
            offset_z: self.offset_z,
            xz_scale: self.xz_scale,
            y_scale: self.y_scale,
            amplitude: self.amplitude,
            fudge_y_scale: self.fudge_y_scale,
            flags: u32::from(self.smeared),
            reserved: 0,
        }
    }
}

/// Volume request using Minecraft's `min + index * step` convention.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct VolumeDesc {
    pub size: [u32; 3],
    pub min: [i32; 3],
    pub step: [i32; 3],
}

impl VolumeDesc {
    /// Output order is `((z * size_x + x) * size_y + y)` (ABI v8).
    pub fn element_count(&self) -> Option<usize> {
        let xy = (self.size[0] as usize).checked_mul(self.size[1] as usize)?;
        xy.checked_mul(self.size[2] as usize)
    }

    pub fn validate(&self) -> Result<(), TerrainError> {
        if self.size.iter().any(|&s| s == 0) {
            return Err(TerrainError::InvalidVolume("zero size"));
        }
        if self.step.iter().any(|&s| s == 0) {
            return Err(TerrainError::InvalidVolume("zero step"));
        }
        if self.element_count().is_none() {
            return Err(TerrainError::InvalidVolume("size overflow"));
        }
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct DensityVolume {
    pub desc: VolumeDesc,
    pub data: Vec<f32>,
}

impl DensityVolume {
    pub fn element_count(&self) -> usize {
        self.data.len()
    }
}

#[derive(Copy, Clone, Eq, PartialEq, Debug)]
pub enum TerrainError {
    InvalidVolume(&'static str),
    WorkerConfig(i32),
    Kernel(i32),
}

pub trait TerrainEngine {
    fn abi_version(&self) -> u32;
    /// Configure the native worker count. Returns the active count.
    fn configure_workers(&mut self, requested: u32) -> Result<u32, TerrainError>;
    /// Evaluate one complete ordered Perlin layer stack into a volume.
    fn perlin_stack_volume(
        &mut self,
        layers: &[PerlinLayerSpec],
        desc: &VolumeDesc,
    ) -> Result<DensityVolume, TerrainError>;
}

/// Deterministic stand-in used in unit tests and on machines without the native
/// library. Not a terrain implementation: values are synthetic.
#[derive(Default, Debug)]
pub struct MockTerrainEngine {
    pub worker_count: u32,
    pub calls: u64,
}

impl TerrainEngine for MockTerrainEngine {
    fn abi_version(&self) -> u32 {
        JPE_ABI_VERSION
    }

    fn configure_workers(&mut self, requested: u32) -> Result<u32, TerrainError> {
        let active = if requested == 0 { 1 } else { requested };
        self.worker_count = active;
        Ok(active)
    }

    fn perlin_stack_volume(
        &mut self,
        layers: &[PerlinLayerSpec],
        desc: &VolumeDesc,
    ) -> Result<DensityVolume, TerrainError> {
        desc.validate()?;
        self.calls += 1;
        let count = desc.element_count().expect("validated");
        let scale = layers.len() as f32;
        let mut data = Vec::with_capacity(count);
        for z in 0..desc.size[2] {
            for x in 0..desc.size[0] {
                for y in 0..desc.size[1] {
                    let value = (x as f32) + (y as f32) * 0.5 + (z as f32) * 0.25 + scale;
                    data.push(value);
                }
            }
        }
        Ok(DensityVolume {
            desc: *desc,
            data,
        })
    }
}

#[cfg(feature = "cpp-bridge")]
mod native {
    use super::JpePerlinLayer;

    extern "C" {
        pub fn jpe_runtime_abi_version() -> u32;
        pub fn jpe_runtime_configure_workers(requested: u32) -> i32;
        #[allow(clippy::too_many_arguments)]
        pub fn jpe_runtime_perlin_add_stack(
            layers: *const JpePerlinLayer,
            layer_count: u32,
            size_x: u32,
            size_y: u32,
            size_z: u32,
            min_x: i32,
            min_y: i32,
            min_z: i32,
            step_x: i32,
            step_y: i32,
            step_z: i32,
            output: *mut f32,
            output_length: u64,
            flags: u32,
        ) -> i32;
    }
}

/// Real backend that links the existing C++ kernels. Enabled with the
/// `cpp-bridge` feature. It does not modify the C++ ABI.
#[cfg(feature = "cpp-bridge")]
#[derive(Default, Debug)]
pub struct NativeTerrainEngine;

#[cfg(feature = "cpp-bridge")]
impl TerrainEngine for NativeTerrainEngine {
    fn abi_version(&self) -> u32 {
        unsafe { native::jpe_runtime_abi_version() }
    }

    fn configure_workers(&mut self, requested: u32) -> Result<u32, TerrainError> {
        let active = unsafe { native::jpe_runtime_configure_workers(requested) };
        if active < 0 {
            Err(TerrainError::WorkerConfig(active))
        } else {
            Ok(active as u32)
        }
    }

    fn perlin_stack_volume(
        &mut self,
        layers: &[PerlinLayerSpec],
        desc: &VolumeDesc,
    ) -> Result<DensityVolume, TerrainError> {
        desc.validate()?;
        let ffi_layers: Vec<JpePerlinLayer> = layers.iter().map(|l| l.as_ffi()).collect();
        let mut data = vec![0f32; desc.element_count().expect("validated")];
        let rc = unsafe {
            native::jpe_runtime_perlin_add_stack(
                ffi_layers.as_ptr(),
                ffi_layers.len() as u32,
                desc.size[0],
                desc.size[1],
                desc.size[2],
                desc.min[0],
                desc.min[1],
                desc.min[2],
                desc.step[0],
                desc.step[1],
                desc.step[2],
                data.as_mut_ptr(),
                data.len() as u64,
                0,
            )
        };
        if rc != 0 {
            return Err(TerrainError::Kernel(rc));
        }
        Ok(DensityVolume { desc: *desc, data })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abi_version_constant_is_v8() {
        assert_eq!(JPE_ABI_VERSION, 8);
    }

    #[test]
    fn ffi_layer_layout_matches_c_header() {
        // permutation(8) + 5 doubles(40) + amplitude(4) + pad(4)
        // + fudge(8) + flags(4) + reserved(4) = 72 bytes.
        assert_eq!(std::mem::size_of::<JpePerlinLayer>(), 72);
        assert_eq!(std::mem::align_of::<JpePerlinLayer>(), 8);
    }

    #[test]
    fn volume_element_count_matches_abi_order() {
        let desc = VolumeDesc {
            size: [16, 384, 16],
            min: [0, -64, 0],
            step: [1, 1, 1],
        };
        assert_eq!(desc.element_count(), Some(16 * 384 * 16));
        assert!(desc.validate().is_ok());
    }

    #[test]
    fn invalid_volume_is_rejected() {
        let zero = VolumeDesc {
            size: [0, 1, 1],
            min: [0, 0, 0],
            step: [1, 1, 1],
        };
        assert_eq!(
            zero.validate(),
            Err(TerrainError::InvalidVolume("zero size"))
        );
        let bad_step = VolumeDesc {
            size: [1, 1, 1],
            min: [0, 0, 0],
            step: [1, 0, 1],
        };
        assert_eq!(
            bad_step.validate(),
            Err(TerrainError::InvalidVolume("zero step"))
        );
    }

    #[test]
    fn mock_engine_returns_full_volume_in_abi_order() {
        let mut engine = MockTerrainEngine::default();
        let desc = VolumeDesc {
            size: [2, 3, 4],
            min: [0, 0, 0],
            step: [1, 1, 1],
        };
        let layer = PerlinLayerSpec {
            permutation: [0u8; 256],
            offset_x: 0.0,
            offset_y: 0.0,
            offset_z: 0.0,
            xz_scale: 1.0,
            y_scale: 1.0,
            amplitude: 1.0,
            fudge_y_scale: 1.0,
            smeared: false,
        };
        let vol = engine.perlin_stack_volume(&[layer], &desc).unwrap();
        assert_eq!(vol.element_count(), 2 * 3 * 4);
        // First element is (x=0, y=0, z=0) plus the layer scale.
        assert_eq!(vol.data[0], 1.0);
        assert_eq!(engine.calls, 1);
    }
}
