//! Vulkan native backend — direct `libvulkan` bindings, no wrapper crate.
//!
//! This is a real, offscreen-capable Vulkan path: it creates an `VkInstance`,
//! picks a physical device and graphics queue, creates a logical device, uploads
//! chunk meshes into host-visible vertex/index buffers, runs a graphics pipeline
//! (SPIR-V compiled offline and embedded here) into an offscreen colour image,
//! copies that image back and exposes the pixels through
//! [`Renderer::read_pixels`]. On a headless machine it runs against Mesa's
//! lavapipe (software) ICD.
//!
//! The production path will reuse Minecraft's window/surface by creating the
//! device with the extensions and `VkSurfaceKHR` the game already owns; that
//! entry point is added with the Minecraft integration. The offscreen path here
//! proves the native call chain and the mesh/pipeline/readback contract.
//!
//! Honest status: instance/device/queue/memory, mesh upload, a real graphics
//! pipeline and colour readback are implemented and tested. Swapchain
//! presentation, textures, depth and descriptor sets are not implemented yet.

use super::{Backend, Capabilities, DrawBatch, FramePixels, MeshHandle, RenderError, Renderer};
use crate::mesh::Vertex;
use std::ffi::{c_char, c_int, c_void};
use std::mem::{size_of, transmute, zeroed};
use std::ptr::{null, null_mut};

pub const BACKEND: Backend = Backend::Vulkan;

const VK_SUCCESS: i32 = 0;

// SPIR-V compiled from src/render/shaders/*.glsl with `glslangValidator -V`.
const VERT_SPV: &[u8] = include_bytes!("shaders/chunk.vert.spv");
const FRAG_SPV: &[u8] = include_bytes!("shaders/chunk.frag.spv");

// --- constants ------------------------------------------------------------
const STRUCTURE_TYPE_APPLICATION_INFO: u32 = 0;
const STRUCTURE_TYPE_INSTANCE_CREATE_INFO: u32 = 1;
const STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO: u32 = 2;
const STRUCTURE_TYPE_DEVICE_CREATE_INFO: u32 = 3;
const STRUCTURE_TYPE_SUBMIT_INFO: u32 = 4;
const STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO: u32 = 5;
const STRUCTURE_TYPE_FENCE_CREATE_INFO: u32 = 8;
const STRUCTURE_TYPE_BUFFER_CREATE_INFO: u32 = 12;
const STRUCTURE_TYPE_IMAGE_CREATE_INFO: u32 = 14;
const STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO: u32 = 15;
const STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO: u32 = 16;
const STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO: u32 = 18;
const STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO: u32 = 19;
const STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO: u32 = 20;
const STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO: u32 = 22;
const STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO: u32 = 23;
const STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO: u32 = 24;
const STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO: u32 = 26;
const STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO: u32 = 27;
const STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO: u32 = 28;
const STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO: u32 = 30;
const STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO: u32 = 37;
const STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO: u32 = 38;
const STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO: u32 = 39;
const STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO: u32 = 40;
const STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO: u32 = 42;
const STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO: u32 = 43;
const STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER: u32 = 45;

const QUEUE_GRAPHICS_BIT: u32 = 0x0000_0001;
const QUEUE_COMPUTE_BIT: u32 = 0x0000_0002;

const FORMAT_R8G8B8A8_UNORM: u32 = 37;
const FORMAT_R32G32B32_SFLOAT: u32 = 106;

const IMAGE_TILING_OPTIMAL: u32 = 0;
const IMAGE_TYPE_2D: u32 = 1;
const IMAGE_VIEW_TYPE_2D: u32 = 1;
const COMPONENT_SWIZZLE_IDENTITY: u32 = 0;

const IMAGE_USAGE_TRANSFER_SRC: u32 = 0x0000_0001;
const IMAGE_USAGE_COLOR_ATTACHMENT: u32 = 0x0000_0010;
const BUFFER_USAGE_TRANSFER_SRC: u32 = 0x0000_0001;
const BUFFER_USAGE_VERTEX: u32 = 0x0000_0040;
const BUFFER_USAGE_INDEX: u32 = 0x0000_0080;

const LAYOUT_UNDEFINED: u32 = 0;
const LAYOUT_TRANSFER_SRC_OPTIMAL: u32 = 6;

const ATTACHMENT_LOAD_OP_CLEAR: u32 = 1;
const ATTACHMENT_STORE_OP_STORE: u32 = 0;
const SHARING_MODE_EXCLUSIVE: u32 = 0;

const PIPELINE_BIND_POINT_GRAPHICS: u32 = 0;
const PRIMITIVE_TRIANGLE_LIST: u32 = 3;
const POLYGON_MODE_FILL: u32 = 0;
const CULL_MODE_NONE: u32 = 0;
const FRONT_FACE_COUNTER_CLOCKWISE: u32 = 0;
const SAMPLE_COUNT_1: u32 = 1;
const BLEND_FACTOR_ONE: u32 = 1;
const BLEND_FACTOR_ZERO: u32 = 0;
const BLEND_OP_ADD: u32 = 0;
const COLOR_COMPONENT_RGBA: u32 = 0xF;

const SHADER_STAGE_VERTEX: u32 = 0x0000_0001;
const SHADER_STAGE_FRAGMENT: u32 = 0x0000_0010;
const DYNAMIC_STATE_VIEWPORT: u32 = 0;
const DYNAMIC_STATE_SCISSOR: u32 = 1;

const STAGE_COLOR_ATTACHMENT_OUTPUT: u32 = 0x0000_0400;
const STAGE_TRANSFER: u32 = 0x0000_1000;
const ACCESS_COLOR_ATTACHMENT_WRITE: u32 = 0x0000_0100;
const ACCESS_TRANSFER_READ: u32 = 0x0000_0800;

const MEMORY_PROPERTY_DEVICE_LOCAL: u32 = 0x0000_0001;
const MEMORY_PROPERTY_HOST_VISIBLE: u32 = 0x0000_0002;
const MEMORY_PROPERTY_HOST_COHERENT: u32 = 0x0000_0004;

const IMAGE_ASPECT_COLOR: u32 = 0x1;
const INDEX_TYPE_UINT32: u32 = 1;
const COMMAND_BUFFER_LEVEL_PRIMARY: u32 = 0;
const COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT: u32 = 0x1;
const SUBPASS_EXTERNAL: u32 = 0xFFFF_FFFF;
const PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT: u32 = 0x0000_0400;
const SUBPASS_CONTENTS_INLINE: u32 = 0;

// --- FFI types ------------------------------------------------------------
type Instance = *mut c_void;
type PhysicalDevice = *mut c_void;
type Device = *mut c_void;
type Queue = *mut c_void;
type CommandBuffer = *mut c_void;
type CommandPool = *mut c_void;

#[repr(C)]
struct Extent2D { width: u32, height: u32 }
#[repr(C)]
#[derive(Copy, Clone)]
struct Extent3D { width: u32, height: u32, depth: u32 }
#[repr(C)]
struct Offset2D { x: i32, y: i32 }
#[repr(C)]
struct Offset3D { x: i32, y: i32, z: i32 }
#[repr(C)]
struct Rect2D { offset: Offset2D, extent: Extent2D }
#[repr(C)]
struct Viewport { x: f32, y: f32, width: f32, height: f32, min_depth: f32, max_depth: f32 }

#[repr(C)]
struct ApplicationInfo {
    s_type: u32,
    p_next: *const c_void,
    p_app_name: *const c_char,
    app_version: u32,
    p_engine_name: *const c_char,
    engine_version: u32,
    api_version: u32,
}
#[repr(C)]
struct InstanceCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    p_app_info: *const ApplicationInfo,
    enabled_layer_count: u32,
    pp_enabled_layer_names: *const *const c_char,
    enabled_extension_count: u32,
    pp_enabled_extension_names: *const *const c_char,
}

