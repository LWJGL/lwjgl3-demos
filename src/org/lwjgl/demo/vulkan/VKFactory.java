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
        return VkInstanceCreateInfo.calloc(stack).sType$Default();
    }

    static VkApplicationInfo VkApplicationInfo(MemoryStack stack) {
        return VkApplicationInfo.calloc(stack).sType$Default();
    }

    static VkDebugReportCallbackCreateInfoEXT VkDebugReportCallbackCreateInfoEXT(MemoryStack stack) {
        return VkDebugReportCallbackCreateInfoEXT.calloc(stack).sType$Default();
    }

    static VkDebugUtilsMessengerCreateInfoEXT VkDebugUtilsMessengerCreateInfoEXT(MemoryStack stack) {
        return VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default();
    }

    static VkDeviceCreateInfo VkDeviceCreateInfo(MemoryStack stack) {
        return VkDeviceCreateInfo.calloc(stack).sType$Default();
    }

    static VkDeviceQueueCreateInfo.Buffer VkDeviceQueueCreateInfo(MemoryStack stack) {
        return VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default();
    }

    static VkPhysicalDevice8BitStorageFeaturesKHR VkPhysicalDevice8BitStorageFeaturesKHR(MemoryStack stack) {
        return VkPhysicalDevice8BitStorageFeaturesKHR.calloc(stack).sType$Default();
    }

    static VkPhysicalDeviceFloat16Int8FeaturesKHR VkPhysicalDeviceFloat16Int8FeaturesKHR(MemoryStack stack) {
        return VkPhysicalDeviceFloat16Int8FeaturesKHR.calloc(stack).sType$Default();
    }

    static VkPhysicalDeviceProperties2 VkPhysicalDeviceProperties2(MemoryStack stack) {
        return VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
    }

    static VkPhysicalDeviceRayTracingPropertiesNV VkPhysicalDeviceRayTracingPropertiesNV(MemoryStack stack) {
        return VkPhysicalDeviceRayTracingPropertiesNV.calloc(stack).sType$Default();
    }

    static VkSwapchainCreateInfoKHR VkSwapchainCreateInfoKHR(MemoryStack stack) {
        return VkSwapchainCreateInfoKHR.calloc(stack).sType$Default();
    }

    static VkImageViewCreateInfo VkImageViewCreateInfo(MemoryStack stack) {
        return VkImageViewCreateInfo.calloc(stack).sType$Default();
    }

    static VkCommandPoolCreateInfo VkCommandPoolCreateInfo(MemoryStack stack) {
        return VkCommandPoolCreateInfo.calloc(stack).sType$Default();
    }

    static VkMemoryRequirements VkMemoryRequirements(MemoryStack stack) {
        return VkMemoryRequirements.calloc(stack);
    }

    static VkImageCreateInfo VkImageCreateInfo(MemoryStack stack) {
        return VkImageCreateInfo.calloc(stack).sType$Default();
    }

    static VkImageMemoryBarrier.Buffer VkImageMemoryBarrier(MemoryStack stack) {
        return VkImageMemoryBarrier.calloc(1, stack).sType$Default();
    }

    static VkFenceCreateInfo VkFenceCreateInfo(MemoryStack stack) {
        return VkFenceCreateInfo.calloc(stack).sType$Default();
    }

    static VkSubmitInfo VkSubmitInfo(MemoryStack stack) {
        return VkSubmitInfo.calloc(stack).sType$Default();
    }

    static VkCommandBufferBeginInfo VkCommandBufferBeginInfo(MemoryStack stack) {
        return VkCommandBufferBeginInfo.calloc(stack).sType$Default();
    }

    static VkCommandBufferAllocateInfo VkCommandBufferAllocateInfo(MemoryStack stack) {
        return VkCommandBufferAllocateInfo.calloc(stack).sType$Default();
    }

    static VkMemoryAllocateInfo VkMemoryAllocateInfo(MemoryStack stack) {
        return VkMemoryAllocateInfo.calloc(stack).sType$Default();
    }

    static VkBufferCreateInfo VkBufferCreateInfo(MemoryStack stack) {
        return VkBufferCreateInfo.calloc(stack).sType$Default();
    }

    static VkGeometryAABBNV VkGeometryAABBNV(VkGeometryAABBNV geometry) {
        return geometry.sType$Default();
    }

    static VkGeometryTrianglesNV VkGeometryTrianglesNV(VkGeometryTrianglesNV geometry) {
        return geometry.sType$Default();
    }

    static VkGeometryNV VkGeometryNV(MemoryStack stack) {
        return VkGeometryNV.calloc(stack).sType$Default();
    }

    static VkMemoryBarrier.Buffer VkMemoryBarrier(MemoryStack stack) {
        return VkMemoryBarrier.calloc(1, stack).sType$Default();
    }

    static VkBindAccelerationStructureMemoryInfoNV.Buffer VkBindAccelerationStructureMemoryInfoNV(MemoryStack stack) {
        return VkBindAccelerationStructureMemoryInfoNV.calloc(1, stack).sType$Default();
    }

    static VkAccelerationStructureInfoNV VkAccelerationStructureInfoNV(MemoryStack stack) {
        return VkAccelerationStructureInfoNV.calloc(stack).sType$Default();
    }

    static VkMemoryRequirements2KHR VkMemoryRequirements2KHR(MemoryStack stack) {
        return VkMemoryRequirements2KHR.calloc(stack).sType$Default();
    }

    static VkAccelerationStructureMemoryRequirementsInfoNV VkAccelerationStructureMemoryRequirementsInfoNV(MemoryStack stack) {
        return VkAccelerationStructureMemoryRequirementsInfoNV.calloc(stack).sType$Default();
    }

    static VkAccelerationStructureCreateInfoNV VkAccelerationStructureCreateInfoNV(MemoryStack stack) {
        return VkAccelerationStructureCreateInfoNV.calloc(stack).sType$Default();
    }

    static VkPipelineShaderStageCreateInfo.Buffer VkPipelineShaderStageCreateInfo(MemoryStack stack, int count) {
        VkPipelineShaderStageCreateInfo.Buffer ret = VkPipelineShaderStageCreateInfo.calloc(count, stack);
        ret.forEach(sci -> sci.sType$Default());
        return ret;
    }

    static VkDescriptorSetLayoutBinding.Buffer VkDescriptorSetLayoutBinding(MemoryStack stack, int count) {
        return VkDescriptorSetLayoutBinding.calloc(count, stack);
    }

    static VkDescriptorSetLayoutBinding VkDescriptorSetLayoutBinding(MemoryStack stack) {
        return VkDescriptorSetLayoutBinding.calloc(stack);
    }

    static VkRayTracingPipelineCreateInfoNV.Buffer VkRayTracingPipelineCreateInfoNV(MemoryStack stack) {
        return VkRayTracingPipelineCreateInfoNV.calloc(1, stack).sType$Default();
    }

    static VkRayTracingShaderGroupCreateInfoNV.Buffer VkRayTracingShaderGroupCreateInfoNV(int size, MemoryStack stack) {
        VkRayTracingShaderGroupCreateInfoNV.Buffer buf = VkRayTracingShaderGroupCreateInfoNV.calloc(size, stack);
        buf.forEach(info -> info.sType$Default().anyHitShader(VK_SHADER_UNUSED_NV)
                .closestHitShader(VK_SHADER_UNUSED_NV).generalShader(VK_SHADER_UNUSED_NV).intersectionShader(VK_SHADER_UNUSED_NV));
        return buf;
    }

    static VkPipelineLayoutCreateInfo VkPipelineLayoutCreateInfo(MemoryStack stack) {
        return VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
    }

    static VkDescriptorSetLayoutCreateInfo VkDescriptorSetLayoutCreateInfo(MemoryStack stack) {
        return VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default();
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
        return VkWriteDescriptorSetAccelerationStructureNV.calloc(stack).sType$Default();
    }

    static VkWriteDescriptorSet VkWriteDescriptorSet(MemoryStack stack) {
        return VkWriteDescriptorSet.calloc(stack).sType$Default();
    }

    static VkDescriptorSetAllocateInfo VkDescriptorSetAllocateInfo(MemoryStack stack) {
        return VkDescriptorSetAllocateInfo.calloc(stack).sType$Default();
    }

    static VkDescriptorPoolCreateInfo VkDescriptorPoolCreateInfo(MemoryStack stack) {
        return VkDescriptorPoolCreateInfo.calloc(stack).sType$Default();
    }

    static VkPresentInfoKHR VkPresentInfoKHR(MemoryStack stack) {
        return VkPresentInfoKHR.calloc(stack).sType$Default();
    }

    static VkSemaphoreCreateInfo VkSemaphoreCreateInfo(MemoryStack stack) {
        return VkSemaphoreCreateInfo.calloc(stack).sType$Default();
    }

    static VkSemaphoreTypeCreateInfoKHR VkSemaphoreTypeCreateInfo(MemoryStack stack) {
        return VkSemaphoreTypeCreateInfoKHR.calloc(stack).sType$Default();
    }

    static VkQueueFamilyProperties.Buffer VkQueueFamilyProperties(int count) {
        return VkQueueFamilyProperties.calloc(count);
    }

    static VkPhysicalDeviceFeatures VkPhysicalDeviceFeatures(MemoryStack stack) {
        return VkPhysicalDeviceFeatures.calloc(stack);
    }

    static VkPhysicalDeviceFeatures2 VkPhysicalDeviceFeatures2(MemoryStack stack) {
        return VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
    }

    static VkPhysicalDeviceProperties VkPhysicalDeviceProperties(MemoryStack stack) {
        return VkPhysicalDeviceProperties.calloc(stack);
    }

    static VkGeometryNV.Buffer VkGeometryNV(MemoryStack stack, int count) {
        VkGeometryNV.Buffer buf = VkGeometryNV.calloc(count, stack);
        buf.forEach(info -> info.sType$Default());
        return buf;
    }

    static VkWriteDescriptorSet.Buffer VkWriteDescriptorSet(MemoryStack stack, int count) {
        Buffer ret = VkWriteDescriptorSet.calloc(count, stack);
        ret.forEach(wds -> wds.sType$Default());
        return ret;
    }

    static VkPipelineShaderStageCreateInfo VkPipelineShaderStageCreateInfo(MemoryStack stack) {
        return VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default();
    }

    static VkShaderModuleCreateInfo VkShaderModuleCreateInfo(MemoryStack stack) {
        return VkShaderModuleCreateInfo.calloc(stack).sType$Default();
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
        return VkSamplerCreateInfo.calloc(stack).sType$Default();
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
        return VkRenderPassCreateInfo.calloc(stack).sType$Default();
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
        return VkQueryPoolCreateInfo.calloc(stack).sType$Default();
    }

    static VkGeometryNV.Buffer VkGeometryNV(int count) {
        return VkGeometryNV.calloc(count).sType$Default();
    }

    static VkFramebufferCreateInfo VkFramebufferCreateInfo(MemoryStack stack) {
        return VkFramebufferCreateInfo.calloc(stack).sType$Default();
    }

    static VkVertexInputBindingDescription.Buffer VkVertexInputBindingDescription(MemoryStack stack, int count) {
        return VkVertexInputBindingDescription.calloc(count, stack);
    }

    static VkVertexInputAttributeDescription.Buffer VkVertexInputAttributeDescription(MemoryStack stack, int count) {
        return VkVertexInputAttributeDescription.calloc(count, stack);
    }

    static VkPipelineVertexInputStateCreateInfo VkPipelineVertexInputStateCreateInfo(MemoryStack stack) {
        return VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
    }

    static VkPipelineInputAssemblyStateCreateInfo VkPipelineInputAssemblyStateCreateInfo(MemoryStack stack) {
        return VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default();
    }

    static VkPipelineRasterizationStateCreateInfo VkPipelineRasterizationStateCreateInfo(MemoryStack stack) {
        return VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default();
    }

    static VkPipelineColorBlendAttachmentState.Buffer VkPipelineColorBlendAttachmentState(MemoryStack stack, int count) {
        return VkPipelineColorBlendAttachmentState.calloc(count, stack);
    }

    static VkPipelineColorBlendStateCreateInfo VkPipelineColorBlendStateCreateInfo(MemoryStack stack) {
        return VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
    }

    static VkPipelineViewportStateCreateInfo VkPipelineViewportStateCreateInfo(MemoryStack stack) {
        return VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default();
    }

    static VkPipelineDynamicStateCreateInfo VkPipelineDynamicStateCreateInfo(MemoryStack stack) {
        return VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default();
    }

    static VkPipelineDepthStencilStateCreateInfo VkPipelineDepthStencilStateCreateInfo(MemoryStack stack) {
        return VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
    }

    static VkPipelineMultisampleStateCreateInfo VkPipelineMultisampleStateCreateInfo(MemoryStack stack) {
        return VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default();
    }

    static VkGraphicsPipelineCreateInfo.Buffer VkGraphicsPipelineCreateInfo(MemoryStack stack, int count) {
        VkGraphicsPipelineCreateInfo.Buffer ret = VkGraphicsPipelineCreateInfo.calloc(count, stack);
        ret.forEach(pci -> pci.sType$Default());
        return ret;
    }

    static VkClearValue.Buffer VkClearValue(MemoryStack stack, int count) {
        return VkClearValue.calloc(count, stack);
    }

    static VkRenderPassBeginInfo VkRenderPassBeginInfo(MemoryStack stack) {
        return VkRenderPassBeginInfo.calloc(stack).sType$Default();
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
