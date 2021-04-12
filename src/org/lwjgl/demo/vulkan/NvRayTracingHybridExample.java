/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.vulkan;

import org.joml.Matrix4f;
import org.joml.Matrix4x3f;
import org.joml.Vector2i;
import org.lwjgl.PointerBuffer;
import org.lwjgl.demo.util.*;
import org.lwjgl.demo.util.GreedyMeshing.Face;
import org.lwjgl.glfw.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static java.lang.Math.*;
import static org.joml.SimplexNoise.*;
import static org.lwjgl.demo.util.FaceTriangulator.*;
import static org.lwjgl.demo.util.IOUtils.*;
import static org.lwjgl.demo.vulkan.VKFactory.*;
import static org.lwjgl.demo.vulkan.VKUtil.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.EXTDebugReport.*;
import static org.lwjgl.vulkan.KHR8bitStorage.*;
import static org.lwjgl.vulkan.KHRDedicatedAllocation.*;
import static org.lwjgl.vulkan.KHRGetMemoryRequirements2.*;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.*;
import static org.lwjgl.vulkan.KHRShaderFloat16Int8.*;
import static org.lwjgl.vulkan.KHRStorageBufferStorageClass.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.NVRayTracing.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Use VK_NV_ray_tracing to trace a simple scene.
 * <p>
 * In addition to {@link NvRayTracingExample}, this demo uses rasterization for the primary rays.
 *
 * @author Kai Burjack
 */
public class NvRayTracingHybridExample {
    private static boolean debug = System.getProperty("NDEBUG") == null;

    static {
        if (debug) {
            System.setProperty("org.lwjgl.util.Debug", "true");
            System.setProperty("org.lwjgl.util.NoChecks", "false");
            System.setProperty("org.lwjgl.util.DebugLoader", "true");
            System.setProperty("org.lwjgl.util.DebugAllocator", "true");
            System.setProperty("org.lwjgl.util.DebugStack", "true");
        } else {
            System.setProperty("org.lwjgl.util.NoChecks", "true");
        }
    }

    private static final int BITS_FOR_POSITIONS = 10; // <- allow for a position maximum of 1024
    private static final int POSITION_SCALE = 1 << BITS_FOR_POSITIONS;
    private static int INITIAL_WINDOW_WIDTH = 800;
    private static int INITIAL_WINDOW_HEIGHT = 600;
    private static int CHUNK_WIDTH = 128;
    private static int CHUNK_HEIGHT = 32;
    private static int CHUNK_DEPTH = 128;
    private static long vmaAllocator;
    private static VkInstance instance;
    private static WindowAndCallbacks windowAndCallbacks;
    private static long surface;
    private static DebugCallbackAndHandle debugCallbackHandle;
    private static DeviceAndQueueFamilies deviceAndQueueFamilies;
    private static int queueFamily;
    private static VkDevice device;
    private static VkQueue queue;
    private static Swapchain swapchain;
    private static AllocationAndImage[] depthStencilImages;
    private static AllocationAndImage[] normalImages;
    private static AllocationAndImage[] rayTracingImages;
    private static long sampler;
    private static Long commandPool, commandPoolTransient;
    private static long renderPass;
    private static long[] framebuffers;
    private static BottomLevelAccelerationStructure blas;
    private static TopLevelAccelerationStructure tlas;
    private static AllocationAndBuffer[] rayTracingUbos;
    private static AllocationAndBuffer[] rasterUbos;
    private static AllocationAndBuffer sobolBuffer, scrambleBuffer, rankingBuffer;
    private static Pipeline rayTracingPipeline;
    private static Pipeline rasterPipeline;
    private static AllocationAndBuffer rayTracingShaderBindingTable;
    private static DescriptorSets rayTracingDescriptorSets;
    private static DescriptorSets rasterDescriptorSets;
    private static RayTracingProperties rayTracingProperties;
    private static VkCommandBuffer[] rayTracingCommandBuffers;
    private static VkCommandBuffer[] rasterCommandBuffers;
    private static VkCommandBuffer[] presentCommandBuffers;
    private static Geometry geometry;
    private static long[] imageAcquireSemaphores;
    private static long[] renderCompleteSemaphores;
    private static long[] renderFences;
    private static long queryPool;
    private static Matrix4f projMatrix = new Matrix4f();
    private static Matrix4x3f viewMatrix = new Matrix4x3f().rotateX(0.3f).rotateY(-0.3f).translate(
            -CHUNK_WIDTH * 0.8f, 
            -CHUNK_HEIGHT * 1.1f, 
            -CHUNK_DEPTH * 1.01f);
    private static Matrix4f viewProjMatrix = new Matrix4f();
    private static Matrix4f invProjMatrix = new Matrix4f();
    private static Matrix4x3f invViewMatrix = new Matrix4x3f();
    private static boolean[] keydown = new boolean[GLFW_KEY_LAST + 1];
    private static float mouseX, mouseY;
    private static boolean mouseDown;
    private static Map<Long, Runnable> waitingFenceActions = new HashMap<>();

    private static PointerBuffer pointers(MemoryStack stack, PointerBuffer pts, ByteBuffer... pointers) {
        PointerBuffer res = stack.mallocPointer(pts.remaining() + pointers.length);
        res.put(pts);
        for (ByteBuffer pointer : pointers) {
            res.put(pointer);
        }
        res.flip();
        return res;
    }

    private static int bitIf(int bit, boolean condition) {
        return condition ? bit : 0;
    }

