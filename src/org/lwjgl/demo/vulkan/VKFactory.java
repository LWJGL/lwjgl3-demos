/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.vulkan;

import static org.lwjgl.vulkan.EXTDebugReport.VK_STRUCTURE_TYPE_DEBUG_REPORT_CALLBACK_CREATE_INFO_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
import static org.lwjgl.vulkan.KHR8bitStorage.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_8BIT_STORAGE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRGetMemoryRequirements2.VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2_KHR;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.*;
import static org.lwjgl.vulkan.KHRShaderFloat16Int8.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FLOAT16_INT8_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.KHRTimelineSemaphore.VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.NVRayTracing.*;
import static org.lwjgl.vulkan.VK10.*;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;
import org.lwjgl.vulkan.VkWriteDescriptorSet.Buffer;

/**
 * Factory methods to allocate various Vulkan structs with their propert sType.
 * 
 * @author Kai Burjack
 */
public class VKFactory {
    static VmaVulkanFunctions VmaVulkanFunctions(MemoryStack stack) {
        return VmaVulkanFunctions.calloc(stack);
    }

    static VmaAllocatorCreateInfo VmaAllocatorCreateInfo(MemoryStack stack) {
        return VmaAllocatorCreateInfo.calloc(stack);
    }

    static VkInstanceCreateInfo VkInstanceCreateInfo(MemoryStack stack) {
        return VkInstanceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
    }

    static VkApplicationInfo VkApplicationInfo(MemoryStack stack) {
        return VkApplicationInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
    }

    static VkDebugReportCallbackCreateInfoEXT VkDebugReportCallbackCreateInfoEXT(MemoryStack stack) {
        return VkDebugReportCallbackCreateInfoEXT.calloc(stack).sType(VK_STRUCTURE_TYPE_DEBUG_REPORT_CALLBACK_CREATE_INFO_EXT);
    }

    static VkDebugUtilsMessengerCreateInfoEXT VkDebugUtilsMessengerCreateInfoEXT(MemoryStack stack) {
        return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
    }

    static VkDeviceCreateInfo VkDeviceCreateInfo(MemoryStack stack) {
        return VkDeviceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
    }

    static VkDeviceQueueCreateInfo.Buffer VkDeviceQueueCreateInfo(MemoryStack stack) {
        return VkDeviceQueueCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
    }

