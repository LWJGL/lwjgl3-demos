/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.vulkan.raytracing;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;
import static org.joml.Math.*;
import static org.lwjgl.demo.vulkan.VKUtil.*;
import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTDescriptorIndexing.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.*;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRShaderFloatControls.VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSpirv14.VK_KHR_SPIRV_1_4_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK11.*;

import java.io.IOException;
import java.nio.*;
import java.util.*;
import java.util.function.Consumer;

import org.joml.*;
import org.lwjgl.PointerBuffer;
import org.lwjgl.demo.util.DynamicByteBuffer;
import org.lwjgl.system.*;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

/**
 * VK_KHR_ray_tracing_pipeline and VK_KHR_acceleration_structure demo that draws a triangle.
 *
 * @author Kai Burjack
 */
public class SimpleTriangle {

    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("debug", "false"));
    static {
        if (DEBUG) {
            /* When we are in debug mode, enable all LWJGL debug flags */
            System.setProperty("org.lwjgl.util.Debug", "true");
            System.setProperty("org.lwjgl.util.NoChecks", "false");
            System.setProperty("org.lwjgl.util.DebugLoader", "true");
            System.setProperty("org.lwjgl.util.DebugAllocator", "true");
            System.setProperty("org.lwjgl.util.DebugStack", "true");
        } else {
            System.setProperty("org.lwjgl.util.NoChecks", "true");
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
    private static long commandPool, commandPoolTransient;
    private static VkCommandBuffer[] commandBuffers;
    private static long[] imageAcquireSemaphores;
    private static long[] renderCompleteSemaphores;
    private static long[] renderFences;
    private static final Map<Long, Runnable> waitingFenceActions = new HashMap<>();
    private static AccelerationStructure blas, tlas;
    private static RayTracingPipeline rayTracingPipeline;
    private static AllocationAndBuffer[] rayTracingUbos;
    private static AllocationAndBuffer sbt;
    private static DescriptorSets rayTracingDescriptorSets;
    private static final Matrix4f projMatrix = new Matrix4f();
    private static final Matrix4x3f viewMatrix = new Matrix4x3f();
    private static final Matrix4f invProjMatrix = new Matrix4f();
    private static final Matrix4x3f invViewMatrix = new Matrix4x3f();
    private static final Vector3f tmpv3 = new Vector3f();

    private static void onKey(long window, int key, int scancode, int action, int mods) {
        if (key == GLFW_KEY_ESCAPE)
            glfwSetWindowShouldClose(window, true);
    }

    private static void registerWindowCallbacks(long window) {
        glfwSetKeyCallback(window, SimpleTriangle::onKey);
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
        private final int shaderGroupHandleSize;
        private final int shaderGroupBaseAlignment;
        private final int minAccelerationStructureScratchOffsetAlignment;
        private DeviceAndQueueFamilies(VkPhysicalDevice physicalDevice, QueueFamilies queuesFamilies,
                int shaderGroupHandleSize,
                int shaderGroupBaseAlignment,
                int minAccelerationStructureScratchOffsetAlignment) {
            this.physicalDevice = physicalDevice;
            this.queuesFamilies = queuesFamilies;
            this.shaderGroupHandleSize = shaderGroupHandleSize;
            this.shaderGroupBaseAlignment = shaderGroupBaseAlignment;
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
            VkExtensionProperties.Buffer pProperties = VkExtensionProperties.mallocStack(propertyCount, stack);
            _CHECK_(vkEnumerateInstanceExtensionProperties((ByteBuffer) null, pPropertyCount, pProperties),
                    "Could not enumerate instance extensions");
            List<String> res = new ArrayList<>(propertyCount);
            for (int i = 0; i < propertyCount; i++) {
                res.add(pProperties.get(i).extensionNameString());
            }
            return res;
        }
    }

    private static VkInstance createInstance(PointerBuffer requiredExtensions) {
        List<String> supportedInstanceExtensions = enumerateSupportedInstanceExtensions();
        try (MemoryStack stack = stackPush()) {
            PointerBuffer ppEnabledExtensionNames;
            if (DEBUG) {
                if (!supportedInstanceExtensions.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
                    throw new AssertionError(VK_EXT_DEBUG_UTILS_EXTENSION_NAME + " is not supported on the instance");
                }
                ppEnabledExtensionNames = pointers(stack, requiredExtensions, stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            } else {
                ppEnabledExtensionNames = pointers(stack, requiredExtensions);
            }
            PointerBuffer enabledLayers = null;
            if (DEBUG) {
                enabledLayers = stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"));
            }
            VkInstanceCreateInfo pCreateInfo = VkInstanceCreateInfo
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(VkApplicationInfo
                            .callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                            .apiVersion(VK_API_VERSION_1_1))
                    .ppEnabledLayerNames(enabledLayers)
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
        long window = glfwCreateWindow(1200, 800, "Hello, ray traced triangle!", NULL, NULL);
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
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
                        .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT |
                                         VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT |
                                         VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
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
            VkQueueFamilyProperties.Buffer familyProperties = VkQueueFamilyProperties.mallocStack(numQueueFamilies, stack);
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
                        .mallocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR)
                        .pNext(NULL);
                VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracingPipelineFeatures = VkPhysicalDeviceRayTracingPipelineFeaturesKHR
                        .mallocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR)
                        .pNext(accelerationStructureFeatures.address());
                VkPhysicalDeviceBufferDeviceAddressFeaturesKHR bufferDeviceAddressFeatures = VkPhysicalDeviceBufferDeviceAddressFeaturesKHR
                        .mallocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES_KHR)
                        .pNext(rayTracingPipelineFeatures.address());
                VkPhysicalDeviceFeatures2 physicalDeviceFeatures2 = VkPhysicalDeviceFeatures2
                        .mallocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                        .pNext(bufferDeviceAddressFeatures.address());
                vkGetPhysicalDeviceFeatures2(dev, physicalDeviceFeatures2);

                // If any of the above is not supported, we continue with the next physical device
                if (!bufferDeviceAddressFeatures.bufferDeviceAddress() ||
                    !rayTracingPipelineFeatures.rayTracingPipeline() ||
                    !accelerationStructureFeatures.accelerationStructure())
                    continue;

                // Retrieve physical device properties (limits, offsets, alignments, ...)
                VkPhysicalDeviceAccelerationStructurePropertiesKHR accelerationStructureProperties = VkPhysicalDeviceAccelerationStructurePropertiesKHR
                        .mallocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_PROPERTIES_KHR)
                        .pNext(NULL);
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR rayTracingProperties = VkPhysicalDeviceRayTracingPipelinePropertiesKHR
                        .mallocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_PROPERTIES_KHR)
                        .pNext(accelerationStructureProperties.address());
                VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2
                        .mallocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
                        .pNext(rayTracingProperties.address());
                vkGetPhysicalDeviceProperties2(dev, props);

                // Check queue families
                QueueFamilies queuesFamilies = obtainQueueFamilies(dev);
                if (isDeviceSuitable(queuesFamilies)) {
                    return new DeviceAndQueueFamilies(dev, queuesFamilies,
                            rayTracingProperties.shaderGroupHandleSize(),
                            rayTracingProperties.shaderGroupBaseAlignment(),
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
            VkPhysicalDeviceBufferDeviceAddressFeaturesKHR bufferDeviceAddressFeatures = VkPhysicalDeviceBufferDeviceAddressFeaturesKHR
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES_KHR)
                    .bufferDeviceAddress(true);
            VkPhysicalDeviceDescriptorIndexingFeaturesEXT indexingFeatures = VkPhysicalDeviceDescriptorIndexingFeaturesEXT
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DESCRIPTOR_INDEXING_FEATURES_EXT)
                    .pNext(bufferDeviceAddressFeatures.address())
                    .runtimeDescriptorArray(true);
            VkPhysicalDeviceAccelerationStructureFeaturesKHR accelerationStructureFeatures = VkPhysicalDeviceAccelerationStructureFeaturesKHR
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR)
                    .pNext(indexingFeatures.address())
                    .accelerationStructure(true);
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracingFeatures = VkPhysicalDeviceRayTracingPipelineFeaturesKHR
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR)
                    .pNext(accelerationStructureFeatures.address())
                    .rayTracingPipeline(true);
            VkDeviceCreateInfo pCreateInfo = VkDeviceCreateInfo
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pNext(rayTracingFeatures.address())
                    .pQueueCreateInfos(VkDeviceQueueCreateInfo
                            .callocStack(1, stack)
                            .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                            .queueFamilyIndex(queueFamily)
                            .pQueuePriorities(stack.floats(1.0f)))
                    .ppEnabledLayerNames(ppEnabledLayerNames)
                    .ppEnabledExtensionNames(extensions);
            PointerBuffer pDevice = stack.mallocPointer(1);
            _CHECK_(vkCreateDevice(deviceAndQueueFamilies.physicalDevice, pCreateInfo, null, pDevice),
                    "Failed to create device");
            return new VkDevice(pDevice.get(0), deviceAndQueueFamilies.physicalDevice, pCreateInfo, VK_API_VERSION_1_1);
        }
    }

    private static List<String> enumerateSupportedDeviceExtensions() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pPropertyCount = stack.mallocInt(1);
            _CHECK_(vkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, (ByteBuffer) null, pPropertyCount, null),
                    "Failed to get number of device extensions");
            int propertyCount = pPropertyCount.get(0);
            VkExtensionProperties.Buffer pProperties = VkExtensionProperties.mallocStack(propertyCount, stack);
            _CHECK_(vkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, (ByteBuffer) null, pPropertyCount, pProperties),
                    "Failed to enumerate the device extensions");
            return range(0, propertyCount).mapToObj(i -> pProperties.get(i).extensionNameString()).collect(toList());
        }
    }

    private static long createVmaAllocator() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pAllocator = stack.mallocPointer(1);
            _CHECK_(vmaCreateAllocator(VmaAllocatorCreateInfo
                        .callocStack(stack)
                        .flags(VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                        .physicalDevice(deviceAndQueueFamilies.physicalDevice)
                        .device(device)
                        .pVulkanFunctions(VmaVulkanFunctions
                                .callocStack(stack)
                                .set(instance, device))
                        .instance(instance)
                        .vulkanApiVersion(VK_API_VERSION_1_1), pAllocator),
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
                    .mallocStack(pSurfaceFormatCount.get(0), stack);
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
                    .mallocStack(stack);
            _CHECK_(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(deviceAndQueueFamilies.physicalDevice, surface, pSurfaceCapabilities),
                    "Failed to get physical device surface capabilities");
            IntBuffer pPresentModeCount = stack.mallocInt(1);
            _CHECK_(vkGetPhysicalDeviceSurfacePresentModesKHR(deviceAndQueueFamilies.physicalDevice, surface, pPresentModeCount, null),
                    "Failed to get presentation modes count");
            int presentModeCount = pPresentModeCount.get(0);
            IntBuffer pPresentModes = stack.mallocInt(presentModeCount);
            _CHECK_(vkGetPhysicalDeviceSurfacePresentModesKHR(deviceAndQueueFamilies.physicalDevice, surface, pPresentModeCount, pPresentModes),
                    "Failed to get presentation modes");
            int imageCount = min(max(pSurfaceCapabilities.minImageCount() + 1, 3), pSurfaceCapabilities.maxImageCount());
            ColorFormatAndSpace surfaceFormat = determineSurfaceFormat(deviceAndQueueFamilies.physicalDevice, surface);
            Vector2i swapchainExtents = determineSwapchainExtents(pSurfaceCapabilities);
            VkSwapchainCreateInfoKHR pCreateInfo = VkSwapchainCreateInfoKHR
                .callocStack(stack)
                .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
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
                .oldSwapchain(swapchain != null ? swapchain.swapchain : VK_NULL_HANDLE);
            LongBuffer pSwapchain = stack.mallocLong(Long.BYTES);
            _CHECK_(vkCreateSwapchainKHR(device, pCreateInfo, null, pSwapchain),
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
                            .callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
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

    private static long createCommandPool(int flags) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pCmdPool = stack.mallocLong(1);
            _CHECK_(vkCreateCommandPool(device, VkCommandPoolCreateInfo
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(flags)
                    .queueFamilyIndex(queueFamily), null, pCmdPool),
                    "Failed to create command pool");
            return pCmdPool.get(0);
        }
    }

    private static VkCommandBuffer createCommandBuffer(long pool) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            _CHECK_(vkAllocateCommandBuffers(device,
                    VkCommandBufferAllocateInfo
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                        .commandPool(pool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1), pCommandBuffer),
                    "Failed to create command buffer");
            VkCommandBuffer cmdBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);
            _CHECK_(vkBeginCommandBuffer(cmdBuffer, VkCommandBufferBeginInfo
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)),
                    "Failed to begin command buffer");
            return cmdBuffer;
        }
    }

    private static void createSyncObjects() {
        imageAcquireSemaphores = new long[swapchain.imageViews.length];
        renderCompleteSemaphores = new long[swapchain.imageViews.length];
        renderFences = new long[swapchain.imageViews.length];
        for (int i = 0; i < swapchain.imageViews.length; i++) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer pSemaphore = stack.mallocLong(1);
                VkSemaphoreCreateInfo pCreateInfo = VkSemaphoreCreateInfo
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
                _CHECK_(vkCreateSemaphore(device, pCreateInfo, null, pSemaphore),
                        "Failed to create image acquire semaphore");
                imageAcquireSemaphores[i] = pSemaphore.get(0);
                _CHECK_(vkCreateSemaphore(device, pCreateInfo, null, pSemaphore),
                        "Failed to create render complete semaphore");
                renderCompleteSemaphores[i] = pSemaphore.get(0);
                LongBuffer pFence = stack.mallocLong(1);
                _CHECK_(vkCreateFence(device, VkFenceCreateInfo
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
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
        rayTracingDescriptorSets = createRayTracingDescriptorSets();
        commandBuffers = createRayTracingCommandBuffers();
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
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pWaitSemaphores(stack.longs(imageAcquireSemaphores[idx]))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR))
                    .pCommandBuffers(stack.pointers(commandBuffers[idx]))
                    .waitSemaphoreCount(1)
                    .pSignalSemaphores(stack.longs(renderCompleteSemaphores[idx])),
                    renderFences[idx]),
                    "Failed to submit command buffer");
            int result = vkQueuePresentKHR(queue, VkPresentInfoKHR
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(stack.longs(renderCompleteSemaphores[idx]))
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

    private static class Geometry {
        private final AllocationAndBuffer positions;
        private final AllocationAndBuffer indices;
        private final int numPrimities;
        private Geometry(AllocationAndBuffer positions, AllocationAndBuffer indices, int numPrimities) {
            this.positions = positions;
            this.indices = indices;
            this.numPrimities = numPrimities;
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
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO), null, pFence),
                    "Failed to create fence");
            _CHECK_(vkQueueSubmit(queue, VkSubmitInfo
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
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
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                        .size(size)
                        .usage(usageFlags | (data != null ? VK_BUFFER_USAGE_TRANSFER_DST_BIT : 0)),
                    VmaAllocationCreateInfo
                        .callocStack(stack)
                        .usage(VMA_MEMORY_USAGE_GPU_ONLY), pBuffer, pAllocation, null),
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
                            .callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                            .size(data.remaining())
                            .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT),
                        VmaAllocationCreateInfo
                            .callocStack(stack)
                            .usage(VMA_MEMORY_USAGE_CPU_ONLY), pBufferStage, pAllocationStage, null),
                        "Failed to allocate stage buffer");

                // map the memory and memcpy into it
                PointerBuffer pData = stack.mallocPointer(1);
                _CHECK_(vmaMapMemory(vmaAllocator, pAllocationStage.get(0), pData), "Failed to map memory");
                memCopy(memAddress(data), pData.get(0), data.remaining());
                // no need to vkFlushMappedMemoryRanges(), because VMA guarantees VMA_MEMORY_USAGE_CPU_ONLY to be host coherent.
                vmaUnmapMemory(vmaAllocator, pAllocationStage.get(0));

                // issue copy buffer command
                VkCommandBuffer cmdBuffer = createCommandBuffer(commandPoolTransient);
                vkCmdCopyBuffer(cmdBuffer, pBufferStage.get(0), pBuffer.get(0), VkBufferCopy
                        .callocStack(1, stack)
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

    private static AllocationAndBuffer[] createUniformBufferObjects(int size) {
        AllocationAndBuffer[] ret = new AllocationAndBuffer[swapchain.imageViews.length];
        for (int i = 0; i < ret.length; i++) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer pBuffer = stack.mallocLong(1);
                PointerBuffer pAllocation = stack.mallocPointer(1);
                VmaAllocationInfo pAllocationInfo = VmaAllocationInfo.mallocStack(stack);
                _CHECK_(vmaCreateBuffer(vmaAllocator, VkBufferCreateInfo
                            .callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                            .size(size)
                            .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT),
                        VmaAllocationCreateInfo
                            .callocStack(stack)
                            .usage(VMA_MEMORY_USAGE_CPU_TO_GPU), pBuffer, pAllocation, pAllocationInfo),
                        "Failed to allocate buffer");

                // check whether the allocation is host-coherent
                IntBuffer memTypeProperties = stack.mallocInt(1);
                vmaGetMemoryTypeProperties(vmaAllocator, pAllocationInfo.memoryType(), memTypeProperties);
                boolean isHostCoherent = (memTypeProperties.get(0) & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
                AllocationAndBuffer a = new AllocationAndBuffer(pAllocation.get(0), pBuffer.get(0), isHostCoherent);
                a.map(size);
                ret[i] = a;
            }
        }
        return ret;
    }

    private static Geometry createGeometry() {
        DynamicByteBuffer positions = new DynamicByteBuffer();
        positions.putFloat(-2).putFloat(-1).putFloat(0);
        positions.putFloat(2).putFloat(-1).putFloat(0);
        positions.putFloat(0).putFloat(1).putFloat(0);
        DynamicByteBuffer indices = new DynamicByteBuffer();
        indices.putShort(0).putShort(1).putShort(2);
        AllocationAndBuffer positionsBuffer = createBuffer(
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR, memByteBuffer(positions.addr, positions.pos), Float.BYTES, null);
        positions.free();
        AllocationAndBuffer indicesBuffer = createBuffer(
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR, memByteBuffer(indices.addr, indices.pos), Short.BYTES, null);
        indices.free();
        return new Geometry(positionsBuffer, indicesBuffer, 1);
    }

    private static VkDeviceOrHostAddressKHR deviceAddress(MemoryStack stack, long buffer, long alignment) {
        return VkDeviceOrHostAddressKHR
                .mallocStack(stack)
                .deviceAddress(bufferAddress(buffer, alignment));
    }
    private static VkDeviceOrHostAddressConstKHR deviceAddressConst(MemoryStack stack, long buffer, long alignment) {
        return VkDeviceOrHostAddressConstKHR
                .mallocStack(stack)
                .deviceAddress(bufferAddress(buffer, alignment));
    }
    private static long bufferAddress(long buffer, long alignment) {
        long address;
        try (MemoryStack stack = stackPush()) {
            address = vkGetBufferDeviceAddressKHR(device, VkBufferDeviceAddressInfo
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO_KHR)
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
                        .callocStack(1, stack)
                        .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                        .geometryCount(1)
                        .pGeometries(VkAccelerationStructureGeometryKHR
                                .callocStack(1, stack)
                                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                                .geometry(VkAccelerationStructureGeometryDataKHR
                                        .callocStack(stack)
                                        .triangles(VkAccelerationStructureGeometryTrianglesDataKHR
                                                .callocStack(stack)
                                                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                                                .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                                                .vertexData(deviceAddressConst(stack, geometry.positions.buffer, Float.BYTES))
                                                .vertexStride(3 * Float.BYTES)
                                                .maxVertex(geometry.numPrimities * 3)
                                                .indexType(VK_INDEX_TYPE_UINT16)
                                                .indexData(deviceAddressConst(stack, geometry.indices.buffer, Short.BYTES))))
                                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR));

            // Query necessary sizes for the acceleration structure buffer and for the scratch buffer
            VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo = VkAccelerationStructureBuildSizesInfoKHR
                    .mallocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR)
                    .pNext(NULL);
            vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    pInfos.get(0),
                    stack.ints(1),
                    buildSizesInfo);

            // Create a buffer that will hold the final BLAS
            AllocationAndBuffer accelerationStructureBuffer = createBuffer(
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR |
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, buildSizesInfo.accelerationStructureSize(),
                    null, 256, null);

            // Create a BLAS object (not currently built)
            LongBuffer pAccelerationStructure = stack.mallocLong(1);
            _CHECK_(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                        .buffer(accelerationStructureBuffer.buffer)
                        .size(buildSizesInfo.accelerationStructureSize())
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR), null, pAccelerationStructure),
                    "Failed to create bottom-level acceleration structure");

            // Create a scratch buffer for the BLAS build
            AllocationAndBuffer scratchBuffer = createBuffer(
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR |
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, buildSizesInfo.buildScratchSize(), null,
                    deviceAndQueueFamilies.minAccelerationStructureScratchOffsetAlignment, null);

            // fill missing/remaining info into the build geometry info to
            // be able to build the BLAS instance.
            pInfos
                .scratchData(deviceAddress(stack, scratchBuffer.buffer, deviceAndQueueFamilies.minAccelerationStructureScratchOffsetAlignment))
                .dstAccelerationStructure(pAccelerationStructure.get(0));
            VkCommandBuffer cmdBuf = createCommandBuffer(commandPoolTransient);

            // Insert barrier to let BLAS build wait for the geometry data transfer from the staging buffer to the GPU
            vkCmdPipelineBarrier(cmdBuf,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, // <- copying of the geometry data from the staging buffer to the GPU buffer
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, // <- accessing the buffer for acceleration structure build
                    0, // <- no dependency flags
                    VkMemoryBarrier
                        .callocStack(1, stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
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
                    stack.pointers(
                            VkAccelerationStructureBuildRangeInfoKHR
                            .callocStack(1, stack)
                            .primitiveCount(geometry.numPrimities)));

            // Finally submit command buffer and register callback when fence signals to 
            // dispose of resources
            submitCommandBuffer(cmdBuf, true, () -> {
                vkFreeCommandBuffers(device, commandPoolTransient, cmdBuf);
                scratchBuffer.free();
                // the BLAS is completely self-contained after build, so
                // we can also free the geometry (vertex, index buffers), since
                // we also don't access the geometry data in the shaders.
                geometry.free();
            });
            return new AccelerationStructure(pAccelerationStructure.get(0), accelerationStructureBuffer);
        }
    }

    private static AccelerationStructure createTopLevelAccelerationStructure(AccelerationStructure blas) {
        try (MemoryStack stack = stackPush()) {
            // Query the BLAS device address to reference in the TLAS instance
            long blasDeviceAddress = vkGetAccelerationStructureDeviceAddressKHR(device, 
                    VkAccelerationStructureDeviceAddressInfoKHR
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                        .accelerationStructure(blas.accelerationStructure));

            // Create a single instance for our TLAS
            VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR
                    .callocStack(stack)
                    .accelerationStructureReference(blasDeviceAddress)
                    .mask(~0) // <- we do not want to mask-away any geometry, so use 0b11111111
                    .flags(VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR)
                    .transform(VkTransformMatrixKHR
                            .callocStack(stack)
                            .matrix(new Matrix4x3f().getTransposed(stack.mallocFloat(12))));

            // This instance data also needs to reside in a GPU buffer, so copy it
            AllocationAndBuffer instanceData = createBuffer(
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR |
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                    memByteBuffer(instance.address(), VkAccelerationStructureInstanceKHR.SIZEOF),
                    16, // <- VUID-vkCmdBuildAccelerationStructuresKHR-pInfos-03715
                    null);

            // Create the build geometry info holding the BLAS reference
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer pInfos = 
                    VkAccelerationStructureBuildGeometryInfoKHR
                        .callocStack(1, stack)
                        .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                        .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                        .pGeometries(VkAccelerationStructureGeometryKHR
                                .callocStack(1, stack)
                                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                                .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                                .geometry(VkAccelerationStructureGeometryDataKHR
                                        .callocStack(stack)
                                        .instances(VkAccelerationStructureGeometryInstancesDataKHR
                                                .callocStack(stack)
                                                .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
                                                .data(deviceAddressConst(stack, instanceData.buffer, 16)))) // <- VUID-vkCmdBuildAccelerationStructuresKHR-pInfos-03715
                                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR))
                        .geometryCount(1);

            // Query necessary sizes for the acceleration structure buffer and for the scratch buffer
            VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo = VkAccelerationStructureBuildSizesInfoKHR
                    .mallocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR)
                    .pNext(NULL);
            vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    pInfos.get(0),
                    stack.ints(1),
                    buildSizesInfo);

            // Create a buffer that will hold the final TLAS
            AllocationAndBuffer accelerationStructureBuffer = createBuffer(
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR |
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, buildSizesInfo.accelerationStructureSize(), null,
                    256,
                    null);

            // Create a TLAS object (not currently built)
            LongBuffer pAccelerationStructure = stack.mallocLong(1);
            _CHECK_(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                        .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                        .size(buildSizesInfo.accelerationStructureSize())
                        .buffer(accelerationStructureBuffer.buffer), null, pAccelerationStructure),
                    "Failed to create top-level acceleration structure");

            // Create a scratch buffer for the TLAS build
            AllocationAndBuffer scratchBuffer = createBuffer(
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR |
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, buildSizesInfo.buildScratchSize(), null,
                    deviceAndQueueFamilies.minAccelerationStructureScratchOffsetAlignment, null);

            // fill missing/remaining info into the build geometry info to
            // be able to build the TLAS instance.
            pInfos
                .scratchData(deviceAddress(stack, scratchBuffer.buffer, deviceAndQueueFamilies.minAccelerationStructureScratchOffsetAlignment))
                .dstAccelerationStructure(pAccelerationStructure.get(0));
            VkCommandBuffer cmdBuf = createCommandBuffer(commandPoolTransient);

            // Insert barrier to let TLAS build wait for the instance data transfer from the staging buffer to the GPU
            vkCmdPipelineBarrier(cmdBuf,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, // <- copying of the instance data from the staging buffer to the GPU buffer
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, // <- accessing the buffer for acceleration structure build
                    0, // <- no dependency flags
                    VkMemoryBarrier
                        .callocStack(1, stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
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
                    stack.pointers(
                            VkAccelerationStructureBuildRangeInfoKHR
                            .callocStack(1, stack)
                            .primitiveCount(1))); // <- number of BLASes!

            // insert barrier to let tracing wait for the TLAS build
            vkCmdPipelineBarrier(cmdBuf,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    0, // <- no dependency flags
                    VkMemoryBarrier
                        .callocStack(1, stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                        .srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                        .dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR),
                    null,
                    null);

            // Finally submit command buffer and register callback when fence signals to 
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
        int numDescriptors = 3;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSetLayout = stack.mallocLong(1);
            // create the descriptor set layout
            // we have one acceleration structure, one storage image and one uniform buffer
            _CHECK_(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                        .pBindings(VkDescriptorSetLayoutBinding
                                .callocStack(numDescriptors, stack)
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
                                .flip()),
                    null, pSetLayout),
                    "Failed to create descriptor set layout");
            LongBuffer pPipelineLayout = stack.mallocLong(1);
            _CHECK_(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                        .pSetLayouts(pSetLayout), null, pPipelineLayout),
                    "Failed to create pipeline layout");
            VkPipelineShaderStageCreateInfo.Buffer pStages = VkPipelineShaderStageCreateInfo
                    .callocStack(3, stack);

            // load shaders
            String pkg = SimpleTriangle.class.getName().toLowerCase().replace('.', '/') + "/";
            loadShader(pStages
                    .get(0)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO), 
                    null, stack, device, pkg + "raygen.glsl", VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            loadShader(pStages
                    .get(1)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO),
                    null, stack, device, pkg + "raymiss.glsl", VK_SHADER_STAGE_MISS_BIT_KHR);
            loadShader(pStages
                    .get(2)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO),
                    null, stack, device, pkg + "closesthit.glsl", VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);

            VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR
                    .callocStack(3, stack);
            groups.forEach(g -> g
                    .sType(VK_STRUCTURE_TYPE_RAY_TRACING_SHADER_GROUP_CREATE_INFO_KHR)
                    .generalShader(VK_SHADER_UNUSED_KHR)
                    .closestHitShader(VK_SHADER_UNUSED_KHR)
                    .anyHitShader(VK_SHADER_UNUSED_KHR)
                    .intersectionShader(VK_SHADER_UNUSED_KHR));
            groups.apply(0, g ->
                        g.type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                         .generalShader(0))
                  .apply(1, g ->
                        g.type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                         .generalShader(1))
                  .apply(2, g ->
                        g.type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                         .closestHitShader(2));
            LongBuffer pPipelines = stack.mallocLong(1);
            _CHECK_(vkCreateRayTracingPipelinesKHR(device, VK_NULL_HANDLE, VK_NULL_HANDLE, VkRayTracingPipelineCreateInfoKHR
                        .callocStack(1, stack)
                        .sType(VK_STRUCTURE_TYPE_RAY_TRACING_PIPELINE_CREATE_INFO_KHR)
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
            int groupHandleSize = deviceAndQueueFamilies.shaderGroupHandleSize;
            // group handles must be properly aligned when writing them to the final GPU buffer, so compute
            // the aligned group handle size
            int groupSizeAligned = alignUp(groupHandleSize, deviceAndQueueFamilies.shaderGroupBaseAlignment);

            // compute the final size of the GPU buffer
            int sbtSize = groupCount * groupSizeAligned;

            // retrieve the three shader group handles
            ByteBuffer handles = stack.malloc(groupCount * groupHandleSize);
            _CHECK_(vkGetRayTracingShaderGroupHandlesKHR(device, rayTracingPipeline.pipeline, 0, 3, handles),
                    "Failed to obtain ray tracing group handles");

            // prepare memory with properly aligned group handles
            ByteBuffer handlesForGpu = stack.malloc(sbtSize);
            memCopy(memAddress(handles), memAddress(handlesForGpu), groupHandleSize);
            memCopy(memAddress(handles) + groupHandleSize, memAddress(handlesForGpu) + groupSizeAligned, groupHandleSize);
            memCopy(memAddress(handles) + 2L * groupHandleSize, memAddress(handlesForGpu) + 2L * groupSizeAligned, groupHandleSize);

            // and upload to a new GPU buffer
            return createBuffer(VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR |
                                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR, handlesForGpu,
                                deviceAndQueueFamilies.shaderGroupBaseAlignment, (cmdBuf) -> {
                                    // insert memory barrier to let ray tracing shader wait for SBT transfer
                                    try (MemoryStack s = stackPush()) {
                                        vkCmdPipelineBarrier(cmdBuf,
                                                VK_PIPELINE_STAGE_TRANSFER_BIT,
                                                VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                                                0,
                                                VkMemoryBarrier
                                                    .callocStack(1, s)
                                                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
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
        int numDescriptors = 3;
        try (MemoryStack stack = stackPush()) {
            LongBuffer pDescriptorPool = stack.mallocLong(1);
            _CHECK_(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo
                        .callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                        .pPoolSizes(VkDescriptorPoolSize
                                .callocStack(numDescriptors, stack)
                                .apply(0, dps -> dps
                                        .type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                                        .descriptorCount(numSets))
                                .apply(1, dps -> dps
                                        .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                        .descriptorCount(numSets))
                                .apply(2, dps -> dps
                                        .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                        .descriptorCount(numSets)))
                        .maxSets(numSets), null, pDescriptorPool),
                    "Failed to create descriptor pool");
            VkDescriptorSetAllocateInfo descriptorSetAllocateInfo = VkDescriptorSetAllocateInfo
                    .callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pDescriptorPool.get(0))
                    .pSetLayouts(repeat(stack, rayTracingPipeline.descriptorSetLayout, numSets));
            LongBuffer pDescriptorSets = stack.mallocLong(numSets);
            _CHECK_(vkAllocateDescriptorSets(device, descriptorSetAllocateInfo, pDescriptorSets),
                    "Failed to allocate descriptor set");
            long[] sets = new long[pDescriptorSets.remaining()];
            pDescriptorSets.get(sets, 0, sets.length);
            VkWriteDescriptorSet.Buffer writeDescriptorSet = VkWriteDescriptorSet
                    .callocStack(numDescriptors * numSets, stack);
            for (int i = 0; i < numSets; i++) {
                final int idx = i;
                writeDescriptorSet
                        .apply(wds -> wds
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                                .dstBinding(0)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pNext(VkWriteDescriptorSetAccelerationStructureKHR
                                        .callocStack(stack)
                                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                                        .pAccelerationStructures(stack.longs(tlas.accelerationStructure))
                                        .address()))
                        .apply(wds -> wds
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .dstBinding(1)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pImageInfo(VkDescriptorImageInfo
                                        .callocStack(1, stack)
                                        .imageView(swapchain.imageViews[idx])
                                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL)))
                        .apply(wds -> wds
                                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                                .dstBinding(2)
                                .dstSet(pDescriptorSets.get(idx))
                                .descriptorCount(1)
                                .pBufferInfo(VkDescriptorBufferInfo
                                        .callocStack(1, stack)
                                        .buffer(rayTracingUbos[idx].buffer)
                                        .range(VK_WHOLE_SIZE)));
            }
            vkUpdateDescriptorSets(device, writeDescriptorSet.flip(), null);
            return new DescriptorSets(pDescriptorPool.get(0), sets);
        }
    }

    private static VkCommandBuffer[] createRayTracingCommandBuffers() {
        if (commandBuffers != null) {
            try (MemoryStack stack = stackPush()) {
                vkFreeCommandBuffers(device, commandPool, stack.pointers(commandBuffers));
            }
        }
        int count = swapchain.imageViews.length;
        VkCommandBuffer[] buffers = new VkCommandBuffer[count];
        for (int i = 0; i < count; i++) {
            VkCommandBuffer cmdBuf = createCommandBuffer(commandPool);
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
                            .callocStack(1, stack)
                            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
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
                int groupSize = alignUp(deviceAndQueueFamilies.shaderGroupHandleSize, deviceAndQueueFamilies.shaderGroupBaseAlignment);
                long sbtAddress = bufferAddress(sbt.buffer, deviceAndQueueFamilies.shaderGroupBaseAlignment);

                // and issue a tracing command
                vkCmdTraceRaysKHR(cmdBuf,
                        VkStridedDeviceAddressRegionKHR.callocStack(stack).deviceAddress(sbtAddress).stride(groupSize).size(groupSize),
                        VkStridedDeviceAddressRegionKHR.callocStack(stack).deviceAddress(sbtAddress + groupSize).stride(groupSize).size(groupSize),
                        VkStridedDeviceAddressRegionKHR.callocStack(stack).deviceAddress(sbtAddress + 2L * groupSize).stride(groupSize).size(groupSize),
                        VkStridedDeviceAddressRegionKHR.callocStack(stack), swapchain.width, swapchain.height, 1);

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
                            .callocStack(1, stack)
                            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
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

    private static void updateRayTracingUniformBufferObject(int idx) {
        projMatrix.scaling(1, -1, 1).perspective((float) toRadians(45.0f), (float) windowAndCallbacks.width / windowAndCallbacks.height, 0.1f, 100.0f, true);
        viewMatrix.setLookAt(0, 0, 10, 0, 0, 0, 0, 1, 0);
        projMatrix.invert(invProjMatrix);
        viewMatrix.invert(invViewMatrix);
        invProjMatrix.transformProject(-1, -1, 0, 1, tmpv3).get(rayTracingUbos[idx].mapped);
        invProjMatrix.transformProject(+1, -1, 0, 1, tmpv3).get(4*Float.BYTES, rayTracingUbos[idx].mapped);
        invProjMatrix.transformProject(-1, +1, 0, 1, tmpv3).get(8*Float.BYTES, rayTracingUbos[idx].mapped);
        invProjMatrix.transformProject(+1, +1, 0, 1, tmpv3).get(12*Float.BYTES, rayTracingUbos[idx].mapped);
        invViewMatrix.get4x4(Float.BYTES * 16, rayTracingUbos[idx].mapped);
        rayTracingUbos[idx].flushMapped(0, Float.BYTES * 16 * 2);
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
                       VK_EXT_DESCRIPTOR_INDEXING_EXTENSION_NAME,
                       VK_KHR_BUFFER_DEVICE_ADDRESS_EXTENSION_NAME,
                       VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
                       VK_KHR_SPIRV_1_4_EXTENSION_NAME,
                       VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME));
        vmaAllocator = createVmaAllocator();
        queue = retrieveQueue();
        swapchain = createSwapchain();
        commandPool = createCommandPool(0);
        commandPoolTransient = createCommandPool(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
        Geometry geometry = createGeometry();
        blas = createBottomLevelAccelerationStructure(geometry);
        tlas = createTopLevelAccelerationStructure(blas);
        rayTracingUbos = createUniformBufferObjects(2 * 16 * Float.BYTES);
        rayTracingPipeline = createRayTracingPipeline();
        sbt = createRayTracingShaderBindingTable();
        rayTracingDescriptorSets = createRayTracingDescriptorSets();
        commandBuffers = createRayTracingCommandBuffers();
        createSyncObjects();
    }

    private static void runOnRenderThread() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pImageIndex = stack.mallocInt(1);
            int idx = 0;
            boolean needRecreate = false;
            while (!glfwWindowShouldClose(windowAndCallbacks.window)) {
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
                updateRayTracingUniformBufferObject(idx);
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
        for (AllocationAndBuffer rayTracingUbo : rayTracingUbos)
            rayTracingUbo.free();
        rayTracingDescriptorSets.free();
        sbt.free();
        rayTracingPipeline.free();
        tlas.free();
        blas.free();
        for (int i = 0; i < swapchain.imageViews.length; i++) {
            vkDestroySemaphore(device, imageAcquireSemaphores[i], null);
            vkDestroySemaphore(device, renderCompleteSemaphores[i], null);
            vkDestroyFence(device, renderFences[i], null);
        }
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
        Thread updateAndRenderThread = new Thread(SimpleTriangle::runOnRenderThread);
        updateAndRenderThread.start();
        runWndProcLoop();
        updateAndRenderThread.join();
        destroy();
    }

}