#[repr(C)]
#[derive(Copy, Clone)]
struct QueueFamilyProperties {
    queue_flags: u32,
    queue_count: u32,
    timestamp_valid_bits: u32,
    min_image_transfer_granularity: Extent3D,
}
#[repr(C)]
struct DeviceQueueCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    queue_family_index: u32,
    queue_count: u32,
    p_queue_priorities: *const f32,
}
#[repr(C)]
struct DeviceCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    queue_create_info_count: u32,
    p_queue_create_infos: *const DeviceQueueCreateInfo,
    enabled_layer_count: u32,
    pp_enabled_layer_names: *const *const c_char,
    enabled_extension_count: u32,
    pp_enabled_extension_names: *const *const c_char,
    p_enabled_features: *const c_void,
}

#[repr(C)]
struct MemoryRequirements { size: u64, alignment: u64, memory_type_bits: u32 }
#[repr(C)]
#[derive(Copy, Clone)]
struct MemoryType { property_flags: u32, heap_index: u32 }
#[repr(C)]
#[derive(Copy, Clone)]
struct MemoryHeap { size: u64, flags: u32 }
#[repr(C)]
#[derive(Copy, Clone)]
struct PhysicalDeviceMemoryProperties {
    memory_type_count: u32,
    memory_types: [MemoryType; 32],
    memory_heap_count: u32,
    memory_heaps: [MemoryHeap; 16],
}
#[repr(C)]
struct MemoryAllocateInfo {
    s_type: u32,
    p_next: *const c_void,
    allocation_size: u64,
    memory_type_index: u32,
}

#[repr(C)]
struct BufferCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    size: u64,
    usage: u32,
    sharing_mode: u32,
    queue_family_index_count: u32,
    p_queue_family_indices: *const u32,
}

#[repr(C)]
struct ImageCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    image_type: u32,
    format: u32,
    extent: Extent3D,
    mip_levels: u32,
    array_layers: u32,
    samples: u32,
    tiling: u32,
    usage: u32,
    sharing_mode: u32,
    queue_family_index_count: u32,
    p_queue_family_indices: *const u32,
    initial_layout: u32,
}

#[repr(C)]
struct ComponentMapping { r: u32, g: u32, b: u32, a: u32 }
#[repr(C)]
#[derive(Copy, Clone)]
struct ImageSubresourceRange {
    aspect_mask: u32,
    base_mip_level: u32,
    level_count: u32,
    base_array_layer: u32,
    layer_count: u32,
}
#[repr(C)]
struct ImageViewCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    image: u64,
    view_type: u32,
    format: u32,
    components: ComponentMapping,
    subresource_range: ImageSubresourceRange,
}

#[repr(C)]
struct AttachmentDescription {
    flags: u32,
    format: u32,
    samples: u32,
    load_op: u32,
    store_op: u32,
    stencil_load_op: u32,
    stencil_store_op: u32,
    initial_layout: u32,
    final_layout: u32,
}
#[repr(C)]
struct AttachmentReference { attachment: u32, layout: u32 }
#[repr(C)]
struct SubpassDescription {
    flags: u32,
    pipeline_bind_point: u32,
    input_attachment_count: u32,
    p_input_attachments: *const AttachmentReference,
    color_attachment_count: u32,
    p_color_attachments: *const AttachmentReference,
    p_resolve_attachments: *const AttachmentReference,
    p_depth_stencil_attachment: *const AttachmentReference,
    preserve_attachment_count: u32,
    p_preserve_attachments: *const u32,
}
#[repr(C)]
struct SubpassDependency {
    src_subpass: u32,
    dst_subpass: u32,
    src_stage_mask: u32,
    dst_stage_mask: u32,
    src_access_mask: u32,
    dst_access_mask: u32,
    dependency_flags: u32,
}
#[repr(C)]
struct RenderPassCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    attachment_count: u32,
    p_attachments: *const AttachmentDescription,
    subpass_count: u32,
    p_subpasses: *const SubpassDescription,
    dependency_count: u32,
    p_dependencies: *const SubpassDependency,
}

#[repr(C)]
struct FramebufferCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    render_pass: u64,
    attachment_count: u32,
    p_attachments: *const u64,
    width: u32,
    height: u32,
    layers: u32,
}

#[repr(C)]
struct ShaderModuleCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    code_size: usize,
    p_code: *const u32,
}
#[repr(C)]
struct PipelineShaderStageCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    stage: u32,
    module: u64,
    p_name: *const c_char,
    p_specialization: *const c_void,
}
#[repr(C)]
struct VertexInputBindingDescription { binding: u32, stride: u32, input_rate: u32 }
#[repr(C)]
struct VertexInputAttributeDescription { location: u32, binding: u32, format: u32, offset: u32 }
#[repr(C)]
struct PipelineVertexInputStateCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    vertex_binding_description_count: u32,
    p_vertex_binding_descriptions: *const VertexInputBindingDescription,
    vertex_attribute_description_count: u32,
    p_vertex_attribute_descriptions: *const VertexInputAttributeDescription,
}
#[repr(C)]
struct PipelineInputAssemblyStateCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    topology: u32,
    primitive_restart_enable: u32,
}
#[repr(C)]
struct PipelineViewportStateCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    viewport_count: u32,
    p_viewports: *const Viewport,
    scissor_count: u32,
    p_scissors: *const Rect2D,
}
#[repr(C)]
struct PipelineRasterizationStateCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    depth_clamp_enable: u32,
    rasterizer_discard_enable: u32,
    polygon_mode: u32,
    cull_mode: u32,
    front_face: u32,
    depth_bias_enable: u32,
    depth_bias_constant_factor: f32,
    depth_bias_clamp: f32,
    depth_bias_slope_factor: f32,
    line_width: f32,
}
#[repr(C)]
struct PipelineMultisampleStateCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    rasterization_samples: u32,
    sample_shading_enable: u32,
    min_sample_shading: f32,
    p_sample_mask: *const u32,
    alpha_to_coverage_enable: u32,
    alpha_to_one_enable: u32,
}
#[repr(C)]
struct PipelineColorBlendAttachmentState {
    blend_enable: u32,
    src_color_blend_factor: u32,
    dst_color_blend_factor: u32,
    color_blend_op: u32,
    src_alpha_blend_factor: u32,
    dst_alpha_blend_factor: u32,
    alpha_blend_op: u32,
    color_write_mask: u32,
}
#[repr(C)]
struct PipelineColorBlendStateCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    logic_op_enable: u32,
    logic_op: u32,
    attachment_count: u32,
    p_attachments: *const PipelineColorBlendAttachmentState,
    blend_constants: [f32; 4],
}
#[repr(C)]
struct PipelineDynamicStateCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    dynamic_state_count: u32,
    p_dynamic_states: *const u32,
}
#[repr(C)]
struct GraphicsPipelineCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    stage_count: u32,
    p_stages: *const PipelineShaderStageCreateInfo,
    p_vertex_input_state: *const PipelineVertexInputStateCreateInfo,
    p_input_assembly_state: *const PipelineInputAssemblyStateCreateInfo,
    p_tessellation_state: *const c_void,
    p_viewport_state: *const PipelineViewportStateCreateInfo,
    p_rasterization_state: *const PipelineRasterizationStateCreateInfo,
    p_multisample_state: *const PipelineMultisampleStateCreateInfo,
    p_depth_stencil_state: *const c_void,
    p_color_blend_state: *const PipelineColorBlendStateCreateInfo,
    p_dynamic_state: *const PipelineDynamicStateCreateInfo,
    layout: u64,
    render_pass: u64,
    subpass: u32,
    base_pipeline_handle: u64,
    base_pipeline_index: i32,
}
#[repr(C)]
struct PipelineLayoutCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    set_layout_count: u32,
    p_set_layouts: *const u64,
    push_constant_range_count: u32,
    p_push_constant_ranges: *const c_void,
}

#[repr(C)]
struct CommandPoolCreateInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    queue_family_index: u32,
}
#[repr(C)]
struct CommandBufferAllocateInfo {
    s_type: u32,
    p_next: *const c_void,
    command_pool: CommandPool,
    level: u32,
    command_buffer_count: u32,
}
#[repr(C)]
struct CommandBufferBeginInfo {
    s_type: u32,
    p_next: *const c_void,
    flags: u32,
    p_inheritance_info: *const c_void,
}

