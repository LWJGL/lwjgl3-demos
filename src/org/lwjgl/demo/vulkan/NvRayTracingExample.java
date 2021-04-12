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
import static org.lwjgl.demo.vulkan.VKFactory.*;
import static org.lwjgl.demo.vulkan.VKUtil.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.EXTDebugReport.*;
import static org.lwjgl.vulkan.KHRDedicatedAllocation.*;
import static org.lwjgl.vulkan.KHRGetMemoryRequirements2.*;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.NVRayTracing.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Use VK_NV_ray_tracing to trace a simple scene.
 *
 * @author Kai Burjack
 */
public class NvRayTracingExample {
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
    private static int CHUNK_WIDTH = 32;
    private static int CHUNK_HEIGHT = 16;
    private static int CHUNK_DEPTH = 32;
    private static int CHUNK_REPEAT_X = 8;
    private static int CHUNK_REPEAT_Z = 8;
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
    private static Long commandPool, commandPoolTransient;
    private static BottomLevelAccelerationStructure blas;
    private static TopLevelAccelerationStructure tlas;
    private static AllocationAndBuffer[] ubos;
    private static RayTracingPipeline pipeline;
    private static AllocationAndBuffer shaderBindingTable;
    private static DescriptorSets descriptorSets;
    private static RayTracingProperties rayTracingProperties;
    private static VkCommandBuffer[] commandBuffers;
    private static Geometry geometry;
    private static long[] imageAcquireSemaphores;
    private static long[] renderCompleteSemaphores;
    private static long[] renderFences;
    private static long queryPool;
    private static Matrix4f projMatrix = new Matrix4f();
    private static Matrix4f viewMatrix = new Matrix4f().translation(
            -CHUNK_WIDTH * CHUNK_REPEAT_X * 0.5f, 
            -CHUNK_HEIGHT * 1.1f, 
            -CHUNK_DEPTH * CHUNK_REPEAT_Z * 1.01f);
    private static Matrix4f invProjMatrix = new Matrix4f();
    private static Matrix4f invViewMatrix = new Matrix4f();
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
            PointerBuffer extensions = stack.mallocPointer(3 + 1);
            extensions.put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                      .put(stack.UTF8(VK_NV_RAY_TRACING_EXTENSION_NAME))
                      .put(stack.UTF8(VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME));
            if (isExtensionEnabled(pProperties, VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME)) {
                extensions.put(stack.UTF8(VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME));
            }
            PointerBuffer ppEnabledLayerNames = null;
            if (debug) {
                ppEnabledLayerNames = stack.pointers(stack.UTF8("VK_LAYER_LUNARG_standard_validation"));
            }
            VkDeviceCreateInfo pCreateInfo = VkDeviceCreateInfo(stack)
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