    private static long createAllocator() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pAllocator = stack.mallocPointer(1);
            _CHECK_(vmaCreateAllocator(VmaAllocatorCreateInfo(stack)
                    .flags(bitIf(VMA_ALLOCATOR_CREATE_KHR_DEDICATED_ALLOCATION_BIT,
                            device.getCapabilities().VK_KHR_dedicated_allocation))
                    .device(device)
                    .physicalDevice(deviceAndQueueFamilies.physicalDevice)
                    .pVulkanFunctions(VmaVulkanFunctions(stack).set(instance, device)), pAllocator),
                    "Failed to create VMA allocator");
            return pAllocator.get(0);
        }
    }

    private static VkInstance createInstance(PointerBuffer requiredExtensions) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer ppEnabledExtensionNames;
            if (debug) {
                ppEnabledExtensionNames = pointers(stack, requiredExtensions,
                        stack.UTF8(VK_EXT_DEBUG_REPORT_EXTENSION_NAME),
                        stack.UTF8(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME));
            } else {
                ppEnabledExtensionNames = pointers(stack, requiredExtensions,
                        stack.UTF8(VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME));
            }
            PointerBuffer enabledLayers = null;
            if (debug) {
                enabledLayers = stack.pointers(stack.UTF8("VK_LAYER_LUNARG_standard_validation"));
            }
            VkInstanceCreateInfo pCreateInfo = VkInstanceCreateInfo(stack)
                    .pApplicationInfo(VkApplicationInfo(stack)
                            .apiVersion(VK_API_VERSION_1_0))
                    .ppEnabledExtensionNames(ppEnabledExtensionNames)
                    .ppEnabledLayerNames(enabledLayers);
            PointerBuffer pInstance = stack.mallocPointer(1);
            _CHECK_(vkCreateInstance(pCreateInfo, null, pInstance), "Failed to create VkInstance");
            return new VkInstance(pInstance.get(0), pCreateInfo);
        }
    }

    private static class DebugCallbackAndHandle {
        long handle;
        VkDebugReportCallbackEXT callback;

        DebugCallbackAndHandle(long handle, VkDebugReportCallbackEXT callback) {
            this.handle = handle;
            this.callback = callback;
        }

        void free() {
            vkDestroyDebugReportCallbackEXT(instance, handle, null);
            callback.free();
        }
    }

    private static DebugCallbackAndHandle setupDebugging() {
        if (!debug) {
            return null;
        }
        VkDebugReportCallbackEXT callback = new VkDebugReportCallbackEXT() {
            public int invoke(int flags, int objectType, long object, long location, int messageCode, long pLayerPrefix,
                              long pMessage, long pUserData) {
                String type = "";
                if ((flags & VK_DEBUG_REPORT_WARNING_BIT_EXT) != 0)
                    type += "WARN ";
                if ((flags & VK_DEBUG_REPORT_ERROR_BIT_EXT) != 0)
                    type += "ERROR ";
                System.err.println(type + VkDebugReportCallbackEXT.getString(pMessage));
                return 0;
            }
        };
        try (MemoryStack stack = stackPush()) {
            LongBuffer pCallback = stack.mallocLong(1);
            _CHECK_(vkCreateDebugReportCallbackEXT(instance, VkDebugReportCallbackCreateInfoEXT(stack)
                            .pfnCallback(callback)
                            .flags(VK_DEBUG_REPORT_ERROR_BIT_EXT | VK_DEBUG_REPORT_WARNING_BIT_EXT), null, pCallback),
                    "Failed to create debug callback");
            return new DebugCallbackAndHandle(pCallback.get(0), callback);
        }
    }

    private static boolean familySupports(VkQueueFamilyProperties prop, int bit) {
        return (prop.queueFlags() & bit) != 0;
    }

    private static QueueFamilies obtainQueueFamilies(VkPhysicalDevice physicalDevice) {
        QueueFamilies ret = new QueueFamilies();
        try (MemoryStack stack = stackPush()) {
            IntBuffer pQueueFamilyPropertyCount = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, null);
            if (pQueueFamilyPropertyCount.get(0) == 0)
                throw new AssertionError("No queue families found");
            VkQueueFamilyProperties.Buffer familyProperties = VkQueueFamilyProperties(pQueueFamilyPropertyCount.get(0));
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, familyProperties);
            int queueFamilyIndex = 0;
            for (VkQueueFamilyProperties queueFamilyProps : familyProperties) {
                IntBuffer pSupported = stack.mallocInt(1);
                if (queueFamilyProps.queueCount() < 1) {
                    continue;
                }
                vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, queueFamilyIndex, surface, pSupported);
                if (familySupports(queueFamilyProps, VK_QUEUE_GRAPHICS_BIT))
                    ret.graphicsFamilies.add(queueFamilyIndex);
                if (familySupports(queueFamilyProps, VK_QUEUE_COMPUTE_BIT))
                    ret.computeFamilies.add(queueFamilyIndex);
                if (familySupports(queueFamilyProps, VK_QUEUE_TRANSFER_BIT))
                    ret.transferFamilies.add(queueFamilyIndex);
                if (pSupported.get(0) != 0)
                    ret.presentFamilies.add(queueFamilyIndex);
                queueFamilyIndex++;
            }
            return ret;
        }
    }

    private static boolean isExtensionEnabled(VkExtensionProperties.Buffer buf, String extension) {
        return buf.stream().anyMatch(p -> p.extensionNameString().equals(extension));
    }

    private static VkDevice createDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pPropertyCount = stack.mallocInt(1);
            _CHECK_(vkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, (ByteBuffer) null, pPropertyCount, null),
                    "Failed to enumerate device extensions");
            VkExtensionProperties.Buffer pProperties = VkExtensionProperties.mallocStack(pPropertyCount.get(0), stack);
            _CHECK_(vkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, (ByteBuffer) null, pPropertyCount,
                    pProperties),
                    "Failed to enumerate device extensions");
            assertAvailable(pProperties, VK_KHR_SWAPCHAIN_EXTENSION_NAME);
            assertAvailable(pProperties, VK_NV_RAY_TRACING_EXTENSION_NAME);
            assertAvailable(pProperties, VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME);
            assertAvailable(pProperties, VK_KHR_8BIT_STORAGE_EXTENSION_NAME);
            assertAvailable(pProperties, VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME);
            assertAvailable(pProperties, VK_KHR_STORAGE_BUFFER_STORAGE_CLASS_EXTENSION_NAME);
            PointerBuffer extensions = stack.mallocPointer(6 + 1);
            extensions.put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                      .put(stack.UTF8(VK_NV_RAY_TRACING_EXTENSION_NAME))
                      .put(stack.UTF8(VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME))
                      .put(stack.UTF8(VK_KHR_8BIT_STORAGE_EXTENSION_NAME))
                      .put(stack.UTF8(VK_KHR_SHADER_FLOAT16_INT8_EXTENSION_NAME))
                      .put(stack.UTF8(VK_KHR_STORAGE_BUFFER_STORAGE_CLASS_EXTENSION_NAME));
            if (isExtensionEnabled(pProperties, VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME)) {
                extensions.put(stack.UTF8(VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME));
            }
            PointerBuffer ppEnabledLayerNames = null;
            if (debug) {
                ppEnabledLayerNames = stack.pointers(stack.UTF8("VK_LAYER_LUNARG_standard_validation"));
            }
            VkDeviceCreateInfo pCreateInfo = VkDeviceCreateInfo(stack)
                    .pNext(VkPhysicalDeviceFeatures2(stack)
                            .pNext(VkPhysicalDevice8BitStorageFeaturesKHR(stack)
                                    .pNext(VkPhysicalDeviceFloat16Int8FeaturesKHR(stack)
                                            .shaderInt8(true).address())
                                    .uniformAndStorageBuffer8BitAccess(true).address()).address())
                    .pQueueCreateInfos(VkDeviceQueueCreateInfo(stack)
                            .queueFamilyIndex(queueFamily)
                            .pQueuePriorities(stack.floats(1.0f)))
                    .ppEnabledExtensionNames(extensions.flip())
                    .ppEnabledLayerNames(ppEnabledLayerNames);
            PointerBuffer pDevice = stack.mallocPointer(1);
            _CHECK_(vkCreateDevice(deviceAndQueueFamilies.physicalDevice, pCreateInfo, null, pDevice), "Failed to create device");
            return new VkDevice(pDevice.get(0), deviceAndQueueFamilies.physicalDevice, pCreateInfo);
        }
    }

    private static void assertAvailable(VkExtensionProperties.Buffer pProperties, String extension) {
        if (!isExtensionEnabled(pProperties, extension))
            throw new AssertionError("Missing required extension: " + extension);
    }

    private static VkQueue retrieveQueue() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, queueFamily, 0, pQueue);
            return new VkQueue(pQueue.get(0), device);
        }
    }

    private static boolean isDeviceSuitable(VkPhysicalDevice device, QueueFamilies queuesFamilies) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties deviceProperties = VkPhysicalDeviceProperties(stack);
            vkGetPhysicalDeviceProperties(device, deviceProperties);
            return !queuesFamilies.computeFamilies.isEmpty() && !queuesFamilies.graphicsFamilies.isEmpty()
                    && !queuesFamilies.transferFamilies.isEmpty() && !queuesFamilies.presentFamilies.isEmpty();
        }
    }

    private static class DeviceAndQueueFamilies {
        VkPhysicalDevice physicalDevice;
        QueueFamilies queuesFamilies;

        DeviceAndQueueFamilies(VkPhysicalDevice physicalDevice, QueueFamilies queuesFamilies) {
            this.physicalDevice = physicalDevice;
            this.queuesFamilies = queuesFamilies;
        }
    }

    private static DeviceAndQueueFamilies createPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pPhysicalDeviceCount = stack.mallocInt(1);
            _CHECK_(vkEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, null),
                    "Failed to get number of physical devices");
            int physicalDeviceCount = pPhysicalDeviceCount.get(0);
            if (physicalDeviceCount == 0)
                throw new AssertionError("No physical devices available");
            PointerBuffer pPhysicalDevices = stack.mallocPointer(physicalDeviceCount);
            _CHECK_(vkEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, pPhysicalDevices),
                    "Failed to get physical devices");
            for (int i = 0; i < physicalDeviceCount; i++) {
                VkPhysicalDevice dev = new VkPhysicalDevice(pPhysicalDevices.get(i), instance);
                QueueFamilies queuesFamilies = obtainQueueFamilies(dev);
                if (isDeviceSuitable(dev, queuesFamilies)) {
                    return new DeviceAndQueueFamilies(dev, queuesFamilies);
                }
            }
            throw new AssertionError("No suitable physical device found");
        }
    }

    private static class RayTracingProperties {
        int shaderGroupHandleSize;

        RayTracingProperties(int shaderGroupHandleSize) {
            this.shaderGroupHandleSize = shaderGroupHandleSize;
        }
    }

    private static RayTracingProperties initRayTracing() {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceRayTracingPropertiesNV rtprops = VkPhysicalDeviceRayTracingPropertiesNV(stack);
            vkGetPhysicalDeviceProperties2KHR(deviceAndQueueFamilies.physicalDevice, VkPhysicalDeviceProperties2(stack)
                    .pNext(rtprops.address()));
            return new RayTracingProperties(rtprops.shaderGroupHandleSize());
        }
    }

    private static class ColorFormatAndSpace {
        int colorFormat;
        int colorSpace;
        int depthFormat;

        ColorFormatAndSpace(int colorFormat, int colorSpace, int depthFormat) {
            this.colorFormat = colorFormat;
            this.colorSpace = colorSpace;
            this.depthFormat = depthFormat;
        }
    }

    private static class Swapchain {
        long swapchain;
        long[] images;
        int width, height;
        ColorFormatAndSpace surfaceFormat;

        Swapchain(long swapchain, long[] images, int width, int height, ColorFormatAndSpace surfaceFormat) {
            this.swapchain = swapchain;
            this.images = images;
            this.width = width;
            this.height = height;
            this.surfaceFormat = surfaceFormat;
        }

        void free() {
            vkDestroySwapchainKHR(device, swapchain, null);
        }
    }

    private static Swapchain createSwapChain() {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR surfCaps = VkSurfaceCapabilitiesKHR(stack);
            _CHECK_(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(deviceAndQueueFamilies.physicalDevice, surface, surfCaps),
                    "Failed to get physical device surface capabilities");
            IntBuffer count = stack.mallocInt(1);
            _CHECK_(vkGetPhysicalDeviceSurfacePresentModesKHR(deviceAndQueueFamilies.physicalDevice, surface, count, null),
                    "Failed to get presentation modes count");
            IntBuffer pPresentModes = stack.mallocInt(count.get(0));
            _CHECK_(vkGetPhysicalDeviceSurfacePresentModesKHR(deviceAndQueueFamilies.physicalDevice, surface, count, pPresentModes),
                    "Failed to get presentation modes");
            int imageCount = 2;//min(surfCaps.minImageCount() + 1, surfCaps.maxImageCount());
            ColorFormatAndSpace surfaceFormat = determineSurfaceFormat(deviceAndQueueFamilies.physicalDevice, surface);
            Vector2i swapchainExtents = determineSwapchainExtents(surfCaps);
            VkSwapchainCreateInfoKHR pCreateInfo = VkSwapchainCreateInfoKHR(stack)
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageExtent(e -> e.set(swapchainExtents.x, swapchainExtents.y))
                    .imageFormat(surfaceFormat.colorFormat)
                    .imageColorSpace(surfaceFormat.colorSpace)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .preTransform(VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                    .imageArrayLayers(1)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .presentMode(VK_PRESENT_MODE_FIFO_KHR)
                    .oldSwapchain(swapchain != null ? swapchain.swapchain : VK_NULL_HANDLE)
                    .clipped(true)
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            LongBuffer pSwapChain = stack.mallocLong(1);
            _CHECK_(vkCreateSwapchainKHR(device, pCreateInfo, null, pSwapChain), "Failed to create swap chain");
            if (swapchain != null) {
                swapchain.free();
            }
            IntBuffer pImageCount = stack.mallocInt(1);
            _CHECK_(vkGetSwapchainImagesKHR(device, pSwapChain.get(0), pImageCount, null),
                    "Failed to get swapchain images count");
            LongBuffer pSwapchainImages = stack.mallocLong(pImageCount.get(0));
            _CHECK_(vkGetSwapchainImagesKHR(device, pSwapChain.get(0), pImageCount, pSwapchainImages),
                    "Failed to get swapchain images");
            long[] images = new long[pImageCount.get(0)];
            pSwapchainImages.get(images, 0, images.length);
            return new Swapchain(pSwapChain.get(0), images, swapchainExtents.x, swapchainExtents.y, surfaceFormat);
        }
    }

    private static AllocationAndImage[] createColorImages(AllocationAndImage[] old, int format, int usage, int dstImageLayout, int dstStageMask, int dstAccessMask) {
        if (old != null) {
            for (AllocationAndImage aai : old)
                aai.free();
        }
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageCreateInfo = VkImageCreateInfo(stack)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .extent(e -> e.set(swapchain.width, swapchain.height, 1));
            VkImageViewCreateInfo imageViewCreateInfo = VkImageViewCreateInfo(stack)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format)
                    .subresourceRange(r -> r
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .levelCount(1)
                            .layerCount(1));
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            AllocationAndImage[] allocations = new AllocationAndImage[swapchain.images.length];
            LongBuffer pImageView = stack.mallocLong(1);
            VkCommandBuffer cmdBuffer = createCommandBuffer(commandPoolTransient);
            for (int i = 0; i < swapchain.images.length; i++) {
                _CHECK_(vmaCreateImage(vmaAllocator, imageCreateInfo,
                        VmaAllocationCreateInfo(stack).usage(VMA_MEMORY_USAGE_GPU_ONLY), pImage, pAllocation, null),
                        "Failed to create image");
                imageViewCreateInfo.image(pImage.get(0));
                _CHECK_(vkCreateImageView(device, imageViewCreateInfo, null, pImageView),
                        "Failed to create image view");
                allocations[i] = new AllocationAndImage(pAllocation.get(0), pImage.get(0), pImageView.get(0), format);
                vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, dstStageMask,0,
                        null,null, VkImageMemoryBarrier(stack)
                                .srcAccessMask(0)
                                .dstAccessMask(dstAccessMask)
                                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                                .newLayout(dstImageLayout)
                                .image(pImage.get(0))
                                .subresourceRange(r1 -> 
                                    r1.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                            .layerCount(1)
                                            .levelCount(1)));
            }
            submitCommandBuffer(cmdBuffer, true, () -> {
                vkFreeCommandBuffers(device, commandPoolTransient, cmdBuffer);
            });
            return allocations;
        }
    }

    private static AllocationAndImage[] createRayTracingImages() {
        return createColorImages(rayTracingImages, VK_FORMAT_B8G8R8A8_UNORM, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV, VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static AllocationAndImage[] createNormalImages() {
        return createColorImages(normalImages, VK_FORMAT_R8G8_SNORM, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_STORAGE_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    }

    private static long createSampler() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSampler = stack.mallocLong(1);
            _CHECK_(vkCreateSampler(device, VkSamplerCreateInfo(stack)
                            .magFilter(VK_FILTER_NEAREST)
                            .minFilter(VK_FILTER_NEAREST)
                            .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                            .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                            .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                            .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                            .compareOp(VK_COMPARE_OP_NEVER)
                            .maxLod(1)
                            .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                            .maxAnisotropy(1.0f), null, pSampler),
                    "Failed to create sampler");
            return pSampler.get(0);
        }
    }

    private static Vector2i determineSwapchainExtents(VkSurfaceCapabilitiesKHR surfCaps) {
        VkExtent2D extent = surfCaps.currentExtent();
        Vector2i ret = new Vector2i(extent.width(), extent.height());
        if (extent.width() == -1) {
            ret.set(max(min(INITIAL_WINDOW_WIDTH, surfCaps.maxImageExtent().width()), surfCaps.minImageExtent().width()),
                    max(min(INITIAL_WINDOW_HEIGHT, surfCaps.maxImageExtent().height()), surfCaps.minImageExtent().height()));
        }
        return ret;
    }

    private static ColorFormatAndSpace determineSurfaceFormat(VkPhysicalDevice physicalDevice, long surface) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            _CHECK_(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, null),
                    "Failed to get device surface formats count");
            VkSurfaceFormatKHR.Buffer pSurfaceFormats = VkSurfaceFormatKHR(stack, count.get(0));
            _CHECK_(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, count, pSurfaceFormats),
                    "Failed to get device surface formats");
            int colorFormat;
            if (pSurfaceFormats.remaining() == 1 && pSurfaceFormats.get(0).format() == VK_FORMAT_UNDEFINED)
                colorFormat = VK_FORMAT_B8G8R8A8_UNORM;
            else
                colorFormat = pSurfaceFormats.get(0).format();
            int colorSpace = pSurfaceFormats.get(0).colorSpace();
            int[] depthFormats = { VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT };
            VkFormatProperties formatProps = VkFormatProperties(stack);
            int depthFormat = -1;
            for (int format : depthFormats) {
                vkGetPhysicalDeviceFormatProperties(physicalDevice, format, formatProps);
                if ((formatProps.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    depthFormat = format;
                    break;
                }
            }
            return new ColorFormatAndSpace(colorFormat, colorSpace, depthFormat);
        }
    }

    private static class AllocationAndImage {
        long allocation;
        long image;
        long imageView;
        int format;

        AllocationAndImage(long allocation, long image, long imageView, int format) {
            this.allocation = allocation;
            this.image = image;
            this.imageView = imageView;
            this.format = format;
        }

        void free() {
            vmaDestroyImage(vmaAllocator, image, allocation);
            vkDestroyImageView(device, imageView, null);
        }
    }

    private static AllocationAndImage[] createDepthStencilImages() {
        if (depthStencilImages != null) {
            for (AllocationAndImage aai : depthStencilImages)
                aai.free();
        }
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo imageCreateInfo = VkImageCreateInfo(stack)
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(swapchain.surfaceFormat.depthFormat)
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .extent(e -> e.set(swapchain.width, swapchain.height, 1));
            VkImageViewCreateInfo depthStencilViewCreateInfo = VkImageViewCreateInfo(stack)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(swapchain.surfaceFormat.depthFormat)
                    .subresourceRange(r -> r
                            .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                            .levelCount(1)
                            .layerCount(1));
            LongBuffer pDepthStencilImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            AllocationAndImage[] allocations = new AllocationAndImage[swapchain.images.length];
            LongBuffer pDepthStencilView = stack.mallocLong(1);
            for (int i = 0; i < swapchain.images.length; i++) {
                _CHECK_(vmaCreateImage(vmaAllocator, imageCreateInfo,
                        VmaAllocationCreateInfo(stack).usage(VMA_MEMORY_USAGE_GPU_ONLY), pDepthStencilImage, pAllocation,
                        null), "Failed to create depth stencil image");
                depthStencilViewCreateInfo.image(pDepthStencilImage.get(0));
                _CHECK_(vkCreateImageView(device, depthStencilViewCreateInfo, null, pDepthStencilView),
                        "Failed to create image view for depth stencil image");
                allocations[i] = new AllocationAndImage(pAllocation.get(0), pDepthStencilImage.get(0), 
                        pDepthStencilView.get(0), swapchain.surfaceFormat.depthFormat);
            }
            return allocations;
        }
    }

    private static long createCommandPool(int flags) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pCmdPool = stack.mallocLong(1);
            _CHECK_(vkCreateCommandPool(device, VkCommandPoolCreateInfo(stack)
                    .queueFamilyIndex(queueFamily)
                    .flags(flags), null, pCmdPool), "Failed to create command pool");
            return pCmdPool.get(0);
        }
    }

    private static class QueueFamilies {
        List<Integer> graphicsFamilies = new ArrayList<>();
        List<Integer> computeFamilies = new ArrayList<>();
        List<Integer> transferFamilies = new ArrayList<>();
        List<Integer> presentFamilies = new ArrayList<>();

        int findSingleSuitableQueue() {
            return graphicsFamilies
                    .stream()
                    .filter(i -> computeFamilies.contains(i)
                            && transferFamilies.contains(i)
                            && presentFamilies.contains(i)).findAny().orElseThrow(
                                    () -> new AssertionError("No suitable queue found"));
        }
    }

    private static long submitCommandBuffer(VkCommandBuffer commandBuffer, boolean endCommandBuffer, Runnable afterComplete) {
        if (endCommandBuffer)
            _CHECK_(vkEndCommandBuffer(commandBuffer), "Failed to end command buffer");
        try (MemoryStack stack = stackPush()) {
            LongBuffer pFence = stack.mallocLong(1);
            _CHECK_(vkCreateFence(device, VkFenceCreateInfo(stack), null, pFence), "Failed to create fence");
            _CHECK_(vkQueueSubmit(queue, VkSubmitInfo(stack)
                    .pCommandBuffers(stack.pointers(commandBuffer)), pFence.get(0)),
                    "Failed to submit command buffer");
            long fence = pFence.get(0);
            if (afterComplete != null)
                waitingFenceActions.put(fence, afterComplete);
            return fence;
        }
    }

    private static void freeCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffers = stack.mallocPointer(
                    rasterCommandBuffers.length +
                    rayTracingCommandBuffers.length + 
                    presentCommandBuffers.length);
            for (VkCommandBuffer cb : rasterCommandBuffers)
                pCommandBuffers.put(cb);
            for (VkCommandBuffer cb : rayTracingCommandBuffers)
                pCommandBuffers.put(cb);
            for (VkCommandBuffer cb : presentCommandBuffers)
                pCommandBuffers.put(cb);
            vkFreeCommandBuffers(device, commandPool, pCommandBuffers.flip());
        }
    }

    private static void processFinishedFences() {
        Iterator<Map.Entry<Long, Runnable>> it = waitingFenceActions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Runnable> e = it.next();
            if (vkGetFenceStatus(device, e.getKey()) == VK_SUCCESS) {
                it.remove();
                vkDestroyFence(device, e.getKey(), null);
                e.getValue().run();
            }
        }
    }

    private static VkCommandBuffer createCommandBuffer(long pool) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffers = stack.mallocPointer(1);
            _CHECK_(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo(stack)
                    .commandBufferCount(1)
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY), pCommandBuffers), "Failed to create command buffers");
            VkCommandBuffer cmdBuffer = new VkCommandBuffer(pCommandBuffers.get(0), device);
            _CHECK_(vkBeginCommandBuffer(cmdBuffer, VkCommandBufferBeginInfo(stack)), "Failed to begin command buffers");
            return cmdBuffer;
        }
    }
    private static VkCommandBuffer[] createCommandBuffers(long pool, int count) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffers = stack.mallocPointer(count);
            _CHECK_(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo(stack)
                    .commandBufferCount(count)
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY), pCommandBuffers), "Failed to create command buffers");
            VkCommandBuffer[] cmdBuffers = new VkCommandBuffer[count];
            for (int i = 0; i < count; i++) {
                cmdBuffers[i] = new VkCommandBuffer(pCommandBuffers.get(i), device);
                _CHECK_(vkBeginCommandBuffer(cmdBuffers[i], VkCommandBufferBeginInfo(stack)), "Failed to begin command buffers");
            }
            return cmdBuffers;
        }
    }

    private static class AllocationAndBuffer {
        long allocation;
        long buffer;
        long mapped;

        AllocationAndBuffer(long allocation, long buffer) {
            this.allocation = allocation;
            this.buffer = buffer;
        }

        void free() {
            if (mapped != NULL) {
                vmaUnmapMemory(vmaAllocator, allocation);
                mapped = NULL;
            }
            vmaDestroyBuffer(vmaAllocator, buffer, allocation);
        }

        void map() {
            if (mapped != NULL) {
                return;
            }
            try (MemoryStack stack = stackPush()) {
                PointerBuffer pb = stack.mallocPointer(1);
                _CHECK_(vmaMapMemory(vmaAllocator, allocation, pb), "Failed to map allocation");
                mapped = pb.get(0);
            }
        }

        void flushMapped(long offset, long size) {
            vmaFlushAllocation(vmaAllocator, allocation, offset, size);
        }
    }

    private static AllocationAndBuffer createBuffer(int usageFlags, ByteBuffer data) {
        return createBuffer(usageFlags, data.remaining(), data);
    }
    private static AllocationAndBuffer createRayTracingBuffer(long size) {
        return createBuffer(VK_BUFFER_USAGE_RAY_TRACING_BIT_NV, size, null);
    }

    private static AllocationAndBuffer createBuffer(int usageFlags, long size, ByteBuffer data) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            _CHECK_(vmaCreateBuffer(vmaAllocator,
                    VkBufferCreateInfo(stack)
                        .size(size)
                        .usage(usageFlags | (data != null ? VK_BUFFER_USAGE_TRANSFER_DST_BIT : 0)),
                    VmaAllocationCreateInfo(stack)
                        .usage(VMA_MEMORY_USAGE_GPU_ONLY), pBuffer, pAllocation, null),
                    "Failed to allocate buffer");
            if (data != null) {
                LongBuffer pBufferStage = stack.mallocLong(1);
                PointerBuffer pAllocationStage = stack.mallocPointer(1);
                _CHECK_(vmaCreateBuffer(vmaAllocator,
                        VkBufferCreateInfo(stack).size(data.remaining()).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT),
                        VmaAllocationCreateInfo(stack).usage(VMA_MEMORY_USAGE_CPU_ONLY), pBufferStage, pAllocationStage,
                        null), "Failed to allocate stage buffer");
                PointerBuffer pData = stack.mallocPointer(1);
                _CHECK_(vmaMapMemory(vmaAllocator, pAllocationStage.get(0), pData), "Failed to map memory");
                memCopy(memAddress(data), pData.get(0), data.remaining());
                vmaUnmapMemory(vmaAllocator, pAllocationStage.get(0));
                VkCommandBuffer cmdBuffer = createCommandBuffer(commandPoolTransient);
                vkCmdCopyBuffer(cmdBuffer, pBufferStage.get(0), pBuffer.get(0),
                        VkBufferCopy(stack, 1).size(data.remaining()));
                long bufferStage = pBufferStage.get(0);
                long allocationStage = pAllocationStage.get(0);
                submitCommandBuffer(cmdBuffer, true, () -> {
                    vkFreeCommandBuffers(device, commandPoolTransient, cmdBuffer);
                    vmaDestroyBuffer(vmaAllocator, bufferStage, allocationStage);
                });
            }
            return new AllocationAndBuffer(pAllocation.get(0), pBuffer.get(0));
        }
    }

    private static abstract class AccelerationStructure<T extends AccelerationStructure<T>> {
        long accelerationStructure;
        AllocationAndMemory memory;

        AccelerationStructure(long accelerationStructure, AllocationAndMemory memory) {
            this.accelerationStructure = accelerationStructure;
            this.memory = memory;
        }

        abstract int type();

        void free() {
            vmaFreeMemory(vmaAllocator, memory.allocation);
            vkDestroyAccelerationStructureNV(device, accelerationStructure, null);
        }

        abstract T createSame(long accelerationStructure, AllocationAndMemory memory);
    }

    private static class BottomLevelAccelerationStructure extends AccelerationStructure<BottomLevelAccelerationStructure> {
        Geometry geometry;
        long handle;

        BottomLevelAccelerationStructure(long accelerationStructure, AllocationAndMemory memory,
                long handle, Geometry geometry) {
            super(accelerationStructure, memory);
            this.handle = handle;
            this.geometry = geometry;
        }

        @Override
        int type() {
            return VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_NV;
        }

        void free(boolean includingGeometry) {
            super.free();
            if (includingGeometry)
                geometry.free();
        }

        @Override
        BottomLevelAccelerationStructure createSame(long accelerationStructure, AllocationAndMemory memory) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer acHandle = stack.mallocLong(1);
                _CHECK_(vkGetAccelerationStructureHandleNV(device, accelerationStructure, acHandle),
                        "Failed to get acceleration structure handle");
                return new BottomLevelAccelerationStructure(accelerationStructure, memory, acHandle.get(0), geometry);
            }
        }
    }

    private static class TopLevelAccelerationStructure extends AccelerationStructure<TopLevelAccelerationStructure> {
        int instanceCount;

        TopLevelAccelerationStructure(long accelerationStructure, AllocationAndMemory memory, int instanceCount) {
            super(accelerationStructure, memory);
            this.instanceCount = instanceCount;
        }

        @Override
        int type() {
            return VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_NV;
        }

        @Override
        TopLevelAccelerationStructure createSame(long accelerationStructure, AllocationAndMemory memory) {
            return new TopLevelAccelerationStructure(accelerationStructure, memory, instanceCount);
        }
    }

    private static class Geometry {
        AllocationAndBuffer positions;
        AllocationAndBuffer normals;
        AllocationAndBuffer indices;
        VkGeometryNV.Buffer geometry;
        int indexCount;

        Geometry(AllocationAndBuffer positions, AllocationAndBuffer normals, AllocationAndBuffer indices,
                VkGeometryNV.Buffer geometry, int indexCount) {
            this.positions = positions;
            this.normals = normals;
            this.indices = indices;
            this.geometry = geometry;
            this.indexCount = indexCount;
        }

        void free() {
            positions.free();
            normals.free();
            indices.free();
            geometry.free();
        }
    }

    private static List<Face> createMesh() {
        int w = CHUNK_WIDTH, h = CHUNK_HEIGHT, d = CHUNK_DEPTH;
        byte[] ds = new byte[(w + 2) * (h + 2) * (d + 2)];
        float xzScale = 0.012343f, yScale = 0.13212f;
        for (int z = 0; z < d + 2; z++)
            for (int y = 0; y < h + 2; y++)
                for (int x = 0; x < w + 2; x++) {
                    float v = noise((x - 1) * xzScale, (y - 1) * yScale, (z - 1) * xzScale);
                    if (y == 0 || v > 0.4f) {
                        ds[x + (w + 2) * (y + z * (h + 2))] = 1;
                    }
                }
        List<Face> faces = new ArrayList<>();
        GreedyMeshing gm = new GreedyMeshing(0, 0, 0, h - 1, w, d);
        gm.mesh(ds, faces);
        System.out.println("Faces: " + faces.size());
        return faces;
    }

    private static AllocationAndBuffer createBufferFromResource(String resource, int usage) throws IOException {
        ByteBuffer buffer = ioResourceToByteBuffer(resource, 8192);
        return createBuffer(usage,
                memByteBuffer(MemoryUtil.memAddress(buffer), buffer.remaining()));
    }
    private static AllocationAndBuffer createSobolBuffer() throws IOException {
        return createBufferFromResource("org/lwjgl/demo/vulkan/sobol_256_256_4spp.data", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    }
    private static AllocationAndBuffer createScrambleBuffer() throws IOException {
        return createBufferFromResource("org/lwjgl/demo/vulkan/scramble_128_128_8_4spp.data", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    }
    private static AllocationAndBuffer createRankingBuffer() throws IOException {
        return createBufferFromResource("org/lwjgl/demo/vulkan/ranking_128_128_8_4spp.data", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
    }

    private static Geometry createGeometry(MemoryStack stack) {
        List<Face> faces = createMesh();
        DynamicByteBuffer positions = new DynamicByteBuffer();
        DynamicByteBuffer normals = new DynamicByteBuffer();
        DynamicByteBuffer indices = new DynamicByteBuffer();
        triangulate_Vsn16_Iu32(BITS_FOR_POSITIONS, faces, positions, normals, indices);
        AllocationAndBuffer positionsBuffer = createBuffer(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_RAY_TRACING_BIT_NV,
                memByteBuffer(positions.addr, positions.pos));
        positions.free();
        AllocationAndBuffer normalsBuffer = createBuffer(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                memByteBuffer(normals.addr, normals.pos));
        normals.free();
        AllocationAndBuffer indicesBuffer = createBuffer(VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                memByteBuffer(indices.addr, indices.pos));
        indices.free();
        VkGeometryNV.Buffer geometry = VkGeometryNV(1)
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_NV)
                .geometry(g -> g.triangles(t -> VkGeometryTrianglesNV(t)
                    .vertexData(positionsBuffer.buffer)
                    .vertexCount(faces.size() * 4)
                    .vertexStride(Short.BYTES * 3 + 4)
                    .vertexFormat(VK_FORMAT_R16G16B16_SNORM)
                    .indexData(indicesBuffer.buffer)
                    .indexCount(faces.size() * 6)
                    .indexType(VK_INDEX_TYPE_UINT32)).aabbs(VKFactory::VkGeometryAABBNV))
                .flags(VK_GEOMETRY_OPAQUE_BIT_NV);
        return new Geometry(positionsBuffer, normalsBuffer, indicesBuffer, geometry, faces.size() * 6);
    }

    private static Pipeline createRasterPipeline() throws IOException {
        try (MemoryStack stack = stackPush()) {
            VkPipelineInputAssemblyStateCreateInfo pInputAssemblyState = VkPipelineInputAssemblyStateCreateInfo(stack)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkPipelineRasterizationStateCreateInfo pRasterizationState = VkPipelineRasterizationStateCreateInfo(stack)
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(VK_CULL_MODE_BACK_BIT)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                    .lineWidth(1.0f);
            VkPipelineColorBlendAttachmentState.Buffer colorWriteMask = VkPipelineColorBlendAttachmentState(stack, 1)
                    .colorWriteMask(0xF); // <- RGBA
            VkPipelineColorBlendStateCreateInfo pColorBlendState = VkPipelineColorBlendStateCreateInfo(stack)
                    .pAttachments(colorWriteMask);
            VkPipelineViewportStateCreateInfo pViewportState = VkPipelineViewportStateCreateInfo(stack)
                    .viewportCount(1)
                    .scissorCount(1);
            VkPipelineDynamicStateCreateInfo pDynamicState = VkPipelineDynamicStateCreateInfo(stack)
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            VkPipelineDepthStencilStateCreateInfo pDepthStencilState = VkPipelineDepthStencilStateCreateInfo(stack)
                    .depthTestEnable(true)
                    .depthWriteEnable(true)
                    .depthCompareOp(VK_COMPARE_OP_GREATER)
                    .back(stencil -> stencil
                            .failOp(VK_STENCIL_OP_KEEP)
                            .passOp(VK_STENCIL_OP_KEEP)
                            .compareOp(VK_COMPARE_OP_ALWAYS));
            pDepthStencilState.front(pDepthStencilState.back());
            VkPipelineMultisampleStateCreateInfo pMultisampleState = VkPipelineMultisampleStateCreateInfo(stack)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineShaderStageCreateInfo.Buffer pStages = VkPipelineShaderStageCreateInfo(stack, 2);
            loadModule(stack, pStages.get(0), "raster.vs.glsl", VK_SHADER_STAGE_VERTEX_BIT);
            loadModule(stack, pStages.get(1), "raster.fs.glsl", VK_SHADER_STAGE_FRAGMENT_BIT);
            VkDescriptorSetLayoutBinding.Buffer layoutBinding = VkDescriptorSetLayoutBinding(stack, 1)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            VkDescriptorSetLayoutCreateInfo descriptorLayout = VkDescriptorSetLayoutCreateInfo(stack)
                    .pBindings(layoutBinding);
            LongBuffer pDescriptorSetLayout = stack.mallocLong(1);
            _CHECK_(vkCreateDescriptorSetLayout(device, descriptorLayout, null, pDescriptorSetLayout),
                    "Failed to create descriptor set layout");
            VkPipelineLayoutCreateInfo layoutCreateInfo = VkPipelineLayoutCreateInfo(stack)
                    .pSetLayouts(pDescriptorSetLayout);
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            _CHECK_(vkCreatePipelineLayout(device, layoutCreateInfo, null, pPipelineLayout),
                    "Failed to create pipeline layout");
            VkVertexInputBindingDescription.Buffer bindingDescriptor = VkVertexInputBindingDescription(stack, 2)
                    .apply(0, d -> d.binding(0).stride(3 * Short.BYTES + 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX))
                    .apply(1, d -> d.binding(1).stride(4 * Byte.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX));
            VkVertexInputAttributeDescription.Buffer attributeDescriptions = VkVertexInputAttributeDescription(stack, 3)
                    .apply(0, d -> d.binding(0).location(0).format(VK_FORMAT_R16G16B16_SNORM).offset(0))
                    .apply(1, d -> d.binding(0).location(1).format(VK_FORMAT_R8G8B8A8_SINT).offset(3 * Short.BYTES))
                    .apply(2, d -> d.binding(1).location(2).format(VK_FORMAT_R8G8B8A8_SNORM).offset(0));
            VkPipelineVertexInputStateCreateInfo pVertexInputState = VkPipelineVertexInputStateCreateInfo(stack)
                .pVertexBindingDescriptions(bindingDescriptor)
                .pVertexAttributeDescriptions(attributeDescriptions);
            VkGraphicsPipelineCreateInfo.Buffer pipelineCreateInfo = VkGraphicsPipelineCreateInfo(stack, 1)
                    .layout(pPipelineLayout.get(0))
                    .renderPass(renderPass)
                    .pVertexInputState(pVertexInputState)
                    .pInputAssemblyState(pInputAssemblyState)
                    .pRasterizationState(pRasterizationState)
                    .pColorBlendState(pColorBlendState)
                    .pMultisampleState(pMultisampleState)
                    .pViewportState(pViewportState)
                    .pDepthStencilState(pDepthStencilState)
                    .pStages(pStages)
                    .pDynamicState(pDynamicState);
            LongBuffer pPipelines = stack.mallocLong(1);
            _CHECK_(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineCreateInfo, null, pPipelines),
                    "Failed to create raster pipeline");
            pStages.forEach(stage -> vkDestroyShaderModule(device, stage.module(), null));
            return new Pipeline(pPipelineLayout.get(0), pDescriptorSetLayout.get(0), pPipelines.get(0));
        }
    }

    private static long createRasterRenderPass() {
        try (MemoryStack stack = stackPush()) {
            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo(stack)
                    .pAttachments(VkAttachmentDescription(stack, 2)
                            .apply(0, d -> d
                                    .format(VK_FORMAT_R8G8_SNORM)
                                    .samples(VK_SAMPLE_COUNT_1_BIT)
                                    .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                                    .finalLayout(VK_IMAGE_LAYOUT_GENERAL))
                            .apply(1, d -> d
                                    .format(swapchain.surfaceFormat.depthFormat)
                                    .samples(VK_SAMPLE_COUNT_1_BIT)
                                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)))
                    .pSubpasses(VkSubpassDescription(stack, 1)
                            .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                            .colorAttachmentCount(1)
                            .pColorAttachments(VkAttachmentReference(stack, 1)
                                    .attachment(0)
                                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL))
                            .pDepthStencilAttachment(VkAttachmentReference(stack)
                                    .attachment(1)
                                    .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)));
            LongBuffer pRenderPass = stack.mallocLong(1);
            _CHECK_(vkCreateRenderPass(device, renderPassInfo, null, pRenderPass), "Failed to create render pass");
            return pRenderPass.get(0);
        }
    }

    private static VkCommandBuffer[] createRasterCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            VkClearValue.Buffer clearValues = VkClearValue(stack, 2);
            clearValues.apply(0, v -> v.color().uint32(0, 0).uint32(1, 0).uint32(2, 0).uint32(3, 0))
                       .apply(1, v -> v.depthStencil().depth(0.0f).stencil(0));
            VkRenderPassBeginInfo renderPassBeginInfo = VkRenderPassBeginInfo(stack).renderPass(renderPass)
                    .pClearValues(clearValues)
                    .renderArea(a -> a.extent().set(swapchain.width, swapchain.height));
            VkCommandBuffer[] cmdBuffers = createCommandBuffers(commandPool, swapchain.images.length);
            VkViewport.Buffer viewport = VkViewport(stack, 1).height(swapchain.height).width(swapchain.width)
                    .minDepth(1.0f).maxDepth(0.0f);
            VkRect2D.Buffer scissor = VkRect2D(stack, 1).extent(e -> e.set(swapchain.width, swapchain.height));
            for (int i = 0; i < swapchain.images.length; i++) {
                renderPassBeginInfo.framebuffer(framebuffers[i]);
                VkCommandBuffer cmdBuffer = cmdBuffers[i];
                vkCmdBeginRenderPass(cmdBuffer, renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE);
                vkCmdSetViewport(cmdBuffer, 0, viewport);
                vkCmdSetScissor(cmdBuffer, 0, scissor);
                vkCmdBindDescriptorSets(cmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, rasterPipeline.pipelineLayout, 0,
                        stack.longs(rasterDescriptorSets.sets[i]), null);
                vkCmdBindPipeline(cmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, rasterPipeline.pipeline);
                vkCmdBindVertexBuffers(cmdBuffer, 0, new long[] {geometry.positions.buffer, geometry.normals.buffer}, new long[]{0, 0});
                vkCmdBindIndexBuffer(cmdBuffer, geometry.indices.buffer, 0, VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexed(cmdBuffer, geometry.indexCount, 1, 0, 0, 0);
                vkCmdEndRenderPass(cmdBuffer);
                _CHECK_(vkEndCommandBuffer(cmdBuffer), "Failed to end command buffer");
            }
            return cmdBuffers;
        }
    }

    private static VkCommandBuffer[] createPresentCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer[] cmdBuffers = createCommandBuffers(commandPool, swapchain.images.length);
            for (int i = 0; i < swapchain.images.length; i++) {
                VkCommandBuffer cmdBuffer = cmdBuffers[i];
                vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null,
                        VkImageMemoryBarrier(stack)
                                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                                .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                                .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                                .image(rayTracingImages[i].image)
                                .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1).levelCount(1)));
                vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null,
                        VkImageMemoryBarrier(stack)
                                .srcAccessMask(0)
                                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                                .image(swapchain.images[i])
                                .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1).levelCount(1)));
                if (swapchain.surfaceFormat.colorFormat == rayTracingImages[i].format) {
                    vkCmdCopyImage(cmdBuffer, rayTracingImages[i].image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 
                            swapchain.images[i], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VkImageCopy(stack, 1)
                            .dstSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                            .extent(e -> e.set(swapchain.width, swapchain.height, 1))
                            .srcSubresource(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)));
                } else {
                    vkCmdBlitImage(cmdBuffer, rayTracingImages[i].image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            swapchain.images[i], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            VkImageBlit(stack, 1)
                                    .dstOffsets(off -> off.apply(1, o -> o.set(swapchain.width, swapchain.height, 1)))
                                    .srcOffsets(off -> off.apply(1, o -> o.set(swapchain.width, swapchain.height, 1)))
                                    .dstSubresource(sr -> sr.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1))
                                    .srcSubresource(sr -> sr.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)),
                            VK_FILTER_NEAREST);
                }
                vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV, 0, null, null,
                        VkImageMemoryBarrier(stack)
                                .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                                .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                                .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                                .image(rayTracingImages[i].image)
                                .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1).levelCount(1)));
                vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
                        null, null,
                        VkImageMemoryBarrier(stack)
                                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                                .dstAccessMask(0)
                                .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                                .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                                .image(swapchain.images[i])
                                .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1).levelCount(1)));
                _CHECK_(vkEndCommandBuffer(cmdBuffer), "Failed to end command buffer");
            }
            return cmdBuffers;
        }
    }

    private static long[] createFramebuffers() {
        if (framebuffers != null) {
            for (long framebuffer : framebuffers)
                vkDestroyFramebuffer(device, framebuffer, null);
        }
        try (MemoryStack stack = stackPush()) {
            LongBuffer pAttachments = stack.mallocLong(2);
            VkFramebufferCreateInfo fci = VkFramebufferCreateInfo(stack)
                    .pAttachments(pAttachments)
                    .height(swapchain.height)
                    .width(swapchain.width)
                    .layers(1)
                    .renderPass(renderPass);
            long[] framebuffers = new long[swapchain.images.length];
            LongBuffer pFramebuffer = stack.mallocLong(1);
            for (int i = 0; i < swapchain.images.length; i++) {
                pAttachments.put(0, normalImages[i].imageView).put(1, depthStencilImages[i].imageView);
                _CHECK_(vkCreateFramebuffer(device, fci, null, pFramebuffer), "Failed to create framebuffer");
                framebuffers[i] = pFramebuffer.get(0);
            }
            return framebuffers;
        }
    }

    /**
     * VkGeometryInstanceNV
     */
    private static class GeometryInstance {
        static final int SIZEOF = Float.BYTES * 4 * 3 + Integer.BYTES + Integer.BYTES + Long.BYTES;

        Matrix4x3f transform = new Matrix4x3f();
        int instanceCustomId;
        byte mask;
        int instanceOffset;
        byte flags;
        long accelerationStructureHandle;

        ByteBuffer write(ByteBuffer bb) {
            transform.getTransposed(bb);
            bb.position(bb.position() + Float.BYTES * 4 * 3);
            bb.putInt(((int) mask << 24) | instanceCustomId);
            bb.putInt(((int) flags << 24) | instanceOffset);
            bb.putLong(accelerationStructureHandle);
            return bb;
        }
    }

    private static BottomLevelAccelerationStructure createBottomLevelAccelerationStructure(
            Geometry geometry) {
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureInfoNV pInfo = VkAccelerationStructureInfoNV(stack)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_NV)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_NV |
                           VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_NV)
                    .pGeometries(geometry.geometry);
            VkCommandBuffer cmdBuffer = createCommandBuffer(commandPoolTransient);
            vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV, 0,
                    VkMemoryBarrier(stack)
                            .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_NV | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV),
                    null, null);
            LongBuffer accelerationStructure = stack.mallocLong(1);
            _CHECK_(vkCreateAccelerationStructureNV(device, VkAccelerationStructureCreateInfoNV(stack)
                            .info(pInfo), null, accelerationStructure),
                    "Failed to create acceleration structure");
            AllocationAndMemory allocation = allocateDeviceMemory(memoryRequirements(stack, accelerationStructure.get(0),
                    VK_ACCELERATION_STRUCTURE_MEMORY_REQUIREMENTS_TYPE_OBJECT_NV));
            _CHECK_(vkBindAccelerationStructureMemoryNV(device, VkBindAccelerationStructureMemoryInfoNV(stack)
                            .accelerationStructure(accelerationStructure.get(0))
                            .memory(allocation.memory)
                            .memoryOffset(allocation.offset)),
                    "Failed to bind acceleration structure memory");
            ByteBuffer accelerationStructureHandle = stack.malloc(8);
            _CHECK_(vkGetAccelerationStructureHandleNV(device, accelerationStructure.get(0),
                    accelerationStructureHandle), "Failed to get acceleration structure handle");
            AllocationAndBuffer scratchBuffer = createRayTracingBuffer(
                    memoryRequirements(stack, accelerationStructure.get(0),
                            VK_ACCELERATION_STRUCTURE_MEMORY_REQUIREMENTS_TYPE_BUILD_SCRATCH_NV).size());
            vkCmdBuildAccelerationStructureNV(
                    cmdBuffer,
                    pInfo,
                    VK_NULL_HANDLE,
                    0,
                    false,
                    accelerationStructure.get(0),
                    VK_NULL_HANDLE,
                    scratchBuffer.buffer,
                    0);
            vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV, 0,
                    VkMemoryBarrier(stack)
                            .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_NV | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV)
                            .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV),
                    null, null);
            submitCommandBuffer(cmdBuffer, true, () -> {
                vkFreeCommandBuffers(device, commandPoolTransient, cmdBuffer);
                scratchBuffer.free();
            });
            return new BottomLevelAccelerationStructure(accelerationStructure.get(0), allocation,
                    accelerationStructureHandle.get(0), geometry);
        }
    }

    private static void waitForFenceAndDestroy(long fence) {
        _CHECK_(vkWaitForFences(device, fence, true, -1), "Failed to wait for fence");
        vkDestroyFence(device, fence, null);
    }

    private static <T extends AccelerationStructure<T>> T compressAccelerationStructure(T src) {
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureInfoNV pInfoCompacted = VkAccelerationStructureInfoNV(stack)
                    .type(src.type())
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_NV);
            VkCommandBuffer cmdBuffer = createCommandBuffer(commandPoolTransient);
            vkCmdWriteAccelerationStructuresPropertiesNV(cmdBuffer, stack.longs(src.accelerationStructure),
                    VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_NV, queryPool, 0);
            long fence = submitCommandBuffer(cmdBuffer, true, null);
            waitForFenceAndDestroy(fence);
            vkFreeCommandBuffers(device, commandPoolTransient, cmdBuffer);
            LongBuffer compactedSize = stack.mallocLong(1);
            vkGetQueryPoolResults(device, queryPool, 0, 1, compactedSize, Long.BYTES,
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
            VkMemoryRequirements memoryRequirementsCompacted = src.memory.memoryRequirementsOfSize(stack,
                    compactedSize.get(0));
            AllocationAndMemory allocationCompacted = allocateDeviceMemory(memoryRequirementsCompacted);
            System.out.println(String.format("Compacting %s from %.2f KB down to %.2f KB", src.getClass().getSimpleName(), 
                    src.memory.size / 1024.0, allocationCompacted.size / 1024.0));
            LongBuffer accelerationStructureCompacted = stack.mallocLong(1);
            _CHECK_(vkCreateAccelerationStructureNV(device,
                    VkAccelerationStructureCreateInfoNV(stack)
                        .info(pInfoCompacted)
                        .compactedSize(compactedSize.get(0)), null,
                    accelerationStructureCompacted), "Failed to create acceleration structure");
            _CHECK_(vkBindAccelerationStructureMemoryNV(device,
                    VkBindAccelerationStructureMemoryInfoNV(stack)
                            .accelerationStructure(accelerationStructureCompacted.get(0))
                            .memory(allocationCompacted.memory).memoryOffset(allocationCompacted.offset)),
                    "Failed to bind acceleration structure memory");
            VkCommandBuffer cmdBuffer2 = createCommandBuffer(commandPoolTransient);
            vkCmdCopyAccelerationStructureNV(cmdBuffer2, accelerationStructureCompacted.get(0),
                    src.accelerationStructure, VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_NV);
            vkCmdPipelineBarrier(cmdBuffer,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV, 0,
                    VkMemoryBarrier(stack)
                            .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_NV | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV)
                            .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV),
                    null, null);
            submitCommandBuffer(cmdBuffer2, true, () -> {
                vkFreeCommandBuffers(device, commandPoolTransient, cmdBuffer2);
                src.free();
            });
            return src.createSame(accelerationStructureCompacted.get(0), allocationCompacted);
        }
    }

    private static VkMemoryRequirements memoryRequirements(MemoryStack s, long accelerationStructure, int requirementType) {
        VkMemoryRequirements2KHR memoryRequirements2 = VkMemoryRequirements2KHR(s);
        try (MemoryStack stack = stackPush()) {
            vkGetAccelerationStructureMemoryRequirementsNV(device,
                    VkAccelerationStructureMemoryRequirementsInfoNV(stack)
                    .type(requirementType)
                    .accelerationStructure(accelerationStructure), memoryRequirements2);
            return memoryRequirements2.memoryRequirements();
        }
    }

    private static long createQueryPool() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pQueryPool = stack.mallocLong(1);
            _CHECK_(vkCreateQueryPool(device,
                    VkQueryPoolCreateInfo(stack).queryCount(1)
                            .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_NV),
                    null, pQueryPool), "Failed to create query pool");
            return pQueryPool.get(0);
        }
    }

    private static TopLevelAccelerationStructure createTopLevelAccelerationStructure() {
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureInfoNV accelerationStructureInfo = VkAccelerationStructureInfoNV(stack)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_NV)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_NV |
                           VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_NV).instanceCount(1);
            LongBuffer accelerationStructure = stack.mallocLong(1);
            _CHECK_(vkCreateAccelerationStructureNV(device,
                    VkAccelerationStructureCreateInfoNV(stack).info(accelerationStructureInfo), null,
                    accelerationStructure), "Failed to create acceleration structure");
            AllocationAndMemory allocation = allocateDeviceMemory(memoryRequirements(stack,
                    accelerationStructure.get(0), VK_ACCELERATION_STRUCTURE_MEMORY_REQUIREMENTS_TYPE_OBJECT_NV));
            _CHECK_(vkBindAccelerationStructureMemoryNV(device,
                    VkBindAccelerationStructureMemoryInfoNV(stack)
                            .accelerationStructure(accelerationStructure.get(0))
                            .memory(allocation.memory)
                            .memoryOffset(allocation.offset)),
                    "Failed to bind acceleration structure memory");
            ByteBuffer instanceData = stack.malloc(GeometryInstance.SIZEOF);
            GeometryInstance inst = new GeometryInstance();
            inst.accelerationStructureHandle = blas.handle;
            inst.mask = (byte) 0x1;
            inst.transform.scaling(POSITION_SCALE);
            inst.write(instanceData);
            instanceData.flip();
            AllocationAndBuffer instanceMemory = createBuffer(VK_BUFFER_USAGE_RAY_TRACING_BIT_NV, instanceData);
            VkCommandBuffer cmdBuffer = createCommandBuffer(commandPoolTransient);
            // Barrier for previous bottom-level acceleration structure building/compression
            // as well as for transfer of GeometryInstance for top-level acceleration structure
            vkCmdPipelineBarrier(cmdBuffer,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV, 0,
                    VkMemoryBarrier(stack)
                            .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_NV | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV | VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV),
                    null, null);
            AllocationAndBuffer scratchBuffer = createRayTracingBuffer(
                    memoryRequirements(stack, accelerationStructure.get(0),
                            VK_ACCELERATION_STRUCTURE_MEMORY_REQUIREMENTS_TYPE_BUILD_SCRATCH_NV).size());
            vkCmdBuildAccelerationStructureNV(
                    cmdBuffer,
                    accelerationStructureInfo,
                    instanceMemory.buffer,
                    0,
                    false,
                    accelerationStructure.get(0),
                    VK_NULL_HANDLE,
                    scratchBuffer.buffer,
                    0);
            vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV, 0,
                    VkMemoryBarrier(stack)
                            .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_NV | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV)
                            .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV),
                    null, null);
            submitCommandBuffer(cmdBuffer, true, () -> {
                vkFreeCommandBuffers(device, commandPoolTransient, cmdBuffer);
                scratchBuffer.free();
                instanceMemory.free();
            });
            return new TopLevelAccelerationStructure(accelerationStructure.get(0), allocation, 1);
        }
    }

    private static class AllocationAndMemory {
        long allocation;
        long memory;
        long offset;
        long alignment;
        int memoryTypeBits;
        long size;

        AllocationAndMemory(long allocation, long memory, long offset, long alignment, int memoryTypeBits, long size) {
            this.allocation = allocation;
            this.memory = memory;
            this.offset = offset;
            this.alignment = alignment;
            this.memoryTypeBits = memoryTypeBits;
            this.size = size;
        }

        VkMemoryRequirements memoryRequirementsOfSize(MemoryStack stack, long size) {
            VkMemoryRequirements res = VkMemoryRequirements.mallocStack(stack);
            memPutLong(res.address() + VkMemoryRequirements.SIZE, size);
            memPutLong(res.address() + VkMemoryRequirements.ALIGNMENT, alignment);
            memPutInt(res.address() + VkMemoryRequirements.MEMORYTYPEBITS, memoryTypeBits);
            return res;
        }
    }

    private static AllocationAndMemory allocateDeviceMemory(VkMemoryRequirements memoryRequirements) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo pAllocationInfo = VmaAllocationInfo(stack);
            _CHECK_(vmaAllocateMemory(vmaAllocator, memoryRequirements, VmaAllocationCreateInfo(stack)
                        .usage(VMA_MEMORY_USAGE_GPU_ONLY)
                        .flags(VMA_ALLOCATION_CREATE_STRATEGY_BEST_FIT_BIT)
                        .preferredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT),
                    pAllocation, pAllocationInfo), "Failed to allocate memory");
            return new AllocationAndMemory(pAllocation.get(0), pAllocationInfo.deviceMemory(), pAllocationInfo.offset(),
                    memoryRequirements.alignment(), memoryRequirements.memoryTypeBits(), memoryRequirements.size());
        }
    }

    private static class Pipeline {
        long pipelineLayout;
        long descriptorSetLayout;
        long pipeline;

        Pipeline(long pipelineLayout, long descriptorSetLayout, long pipeline) {
            this.pipelineLayout = pipelineLayout;
            this.descriptorSetLayout = descriptorSetLayout;
            this.pipeline = pipeline;
        }

        void free() {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            vkDestroyPipeline(device, pipeline, null);
        }
    }

    private static Pipeline createRayTracingPipeline() throws IOException {
        int numDescriptors = 10;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSetLayout = stack.mallocLong(1);
            _CHECK_(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo(stack)
                        .pBindings(VkDescriptorSetLayoutBinding(stack, numDescriptors)
                                .apply(dslb -> dslb
                                        .binding(0)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_NV)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_NV))
                                .apply(dslb -> dslb
                                        .binding(1)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_NV))
                                .apply(dslb -> dslb
                                        .binding(2)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_NV))
                                .apply(dslb -> dslb
                                        .binding(3)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_NV))
                                .apply(dslb -> dslb
                                        .binding(4)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_NV))
                                .apply(dslb -> dslb
                                        .binding(5)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_NV))
                                .apply(dslb -> dslb
                                        .binding(6)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_NV))
                                .apply(dslb -> dslb
                                        .binding(7)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_NV))
                                .apply(dslb -> dslb
                                        .binding(8)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_NV))
                                .apply(dslb -> dslb
                                        .binding(9)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_NV))
                                .flip()),
                    null, pSetLayout),
                    "Failed to create descriptor set layout");
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            _CHECK_(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo(stack)
                            .pSetLayouts(pSetLayout), null, pPipelineLayout),
                    "Failed to create pipeline layout");
            VkPipelineShaderStageCreateInfo.Buffer pStages = VkPipelineShaderStageCreateInfo(stack, 3);
            loadModule(stack, pStages.get(0), "raygen-hybrid.glsl", VK_SHADER_STAGE_RAYGEN_BIT_NV);
            loadModule(stack, pStages.get(1), "raymiss.glsl", VK_SHADER_STAGE_MISS_BIT_NV);
            loadModule(stack, pStages.get(2), "closesthit.glsl", VK_SHADER_STAGE_CLOSEST_HIT_BIT_NV);
            VkRayTracingShaderGroupCreateInfoNV.Buffer groups = VkRayTracingShaderGroupCreateInfoNV(3, stack);
            groups.apply(0, g ->
                        g.type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_NV)
                         .generalShader(0))
                  .apply(1, g ->
                        g.type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_NV)
                         .generalShader(1))
                  .apply(2, g ->
                        g.type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_NV)
                         .closestHitShader(2));
            LongBuffer pPipelines = stack.mallocLong(1);
            _CHECK_(vkCreateRayTracingPipelinesNV(device, VK_NULL_HANDLE, VkRayTracingPipelineCreateInfoNV(stack)
                            .pStages(pStages)
                            .maxRecursionDepth(0)
                            .pGroups(groups)
                            .layout(pPipelineLayout.get(0)), null, pPipelines),
                    "Failed to create ray tracing pipeline");
            pStages.forEach(stage -> vkDestroyShaderModule(device, stage.module(), null));
            if (rayTracingPipeline != null)
                rayTracingPipeline.free();
            return new Pipeline(pPipelineLayout.get(0), pSetLayout.get(0), pPipelines.get(0));
        }
    }

    private static void loadModule(MemoryStack stack, VkPipelineShaderStageCreateInfo pStage,
                                   String classpathResource, int vkShaderStageRaygenBitNv) throws IOException {
        String pack = NvRayTracingHybridExample.class.getPackage().getName().replace('.', '/');
        loadShader(pStage, null, stack, device, pack + "/" + classpathResource, vkShaderStageRaygenBitNv);
    }

    private static class DescriptorSets {
        long descriptorPool;
        long[] sets;

        DescriptorSets(long descriptorPool, long[] sets) {
            this.descriptorPool = descriptorPool;
            this.sets = sets;
        }

        void free() {
            vkDestroyDescriptorPool(device, descriptorPool, null);
        }
    }

    private static LongBuffer repeat(MemoryStack stack, long value, int count) {
        LongBuffer ret = stack.mallocLong(count);
        for (int i = 0; i < count; i++) {
            ret.put(i, value);
        }
        return ret;
    }

    private static DescriptorSets createRayTracingDescriptorSets() {
        if (rayTracingDescriptorSets != null) {
            rayTracingDescriptorSets.free();
        }
        int numSets = swapchain.images.length;
        int numDescriptors = 10;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pDescriptorPool = stack.mallocLong(1);
            _CHECK_(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo(stack)
                            .pPoolSizes(VkDescriptorPoolSize(stack, numDescriptors)
                                    .apply(0, dps -> dps.type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_NV)
                                            .descriptorCount(numSets))
                                    .apply(1, dps -> dps.type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                            .descriptorCount(numSets))
                                    .apply(2, dps -> dps.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                            .descriptorCount(numSets))
                                    .apply(3, dps -> dps.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                            .descriptorCount(numSets))
                                    .apply(4, dps -> dps.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                            .descriptorCount(numSets))
                                    .apply(5, dps -> dps.type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                            .descriptorCount(numSets))
                                    .apply(6, dps -> dps.type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                            .descriptorCount(numSets))
                                    .apply(7, dps -> dps.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                            .descriptorCount(numSets))
                                    .apply(8, dps -> dps.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                            .descriptorCount(numSets))
                                    .apply(9, dps -> dps.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                            .descriptorCount(numSets)))
                            .maxSets(numSets), null, pDescriptorPool),
                    "Failed to create descriptor pool");
            VkDescriptorSetAllocateInfo descriptorSetAllocateInfo = VkDescriptorSetAllocateInfo(stack)
                    .descriptorPool(pDescriptorPool.get(0))
                    .pSetLayouts(repeat(stack, rayTracingPipeline.descriptorSetLayout, numSets));
            LongBuffer pDescriptorSets = stack.mallocLong(numSets);
            _CHECK_(vkAllocateDescriptorSets(device, descriptorSetAllocateInfo, pDescriptorSets),
                    "Failed to allocate descriptor set");
            long[] sets = new long[pDescriptorSets.remaining()];
            pDescriptorSets.get(sets, 0, sets.length);
            VkWriteDescriptorSet.Buffer writeDescriptorSet = VkWriteDescriptorSet(stack, numDescriptors * numSets);
            for (int i = 0; i < numSets; i++) {
                final int idx = i;
                writeDescriptorSet
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_NV)
                                .dstBinding(0)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pNext(VkWriteDescriptorSetAccelerationStructureNV(stack)
                                        .pAccelerationStructures(stack.longs(tlas.accelerationStructure))
                                        .address()))
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .dstBinding(1)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pImageInfo(VkDescriptorImageInfo(stack, 1)
                                        .imageView(rayTracingImages[idx].imageView)
                                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL)))
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                .dstBinding(2)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo(stack, 1)
                                        .buffer(rayTracingUbos[idx].buffer)
                                        .range(VK_WHOLE_SIZE)))
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .dstBinding(3)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo(stack, 1)
                                        .buffer(geometry.normals.buffer)
                                        .range(VK_WHOLE_SIZE)))
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .dstBinding(4)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo(stack, 1)
                                        .buffer(geometry.indices.buffer)
                                        .range(VK_WHOLE_SIZE)))
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .dstBinding(5)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pImageInfo(VkDescriptorImageInfo(stack, 1)
                                        .imageView(normalImages[idx].imageView)
                                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL)))
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                .dstBinding(6)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pImageInfo(VkDescriptorImageInfo(stack, 1)
                                        .imageView(depthStencilImages[idx].imageView)
                                        .sampler(sampler)
                                        .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)))
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .dstBinding(7)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo(stack, 1)
                                        .buffer(sobolBuffer.buffer)
                                        .range(VK_WHOLE_SIZE)))
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .dstBinding(8)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo(stack, 1)
                                        .buffer(scrambleBuffer.buffer)
                                        .range(VK_WHOLE_SIZE)))
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .dstBinding(9)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo(stack, 1)
                                        .buffer(rankingBuffer.buffer)
                                        .range(VK_WHOLE_SIZE)));
            }
            vkUpdateDescriptorSets(device, writeDescriptorSet.flip(), null);
            return new DescriptorSets(pDescriptorPool.get(0), sets);
        }
    }

    private static DescriptorSets createRasterDescriptorSets() {
        if (rasterDescriptorSets != null) {
            rasterDescriptorSets.free();
        }
        int numSets = swapchain.images.length;
        int numDescriptors = 1;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pDescriptorPool = stack.mallocLong(1);
            _CHECK_(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo(stack)
                            .pPoolSizes(VkDescriptorPoolSize(stack, numDescriptors)
                                    .apply(0, dps -> dps.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                            .descriptorCount(numSets)))
                            .maxSets(numSets), null, pDescriptorPool),
                    "Failed to create descriptor pool");
            VkDescriptorSetAllocateInfo descriptorSetAllocateInfo = VkDescriptorSetAllocateInfo(stack)
                    .descriptorPool(pDescriptorPool.get(0))
                    .pSetLayouts(repeat(stack, rasterPipeline.descriptorSetLayout, numSets));
            LongBuffer pDescriptorSets = stack.mallocLong(numSets);
            _CHECK_(vkAllocateDescriptorSets(device, descriptorSetAllocateInfo, pDescriptorSets),
                    "Failed to allocate descriptor set");
            long[] sets = new long[pDescriptorSets.remaining()];
            pDescriptorSets.get(sets, 0, sets.length);
            VkWriteDescriptorSet.Buffer writeDescriptorSet = VkWriteDescriptorSet(stack, numDescriptors * numSets);
            for (int i = 0; i < numSets; i++) {
                final int idx = i;
                writeDescriptorSet
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                .dstBinding(0)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo(stack, 1)
                                        .buffer(rasterUbos[idx].buffer)
                                        .range(VK_WHOLE_SIZE)));
            }
            vkUpdateDescriptorSets(device, writeDescriptorSet.flip(), null);
            return new DescriptorSets(pDescriptorPool.get(0), sets);
        }
    }

    private static AllocationAndBuffer createRayTracingShaderBindingTable() {
        if (rayTracingShaderBindingTable != null) {
            rayTracingShaderBindingTable.free();
        }
        try (MemoryStack stack = stackPush()) {
            int sbtSize = rayTracingProperties.shaderGroupHandleSize * 3;
            ByteBuffer handles = stack.malloc(sbtSize);
            _CHECK_(vkGetRayTracingShaderGroupHandlesNV(device, rayTracingPipeline.pipeline, 0, 3, handles),
                    "Failed to obtain ray tracing group handles");
            return createBuffer(VK_BUFFER_USAGE_RAY_TRACING_BIT_NV, handles);
        }
    }

    private static VkCommandBuffer[] createRayTracingCommandBuffers() {
        VkCommandBuffer[] cmdBuffers = createCommandBuffers(commandPool, swapchain.images.length);
        for (int i = 0; i < swapchain.images.length; i++) {
            VkCommandBuffer cmdBuf = cmdBuffers[i];
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_RAY_TRACING_NV, rayTracingPipeline.pipeline);
            try (MemoryStack stack = stackPush()) {
                vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_RAY_TRACING_NV,
                        rayTracingPipeline.pipelineLayout, 0, stack.longs(rayTracingDescriptorSets.sets[i]), null);
            }
            int stride = rayTracingProperties.shaderGroupHandleSize;
            vkCmdTraceRaysNV(cmdBuf,
                    rayTracingShaderBindingTable.buffer, 0,
                    rayTracingShaderBindingTable.buffer, stride, stride,
                    rayTracingShaderBindingTable.buffer, stride * 2, stride,
                    VK_NULL_HANDLE, 0, 0,
                    swapchain.width, swapchain.height, 1);
            _CHECK_(vkEndCommandBuffer(cmdBuf), "Failed to end command buffer");
        }
        return cmdBuffers;
    }

    private static PointerBuffer initGlfw() throws AssertionError {
        if (!glfwInit())
            throw new AssertionError("Failed to initialize GLFW");
        if (!glfwVulkanSupported())
            throw new AssertionError("GLFW failed to find the Vulkan loader");
        PointerBuffer requiredExtensions = glfwGetRequiredInstanceExtensions();
        if (requiredExtensions == null)
            throw new AssertionError("Failed to find list of required Vulkan extensions");
        return requiredExtensions;
    }

    private static long createSurface() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            _CHECK_(glfwCreateWindowSurface(instance, windowAndCallbacks.window, null, pSurface),
                    "Failed to create surface");
            return pSurface.get(0);
        }
    }
    private static class WindowAndCallbacks {
        long window;
        int width;
        int height;

        GLFWKeyCallback keyCallback;
        GLFWCursorPosCallback cpCallback;
        GLFWMouseButtonCallback mbCallback;

        WindowAndCallbacks(long window, GLFWKeyCallback keyCallback, GLFWCursorPosCallback cpCallback, GLFWMouseButtonCallback mbCallback) {
            this.window = window;
            this.keyCallback = keyCallback;
            this.cpCallback = cpCallback;
            this.mbCallback = mbCallback;
        }
        void free() {
            glfwDestroyWindow(window);
            keyCallback.free();
            cpCallback.free();
            mbCallback.free();
        }
    }

    private static WindowAndCallbacks createWindow() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        long window = glfwCreateWindow(INITIAL_WINDOW_WIDTH, INITIAL_WINDOW_HEIGHT, "Hybrid Ray Tracing", NULL, NULL);
        GLFWKeyCallback keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
                if (key == -1)
                    return;
                keydown[key] = action == GLFW_PRESS || action == GLFW_REPEAT;
            }
        };
        glfwSetKeyCallback(window, keyCallback);
        GLFWCursorPosCallback cpCallback = new GLFWCursorPosCallback() {
            @Override
            public void invoke(long window, double x, double y) {
                if (mouseDown) {
                    float deltaX = (float) x - mouseX;
                    float deltaY = (float) y - mouseY;
                    viewMatrix.rotateLocalY(deltaX * 0.01f);
                    viewMatrix.rotateLocalX(deltaY * 0.01f);
                }
                mouseX = (float) x;
                mouseY = (float) y;
            }
        };
        glfwSetCursorPosCallback(window, cpCallback);
        GLFWMouseButtonCallback mbCallback = new GLFWMouseButtonCallback() {
            public void invoke(long window, int button, int action, int mods) {
                if (action == GLFW_PRESS) {
                    mouseDown = true;
                } else if (action == GLFW_RELEASE) {
                    mouseDown = false;
                }
            }
        };
        glfwSetMouseButtonCallback(window, mbCallback);
        return new WindowAndCallbacks(window, keyCallback, cpCallback, mbCallback);
    }

    private static AllocationAndBuffer[] createUniformBufferObjects(int size) {
        AllocationAndBuffer[] ret = new AllocationAndBuffer[swapchain.images.length];
        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < ret.length; i++) {
                LongBuffer pBuffer = stack.mallocLong(1);
                PointerBuffer pAllocation = stack.mallocPointer(1);
                _CHECK_(vmaCreateBuffer(vmaAllocator,
                        VkBufferCreateInfo(stack).size(size).usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT),
                        VmaAllocationCreateInfo(stack).usage(VMA_MEMORY_USAGE_CPU_TO_GPU), pBuffer, pAllocation, null),
                        "Failed to allocate buffer");
                AllocationAndBuffer a = new AllocationAndBuffer(pAllocation.get(0), pBuffer.get(0));
                a.map();
                ret[i] = a;
            }
            return ret;
        }
    }

    private static void updateCamera(float dt) {
        float factor = 5.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 20.0f;
        if (keydown[GLFW_KEY_W]) {
            viewMatrix.translateLocal(0, 0, factor * dt);
        }
        if (keydown[GLFW_KEY_S]) {
            viewMatrix.translateLocal(0, 0, -factor * dt);
        }
        if (keydown[GLFW_KEY_A]) {
            viewMatrix.translateLocal(factor * dt, 0, 0);
        }
        if (keydown[GLFW_KEY_D]) {
            viewMatrix.translateLocal(-factor * dt, 0, 0);
        }
        if (keydown[GLFW_KEY_LEFT_CONTROL]) {
            viewMatrix.translateLocal(0, factor * dt, 0);
        }
        if (keydown[GLFW_KEY_SPACE]) {
            viewMatrix.translateLocal(0, -factor * dt, 0);
        }
        viewMatrix.withLookAtUp(0, 1, 0);
        projMatrix
                .scaling(1, -1, 1) // <- Y up
                .perspective((float) Math.toRadians(60),
                        (float) windowAndCallbacks.width / windowAndCallbacks.height, 1E-1f, 1E4f, true);
        viewProjMatrix.set(projMatrix).mul(viewMatrix).scale(POSITION_SCALE);
    }

    private static void updateRayTracingUniformBufferObject(int idx) {
        projMatrix.invert(invProjMatrix);
        viewMatrix.invert(invViewMatrix);
        ByteBuffer bb = memByteBuffer(rayTracingUbos[idx].mapped, Float.BYTES * 16 * 2);
        invProjMatrix.get(0, bb);
        invViewMatrix.get4x4(Float.BYTES * 16, bb);
        rayTracingUbos[idx].flushMapped(0, Float.BYTES * 16 * 2);
    }

    private static void updateRasterUniformBufferObject(int idx) {
        ByteBuffer bb = memByteBuffer(rasterUbos[idx].mapped, Float.BYTES * (16 + 4));
        viewProjMatrix.get(0, bb);
        bb.putFloat(Float.BYTES * 16, POSITION_SCALE);
        rasterUbos[idx].flushMapped(0, Float.BYTES * (16 + 4));
    }

    private static void createSyncObjects() {
        imageAcquireSemaphores = new long[swapchain.images.length];
        renderCompleteSemaphores = new long[swapchain.images.length];
        renderFences = new long[swapchain.images.length];
        for (int i = 0; i < swapchain.images.length; i++) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer pSemaphore = stack.mallocLong(1);
                _CHECK_(vkCreateSemaphore(device, VkSemaphoreCreateInfo(stack), null, pSemaphore),
                        "Failed to create image acquire semaphore");
                imageAcquireSemaphores[i] = pSemaphore.get(0);
                _CHECK_(vkCreateSemaphore(device, VkSemaphoreCreateInfo(stack), null, pSemaphore),
                        "Failed to create raster semaphore");
                renderCompleteSemaphores[i] = pSemaphore.get(0);
            }
        }
        recreateFences();
    }

    private static void recreateFences() {
        for (int i = 0; i < swapchain.images.length; i++) {
            try (MemoryStack stack = stackPush()) {
                if (renderFences[i] != 0L)
                    vkDestroyFence(device, renderFences[i], null);
                LongBuffer pFence = stack.mallocLong(1);
                _CHECK_(vkCreateFence(device, VkFenceCreateInfo(stack).flags(VK_FENCE_CREATE_SIGNALED_BIT), null,
                        pFence), "Failed to create fence");
                renderFences[i] = pFence.get(0);
            }
        }
    }

    private static void init(MemoryStack stack) throws IOException {
        instance = createInstance(initGlfw());
        windowAndCallbacks = createWindow();
        surface = createSurface();
        debugCallbackHandle = setupDebugging();
        deviceAndQueueFamilies = createPhysicalDevice();
        queueFamily = deviceAndQueueFamilies.queuesFamilies.findSingleSuitableQueue();
        device = createDevice();
        vmaAllocator = createAllocator();
        queue = retrieveQueue();
        swapchain = createSwapChain();
        depthStencilImages = createDepthStencilImages();
        commandPool = createCommandPool(0);
        commandPoolTransient = createCommandPool(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
        sobolBuffer = createSobolBuffer();
        scrambleBuffer = createScrambleBuffer();
        rankingBuffer = createRankingBuffer();
        normalImages = createNormalImages();
        rayTracingImages = createRayTracingImages();
        sampler = createSampler();
        rayTracingProperties = initRayTracing();
        geometry = createGeometry(stack);
        queryPool = createQueryPool();
        renderPass = createRasterRenderPass();
        framebuffers = createFramebuffers();
        blas = compressAccelerationStructure(createBottomLevelAccelerationStructure(geometry));
        tlas = compressAccelerationStructure(createTopLevelAccelerationStructure());
        rayTracingUbos = createUniformBufferObjects(Float.BYTES * 16 * 2);
        rayTracingPipeline = createRayTracingPipeline();
        rasterUbos = createUniformBufferObjects(Float.BYTES * (16 + 4));
        rasterPipeline = createRasterPipeline();
        rayTracingShaderBindingTable = createRayTracingShaderBindingTable();
        rayTracingDescriptorSets = createRayTracingDescriptorSets();
        rasterDescriptorSets = createRasterDescriptorSets();
        rayTracingCommandBuffers = createRayTracingCommandBuffers();
        rasterCommandBuffers = createRasterCommandBuffers();
        presentCommandBuffers = createPresentCommandBuffers();
        createSyncObjects();
    }

    private static void cleanup() {
        _CHECK_(vkDeviceWaitIdle(device), "Failed to wait for device idle");
        for (int i = 0; i < swapchain.images.length; i++) {
            vkDestroySemaphore(device, imageAcquireSemaphores[i], null);
            vkDestroySemaphore(device, renderCompleteSemaphores[i], null);
            vkDestroyFence(device, renderFences[i], null);
            vkDestroyFramebuffer(device, framebuffers[i], null);
            rayTracingUbos[i].free();
            rasterUbos[i].free();
            depthStencilImages[i].free();
            normalImages[i].free();
            rayTracingImages[i].free();
        }
        rankingBuffer.free();
        scrambleBuffer.free();
        sobolBuffer.free();
        freeCommandBuffers();
        rayTracingDescriptorSets.free();
        rayTracingShaderBindingTable.free();
        rayTracingPipeline.free();
        rasterDescriptorSets.free();
        rasterPipeline.free();
        vkDestroyQueryPool(device, queryPool, null);
        tlas.free();
        blas.free(true);
        vkDestroySampler(device, sampler, null);
        swapchain.free();
        vkDestroyRenderPass(device, renderPass, null);
        vkDestroyCommandPool(device, commandPool, null);
        vkDestroyCommandPool(device, commandPoolTransient, null);
        vmaDestroyAllocator(vmaAllocator);
        vkDestroyDevice(device, null);
        if (debugCallbackHandle != null) {
            debugCallbackHandle.free();
        }
        vkDestroySurfaceKHR(instance, surface, null);
        vkDestroyInstance(instance, null);
        windowAndCallbacks.free();
    }

    private static void recreateOnResize() {
        swapchain = createSwapChain();
        depthStencilImages = createDepthStencilImages();
        normalImages = createNormalImages();
        rayTracingImages = createRayTracingImages();
        rayTracingDescriptorSets = createRayTracingDescriptorSets();
        rasterDescriptorSets = createRasterDescriptorSets();
        framebuffers = createFramebuffers();
        freeCommandBuffers();
        rasterCommandBuffers = createRasterCommandBuffers();
        rayTracingCommandBuffers = createRayTracingCommandBuffers();
        presentCommandBuffers = createPresentCommandBuffers();
        recreateFences();
    }

    private static boolean windowSizeChanged() {
        return windowAndCallbacks.width != swapchain.width
            || windowAndCallbacks.height != swapchain.height;
    }

    private static boolean isWindowRenderable() {
        return windowAndCallbacks.width > 0 && windowAndCallbacks.height > 0;
    }

    private static void updateFramebufferSize() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer framebufferWidth = stack.mallocInt(1);
            IntBuffer framebufferHeight = stack.mallocInt(1);
            glfwGetFramebufferSize(windowAndCallbacks.window, framebufferWidth, framebufferHeight);
            windowAndCallbacks.width = framebufferWidth.get(0);
            windowAndCallbacks.height = framebufferHeight.get(0);
        }
    }

    private static void pollEvents() {
        glfwPollEvents();
        updateFramebufferSize();
    }

    private static void submitAndPresent(int imageIndex, int idx) {
        try (MemoryStack stack = stackPush()) {
            _CHECK_(vkQueueSubmit(queue, VkSubmitInfo(stack)
                    .pCommandBuffers(stack.pointers(rasterCommandBuffers[idx])), VK_NULL_HANDLE), "Failed to submit command buffer");
            _CHECK_(vkQueueSubmit(queue, VkSubmitInfo(stack)
                    .pCommandBuffers(stack.pointers(rayTracingCommandBuffers[idx])), VK_NULL_HANDLE), "Failed to submit command buffer");
            _CHECK_(vkQueueSubmit(queue, VkSubmitInfo(stack)
                    .pCommandBuffers(stack.pointers(presentCommandBuffers[idx]))
                    .pWaitSemaphores(stack.longs(imageAcquireSemaphores[idx]))
                    .waitSemaphoreCount(1)
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                    .pSignalSemaphores(stack.longs(renderCompleteSemaphores[idx])), renderFences[idx]), "Failed to submit command buffer");
            _CHECK_(vkQueuePresentKHR(queue, VkPresentInfoKHR(stack)
                    .pWaitSemaphores(stack.longs(renderCompleteSemaphores[idx]))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain.swapchain))
                    .pImageIndices(stack.ints(imageIndex))), "Failed to present image");
        }
    }

    public static void main(String[] args) throws IOException {
        try (MemoryStack stack = stackPush()) {
            init(stack);
            glfwShowWindow(windowAndCallbacks.window);
            IntBuffer pImageIndex = stack.mallocInt(1);
            long lastTime = System.nanoTime();
            int idx = 0;
            while (!glfwWindowShouldClose(windowAndCallbacks.window)) {
                long thisTime = System.nanoTime();
                float dt = (thisTime - lastTime) * 1E-9f;
                lastTime = thisTime;
                pollEvents();
                updateCamera(dt);
                if (!isWindowRenderable()) {
                    continue;
                }
                vkWaitForFences(device, renderFences[idx], true, Long.MAX_VALUE);
                vkResetFences(device, renderFences[idx]);
                if (windowSizeChanged()) {
                    vkQueueWaitIdle(queue);
                    recreateOnResize();
                    idx = 0;
                    continue;
                }
                updateRayTracingUniformBufferObject(idx);
                updateRasterUniformBufferObject(idx);
                _CHECK_(vkAcquireNextImageKHR(device, swapchain.swapchain, -1L, imageAcquireSemaphores[idx], VK_NULL_HANDLE,
                        pImageIndex), "Failed to acquire image");
                submitAndPresent(pImageIndex.get(0), idx);
                processFinishedFences();
                idx = (idx + 1) % swapchain.images.length;
            }
            cleanup();
        }
    }

}