#[repr(C)]
struct ImageSubresourceLayers {
    aspect_mask: u32,
    mip_level: u32,
    base_array_layer: u32,
    layer_count: u32,
}
#[repr(C)]
struct BufferImageCopy {
    buffer_offset: u64,
    buffer_row_length: u32,
    buffer_image_height: u32,
    image_subresource: ImageSubresourceLayers,
    image_offset: Offset3D,
    image_extent: Extent3D,
}
#[repr(C)]
struct ImageMemoryBarrier {
    s_type: u32,
    p_next: *const c_void,
    src_access_mask: u32,
    dst_access_mask: u32,
    old_layout: u32,
    new_layout: u32,
    src_queue_family_index: u32,
    dst_queue_family_index: u32,
    image: u64,
    subresource_range: ImageSubresourceRange,
}
#[repr(C)]
struct SubmitInfo {
    s_type: u32,
    p_next: *const c_void,
    wait_semaphore_count: u32,
    p_wait_semaphores: *const u64,
    p_wait_dst_stage_mask: *const u32,
    command_buffer_count: u32,
    p_command_buffers: *const CommandBuffer,
    signal_semaphore_count: u32,
    p_signal_semaphores: *const u64,
}
#[repr(C)]
struct FenceCreateInfo { s_type: u32, p_next: *const c_void, flags: u32 }
#[repr(C)]
struct ClearColorValue { float32: [f32; 4] }
#[repr(C)]
struct ClearValue { color: ClearColorValue }
#[repr(C)]
struct RenderPassBeginInfo {
    s_type: u32,
    p_next: *const c_void,
    render_pass: u64,
    framebuffer: u64,
    render_area: Rect2D,
    clear_value_count: u32,
    p_clear_values: *const ClearValue,
}

// --- function pointer table ----------------------------------------------
macro_rules! vk_fns {
    ($( $name:ident : fn($($arg:ty),* $(,)?) -> $ret:ty; )*) => {
        #[allow(non_snake_case)]
        struct VkFns { $($name: unsafe extern "C" fn($($arg),*) -> $ret,)* }
        impl VkFns {
            unsafe fn load(lib: *mut c_void) -> Result<Self, RenderError> {
                // SAFETY: resolves each symbol from the live loader handle and
                // reinterprets the address as the documented C signature.
                unsafe {
                    Ok(Self {
                        $($name: {
                            let p = dlsym(lib, concat!(stringify!($name), "\0").as_ptr() as *const c_char);
                            if p.is_null() {
                                return Err(RenderError::BackendUnavailable(
                                    concat!("vulkan: missing symbol ", stringify!($name))));
                            }
                            transmute::<*mut c_void, unsafe extern "C" fn($($arg),*) -> $ret>(p)
                        },)*
                    })
                }
            }
        }
    };
}

vk_fns! {
    vkCreateInstance: fn(*const InstanceCreateInfo, *const c_void, *mut Instance) -> i32;
    vkDestroyInstance: fn(Instance, *const c_void) -> ();
    vkEnumeratePhysicalDevices: fn(Instance, *mut u32, *mut PhysicalDevice) -> i32;
    vkGetPhysicalDeviceProperties: fn(PhysicalDevice, *mut c_void) -> ();
    vkGetPhysicalDeviceQueueFamilyProperties: fn(PhysicalDevice, *mut u32, *mut QueueFamilyProperties) -> ();
    vkGetPhysicalDeviceMemoryProperties: fn(PhysicalDevice, *mut PhysicalDeviceMemoryProperties) -> ();
    vkCreateDevice: fn(PhysicalDevice, *const DeviceCreateInfo, *const c_void, *mut Device) -> i32;
    vkDestroyDevice: fn(Device, *const c_void) -> ();
    vkGetDeviceQueue: fn(Device, u32, u32, *mut Queue) -> ();
    vkDeviceWaitIdle: fn(Device) -> i32;
    vkCreateBuffer: fn(Device, *const BufferCreateInfo, *const c_void, *mut u64) -> i32;
    vkDestroyBuffer: fn(Device, u64, *const c_void) -> ();
    vkGetBufferMemoryRequirements: fn(Device, u64, *mut MemoryRequirements) -> ();
    vkAllocateMemory: fn(Device, *const MemoryAllocateInfo, *const c_void, *mut u64) -> i32;
    vkFreeMemory: fn(Device, u64, *const c_void) -> ();
    vkBindBufferMemory: fn(Device, u64, u64, u64) -> i32;
    vkMapMemory: fn(Device, u64, u64, u64, u32, *mut *mut c_void) -> i32;
    vkUnmapMemory: fn(Device, u64) -> ();
    vkCreateImage: fn(Device, *const ImageCreateInfo, *const c_void, *mut u64) -> i32;
    vkDestroyImage: fn(Device, u64, *const c_void) -> ();
    vkGetImageMemoryRequirements: fn(Device, u64, *mut MemoryRequirements) -> ();
    vkBindImageMemory: fn(Device, u64, u64, u64) -> i32;
    vkCreateImageView: fn(Device, *const ImageViewCreateInfo, *const c_void, *mut u64) -> i32;
    vkDestroyImageView: fn(Device, u64, *const c_void) -> ();
    vkCreateRenderPass: fn(Device, *const RenderPassCreateInfo, *const c_void, *mut u64) -> i32;
    vkDestroyRenderPass: fn(Device, u64, *const c_void) -> ();
    vkCreateFramebuffer: fn(Device, *const FramebufferCreateInfo, *const c_void, *mut u64) -> i32;
    vkDestroyFramebuffer: fn(Device, u64, *const c_void) -> ();
    vkCreateShaderModule: fn(Device, *const ShaderModuleCreateInfo, *const c_void, *mut u64) -> i32;
    vkDestroyShaderModule: fn(Device, u64, *const c_void) -> ();
    vkCreatePipelineLayout: fn(Device, *const PipelineLayoutCreateInfo, *const c_void, *mut u64) -> i32;
    vkDestroyPipelineLayout: fn(Device, u64, *const c_void) -> ();
    vkCreateGraphicsPipelines: fn(Device, u64, u32, *const GraphicsPipelineCreateInfo, *const c_void, *mut u64) -> i32;
    vkDestroyPipeline: fn(Device, u64, *const c_void) -> ();
    vkCreateCommandPool: fn(Device, *const CommandPoolCreateInfo, *const c_void, *mut CommandPool) -> i32;
    vkDestroyCommandPool: fn(Device, CommandPool, *const c_void) -> ();
    vkAllocateCommandBuffers: fn(Device, *const CommandBufferAllocateInfo, *mut CommandBuffer) -> i32;
    vkBeginCommandBuffer: fn(CommandBuffer, *const CommandBufferBeginInfo) -> i32;
    vkEndCommandBuffer: fn(CommandBuffer) -> i32;
    vkCmdBeginRenderPass: fn(CommandBuffer, *const RenderPassBeginInfo, u32) -> ();
    vkCmdEndRenderPass: fn(CommandBuffer) -> ();
    vkCmdBindPipeline: fn(CommandBuffer, u32, u64) -> ();
    vkCmdSetViewport: fn(CommandBuffer, u32, u32, *const Viewport) -> ();
    vkCmdSetScissor: fn(CommandBuffer, u32, u32, *const Rect2D) -> ();
    vkCmdBindVertexBuffers: fn(CommandBuffer, u32, u32, *const u64, *const u64) -> ();
    vkCmdBindIndexBuffer: fn(CommandBuffer, u64, u64, u32) -> ();
    vkCmdDrawIndexed: fn(CommandBuffer, u32, u32, u32, i32, u32) -> ();
    vkCmdPipelineBarrier: fn(CommandBuffer, u32, u32, u32, u32, *const c_void, u32, *const c_void, u32, *const ImageMemoryBarrier) -> ();
    vkCmdCopyImageToBuffer: fn(CommandBuffer, u64, u32, u64, u32, *const BufferImageCopy) -> ();
    vkCreateFence: fn(Device, *const FenceCreateInfo, *const c_void, *mut u64) -> i32;
    vkDestroyFence: fn(Device, u64, *const c_void) -> ();
    vkWaitForFences: fn(Device, u32, *const u64, u32, u64) -> i32;
    vkQueueSubmit: fn(Queue, u32, *const SubmitInfo, u64) -> i32;
}