        ColorFormatAndSpace(int colorFormat, int colorSpace) {
            this.colorFormat = colorFormat;
            this.colorSpace = colorSpace;
        }
    }

    private static class Swapchain {
        long swapchain;
        long[] images;
        long[] imageViews;
        int width, height;

        Swapchain(long swapchain, long[] images, long[] imageViews, int width, int height) {
            this.swapchain = swapchain;
            this.images = images;
            this.imageViews = imageViews;
            this.width = width;
            this.height = height;
        }

        void free() {
            for (long imageView : imageViews) {
                vkDestroyImageView(device, imageView, null);
            }
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
            int imageCount = min(surfCaps.minImageCount() + 1, surfCaps.maxImageCount());
            ColorFormatAndSpace surfaceFormat = determineSurfaceFormat(deviceAndQueueFamilies.physicalDevice, surface);
            Vector2i swapchainExtents = determineSwapchainExtents(surfCaps);
            VkSwapchainCreateInfoKHR pCreateInfo = VkSwapchainCreateInfoKHR(stack)
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageExtent(e -> e.width(swapchainExtents.x).height(swapchainExtents.y))
                    .imageFormat(surfaceFormat.colorFormat)
                    .imageColorSpace(surfaceFormat.colorSpace)
                    .imageUsage(VK_IMAGE_USAGE_STORAGE_BIT)
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
            long[] imageViews = new long[pImageCount.get(0)];
            long[] images = new long[pImageCount.get(0)];
            pSwapchainImages.get(images, 0, images.length);
            LongBuffer pBufferView = stack.mallocLong(1);
            VkImageViewCreateInfo colorAttachmentView = VkImageViewCreateInfo(stack)
                    .format(surfaceFormat.colorFormat)
                    .subresourceRange(r -> r
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .levelCount(1)
                            .layerCount(1))
                    .viewType(VK_IMAGE_VIEW_TYPE_2D);
            for (int i = 0; i < pImageCount.get(0); i++) {
                colorAttachmentView.image(pSwapchainImages.get(i));
                _CHECK_(vkCreateImageView(device, colorAttachmentView, null, pBufferView),
                        "Failed to create image view");
                imageViews[i] = pBufferView.get(0);
            }
            return new Swapchain(pSwapChain.get(0), images, imageViews,
                    swapchainExtents.x, swapchainExtents.y);
        }
    }

    private static Vector2i determineSwapchainExtents(VkSurfaceCapabilitiesKHR surfCaps) {
        VkExtent2D extent = surfCaps.currentExtent();
        Vector2i ret = new Vector2i(extent.width(), extent.height());
        if (extent.width() == -1) {
            ret.set(max(min(1280, surfCaps.maxImageExtent().width()), surfCaps.minImageExtent().width()),
                    max(min(720, surfCaps.maxImageExtent().height()), surfCaps.minImageExtent().height()));
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
            return new ColorFormatAndSpace(colorFormat, colorSpace);
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

    private static void freeRenderCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            vkFreeCommandBuffers(device, commandPool, stack.pointers(commandBuffers));
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
            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            _CHECK_(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo(stack)
                    .commandBufferCount(1)
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY), pCommandBuffer), "Failed to create command buffer");
            VkCommandBuffer cmdBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);
            _CHECK_(vkBeginCommandBuffer(cmdBuffer, VkCommandBufferBeginInfo(stack)), "Failed to begin command buffer");
            return cmdBuffer;
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
    private static AllocationAndBuffer createRayTracingBuffer(ByteBuffer data) {
        return createBuffer(VK_BUFFER_USAGE_RAY_TRACING_BIT_NV, data.remaining(), data);
    }

    private static AllocationAndBuffer createBuffer(int usageFlags, long size, ByteBuffer data) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            _CHECK_(vmaCreateBuffer(vmaAllocator,
                    VkBufferCreateInfo(stack).size(size)
                            .usage(usageFlags | (data != null ? VK_BUFFER_USAGE_TRANSFER_DST_BIT : 0)),
                    VmaAllocationCreateInfo(stack).usage(VMA_MEMORY_USAGE_GPU_ONLY), pBuffer, pAllocation, null),
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

        Geometry(AllocationAndBuffer positions, AllocationAndBuffer normals, AllocationAndBuffer indices,
                VkGeometryNV.Buffer geometry) {
            this.positions = positions;
            this.normals = normals;
            this.indices = indices;
            this.geometry = geometry;
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
        float xzScale = 0.072343f, yScale = 0.13212f;
        for (int z = 0; z < d + 2; z++)
            for (int y = 0; y < h + 2; y++)
                for (int x = 0; x < w + 2; x++) {
                    float v = noise((x - 1) * xzScale, (y - 1) * yScale, (z - 1) * xzScale);
                    if (x > 0 && y > 0 && z > 0 && x < w + 1 && y < h + 1 && z < d + 1 && v > 0.0f) {
                        ds[x + (w + 2) * (y + z * (h + 2))] = 1;
                    }
                }
        List<Face> faces = new ArrayList<>();
        GreedyMeshing gm = new GreedyMeshing(0, 0, 0, h - 1, w, d);
        gm.mesh(ds, faces);
        return faces;
    }

    private static Geometry createGeometry(MemoryStack stack) {
        List<Face> faces = createMesh();
        DynamicByteBuffer positions = new DynamicByteBuffer();
        DynamicByteBuffer normals = new DynamicByteBuffer();
        DynamicByteBuffer indices = new DynamicByteBuffer();
        triangulate_Vsn16_Iu32(BITS_FOR_POSITIONS, faces, positions, normals, indices);
        AllocationAndBuffer positionsBuffer = createRayTracingBuffer(memByteBuffer(positions.addr, positions.pos));
        positions.free();
        AllocationAndBuffer normalsBuffer = createBuffer(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                memByteBuffer(normals.addr, normals.pos));
        normals.free();
        AllocationAndBuffer indicesBuffer = createBuffer(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
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
        return new Geometry(positionsBuffer, normalsBuffer, indicesBuffer, geometry);
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
                    VkMemoryBarrier(stack).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_NV
                                    | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV),
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
                            .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_NV
                                    | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV)
                            .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV),
                    null, null);
            long fence = submitCommandBuffer(cmdBuffer, true, null);
            waitForFenceAndDestroy(fence);
            vkFreeCommandBuffers(device, commandPoolTransient, cmdBuffer);
            scratchBuffer.free();
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
            LongBuffer accelerationStructureCompactedHandle = stack.mallocLong(1);
            _CHECK_(vkGetAccelerationStructureHandleNV(device, accelerationStructureCompacted.get(0),
                    accelerationStructureCompactedHandle), "Failed to get acceleration structure handle");
            VkCommandBuffer cmdBuffer2 = createCommandBuffer(commandPoolTransient);
            vkCmdCopyAccelerationStructureNV(cmdBuffer2, accelerationStructureCompacted.get(0),
                    src.accelerationStructure, VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_NV);
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
            int instanceCount = CHUNK_REPEAT_X * CHUNK_REPEAT_Z;
            VkAccelerationStructureInfoNV accelerationStructureInfo = VkAccelerationStructureInfoNV(stack)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_NV)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_NV |
                           VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_NV).instanceCount(instanceCount);
            LongBuffer accelerationStructure = stack.mallocLong(1);
            _CHECK_(vkCreateAccelerationStructureNV(device,
                    VkAccelerationStructureCreateInfoNV(stack).info(accelerationStructureInfo), null,
                    accelerationStructure), "Failed to create acceleration structure");
            AllocationAndMemory allocation = allocateDeviceMemory(memoryRequirements(stack,
                    accelerationStructure.get(0), VK_ACCELERATION_STRUCTURE_MEMORY_REQUIREMENTS_TYPE_OBJECT_NV));
            _CHECK_(vkBindAccelerationStructureMemoryNV(device,
                    VkBindAccelerationStructureMemoryInfoNV(stack).accelerationStructure(accelerationStructure.get(0))
                            .memory(allocation.memory).memoryOffset(allocation.offset)),
                    "Failed to bind acceleration structure memory");
            ByteBuffer instanceData = stack.malloc(GeometryInstance.SIZEOF * instanceCount);
            for (int z = 0; z < CHUNK_REPEAT_Z; z++) {
                for (int x = 0; x < CHUNK_REPEAT_X; x++) {
                    GeometryInstance inst = new GeometryInstance();
                    inst.accelerationStructureHandle = blas.handle;
                    inst.mask = (byte) 0x1;
                    inst.transform.translate(x * CHUNK_WIDTH, 0, z * CHUNK_DEPTH).scale(POSITION_SCALE);
                    inst.write(instanceData);
                }
            }
            instanceData.flip();
            AllocationAndBuffer instanceMemory = createBuffer(VK_BUFFER_USAGE_RAY_TRACING_BIT_NV, instanceData);
            VkCommandBuffer cmdBuffer = createCommandBuffer(commandPoolTransient);
            vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV, 0,
                    VkMemoryBarrier(stack).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_NV
                                    | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV),
                    null, null);
            AllocationAndBuffer scratchBuffer = createRayTracingBuffer(
                    memoryRequirements(stack, accelerationStructure.get(0),
                            VK_ACCELERATION_STRUCTURE_MEMORY_REQUIREMENTS_TYPE_BUILD_SCRATCH_NV).size());
            vkCmdBuildAccelerationStructureNV(cmdBuffer, accelerationStructureInfo, instanceMemory.buffer, 0, false,
                    accelerationStructure.get(0), VK_NULL_HANDLE, scratchBuffer.buffer, 0);
            vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV, 0,
                    VkMemoryBarrier(stack)
                            .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_NV
                                    | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV)
                            .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV),
                    null, null);
            long fence = submitCommandBuffer(cmdBuffer, true, null);
            waitForFenceAndDestroy(fence);
            vkFreeCommandBuffers(device, commandPoolTransient, cmdBuffer);
            scratchBuffer.free();
            instanceMemory.free();
            return new TopLevelAccelerationStructure(accelerationStructure.get(0), allocation, instanceCount);
        }
    }

    private static class AllocationAndMemory {
        long allocation;
        long memory;
        long offset;
        long alignment;
        int memoryTypeBits;

        AllocationAndMemory(long allocation, long memory, long offset, long alignment, int memoryTypeBits) {
            this.allocation = allocation;
            this.memory = memory;
            this.offset = offset;
            this.alignment = alignment;
            this.memoryTypeBits = memoryTypeBits;
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
                    memoryRequirements.alignment(), memoryRequirements.memoryTypeBits());
        }
    }

    private static class RayTracingPipeline {
        long pipelineLayout;
        long descriptorSetLayout;
        long pipeline;

        RayTracingPipeline(long pipelineLayout, long descriptorSetLayout, long pipeline) {
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

    private static RayTracingPipeline createRayTracingPipeline() throws IOException {
        int numDescriptors = 5;
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
                                .flip()),
                    null, pSetLayout),
                    "Failed to create descriptor set layout");
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            _CHECK_(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo(stack)
                            .pSetLayouts(pSetLayout), null, pPipelineLayout),
                    "Failed to create pipeline layout");
            VkPipelineShaderStageCreateInfo.Buffer pStages = VkPipelineShaderStageCreateInfo(stack, 3);
            loadModule(stack, pStages.get(0), "raygen.glsl", VK_SHADER_STAGE_RAYGEN_BIT_NV);
            loadModule(stack, pStages.get(1), "raymiss.glsl", VK_SHADER_STAGE_MISS_BIT_NV);
            loadModule(stack, pStages.get(2), "closesthit.glsl", VK_SHADER_STAGE_CLOSEST_HIT_BIT_NV);
            VkRayTracingShaderGroupCreateInfoNV.Buffer groups = VkRayTracingShaderGroupCreateInfoNV(3, stack);
            groups.forEach(g -> g
                    .generalShader(VK_SHADER_UNUSED_NV)
                    .closestHitShader(VK_SHADER_UNUSED_NV)
                    .anyHitShader(VK_SHADER_UNUSED_NV)
                    .intersectionShader(VK_SHADER_UNUSED_NV));
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
            if (pipeline != null)
                pipeline.free();
            return new RayTracingPipeline(pPipelineLayout.get(0), pSetLayout.get(0), pPipelines.get(0));
        }
    }

    private static void loadModule(MemoryStack stack, VkPipelineShaderStageCreateInfo pStage,
                                   String classpathResource, int vkShaderStageRaygenBitNv) throws IOException {
        String pack = NvRayTracingExample.class.getPackage().getName().replace('.', '/');
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

    private static DescriptorSets createDescriptorSets() {
        if (descriptorSets != null)
            descriptorSets.free();
        int numSets = swapchain.imageViews.length;
        int numDescriptors = 5;
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
                                            .descriptorCount(numSets)))
                            .maxSets(numSets), null, pDescriptorPool),
                    "Failed to create descriptor pool");
            VkDescriptorSetAllocateInfo descriptorSetAllocateInfo = VkDescriptorSetAllocateInfo(stack)
                    .descriptorPool(pDescriptorPool.get(0))
                    .pSetLayouts(repeat(stack, pipeline.descriptorSetLayout, numSets));
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
                                        .imageView(swapchain.imageViews[idx])
                                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL)))
                        .apply(wds -> wds
                                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                .dstBinding(2)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo(stack, 1)
                                        .buffer(ubos[idx].buffer)
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
                                        .range(VK_WHOLE_SIZE)));
            }
            vkUpdateDescriptorSets(device, writeDescriptorSet.flip(), null);
            return new DescriptorSets(pDescriptorPool.get(0), sets);
        }
    }

    private static AllocationAndBuffer createShaderBindingTable() {
        if (shaderBindingTable != null) {
            shaderBindingTable.free();
        }
        try (MemoryStack stack = stackPush()) {
            int sbtSize = rayTracingProperties.shaderGroupHandleSize * 3;
            ByteBuffer handles = stack.malloc(sbtSize);
            _CHECK_(vkGetRayTracingShaderGroupHandlesNV(device, pipeline.pipeline, 0, 3, handles),
                    "Failed to obtain ray tracing group handles");
            return createBuffer(VK_BUFFER_USAGE_RAY_TRACING_BIT_NV, handles);
        }
    }

    private static VkCommandBuffer[] createRayTracingCommandBuffers() {
        if (commandBuffers != null) {
            freeRenderCommandBuffers();
        }
        int count = swapchain.imageViews.length;
        VkCommandBuffer[] buffers = new VkCommandBuffer[count];
        for (int i = 0; i < count; i++) {
            VkCommandBuffer cmdBuf = createCommandBuffer(commandPool);
            try (MemoryStack stack = stackPush()) {
                vkCmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV,0,
                        null,null, VkImageMemoryBarrier(stack)
                                .srcAccessMask(0)
                                .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                                .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                                .image(swapchain.images[i])
                                .subresourceRange(r -> {
                                    r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                            .layerCount(1)
                                            .levelCount(1);
                                }));
            }
            vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_RAY_TRACING_NV, pipeline.pipeline);
            try (MemoryStack stack = stackPush()) {
                vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_RAY_TRACING_NV,
                        pipeline.pipelineLayout, 0, stack.longs(descriptorSets.sets[i]), null);
            }
            int stride = rayTracingProperties.shaderGroupHandleSize;
            vkCmdTraceRaysNV(cmdBuf,
                    shaderBindingTable.buffer, 0,
                    shaderBindingTable.buffer, stride, stride,
                    shaderBindingTable.buffer, stride * 2, stride,
                    VK_NULL_HANDLE, 0, 0,
                    swapchain.width, swapchain.height, 1);
            try (MemoryStack stack = stackPush()) {
                vkCmdPipelineBarrier(cmdBuf, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,0,
                        null,null, VkImageMemoryBarrier(stack)
                                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                                .dstAccessMask(0)
                                .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                                .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                                .image(swapchain.images[i])
                                .subresourceRange(r -> {
                                    r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                            .layerCount(1)
                                            .levelCount(1);
                                }));
            }
            _CHECK_(vkEndCommandBuffer(cmdBuf), "Failed to end command buffer");
            buffers[i] = cmdBuf;
        }
        return buffers;
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
        long window = glfwCreateWindow(800, 600, "Vulkan Raytracing Demo", NULL, NULL);
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

    private static AllocationAndBuffer[] createMappedUniformBufferObject() {
        AllocationAndBuffer[] ret = new AllocationAndBuffer[swapchain.images.length];
        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < ret.length; i++) {
                LongBuffer pBuffer = stack.mallocLong(1);
                PointerBuffer pAllocation = stack.mallocPointer(1);
                _CHECK_(vmaCreateBuffer(vmaAllocator,
                        VkBufferCreateInfo(stack).size(Float.BYTES * 16 * 2).usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT),
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
                        (float) windowAndCallbacks.width / windowAndCallbacks.height, 0.1f, 100.0f);
    }

    private static void updateUniformBufferObject(int idx) {
        projMatrix.invert(invProjMatrix);
        viewMatrix.invert(invViewMatrix);
        ByteBuffer bb = memByteBuffer(ubos[idx].mapped, Float.BYTES * 16 * 2);
        invProjMatrix.get(0, bb);
        invViewMatrix.get(Float.BYTES * 16, bb);
        ubos[idx].flushMapped(0, Float.BYTES * 16 * 2);
    }

    private static void createSyncObjects() {
        imageAcquireSemaphores = new long[swapchain.images.length];
        renderCompleteSemaphores = new long[swapchain.images.length];
        renderFences = new long[swapchain.images.length];
        for (int i = 0; i < swapchain.images.length; i++) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer pSemaphore = stack.mallocLong(1);
                _CHECK_(vkCreateSemaphore(device, VkSemaphoreCreateInfo(stack), null, pSemaphore),
                        "Failed to create semaphore");
                imageAcquireSemaphores[i] = pSemaphore.get(0);
                _CHECK_(vkCreateSemaphore(device, VkSemaphoreCreateInfo(stack), null, pSemaphore),
                        "Failed to create semaphore");
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
        commandPool = createCommandPool(0);
        commandPoolTransient = createCommandPool(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
        rayTracingProperties = initRayTracing();
        geometry = createGeometry(stack);
        queryPool = createQueryPool();
        blas = compressAccelerationStructure(createBottomLevelAccelerationStructure(geometry));
        tlas = compressAccelerationStructure(createTopLevelAccelerationStructure());
        ubos = createMappedUniformBufferObject();
        pipeline = createRayTracingPipeline();
        shaderBindingTable = createShaderBindingTable();
        descriptorSets = createDescriptorSets();
        commandBuffers = createRayTracingCommandBuffers();
        createSyncObjects();
    }

    private static void cleanup() {
        _CHECK_(vkDeviceWaitIdle(device), "Failed to wait for device idle");
        for (int i = 0; i < swapchain.images.length; i++) {
            vkDestroySemaphore(device, imageAcquireSemaphores[i], null);
            vkDestroySemaphore(device, renderCompleteSemaphores[i], null);
            vkDestroyFence(device, renderFences[i], null);
            ubos[i].free();
        }
        freeRenderCommandBuffers();
        descriptorSets.free();
        shaderBindingTable.free();
        pipeline.free();
        vkDestroyQueryPool(device, queryPool, null);
        tlas.free();
        blas.free(true);
        swapchain.free();
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
        descriptorSets = createDescriptorSets();
        commandBuffers = createRayTracingCommandBuffers();
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
                    .pCommandBuffers(stack.pointers(commandBuffers[imageIndex]))
                    .pSignalSemaphores(stack.longs(renderCompleteSemaphores[idx]))
                    .pWaitSemaphores(stack.longs(imageAcquireSemaphores[idx]))
                    .waitSemaphoreCount(1)
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV)), renderFences[idx]), "Failed to submit queue");
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
                updateUniformBufferObject(idx);
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