    static VkPhysicalDevice8BitStorageFeaturesKHR VkPhysicalDevice8BitStorageFeaturesKHR(MemoryStack stack) {
        return VkPhysicalDevice8BitStorageFeaturesKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_8BIT_STORAGE_FEATURES_KHR);
    }

    static VkPhysicalDeviceFloat16Int8FeaturesKHR VkPhysicalDeviceFloat16Int8FeaturesKHR(MemoryStack stack) {
        return VkPhysicalDeviceFloat16Int8FeaturesKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FLOAT16_INT8_FEATURES_KHR);
    }

    static VkPhysicalDeviceProperties2 VkPhysicalDeviceProperties2(MemoryStack stack) {
        return VkPhysicalDeviceProperties2.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2_KHR);
    }

    static VkPhysicalDeviceRayTracingPropertiesNV VkPhysicalDeviceRayTracingPropertiesNV(MemoryStack stack) {
        return VkPhysicalDeviceRayTracingPropertiesNV.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PROPERTIES_NV);
    }

    static VkSwapchainCreateInfoKHR VkSwapchainCreateInfoKHR(MemoryStack stack) {
        return VkSwapchainCreateInfoKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
    }

    static VkImageViewCreateInfo VkImageViewCreateInfo(MemoryStack stack) {
        return VkImageViewCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
    }

    static VkCommandPoolCreateInfo VkCommandPoolCreateInfo(MemoryStack stack) {
        return VkCommandPoolCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
    }

    static VkMemoryRequirements VkMemoryRequirements(MemoryStack stack) {
        return VkMemoryRequirements.calloc(stack);
    }

    static VkImageCreateInfo VkImageCreateInfo(MemoryStack stack) {
        return VkImageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
    }

    static VkImageMemoryBarrier.Buffer VkImageMemoryBarrier(MemoryStack stack) {
        return VkImageMemoryBarrier.calloc(1, stack).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
    }

    static VkFenceCreateInfo VkFenceCreateInfo(MemoryStack stack) {
        return VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
    }

    static VkSubmitInfo VkSubmitInfo(MemoryStack stack) {
        return VkSubmitInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
    }

    static VkCommandBufferBeginInfo VkCommandBufferBeginInfo(MemoryStack stack) {
        return VkCommandBufferBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
    }

    static VkCommandBufferAllocateInfo VkCommandBufferAllocateInfo(MemoryStack stack) {
        return VkCommandBufferAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
    }

    static VkMemoryAllocateInfo VkMemoryAllocateInfo(MemoryStack stack) {
        return VkMemoryAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
    }

    static VkBufferCreateInfo VkBufferCreateInfo(MemoryStack stack) {
        return VkBufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
    }

    static VkGeometryAABBNV VkGeometryAABBNV(VkGeometryAABBNV geometry) {
        return geometry.sType(VK_STRUCTURE_TYPE_GEOMETRY_AABB_NV);
    }

    static VkGeometryTrianglesNV VkGeometryTrianglesNV(VkGeometryTrianglesNV geometry) {
        return geometry.sType(VK_STRUCTURE_TYPE_GEOMETRY_TRIANGLES_NV);
    }

    static VkGeometryNV VkGeometryNV(MemoryStack stack) {
        return VkGeometryNV.calloc(stack).sType(VK_STRUCTURE_TYPE_GEOMETRY_NV);
    }

    static VkMemoryBarrier.Buffer VkMemoryBarrier(MemoryStack stack) {
        return VkMemoryBarrier.calloc(1, stack).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
    }

    static VkBindAccelerationStructureMemoryInfoNV.Buffer VkBindAccelerationStructureMemoryInfoNV(MemoryStack stack) {
        return VkBindAccelerationStructureMemoryInfoNV.calloc(1, stack).sType(VK_STRUCTURE_TYPE_BIND_ACCELERATION_STRUCTURE_MEMORY_INFO_NV);
    }

    static VkAccelerationStructureInfoNV VkAccelerationStructureInfoNV(MemoryStack stack) {
        return VkAccelerationStructureInfoNV.calloc(stack).sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_INFO_NV);
    }

    static VkMemoryRequirements2KHR VkMemoryRequirements2KHR(MemoryStack stack) {
        return VkMemoryRequirements2KHR.calloc(stack).sType(VK_STRUCTURE_TYPE_MEMORY_REQUIREMENTS_2_KHR);
    }

    static VkAccelerationStructureMemoryRequirementsInfoNV VkAccelerationStructureMemoryRequirementsInfoNV(MemoryStack stack) {
        return VkAccelerationStructureMemoryRequirementsInfoNV.calloc(stack).sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_MEMORY_REQUIREMENTS_INFO_NV);
    }

    static VkAccelerationStructureCreateInfoNV VkAccelerationStructureCreateInfoNV(MemoryStack stack) {
        return VkAccelerationStructureCreateInfoNV.calloc(stack).sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_NV);
    }

    static VkPipelineShaderStageCreateInfo.Buffer VkPipelineShaderStageCreateInfo(MemoryStack stack, int count) {
        VkPipelineShaderStageCreateInfo.Buffer ret = VkPipelineShaderStageCreateInfo.calloc(count, stack);
        ret.forEach(sci -> sci.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO));
        return ret;
    }

    static VkDescriptorSetLayoutBinding.Buffer VkDescriptorSetLayoutBinding(MemoryStack stack, int count) {
        return VkDescriptorSetLayoutBinding.calloc(count, stack);
    }

    static VkDescriptorSetLayoutBinding VkDescriptorSetLayoutBinding(MemoryStack stack) {
        return VkDescriptorSetLayoutBinding.calloc(stack);
    }

    static VkRayTracingPipelineCreateInfoNV.Buffer VkRayTracingPipelineCreateInfoNV(MemoryStack stack) {
        return VkRayTracingPipelineCreateInfoNV.calloc(1, stack).sType(VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_NV);
    }

    static VkRayTracingShaderGroupCreateInfoNV.Buffer VkRayTracingShaderGroupCreateInfoNV(int size, MemoryStack stack) {
        VkRayTracingShaderGroupCreateInfoNV.Buffer buf = VkRayTracingShaderGroupCreateInfoNV.calloc(size, stack);
        buf.forEach(info -> info.sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_NV).anyHitShader(VK_SHADER_UNUSED_NV)
                .closestHitShader(VK_SHADER_UNUSED_NV).generalShader(VK_SHADER_UNUSED_NV).intersectionShader(VK_SHADER_UNUSED_NV));
        return buf;
    }

    static VkPipelineLayoutCreateInfo VkPipelineLayoutCreateInfo(MemoryStack stack) {
        return VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
    }

    static VkDescriptorSetLayoutCreateInfo VkDescriptorSetLayoutCreateInfo(MemoryStack stack) {
        return VkDescriptorSetLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
    }

    static VkDescriptorBufferInfo.Buffer VkDescriptorBufferInfo(MemoryStack stack, int count) {
        return VkDescriptorBufferInfo.calloc(count, stack);
    }

    static VkDescriptorImageInfo.Buffer VkDescriptorImageInfo(MemoryStack stack, int count) {
        return VkDescriptorImageInfo.calloc(count, stack);
    }

    static VkDescriptorPoolSize.Buffer VkDescriptorPoolSize(MemoryStack stack, int count) {
        return VkDescriptorPoolSize.calloc(count, stack);
    }

    static VkWriteDescriptorSetAccelerationStructureNV VkWriteDescriptorSetAccelerationStructureNV(MemoryStack stack) {
        return VkWriteDescriptorSetAccelerationStructureNV.calloc(stack).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_NV);
    }

    static VkWriteDescriptorSet VkWriteDescriptorSet(MemoryStack stack) {
        return VkWriteDescriptorSet.calloc(stack).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
    }

    static VkDescriptorSetAllocateInfo VkDescriptorSetAllocateInfo(MemoryStack stack) {
        return VkDescriptorSetAllocateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
    }

    static VkDescriptorPoolCreateInfo VkDescriptorPoolCreateInfo(MemoryStack stack) {
        return VkDescriptorPoolCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
    }

    static VkPresentInfoKHR VkPresentInfoKHR(MemoryStack stack) {
        return VkPresentInfoKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
    }

    static VkSemaphoreCreateInfo VkSemaphoreCreateInfo(MemoryStack stack) {
        return VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
    }

    static VkSemaphoreTypeCreateInfoKHR VkSemaphoreTypeCreateInfo(MemoryStack stack) {
        return VkSemaphoreTypeCreateInfoKHR.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO_KHR);
    }

    static VkQueueFamilyProperties.Buffer VkQueueFamilyProperties(int count) {
        return VkQueueFamilyProperties.calloc(count);
    }

    static VkPhysicalDeviceFeatures VkPhysicalDeviceFeatures(MemoryStack stack) {
        return VkPhysicalDeviceFeatures.calloc(stack);
    }

    static VkPhysicalDeviceFeatures2 VkPhysicalDeviceFeatures2(MemoryStack stack) {
        return VkPhysicalDeviceFeatures2.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2_KHR);
    }

    static VkPhysicalDeviceProperties VkPhysicalDeviceProperties(MemoryStack stack) {
        return VkPhysicalDeviceProperties.calloc(stack);
    }

    static VkGeometryNV.Buffer VkGeometryNV(MemoryStack stack, int count) {
        VkGeometryNV.Buffer buf = VkGeometryNV.calloc(count, stack);
        buf.forEach(info -> info.sType(VK_STRUCTURE_TYPE_GEOMETRY_NV));
        return buf;
    }

    static VkWriteDescriptorSet.Buffer VkWriteDescriptorSet(MemoryStack stack, int count) {
        Buffer ret = VkWriteDescriptorSet.calloc(count, stack);
        ret.forEach(wds -> wds.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET));
        return ret;
    }

    static VkPipelineShaderStageCreateInfo VkPipelineShaderStageCreateInfo(MemoryStack stack) {
        return VkPipelineShaderStageCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
    }

    static VkShaderModuleCreateInfo VkShaderModuleCreateInfo(MemoryStack stack) {
        return VkShaderModuleCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
    }

    static VkSurfaceCapabilitiesKHR VkSurfaceCapabilitiesKHR(MemoryStack stack) {
        return VkSurfaceCapabilitiesKHR.calloc(stack);
    }

    static VkSurfaceFormatKHR.Buffer VkSurfaceFormatKHR(MemoryStack stack, int count) {
        return VkSurfaceFormatKHR.calloc(count, stack);
    }

    static VmaAllocationCreateInfo VmaAllocationCreateInfo(MemoryStack stack) {
        return VmaAllocationCreateInfo.calloc(stack);
    }

    static VmaAllocationInfo VmaAllocationInfo(MemoryStack stack) {
        return VmaAllocationInfo.calloc(stack);
    }

    static VkBufferCopy.Buffer VkBufferCopy(MemoryStack stack, int count) {
        return VkBufferCopy.calloc(count, stack);
    }

    static VkSamplerCreateInfo VkSamplerCreateInfo(MemoryStack stack) {
        return VkSamplerCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
    }

    static VkBufferImageCopy.Buffer VkBufferImageCopy(MemoryStack stack) {
        return VkBufferImageCopy.calloc(1, stack);
    }

    static VkImageSubresourceRange VkImageSubresourceRange(MemoryStack stack) {
        return VkImageSubresourceRange.calloc(stack);
    }

    static VkComponentMapping VkComponentMapping(MemoryStack stack) {
        return VkComponentMapping.calloc(stack);
    }

    static VkAttachmentReference VkAttachmentReference(MemoryStack stack) {
        return VkAttachmentReference.calloc(stack);
    }

    static VkAttachmentReference.Buffer VkAttachmentReference(MemoryStack stack, int count) {
        return VkAttachmentReference.calloc(count, stack);
    }

    static VkSubpassDescription.Buffer VkSubpassDescription(MemoryStack stack, int count) {
        return VkSubpassDescription.calloc(count, stack);
    }

    static VkAttachmentDescription.Buffer VkAttachmentDescription(MemoryStack stack, int count) {
        return VkAttachmentDescription.calloc(count, stack);
    }

    static VkRenderPassCreateInfo VkRenderPassCreateInfo(MemoryStack stack) {
        return VkRenderPassCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
    }

    static VkOffset3D VkOffset3D(MemoryStack stack) {
        return VkOffset3D.calloc(stack);
    }

    static VkImageBlit.Buffer VkImageBlit(MemoryStack stack, int count) {
        return VkImageBlit.calloc(count, stack);
    }

    static VkSpecializationMapEntry.Buffer VkSpecializationMapEntry(MemoryStack stack, int count) {
        return VkSpecializationMapEntry.calloc(count, stack);
    }

    static VkSpecializationInfo VkSpecializationInfo(MemoryStack stack) {
        return VkSpecializationInfo.calloc(stack);
    }

    static VkQueryPoolCreateInfo VkQueryPoolCreateInfo(MemoryStack stack) {
        return VkQueryPoolCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO);
    }

    static VkGeometryNV.Buffer VkGeometryNV(int count) {
        return VkGeometryNV.calloc(count).sType(VK_STRUCTURE_TYPE_GEOMETRY_NV);
    }

    static VkFramebufferCreateInfo VkFramebufferCreateInfo(MemoryStack stack) {
        return VkFramebufferCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
    }

    static VkVertexInputBindingDescription.Buffer VkVertexInputBindingDescription(MemoryStack stack, int count) {
        return VkVertexInputBindingDescription.calloc(count, stack);
    }

    static VkVertexInputAttributeDescription.Buffer VkVertexInputAttributeDescription(MemoryStack stack, int count) {
        return VkVertexInputAttributeDescription.calloc(count, stack);
    }

    static VkPipelineVertexInputStateCreateInfo VkPipelineVertexInputStateCreateInfo(MemoryStack stack) {
        return VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
    }

    static VkPipelineInputAssemblyStateCreateInfo VkPipelineInputAssemblyStateCreateInfo(MemoryStack stack) {
        return VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
    }

    static VkPipelineRasterizationStateCreateInfo VkPipelineRasterizationStateCreateInfo(MemoryStack stack) {
        return VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
    }

    static VkPipelineColorBlendAttachmentState.Buffer VkPipelineColorBlendAttachmentState(MemoryStack stack, int count) {
        return VkPipelineColorBlendAttachmentState.calloc(count, stack);
    }

    static VkPipelineColorBlendStateCreateInfo VkPipelineColorBlendStateCreateInfo(MemoryStack stack) {
        return VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
    }

    static VkPipelineViewportStateCreateInfo VkPipelineViewportStateCreateInfo(MemoryStack stack) {
        return VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
    }

    static VkPipelineDynamicStateCreateInfo VkPipelineDynamicStateCreateInfo(MemoryStack stack) {
        return VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);
    }

    static VkPipelineDepthStencilStateCreateInfo VkPipelineDepthStencilStateCreateInfo(MemoryStack stack) {
        return VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
    }

    static VkPipelineMultisampleStateCreateInfo VkPipelineMultisampleStateCreateInfo(MemoryStack stack) {
        return VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
    }

    static VkGraphicsPipelineCreateInfo.Buffer VkGraphicsPipelineCreateInfo(MemoryStack stack, int count) {
        VkGraphicsPipelineCreateInfo.Buffer ret = VkGraphicsPipelineCreateInfo.calloc(count, stack);
        ret.forEach(pci -> pci.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO));
        return ret;
    }

    static VkClearValue.Buffer VkClearValue(MemoryStack stack, int count) {
        return VkClearValue.calloc(count, stack);
    }

    static VkRenderPassBeginInfo VkRenderPassBeginInfo(MemoryStack stack) {
        return VkRenderPassBeginInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
    }

    static VkViewport.Buffer VkViewport(MemoryStack stack, int count) {
        return VkViewport.calloc(count, stack);
    }

    static VkRect2D.Buffer VkRect2D(MemoryStack stack, int count) {
        return VkRect2D.calloc(count, stack);
    }

    static VkFormatProperties VkFormatProperties(MemoryStack stack) {
        return VkFormatProperties.calloc(stack);
    }

    static VkSubpassDependency.Buffer VkSubpassDependency(MemoryStack stack, int count) {
        return VkSubpassDependency.calloc(count, stack);
    }

    static VkImageCopy.Buffer VkImageCopy(MemoryStack stack, int count) {
        return VkImageCopy.calloc(count, stack);
    }

}