#[link(name = "c")]
extern "C" {
    fn dlopen(filename: *const c_char, flag: c_int) -> *mut c_void;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}

const RTLD_NOW: c_int = 2;

fn open_vulkan() -> Result<*mut c_void, RenderError> {
    // SAFETY: plain loader call with a static, NUL-terminated name.
    let handle = unsafe { dlopen(c"libvulkan.so.1".as_ptr(), RTLD_NOW) };
    if handle.is_null() {
        return Err(RenderError::BackendUnavailable("vulkan: libvulkan.so.1 not loadable"));
    }
    Ok(handle)
}

fn vk_err(code: i32) -> RenderError {
    match code {
        -1 => RenderError::OutOfMemory,
        -2 => RenderError::BackendUnavailable("vulkan: initialisation failed"),
        -3 => RenderError::BackendUnavailable("vulkan: device lost"),
        -4 => RenderError::BackendUnavailable("vulkan: device lost"),
        -5 => RenderError::OutOfMemory,
        -6 => RenderError::BackendUnavailable("vulkan: layer not present"),
        -7 => RenderError::Unsupported("vulkan: extension not present"),
        -8 => RenderError::Unsupported("vulkan: feature not present"),
        -9 => RenderError::BackendUnavailable("vulkan: incompatible driver"),
        -11 => RenderError::OutOfMemory,
        -12 => RenderError::BackendUnavailable("vulkan: operation failed"),
        _ => RenderError::Api("vulkan: call failed"),
    }
}

fn check(code: i32) -> Result<(), RenderError> {
    if code == VK_SUCCESS { Ok(()) } else { Err(vk_err(code)) }
}

/// Parsed physical-device properties read from the raw driver struct.
struct DeviceProps {
    api_major: u32,
    api_minor: u32,
    max_image_dimension_2d: u32,
    device_name: String,
}

fn read_device_props(fns: &VkFns, phys: PhysicalDevice) -> DeviceProps {
    // VkPhysicalDeviceProperties is large and version-specific. We allocate a
    // generously sized buffer and read the leading, stable fields by offset:
    //   0   u32 apiVersion
    //   16  u32 deviceType
    //   20  256-byte deviceName
    //   292 start of VkPhysicalDeviceLimits; maxImageDimension2D at +4 => 296
    let mut raw = [0u8; 1024];
    // SAFETY: the driver writes at most sizeof(VkPhysicalDeviceProperties) into
    // the 1024-byte buffer, which is larger than that struct on all known ICDs.
    unsafe { (fns.vkGetPhysicalDeviceProperties)(phys, raw.as_mut_ptr() as *mut c_void) };
    let api = u32::from_ne_bytes([raw[0], raw[1], raw[2], raw[3]]);
    let max_dim = u32::from_ne_bytes([raw[296], raw[297], raw[298], raw[299]]);
    let name_bytes = &raw[20..276];
    let end = name_bytes.iter().position(|&b| b == 0).unwrap_or(name_bytes.len());
    let name = String::from_utf8_lossy(&name_bytes[..end]).into_owned();
    DeviceProps {
        api_major: (api >> 22) & 0x7f,
        api_minor: (api >> 12) & 0x3ff,
        max_image_dimension_2d: max_dim,
        device_name: name,
    }
}

struct GpuMesh {
    vertex_buffer: u64,
    vertex_memory: u64,
    index_buffer: u64,
    index_memory: u64,
    index_count: u32,
}

struct FrameTarget {
    extent: [u32; 2],
    image: u64,
    image_memory: u64,
    view: u64,
    framebuffer: u64,
    readback_buffer: u64,
    readback_memory: u64,
}

/// A Vulkan renderer owning a device and an offscreen colour target.
pub struct VulkanRenderer {
    // Kept open for the renderer's lifetime; see `Drop`.
    _lib: *mut c_void,
    fns: VkFns,
    instance: Instance,
    physical_device: PhysicalDevice,
    device: Device,
    queue: Queue,
    queue_family: u32,
    memory_properties: PhysicalDeviceMemoryProperties,
    command_pool: CommandPool,
    command_buffer: CommandBuffer,
    render_pass: u64,
    pipeline_layout: u64,
    pipeline: u64,
    capabilities: Capabilities,
    device_name: String,
    meshes: Vec<Option<GpuMesh>>,
    next_handle: u64,
    target: Option<FrameTarget>,
    pending: Vec<DrawBatch>,
    last_frame: Option<FramePixels>,
}

/// Probe without creating a persistent renderer.
pub fn probe() -> Result<Capabilities, RenderError> {
    let renderer = VulkanRenderer::create_headless()?;
    let caps = renderer.capabilities();
    drop(renderer);
    Ok(caps)
}

impl VulkanRenderer {
    /// Create a headless (windowless) Vulkan renderer for offscreen rendering.
    pub fn create_headless() -> Result<Self, RenderError> {
        let lib = open_vulkan()?;
        Self::init(lib)
    }

