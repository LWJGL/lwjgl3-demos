package org.lwjgl.demo.vulkan;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTDebugReport.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.KHRWin32Surface.*;
import static org.lwjgl.vulkan.KHRXlibSurface.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.demo.vulkan.VkUtil.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.Version;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.system.Platform;
import org.lwjgl.system.MemoryUtil.BufferAllocator;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComponentMapping;
import org.lwjgl.vulkan.VkDebugReportCallbackCreateInfoEXT;
import org.lwjgl.vulkan.VkDebugReportCallbackEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkViewport;

/**
 * Renders a simple cornflower blue image on an SWT Canvas with Vulkan.
 * 
 * @author Kai Burjack
 */
public class SWTVulkanCompleteDemo {

    private static ByteBuffer[] layers = {
            memEncodeASCII("VK_LAYER_LUNARG_threading", BufferAllocator.MALLOC),
            memEncodeASCII("VK_LAYER_LUNARG_mem_tracker", BufferAllocator.MALLOC),
            memEncodeASCII("VK_LAYER_LUNARG_object_tracker", BufferAllocator.MALLOC),
            memEncodeASCII("VK_LAYER_LUNARG_draw_state", BufferAllocator.MALLOC),
            memEncodeASCII("VK_LAYER_LUNARG_param_checker", BufferAllocator.MALLOC),
            memEncodeASCII("VK_LAYER_LUNARG_swapchain", BufferAllocator.MALLOC),
            memEncodeASCII("VK_LAYER_LUNARG_device_limits", BufferAllocator.MALLOC),
            memEncodeASCII("VK_LAYER_LUNARG_image", BufferAllocator.MALLOC)
    };

