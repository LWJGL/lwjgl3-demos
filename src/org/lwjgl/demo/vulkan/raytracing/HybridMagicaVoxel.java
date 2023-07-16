/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.vulkan.raytracing;

import static java.lang.ClassLoader.getSystemResourceAsStream;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.joml.Math.*;
import static org.lwjgl.demo.vulkan.VKUtil.*;
import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK12.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.function.Consumer;

import org.joml.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.demo.util.*;
import org.lwjgl.demo.util.GreedyMeshingNoAo.Face;
import org.lwjgl.demo.util.MagicaVoxelLoader.Material;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.*;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

/**
 * Uses hybrid rasterization and ray tracing to trace shadow rays.
 * 
 * @author Kai Burjack
 */
public class HybridMagicaVoxel {

    private static final int VERTICES_PER_FACE = 4;
    private static final int INDICES_PER_FACE = 6;
    private static final int BITS_FOR_POSITIONS = 7; // <- allow for a position maximum of 128
    private static final int POSITION_SCALE = 1 << BITS_FOR_POSITIONS;
    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("debug", "true"));
    static {
        if (DEBUG) {
            // When we are in debug mode, enable all LWJGL debug flags
            Configuration.DEBUG.set(true);
            Configuration.DEBUG_FUNCTIONS.set(true);
            Configuration.DEBUG_LOADER.set(true);
            Configuration.DEBUG_MEMORY_ALLOCATOR.set(true);
            Configuration.DEBUG_MEMORY_ALLOCATOR_FAST.set(true);
            Configuration.DEBUG_STACK.set(true);
        } else {
            Configuration.DISABLE_CHECKS.set(true);
        }
    }

    private static WindowAndCallbacks windowAndCallbacks;
    private static VkInstance instance;
    private static long surface;
    private static DebugCallbackAndHandle debugCallbackHandle;
    private static DeviceAndQueueFamilies deviceAndQueueFamilies;
    private static int queueFamily;
    private static VkDevice device;
    private static long vmaAllocator;
    private static VkQueue queue;
    private static Swapchain swapchain;
    private static int depthFormat;
    private static long commandPool, commandPoolTransient;
    private static VkCommandBuffer[] rasterCommandBuffers;
    private static VkCommandBuffer[] rayTracingCommandBuffers;
    private static AllocationAndImage[] depthStencilImages;
    private static AllocationAndImage[] normalImages;
    private static long sampler;
    private static long[] imageAcquireSemaphores;
    private static long[] rayTraceCompleteSemaphores;
    private static long[] rasterCompleteSemaphores;
    private static long[] renderFences;
    private static long queryPool;
    private static long renderPass;
    private static long[] framebuffers;
    private static final Map<Long, Runnable> waitingFenceActions = new HashMap<>();
    private static Geometry geometry;
    private static AccelerationStructure blas, tlas;
    private static RayTracingPipeline rayTracingPipeline;
    private static Pipeline rasterPipeline;
    private static final int uboStructSize = 3 * 16 * Float.BYTES;
    private static AllocationAndBuffer ubo;
    private static AllocationAndBuffer sbt;
    private static AllocationAndBuffer materialsBuffer;
    private static DescriptorSets rayTracingDescriptorSets;
    private static DescriptorSets rasterDescriptorSets;
    private static final Matrix4f projMatrix = new Matrix4f();
    private static final Vector3f cameraPosition = new Vector3f(10, 40, 150);
    private static final Quaternionf cameraRotation = new Quaternionf();
    private static float cameraRotationX = 0.3f, cameraRotationY = 0.5f;
    private static final Matrix4x3f viewMatrix = new Matrix4x3f();
    private static final Matrix4f mvpMatrix = new Matrix4f();
    private static final Matrix4f invProjMatrix = new Matrix4f();
    private static final Matrix4x3f invViewMatrix = new Matrix4x3f();
    private static final Vector3f tmpv3 = new Vector3f();
    private static final Material[] materials = new Material[256];
    private static final boolean[] keydown = new boolean[GLFW_KEY_LAST + 1];
    private static boolean mouseDown;
    private static int mouseX, mouseY;

    private static void onCursorPos(long window, double x, double y) {
        if (mouseDown) {
            float deltaX = (float) x - mouseX;
            float deltaY = (float) y - mouseY;
            cameraRotationY += deltaX * 0.001f;
            cameraRotationX += deltaY * 0.001f;
        }
        mouseX = (int) x;
        mouseY = (int) y;
    }

    private static void onKey(long window, int key, int scancode, int action, int mods) {
        if (key == GLFW_KEY_ESCAPE)
            glfwSetWindowShouldClose(window, true);
        if (key >= 0)
            keydown[key] = action == GLFW_PRESS || action == GLFW_REPEAT;
    }

    private static void onMouseButton(long window, int button, int action, int mods) {
        mouseDown = action == GLFW_PRESS;
    }

    private static void registerWindowCallbacks(long window) {
        glfwSetKeyCallback(window, HybridMagicaVoxel::onKey);
        glfwSetCursorPosCallback(window, HybridMagicaVoxel::onCursorPos);
        glfwSetMouseButtonCallback(window, HybridMagicaVoxel::onMouseButton);
    }

    private static class WindowAndCallbacks {
        private final long window;
        private int width;
        private int height;
        private WindowAndCallbacks(long window, int width, int height) {
            this.window = window;
            this.width = width;
            this.height = height;
        }
        private void free() {
            glfwFreeCallbacks(window);
            glfwDestroyWindow(window);
        }
    }

    private static class DebugCallbackAndHandle {
        private final long messengerHandle;
        private final VkDebugUtilsMessengerCallbackEXT callback;
        private DebugCallbackAndHandle(long handle, VkDebugUtilsMessengerCallbackEXT callback) {
            this.messengerHandle = handle;
            this.callback = callback;
        }
        private void free() {
            vkDestroyDebugUtilsMessengerEXT(instance, messengerHandle, null);
            callback.free();
        }
    }

    private static class QueueFamilies {
        private final List<Integer> computeFamilies = new ArrayList<>();
        private final List<Integer> presentFamilies = new ArrayList<>();
        private int findSingleSuitableQueue() {
            return computeFamilies
                    .stream()
                    .filter(presentFamilies::contains)
                    .findAny()
                    .orElseThrow(() -> new AssertionError("No suitable queue found"));
        }
    }

    private static class DeviceAndQueueFamilies {
        private final VkPhysicalDevice physicalDevice;
        private final QueueFamilies queuesFamilies;
        private final int shaderGroupBaseAlignment;
        private final int minUniformBufferOffsetAlignment;
        private final int minAccelerationStructureScratchOffsetAlignment;
        private DeviceAndQueueFamilies(VkPhysicalDevice physicalDevice, QueueFamilies queuesFamilies,
                int shaderGroupBaseAlignment,
                int minUniformBufferOffsetAlignment,
                int minAccelerationStructureScratchOffsetAlignment) {
            this.physicalDevice = physicalDevice;
            this.queuesFamilies = queuesFamilies;
            this.shaderGroupBaseAlignment = shaderGroupBaseAlignment;
            this.minUniformBufferOffsetAlignment = minUniformBufferOffsetAlignment;
            this.minAccelerationStructureScratchOffsetAlignment = minAccelerationStructureScratchOffsetAlignment;
        }
    }

    private static class ColorFormatAndSpace {
        private final int colorFormat;
        private final int colorSpace;
        private ColorFormatAndSpace(int colorFormat, int colorSpace) {
            this.colorFormat = colorFormat;
            this.colorSpace = colorSpace;
        }
    }

    private static class Swapchain {
        private final long swapchain;
        private final long[] images;
        private final long[] imageViews;
        private final int width, height;
        private Swapchain(long swapchain, long[] images, long[] imageViews, int width, int height) {
            this.swapchain = swapchain;
            this.images = images;
            this.imageViews = imageViews;
            this.width = width;
            this.height = height;
        }
        private void free() {
            vkDestroySwapchainKHR(device, swapchain, null);
            for (long imageView : imageViews)
                vkDestroyImageView(device, imageView, null);
        }
    }

    private static class RayTracingPipeline {
        private final long pipelineLayout;
        private final long descriptorSetLayout;
        private final long pipeline;
        private RayTracingPipeline(long pipelineLayout, long descriptorSetLayout, long pipeline) {
            this.pipelineLayout = pipelineLayout;
            this.descriptorSetLayout = descriptorSetLayout;
            this.pipeline = pipeline;
        }
        private void free() {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            vkDestroyPipeline(device, pipeline, null);
        }
    }

    private static class DescriptorSets {
        private final long descriptorPool;
        private final long[] sets;
        private DescriptorSets(long descriptorPool, long[] sets) {
            this.descriptorPool = descriptorPool;
            this.sets = sets;
        }
        private void free() {
            vkDestroyDescriptorPool(device, descriptorPool, null);
        }
    }

    private static PointerBuffer pointers(MemoryStack stack, PointerBuffer pts, ByteBuffer... pointers) {
        PointerBuffer res = stack.mallocPointer(pts.remaining() + pointers.length);
        res.put(pts);
        for (ByteBuffer pointer : pointers) {
            res.put(pointer);
        }
        res.flip();
        return res;
    }

    private static List<String> enumerateSupportedInstanceExtensions() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pPropertyCount = stack.mallocInt(1);
            _CHECK_(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, pPropertyCount, null),
                    "Could not enumerate number of instance extensions");
            int propertyCount = pPropertyCount.get(0);
            VkExtensionProperties.Buffer pProperties = VkExtensionProperties.malloc(propertyCount, stack);
            _CHECK_(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, pPropertyCount, pProperties),
                    "Could not enumerate instance extensions");
            return pProperties.stream().map(VkExtensionProperties::extensionNameString).collect(toList());
        }
    }

    private static List<String> enumerateSupportedInstanceLayers() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pPropertyCount = stack.mallocInt(1);
            vkEnumerateInstanceLayerProperties(pPropertyCount, null);
            int count = pPropertyCount.get(0);
            if (count > 0) {
                VkLayerProperties.Buffer pProperties = VkLayerProperties.malloc(count, stack);
                vkEnumerateInstanceLayerProperties(pPropertyCount, pProperties);
                return pProperties.stream().map(VkLayerProperties::layerNameString).collect(toList());
            }
        }
        return emptyList();
    }

    private static VkInstance createInstance(PointerBuffer requiredExtensions) {
        List<String> supportedInstanceExtensions = enumerateSupportedInstanceExtensions();
        try (MemoryStack stack = stackPush()) {
            PointerBuffer ppEnabledExtensionNames = requiredExtensions;
            if (DEBUG) {
                if (!supportedInstanceExtensions.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                    throw new AssertionError(VK_EXT_DEBUG_UTILS_EXTENSION_NAME + " is not supported on the instance");
                }
                ppEnabledExtensionNames = pointers(stack, requiredExtensions, stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            }
            PointerBuffer ppEnabledLayerNames = null;
            if (DEBUG) {
                List<String> supportedLayers = enumerateSupportedInstanceLayers();
                if (!supportedLayers.contains("VK_LAYER_KHRONOS_validation")) {
                    System.err.println("DEBUG requested but layer VK_LAYER_KHRONOS_validation is unavailable. Install the Vulkan SDK for your platform. Vulkan debug layer will not be used.");
                } else {
                    ppEnabledLayerNames = stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"));
                }
            }
            VkInstanceCreateInfo pCreateInfo = VkInstanceCreateInfo
                    .calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(VkApplicationInfo
                            .calloc(stack)
                            .sType$Default()
                            .apiVersion(VK_API_VERSION_1_2))
                    .ppEnabledLayerNames(ppEnabledLayerNames)
                    .ppEnabledExtensionNames(ppEnabledExtensionNames);
            PointerBuffer pInstance = stack.mallocPointer(1);
            _CHECK_(vkCreateInstance(pCreateInfo, null, pInstance), "Failed to create VkInstance");
            return new VkInstance(pInstance.get(0), pCreateInfo);
        }
    }

    private static PointerBuffer initGlfwAndReturnRequiredExtensions() {
        if (!glfwInit())
            throw new AssertionError("Failed to initialize GLFW");
        if (!glfwVulkanSupported())
            throw new AssertionError("GLFW failed to find the Vulkan loader");
        PointerBuffer requiredExtensions = glfwGetRequiredInstanceExtensions();
        if (requiredExtensions == null)
            throw new AssertionError("Failed to find list of required Vulkan extensions");
        return requiredExtensions;
    }

    private static WindowAndCallbacks createWindow() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
        GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        long window = glfwCreateWindow(mode.width(), mode.height(), "Hello, hybrid rendered MagicaVoxel!", NULL, NULL);
        registerWindowCallbacks(window);
        int w, h;
        try (MemoryStack stack = stackPush()) {
            IntBuffer addr = stack.mallocInt(2);
            nglfwGetFramebufferSize(window, memAddress(addr), memAddress(addr) + Integer.BYTES);
            w = addr.get(0);
            h = addr.get(1);
        }
        return new WindowAndCallbacks(window, w, h);
    }

    private static long createSurface() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer surface = stack.mallocLong(1);
            _CHECK_(glfwCreateWindowSurface(instance, windowAndCallbacks.window, null, surface), "Failed to create surface");
            return surface.get(0);
        }
    }

    private static DebugCallbackAndHandle setupDebugging() {
        if (!DEBUG) {
            return null;
        }
        VkDebugUtilsMessengerCallbackEXT callback = new VkDebugUtilsMessengerCallbackEXT() {
            public int invoke(int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
                VkDebugUtilsMessengerCallbackDataEXT message = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                System.err.println(message.pMessageString());
                return 0;
            }
        };
        try (MemoryStack stack = stackPush()) {
            LongBuffer pMessenger = stack.mallocLong(1);
            _CHECK_(vkCreateDebugUtilsMessengerEXT(instance,
                    VkDebugUtilsMessengerCreateInfoEXT
                        .calloc(stack)
                        .sType$Default()
                        .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                                         VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                                     VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                                     VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(callback),
                    null, pMessenger),
                    "Failed to create debug messenger");
            return new DebugCallbackAndHandle(pMessenger.get(0), callback);
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
            int numQueueFamilies = pQueueFamilyPropertyCount.get(0);
            if (numQueueFamilies == 0)
                throw new AssertionError("No queue families found");
            VkQueueFamilyProperties.Buffer familyProperties = VkQueueFamilyProperties.malloc(numQueueFamilies, stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, familyProperties);
            int queueFamilyIndex = 0;
            IntBuffer pSupported = stack.mallocInt(1);
            for (VkQueueFamilyProperties queueFamilyProps : familyProperties) {
                if (queueFamilyProps.queueCount() < 1) {
                    continue;
                }
                vkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, queueFamilyIndex, surface, pSupported);
                // we need compute for acceleration structure build and ray tracing commands.
                // we will also use this for vkCmdCopyBuffer
                if (familySupports(queueFamilyProps, VK_QUEUE_COMPUTE_BIT))
                    ret.computeFamilies.add(queueFamilyIndex);
                // we also need present
                if (pSupported.get(0) != 0)
                    ret.presentFamilies.add(queueFamilyIndex);
                queueFamilyIndex++;
            }
            return ret;
        }
    }

    private static boolean isDeviceSuitable(QueueFamilies queuesFamilies) {
        return !queuesFamilies.computeFamilies.isEmpty() && !queuesFamilies.presentFamilies.isEmpty();
    }

    private static DeviceAndQueueFamilies selectPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            // Retrieve number of available physical devices
            IntBuffer pPhysicalDeviceCount = stack.mallocInt(1);
            _CHECK_(vkEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, null),
                    "Failed to get number of physical devices");
            int physicalDeviceCount = pPhysicalDeviceCount.get(0);
            if (physicalDeviceCount == 0)
                throw new AssertionError("No physical devices available");

            // Retrieve pointers to all available physical devices
            PointerBuffer pPhysicalDevices = stack.mallocPointer(physicalDeviceCount);
            _CHECK_(vkEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, pPhysicalDevices),
                    "Failed to get physical devices");

            // and enumerate them to see which one we will use...
            for (int i = 0; i < physicalDeviceCount; i++) {
                VkPhysicalDevice dev = new VkPhysicalDevice(pPhysicalDevices.get(i), instance);
                // Check if the device supports all needed features
                VkPhysicalDeviceAccelerationStructureFeaturesKHR accelerationStructureFeatures = VkPhysicalDeviceAccelerationStructureFeaturesKHR
                        .malloc(stack)
                        .sType$Default();
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracingPipelineFeatures = VkPhysicalDeviceRayTracingPipelineFeaturesKHR
                        .malloc(stack)
                        .sType$Default();
                VkPhysicalDeviceVulkan12Features vulkan12Features = VkPhysicalDeviceVulkan12Features
                        .malloc(stack)
                        .sType$Default();
                vkGetPhysicalDeviceFeatures2(dev, VkPhysicalDeviceFeatures2
                        .calloc(stack)
                        .sType$Default()
                        .pNext(vulkan12Features)
                        .pNext(rayTracingPipelineFeatures)
                        .pNext(accelerationStructureFeatures));

                // If any of the above is not supported, we continue with the next physical device
                if (!vulkan12Features.bufferDeviceAddress() ||
                    !rayTracingPipelineFeatures.rayTracingPipeline() ||
                    !accelerationStructureFeatures.accelerationStructure())
                    continue;

                // Check if the physical device supports the VK_FORMAT_R16G16B16_UNORM vertexFormat for acceleration structure geometry
                VkFormatProperties formatProperties = VkFormatProperties.malloc(stack);
                vkGetPhysicalDeviceFormatProperties(dev, VK_FORMAT_R16G16B16_UNORM, formatProperties);
                if ((formatProperties.bufferFeatures() & VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR) == 0)
                    continue;

                // Retrieve physical device properties (limits, offsets, alignments, ...)
                VkPhysicalDeviceAccelerationStructurePropertiesKHR accelerationStructureProperties = VkPhysicalDeviceAccelerationStructurePropertiesKHR
                        .malloc(stack)
                        .sType$Default();
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR rayTracingProperties = VkPhysicalDeviceRayTracingPipelinePropertiesKHR
                        .malloc(stack)
                        .sType$Default();
                VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2
                        .calloc(stack)
                        .sType$Default()
                        .pNext(rayTracingProperties)
                        .pNext(accelerationStructureProperties);
                vkGetPhysicalDeviceProperties2(dev, props);

                // Check queue families
                QueueFamilies queuesFamilies = obtainQueueFamilies(dev);
                if (isDeviceSuitable(queuesFamilies)) {
                    return new DeviceAndQueueFamilies(dev, queuesFamilies,
                            rayTracingProperties.shaderGroupBaseAlignment(),
                            (int) props.properties().limits().minUniformBufferOffsetAlignment(),
                            accelerationStructureProperties.minAccelerationStructureScratchOffsetAlignment());
                }
            }
            throw new AssertionError("No suitable physical device found");
        }
    }

    private static VkDevice createDevice(List<String> requiredExtensions) {
        List<String> supportedDeviceExtensions = enumerateSupportedDeviceExtensions();
        for (String requiredExtension : requiredExtensions) {
            if (!supportedDeviceExtensions.contains(requiredExtension))
                throw new AssertionError(requiredExtension + " device extension is not supported");
        }
        try (MemoryStack stack = stackPush()) {
            PointerBuffer extensions = stack.mallocPointer(requiredExtensions.size());
            for (String requiredExtension : requiredExtensions)
                extensions.put(stack.UTF8(requiredExtension));
            extensions.flip();
            PointerBuffer ppEnabledLayerNames = null;
            if (DEBUG) {
                ppEnabledLayerNames = stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"));
            }
            VkDeviceCreateInfo pCreateInfo = VkDeviceCreateInfo
                    .calloc(stack)
                    .sType$Default()
                    .pNext(VkPhysicalDeviceVulkan12Features
                            .calloc(stack)
                            .sType$Default()
                            .bufferDeviceAddress(true))
                    .pNext(VkPhysicalDeviceRayTracingPipelineFeaturesKHR
                            .calloc(stack)
                            .sType$Default()
                            .rayTracingPipeline(true))
                    .pNext(VkPhysicalDeviceAccelerationStructureFeaturesKHR
                            .calloc(stack)
                            .sType$Default()
                            .accelerationStructure(true))
                    .pQueueCreateInfos(VkDeviceQueueCreateInfo
                            .calloc(1, stack)
                            .sType$Default()
                            .queueFamilyIndex(queueFamily)
                            .pQueuePriorities(stack.floats(1.0f)))
                    .ppEnabledLayerNames(ppEnabledLayerNames)
                    .ppEnabledExtensionNames(extensions);
            PointerBuffer pDevice = stack.mallocPointer(1);
            _CHECK_(vkCreateDevice(deviceAndQueueFamilies.physicalDevice, pCreateInfo, null, pDevice),
                    "Failed to create device");
            return new VkDevice(pDevice.get(0), deviceAndQueueFamilies.physicalDevice, pCreateInfo, VK_API_VERSION_1_2);
        }
    }

    private static List<String> enumerateSupportedDeviceExtensions() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pPropertyCount = stack.mallocInt(1);
            _CHECK_(vkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, (ByteBuffer) null, pPropertyCount, null),
                    "Failed to get number of device extensions");
            int propertyCount = pPropertyCount.get(0);
            VkExtensionProperties.Buffer pProperties = VkExtensionProperties.malloc(propertyCount, stack);
            _CHECK_(vkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, (ByteBuffer) null, pPropertyCount, pProperties),
                    "Failed to enumerate the device extensions");
            return pProperties.stream().map(VkExtensionProperties::extensionNameString).collect(toList());
        }
    }

    private static long createVmaAllocator() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pAllocator = stack.mallocPointer(1);
            _CHECK_(vmaCreateAllocator(VmaAllocatorCreateInfo
                        .calloc(stack)
                        .flags(VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                        .physicalDevice(deviceAndQueueFamilies.physicalDevice)
                        .device(device)
                        .pVulkanFunctions(VmaVulkanFunctions
                                .calloc(stack)
                                .set(instance, device))
                        .instance(instance)
                        .vulkanApiVersion(VK_API_VERSION_1_2), pAllocator),
                    "Failed to create VMA allocator");
            return pAllocator.get(0);
        }
    }

    private static VkQueue retrieveQueue() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, queueFamily, 0, pQueue);
            return new VkQueue(pQueue.get(0), device);
        }
    }

    private static ColorFormatAndSpace determineSurfaceFormat(VkPhysicalDevice physicalDevice, long surface) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pSurfaceFormatCount = stack.mallocInt(1);
            _CHECK_(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pSurfaceFormatCount, null),
                    "Failed to get number of device surface formats");
            VkSurfaceFormatKHR.Buffer pSurfaceFormats = VkSurfaceFormatKHR
                    .malloc(pSurfaceFormatCount.get(0), stack);
            _CHECK_(vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pSurfaceFormatCount, pSurfaceFormats),
                    "Failed to get device surface formats");
            for (VkSurfaceFormatKHR surfaceFormat : pSurfaceFormats) {
                if (surfaceFormat.format() == VK_FORMAT_B8G8R8A8_UNORM) {
                    return new ColorFormatAndSpace(surfaceFormat.format(), surfaceFormat.colorSpace());
                }
            }
            return new ColorFormatAndSpace(pSurfaceFormats.get(0).format(), pSurfaceFormats.get(0).colorSpace());
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

    private static Swapchain createSwapchain() {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR pSurfaceCapabilities = VkSurfaceCapabilitiesKHR
                    .malloc(stack);
            _CHECK_(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(deviceAndQueueFamilies.physicalDevice, surface, pSurfaceCapabilities),
                    "Failed to get physical device surface capabilities");
            IntBuffer pPresentModeCount = stack.mallocInt(1);
            _CHECK_(vkGetPhysicalDeviceSurfacePresentModesKHR(deviceAndQueueFamilies.physicalDevice, surface, pPresentModeCount, null),
                    "Failed to get presentation modes count");
            int presentModeCount = pPresentModeCount.get(0);
            IntBuffer pPresentModes = stack.mallocInt(presentModeCount);
            _CHECK_(vkGetPhysicalDeviceSurfacePresentModesKHR(deviceAndQueueFamilies.physicalDevice, surface, pPresentModeCount, pPresentModes),
                    "Failed to get presentation modes");
            int imageCount = min(max(pSurfaceCapabilities.minImageCount(), 2), pSurfaceCapabilities.maxImageCount());
            ColorFormatAndSpace surfaceFormat = determineSurfaceFormat(deviceAndQueueFamilies.physicalDevice, surface);
            Vector2i swapchainExtents = determineSwapchainExtents(pSurfaceCapabilities);
            LongBuffer pSwapchain = stack.mallocLong(1);
            _CHECK_(vkCreateSwapchainKHR(device, VkSwapchainCreateInfoKHR
                .calloc(stack)
                .sType$Default()
                .surface(surface)
                .minImageCount(imageCount)
                .imageFormat(surfaceFormat.colorFormat)
                .imageColorSpace(surfaceFormat.colorSpace)
                .imageExtent(e -> e.width(swapchainExtents.x).height(swapchainExtents.y))
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_STORAGE_BIT) // <- for writing to in the raygen shader
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(pSurfaceCapabilities.currentTransform())
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .presentMode(VK_PRESENT_MODE_FIFO_KHR)
                .clipped(true)
                .oldSwapchain(swapchain != null ? swapchain.swapchain : VK_NULL_HANDLE), null, pSwapchain),
                    "Failed to create swap chain");
            if (swapchain != null) {
                swapchain.free();
            }
            long swapchain = pSwapchain.get(0);
            IntBuffer pSwapchainImageCount = stack.mallocInt(1);
            _CHECK_(vkGetSwapchainImagesKHR(device, swapchain, pSwapchainImageCount, null),
                    "Failed to get swapchain images count");
            int actualImageCount = pSwapchainImageCount.get(0);
            LongBuffer pSwapchainImages = stack.mallocLong(actualImageCount);
            _CHECK_(vkGetSwapchainImagesKHR(device, swapchain, pSwapchainImageCount, pSwapchainImages),
                    "Failed to get swapchain images");
            long[] images = new long[actualImageCount];
            pSwapchainImages.get(images, 0, images.length);
            long[] imageViews = new long[actualImageCount];
            LongBuffer pImageView = stack.mallocLong(1);
            for (int i = 0; i < actualImageCount; i++) {
                _CHECK_(vkCreateImageView(device,
                        VkImageViewCreateInfo
                            .calloc(stack)
                            .sType$Default()
                            .image(pSwapchainImages.get(i))
                            .viewType(VK_IMAGE_TYPE_2D)
                            .format(surfaceFormat.colorFormat)
                            .subresourceRange(r -> r
                                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                    .layerCount(1)
                                    .levelCount(1)),
                        null, pImageView),
                        "Failed to create image view");
                imageViews[i] = pImageView.get(0);
            }
            return new Swapchain(swapchain, images, imageViews, swapchainExtents.x, swapchainExtents.y);
        }
    }

    private static int determineDepthFormat() {
        try (MemoryStack stack = stackPush()) {
            // prefer any 32-bit signed float depth, and then 24-bit integer depth
            int[] depthFormats = {
                VK_FORMAT_D32_SFLOAT,
                VK_FORMAT_D32_SFLOAT_S8_UINT,
                VK_FORMAT_D24_UNORM_S8_UINT
            };
            VkFormatProperties formatProps = VkFormatProperties
                    .malloc(stack);
            int depthFormat = -1;
            for (int format : depthFormats) {
                vkGetPhysicalDeviceFormatProperties(deviceAndQueueFamilies.physicalDevice, format, formatProps);
                if ((formatProps.optimalTilingFeatures() & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                    depthFormat = format;
                    break;
                }
            }
            return depthFormat;
        }
    }

    private static AllocationAndImage[] createDepthStencilImages() {
        if (depthStencilImages != null) {
            for (AllocationAndImage aai : depthStencilImages)
                aai.free();
        }
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo depthStencilViewCreateInfo = VkImageViewCreateInfo
                    .calloc(stack)
                    .sType$Default()
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(depthFormat)
                    .subresourceRange(r -> r
                            .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                            .levelCount(1)
                            .layerCount(1));
            LongBuffer pDepthStencilImage = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            AllocationAndImage[] allocations = new AllocationAndImage[swapchain.images.length];
            LongBuffer pDepthStencilView = stack.mallocLong(1);
            for (int i = 0; i < swapchain.images.length; i++) {
                _CHECK_(vmaCreateImage(vmaAllocator, VkImageCreateInfo
                        .calloc(stack)
                        .sType$Default()
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(depthFormat)
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT // <- write to depth when rasterizing
                             | VK_IMAGE_USAGE_SAMPLED_BIT) // <- read from depth when ray tracing
                        .extent(e -> e.set(swapchain.width, swapchain.height, 1)),
                        VmaAllocationCreateInfo
                            .calloc(stack)
                            .usage(VMA_MEMORY_USAGE_AUTO), pDepthStencilImage, pAllocation,
                        null), "Failed to create depth stencil image");
                depthStencilViewCreateInfo.image(pDepthStencilImage.get(0));
                _CHECK_(vkCreateImageView(device, depthStencilViewCreateInfo, null, pDepthStencilView),
                        "Failed to create image view for depth stencil image");
                allocations[i] = new AllocationAndImage(
                        pAllocation.get(0),
                        pDepthStencilImage.get(0), 
                        pDepthStencilView.get(0));
            }
            return allocations;
        }
    }

    private static long createSampler() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSampler = stack.mallocLong(1);
            _CHECK_(vkCreateSampler(device, VkSamplerCreateInfo
                        .calloc(stack)
                        .sType$Default()
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

    private static AllocationAndImage[] createColorImages(
            AllocationAndImage[] old, int format, int usage, int dstImageLayout, int dstStageMask, int dstAccessMask) {
        if (old != null)
            for (AllocationAndImage aai : old)
                aai.free();
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo imageViewCreateInfo = VkImageViewCreateInfo
                    .calloc(stack)
                    .sType$Default()
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
            VkCommandBuffer cmdBuffer = createCommandBuffer(commandPoolTransient, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            for (int i = 0; i < swapchain.images.length; i++) {
                _CHECK_(vmaCreateImage(vmaAllocator, VkImageCreateInfo
                        .calloc(stack)
                        .sType$Default()
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(format)
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(usage)
                        .extent(e -> e.set(swapchain.width, swapchain.height, 1)),
                        VmaAllocationCreateInfo
                            .calloc(stack)
                            .usage(VMA_MEMORY_USAGE_AUTO), pImage, pAllocation, null),
                        "Failed to create image");
                imageViewCreateInfo.image(pImage.get(0));
                _CHECK_(vkCreateImageView(device, imageViewCreateInfo, null, pImageView),
                        "Failed to create image view");
                allocations[i] = new AllocationAndImage(pAllocation.get(0), pImage.get(0), pImageView.get(0));
                vkCmdPipelineBarrier(cmdBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, dstStageMask,0,
                        null,null, VkImageMemoryBarrier
                            .calloc(1, stack)
                            .sType$Default()
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

    private static AllocationAndImage[] createNormalImages() {
        return createColorImages(
                normalImages,
                VK_FORMAT_R8G8B8A8_SNORM,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
    }

    private static long createCommandPool(int flags) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pCmdPool = stack.mallocLong(1);
            _CHECK_(vkCreateCommandPool(device, VkCommandPoolCreateInfo
                    .calloc(stack)
                    .sType$Default()
                    .flags(flags)
                    .queueFamilyIndex(queueFamily), null, pCmdPool),
                    "Failed to create command pool");
            return pCmdPool.get(0);
        }
    }

    private static VkCommandBuffer createCommandBuffer(long pool, int beginFlags) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            _CHECK_(vkAllocateCommandBuffers(device,
                    VkCommandBufferAllocateInfo
                        .calloc(stack)
                        .sType$Default()
                        .commandPool(pool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1), pCommandBuffer),
                    "Failed to create command buffer");
            VkCommandBuffer cmdBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);
            _CHECK_(vkBeginCommandBuffer(cmdBuffer, VkCommandBufferBeginInfo
                        .calloc(stack)
                        .sType$Default()
                        .flags(beginFlags)),
                    "Failed to begin command buffer");
            return cmdBuffer;
        }
    }

    private static void createSyncObjects() {
        imageAcquireSemaphores = new long[swapchain.imageViews.length];
        rasterCompleteSemaphores = new long[swapchain.imageViews.length];
        rayTraceCompleteSemaphores = new long[swapchain.imageViews.length];
        renderFences = new long[swapchain.imageViews.length];
        for (int i = 0; i < swapchain.imageViews.length; i++) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer pSemaphore = stack.mallocLong(1);
                VkSemaphoreCreateInfo pCreateInfo = VkSemaphoreCreateInfo
                        .calloc(stack)
                        .sType$Default();
                _CHECK_(vkCreateSemaphore(device, pCreateInfo, null, pSemaphore),
                        "Failed to create image acquire semaphore");
                imageAcquireSemaphores[i] = pSemaphore.get(0);
                _CHECK_(vkCreateSemaphore(device, pCreateInfo, null, pSemaphore),
                        "Failed to create render complete semaphore");
                rasterCompleteSemaphores[i] = pSemaphore.get(0);
                _CHECK_(vkCreateSemaphore(device, pCreateInfo, null, pSemaphore),
                        "Failed to create ray trace complete semaphore");
                rayTraceCompleteSemaphores[i] = pSemaphore.get(0);
                LongBuffer pFence = stack.mallocLong(1);
                _CHECK_(vkCreateFence(device, VkFenceCreateInfo
                        .calloc(stack)
                        .sType$Default()
                        .flags(VK_FENCE_CREATE_SIGNALED_BIT), null, pFence),
                        "Failed to create fence");
                renderFences[i] = pFence.get(0);
            }
        }
    }

    private static void updateFramebufferSize() {
        try (MemoryStack stack = stackPush()) {
            long mem = stack.nmalloc(2 * Integer.BYTES);
            nglfwGetFramebufferSize(windowAndCallbacks.window, mem, mem + Integer.BYTES);
            windowAndCallbacks.width = memGetInt(mem);
            windowAndCallbacks.height = memGetInt(mem + Integer.BYTES);
        }
    }

    private static boolean isWindowRenderable() {
        return windowAndCallbacks.width > 0 && windowAndCallbacks.height > 0;
    }

    private static boolean windowSizeChanged() {
        return windowAndCallbacks.width != swapchain.width || windowAndCallbacks.height != swapchain.height;
    }

    private static void recreateSwapchainAndDependentResources() {
        swapchain = createSwapchain();
        depthStencilImages = createDepthStencilImages();
        normalImages = createNormalImages();
        rayTracingDescriptorSets = createRayTracingDescriptorSets();
        rasterDescriptorSets = createRasterDescriptorSets();
        framebuffers = createFramebuffers();
        rayTracingCommandBuffers = createRayTracingCommandBuffers();
        rasterCommandBuffers = createRasterCommandBuffers();
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

    private static boolean submitAndPresent(int imageIndex, int idx) {
        try (MemoryStack stack = stackPush()) {
            _CHECK_(vkQueueSubmit(queue, VkSubmitInfo
                    .calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(imageAcquireSemaphores[idx]))
                    // must wait before COLOR_ATTACHMENT_OUTPUT to output color values
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(rasterCommandBuffers[idx]))
                    .waitSemaphoreCount(1)
                    .pSignalSemaphores(stack.longs(rasterCompleteSemaphores[idx])),
                    VK_NULL_HANDLE),
                    "Failed to submit raster command buffer");
            _CHECK_(vkQueueSubmit(queue, VkSubmitInfo
                    .calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(rasterCompleteSemaphores[idx]))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR))
                    .pCommandBuffers(stack.pointers(rayTracingCommandBuffers[idx]))
                    .waitSemaphoreCount(1)
                    .pSignalSemaphores(stack.longs(rayTraceCompleteSemaphores[idx])),
                    renderFences[idx]),
                    "Failed to submit ray tracing command buffer");
            int result = vkQueuePresentKHR(queue, VkPresentInfoKHR
                    .calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(rayTraceCompleteSemaphores[idx]))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain.swapchain))
                    .pImageIndices(stack.ints(imageIndex)));
            return result != VK_ERROR_OUT_OF_DATE_KHR && result != VK_SUBOPTIMAL_KHR;
        }
    }

    private static boolean acquireSwapchainImage(IntBuffer pImageIndex, int idx) {
        int res = vkAcquireNextImageKHR(device, swapchain.swapchain, -1L, imageAcquireSemaphores[idx], VK_NULL_HANDLE, pImageIndex);
        return res != VK_ERROR_OUT_OF_DATE_KHR;
    }

    private static void runWndProcLoop() {
        glfwShowWindow(windowAndCallbacks.window);
        while (!glfwWindowShouldClose(windowAndCallbacks.window)) {
            glfwWaitEvents();
        }
    }

    private static class AllocationAndBuffer {
        private final long allocation;
        private final long buffer;
        private final boolean hostCoherent;
        private ByteBuffer mapped;
        private AllocationAndBuffer(long allocation, long buffer, boolean hostCoerent) {
            this.allocation = allocation;
            this.buffer = buffer;
            this.hostCoherent = hostCoerent;
        }
        private void free() {
            if (mapped != null) {
                vmaUnmapMemory(vmaAllocator, allocation);
                mapped = null;
            }
            vmaDestroyBuffer(vmaAllocator, buffer, allocation);
        }
        private void map(int size) {
            if (mapped != null)
                return;
            try (MemoryStack stack = stackPush()) {
                PointerBuffer pb = stack.mallocPointer(1);
                _CHECK_(vmaMapMemory(vmaAllocator, allocation, pb), "Failed to map allocation");
                mapped = memByteBuffer(pb.get(0), size);
            }
        }
        private void flushMapped(long offset, long size) {
            if (!hostCoherent)
                vmaFlushAllocation(vmaAllocator, allocation, offset, size);
        }
    }

    private static class AllocationAndImage {
        private final long allocation;
        private final long image;
        private final long imageView;
        private AllocationAndImage(long allocation, long image, long imageView) {
            this.allocation = allocation;
            this.image = image;
            this.imageView = imageView;
        }
        private void free() {
            vmaDestroyImage(vmaAllocator, image, allocation);
            vkDestroyImageView(device, imageView, null);
        }
    }

    private static class Geometry {
        private final AllocationAndBuffer positions;
        private final AllocationAndBuffer indices;
        private final int numFaces;
        private Geometry(AllocationAndBuffer positions, AllocationAndBuffer indices, int numFaces) {
            this.positions = positions;
            this.indices = indices;
            this.numFaces = numFaces;
        }
        private void free() {
            positions.free();
            indices.free();
        }
    }

    private static long submitCommandBuffer(VkCommandBuffer commandBuffer, boolean endCommandBuffer, Runnable afterComplete) {
        if (endCommandBuffer)
            _CHECK_(vkEndCommandBuffer(commandBuffer),
                    "Failed to end command buffer");
        try (MemoryStack stack = stackPush()) {
            LongBuffer pFence = stack.mallocLong(1);
            _CHECK_(vkCreateFence(device, VkFenceCreateInfo
                    .calloc(stack)
                    .sType$Default(), null, pFence),
                    "Failed to create fence");
            _CHECK_(vkQueueSubmit(queue, VkSubmitInfo
                    .calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(commandBuffer)), pFence.get(0)),
                    "Failed to submit command buffer");
            long fence = pFence.get(0);
            if (afterComplete != null)
                waitingFenceActions.put(fence, afterComplete);
            return fence;
        }
    }

    private static AllocationAndBuffer createBuffer(int usageFlags, long size, ByteBuffer data, long alignment, Consumer<VkCommandBuffer> beforeSubmit) {
        try (MemoryStack stack = stackPush()) {
            // create the final destination buffer
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            _CHECK_(vmaCreateBuffer(vmaAllocator,
                    VkBufferCreateInfo
                        .calloc(stack)
                        .sType$Default()
                        .size(size)
                        .usage(usageFlags | (data != null ? VK_BUFFER_USAGE_TRANSFER_DST_BIT : 0)),
                    VmaAllocationCreateInfo
                        .calloc(stack)
                        .usage(VMA_MEMORY_USAGE_AUTO), pBuffer, pAllocation, null),
                    "Failed to allocate buffer");

            // validate alignment
            VmaAllocationInfo ai = VmaAllocationInfo.create(pAllocation.get(0));
            if ((ai.offset() % alignment) != 0)
                throw new AssertionError("Illegal offset alignment");

            // if we have data to upload, use a staging buffer
            if (data != null) {
                // create the staging buffer
                LongBuffer pBufferStage = stack.mallocLong(1);
                PointerBuffer pAllocationStage = stack.mallocPointer(1);
                _CHECK_(vmaCreateBuffer(vmaAllocator, VkBufferCreateInfo
                            .calloc(stack)
                            .sType$Default()
                            .size(data.remaining())
                            .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT),
                        VmaAllocationCreateInfo
                            .calloc(stack)
                            .usage(VMA_MEMORY_USAGE_AUTO)
                            .flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT), pBufferStage, pAllocationStage, null),
                        "Failed to allocate stage buffer");

                // map the memory and memcpy into it
                PointerBuffer pData = stack.mallocPointer(1);
                _CHECK_(vmaMapMemory(vmaAllocator, pAllocationStage.get(0), pData), "Failed to map memory");
                memCopy(memAddress(data), pData.get(0), data.remaining());
                // no need to vkFlushMappedMemoryRanges(), because VMA guarantees VMA_MEMORY_USAGE_CPU_ONLY to be host coherent.
                vmaUnmapMemory(vmaAllocator, pAllocationStage.get(0));

                // issue copy buffer command
                VkCommandBuffer cmdBuffer = createCommandBuffer(commandPoolTransient, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                vkCmdCopyBuffer(cmdBuffer, pBufferStage.get(0), pBuffer.get(0), VkBufferCopy
                        .calloc(1, stack)
                        .size(data.remaining()));
                long bufferStage = pBufferStage.get(0);
                long allocationStage = pAllocationStage.get(0);

                if (beforeSubmit != null)
                    beforeSubmit.accept(cmdBuffer);

                // and submit that, with a callback to destroy the staging buffer once copying is complete
                submitCommandBuffer(cmdBuffer, true, () -> {
                    vkFreeCommandBuffers(device, commandPoolTransient, cmdBuffer);
                    vmaDestroyBuffer(vmaAllocator, bufferStage, allocationStage);
                });
            }
            return new AllocationAndBuffer(pAllocation.get(0), pBuffer.get(0), false);
        }
    }
    private static AllocationAndBuffer createBuffer(int usageFlags, ByteBuffer data, long alignment, Consumer<VkCommandBuffer> beforeSubmit) {
        return createBuffer(usageFlags, data.remaining(), data, alignment, beforeSubmit);
    }

    private static AllocationAndBuffer createUniformBufferObject(int size) {
        int totalSize = alignUp(size, deviceAndQueueFamilies.minUniformBufferOffsetAlignment) * swapchain.imageViews.length;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAllocation = stack.mallocPointer(1);
            VmaAllocationInfo pAllocationInfo = VmaAllocationInfo.malloc(stack);
            _CHECK_(vmaCreateBuffer(vmaAllocator, VkBufferCreateInfo
                        .calloc(stack)
                        .sType$Default()
                        .size(totalSize)
                        .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT),
                    VmaAllocationCreateInfo
                        .calloc(stack)
                        .usage(VMA_MEMORY_USAGE_AUTO)
                        .flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT), pBuffer, pAllocation, pAllocationInfo),
                    "Failed to allocate buffer");

            // check whether the allocation is host-coherent
            IntBuffer memTypeProperties = stack.mallocInt(1);
            vmaGetMemoryTypeProperties(vmaAllocator, pAllocationInfo.memoryType(), memTypeProperties);
            boolean isHostCoherent = (memTypeProperties.get(0) & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
            AllocationAndBuffer a = new AllocationAndBuffer(pAllocation.get(0), pBuffer.get(0), isHostCoherent);
            a.map(totalSize);
            return a;
        }
    }

    private static long createRasterRenderPass() {
        try (MemoryStack stack = stackPush()) {
            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo
                    .calloc(stack)
                    .sType$Default()
                    .pAttachments(VkAttachmentDescription
                            .calloc(2, stack)
                            .apply(0, d -> d
                                    .format(VK_FORMAT_R8G8B8A8_SNORM)
                                    .samples(VK_SAMPLE_COUNT_1_BIT)
                                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                                    .finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL))
                            .apply(1, d -> d
                                    .format(depthFormat)
                                    .samples(VK_SAMPLE_COUNT_1_BIT)
                                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                                    .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)))
                    .pSubpasses(VkSubpassDescription
                            .calloc(1, stack)
                            .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                            .colorAttachmentCount(1)
                            .pColorAttachments(VkAttachmentReference
                                    .calloc(1, stack)
                                    .attachment(0)
                                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL))
                            .pDepthStencilAttachment(VkAttachmentReference
                                    .calloc(stack)
                                    .attachment(1)
                                    .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)))
                    .pDependencies(VkSubpassDependency
                            .calloc(1, stack)
                            .srcSubpass(VK_SUBPASS_EXTERNAL)
                            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
                                    VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                            .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | 
                                    VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                            .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT));
            LongBuffer pRenderPass = stack.mallocLong(1);
            _CHECK_(vkCreateRenderPass(device, renderPassInfo, null, pRenderPass),
                    "Failed to create render pass");
            return pRenderPass.get(0);
        }
    }

    private static class Pipeline {
        private final long pipelineLayout;
        private final long descriptorSetLayout;
        private final long pipeline;
        private Pipeline(long pipelineLayout, long descriptorSetLayout, long pipeline) {
            this.pipelineLayout = pipelineLayout;
            this.descriptorSetLayout = descriptorSetLayout;
            this.pipeline = pipeline;
        }
        private void free() {
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
            vkDestroyPipeline(device, pipeline, null);
        }
    }

    private static Pipeline createRasterPipeline() throws IOException {
        try (MemoryStack stack = stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer pStages = VkPipelineShaderStageCreateInfo
                    .calloc(2, stack);
            String pkg = HybridMagicaVoxel.class.getName().toLowerCase().replace('.', '/') + "/";
            loadShader(pStages.get(0).sType$Default(), 
                    null, stack, device, pkg + "raster.vs.glsl", VK_SHADER_STAGE_VERTEX_BIT);
            loadShader(pStages.get(1).sType$Default(), 
                    null, stack, device, pkg + "raster.fs.glsl", VK_SHADER_STAGE_FRAGMENT_BIT);
            LongBuffer pDescriptorSetLayout = stack.mallocLong(1);
            _CHECK_(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo
                    .calloc(stack)
                    .sType$Default()
                    .pBindings(VkDescriptorSetLayoutBinding
                            .calloc(1, stack)
                            .binding(0)
                            .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .descriptorCount(1)
                            .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)), null, pDescriptorSetLayout),
                    "Failed to create descriptor set layout");
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            _CHECK_(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo
                    .calloc(stack)
                    .sType$Default()
                    .pSetLayouts(pDescriptorSetLayout), null, pPipelineLayout),
                    "Failed to create pipeline layout");
            LongBuffer pPipelines = stack.mallocLong(1);
            _CHECK_(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, VkGraphicsPipelineCreateInfo
                    .calloc(1, stack)
                    .sType$Default()
                    .layout(pPipelineLayout.get(0))
                    .renderPass(renderPass)
                    .pVertexInputState(VkPipelineVertexInputStateCreateInfo
                            .calloc(stack)
                            .sType$Default()
                            .pVertexBindingDescriptions(VkVertexInputBindingDescription
                                    .calloc(1, stack)
                                    .apply(0, d -> d.binding(0).stride(4 * Short.BYTES).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)))
                            .pVertexAttributeDescriptions(VkVertexInputAttributeDescription
                                    .calloc(1, stack)
                                    .apply(0, d -> d.binding(0).location(0).format(VK_FORMAT_R16G16B16A16_UNORM).offset(0))))
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo
                            .calloc(stack)
                            .sType$Default()
                            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo
                            .calloc(stack)
                            .sType$Default()
                            .polygonMode(VK_POLYGON_MODE_FILL)
                            .cullMode(VK_CULL_MODE_BACK_BIT)
                            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                            .lineWidth(1.0f))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo
                            .calloc(stack)
                            .sType$Default()
                            .pAttachments(VkPipelineColorBlendAttachmentState
                                    .calloc(1, stack)
                                    .colorWriteMask(0xF)))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo
                            .calloc(stack)
                            .sType$Default()
                            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pViewportState(VkPipelineViewportStateCreateInfo
                            .calloc(stack)
                            .sType$Default()
                            .viewportCount(1)
                            .scissorCount(1))
                    .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo
                            .calloc(stack)
                            .sType$Default()
                            .depthTestEnable(true)
                            .depthWriteEnable(true)
                            .depthCompareOp(VK_COMPARE_OP_GREATER_OR_EQUAL))
                    .pStages(pStages)
                    .pDynamicState(VkPipelineDynamicStateCreateInfo
                            .calloc(stack)
                            .sType$Default()
                            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))), null, pPipelines),
                    "Failed to create raster pipeline");
            pStages.forEach(stage -> vkDestroyShaderModule(device, stage.module(), null));
            return new Pipeline(pPipelineLayout.get(0), pDescriptorSetLayout.get(0), pPipelines.get(0));
        }
    }

    private static long[] createFramebuffers() {
        if (framebuffers != null) {
            for (long framebuffer : framebuffers)
                vkDestroyFramebuffer(device, framebuffer, null);
        }
        try (MemoryStack stack = stackPush()) {
            LongBuffer pAttachments = stack.mallocLong(2);
            VkFramebufferCreateInfo fci = VkFramebufferCreateInfo
                    .calloc(stack)
                    .sType$Default()
                    .pAttachments(pAttachments)
                    .height(swapchain.height)
                    .width(swapchain.width)
                    .layers(1)
                    .renderPass(renderPass);
            long[] framebuffers = new long[swapchain.images.length];
            LongBuffer pFramebuffer = stack.mallocLong(1);
            for (int i = 0; i < swapchain.images.length; i++) {
                pAttachments.put(0, normalImages[i].imageView).put(1, depthStencilImages[i].imageView);
                _CHECK_(vkCreateFramebuffer(device, fci, null, pFramebuffer),
                        "Failed to create framebuffer");
                framebuffers[i] = pFramebuffer.get(0);
            }
            return framebuffers;
        }
    }

    private static Geometry createGeometry() throws IOException {
        VoxelField voxelField = buildVoxelField();
        ArrayList<Face> faces = buildFaces(voxelField);
        ByteBuffer positionsAndTypes = memAlloc(Short.BYTES * 4 * faces.size() * VERTICES_PER_FACE);
        ByteBuffer indices = memAlloc(Integer.BYTES * faces.size() * INDICES_PER_FACE);
        triangulate(faces, positionsAndTypes.asShortBuffer(), indices.asIntBuffer());

        AllocationAndBuffer positionsBuffer = createBuffer(
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT |
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, positionsAndTypes, 4*Byte.BYTES, null);
        memFree(positionsAndTypes);
        AllocationAndBuffer indicesBuffer = createBuffer(
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT |
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, indices, Integer.BYTES, null);
        memFree(indices);

        return new Geometry(positionsBuffer, indicesBuffer, faces.size());
    }

    private static AllocationAndBuffer createMaterialsBuffer() {
        ByteBuffer bb = memAlloc(materials.length * Integer.BYTES);
        for (int i = 0; i < materials.length; i++)
            bb.putInt(materials[i] == null ? MagicaVoxelLoader.DEFAULT_PALETTE[i] : materials[i].color);
        bb.flip();
        AllocationAndBuffer buf = createBuffer(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, bb, Integer.BYTES, null);
        memFree(bb);
        return buf;
    }

    private static VkDeviceOrHostAddressKHR deviceAddress(MemoryStack stack, long buffer, long alignment) {
        return VkDeviceOrHostAddressKHR
                .malloc(stack)
                .deviceAddress(bufferAddress(buffer, alignment));
    }
    private static VkDeviceOrHostAddressConstKHR deviceAddressConst(MemoryStack stack, long buffer, long alignment) {
        return VkDeviceOrHostAddressConstKHR
                .malloc(stack)
                .deviceAddress(bufferAddress(buffer, alignment));
    }
    private static long bufferAddress(long buffer, long alignment) {
        long address;
        try (MemoryStack stack = stackPush()) {
            address = vkGetBufferDeviceAddress(device, VkBufferDeviceAddressInfo
                    .calloc(stack)
                    .sType$Default()
                    .buffer(buffer));
        }
        // check alignment
        if ((address % alignment) != 0)
            throw new AssertionError("Illegal address alignment");
        return address;
    }

    private static class AccelerationStructure {
        private final long accelerationStructure;
        private final AllocationAndBuffer buffer;
        private AccelerationStructure(long accelerationStructure, AllocationAndBuffer buffer) {
            this.accelerationStructure = accelerationStructure;
            this.buffer = buffer;
        }
        private void free() {
            vkDestroyAccelerationStructureKHR(device, accelerationStructure, null);
            buffer.free();
        }
    }

    private static AccelerationStructure createBottomLevelAccelerationStructure(
            Geometry geometry) {
        try (MemoryStack stack = stackPush()) {
            // Create the build geometry info holding the vertex and index data
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer pInfos = 
                    VkAccelerationStructureBuildGeometryInfoKHR
                        .calloc(1, stack)
                        .sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR | 
                               VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
                        .geometryCount(1)
                        .pGeometries(VkAccelerationStructureGeometryKHR
                                .calloc(1, stack)
                                .sType$Default()
                                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                                .geometry(VkAccelerationStructureGeometryDataKHR
                                        .calloc(stack)
                                        .triangles(VkAccelerationStructureGeometryTrianglesDataKHR
                                                .calloc(stack)
                                                .sType$Default()
                                                .vertexFormat(VK_FORMAT_R16G16B16_UNORM)
                                                .vertexData(deviceAddressConst(stack, geometry.positions.buffer, Short.BYTES))
                                                .vertexStride(4 * Short.BYTES)
                                                .maxVertex(geometry.numFaces * VERTICES_PER_FACE)
                                                .indexType(VK_INDEX_TYPE_UINT32)
                                                .indexData(deviceAddressConst(stack, geometry.indices.buffer, Integer.BYTES))))
                                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR));

            // Query necessary sizes for the acceleration structure buffer and for the scratch buffer
            VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo = VkAccelerationStructureBuildSizesInfoKHR
                    .malloc(stack)
                    .sType$Default()
                    .pNext(NULL);
            vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    pInfos.get(0),
                    stack.ints(geometry.numFaces * 2),
                    buildSizesInfo);

            // Create a buffer that will hold the final BLAS
            AllocationAndBuffer accelerationStructureBuffer = createBuffer(
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, buildSizesInfo.accelerationStructureSize(),
                    null, 256, null);

            // Create a BLAS object (not currently built)
            LongBuffer pAccelerationStructure = stack.mallocLong(1);
            _CHECK_(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR
                        .calloc(stack)
                        .sType$Default()
                        .buffer(accelerationStructureBuffer.buffer)
                        .size(buildSizesInfo.accelerationStructureSize())
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR), null, pAccelerationStructure),
                    "Failed to create bottom-level acceleration structure");

            // Create a scratch buffer for the BLAS build
            AllocationAndBuffer scratchBuffer = createBuffer(
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT |
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, buildSizesInfo.buildScratchSize(), null,
                    deviceAndQueueFamilies.minAccelerationStructureScratchOffsetAlignment, null);

            // fill missing/remaining info into the build geometry info to
            // be able to build the BLAS instance.
            pInfos
                .scratchData(deviceAddress(stack, scratchBuffer.buffer, deviceAndQueueFamilies.minAccelerationStructureScratchOffsetAlignment))
                .dstAccelerationStructure(pAccelerationStructure.get(0));
            VkCommandBuffer cmdBuf = createCommandBuffer(commandPoolTransient, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            // Insert barrier to let BLAS build wait for the geometry data transfer from the staging buffer to the GPU
            vkCmdPipelineBarrier(cmdBuf,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, // <- copying of the geometry data from the staging buffer to the GPU buffer
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, // <- accessing the buffer for acceleration structure build
                    0, // <- no dependency flags
                    VkMemoryBarrier
                        .calloc(1, stack)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT) // <- GPU buffer was written to during the transfer
                        .dstAccessMask(
                                VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | // <- Accesses to the destination acceleration structures, and the scratch buffers
                                VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR |
                                VK_ACCESS_SHADER_READ_BIT), // <- Accesses to input buffers for the build (vertex, index, transform, aabb, or instance data)
                    null, null);

            // Issue build command
            vkCmdBuildAccelerationStructuresKHR(
                    cmdBuf,
                    pInfos,
                    pointersOfElements(stack,
                            VkAccelerationStructureBuildRangeInfoKHR
                                    .calloc(1, stack)
                                    .primitiveCount(geometry.numFaces * 2)));

            // barrier for compressing the BLAS
            vkCmdPipelineBarrier(cmdBuf,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    0,
                    VkMemoryBarrier
                        .calloc(1, stack)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                        .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR),
                    null, null);

            // issue query for compacted size
            vkCmdResetQueryPool(cmdBuf, queryPool, 0, 1);
            vkCmdWriteAccelerationStructuresPropertiesKHR(
                    cmdBuf,
                    pAccelerationStructure,
                    VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR,
                    queryPool,
                    0);

            // submit command buffer and wait for command buffer completion
            long fence = submitCommandBuffer(cmdBuf, true, null);
            waitForFenceAndDestroy(fence);
            vkFreeCommandBuffers(device, commandPoolTransient, cmdBuf);

            // read-back compacted size
            LongBuffer compactedSize = stack.mallocLong(1);
            vkGetQueryPoolResults(device, queryPool, 0, 1, compactedSize, Long.BYTES, 
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);

            // Create a buffer that will hold the compacted BLAS
            AllocationAndBuffer accelerationStructureCompactedBuffer = createBuffer(
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT |
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR,
                    compactedSize.get(0), null, 256, null);

            // create compacted acceleration structure
            LongBuffer pAccelerationStructureCompacted = stack.mallocLong(1);
            vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR
                    .calloc(stack)
                    .sType$Default()
                    .buffer(accelerationStructureCompactedBuffer.buffer)
                    .size(compactedSize.get(0))
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR), null, pAccelerationStructureCompacted);

            // issue copy command
            VkCommandBuffer cmdBuf2 = createCommandBuffer(commandPoolTransient, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkCmdCopyAccelerationStructureKHR(
                    cmdBuf2,
                    VkCopyAccelerationStructureInfoKHR
                        .calloc(stack)
                        .sType$Default()
                        .src(pAccelerationStructure.get(0))
                        .dst(pAccelerationStructureCompacted.get(0))
                        .mode(VK_COPY_ACCELERATION_STRUCTURE_MODE_COMPACT_KHR));

            // barrier to let TLAS build wait for BLAS compressed copy
            vkCmdPipelineBarrier(cmdBuf2,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    0,
                    VkMemoryBarrier
                        .calloc(1, stack)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                        .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR),
                    null, null);

            // Finally submit command buffer and register callback when fence signals to 
            // dispose of resources
            long accelerationStructure = pAccelerationStructure.get(0);
            submitCommandBuffer(cmdBuf2, true, () -> {
                vkDestroyAccelerationStructureKHR(device, accelerationStructure, null);
                accelerationStructureBuffer.free();
                vkFreeCommandBuffers(device, commandPoolTransient, cmdBuf2);
                scratchBuffer.free();
            });

            return new AccelerationStructure(pAccelerationStructureCompacted.get(0), accelerationStructureCompactedBuffer);
        }
    }

    private static void waitForFenceAndDestroy(long fence) {
        _CHECK_(vkWaitForFences(device, fence, true, -1), "Failed to wait for fence");
        vkDestroyFence(device, fence, null);
    }

    private static AccelerationStructure createTopLevelAccelerationStructure(AccelerationStructure blas) {
        try (MemoryStack stack = stackPush()) {
            // Query the BLAS device address to reference in the TLAS instance
            long blasDeviceAddress = vkGetAccelerationStructureDeviceAddressKHR(device, 
                    VkAccelerationStructureDeviceAddressInfoKHR
                        .calloc(stack)
                        .sType$Default()
                        .accelerationStructure(blas.accelerationStructure));

            // Create a single instance for our TLAS
            // This instance data also needs to reside in a GPU buffer, so copy it
            AllocationAndBuffer instanceData = createBuffer(
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    memByteBuffer(VkAccelerationStructureInstanceKHR
                            .calloc(1, stack)
                            .accelerationStructureReference(blasDeviceAddress)
                            .mask(~0) // <- we do not want to mask-away any geometry, so use 0b11111111
                            .flags(VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR |
                                   VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                            .transform(VkTransformMatrixKHR
                                    .calloc(stack)
                                    .matrix(new Matrix4x3f().scale(POSITION_SCALE).getTransposed(stack.mallocFloat(12))))),
                    16, // <- VUID-vkCmdBuildAccelerationStructuresKHR-pInfos-03715
                    null);

            // Create the build geometry info holding the BLAS reference
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer pInfos = 
                    VkAccelerationStructureBuildGeometryInfoKHR
                        .calloc(1, stack)
                        .sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                        .pGeometries(VkAccelerationStructureGeometryKHR
                                .calloc(1, stack)
                                .sType$Default()
                                .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                                .geometry(VkAccelerationStructureGeometryDataKHR
                                        .calloc(stack)
                                        .instances(VkAccelerationStructureGeometryInstancesDataKHR
                                                .calloc(stack)
                                                .sType$Default()
                                                .data(deviceAddressConst(stack, instanceData.buffer, 16)))) // <- VUID-vkCmdBuildAccelerationStructuresKHR-pInfos-03715
                                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR))
                        .geometryCount(1);

            // Query necessary sizes for the acceleration structure buffer and for the scratch buffer
            VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo = VkAccelerationStructureBuildSizesInfoKHR
                    .malloc(stack)
                    .sType$Default()
                    .pNext(NULL);
            vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    pInfos.get(0),
                    stack.ints(1),
                    buildSizesInfo);

            // Create a buffer that will hold the final TLAS
            AllocationAndBuffer accelerationStructureBuffer = createBuffer(
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, buildSizesInfo.accelerationStructureSize(), null,
                    256,
                    null);

            // Create a TLAS object (not currently built)
            LongBuffer pAccelerationStructure = stack.mallocLong(1);
            _CHECK_(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR
                        .calloc(stack)
                        .sType$Default()
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                        .size(buildSizesInfo.accelerationStructureSize())
                        .buffer(accelerationStructureBuffer.buffer), null, pAccelerationStructure),
                    "Failed to create top-level acceleration structure");

            // Create a scratch buffer for the TLAS build
            AllocationAndBuffer scratchBuffer = createBuffer(
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT |
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, buildSizesInfo.buildScratchSize(), null,
                    deviceAndQueueFamilies.minAccelerationStructureScratchOffsetAlignment,
                    null);

            // fill missing/remaining info into the build geometry info to
            // be able to build the TLAS instance.
            pInfos
                .scratchData(deviceAddress(stack, scratchBuffer.buffer, deviceAndQueueFamilies.minAccelerationStructureScratchOffsetAlignment))
                .dstAccelerationStructure(pAccelerationStructure.get(0));
            VkCommandBuffer cmdBuf = createCommandBuffer(commandPoolTransient, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            // insert barrier to let TLAS build wait for the instance data transfer from the staging buffer to the GPU
            vkCmdPipelineBarrier(cmdBuf,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, // <- copying of the instance data from the staging buffer to the GPU buffer
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, // <- accessing the buffer for acceleration structure build
                    0, // <- no dependency flags
                    VkMemoryBarrier
                        .calloc(1, stack)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT) // <- GPU buffer was written to during the transfer
                        .dstAccessMask(
                                VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | // <- Accesses to the destination acceleration structures, and the scratch buffers
                                VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR |
                                VK_ACCESS_SHADER_READ_BIT), // <- Accesses to input buffers for the build (vertex, index, transform, aabb, or instance data)
                    null, null);

            // issue build command
            vkCmdBuildAccelerationStructuresKHR(
                    cmdBuf,
                    pInfos,
                    pointersOfElements(stack,
                            VkAccelerationStructureBuildRangeInfoKHR
                                    .calloc(1, stack)
                                    .primitiveCount(1))); // <- number of BLASes!

            // insert barrier to let tracing wait for the TLAS build
            vkCmdPipelineBarrier(cmdBuf,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    0, // <- no dependency flags
                    VkMemoryBarrier
                        .calloc(1, stack)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                        .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR),
                    null,
                    null);

            // finally submit command buffer and register callback when fence signals to 
            // dispose of resources
            submitCommandBuffer(cmdBuf, true, () -> {
                vkFreeCommandBuffers(device, commandPoolTransient, cmdBuf);
                scratchBuffer.free();
                // the TLAS is completely self-contained after build, so
                // we can free the instance data.
                instanceData.free();
            });

            return new AccelerationStructure(pAccelerationStructure.get(0), accelerationStructureBuffer);
        }
    }

    private static RayTracingPipeline createRayTracingPipeline() throws IOException {
        int numDescriptors = 6;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSetLayout = stack.mallocLong(1);
            // create the descriptor set layout
            // we have one acceleration structure, one storage image and one uniform buffer
            _CHECK_(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo
                        .calloc(stack)
                        .sType$Default()
                        .pBindings(VkDescriptorSetLayoutBinding
                                .calloc(numDescriptors, stack)
                                .apply(dslb -> dslb
                                        .binding(0)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR))
                                .apply(dslb -> dslb
                                        .binding(1)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR))
                                .apply(dslb -> dslb
                                        .binding(2)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR))
                                .apply(dslb -> dslb
                                        .binding(3)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR))
                                .apply(dslb -> dslb
                                        .binding(4)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR))
                                .apply(dslb -> dslb
                                        .binding(5)
                                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                        .descriptorCount(1)
                                        .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR))
                                .flip()),
                    null, pSetLayout),
                    "Failed to create descriptor set layout");
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            _CHECK_(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo
                        .calloc(stack)
                        .sType$Default()
                        .pSetLayouts(pSetLayout), null, pPipelineLayout),
                    "Failed to create pipeline layout");
            VkPipelineShaderStageCreateInfo.Buffer pStages = VkPipelineShaderStageCreateInfo
                    .calloc(2, stack);

            // load shaders
            String pkg = HybridMagicaVoxel.class.getName().toLowerCase().replace('.', '/') + "/";
            loadShader(pStages
                    .get(0)
                    .sType$Default(), 
                    null, stack, device, pkg + "raygen.glsl", VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            loadShader(pStages
                    .get(1)
                    .sType$Default(),
                    null, stack, device, pkg + "raymiss.glsl", VK_SHADER_STAGE_MISS_BIT_KHR);

            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR
                    .calloc(2, stack);
            groups.forEach(g -> g
                    .sType$Default()
                    .generalShader(VK_SHADER_UNUSED_KHR)
                    .closestHitShader(VK_SHADER_UNUSED_KHR)
                    .anyHitShader(VK_SHADER_UNUSED_KHR)
                    .intersectionShader(VK_SHADER_UNUSED_KHR));
            groups.apply(0, g ->
                        g.type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                         .generalShader(0))
                  .apply(1, g ->
                        g.type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                         .generalShader(1));
            LongBuffer pPipelines = stack.mallocLong(1);
            _CHECK_(vkCreateRayTracingPipelinesKHR(device, VK_NULL_HANDLE, VK_NULL_HANDLE, VkRayTracingPipelineCreateInfoKHR
                        .calloc(1, stack)
                        .sType$Default()
                        .pStages(pStages)
                        .maxPipelineRayRecursionDepth(1)
                        .pGroups(groups)
                        .layout(pPipelineLayout.get(0)), null, pPipelines),
                    "Failed to create ray tracing pipeline");
            pStages.forEach(stage -> vkDestroyShaderModule(device, stage.module(), null));
            return new RayTracingPipeline(pPipelineLayout.get(0), pSetLayout.get(0), pPipelines.get(0));
        }
    }

    private static int alignUp(int size, int alignment) {
        return (size + alignment - 1) & -alignment;
    }

    private static AllocationAndBuffer createRayTracingShaderBindingTable() {
        if (sbt != null)
            sbt.free();
        try (MemoryStack stack = stackPush()) {
            int groupCount = 3;
            int groupHandleSize = 32 /* shaderGroupHandleSize is exactly 32 bytes, by definition */;
            // group handles must be properly aligned when writing them to the final GPU buffer, so compute
            // the aligned group handle size
            int groupSizeAligned = alignUp(groupHandleSize, deviceAndQueueFamilies.shaderGroupBaseAlignment);

            // compute the final size of the GPU buffer
            int sbtSize = groupCount * groupSizeAligned;

            // retrieve the two shader group handles
            ByteBuffer handles = stack.malloc(groupCount * groupHandleSize);
            _CHECK_(vkGetRayTracingShaderGroupHandlesKHR(device, rayTracingPipeline.pipeline, 0, 2, handles),
                    "Failed to obtain ray tracing group handles");

            // prepare memory with properly aligned group handles
            ByteBuffer handlesForGpu = stack.malloc(sbtSize);
            memCopy(memAddress(handles), memAddress(handlesForGpu), groupHandleSize);
            memCopy(memAddress(handles) + groupHandleSize, memAddress(handlesForGpu) + groupSizeAligned, groupHandleSize);

            // and upload to a new GPU buffer
            return createBuffer(VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR |
                                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, handlesForGpu,
                                deviceAndQueueFamilies.shaderGroupBaseAlignment, (cmdBuf) -> {
                                    // insert memory barrier to let ray tracing shader wait for SBT transfer
                                    try (MemoryStack s = stackPush()) {
                                        vkCmdPipelineBarrier(cmdBuf,
                                                VK_PIPELINE_STAGE_TRANSFER_BIT,
                                                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                                                0,
                                                VkMemoryBarrier
                                                    .calloc(1, s)
                                                    .sType$Default()
                                                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                                                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT),
                                                null,
                                                null);
                                    }
                                });
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
        int numSets = swapchain.imageViews.length;
        int numDescriptors = 6;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pDescriptorPool = stack.mallocLong(1);
            _CHECK_(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo
                        .calloc(stack)
                        .sType$Default()
                        .pPoolSizes(VkDescriptorPoolSize
                                .calloc(numDescriptors, stack)
                                .apply(0, dps -> dps
                                        .type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                                        .descriptorCount(numSets))
                                .apply(1, dps -> dps
                                        .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                        .descriptorCount(numSets))
                                .apply(2, dps -> dps
                                        .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                        .descriptorCount(numSets))
                                .apply(3, dps -> dps
                                        .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                        .descriptorCount(numSets))
                                .apply(4, dps -> dps
                                        .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                        .descriptorCount(numSets))
                                .apply(5, dps -> dps
                                        .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                        .descriptorCount(numSets)))
                        .maxSets(numSets), null, pDescriptorPool),
                    "Failed to create descriptor pool");
            LongBuffer pDescriptorSets = stack.mallocLong(numSets);
            _CHECK_(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo
                    .calloc(stack)
                    .sType$Default()
                    .descriptorPool(pDescriptorPool.get(0))
                    .pSetLayouts(repeat(stack, rayTracingPipeline.descriptorSetLayout, numSets)), pDescriptorSets),
                    "Failed to allocate descriptor set");
            long[] sets = new long[pDescriptorSets.remaining()];
            pDescriptorSets.get(sets, 0, sets.length);
            VkWriteDescriptorSet.Buffer writeDescriptorSet = VkWriteDescriptorSet
                    .calloc(numDescriptors * numSets, stack);
            for (int i = 0; i < numSets; i++) {
                final int idx = i;
                writeDescriptorSet
                        .apply(wds -> wds
                                .sType$Default()
                                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                                .dstBinding(0)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pNext(VkWriteDescriptorSetAccelerationStructureKHR
                                        .calloc(stack)
                                        .sType$Default()
                                        .pAccelerationStructures(stack.longs(tlas.accelerationStructure))))
                        .apply(wds -> wds
                                .sType$Default()
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .dstBinding(1)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pImageInfo(VkDescriptorImageInfo
                                        .calloc(1, stack)
                                        .imageView(swapchain.imageViews[idx])
                                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL)))
                        .apply(wds -> wds
                                .sType$Default()
                                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                .dstBinding(2)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo
                                        .calloc(1, stack)
                                        .buffer(ubo.buffer)
                                        .offset(alignUp(uboStructSize, deviceAndQueueFamilies.minUniformBufferOffsetAlignment) * idx)
                                        .range(VK_WHOLE_SIZE)))
                        .apply(wds -> wds
                                .sType$Default()
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                                .dstBinding(3)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo
                                        .calloc(1, stack)
                                        .buffer(materialsBuffer.buffer)
                                        .range(VK_WHOLE_SIZE)))
                        .apply(wds -> wds
                                .sType$Default()
                                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                .dstBinding(4)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pImageInfo(VkDescriptorImageInfo
                                        .calloc(1, stack)
                                        .imageView(depthStencilImages[idx].imageView)
                                        .sampler(sampler)
                                        .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL)))
                        .apply(wds -> wds
                                .sType$Default()
                                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                .dstBinding(5)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pImageInfo(VkDescriptorImageInfo
                                        .calloc(1, stack)
                                        .imageView(normalImages[idx].imageView)
                                        .sampler(sampler)
                                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)));
            }
            vkUpdateDescriptorSets(device, writeDescriptorSet.flip(), null);
            return new DescriptorSets(pDescriptorPool.get(0), sets);
        }
    }

    private static DescriptorSets createRasterDescriptorSets() {
        if (rasterDescriptorSets != null)
            rasterDescriptorSets.free();
        int numSets = swapchain.images.length;
        int numDescriptors = 1;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pDescriptorPool = stack.mallocLong(1);
            _CHECK_(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo
                        .calloc(stack)
                        .sType$Default()
                        .pPoolSizes(VkDescriptorPoolSize
                                .calloc(numDescriptors, stack)
                                .apply(0, dps -> dps.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                        .descriptorCount(numSets)))
                        .maxSets(numSets), null, pDescriptorPool),
                    "Failed to create descriptor pool");
            LongBuffer pDescriptorSets = stack.mallocLong(numSets);
            _CHECK_(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo
                    .calloc(stack)
                    .sType$Default()
                    .descriptorPool(pDescriptorPool.get(0))
                    .pSetLayouts(repeat(stack, rasterPipeline.descriptorSetLayout, numSets)), pDescriptorSets),
                    "Failed to allocate descriptor set");
            long[] sets = new long[pDescriptorSets.remaining()];
            pDescriptorSets.get(sets, 0, sets.length);
            VkWriteDescriptorSet.Buffer writeDescriptorSet = VkWriteDescriptorSet
                    .calloc(numDescriptors * numSets, stack);
            for (int i = 0; i < numSets; i++) {
                final int idx = i;
                writeDescriptorSet
                        .apply(wds -> wds
                                .sType$Default()
                                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                .dstBinding(0)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo
                                        .calloc(1, stack)
                                        .buffer(ubo.buffer)
                                        .offset(alignUp(uboStructSize, deviceAndQueueFamilies.minUniformBufferOffsetAlignment) * idx)
                                        .range(VK_WHOLE_SIZE)));
            }
            vkUpdateDescriptorSets(device, writeDescriptorSet.flip(), null);
            return new DescriptorSets(pDescriptorPool.get(0), sets);
        }
    }

    private static long createQueryPool() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pQueryPool = stack.mallocLong(1);
            _CHECK_(vkCreateQueryPool(device,
                    VkQueryPoolCreateInfo
                        .calloc(stack)
                        .sType$Default()
                        .queryCount(1)
                        .queryType(VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR),
                    null, pQueryPool), "Failed to create query pool");
            return pQueryPool.get(0);
        }
    }

    private static VkCommandBuffer[] createRayTracingCommandBuffers() {
        if (rayTracingCommandBuffers != null) {
            try (MemoryStack stack = stackPush()) {
                vkFreeCommandBuffers(device, commandPool, stack.pointers(rayTracingCommandBuffers));
            }
        }
        int count = swapchain.imageViews.length;
        VkCommandBuffer[] buffers = new VkCommandBuffer[count];
        for (int i = 0; i < count; i++) {
            VkCommandBuffer cmdBuf = createCommandBuffer(commandPool, 0);
            try (MemoryStack stack = stackPush()) {
                // insert a barrier to transition the framebuffer image from undefined to general,
                // and do it somewhere between the top of the pipe and the start of the ray tracing.
                vkCmdPipelineBarrier(cmdBuf,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        0,
                        null,
                        null,
                        VkImageMemoryBarrier
                            .calloc(1, stack)
                            .sType$Default()
                            .srcAccessMask(0)
                            .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                            .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                            .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                            .image(swapchain.images[i])
                            .subresourceRange(r -> r
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .layerCount(1)
                                .levelCount(1)));

                // bind ray tracing pipeline
                vkCmdBindPipeline(cmdBuf, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, rayTracingPipeline.pipeline);
                // and descriptor set
                vkCmdBindDescriptorSets(cmdBuf, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, rayTracingPipeline.pipelineLayout, 0,
                        stack.longs(rayTracingDescriptorSets.sets[i]), null);

                // calculate shader group offsets and sizes in the SBT
                int groupSize = alignUp(32/* shaderGroupHandleSize is exactly 32 bytes, by definition */, deviceAndQueueFamilies.shaderGroupBaseAlignment);
                long sbtAddress = bufferAddress(sbt.buffer, deviceAndQueueFamilies.shaderGroupBaseAlignment);

                // and issue a tracing command
                vkCmdTraceRaysKHR(cmdBuf,
                        VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(sbtAddress).stride(groupSize).size(groupSize),
                        VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(sbtAddress + groupSize).stride(groupSize).size(groupSize),
                        VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(sbtAddress + 2L * groupSize).stride(groupSize).size(groupSize),
                        VkStridedDeviceAddressRegionKHR.calloc(stack), swapchain.width, swapchain.height, 1);

                // insert barrier to transition the image from general to present source,
                // and wait for the tracing to complete.
                vkCmdPipelineBarrier(
                        cmdBuf,
                        VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        0,
                        null,
                        null,
                        VkImageMemoryBarrier
                            .calloc(1, stack)
                            .sType$Default()
                            .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                            .dstAccessMask(0)
                            .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                            .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                            .image(swapchain.images[i])
                            .subresourceRange(r -> r
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .layerCount(1)
                                .levelCount(1)));
            }
            _CHECK_(vkEndCommandBuffer(cmdBuf), "Failed to end command buffer");
            buffers[i] = cmdBuf;
        }
        return buffers;
    }

    private static VkCommandBuffer[] createRasterCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            VkRenderPassBeginInfo renderPassBeginInfo = VkRenderPassBeginInfo
                    .calloc(stack)
                    .sType$Default()
                    .renderPass(renderPass)
                    .pClearValues(VkClearValue
                            .calloc(2, stack)) // <- all zeroes, we use reverse depth
                    .renderArea(a -> a.extent().set(swapchain.width, swapchain.height));
            VkViewport.Buffer viewport = VkViewport
                    .calloc(1, stack)
                    .width(swapchain.width)
                    .height(swapchain.height)
                    .minDepth(0.0f)
                    .maxDepth(1.0f);
            VkRect2D.Buffer scissor = VkRect2D
                    .calloc(1, stack)
                    .extent(e -> e.set(swapchain.width, swapchain.height));
            int count = swapchain.imageViews.length;
            VkCommandBuffer[] cmdBuffers = new VkCommandBuffer[count];
            for (int i = 0; i < swapchain.images.length; i++) {
                VkCommandBuffer cmdBuffer = createCommandBuffer(commandPool, 0);
                cmdBuffers[i] = cmdBuffer;
                renderPassBeginInfo.framebuffer(framebuffers[i]);
                vkCmdBeginRenderPass(cmdBuffer, renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE);
                vkCmdSetViewport(cmdBuffer, 0, viewport);
                vkCmdSetScissor(cmdBuffer, 0, scissor);
                vkCmdBindDescriptorSets(cmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, rasterPipeline.pipelineLayout, 0,
                        stack.longs(rasterDescriptorSets.sets[i]), null);
                vkCmdBindPipeline(cmdBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, rasterPipeline.pipeline);
                vkCmdBindVertexBuffers(cmdBuffer, 0, new long[] {geometry.positions.buffer}, new long[]{0});
                vkCmdBindIndexBuffer(cmdBuffer, geometry.indices.buffer, 0, VK_INDEX_TYPE_UINT32);
                vkCmdDrawIndexed(cmdBuffer, geometry.numFaces * 6, 1, 0, 0, 0);
                vkCmdEndRenderPass(cmdBuffer);
                _CHECK_(vkEndCommandBuffer(cmdBuffer), "Failed to end command buffer");
            }
            return cmdBuffers;
        }
    }

    private static void handleKeyboardInput(float dt) {
        float factor = 10.0f;
        if (keydown[GLFW_KEY_LEFT_SHIFT])
            factor = 40.0f;
        cameraRotation.rotationXYZ(cameraRotationX, cameraRotationY, 0.0f);
        if (keydown[GLFW_KEY_W])
            cameraPosition.add(cameraRotation.positiveZ(tmpv3).negate().mul(factor * dt));
        if (keydown[GLFW_KEY_S])
            cameraPosition.add(cameraRotation.positiveZ(tmpv3).mul(factor * dt));
        if (keydown[GLFW_KEY_A])
            cameraPosition.add(cameraRotation.positiveX(tmpv3).negate().mul(factor * dt));
        if (keydown[GLFW_KEY_D])
            cameraPosition.add(cameraRotation.positiveX(tmpv3).mul(factor * dt));
        if (keydown[GLFW_KEY_LEFT_CONTROL])
            cameraPosition.add(cameraRotation.positiveY(tmpv3).negate().mul(factor * dt));
        if (keydown[GLFW_KEY_SPACE])
            cameraPosition.add(cameraRotation.positiveY(tmpv3).mul(factor * dt));
    }

    private static void update(float dt) {
        handleKeyboardInput(dt);
        viewMatrix.rotation(cameraRotation).translate(cameraPosition.negate(tmpv3));
        viewMatrix.invert(invViewMatrix);
        projMatrix.scaling(1, -1, 1)
                  .perspective(toRadians(45.0f),
                               (float) windowAndCallbacks.width / windowAndCallbacks.height,
                               1000.0f, // <- near plane (we use reverse depth)
                               0.1f,    // <- far plane (we use reverse depth)
                               true);
        projMatrix.invert(invProjMatrix);
        projMatrix.mul(viewMatrix, mvpMatrix).scale(POSITION_SCALE);
    }

    private static void updateUniformBufferObject(int idx) {
        int off = alignUp(uboStructSize, deviceAndQueueFamilies.minUniformBufferOffsetAlignment) * idx;
        mvpMatrix.get(off, ubo.mapped);
        invProjMatrix.get(off + Float.BYTES * 16, ubo.mapped);
        invViewMatrix.get4x4(off + Float.BYTES * 16 * 2, ubo.mapped);
        ubo.flushMapped(off, Float.BYTES * 16 * 3);
    }

    private static int idx(int x, int y, int z, int width, int depth) {
        return (x + 1) + (width + 2) * ((z + 1) + (y + 1) * (depth + 2));
    }

    private static class VoxelField {
        int w, d;
        Vector3i min;
        Vector3i max;
        byte[] field;
    }

    private static VoxelField buildVoxelField() throws IOException {
        Vector3i dims = new Vector3i();
        Vector3i min = new Vector3i(Integer.MAX_VALUE);
        Vector3i max = new Vector3i(Integer.MIN_VALUE);
        byte[] field = new byte[(256 + 2) * (256 + 2) * (256 + 2)];
        try (InputStream is = getSystemResourceAsStream("org/lwjgl/demo/models/mikelovesrobots_mmmm/scene_house6.vox");
             BufferedInputStream bis = new BufferedInputStream(is)) {
            new MagicaVoxelLoader().read(bis, new MagicaVoxelLoader.Callback() {
                public void voxel(int x, int y, int z, byte c) {
                    y = dims.z - y - 1;
                    field[idx(x, z, y, dims.x, dims.z)] = c;
                    min.set(min(min.x, x), min(min.y, z), min(min.z, y));
                    max.set(max(max.x, x), max(max.y, z), max(max.z, y));
                }
                public void size(int x, int y, int z) {
                    dims.x = x;
                    dims.y = z;
                    dims.z = y;
                }
                public void paletteMaterial(int i, Material mat) {
                    if (i > 255) {
                        System.out.println("Material index " + i + " is out of range, ignoring");
                        return;
                    }
                    materials[i] = mat;
                }
            });
        }
        VoxelField res = new VoxelField();
        res.w = dims.x;
        res.d = dims.z;
        res.min = min;
        res.max = max;
        res.field = field;
        return res;
    }

    private static ArrayList<Face> buildFaces(VoxelField vf) {
        GreedyMeshingNoAo gm = new GreedyMeshingNoAo(vf.min.x, vf.min.y, vf.min.z, vf.max.x, vf.max.y, vf.max.z, vf.w, vf.d);
        ArrayList<Face> faces = new ArrayList<>();
        gm.mesh(vf.field, new GreedyMeshingNoAo.FaceConsumer() {
            public void consume(int u0, int v0, int u1, int v1, int p, int s, int v) {
                faces.add(new Face(u0, v0, u1, v1, p, s, v));
            }
        });
        return faces;
    }

    public static void triangulate(List<Face> faces, ShortBuffer positionsAndTypes, IntBuffer indices) {
        for (int i = 0; i < faces.size(); i++) {
            Face f = faces.get(i);
            switch (f.s >>> 1) {
            case 0:
                generatePositionsTypesAndSideX(f, positionsAndTypes);
                break;
            case 1:
                generatePositionsTypesAndSideY(f, positionsAndTypes);
                break;
            case 2:
                generatePositionsTypesAndSideZ(f, positionsAndTypes);
                break;
            }
            generateIndices(f, i, indices);
        }
    }

    private static boolean isPositiveSide(int side) {
        return (side & 1) != 0;
    }

    private static void generateIndices(Face f, int i, IntBuffer indices) {
        if (isPositiveSide(f.s))
            generateIndicesPositive(i, indices);
        else
            generateIndicesNegative(i, indices);
    }

    private static void generateIndicesNegative(int i, IntBuffer indices) {
        indices.put((i << 2) + 3).put((i << 2) + 1).put((i << 2) + 2)
               .put((i << 2) + 1).put(i << 2).put((i << 2) + 2);
    }
    private static void generateIndicesPositive(int i, IntBuffer indices) {
        indices.put((i << 2) + 3).put((i << 2) + 2).put((i << 2) + 1)
               .put((i << 2) + 2).put(i << 2).put((i << 2) + 1);
    }

    private static void generatePositionsTypesAndSideZ(Face f,ShortBuffer positions) {
        positions.put(u16(f.u0)).put(u16(f.v0)).put(u16(f.p)).put((short) (f.v & 0xFF | f.s << 8));
        positions.put(u16(f.u1)).put(u16(f.v0)).put(u16(f.p)).put((short) (f.v & 0xFF | f.s << 8));
        positions.put(u16(f.u0)).put(u16(f.v1)).put(u16(f.p)).put((short) (f.v & 0xFF | f.s << 8));
        positions.put(u16(f.u1)).put(u16(f.v1)).put(u16(f.p)).put((short) (f.v & 0xFF | f.s << 8));
    }
    private static void generatePositionsTypesAndSideY(Face f, ShortBuffer positions) {
        positions.put(u16(f.v0)).put(u16(f.p)).put(u16(f.u0)).put((short) (f.v & 0xFF | f.s << 8));
        positions.put(u16(f.v0)).put(u16(f.p)).put(u16(f.u1)).put((short) (f.v & 0xFF | f.s << 8));
        positions.put(u16(f.v1)).put(u16(f.p)).put(u16(f.u0)).put((short) (f.v & 0xFF | f.s << 8));
        positions.put(u16(f.v1)).put(u16(f.p)).put(u16(f.u1)).put((short) (f.v & 0xFF | f.s << 8));
    }
    private static void generatePositionsTypesAndSideX(Face f, ShortBuffer positions) {
        positions.put(u16(f.p)).put(u16(f.u0)).put(u16(f.v0)).put((short) (f.v & 0xFF | f.s << 8));
        positions.put(u16(f.p)).put(u16(f.u1)).put(u16(f.v0)).put((short) (f.v & 0xFF | f.s << 8));
        positions.put(u16(f.p)).put(u16(f.u0)).put(u16(f.v1)).put((short) (f.v & 0xFF | f.s << 8));
        positions.put(u16(f.p)).put(u16(f.u1)).put(u16(f.v1)).put((short) (f.v & 0xFF | f.s << 8));
    }

    private static short u16(short v) {
        return (short) (v << Short.SIZE - BITS_FOR_POSITIONS);
    }

    private static void init() throws IOException {
        PointerBuffer requiredExtensions = initGlfwAndReturnRequiredExtensions();
        instance = createInstance(requiredExtensions);
        windowAndCallbacks = createWindow();
        surface = createSurface();
        debugCallbackHandle = setupDebugging();
        deviceAndQueueFamilies = selectPhysicalDevice();
        queueFamily = deviceAndQueueFamilies.queuesFamilies.findSingleSuitableQueue();
        device = createDevice(
                asList(VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                       VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
                       VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
                       VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME));
        vmaAllocator = createVmaAllocator();
        queue = retrieveQueue();
        swapchain = createSwapchain();
        depthFormat = determineDepthFormat();
        commandPool = createCommandPool(0);
        commandPoolTransient = createCommandPool(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
        depthStencilImages = createDepthStencilImages();
        normalImages = createNormalImages();
        sampler = createSampler();
        queryPool = createQueryPool();
        geometry = createGeometry();
        renderPass = createRasterRenderPass();
        framebuffers = createFramebuffers();
        materialsBuffer = createMaterialsBuffer();
        blas = createBottomLevelAccelerationStructure(geometry);
        tlas = createTopLevelAccelerationStructure(blas);
        ubo = createUniformBufferObject(uboStructSize);
        rayTracingPipeline = createRayTracingPipeline();
        sbt = createRayTracingShaderBindingTable();
        rayTracingDescriptorSets = createRayTracingDescriptorSets();
        rayTracingCommandBuffers = createRayTracingCommandBuffers();
        rasterPipeline = createRasterPipeline();
        rasterDescriptorSets = createRasterDescriptorSets();
        rasterCommandBuffers = createRasterCommandBuffers();
        createSyncObjects();
    }

    private static void runOnRenderThread() {
        long lastTime = System.nanoTime();
        try (MemoryStack stack = stackPush()) {
            IntBuffer pImageIndex = stack.mallocInt(1);
            int idx = 0;
            boolean needRecreate = false;
            while (!glfwWindowShouldClose(windowAndCallbacks.window)) {
                long thisTime = System.nanoTime();
                float dt = (thisTime - lastTime) / 1E9f;
                lastTime = thisTime;
                updateFramebufferSize();
                if (!isWindowRenderable())
                    continue;
                if (windowSizeChanged())
                    needRecreate = true;
                if (needRecreate) {
                    _CHECK_(vkDeviceWaitIdle(device), "Failed to wait for device idle");
                    recreateSwapchainAndDependentResources();
                    idx = 0;
                }
                _CHECK_(vkWaitForFences(device, renderFences[idx], true, Long.MAX_VALUE), "Failed to wait for fence");
                _CHECK_(vkResetFences(device, renderFences[idx]), "Failed to reset fence");
                update(dt);
                updateUniformBufferObject(idx);
                if (!acquireSwapchainImage(pImageIndex, idx)) {
                    needRecreate = true;
                    continue;
                }
                needRecreate = !submitAndPresent(pImageIndex.get(0), idx);
                processFinishedFences();
                idx = (idx + 1) % swapchain.imageViews.length;
            }
        }
    }

    private static void destroy() {
        _CHECK_(vkDeviceWaitIdle(device), "Failed to wait for device idle");
        rasterDescriptorSets.free();
        rasterPipeline.free();
        ubo.free();
        rayTracingDescriptorSets.free();
        sbt.free();
        rayTracingPipeline.free();
        tlas.free();
        blas.free();
        materialsBuffer.free();
        geometry.free();
        for (int i = 0; i < swapchain.imageViews.length; i++) {
            vkDestroySemaphore(device, imageAcquireSemaphores[i], null);
            vkDestroySemaphore(device, rasterCompleteSemaphores[i], null);
            vkDestroySemaphore(device, rayTraceCompleteSemaphores[i], null);
            vkDestroyFence(device, renderFences[i], null);
        }
        for (long framebuffer : framebuffers)
            vkDestroyFramebuffer(device, framebuffer, null);
        vkDestroyRenderPass(device, renderPass, null);
        vkDestroyQueryPool(device, queryPool, null);
        vkDestroySampler(device, sampler, null);
        for (AllocationAndImage depthStencilImage : depthStencilImages)
            depthStencilImage.free();
        for (AllocationAndImage normalImage : normalImages)
            normalImage.free();
        vkDestroyCommandPool(device, commandPoolTransient, null);
        vkDestroyCommandPool(device, commandPool, null);
        swapchain.free();
        vmaDestroyAllocator(vmaAllocator);
        vkDestroyDevice(device, null);
        if (DEBUG) {
            debugCallbackHandle.free();
        }
        vkDestroySurfaceKHR(instance, surface, null);
        windowAndCallbacks.free();
        vkDestroyInstance(instance, null);
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        init();
        Thread updateAndRenderThread = new Thread(HybridMagicaVoxel::runOnRenderThread);
        updateAndRenderThread.start();
        runWndProcLoop();
        updateAndRenderThread.join();
        destroy();
    }

}