    /// Initialise the device. If a late step fails the partially built objects
    /// are left to process teardown; this only happens on unsupported machines,
    /// so a small leak is preferable to threading cleanup through every early
    /// return.
    fn init(lib: *mut c_void) -> Result<Self, RenderError> {
        // SAFETY: `lib` is an open loader handle. Every call below is a Vulkan
        // entry point invoked with correctly laid out #[repr(C)] arguments, and
        // the handles produced are owned by the renderer that stores `lib`.
        unsafe {
            let fns = VkFns::load(lib)?;

            // Instance.
            let app_name = c"jpe-world-engine";
            let app_info = ApplicationInfo {
                s_type: STRUCTURE_TYPE_APPLICATION_INFO,
                p_next: null(),
                p_app_name: app_name.as_ptr(),
                app_version: 1,
                p_engine_name: app_name.as_ptr(),
                engine_version: 1,
                api_version: (1 << 22) | (0 << 12),
            };
            let instance_info = InstanceCreateInfo {
                s_type: STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                p_next: null(),
                flags: 0,
                p_app_info: &app_info,
                enabled_layer_count: 0,
                pp_enabled_layer_names: null(),
                enabled_extension_count: 0,
                pp_enabled_extension_names: null(),
            };
            let mut instance: Instance = null_mut();
            check((fns.vkCreateInstance)(&instance_info, null(), &mut instance))?;

            // Physical device with a graphics queue.
            let mut count = 0u32;
            check((fns.vkEnumeratePhysicalDevices)(instance, &mut count, null_mut()))?;
            if count == 0 {
                return Err(RenderError::BackendUnavailable("vulkan: no physical device"));
            }
            let mut devices = vec![null_mut(); count as usize];
            check((fns.vkEnumeratePhysicalDevices)(instance, &mut count, devices.as_mut_ptr()))?;
            if devices.is_empty() {
                return Err(RenderError::BackendUnavailable("vulkan: no physical device"));
            }
            let physical_device = devices[0];
            let props = read_device_props(&fns, physical_device);

            let mut qf_count = 0u32;
            (fns.vkGetPhysicalDeviceQueueFamilyProperties)(physical_device, &mut qf_count, null_mut());
            let mut qf = vec![QueueFamilyProperties {
                queue_flags: 0, queue_count: 0, timestamp_valid_bits: 0,
                min_image_transfer_granularity: Extent3D { width: 0, height: 0, depth: 0 },
            }; qf_count as usize];
            (fns.vkGetPhysicalDeviceQueueFamilyProperties)(physical_device, &mut qf_count, qf.as_mut_ptr());
            let mut supports_compute = false;
            let mut graphics_family = None;
            for (i, f) in qf.iter().enumerate() {
                if f.queue_flags & QUEUE_COMPUTE_BIT != 0 { supports_compute = true; }
                if graphics_family.is_none() && f.queue_flags & QUEUE_GRAPHICS_BIT != 0 && f.queue_count > 0 {
                    graphics_family = Some(i as u32);
                }
            }
            let Some(queue_family) = graphics_family else {
                return Err(RenderError::BackendUnavailable("vulkan: no graphics queue family"));
            };

            // Logical device.
            let priorities = [1.0f32];
            let qci = DeviceQueueCreateInfo {
                s_type: STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                p_next: null(),
                flags: 0,
                queue_family_index: queue_family,
                queue_count: 1,
                p_queue_priorities: priorities.as_ptr(),
            };
            let dci = DeviceCreateInfo {
                s_type: STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                p_next: null(),
                flags: 0,
                queue_create_info_count: 1,
                p_queue_create_infos: &qci,
                enabled_layer_count: 0,
                pp_enabled_layer_names: null(),
                enabled_extension_count: 0,
                pp_enabled_extension_names: null(),
                p_enabled_features: null(),
            };
            let mut device: Device = null_mut();
            check((fns.vkCreateDevice)(physical_device, &dci, null(), &mut device))?;
            let mut queue: Queue = null_mut();
            (fns.vkGetDeviceQueue)(device, queue_family, 0, &mut queue);

            let mut memory_properties: PhysicalDeviceMemoryProperties = zeroed();
            (fns.vkGetPhysicalDeviceMemoryProperties)(physical_device, &mut memory_properties);

            // Command pool + one primary command buffer.
            let cpci = CommandPoolCreateInfo {
                s_type: STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                p_next: null(),
                flags: 0,
                queue_family_index: queue_family,
            };
            let mut command_pool: CommandPool = null_mut();
            check((fns.vkCreateCommandPool)(device, &cpci, null(), &mut command_pool))?;
            let cbai = CommandBufferAllocateInfo {
                s_type: STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                p_next: null(),
                command_pool,
                level: COMMAND_BUFFER_LEVEL_PRIMARY,
                command_buffer_count: 1,
            };
            let mut command_buffer: CommandBuffer = null_mut();
            check((fns.vkAllocateCommandBuffers)(device, &cbai, &mut command_buffer))?;

            // Render pass: one colour attachment, cleared then stored as transfer src.
            let attachment = AttachmentDescription {
                flags: 0,
                format: FORMAT_R8G8B8A8_UNORM,
                samples: SAMPLE_COUNT_1,
                load_op: ATTACHMENT_LOAD_OP_CLEAR,
                store_op: ATTACHMENT_STORE_OP_STORE,
                stencil_load_op: ATTACHMENT_LOAD_OP_CLEAR,
                stencil_store_op: ATTACHMENT_STORE_OP_STORE,
                initial_layout: LAYOUT_UNDEFINED,
                final_layout: LAYOUT_TRANSFER_SRC_OPTIMAL,
            };
            let colour_ref = AttachmentReference { attachment: 0, layout: 0x2 /* COLOR_ATTACHMENT_OPTIMAL */ };
            let subpass = SubpassDescription {
                flags: 0,
                pipeline_bind_point: PIPELINE_BIND_POINT_GRAPHICS,
                input_attachment_count: 0,
                p_input_attachments: null(),
                color_attachment_count: 1,
                p_color_attachments: &colour_ref,
                p_resolve_attachments: null(),
                p_depth_stencil_attachment: null(),
                preserve_attachment_count: 0,
                p_preserve_attachments: null(),
            };
            let dependency = SubpassDependency {
                src_subpass: SUBPASS_EXTERNAL,
                dst_subpass: 0,
                src_stage_mask: PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT,
                dst_stage_mask: PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT,
                src_access_mask: 0,
                dst_access_mask: ACCESS_COLOR_ATTACHMENT_WRITE,
                dependency_flags: 0,
            };
            let rpci = RenderPassCreateInfo {
                s_type: STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
                p_next: null(),
                flags: 0,
                attachment_count: 1,
                p_attachments: &attachment,
                subpass_count: 1,
                p_subpasses: &subpass,
                dependency_count: 1,
                p_dependencies: &dependency,
            };
            let mut render_pass = 0u64;
            check((fns.vkCreateRenderPass)(device, &rpci, null(), &mut render_pass))?;

            // Pipeline layout + graphics pipeline.
            let plci = PipelineLayoutCreateInfo {
                s_type: STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
                p_next: null(),
                flags: 0,
                set_layout_count: 0,
                p_set_layouts: null(),
                push_constant_range_count: 0,
                p_push_constant_ranges: null(),
            };
            let mut pipeline_layout = 0u64;
            check((fns.vkCreatePipelineLayout)(device, &plci, null(), &mut pipeline_layout))?;

            let mut pipeline = 0u64;
            let mut modules = [0u64; 2];
            VulkanRenderer::create_pipeline(&fns, device, render_pass, pipeline_layout, &mut pipeline, &mut modules)?;

            Ok(VulkanRenderer {
                _lib: lib,
                fns,
                instance,
                physical_device,
                device,
                queue,
                queue_family,
                memory_properties,
                command_pool,
                command_buffer,
                render_pass,
                pipeline_layout,
                pipeline,
                capabilities: Capabilities {
                    backend: BACKEND,
                    api_version: (props.api_major, props.api_minor),
                    max_texture_size: props.max_image_dimension_2d,
                    supports_compute,
                },
                device_name: props.device_name,
                meshes: Vec::new(),
                next_handle: 1,
                target: None,
                pending: Vec::new(),
                last_frame: None,
            })
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn create_pipeline(
        fns: &VkFns,
        device: Device,
        render_pass: u64,
        layout: u64,
        out_pipeline: &mut u64,
        out_modules: &mut [u64; 2],
    ) -> Result<(), RenderError> {
        let name = c"main";
        let vert_ci = ShaderModuleCreateInfo {
            s_type: STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
            p_next: null(),
            flags: 0,
            code_size: VERT_SPV.len(),
            p_code: VERT_SPV.as_ptr() as *const u32,
        };
        let frag_ci = ShaderModuleCreateInfo {
            s_type: STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
            p_next: null(),
            flags: 0,
            code_size: FRAG_SPV.len(),
            p_code: FRAG_SPV.as_ptr() as *const u32,
        };
        unsafe {
            check((fns.vkCreateShaderModule)(device, &vert_ci, null(), &mut out_modules[0]))?;
            check((fns.vkCreateShaderModule)(device, &frag_ci, null(), &mut out_modules[1]))?;
        }
        let stages = [
            PipelineShaderStageCreateInfo {
                s_type: STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                p_next: null(),
                flags: 0,
                stage: SHADER_STAGE_VERTEX,
                module: out_modules[0],
                p_name: name.as_ptr(),
                p_specialization: null(),
            },
            PipelineShaderStageCreateInfo {
                s_type: STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                p_next: null(),
                flags: 0,
                stage: SHADER_STAGE_FRAGMENT,
                module: out_modules[1],
                p_name: name.as_ptr(),
                p_specialization: null(),
            },
        ];
        let binding = VertexInputBindingDescription { binding: 0, stride: Vertex::STRIDE as u32, input_rate: 0 };
        let attribute = VertexInputAttributeDescription {
            location: 0,
            binding: 0,
            format: FORMAT_R32G32B32_SFLOAT,
            offset: 0,
        };
        let vertex_input = PipelineVertexInputStateCreateInfo {
            s_type: STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
            p_next: null(),
            flags: 0,
            vertex_binding_description_count: 1,
            p_vertex_binding_descriptions: &binding,
            vertex_attribute_description_count: 1,
            p_vertex_attribute_descriptions: &attribute,
        };
        let input_assembly = PipelineInputAssemblyStateCreateInfo {
            s_type: STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
            p_next: null(),
            flags: 0,
            topology: PRIMITIVE_TRIANGLE_LIST,
            primitive_restart_enable: 0,
        };
        let viewport_state = PipelineViewportStateCreateInfo {
            s_type: STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
            p_next: null(),
            flags: 0,
            viewport_count: 1,
            p_viewports: null(),
            scissor_count: 1,
            p_scissors: null(),
        };
        let raster = PipelineRasterizationStateCreateInfo {
            s_type: STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
            p_next: null(),
            flags: 0,
            depth_clamp_enable: 0,
            rasterizer_discard_enable: 0,
            polygon_mode: POLYGON_MODE_FILL,
            cull_mode: CULL_MODE_NONE,
            front_face: FRONT_FACE_COUNTER_CLOCKWISE,
            depth_bias_enable: 0,
            depth_bias_constant_factor: 0.0,
            depth_bias_clamp: 0.0,
            depth_bias_slope_factor: 0.0,
            line_width: 1.0,
        };
        let multisample = PipelineMultisampleStateCreateInfo {
            s_type: STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
            p_next: null(),
            flags: 0,
            rasterization_samples: SAMPLE_COUNT_1,
            sample_shading_enable: 0,
            min_sample_shading: 0.0,
            p_sample_mask: null(),
            alpha_to_coverage_enable: 0,
            alpha_to_one_enable: 0,
        };
        let blend_attachment = PipelineColorBlendAttachmentState {
            blend_enable: 0,
            src_color_blend_factor: BLEND_FACTOR_ONE,
            dst_color_blend_factor: BLEND_FACTOR_ZERO,
            color_blend_op: BLEND_OP_ADD,
            src_alpha_blend_factor: BLEND_FACTOR_ONE,
            dst_alpha_blend_factor: BLEND_FACTOR_ZERO,
            alpha_blend_op: BLEND_OP_ADD,
            color_write_mask: COLOR_COMPONENT_RGBA,
        };
        let blend = PipelineColorBlendStateCreateInfo {
            s_type: STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
            p_next: null(),
            flags: 0,
            logic_op_enable: 0,
            logic_op: 0,
            attachment_count: 1,
            p_attachments: &blend_attachment,
            blend_constants: [0.0; 4],
        };
        let dynamic_states = [DYNAMIC_STATE_VIEWPORT, DYNAMIC_STATE_SCISSOR];
        let dynamic = PipelineDynamicStateCreateInfo {
            s_type: STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
            p_next: null(),
            flags: 0,
            dynamic_state_count: dynamic_states.len() as u32,
            p_dynamic_states: dynamic_states.as_ptr(),
        };
        let gpci = GraphicsPipelineCreateInfo {
            s_type: STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
            p_next: null(),
            flags: 0,
            stage_count: stages.len() as u32,
            p_stages: stages.as_ptr(),
            p_vertex_input_state: &vertex_input,
            p_input_assembly_state: &input_assembly,
            p_tessellation_state: null(),
            p_viewport_state: &viewport_state,
            p_rasterization_state: &raster,
            p_multisample_state: &multisample,
            p_depth_stencil_state: null(),
            p_color_blend_state: &blend,
            p_dynamic_state: &dynamic,
            layout,
            render_pass,
            subpass: 0,
            base_pipeline_handle: 0,
            base_pipeline_index: -1,
        };
        unsafe {
            let result = check((fns.vkCreateGraphicsPipelines)(device, 0, 1, &gpci, null(), out_pipeline));
            // Shader modules are only needed to build the pipeline.
            (fns.vkDestroyShaderModule)(device, out_modules[0], null());
            (fns.vkDestroyShaderModule)(device, out_modules[1], null());
            result
        }
    }

    fn memory_type(&self, type_bits: u32, wanted: u32) -> Option<u32> {
        for i in 0..self.memory_properties.memory_type_count {
            if type_bits & (1 << i) != 0 {
                let t = self.memory_properties.memory_types[i as usize];
                if t.property_flags & wanted == wanted {
                    return Some(i);
                }
            }
        }
        None
    }

    fn create_buffer(&self, size: u64, usage: u32, wanted: u32) -> Result<(u64, u64), RenderError> {
        let ci = BufferCreateInfo {
            s_type: STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            p_next: null(),
            flags: 0,
            size,
            usage,
            sharing_mode: SHARING_MODE_EXCLUSIVE,
            queue_family_index_count: 0,
            p_queue_family_indices: null(),
        };
        let mut buffer = 0u64;
        unsafe { check((self.fns.vkCreateBuffer)(self.device, &ci, null(), &mut buffer))?; }
        let mut req: MemoryRequirements = unsafe { zeroed() };
        unsafe { (self.fns.vkGetBufferMemoryRequirements)(self.device, buffer, &mut req) };
        let Some(index) = self.memory_type(req.memory_type_bits, wanted) else {
            unsafe { (self.fns.vkDestroyBuffer)(self.device, buffer, null()) };
            return Err(RenderError::BackendUnavailable("vulkan: no suitable memory type"));
        };
        let ai = MemoryAllocateInfo {
            s_type: STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            p_next: null(),
            allocation_size: req.size,
            memory_type_index: index,
        };
        let mut memory = 0u64;
        unsafe { check((self.fns.vkAllocateMemory)(self.device, &ai, null(), &mut memory))?; }
        unsafe { check((self.fns.vkBindBufferMemory)(self.device, buffer, memory, 0))?; }
        Ok((buffer, memory))
    }

    fn write_buffer(&self, buffer_memory: u64, bytes: &[u8]) -> Result<(), RenderError> {
        let mut ptr: *mut c_void = null_mut();
        unsafe {
            check((self.fns.vkMapMemory)(self.device, buffer_memory, 0, bytes.len() as u64, 0, &mut ptr))?;
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), ptr as *mut u8, bytes.len());
            (self.fns.vkUnmapMemory)(self.device, buffer_memory);
        }
        Ok(())
    }