    /**
     * Create a Vulkan instance using LWJGL 3.
     * 
     * @return the VkInstance handle
     */
    private static VkInstance createInstance() {
        VkApplicationInfo appInfo = VkApplicationInfo.calloc();
        appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
        appInfo.pApplicationName("SWT Vulkan Demo");
        appInfo.pEngineName("");
        appInfo.apiVersion(VK_MAKE_VERSION(1, 0, 3));
        PointerBuffer ppEnabledExtensionNames = memAllocPointer(2);
        ByteBuffer VK_KHR_SURFACE_EXTENSION;
        if (Platform.get() == Platform.WINDOWS)
            VK_KHR_SURFACE_EXTENSION = memEncodeASCII(VK_KHR_WIN32_SURFACE_EXTENSION_NAME, BufferAllocator.MALLOC);
        else
            VK_KHR_SURFACE_EXTENSION = memEncodeASCII(VK_KHR_XLIB_SURFACE_EXTENSION_NAME, BufferAllocator.MALLOC);
        ppEnabledExtensionNames.put(VK_KHR_SURFACE_EXTENSION);
        ByteBuffer VK_EXT_DEBUG_REPORT_EXTENSION = memEncodeASCII(VK_EXT_DEBUG_REPORT_EXTENSION_NAME, BufferAllocator.MALLOC);
        ppEnabledExtensionNames.put(VK_EXT_DEBUG_REPORT_EXTENSION);
        ppEnabledExtensionNames.flip();
        PointerBuffer ppEnabledLayerNames = memAllocPointer(layers.length);
        for (int i = 0; i < layers.length; i++)
            ppEnabledLayerNames.put(layers[i]);
        ppEnabledLayerNames.flip();
        VkInstanceCreateInfo pCreateInfo = VkInstanceCreateInfo.calloc();
        pCreateInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
        pCreateInfo.pNext(NULL);
        pCreateInfo.pApplicationInfo(appInfo);
        pCreateInfo.enabledExtensionCount(ppEnabledExtensionNames.remaining());
        pCreateInfo.ppEnabledExtensionNames(ppEnabledExtensionNames);
        pCreateInfo.enabledLayerCount(ppEnabledLayerNames.remaining());
        pCreateInfo.ppEnabledLayerNames(ppEnabledLayerNames);
        PointerBuffer pInstance = memAllocPointer(1);
        int err = vkCreateInstance(pCreateInfo, null, pInstance);
        long instance = pInstance.get(0);
        memFree(pInstance);
        pCreateInfo.free();
        memFree(VK_EXT_DEBUG_REPORT_EXTENSION);
        memFree(VK_KHR_SURFACE_EXTENSION);
        memFree(ppEnabledExtensionNames);
        appInfo.free();
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to create VkInstance: " + translateVulkanError(err));
        }
        return new VkInstance(instance);
    }

    private static long setupDebugging(VkInstance instance, int flags, VkDebugReportCallbackEXT callback) {
        VkDebugReportCallbackCreateInfoEXT dbgCreateInfo = VkDebugReportCallbackCreateInfoEXT.calloc();
        dbgCreateInfo.sType(VK_STRUCTURE_TYPE_DEBUG_REPORT_CREATE_INFO_EXT);
        dbgCreateInfo.pNext(NULL);
        dbgCreateInfo.pfnCallback(callback);
        dbgCreateInfo.pUserData(NULL);
        dbgCreateInfo.flags(flags);
        LongBuffer pCallback = memAllocLong(1);
        int err = vkCreateDebugReportCallbackEXT(instance, dbgCreateInfo, null, pCallback);
        memFree(pCallback);
        dbgCreateInfo.free();
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to create VkInstance: " + translateVulkanError(err));
        }
        return pCallback.get(0);
    }

    private static VkPhysicalDevice getFirstPhysicalDevice(VkInstance instance) {
        IntBuffer pPhysicalDeviceCount = memAllocInt(1);
        int err = vkEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, null);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to get number of physical devices: " + translateVulkanError(err));
        }
        PointerBuffer pPhysicalDevices = memAllocPointer(pPhysicalDeviceCount.get(0));
        err = vkEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, pPhysicalDevices);
        long physicalDevice = pPhysicalDevices.get(0);
        memFree(pPhysicalDeviceCount);
        memFree(pPhysicalDevices);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to get physical devices: " + translateVulkanError(err));
        }
        return new VkPhysicalDevice(physicalDevice, instance);
    }

    private static class DeviceAndGraphicsQueueFamily {
        VkDevice device;
        int queueFamilyIndex;
    }

    private static DeviceAndGraphicsQueueFamily createDeviceAndGetGraphicsQueueFamily(VkPhysicalDevice physicalDevice) {
        IntBuffer pQueueFamilyPropertyCount = memAllocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, null);
        int queueCount = pQueueFamilyPropertyCount.get(0);
        VkQueueFamilyProperties.Buffer queueProps = VkQueueFamilyProperties.calloc(queueCount);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, queueProps);
        memFree(pQueueFamilyPropertyCount);
        int graphicsQueueFamilyIndex;
        for (graphicsQueueFamilyIndex = 0; graphicsQueueFamilyIndex < queueCount; graphicsQueueFamilyIndex++) {
            if ((queueProps.get(graphicsQueueFamilyIndex).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0)
                break;
        }
        queueProps.free();
        FloatBuffer pQueuePriorities = memAllocFloat(1).put(0.0f);
        pQueuePriorities.flip();
        VkDeviceQueueCreateInfo.Buffer queueCreateInfo = VkDeviceQueueCreateInfo.calloc(1);
        queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
        queueCreateInfo.queueFamilyIndex(graphicsQueueFamilyIndex);
        queueCreateInfo.queueCount(1);
        queueCreateInfo.pQueuePriorities(pQueuePriorities);

        PointerBuffer extensions = memAllocPointer(2);
        ByteBuffer VK_KHR_SWAPCHAIN_EXTENSION = memEncodeASCII(VK_KHR_SWAPCHAIN_EXTENSION_NAME, BufferAllocator.MALLOC);
        extensions.put(VK_KHR_SWAPCHAIN_EXTENSION);
        extensions.flip();
        PointerBuffer ppEnabledLayerNames = memAllocPointer(layers.length);
        for (int i = 0; i < layers.length; i++)
            ppEnabledLayerNames.put(layers[i]);
        ppEnabledLayerNames.flip();

        VkDeviceCreateInfo deviceCreateInfo = VkDeviceCreateInfo.calloc();
        deviceCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
        deviceCreateInfo.pNext(NULL);
        deviceCreateInfo.queueCreateInfoCount(1);
        deviceCreateInfo.pQueueCreateInfos(queueCreateInfo);
        deviceCreateInfo.enabledExtensionCount(1);
        deviceCreateInfo.ppEnabledExtensionNames(extensions);
        deviceCreateInfo.enabledLayerCount(ppEnabledLayerNames.remaining());
        deviceCreateInfo.ppEnabledLayerNames(ppEnabledLayerNames);

        PointerBuffer pDevice = memAllocPointer(1);
        int err = vkCreateDevice(physicalDevice, deviceCreateInfo, null, pDevice);
        long device = pDevice.get(0);
        memFree(pDevice);
        queueCreateInfo.free();
        deviceCreateInfo.free();
        memFree(extensions);
        memFree(pQueuePriorities);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to create device: " + translateVulkanError(err));
        }
        DeviceAndGraphicsQueueFamily ret = new DeviceAndGraphicsQueueFamily();
        ret.device = new VkDevice(device, physicalDevice);
        ret.queueFamilyIndex = graphicsQueueFamilyIndex;
        return ret;
    }

    private static class ColorFormatAndSpace {
        int colorFormat;
        int colorSpace;
    }

    private static ColorFormatAndSpace getColorFormatAndSpace(VkPhysicalDevice physicalDevice, long surface) {
        IntBuffer pQueueFamilyPropertyCount = memAllocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, null);
        int queueCount = pQueueFamilyPropertyCount.get(0);
        VkQueueFamilyProperties.Buffer queueProps = VkQueueFamilyProperties.calloc(queueCount);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, queueProps);

        // Iterate over each queue to learn whether it supports presenting:
        IntBuffer supportsPresent = memAllocInt(queueCount);
        for (int i = 0; i < queueCount; i++) {
            supportsPresent.position(i);
            int err = vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, i, surface, supportsPresent);
            if (err != VK_SUCCESS) {
                throw new AssertionError("Failed to physical device surface support: " + translateVulkanError(err));
            }
        }

        // Search for a graphics and a present queue in the array of queue families, try to find one that supports both
        int graphicsQueueNodeIndex = Integer.MAX_VALUE;
        int presentQueueNodeIndex = Integer.MAX_VALUE;
        for (int i = 0; i < queueCount; i++) {
            if ((queueProps.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                if (graphicsQueueNodeIndex == Integer.MAX_VALUE) {
                    graphicsQueueNodeIndex = i;
                }
                if (supportsPresent.get(i) == VK_TRUE) {
                    graphicsQueueNodeIndex = i;
                    presentQueueNodeIndex = i;
                    break;
                }
            }
        }
        if (presentQueueNodeIndex == Integer.MAX_VALUE) {
            // If there's no queue that supports both present and graphics try to find a separate present queue
            for (int i = 0; i < queueCount; ++i) {
                if (supportsPresent.get(i) == VK_TRUE) {
                    presentQueueNodeIndex = i;
                    break;
                }
            }
        }
        memFree(supportsPresent);

        // Generate error if could not find both a graphics and a present queue
        if (graphicsQueueNodeIndex == Integer.MAX_VALUE) {
            throw new AssertionError("No graphics queue found");
        }
        if (presentQueueNodeIndex == Integer.MAX_VALUE) {
            throw new AssertionError("No presentation queue found");
        }
        if (graphicsQueueNodeIndex != presentQueueNodeIndex) {
            throw new AssertionError("Presentation queue != graphics queue");
        }

        // Get list of supported formats
        IntBuffer pFormatCount = memAllocInt(1);
        int err = vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pFormatCount, null);
        int formatCount = pFormatCount.get(0);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to query number of physical device surface formats: " + translateVulkanError(err));
        }

        VkSurfaceFormatKHR.Buffer surfFormats = VkSurfaceFormatKHR.calloc(formatCount);
        err = vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pFormatCount, surfFormats);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to query physical device surface formats: " + translateVulkanError(err));
        }

        // If the format list includes just one entry of VK_FORMAT_UNDEFINED, the surface has no preferred format. Otherwise, at least one supported format will
        // be returned.
        int colorFormat;
        if (formatCount == 1 && surfFormats.get(0).format() == VK_FORMAT_UNDEFINED) {
            colorFormat = VK_FORMAT_B8G8R8A8_UNORM;
        } else {
            colorFormat = surfFormats.get(0).format();
        }
        int colorSpace = surfFormats.get(0).colorSpace();

        ColorFormatAndSpace ret = new ColorFormatAndSpace();
        ret.colorFormat = colorFormat;
        ret.colorSpace = colorSpace;
        return ret;
    }

    private static long createCommandPool(VkDevice device, int queueNodeIndex) {
        VkCommandPoolCreateInfo cmdPoolInfo = VkCommandPoolCreateInfo.calloc();
        cmdPoolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
        cmdPoolInfo.queueFamilyIndex(queueNodeIndex);
        cmdPoolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
        LongBuffer pCmdPool = memAllocLong(1);
        int err = vkCreateCommandPool(device, cmdPoolInfo, null, pCmdPool);
        long commandPool = pCmdPool.get(0);
        cmdPoolInfo.free();
        memFree(pCmdPool);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to create command pool: " + translateVulkanError(err));
        }
        return commandPool;
    }

    private static VkQueue createDeviceQueue(VkDevice device, int queueFamilyIndex) {
        PointerBuffer pQueue = memAllocPointer(1);
        vkGetDeviceQueue(device, queueFamilyIndex, 0, pQueue);
        long queue = pQueue.get(0);
        memFree(pQueue);
        return new VkQueue(queue, device);
    }

    private static VkCommandBuffer createCommandBuffer(VkDevice device, long commandPool) {
        VkCommandBufferAllocateInfo cmdBufAllocateInfo = VkCommandBufferAllocateInfo.calloc();
        cmdBufAllocateInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        cmdBufAllocateInfo.commandPool(commandPool);
        cmdBufAllocateInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
        cmdBufAllocateInfo.commandBufferCount(1);
        PointerBuffer pCommandBuffer = memAllocPointer(1);
        int err = vkAllocateCommandBuffers(device, cmdBufAllocateInfo, pCommandBuffer);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to allocate command buffer: " + translateVulkanError(err));
        }
        VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);
        cmdBufAllocateInfo.free();
        memFree(pCommandBuffer);
        return commandBuffer;
    }

    private static void imageBarrier(VkCommandBuffer cmdbuffer, long image, int aspectMask, int oldImageLayout, int newImageLayout) {
        // Create an image barrier object
        VkImageMemoryBarrier.Buffer imageMemoryBarrier = VkImageMemoryBarrier.calloc(1);
        imageMemoryBarrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        imageMemoryBarrier.pNext(NULL);
        imageMemoryBarrier.oldLayout(oldImageLayout);
        imageMemoryBarrier.newLayout(newImageLayout);
        imageMemoryBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        imageMemoryBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        imageMemoryBarrier.image(image);
        VkImageSubresourceRange subresourceRange = imageMemoryBarrier.subresourceRange();
        subresourceRange.aspectMask(aspectMask);
        subresourceRange.baseMipLevel(0);
        subresourceRange.levelCount(1);
        subresourceRange.layerCount(1);

        // Source layouts (old)

        // Undefined layout
        // Only allowed as initial layout!
        // Make sure any writes to the image have been finished
        if (oldImageLayout == VK_IMAGE_LAYOUT_UNDEFINED) {
            //imageMemoryBarrier.srcAccessMask(VK_ACCESS_HOST_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            imageMemoryBarrier.srcAccessMask(0);// <- validation layer tells that this must be 0
        }

        // Old layout is color attachment
        // Make sure any writes to the color buffer have been finished
        if (oldImageLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
            imageMemoryBarrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        }

        // Old layout is transfer source
        // Make sure any reads from the image have been finished
        if (oldImageLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
            imageMemoryBarrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
        }

        // Old layout is shader read (sampler, input attachment)
        // Make sure any shader reads from the image have been finished
        if (oldImageLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            imageMemoryBarrier.srcAccessMask(VK_ACCESS_SHADER_READ_BIT);
        }

        // Target layouts (new)

        // New layout is transfer destination (copy, blit)
        // Make sure any copyies to the image have been finished
        if (newImageLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            imageMemoryBarrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
        }

        // New layout is transfer source (copy, blit)
        // Make sure any reads from and writes to the image have been finished
        if (newImageLayout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
            imageMemoryBarrier.srcAccessMask(imageMemoryBarrier.srcAccessMask() | VK_ACCESS_TRANSFER_READ_BIT);
            imageMemoryBarrier.dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
        }

        // New layout is color attachment
        // Make sure any writes to the color buffer hav been finished
        if (newImageLayout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
            imageMemoryBarrier.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            imageMemoryBarrier.srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
        }

        // New layout is depth attachment
        // Make sure any writes to depth/stencil buffer have been finished
        if (newImageLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
            imageMemoryBarrier.dstAccessMask(imageMemoryBarrier.dstAccessMask() | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        }

        // New layout is shader read (sampler, input attachment)
        // Make sure any writes to the image have been finished
        if (newImageLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            imageMemoryBarrier.srcAccessMask(VK_ACCESS_HOST_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
            imageMemoryBarrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
        }

        // Put barrier on top
        int srcStageFlags = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        int destStageFlags = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;

        // Put barrier inside setup command buffer
        vkCmdPipelineBarrier(cmdbuffer, srcStageFlags, destStageFlags, VK_FLAGS_NONE,
                null, // no memory barriers
                null, // no buffer memory barriers
                imageMemoryBarrier); // one image memory barrier
        imageMemoryBarrier.free();
    }

    private static class Swapchain {
        long swapchainHandle;
        long[] images;
        long[] imageViews;
    }

    private static Swapchain createSwapChain(VkDevice device, VkPhysicalDevice physicalDevice, long surface, long oldSwapChain, VkCommandBuffer commandBuffer, int width,
            int height, int colorFormat, int colorSpace) {
        int err;
        // Get physical device surface properties and formats
        VkSurfaceCapabilitiesKHR surfCaps = VkSurfaceCapabilitiesKHR.calloc();
        err = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, surfCaps);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to get physical device surface capabilities: " + translateVulkanError(err));
        }

        IntBuffer pPresentModeCount = memAllocInt(1);
        err = vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pPresentModeCount, null);
        int presentModeCount = pPresentModeCount.get(0);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to get number of physical device surface presentation modes: " + translateVulkanError(err));
        }

        IntBuffer pPresentModes = memAllocInt(presentModeCount);
        err = vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pPresentModeCount, pPresentModes);
        memFree(pPresentModeCount);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to get physical device surface presentation modes: " + translateVulkanError(err));
        }

        // Try to use mailbox mode. Low latency and non-tearing
        int swapchainPresentMode = VK_PRESENT_MODE_FIFO_KHR;
        for (int i = 0; i < presentModeCount; i++) {
            if (pPresentModes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                swapchainPresentMode = VK_PRESENT_MODE_MAILBOX_KHR;
                break;
            }
            if ((swapchainPresentMode != VK_PRESENT_MODE_MAILBOX_KHR) && (pPresentModes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR)) {
                swapchainPresentMode = VK_PRESENT_MODE_IMMEDIATE_KHR;
            }
        }
        memFree(pPresentModes);

        // Determine the number of images
        int desiredNumberOfSwapchainImages = surfCaps.minImageCount() + 1;
        if ((surfCaps.maxImageCount() > 0) && (desiredNumberOfSwapchainImages > surfCaps.maxImageCount())) {
            desiredNumberOfSwapchainImages = surfCaps.maxImageCount();
        }

        int preTransform;
        if ((surfCaps.supportedTransforms() & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0) {
            preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
        } else {
            preTransform = surfCaps.currentTransform();
        }
        surfCaps.free();

        VkSwapchainCreateInfoKHR swapchainCI = VkSwapchainCreateInfoKHR.calloc();
        swapchainCI.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
        swapchainCI.pNext(NULL);
        swapchainCI.surface(surface);
        swapchainCI.minImageCount(desiredNumberOfSwapchainImages);
        swapchainCI.imageFormat(colorFormat);
        swapchainCI.imageColorSpace(colorSpace);
        VkExtent2D swapchainExtent = swapchainCI.imageExtent();
        swapchainExtent.width(width);
        swapchainExtent.height(height);
        swapchainCI.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        swapchainCI.preTransform(preTransform);
        swapchainCI.imageArrayLayers(1);
        swapchainCI.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
        swapchainCI.queueFamilyIndexCount(0);
        swapchainCI.pQueueFamilyIndices(null);
        swapchainCI.presentMode(swapchainPresentMode);
        swapchainCI.oldSwapchain(oldSwapChain);
        swapchainCI.clipped(VK_TRUE);
        swapchainCI.compositeAlpha(0);

        LongBuffer pSwapChain = memAllocLong(1);
        err = vkCreateSwapchainKHR(device, swapchainCI, null, pSwapChain);
        swapchainCI.free();
        long swapChain = pSwapChain.get(0);
        memFree(pSwapChain);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to create swap chain: " + translateVulkanError(err));
        }

        // If we just re-created an existing swapchain, we should destroy the old swapchain at this point.
        // Note: destroying the swapchain also cleans up all its associated presentable images once the platform is done with them.
        if (oldSwapChain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device, oldSwapChain, null);
        }

        IntBuffer pImageCount = memAllocInt(1);
        err = vkGetSwapchainImagesKHR(device, swapChain, pImageCount, null);
        int imageCount = pImageCount.get(0);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to get number of swapchain images: " + translateVulkanError(err));
        }

        LongBuffer pSwapchainImages = memAllocLong(imageCount);
        err = vkGetSwapchainImagesKHR(device, swapChain, pImageCount, pSwapchainImages);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to get swapchain images: " + translateVulkanError(err));
        }
        memFree(pImageCount);

        long[] images = new long[imageCount];
        long[] imageViews = new long[imageCount];
        LongBuffer pBufferView = memAllocLong(1);
        VkImageViewCreateInfo colorAttachmentView = VkImageViewCreateInfo.calloc();
        colorAttachmentView.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
        colorAttachmentView.pNext(NULL);
        colorAttachmentView.format(colorFormat);
        VkComponentMapping components = colorAttachmentView.components();
        components.r(VK_COMPONENT_SWIZZLE_R);
        components.g(VK_COMPONENT_SWIZZLE_G);
        components.b(VK_COMPONENT_SWIZZLE_B);
        components.a(VK_COMPONENT_SWIZZLE_A);
        VkImageSubresourceRange subresourceRange = colorAttachmentView.subresourceRange();
        subresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        subresourceRange.baseMipLevel(0);
        subresourceRange.levelCount(1);
        subresourceRange.baseArrayLayer(0);
        subresourceRange.layerCount(1);
        colorAttachmentView.viewType(VK_IMAGE_VIEW_TYPE_2D);
        colorAttachmentView.flags(VK_FLAGS_NONE);
        for (int i = 0; i < imageCount; i++) {
            images[i] = pSwapchainImages.get(i);
            // Bring the image from an UNDEFINED state to the PRESENT_SRC state
            imageBarrier(commandBuffer, images[i], VK_IMAGE_ASPECT_COLOR_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            colorAttachmentView.image(images[i]);
            err = vkCreateImageView(device, colorAttachmentView, null, pBufferView);
            imageViews[i] = pBufferView.get(0);
            if (err != VK_SUCCESS) {
                throw new AssertionError("Failed to create image view: " + translateVulkanError(err));
            }
        }
        colorAttachmentView.free();
        memFree(pBufferView);
        memFree(pSwapchainImages);

        Swapchain ret = new Swapchain();
        ret.images = images;
        ret.imageViews = imageViews;
        ret.swapchainHandle = swapChain;
        return ret;
    }

    private static long createClearRenderPass(VkDevice device, int colorFormat) {
        VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(1);
        attachments.format(colorFormat);
        attachments.samples(VK_SAMPLE_COUNT_1_BIT);
        attachments.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
        attachments.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        attachments.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
        attachments.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
        attachments.initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        attachments.finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        VkAttachmentReference.Buffer colorReference = VkAttachmentReference.calloc(1);
        colorReference.attachment(0);
        colorReference.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1);
        subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);
        subpass.flags(VK_FLAGS_NONE);
        subpass.inputAttachmentCount(0);
        subpass.pInputAttachments(null);
        subpass.colorAttachmentCount(1);
        subpass.pColorAttachments(colorReference);
        subpass.pResolveAttachments(null);
        subpass.pDepthStencilAttachment(null);
        subpass.preserveAttachmentCount(0);
        subpass.pPreserveAttachments(null);

        VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc();
        renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
        renderPassInfo.pNext(NULL);
        renderPassInfo.attachmentCount(1);
        renderPassInfo.pAttachments(attachments);
        renderPassInfo.subpassCount(1);
        renderPassInfo.pSubpasses(subpass);
        renderPassInfo.dependencyCount(0);
        renderPassInfo.pDependencies(null);

        LongBuffer pRenderPass = memAllocLong(1);
        int err = vkCreateRenderPass(device, renderPassInfo, null, pRenderPass);
        long renderPass = pRenderPass.get(0);
        memFree(pRenderPass);
        renderPassInfo.free();
        colorReference.free();
        subpass.free();
        attachments.free();
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to create clear render pass: " + translateVulkanError(err));
        }
        return renderPass;
    }

    private static long[] createFramebuffers(VkDevice device, Swapchain swapchain, long renderPass, int width, int height) {
        VkFramebufferCreateInfo fci = VkFramebufferCreateInfo.calloc();
        fci.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
        fci.attachmentCount(1);
        LongBuffer attachments = memAllocLong(1);
        fci.pAttachments(attachments);
        fci.flags(VK_FLAGS_NONE);
        fci.height(height);
        fci.width(width);
        fci.layers(1);
        fci.pNext(NULL);
        fci.renderPass(renderPass);
        // Create a framebuffer for each swapchain image
        long[] framebuffers = new long[swapchain.images.length];
        LongBuffer pFramebuffer = memAllocLong(1);
        for (int i = 0; i < swapchain.images.length; i++) {
            attachments.put(0, swapchain.imageViews[i]);
            int err = vkCreateFramebuffer(device, fci, null, pFramebuffer);
            long framebuffer = pFramebuffer.get(0);
            if (err != VK_SUCCESS) {
                throw new AssertionError("Failed to create framebuffer: " + translateVulkanError(err));
            }
            framebuffers[i] = framebuffer;
        }
        memFree(pFramebuffer);
        fci.free();
        return framebuffers;
    }

    private static void submitCommandBuffer(VkQueue queue, VkCommandBuffer commandBuffer) {
        if (commandBuffer == null || commandBuffer.address() == NULL)
            return;
        VkSubmitInfo submitInfo = VkSubmitInfo.calloc();
        submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
        submitInfo.commandBufferCount(1);
        PointerBuffer pCommandBuffers = memAllocPointer(1);
        pCommandBuffers.put(commandBuffer);
        pCommandBuffers.flip();
        submitInfo.pCommandBuffers(pCommandBuffers);
        int err = vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE);
        memFree(pCommandBuffers);
        submitInfo.free();
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to submit command buffer: " + translateVulkanError(err));
        }
    }

    private static VkCommandBuffer[] createRenderCommandBuffers(VkDevice device, long commandPool, long[] framebuffers, long renderPass, int width, int height) {
        // Create the render command buffers (one command buffer per framebuffer image)
        VkCommandBufferAllocateInfo cmdBufAllocateInfo = VkCommandBufferAllocateInfo.calloc();
        cmdBufAllocateInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        cmdBufAllocateInfo.commandPool(commandPool);
        cmdBufAllocateInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
        cmdBufAllocateInfo.commandBufferCount(framebuffers.length);
        PointerBuffer pCommandBuffer = memAllocPointer(framebuffers.length);
        int err = vkAllocateCommandBuffers(device, cmdBufAllocateInfo, pCommandBuffer);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to allocate render command buffer: " + translateVulkanError(err));
        }
        VkCommandBuffer[] renderCommandBuffers = new VkCommandBuffer[framebuffers.length];
        for (int i = 0; i < framebuffers.length; i++) {
            renderCommandBuffers[i] = new VkCommandBuffer(pCommandBuffer.get(i), device);
        }
        memFree(pCommandBuffer);
        cmdBufAllocateInfo.free();

        // Create the command buffer begin structure
        VkCommandBufferBeginInfo cmdBufInfo = VkCommandBufferBeginInfo.calloc();
        cmdBufInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        cmdBufInfo.pNext(NULL);

        // Specify clear color (cornflower blue)
        VkClearValue.Buffer clearValues = VkClearValue.calloc(1);
        VkClearColorValue clearColor = clearValues.color();
        clearColor.float32(0, 0.1f);
        clearColor.float32(1, 0.4f);
        clearColor.float32(2, 0.7f);
        clearColor.float32(3, 1.0f);

        // Specify everything to begin a render pass
        VkRenderPassBeginInfo renderPassBeginInfo = VkRenderPassBeginInfo.calloc();
        renderPassBeginInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
        renderPassBeginInfo.pNext(NULL);
        renderPassBeginInfo.renderPass(renderPass);
        VkRect2D renderArea = renderPassBeginInfo.renderArea();
        VkOffset2D offset = renderArea.offset();
        offset.x(0);
        offset.y(0);
        VkExtent2D extent = renderArea.extent();
        extent.width(width);
        extent.height(height);
        renderPassBeginInfo.clearValueCount(1);
        renderPassBeginInfo.pClearValues(clearValues);

        for (int i = 0; i < renderCommandBuffers.length; ++i) {
            // Set target frame buffer
            renderPassBeginInfo.framebuffer(framebuffers[i]);

            err = vkBeginCommandBuffer(renderCommandBuffers[i], cmdBufInfo);
            if (err != VK_SUCCESS) {
                throw new AssertionError("Failed to begin render command buffer: " + translateVulkanError(err));
            }

            vkCmdBeginRenderPass(renderCommandBuffers[i], renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE);

            // Update dynamic viewport state
            VkViewport.Buffer viewport = VkViewport.calloc(1);
            viewport.height(height);
            viewport.width(width);
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);
            vkCmdSetViewport(renderCommandBuffers[i], 0, viewport);
            viewport.free();

            // Update dynamic scissor state
            VkRect2D.Buffer scissor = VkRect2D.calloc(1);
            VkExtent2D scissorExtent = scissor.extent();
            VkOffset2D scissorOffset = scissor.offset();
            scissorExtent.width(width);
            scissorExtent.height(height);
            scissorOffset.x(0);
            scissorOffset.y(0);
            vkCmdSetScissor(renderCommandBuffers[i], 0, scissor);
            scissor.free();

            vkCmdEndRenderPass(renderCommandBuffers[i]);

            // Add a present memory barrier to the end of the command buffer
            // This will transform the frame buffer color attachment to a
            // new layout for presenting it to the windowing system integration 
            VkImageMemoryBarrier.Buffer prePresentBarrier = createPrePresentBarrier(swapchain.images[i]);
            vkCmdPipelineBarrier(renderCommandBuffers[i],
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_FLAGS_NONE,
                null, // No memory barriers
                null, // No buffer memory barriers
                prePresentBarrier); // One image memory barrier
            prePresentBarrier.free();

            err = vkEndCommandBuffer(renderCommandBuffers[i]);
            if (err != VK_SUCCESS) {
                throw new AssertionError("Failed to begin render command buffer: " + translateVulkanError(err));
            }
        }
        renderPassBeginInfo.free();
        clearValues.free();
        cmdBufInfo.free();
        return renderCommandBuffers;
    }

    private static VkImageMemoryBarrier.Buffer createPrePresentBarrier(long presentImage) {
        VkImageMemoryBarrier.Buffer imageMemoryBarrier = VkImageMemoryBarrier.calloc(1);
        imageMemoryBarrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        imageMemoryBarrier.pNext(NULL);
        imageMemoryBarrier.srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        imageMemoryBarrier.dstAccessMask(0);
        imageMemoryBarrier.oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        imageMemoryBarrier.newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        imageMemoryBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        imageMemoryBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        VkImageSubresourceRange subresourceRange = imageMemoryBarrier.subresourceRange();
        subresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        subresourceRange.baseMipLevel(0);
        subresourceRange.levelCount(1);
        subresourceRange.baseArrayLayer(0);
        subresourceRange.layerCount(1);
        imageMemoryBarrier.image(presentImage);
        return imageMemoryBarrier;
    }

    private static VkImageMemoryBarrier.Buffer createPostPresentBarrier(long presentImage) {
        VkImageMemoryBarrier.Buffer imageMemoryBarrier = VkImageMemoryBarrier.calloc(1);
        imageMemoryBarrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        imageMemoryBarrier.pNext(NULL);
        imageMemoryBarrier.srcAccessMask(0);
        imageMemoryBarrier.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        imageMemoryBarrier.oldLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        imageMemoryBarrier.newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        imageMemoryBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        imageMemoryBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        VkImageSubresourceRange subresourceRange = imageMemoryBarrier.subresourceRange();
        subresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        subresourceRange.baseMipLevel(0);
        subresourceRange.levelCount(1);
        subresourceRange.baseArrayLayer(0);
        subresourceRange.layerCount(1);
        imageMemoryBarrier.image(presentImage);
        return imageMemoryBarrier;
    }

    private static void submitPostPresentBarrier(long image, VkCommandBuffer commandBuffer, VkQueue queue) {
        VkCommandBufferBeginInfo cmdBufInfo = VkCommandBufferBeginInfo.calloc();
        cmdBufInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        cmdBufInfo.pNext(NULL);
        int err = vkBeginCommandBuffer(commandBuffer, cmdBufInfo);
        cmdBufInfo.free();
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to begin command buffer: " + translateVulkanError(err));
        }

        VkImageMemoryBarrier.Buffer postPresentBarrier = createPostPresentBarrier(image);
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_FLAGS_NONE,
            null, // No memory barriers,
            null, // No buffer barriers,
            postPresentBarrier); // one image barrier
        postPresentBarrier.free();

        err = vkEndCommandBuffer(commandBuffer);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to wait for idle queue: " + translateVulkanError(err));
        }

        // Submit the command buffer
        submitCommandBuffer(queue, commandBuffer);
    }

    /*
     * All resources that must be reallocated on window resize.
     */
    private static Swapchain swapchain;
    private static long[] framebuffers;
    private static VkCommandBuffer[] renderCommandBuffers;

    public static void main(String[] args) {
        System.err.println(Version.getVersion());

        // Create the Vulkan instance
        final VkInstance instance = createInstance();
        final VkDebugReportCallbackEXT debugCallback = new VkDebugReportCallbackEXT() {
            public int invoke(int flags, int objectType, long object, long location, int messageCode, long pLayerPrefix, long pMessage, long pUserData) {
                System.err.println("ERROR OCCURED: " + memDecodeASCII(pMessage));
                return 0;
            }
        };
        final long debugCallbackHandle = setupDebugging(instance, VK_DEBUG_REPORT_ERROR_BIT_EXT | VK_DEBUG_REPORT_WARNING_BIT_EXT, debugCallback);
        final VkPhysicalDevice physicalDevice = getFirstPhysicalDevice(instance);
        final DeviceAndGraphicsQueueFamily deviceAndGraphicsQueueFamily = createDeviceAndGetGraphicsQueueFamily(physicalDevice);
        final VkDevice device = deviceAndGraphicsQueueFamily.device;
        int queueFamilyIndex = deviceAndGraphicsQueueFamily.queueFamilyIndex;

        // Create GLFW window
        if (glfwInit() != GLFW_TRUE) {
            throw new RuntimeException("Failed to initialize GLFW");
        }
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        long window = glfwCreateWindow(800, 600, "GLFW Vulkan demo", NULL, NULL);
        LongBuffer pSurface = memAllocLong(1);
        int err = glfwCreateWindowSurface(instance, window, null, pSurface);
        final long surface = pSurface.get(0);
        if (err != VK_SUCCESS) {
            throw new AssertionError("Failed to create surface: " + translateVulkanError(err));
        }

        // Create static Vulkan resources
        final ColorFormatAndSpace colorFormatAndSpace = getColorFormatAndSpace(physicalDevice, surface);
        final long clearRenderPass = createClearRenderPass(device, colorFormatAndSpace.colorFormat);
        final long commandPool = createCommandPool(device, queueFamilyIndex);
        final long renderCommandPool = createCommandPool(device, queueFamilyIndex);
        final VkCommandBuffer setupCommandBuffer = createCommandBuffer(device, commandPool);
        final VkCommandBuffer postPresentCommandBuffer = createCommandBuffer(device, commandPool);
        final VkQueue queue = createDeviceQueue(device, queueFamilyIndex);

        // Handle canvas resize
        GLFWWindowSizeCallback windowSizeCallback = new GLFWWindowSizeCallback() {
            public void invoke(long window, int width, int height) {
                // Begin the setup command buffer (the one we will use for swapchain/framebuffer creation)
                VkCommandBufferBeginInfo cmdBufInfo = VkCommandBufferBeginInfo.calloc();
                cmdBufInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
                cmdBufInfo.pNext(NULL);
                int err = vkBeginCommandBuffer(setupCommandBuffer, cmdBufInfo);
                cmdBufInfo.free();
                if (err != VK_SUCCESS) {
                    throw new AssertionError("Failed to begin setup command buffer: " + translateVulkanError(err));
                }
                long oldChain = swapchain != null ? swapchain.swapchainHandle : VK_NULL_HANDLE;
                // Create the swapchain (this will also add a memory barrier to initialize the framebuffer images)
                swapchain = createSwapChain(device, physicalDevice, surface, oldChain, setupCommandBuffer,
                        width, height, colorFormatAndSpace.colorFormat, colorFormatAndSpace.colorSpace);
                err = vkEndCommandBuffer(setupCommandBuffer);
                if (err != VK_SUCCESS) {
                    throw new AssertionError("Failed to end setup command buffer: " + translateVulkanError(err));
                }
                submitCommandBuffer(queue, setupCommandBuffer);

                if (framebuffers != null) {
                    for (int i = 0; i < framebuffers.length; i++)
                        vkDestroyFramebuffer(device, framebuffers[i], null);
                }
                framebuffers = createFramebuffers(device, swapchain, clearRenderPass, width, height);
                // Create render command buffers
                if (renderCommandBuffers != null) {
                    vkResetCommandPool(device, renderCommandPool, VK_FLAGS_NONE);
                }
                renderCommandBuffers = createRenderCommandBuffers(device, renderCommandPool, framebuffers, clearRenderPass, width, height);
            }
        };
        glfwSetWindowSizeCallback(window, windowSizeCallback);
        glfwShowWindow(window);

        // Pre-allocate everything needed in the render loop

        IntBuffer pImageIndex = memAllocInt(1);
        int currentBuffer = 0;
        PointerBuffer pCommandBuffers = memAllocPointer(1);
        LongBuffer pSwapchains = memAllocLong(1);
        LongBuffer pImageAcquiredSemaphore = memAllocLong(1);
        LongBuffer pRenderCompleteSemaphore = memAllocLong(1);

        // Info struct to create a semaphore
        VkSemaphoreCreateInfo semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc();
        semaphoreCreateInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
        semaphoreCreateInfo.pNext(NULL);
        semaphoreCreateInfo.flags(VK_FLAGS_NONE);

        // Info struct to submit a command buffer which will wait on the semaphore
        VkSubmitInfo submitInfo = VkSubmitInfo.calloc();
        submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
        submitInfo.pNext(NULL);
        submitInfo.waitSemaphoreCount(1);
        submitInfo.pWaitSemaphores(pImageAcquiredSemaphore);
        IntBuffer pWaitDstStageMask = memAllocInt(1);
        pWaitDstStageMask.put(0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
        submitInfo.pWaitDstStageMask(pWaitDstStageMask);
        submitInfo.commandBufferCount(1);
        submitInfo.pCommandBuffers(pCommandBuffers);
        submitInfo.signalSemaphoreCount(1);
        submitInfo.pSignalSemaphores(pRenderCompleteSemaphore);

        // Info struct to present the current swapchain image to the display
        VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc();
        presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
        presentInfo.pNext(NULL);
        presentInfo.waitSemaphoreCount(1);
        presentInfo.pWaitSemaphores(pRenderCompleteSemaphore);
        presentInfo.swapchainCount(1);
        presentInfo.pSwapchains(pSwapchains);
        presentInfo.pImageIndices(pImageIndex);
        presentInfo.pResults(null);

        // The render loop
        while (glfwWindowShouldClose(window) == GLFW_FALSE) {
            // Handle window messages. Resize events happen exactly here.
            // So it is safe to use the new swapchain images and framebuffers afterwards.
            glfwPollEvents();

            // Create a semaphore to wait for the swapchain to acquire the next image
            err = vkCreateSemaphore(device, semaphoreCreateInfo, null, pImageAcquiredSemaphore);
            if (err != VK_SUCCESS) {
                throw new AssertionError("Failed to create image acquired semaphore: " + translateVulkanError(err));
            }

            // Create a semaphore to wait for the render to complete, before presenting
            err = vkCreateSemaphore(device, semaphoreCreateInfo, null, pRenderCompleteSemaphore);
            if (err != VK_SUCCESS) {
                throw new AssertionError("Failed to create render complete semaphore: " + translateVulkanError(err));
            }

            // Get next image from the swap chain (back/front buffer).
            // This will setup the imageAquiredSemaphore to be signalled when the operation is complete
            err = vkAcquireNextImageKHR(device, swapchain.swapchainHandle, UINT64_MAX, pImageAcquiredSemaphore.get(0), VK_NULL_HANDLE, pImageIndex);
            currentBuffer = pImageIndex.get(0);
            if (err != VK_SUCCESS) {
                throw new AssertionError("Failed to acquire next swapchain image: " + translateVulkanError(err));
            }

            // Select the command buffer for the current framebuffer image/attachment
            pCommandBuffers.put(0, renderCommandBuffers[currentBuffer]);

            // Submit to the graphics queue
            err = vkQueueSubmit(queue, submitInfo, VK_NULL_HANDLE);
            if (err != VK_SUCCESS) {
                throw new AssertionError("Failed to submit render queue: " + translateVulkanError(err));
            }

            // Present the current buffer to the swap chain
            // This will display the image
            pSwapchains.put(0, swapchain.swapchainHandle);
            err = vkQueuePresentKHR(queue, presentInfo);
            if (err != VK_SUCCESS) {
                throw new AssertionError("Failed to present the swapchain image: " + translateVulkanError(err));
            }
            // Destroy this semaphore (we will create a new one in the next frame)
            vkDestroySemaphore(device, pImageAcquiredSemaphore.get(0), null);
            vkDestroySemaphore(device, pRenderCompleteSemaphore.get(0), null);

            // Create and submit post present barrier
            vkQueueWaitIdle(queue);
            submitPostPresentBarrier(swapchain.images[currentBuffer], postPresentCommandBuffer, queue);
        }
        presentInfo.free();
        memFree(pWaitDstStageMask);
        submitInfo.free();
        memFree(pImageAcquiredSemaphore);
        memFree(pRenderCompleteSemaphore);
        semaphoreCreateInfo.free();
        memFree(pSwapchains);
        memFree(pCommandBuffers);

        vkDestroyDebugReportCallbackEXT(instance, debugCallbackHandle, null);

        glfwDestroyWindow(window);

        // We don't bother disposing of all Vulkan resources.
        // Let the OS process manager take care of it.
    }

}
