/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.vulkan;

import org.joml.Matrix4f;
import org.joml.Matrix4x3f;
import org.joml.Vector2i;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import org.lwjgl.glfw.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.*;
import static java.lang.Thread.*;
import static java.util.concurrent.Executors.*;
import static org.lwjgl.demo.opengl.util.DemoUtils.*;
import static org.lwjgl.demo.vulkan.VKFactory.*;
import static org.lwjgl.demo.vulkan.VKUtil.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.stb.STBImage.*;
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
    private static boolean debug = true;

    static {
        if (debug) {
            System.setProperty("org.lwjgl.util.Debug", "true");
            System.setProperty("org.lwjgl.util.NoChecks", "false");
            System.setProperty("org.lwjgl.util.DebugLoader", "true");
            System.setProperty("org.lwjgl.util.DebugAllocator", "true");
            System.setProperty("org.lwjgl.util.DebugStack", "true");
        }
    }

    private static ExecutorService fenceWaiter = newCachedThreadPool((r) -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setPriority(MIN_PRIORITY);
        return t;
    });

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
    private static final Object commandPoolLock = new Object();
    private static Long commandPool;
    private static BottomLevelAccelerationStructure blas;
    private static TopLevelAccelerationStructure tlas;
    private static AllocationAndBuffer ubo;
    private static EnvironmentTexture environmentTexture;
    private static RayTracingPipeline pipeline;
    private static AllocationAndBuffer shaderBindingTable;
    private static DescriptorSets descriptorSets;
    private static RayTracingProperties rayTracingProperties;
    private static VkCommandBuffer[] commandBuffers;
    private static Geometry geometry;
    private static long imageAcquireSemaphore;
    private static long renderCompleteSemaphore;
    private static long sampler;
    private static Matrix4f projMatrix = new Matrix4f();
    private static Matrix4f viewMatrix = new Matrix4f().translation(0, 0, -5).rotateX(0.4f).rotateY(0.1f);
    private static Matrix4f invProjMatrix = new Matrix4f();
    private static Matrix4f invViewMatrix = new Matrix4f();
    private static boolean[] keydown = new boolean[GLFW_KEY_LAST + 1];
    private static float mouseX, mouseY;
    private static boolean mouseDown;

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
                            .pApplicationName(stack.UTF8("Vulkan Nv Raytracing Demo"))
                            .pEngineName(stack.UTF8("LWJGL 3 Demos"))
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
            assertAvailable(pProperties, VK_KHR_SWAPCHAIN_EXTENSION_NAME);
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
            boolean isDiscrete = deviceProperties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
            return isDiscrete && !queuesFamilies.computeFamilies.isEmpty() && !queuesFamilies.graphicsFamilies.isEmpty()
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

    private static long createCommandPool() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pCmdPool = stack.mallocLong(1);
            _CHECK_(vkCreateCommandPool(device, VkCommandPoolCreateInfo(stack)
                    .queueFamilyIndex(queueFamily)
                    .flags(0), null, pCmdPool), "Failed to create command pool");
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

    private static void submitCommandBuffer(VkCommandBuffer commandBuffer, boolean endCommandBuffer, Runnable afterComplete) {
        if (commandBuffer == null || commandBuffer.address() == NULL)
            return;
        if (endCommandBuffer)
            _CHECK_(vkEndCommandBuffer(commandBuffer), "Failed to end command buffer");
        try (MemoryStack stack = stackPush()) {
            if (afterComplete != null) {
                LongBuffer pFence = stack.mallocLong(1);
                _CHECK_(vkCreateFence(device, VkFenceCreateInfo(stack), null, pFence), "Failed to create fence");
                synchronized (commandPoolLock) {
                    _CHECK_(vkQueueSubmit(queue, VkSubmitInfo(stack)
                            .pCommandBuffers(stack.pointers(commandBuffer)), pFence.get(0)),
                            "Failed to submit command buffer");
                }
                long fence = pFence.get(0);
                fenceWaiter.execute(() ->
                    freeCommandBufferAfterFence(commandBuffer, afterComplete, fence)
                );
            } else {
                synchronized (commandPoolLock) {
                    _CHECK_(vkQueueSubmit(queue, VkSubmitInfo(stack)
                            .pCommandBuffers(stack.pointers(commandBuffer)), VK_NULL_HANDLE),
                            "Failed to submit command buffer");
                }
            }
        }
    }

    private static void freeCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            synchronized (commandPoolLock) {
                vkFreeCommandBuffers(device, commandPool, stack.pointers(commandBuffers));
            }
        }
    }

    private static void freeCommandBufferAfterFence(VkCommandBuffer commandBuffer, Runnable afterComplete, long fence) {
        try (MemoryStack stack = stackPush()) {
            _CHECK_(vkWaitForFences(device, stack.longs(fence), true, -1L), "Failed to wait for fence");
            vkDestroyFence(device, fence, null);
            synchronized (commandPoolLock) {
                vkFreeCommandBuffers(device, commandPool, commandBuffer);
            }
            afterComplete.run();
        }
    }

    private static VkCommandBuffer createCommandBuffer() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
                _CHECK_(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo(stack)
                        .commandBufferCount(1)
                        .commandPool(commandPool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY), pCommandBuffer), "Failed to create command buffer");
                VkCommandBuffer cmdBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);
            synchronized (commandPoolLock) {
                _CHECK_(vkBeginCommandBuffer(cmdBuffer, VkCommandBufferBeginInfo(stack)), "Failed to begin command buffer");
            }
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
    private static AllocationAndBuffer createBuffer(int usageFlags, long size, ByteBuffer data) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            _CHECK_(vmaCreateBuffer(vmaAllocator, VkBufferCreateInfo(stack)
                            .size(size)
                            .usage(usageFlags | (data != null ? VK_BUFFER_USAGE_TRANSFER_DST_BIT : 0)), VmaAllocationCreateInfo(stack)
                    .usage(VMA_MEMORY_USAGE_GPU_ONLY), pBuffer, pAllocation, null),
                    "Failed to allocate buffer");
            if (data != null)
                copyWithStageBuffer(data, pBuffer.get(0));
            return new AllocationAndBuffer(pAllocation.get(0), pBuffer.get(0));
        }
    }

    private static void copyWithStageBuffer(ByteBuffer data, long dstBuffer) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pBufferStage = stack.mallocLong(1);
            PointerBuffer pAllocationStage = stack.mallocPointer(1);
            _CHECK_(vmaCreateBuffer(vmaAllocator, VkBufferCreateInfo(stack)
                            .size(data.remaining())
                            .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT), VmaAllocationCreateInfo(stack)
                            .usage(VMA_MEMORY_USAGE_CPU_ONLY), pBufferStage, pAllocationStage, null),
                    "Failed to allocate stage buffer");
            copyToMapped(data, pAllocationStage.get(0));
            VkCommandBuffer cmdBuffer = createCommandBuffer();
            vkCmdCopyBuffer(cmdBuffer, pBufferStage.get(0), dstBuffer,
                    VkBufferCopy(stack, 1).size(data.remaining()));
            long bufferStage = pBufferStage.get(0);
            long allocationStage = pAllocationStage.get(0);
            submitCommandBuffer(cmdBuffer, true, () -> vmaDestroyBuffer(vmaAllocator, bufferStage, allocationStage));
        }
    }

    private static void copyToMapped(ByteBuffer data, long allocation) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            _CHECK_(vmaMapMemory(vmaAllocator, allocation, pData), "Failed to map memory");
            memCopy(memAddress(data), pData.get(0), data.remaining());
            vmaUnmapMemory(vmaAllocator, allocation);
        }
    }

    private static class BottomLevelAccelerationStructure {
        Geometry geometry;
        long accelerationStructure;
        AllocationAndMemory memory;
        long handle;

        void free() {
            geometry.free();
            vmaFreeMemory(vmaAllocator, memory.allocation);
            vkDestroyAccelerationStructureNV(device, accelerationStructure, null);
        }
    }

    private static class Geometry {
        AllocationAndBuffer vertexData;
        AllocationAndBuffer indexData;
        AllocationAndBuffer normalData;
        @StackAllocated VkGeometryNV geometry;

        void free() {
            vertexData.free();
            indexData.free();
            normalData.free();
        }
    }

    private static Geometry createGeometry(MemoryStack stack, String classpathResource) {
        AIScene scene = loadModel(classpathResource);
        AIMesh m = AIMesh.create(Objects.requireNonNull(scene.mMeshes()).get(0));
        int numVertices = m.mNumVertices();
        AIVector3D.Buffer vertices = m.mVertices();
        AIVector3D.Buffer normals = m.mNormals();
        AllocationAndBuffer vertexBuffer = createBuffer(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                memByteBuffer(vertices.address(), numVertices * Float.BYTES * 3));
        ByteBuffer normals4 = memAlloc(numVertices * Float.BYTES * 4);
        FloatBuffer nf = normals4.asFloatBuffer();
        for (int i = 0; i < numVertices; i++) {
            AIVector3D n = Objects.requireNonNull(normals).get(i);
            nf.put(n.x()).put(n.y()).put(n.z()).put(0.0f);
        }
        AllocationAndBuffer normalBuffer = createBuffer(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, normals4);
        memFree(normals4);
        int faceCount = m.mNumFaces();
        ByteBuffer bb = memAlloc(faceCount * 3 * Integer.BYTES);
        IntBuffer elementArrayBufferData = bb.asIntBuffer();
        AIFace.Buffer facesBuffer = m.mFaces();
        for (int i = 0; i < faceCount; ++i) {
            AIFace face = facesBuffer.get(i);
            elementArrayBufferData.put(face.mIndices());
        }
        AllocationAndBuffer indexBuffer = createBuffer(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, bb);
        memFree(bb);
        VkGeometryNV geometry = VkGeometryNV(stack)
                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_NV)
                .geometry(g -> g.triangles(t -> VkGeometryTrianglesNV(t)
                    .vertexData(vertexBuffer.buffer)
                    .vertexCount(numVertices)
                    .vertexStride(Float.BYTES * 3)
                    .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                    .indexData(indexBuffer.buffer)
                    .indexCount(faceCount * 3)
                    .indexType(VK_INDEX_TYPE_UINT32)).aabbs(VKFactory::VkGeometryAABBNV))
                .flags(VK_GEOMETRY_OPAQUE_BIT_NV);
        Geometry ret = new Geometry();
        ret.geometry = geometry;
        ret.vertexData = vertexBuffer;
        ret.normalData = normalBuffer;
        ret.indexData = indexBuffer;
        return ret;
    }

    private static class GeometryInstance {
        Matrix4x3f transform = new Matrix4x3f();
        int instanceId;
        byte mask;
        int instanceOffset;
        byte flags;
        long accelerationStructureHandle;

        ByteBuffer write(ByteBuffer bb) {
            transform.getTransposed(bb);
            bb.putInt(Float.BYTES * 12, ((int) mask << 24) | instanceId);
            bb.putInt(Float.BYTES * 12 + 4, ((int) flags << 24) | instanceOffset);
            bb.putLong(Float.BYTES * 12 + 4 + 4, accelerationStructureHandle);
            return bb;
        }
    }

    private static BottomLevelAccelerationStructure createBottomLevelAccelerationStructure(
            Geometry geometry) {
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureInfoNV pInfo = VkAccelerationStructureInfoNV(stack)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_NV)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_NV)
                    .pGeometries(VkGeometryNV.create(geometry.geometry.address(), 1));
            VkCommandBuffer cmdBuffer = createCommandBuffer();
            transferBarrierForTlasBuild(stack, cmdBuffer);
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
            buildBarrierForTlasBuild(stack, cmdBuffer);
            submitCommandBuffer(cmdBuffer, true, scratchBuffer::free);
            BottomLevelAccelerationStructure ret = new BottomLevelAccelerationStructure();
            ret.geometry = geometry;
            ret.accelerationStructure = accelerationStructure.get(0);
            ret.memory = allocation;
            ret.handle = accelerationStructureHandle.getLong(0);
            return ret;
        }
    }

    private static void buildBarrierForTlasBuild(MemoryStack stack, VkCommandBuffer cmdBuffer) {
        vkCmdPipelineBarrier(cmdBuffer,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV,
                0, accelerationStructureBuildBarrier(stack),
                null, null);
    }

    private static VkMemoryBarrier.Buffer accelerationStructureBuildBarrier(MemoryStack stack) {
        return VkMemoryBarrier(stack)
                .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_NV | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV)
                .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV);
    }

    private static VkMemoryBarrier.Buffer accelerationStructureMemoryUploadBarrier(MemoryStack stack) {
        return VkMemoryBarrier(stack)
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_NV | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_NV);
    }

    private static void buildBarrierForShaderRead(MemoryStack stack, VkCommandBuffer cmdBuffer) {
        vkCmdPipelineBarrier(cmdBuffer,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV,
                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV,
                0, accelerationStructureBuildBarrier(stack),
                null, null);
    }

    private static void transferBarrierForTlasBuild(MemoryStack stack, VkCommandBuffer cmdBuffer) {
        vkCmdPipelineBarrier(cmdBuffer,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_NV,
                0, accelerationStructureMemoryUploadBarrier(stack),
                null, null);
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

    private static class TopLevelAccelerationStructure {
        AllocationAndMemory memory;
        long accelerationStructure;

        TopLevelAccelerationStructure(AllocationAndMemory memory, long accelerationStructure) {
            this.memory = memory;
            this.accelerationStructure = accelerationStructure;
        }

        void free() {
            vmaFreeMemory(vmaAllocator, memory.allocation);
            vkDestroyAccelerationStructureNV(device, accelerationStructure, null);
        }
    }

    private static TopLevelAccelerationStructure createTopLevelAccelerationStructure() {
        try (MemoryStack stack = stackPush()) {
            VkAccelerationStructureInfoNV accelerationStructureInfo = VkAccelerationStructureInfoNV(stack)
                    .instanceCount(1);
            LongBuffer accelerationStructure = stack.mallocLong(1);
            _CHECK_(vkCreateAccelerationStructureNV(device, VkAccelerationStructureCreateInfoNV(stack)
                            .info(accelerationStructureInfo), null, accelerationStructure),
                    "Failed to create acceleration structure");
            AllocationAndMemory allocation = allocateDeviceMemory(memoryRequirements(stack, accelerationStructure.get(0),
                    VK_ACCELERATION_STRUCTURE_MEMORY_REQUIREMENTS_TYPE_OBJECT_NV));
            _CHECK_(vkBindAccelerationStructureMemoryNV(device, VkBindAccelerationStructureMemoryInfoNV(stack)
                            .accelerationStructure(accelerationStructure.get(0))
                            .memory(allocation.memory)
                            .memoryOffset(allocation.offset)),
                    "Failed to bind acceleration structure memory");
            GeometryInstance instance = new GeometryInstance();
            instance.accelerationStructureHandle = blas.handle;
            instance.mask = (byte) 0xFF;
            AllocationAndBuffer instanceMemory = createBuffer(VK_BUFFER_USAGE_RAY_TRACING_BIT_NV,
                            instance.write(stack.malloc(64)));
            VkCommandBuffer cmdBuffer = createCommandBuffer();
            transferBarrierForTlasBuild(stack, cmdBuffer);
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
            buildBarrierForShaderRead(stack, cmdBuffer);
            submitCommandBuffer(cmdBuffer, true, () -> {
                instanceMemory.free();
                scratchBuffer.free();
            });
            return new TopLevelAccelerationStructure(allocation, accelerationStructure.get(0));
        }
    }

    private static class AllocationAndMemory {
        long allocation;
        long memory;
        long offset;

        AllocationAndMemory(long allocation, long memory, long offset) {
            this.allocation = allocation;
            this.memory = memory;
            this.offset = offset;
        }
    }

    private static AllocationAndMemory allocateDeviceMemory(VkMemoryRequirements memoryRequirements) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo pAllocationInfo = VmaAllocationInfo(stack);
            _CHECK_(vmaAllocateMemory(vmaAllocator, memoryRequirements, VmaAllocationCreateInfo(stack).usage(VMA_MEMORY_USAGE_GPU_ONLY),
                    pAllocation, pAllocationInfo), "Failed to allocate memory");
            return new AllocationAndMemory(pAllocation.get(0), pAllocationInfo.deviceMemory(), pAllocationInfo.offset());
        }
    }

    private static class RayTracingPipeline {
        long pipelineLayout;
        long descriptorSetLayout;
        long pipeline;

        void free() {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            vkDestroyPipeline(device, pipeline, null);
        }
    }

    private static RayTracingPipeline createRayTracingPipeline() throws IOException {
        int numDescriptors = 6;
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
                                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_NV))
                                .apply(dslb -> dslb
                                        .binding(4)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_NV))
                                .apply(dslb -> dslb
                                        .binding(5)
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
                            .maxRecursionDepth(1)
                            .pGroups(groups)
                            .layout(pPipelineLayout.get(0)), null, pPipelines),
                    "Failed to create ray tracing pipeline");
            pStages.forEach(stage -> vkDestroyShaderModule(device, stage.module(), null));
            if (pipeline != null)
                pipeline.free();
            RayTracingPipeline ret = new RayTracingPipeline();
            ret.descriptorSetLayout = pSetLayout.get(0);
            ret.pipelineLayout = pPipelineLayout.get(0);
            ret.pipeline = pPipelines.get(0);
            return ret;
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

    private static class EnvironmentTexture {
        long image;
        long allocation;
        long imageView;

        void free() {
            vmaDestroyImage(vmaAllocator, image, allocation);
            vkDestroyImageView(device, imageView, null);
        }
    }

    private static DescriptorSets createDescriptorSets() {
        if (descriptorSets != null) {
            descriptorSets.free();
        }
        int numSets = swapchain.imageViews.length;
        int numDescriptors = 6;
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
                                    .apply(3, dps -> dps.type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                            .descriptorCount(numSets))
                                    .apply(4, dps -> dps.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                            .descriptorCount(numSets))
                                    .apply(5, dps -> dps.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
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
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_NV)
                                .dstBinding(0)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pNext(VkWriteDescriptorSetAccelerationStructureNV(stack)
                                        .pAccelerationStructures(stack.longs(tlas.accelerationStructure))
                                        .address()))
                        .apply(wds -> wds
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .dstBinding(1)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pImageInfo(VkDescriptorImageInfo(stack, 1)
                                        .imageView(swapchain.imageViews[idx])
                                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL)))
                        .apply(wds -> wds
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                .dstBinding(2)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo(stack, 1)
                                        .buffer(ubo.buffer)
                                        .range(VK_WHOLE_SIZE)))
                        .apply(wds -> wds
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                .dstBinding(3)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pImageInfo(VkDescriptorImageInfo(stack, 1)
                                        .sampler(sampler)
                                        .imageView(environmentTexture.imageView)
                                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)))
                        .apply(wds -> wds
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .dstBinding(4)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo(stack, 1)
                                        .buffer(geometry.normalData.buffer)
                                        .range(VK_WHOLE_SIZE)))
                        .apply(wds -> wds
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .dstBinding(5)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo(stack, 1)
                                        .buffer(geometry.indexData.buffer)
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
            freeCommandBuffers();
        }
        int count = swapchain.imageViews.length;
        VkCommandBuffer[] buffers = new VkCommandBuffer[count];
        for (int i = 0; i < count; i++) {
            VkCommandBuffer cmdBuf = createCommandBuffer();
            transitionImageLayout(cmdBuf, swapchain.images[i], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV);
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
            transitionImageLayout(cmdBuf, swapchain.images[i], VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            vkEndCommandBuffer(cmdBuf);
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

    private static AllocationAndBuffer createMappedUniformBufferObject() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            _CHECK_(vmaCreateBuffer(vmaAllocator, VkBufferCreateInfo(stack)
                            .size(Float.BYTES * 16 * 2)
                            .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT), VmaAllocationCreateInfo(stack)
                            .usage(VMA_MEMORY_USAGE_CPU_TO_GPU), pBuffer, pAllocation, null),
                    "Failed to allocate buffer");
            AllocationAndBuffer ret = new AllocationAndBuffer(pAllocation.get(0), pBuffer.get(0));
            ret.map();
            return ret;
        }
    }

    private static void updateCamera(float dt) {
        float factor = 1.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 3.0f;
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
        if (keydown[GLFW_KEY_Q]) {
            viewMatrix.rotateLocalZ(-factor * dt);
        }
        if (keydown[GLFW_KEY_E]) {
            viewMatrix.rotateLocalZ(factor * dt);
        }
        if (keydown[GLFW_KEY_LEFT_CONTROL]) {
            viewMatrix.translateLocal(0, factor * dt, 0);
        }
        if (keydown[GLFW_KEY_SPACE]) {
            viewMatrix.translateLocal(0, -factor * dt, 0);
        }
        projMatrix
                .scaling(1, -1, 1) // <- Y up
                .perspective((float) Math.toRadians(60),
                        (float) windowAndCallbacks.width / windowAndCallbacks.height, 0.1f, 100.0f);
    }

    private static void updateUniformBufferObject() {
        projMatrix.invert(invProjMatrix);
        viewMatrix.invert(invViewMatrix);
        ByteBuffer bb = memByteBuffer(ubo.mapped, Float.BYTES * 16 * 2);
        invProjMatrix.get(0, bb);
        invViewMatrix.get(Float.BYTES * 16, bb);
        ubo.flushMapped(0, Float.BYTES * 16 * 2);
    }

    private static long createSemaphore() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSemaphore = stack.mallocLong(1);
            _CHECK_(vkCreateSemaphore(device, VkSemaphoreCreateInfo(stack), null, pSemaphore),
                    "Failed to create semaphore");
            return pSemaphore.get(0);
        }
    }

    private static long createSampler() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSampler = stack.mallocLong(1);
            _CHECK_(vkCreateSampler(device, VkSamplerCreateInfo(stack)
                            .magFilter(VK_FILTER_LINEAR)
                            .minFilter(VK_FILTER_LINEAR)
                            .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                            .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                            .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                            .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                            .compareOp(VK_COMPARE_OP_NEVER)
                            .maxLod(1)
                            .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
                            .maxAnisotropy(1.0f), null, pSampler),
                    "Failed to create sampler");
            return pSampler.get(0);
        }
    }

    private static EnvironmentTexture createEnvironmentMap() throws IOException {
        try (MemoryStack stack = stackPush()) {
            ByteBuffer imageBuffer;
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);
            String[] names = { "right", "left", "top", "bottom", "front", "back" };
            ByteBuffer[] images = new ByteBuffer[6];
            for (int i = 0; i < 6; i++) {
                imageBuffer = ioResourceToByteBuffer("org/lwjgl/demo/space_" + names[i] + (i+1) + ".jpg", 8 * 1024);
                images[i] = stbi_load_from_memory(imageBuffer, w, h, comp, 4);
                if (images[i] == null) {
                    throw new IOException("Failed to load image: " + stbi_failure_reason());
                }
            }
            int imageSize = w.get(0) * h.get(0) * 4 * 6;
            int layerSize = imageSize / 6;
            LongBuffer pImage = stack.mallocLong(1);
            PointerBuffer pBufferAllocation = stack.mallocPointer(1);
            LongBuffer pBuffer = stack.mallocLong(1);
            _CHECK_(vmaCreateBuffer(vmaAllocator, VkBufferCreateInfo(stack).size(imageSize).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT),
                    VmaAllocationCreateInfo(stack).usage(VMA_MEMORY_USAGE_CPU_ONLY), pBuffer, pBufferAllocation, null),
                    "Failed to create stage buffer");
            PointerBuffer map = stack.mallocPointer(1);
            _CHECK_(vmaMapMemory(vmaAllocator, pBufferAllocation.get(0), map), "Failed to map buffer");
            for (int i = 0; i < 6; i++) {
                memCopy(memAddressSafe(images[i]), map.get(0) + layerSize * i, layerSize);
                stbi_image_free(images[i]);
            }
            vmaUnmapMemory(vmaAllocator, pBufferAllocation.get(0));
            PointerBuffer pImageAllocation = stack.mallocPointer(1);
            _CHECK_(vmaCreateImage(vmaAllocator, VkImageCreateInfo(stack)
                            .imageType(VK_IMAGE_TYPE_2D)
                            .format(VK_FORMAT_R8G8B8A8_UNORM)
                            .mipLevels(1)
                            .samples(VK_SAMPLE_COUNT_1_BIT)
                            .tiling(VK_IMAGE_TILING_OPTIMAL)
                            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                            .extent(e -> e.width(w.get(0)).height(h.get(0)).depth(1))
                            .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                            .arrayLayers(6)
                            .flags(VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT),
                    VmaAllocationCreateInfo(stack).usage(VMA_MEMORY_USAGE_GPU_ONLY), pImage, pImageAllocation, null),
                    "Failed to create image");
            LongBuffer pView = stack.mallocLong(1);
            _CHECK_(vkCreateImageView(device, VkImageViewCreateInfo(stack)
                    .viewType(VK_IMAGE_VIEW_TYPE_CUBE)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .components(VkComponentMapping(stack)
                            .r(VK_COMPONENT_SWIZZLE_IDENTITY)
                            .g(VK_COMPONENT_SWIZZLE_IDENTITY)
                            .b(VK_COMPONENT_SWIZZLE_IDENTITY)
                            .a(VK_COMPONENT_SWIZZLE_IDENTITY))
                    .subresourceRange(VkImageSubresourceRange(stack)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(6).levelCount(1))
                    .image(pImage.get(0)), null, pView), "Failed to create image view");
            VkCommandBuffer cmdBuffer = createCommandBuffer();
            transitionImageLayout(cmdBuffer, pImage.get(0), VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 6);
            vkCmdCopyBufferToImage(cmdBuffer, pBuffer.get(0), pImage.get(0), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VkBufferImageCopy(stack)
                    .imageSubresource(isl -> isl.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                       .layerCount(6)).imageExtent(e -> e.width(w.get(0)).height(h.get(0)).depth(1)));
            transitionImageLayout(cmdBuffer, pImage.get(0), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV, 6);
            long buffer = pBuffer.get(0);
            long bufferAllocation = pBufferAllocation.get(0);
            submitCommandBuffer(cmdBuffer, true, () -> vmaDestroyBuffer(vmaAllocator, buffer, bufferAllocation));
            EnvironmentTexture ret = new EnvironmentTexture();
            ret.image = pImage.get(0);
            ret.allocation = pImageAllocation.get(0);
            ret.imageView = pView.get(0);
            return ret;
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
        commandPool = createCommandPool();
        rayTracingProperties = initRayTracing();
        geometry = createGeometry(stack, "org/lwjgl/demo/vulkan/lwjgl3.obj");
        blas = createBottomLevelAccelerationStructure(geometry);
        tlas = createTopLevelAccelerationStructure();
        ubo = createMappedUniformBufferObject();
        environmentTexture = createEnvironmentMap();
        pipeline = createRayTracingPipeline();
        shaderBindingTable = createShaderBindingTable();
        sampler = createSampler();
        descriptorSets = createDescriptorSets();
        commandBuffers = createRayTracingCommandBuffers();
        renderCompleteSemaphore = createSemaphore();
        imageAcquireSemaphore = createSemaphore();
    }

    private static void cleanup() {
        fenceWaiter.shutdown();
        try {
            if (!fenceWaiter.awaitTermination(1000, TimeUnit.MILLISECONDS))
                throw new AssertionError("Failed to wait for commands to complete");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        _CHECK_(vkDeviceWaitIdle(device), "Failed to wait for device idle");
        vkDestroySemaphore(device, imageAcquireSemaphore, null);
        vkDestroySemaphore(device, renderCompleteSemaphore, null);
        vkDestroySampler(device, sampler, null);
        environmentTexture.free();
        freeCommandBuffers();
        descriptorSets.free();
        ubo.free();
        shaderBindingTable.free();
        pipeline.free();
        tlas.free();
        blas.free();
        swapchain.free();
        vkDestroyCommandPool(device, commandPool, null);
        vmaDestroyAllocator(vmaAllocator);
        vkDestroyDevice(device, null);
        if (debugCallbackHandle != null) {
            debugCallbackHandle.free();
        }
        vkDestroySurfaceKHR(instance, surface, null);
        vkDestroyInstance(instance, null);
        windowAndCallbacks.free();
    }

    private static void reallocateOnResize() {
        swapchain = createSwapChain();
        shaderBindingTable = createShaderBindingTable();
        descriptorSets = createDescriptorSets();
        commandBuffers = createRayTracingCommandBuffers();
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

    private static void submitAndPresent(int imageIndex) {
        try (MemoryStack stack = stackPush()) {
            synchronized (commandPoolLock) {
                _CHECK_(vkQueueSubmit(queue, VkSubmitInfo(stack)
                        .pCommandBuffers(stack.pointers(commandBuffers[imageIndex]))
                        .pSignalSemaphores(stack.longs(renderCompleteSemaphore))
                        .pWaitSemaphores(stack.longs(imageAcquireSemaphore))
                        .waitSemaphoreCount(1)
                        .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_NV)), VK_NULL_HANDLE), "Failed to submit queue");
                _CHECK_(vkQueuePresentKHR(queue, VkPresentInfoKHR(stack)
                        .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                        .pWaitSemaphores(stack.longs(renderCompleteSemaphore))
                        .swapchainCount(1)
                        .pSwapchains(stack.longs(swapchain.swapchain))
                        .pImageIndices(stack.ints(imageIndex))), "Failed to present image");
            }
        }
    }

    public static void main(String[] args) throws IOException {
        try (MemoryStack stack = stackPush()) {
            init(stack);
            glfwShowWindow(windowAndCallbacks.window);
            IntBuffer pImageIndex = stack.mallocInt(1);
            long lastTime = System.nanoTime();
            while (!glfwWindowShouldClose(windowAndCallbacks.window)) {
                long thisTime = System.nanoTime();
                float dt = (thisTime - lastTime) * 1E-9f;
                lastTime = thisTime;
                pollEvents();
                updateCamera(dt);
                if (!isWindowRenderable()) {
                    continue;
                }
                if (windowSizeChanged()) {
                    reallocateOnResize();
                }
                updateUniformBufferObject();
                _CHECK_(vkAcquireNextImageKHR(device, swapchain.swapchain, -1L, imageAcquireSemaphore, VK_NULL_HANDLE, pImageIndex),
                        "Failed to acquire image");
                submitAndPresent(pImageIndex.get(0));
                _CHECK_(vkQueueWaitIdle(queue), "Failed to wait for idle queue");
            }
            cleanup();
        }
    }

}