    fn ensure_target(&mut self, extent: [u32; 2]) -> Result<(), RenderError> {
        if let Some(t) = &self.target {
            if t.extent == extent {
                return Ok(());
            }
        }
        self.destroy_target();
        let (width, height) = (extent[0].max(1), extent[1].max(1));

        let image_ci = ImageCreateInfo {
            s_type: STRUCTURE_TYPE_IMAGE_CREATE_INFO,
            p_next: null(),
            flags: 0,
            image_type: IMAGE_TYPE_2D,
            format: FORMAT_R8G8B8A8_UNORM,
            extent: Extent3D { width, height, depth: 1 },
            mip_levels: 1,
            array_layers: 1,
            samples: SAMPLE_COUNT_1,
            tiling: IMAGE_TILING_OPTIMAL,
            usage: IMAGE_USAGE_COLOR_ATTACHMENT | IMAGE_USAGE_TRANSFER_SRC,
            sharing_mode: SHARING_MODE_EXCLUSIVE,
            queue_family_index_count: 0,
            p_queue_family_indices: null(),
            initial_layout: LAYOUT_UNDEFINED,
        };
        let mut image = 0u64;
        unsafe { check((self.fns.vkCreateImage)(self.device, &image_ci, null(), &mut image))?; }
        let mut req: MemoryRequirements = unsafe { zeroed() };
        unsafe { (self.fns.vkGetImageMemoryRequirements)(self.device, image, &mut req) };
        let Some(index) = self.memory_type(req.memory_type_bits, MEMORY_PROPERTY_DEVICE_LOCAL) else {
            unsafe { (self.fns.vkDestroyImage)(self.device, image, null()) };
            return Err(RenderError::BackendUnavailable("vulkan: no device-local memory"));
        };
        let ai = MemoryAllocateInfo {
            s_type: STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            p_next: null(),
            allocation_size: req.size,
            memory_type_index: index,
        };
        let mut image_memory = 0u64;
        unsafe {
            check((self.fns.vkAllocateMemory)(self.device, &ai, null(), &mut image_memory))?;
            check((self.fns.vkBindImageMemory)(self.device, image, image_memory, 0))?;
        }

        let view_ci = ImageViewCreateInfo {
            s_type: STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            p_next: null(),
            flags: 0,
            image,
            view_type: IMAGE_VIEW_TYPE_2D,
            format: FORMAT_R8G8B8A8_UNORM,
            components: ComponentMapping {
                r: COMPONENT_SWIZZLE_IDENTITY,
                g: COMPONENT_SWIZZLE_IDENTITY,
                b: COMPONENT_SWIZZLE_IDENTITY,
                a: COMPONENT_SWIZZLE_IDENTITY,
            },
            subresource_range: ImageSubresourceRange {
                aspect_mask: IMAGE_ASPECT_COLOR,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            },
        };
        let mut view = 0u64;
        unsafe { check((self.fns.vkCreateImageView)(self.device, &view_ci, null(), &mut view))?; }

        let fb_ci = FramebufferCreateInfo {
            s_type: STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
            p_next: null(),
            flags: 0,
            render_pass: self.render_pass,
            attachment_count: 1,
            p_attachments: &view,
            width,
            height,
            layers: 1,
        };
        let mut framebuffer = 0u64;
        unsafe { check((self.fns.vkCreateFramebuffer)(self.device, &fb_ci, null(), &mut framebuffer))?; }

        let readback_size = width as u64 * height as u64 * 4;
        let (readback_buffer, readback_memory) =
            self.create_buffer(readback_size, BUFFER_USAGE_TRANSFER_SRC, MEMORY_PROPERTY_HOST_VISIBLE | MEMORY_PROPERTY_HOST_COHERENT)?;

        self.target = Some(FrameTarget {
            extent: [width, height],
            image,
            image_memory,
            view,
            framebuffer,
            readback_buffer,
            readback_memory,
        });
        Ok(())
    }

    fn destroy_target(&mut self) {
        if let Some(t) = self.target.take() {
            unsafe {
                (self.fns.vkDestroyFramebuffer)(self.device, t.framebuffer, null());
                (self.fns.vkDestroyImageView)(self.device, t.view, null());
                (self.fns.vkDestroyImage)(self.device, t.image, null());
                (self.fns.vkFreeMemory)(self.device, t.image_memory, null());
                (self.fns.vkDestroyBuffer)(self.device, t.readback_buffer, null());
                (self.fns.vkFreeMemory)(self.device, t.readback_memory, null());
            }
        }
    }

    /// Name reported by the driver for the selected physical device.
    pub fn device_name(&self) -> &str {
        &self.device_name
    }

    fn record_and_submit(&mut self) -> Result<(), RenderError> {
        let Some(target) = &self.target else {
            return Err(RenderError::InvalidMesh);
        };
        let fns = &self.fns;
        let device = self.device;

        let begin = CommandBufferBeginInfo {
            s_type: STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
            p_next: null(),
            flags: COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT,
            p_inheritance_info: null(),
        };
        unsafe {
            check((fns.vkBeginCommandBuffer)(self.command_buffer, &begin))?;
        }

        let clear = ClearValue { color: ClearColorValue { float32: [0.05, 0.05, 0.1, 1.0] } };
        let rp_begin = RenderPassBeginInfo {
            s_type: STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            p_next: null(),
            render_pass: self.render_pass,
            framebuffer: target.framebuffer,
            render_area: Rect2D {
                offset: Offset2D { x: 0, y: 0 },
                extent: Extent2D { width: target.extent[0], height: target.extent[1] },
            },
            clear_value_count: 1,
            p_clear_values: &clear,
        };
        let viewport = Viewport {
            x: 0.0, y: 0.0,
            width: target.extent[0] as f32, height: target.extent[1] as f32,
            min_depth: 0.0, max_depth: 1.0,
        };
        let scissor = Rect2D {
            offset: Offset2D { x: 0, y: 0 },
            extent: Extent2D { width: target.extent[0], height: target.extent[1] },
        };

        unsafe {
            (fns.vkCmdBeginRenderPass)(self.command_buffer, &rp_begin, SUBPASS_CONTENTS_INLINE);
            (fns.vkCmdBindPipeline)(self.command_buffer, PIPELINE_BIND_POINT_GRAPHICS, self.pipeline);
            (fns.vkCmdSetViewport)(self.command_buffer, 0, 1, &viewport);
            (fns.vkCmdSetScissor)(self.command_buffer, 0, 1, &scissor);
            for batch in &self.pending {
                let idx = batch.mesh.0 as usize - 1;
                if let Some(Some(mesh)) = self.meshes.get(idx) {
                    let buffers = [mesh.vertex_buffer];
                    let offsets = [0u64];
                    (fns.vkCmdBindVertexBuffers)(self.command_buffer, 0, 1, buffers.as_ptr(), offsets.as_ptr());
                    (fns.vkCmdBindIndexBuffer)(self.command_buffer, mesh.index_buffer, 0, INDEX_TYPE_UINT32);
                    (fns.vkCmdDrawIndexed)(
                        self.command_buffer,
                        batch.index_count.min(mesh.index_count),
                        batch.instance_count.max(1),
                        0, 0, 0,
                    );
                }
            }
            (fns.vkCmdEndRenderPass)(self.command_buffer);

            // Colour attachment -> transfer read, then copy into the host buffer.
            let barrier = ImageMemoryBarrier {
                s_type: STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                p_next: null(),
                src_access_mask: ACCESS_COLOR_ATTACHMENT_WRITE,
                dst_access_mask: ACCESS_TRANSFER_READ,
                old_layout: LAYOUT_TRANSFER_SRC_OPTIMAL,
                new_layout: LAYOUT_TRANSFER_SRC_OPTIMAL,
                src_queue_family_index: SUBPASS_EXTERNAL,
                dst_queue_family_index: SUBPASS_EXTERNAL,
                image: target.image,
                subresource_range: ImageSubresourceRange {
                    aspect_mask: IMAGE_ASPECT_COLOR,
                    base_mip_level: 0,
                    level_count: 1,
                    base_array_layer: 0,
                    layer_count: 1,
                },
            };
            (fns.vkCmdPipelineBarrier)(
                self.command_buffer,
                STAGE_COLOR_ATTACHMENT_OUTPUT, STAGE_TRANSFER, 0,
                0, null(), 0, null(), 1, &barrier,
            );
            let region = BufferImageCopy {
                buffer_offset: 0,
                buffer_row_length: 0,
                buffer_image_height: 0,
                image_subresource: ImageSubresourceLayers {
                    aspect_mask: IMAGE_ASPECT_COLOR,
                    mip_level: 0,
                    base_array_layer: 0,
                    layer_count: 1,
                },
                image_offset: Offset3D { x: 0, y: 0, z: 0 },
                image_extent: Extent3D { width: target.extent[0], height: target.extent[1], depth: 1 },
            };
            (fns.vkCmdCopyImageToBuffer)(
                self.command_buffer, target.image, LAYOUT_TRANSFER_SRC_OPTIMAL,
                target.readback_buffer, 1, &region,
            );
            check((fns.vkEndCommandBuffer)(self.command_buffer))?;

            // Submit and wait.
            let fence_ci = FenceCreateInfo { s_type: STRUCTURE_TYPE_FENCE_CREATE_INFO, p_next: null(), flags: 0 };
            let mut fence = 0u64;
            check((fns.vkCreateFence)(device, &fence_ci, null(), &mut fence))?;
            let submit = SubmitInfo {
                s_type: STRUCTURE_TYPE_SUBMIT_INFO,
                p_next: null(),
                wait_semaphore_count: 0,
                p_wait_semaphores: null(),
                p_wait_dst_stage_mask: null(),
                command_buffer_count: 1,
                p_command_buffers: &self.command_buffer,
                signal_semaphore_count: 0,
                p_signal_semaphores: null(),
            };
            let submit_result = (fns.vkQueueSubmit)(self.queue, 1, &submit, fence);
            if submit_result != VK_SUCCESS {
                (fns.vkDestroyFence)(device, fence, null());
                return Err(vk_err(submit_result));
            }
            let wait_result = (fns.vkWaitForFences)(device, 1, &fence, 1, u64::MAX);
            (fns.vkDestroyFence)(device, fence, null());
            check(wait_result)?;
        }

        // Read back into CPU memory.
        let target = self.target.as_ref().unwrap();
        let size = target.extent[0] as u64 * target.extent[1] as u64 * 4;
        let mut ptr: *mut c_void = null_mut();
        unsafe {
            check((fns.vkMapMemory)(device, target.readback_memory, 0, size, 0, &mut ptr))?;
            let slice = std::slice::from_raw_parts(ptr as *const u8, size as usize);
            self.last_frame = Some(FramePixels {
                width: target.extent[0],
                height: target.extent[1],
                rgba8: slice.to_vec(),
            });
            (fns.vkUnmapMemory)(device, target.readback_memory);
        }
        Ok(())
    }
}

impl Renderer for VulkanRenderer {
    fn backend(&self) -> Backend { BACKEND }

    fn capabilities(&self) -> Capabilities { self.capabilities }

    fn begin_frame(&mut self, extent: [u32; 2]) -> Result<(), RenderError> {
        if extent[0] == 0 || extent[1] == 0 {
            return Err(RenderError::InvalidMesh);
        }
        self.ensure_target(extent)?;
        self.pending.clear();
        Ok(())
    }

    fn create_mesh(&mut self, mesh: &crate::mesh::MeshData) -> Result<MeshHandle, RenderError> {
        if mesh.vertices.is_empty() || mesh.indices.is_empty() {
            return Err(RenderError::InvalidMesh);
        }
        let mut vertex_bytes = vec![0u8; mesh.vertices.len() * Vertex::STRIDE];
        for (i, v) in mesh.vertices.iter().enumerate() {
            v.write_le(&mut vertex_bytes[i * Vertex::STRIDE..(i + 1) * Vertex::STRIDE]);
        }
        let index_bytes = mesh.indices.len() * size_of::<u32>();

        let (vb, vbm) = self.create_buffer(
            vertex_bytes.len() as u64, BUFFER_USAGE_VERTEX,
            MEMORY_PROPERTY_HOST_VISIBLE | MEMORY_PROPERTY_HOST_COHERENT,
        )?;
        self.write_buffer(vbm, &vertex_bytes)?;
        let (ib, ibm) = self.create_buffer(
            index_bytes as u64, BUFFER_USAGE_INDEX,
            MEMORY_PROPERTY_HOST_VISIBLE | MEMORY_PROPERTY_HOST_COHERENT,
        )?;
        let raw_indices: &[u8] = unsafe {
            std::slice::from_raw_parts(mesh.indices.as_ptr() as *const u8, index_bytes)
        };
        self.write_buffer(ibm, raw_indices)?;

        let handle = MeshHandle(self.next_handle);
        self.next_handle += 1;
        self.meshes.push(Some(GpuMesh {
            vertex_buffer: vb,
            vertex_memory: vbm,
            index_buffer: ib,
            index_memory: ibm,
            index_count: mesh.indices.len() as u32,
        }));
        Ok(handle)
    }

    fn destroy_mesh(&mut self, handle: MeshHandle) {
        let idx = handle.0 as usize - 1;
        if let Some(slot) = self.meshes.get_mut(idx) {
            if let Some(m) = slot.take() {
                unsafe {
                    (self.fns.vkDestroyBuffer)(self.device, m.vertex_buffer, null());
                    (self.fns.vkFreeMemory)(self.device, m.vertex_memory, null());
                    (self.fns.vkDestroyBuffer)(self.device, m.index_buffer, null());
                    (self.fns.vkFreeMemory)(self.device, m.index_memory, null());
                }
            }
        }
    }

    fn draw(&mut self, batch: DrawBatch) -> Result<(), RenderError> {
        if self.target.is_none() {
            return Err(RenderError::InvalidMesh);
        }
        let idx = batch.mesh.0 as usize - 1;
        match self.meshes.get(idx) {
            Some(Some(_)) => {
                self.pending.push(batch);
                Ok(())
            }
            _ => Err(RenderError::InvalidMesh),
        }
    }

    fn end_frame(&mut self) -> Result<(), RenderError> {
        if self.target.is_none() {
            return Err(RenderError::InvalidMesh);
        }
        self.record_and_submit()
    }

    fn read_pixels(&mut self) -> Result<FramePixels, RenderError> {
        self.last_frame
            .clone()
            .ok_or(RenderError::Unsupported("no completed frame to read"))
    }
}

impl Drop for VulkanRenderer {
    fn drop(&mut self) {
        unsafe {
            (self.fns.vkDeviceWaitIdle)(self.device);
            for slot in &mut self.meshes {
                if let Some(m) = slot.take() {
                    (self.fns.vkDestroyBuffer)(self.device, m.vertex_buffer, null());
                    (self.fns.vkFreeMemory)(self.device, m.vertex_memory, null());
                    (self.fns.vkDestroyBuffer)(self.device, m.index_buffer, null());
                    (self.fns.vkFreeMemory)(self.device, m.index_memory, null());
                }
            }
            self.destroy_target();
            (self.fns.vkDestroyPipeline)(self.device, self.pipeline, null());
            (self.fns.vkDestroyPipelineLayout)(self.device, self.pipeline_layout, null());
            (self.fns.vkDestroyRenderPass)(self.device, self.render_pass, null());
            (self.fns.vkDestroyCommandPool)(self.device, self.command_pool, null());
            (self.fns.vkDestroyDevice)(self.device, null());
            (self.fns.vkDestroyInstance)(self.instance, null());
            // The loader handle is intentionally left open: closing it can
            // unload the ICD while process-wide teardown state still refers to
            // it, which crashes at exit. One handle per renderer is harmless.
        }
        let _ = self.physical_device;
        let _ = self.queue_family;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mesh::{build_section_mesh, ChunkSection};

    /// CI sets `JPE_REQUIRE_RENDER_BACKENDS=1` so a missing driver is a failure
    /// instead of a silent skip; that proves the backend actually ran.
    fn require_or_skip(msg: &'static str) {
        if std::env::var_os("JPE_REQUIRE_RENDER_BACKENDS").is_some() {
            panic!("render backend required but unavailable: {msg}");
        }
    }

    fn one_block_mesh() -> crate::mesh::MeshData {
        let mut section = ChunkSection::new();
        section.set(0, 0, 0, 1);
        build_section_mesh(&section, [0, 0, 0])
    }

    #[test]
    fn probe_or_skip() {
        match probe() {
            Ok(caps) => {
                assert_eq!(caps.backend, Backend::Vulkan);
                assert!(caps.api_version.0 >= 1);
                eprintln!("vulkan probe ok: api={:?} maxTex={}", caps.api_version, caps.max_texture_size);
            }
            Err(RenderError::BackendUnavailable(msg)) => {
                require_or_skip(msg);
                eprintln!("vulkan unavailable here, skipping: {msg}");
            }
            Err(e) => panic!("unexpected probe error: {e:?}"),
        }
    }

    #[test]
    fn offscreen_draw_produces_pixels() {
        let mut renderer = match VulkanRenderer::create_headless() {
            Ok(r) => r,
            Err(RenderError::BackendUnavailable(msg)) => {
                require_or_skip(msg);
                eprintln!("vulkan unavailable here, skipping draw: {msg}");
                return;
            }
            Err(e) => panic!("create_headless failed: {e:?}"),
        };
        eprintln!("vulkan device: {}", renderer.device_name());

        let mesh = one_block_mesh();
        let handle = renderer.create_mesh(&mesh).expect("create_mesh");
        renderer.begin_frame([128, 128]).expect("begin_frame");
        renderer
            .draw(DrawBatch { mesh: handle, index_count: mesh.indices.len() as u32, instance_count: 1 })
            .expect("draw");
        renderer.end_frame().expect("end_frame");

        let pixels = renderer.read_pixels().expect("read_pixels");
        assert_eq!(pixels.width, 128);
        assert_eq!(pixels.height, 128);
        assert_eq!(pixels.rgba8.len(), 128 * 128 * 4);

        // The cleared top-left corner is background; the geometry covers part of
        // the frame, so a meaningful number of pixels must differ from it.
        let background = pixels.pixel(0, 0).expect("corner pixel");
        let non_bg = pixels.count_non_background(background);
        assert!(non_bg > 100, "expected geometry coverage, got {non_bg} non-background pixels");

        renderer.destroy_mesh(handle);
    }
}

// The queue-family-create structure type constant (2), spelled out to avoid a
// magic number at the call site above.
