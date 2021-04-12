/*
 * Copyright LWJGL. All rights reserved.
 * License terms: https://www.lwjgl.org/license
 */
package org.lwjgl.demo.vulkan;

import static java.lang.Math.*;
import static org.lwjgl.demo.vulkan.VKFactory.*;
import static org.lwjgl.demo.vulkan.VKUtil.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK11.*;

import java.nio.*;
import java.util.*;

import org.joml.Vector2i;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.*;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;

/**
 * Renders a simple cornflower blue image on a GLFW window with Vulkan.
 * 
 * @author Kai Burjack
 */
public class ClearScreenDemo {

    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("debug", "false"));
    static {
        if (DEBUG) {
            // When we are in debug mode, enable all LWJGL debug flags
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
    private static VkQueue queue;
    private static Swapchain swapchain;
    private static long renderPass;
    private static long commandPool;
    private static long[] framebuffers;
    private static VkCommandBuffer[] commandBuffers;
    private static long[] imageAcquireSemaphores;
    private static long[] renderCompleteSemaphores;
    private static long[] renderFences;

    private static class WindowAndCallbacks {
        private final long window;
        private final GLFWKeyCallback keyCallback;
        private int width;
        private int height;

        WindowAndCallbacks(long window, int width, int height, GLFWKeyCallback keyCallback) {
            this.window = window;
            this.width = width;
            this.height = height;
            this.keyCallback = keyCallback;
        }

        void free() {
            glfwDestroyWindow(window);
            keyCallback.free();
        }
    }

    private static class DebugCallbackAndHandle {
        long messengerHandle;
        VkDebugUtilsMessengerCallbackEXT callback;

        DebugCallbackAndHandle(long handle, VkDebugUtilsMessengerCallbackEXT callback) {
            this.messengerHandle = handle;
            this.callback = callback;
        }

        void free() {
            vkDestroyDebugUtilsMessengerEXT(instance, messengerHandle, null);
            callback.free();
        }
    }

    private static class QueueFamilies {
        List<Integer> graphicsFamilies = new ArrayList<>();
        List<Integer> presentFamilies = new ArrayList<>();

        int findSingleSuitableQueue() {
            return graphicsFamilies.stream().filter(i -> presentFamilies.contains(i)).findAny()
                    .orElseThrow(() -> new AssertionError("No suitable queue found"));
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
        long[] imageViews;
        int width, height;
        ColorFormatAndSpace surfaceFormat;

        Swapchain(long swapchain, long[] imageViews, int width, int height, ColorFormatAndSpace surfaceFormat) {
            this.swapchain = swapchain;
            this.imageViews = imageViews;
            this.width = width;
            this.height = height;
            this.surfaceFormat = surfaceFormat;
        }

        void free() {
            vkDestroySwapchainKHR(device, swapchain, null);
            for (long imageView : imageViews)
                vkDestroyImageView(device, imageView, null);
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
            long pPropertyCount = stack.nmalloc(Integer.BYTES);
            _CHECK_(nvkEnumerateInstanceExtensionProperties(NULL, pPropertyCount, NULL),
                    "Could not enumerate number of instance extensions");
            int propertyCount = memGetInt(pPropertyCount);
            VkExtensionProperties.Buffer pProperties = VkExtensionProperties.mallocStack(propertyCount, stack);
            _CHECK_(nvkEnumerateInstanceExtensionProperties(NULL, pPropertyCount, pProperties.address()),
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
            VkInstanceCreateInfo pCreateInfo = VkInstanceCreateInfo(stack)
                    .pApplicationInfo(VkApplicationInfo(stack).apiVersion(VK_API_VERSION_1_1))
                    .ppEnabledExtensionNames(ppEnabledExtensionNames)
                    .ppEnabledLayerNames(enabledLayers);
            long pInstance = stack.nmalloc(POINTER_SIZE);
            _CHECK_(nvkCreateInstance(pCreateInfo.address(), NULL, pInstance), "Failed to create VkInstance");
            return new VkInstance(memGetAddress(pInstance), pCreateInfo);
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
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode vidmode = glfwGetVideoMode(monitor);
        int width = vidmode.width(), height = vidmode.height();
        long window = glfwCreateWindow(width, height, "Hello, voxel world!", monitor, NULL);
        GLFWKeyCallback keyCallback = new GLFWKeyCallback() {
            public void invoke(long window, int key, int scancode, int action, int mods) {
                if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE)
                    glfwSetWindowShouldClose(window, true);
            }
        };
        int w, h;
        try (MemoryStack stack = stackPush()) {
            long addr = stack.nmalloc(2 * Integer.BYTES);
            nglfwGetFramebufferSize(window, addr, addr + Integer.BYTES);
            w = memGetInt(addr);
            h = memGetInt(addr + Integer.BYTES);
        }
        glfwSetKeyCallback(window, keyCallback);
        return new WindowAndCallbacks(window, w, h, keyCallback);
    }

    private static long createSurface() {
        try (MemoryStack stack = stackPush()) {
            long surface = stack.nmalloc(Long.BYTES);
            _CHECK_(nglfwCreateWindowSurface(instance.address(), windowAndCallbacks.window, NULL, surface), "Failed to create surface");
            return memGetLong(surface);
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
            long pMessenger = stack.nmalloc(Long.BYTES);
            _CHECK_(nvkCreateDebugUtilsMessengerEXT(instance,
                    VkDebugUtilsMessengerCreateInfoEXT(stack)
                            .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                            .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                    | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                            .pfnUserCallback(callback).address(),
                    NULL, pMessenger), "Failed to create debug messenger");
            return new DebugCallbackAndHandle(memGetLong(pMessenger), callback);
        }
    }

    private static boolean familySupports(VkQueueFamilyProperties prop, int bit) {
        return (prop.queueFlags() & bit) != 0;
    }

    private static QueueFamilies obtainQueueFamilies(VkPhysicalDevice physicalDevice) {
        QueueFamilies ret = new QueueFamilies();
        try (MemoryStack stack = stackPush()) {
            long pQueueFamilyPropertyCount = stack.nmalloc(Integer.BYTES);
            nvkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, NULL);
            int numQueueFamilies = memGetInt(pQueueFamilyPropertyCount);
            if (numQueueFamilies == 0)
                throw new AssertionError("No queue families found");
            VkQueueFamilyProperties.Buffer familyProperties = VkQueueFamilyProperties(numQueueFamilies);
            nvkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pQueueFamilyPropertyCount, familyProperties.address());
            int queueFamilyIndex = 0;
            long pSupported = stack.nmalloc(Integer.BYTES);
            for (VkQueueFamilyProperties queueFamilyProps : familyProperties) {
                if (queueFamilyProps.queueCount() < 1) {
                    continue;
                }
                nvkGetPhysicalDeviceSurfaceSupportKHR(physicalDevice, queueFamilyIndex, surface, pSupported);
                if (familySupports(queueFamilyProps, VK_QUEUE_GRAPHICS_BIT))
                    ret.graphicsFamilies.add(queueFamilyIndex);
                if (memGetInt(pSupported) != 0)
                    ret.presentFamilies.add(queueFamilyIndex);
                queueFamilyIndex++;
            }
            return ret;
        }
    }

    private static boolean isDeviceSuitable(QueueFamilies queuesFamilies) {
        return !queuesFamilies.graphicsFamilies.isEmpty() && !queuesFamilies.presentFamilies.isEmpty();
    }

    private static DeviceAndQueueFamilies createSinglePhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            long pPhysicalDeviceCount = stack.nmalloc(Integer.BYTES);
            _CHECK_(nvkEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, NULL), "Failed to get number of physical devices");
            int physicalDeviceCount = memGetInt(pPhysicalDeviceCount);
            if (physicalDeviceCount == 0)
                throw new AssertionError("No physical devices available");
            long pPhysicalDevices = stack.nmalloc(physicalDeviceCount * POINTER_SIZE);
            _CHECK_(nvkEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, pPhysicalDevices), "Failed to get physical devices");
            for (int i = 0; i < physicalDeviceCount; i++) {
                VkPhysicalDevice dev = new VkPhysicalDevice(memGetAddress(pPhysicalDevices + (long) POINTER_SIZE * i), instance);
                QueueFamilies queuesFamilies = obtainQueueFamilies(dev);
                if (isDeviceSuitable(queuesFamilies)) {
                    return new DeviceAndQueueFamilies(dev, queuesFamilies);
                }
            }
            throw new AssertionError("No suitable physical device found");
        }
    }

    private static VkDevice createDevice() {
        List<String> supportedDeviceExtensions = enumerateSupportedDeviceExtensions();
        try (MemoryStack stack = stackPush()) {
            if (!supportedDeviceExtensions.contains(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                throw new AssertionError(VK_KHR_SWAPCHAIN_EXTENSION_NAME + " device extension is not supported");
            }
            PointerBuffer ppEnabledExtensionNames = stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            PointerBuffer ppEnabledLayerNames = null;
            if (DEBUG) {
                ppEnabledLayerNames = stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"));
            }
            VkDeviceCreateInfo pCreateInfo = VkDeviceCreateInfo(stack)
                    .pQueueCreateInfos(VkDeviceQueueCreateInfo(stack)
                            .queueFamilyIndex(queueFamily)
                            .pQueuePriorities(stack.floats(1.0f)))
                    .ppEnabledExtensionNames(ppEnabledExtensionNames)
                    .ppEnabledLayerNames(ppEnabledLayerNames);
            long pDevice = stack.nmalloc(POINTER_SIZE);
            _CHECK_(nvkCreateDevice(deviceAndQueueFamilies.physicalDevice, pCreateInfo.address(), NULL, pDevice), "Failed to create device");
            return new VkDevice(memGetAddress(pDevice), deviceAndQueueFamilies.physicalDevice, pCreateInfo);
        }
    }

    private static List<String> enumerateSupportedDeviceExtensions() {
        try (MemoryStack stack = stackPush()) {
            long pPropertyCount = stack.nmalloc(Integer.BYTES);
            _CHECK_(nvkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, NULL, pPropertyCount, NULL),
                    "Failed to get number of device extensions");
            int propertyCount = memGetInt(pPropertyCount);
            VkExtensionProperties.Buffer pProperties = VkExtensionProperties.mallocStack(propertyCount, stack);
            _CHECK_(nvkEnumerateDeviceExtensionProperties(deviceAndQueueFamilies.physicalDevice, NULL, pPropertyCount, pProperties.address()),
                    "Failed to enumerate the device extensions");
            List<String> res = new ArrayList<>(propertyCount);
            for (int i = 0; i < propertyCount; i++) {
                res.add(pProperties.get(i).extensionNameString());
            }
            return res;
        }
    }

    private static VkQueue retrieveQueue() {
        try (MemoryStack stack = stackPush()) {
            long pQueue = stack.nmalloc(POINTER_SIZE);
            nvkGetDeviceQueue(device, queueFamily, 0, pQueue);
            return new VkQueue(memGetAddress(pQueue), device);
        }
    }

    private static ColorFormatAndSpace determineSurfaceFormat(VkPhysicalDevice physicalDevice, long surface) {
        try (MemoryStack stack = stackPush()) {
            long pSurfaceFormatCount = stack.nmalloc(Integer.BYTES);
            _CHECK_(nvkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pSurfaceFormatCount, NULL),
                    "Failed to get number of device surface formats");
            VkSurfaceFormatKHR.Buffer pSurfaceFormats = VkSurfaceFormatKHR(stack, memGetInt(pSurfaceFormatCount));
            _CHECK_(nvkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface, pSurfaceFormatCount, pSurfaceFormats.address()),
                    "Failed to get device surface formats");
            for (VkSurfaceFormatKHR surfaceFormat : pSurfaceFormats) {
                if (surfaceFormat.format() == VK_FORMAT_B8G8R8A8_SRGB && surfaceFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
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
            ret.set(max(min(windowAndCallbacks.width, surfCaps.maxImageExtent().width()), surfCaps.minImageExtent().width()),
                    max(min(windowAndCallbacks.height, surfCaps.maxImageExtent().height()), surfCaps.minImageExtent().height()));
        }
        return ret;
    }

    private static Swapchain createSwapchain() {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR pSurfaceCapabilities = VkSurfaceCapabilitiesKHR(stack);
            _CHECK_(nvkGetPhysicalDeviceSurfaceCapabilitiesKHR(deviceAndQueueFamilies.physicalDevice, surface, pSurfaceCapabilities.address()),
                    "Failed to get physical device surface capabilities");
            long pPresentModeCount = stack.nmalloc(Integer.BYTES);
            _CHECK_(nvkGetPhysicalDeviceSurfacePresentModesKHR(deviceAndQueueFamilies.physicalDevice, surface, pPresentModeCount, NULL),
                    "Failed to get presentation modes count");
            int presentModeCount = memGetInt(pPresentModeCount);
            long pPresentModes = stack.nmalloc(Integer.BYTES * presentModeCount);
            _CHECK_(nvkGetPhysicalDeviceSurfacePresentModesKHR(deviceAndQueueFamilies.physicalDevice, surface, pPresentModeCount, pPresentModes),
                    "Failed to get presentation modes");
            int presentMode = VK_PRESENT_MODE_FIFO_KHR;
            for (int i = 0; i < presentModeCount; i++) {
                int mode = memGetInt(pPresentModes + (long) Integer.BYTES * i);
                if (mode == VK_PRESENT_MODE_MAILBOX_KHR) {
                    presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
                    break;
                }
                if (mode == VK_PRESENT_MODE_IMMEDIATE_KHR) {
                    presentMode = VK_PRESENT_MODE_IMMEDIATE_KHR;
                }
            }
            int imageCount = min(max(pSurfaceCapabilities.minImageCount() + 1, 4), pSurfaceCapabilities.maxImageCount());
            ColorFormatAndSpace surfaceFormat = determineSurfaceFormat(deviceAndQueueFamilies.physicalDevice, surface);
            Vector2i swapchainExtents = determineSwapchainExtents(pSurfaceCapabilities);
            VkSwapchainCreateInfoKHR pCreateInfo = VkSwapchainCreateInfoKHR(stack)
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageExtent(e -> e.set(swapchainExtents.x, swapchainExtents.y))
                    .imageFormat(surfaceFormat.colorFormat)
                    .imageColorSpace(surfaceFormat.colorSpace)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .preTransform(pSurfaceCapabilities.currentTransform())
                    .imageArrayLayers(1)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .presentMode(presentMode)
                    .oldSwapchain(swapchain != null ? swapchain.swapchain : VK_NULL_HANDLE)
                    .clipped(true)
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            long pSwapchain = stack.nmalloc(Long.BYTES);
            _CHECK_(nvkCreateSwapchainKHR(device, pCreateInfo.address(), NULL, pSwapchain), "Failed to create swap chain");
            if (swapchain != null) {
                swapchain.free();
            }
            long swapchain = memGetLong(pSwapchain);
            long pSwapchainImageCount = stack.nmalloc(Integer.BYTES);
            _CHECK_(nvkGetSwapchainImagesKHR(device, swapchain, pSwapchainImageCount, NULL), "Failed to get swapchain images count");
            int actualImageCount = memGetInt(pSwapchainImageCount);
            long pSwapchainImages = stack.nmalloc(Long.BYTES * actualImageCount);
            _CHECK_(nvkGetSwapchainImagesKHR(device, swapchain, pSwapchainImageCount, pSwapchainImages), "Failed to get swapchain images");
            long[] imageViews = new long[actualImageCount];
            long pImageView = stack.nmalloc(Long.BYTES);
            for (int i = 0; i < actualImageCount; i++) {
                _CHECK_(nvkCreateImageView(device,
                        VkImageViewCreateInfo(stack)
                        .format(surfaceFormat.colorFormat)
                        .viewType(VK_IMAGE_TYPE_2D)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .layerCount(1)
                                .levelCount(1))
                        .image(memGetLong(pSwapchainImages + (long) Long.BYTES * i)).address(),
                        NULL, pImageView), "Failed to create image view");
                imageViews[i] = memGetLong(pImageView);
            }
            return new Swapchain(swapchain, imageViews, swapchainExtents.x, swapchainExtents.y, surfaceFormat);
        }
    }

    private static long createCommandPool() {
        try (MemoryStack stack = stackPush()) {
            long pCommandPool = stack.nmalloc(Long.BYTES);
            _CHECK_(nvkCreateCommandPool(device, VkCommandPoolCreateInfo(stack).queueFamilyIndex(queueFamily).flags(0).address(), NULL, pCommandPool),
                    "Failed to create command pool");
            return memGetLong(pCommandPool);
        }
    }

    private static long createRasterRenderPass() {
        try (MemoryStack stack = stackPush()) {
            VkRenderPassCreateInfo pCreateInfo = VkRenderPassCreateInfo(stack)
                    .pAttachments(VkAttachmentDescription(stack, 1)
                            .apply(0, d -> d
                                    .format(swapchain.surfaceFormat.colorFormat)
                                    .samples(VK_SAMPLE_COUNT_1_BIT)
                                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                                    .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)))
                    .pSubpasses(VkSubpassDescription(stack, 1)
                            .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                            .colorAttachmentCount(1)
                            .pColorAttachments(VkAttachmentReference(stack, 1)
                                    .attachment(0)
                                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)))
                    .pDependencies(VkSubpassDependency(stack, 1)
                            .srcSubpass(VK_SUBPASS_EXTERNAL)
                            .srcAccessMask(0)
                            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                            .dstSubpass(0)
                            .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                            .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                            .dependencyFlags(VK_DEPENDENCY_BY_REGION_BIT));
            long pRenderPass = stack.nmalloc(Long.BYTES);
            _CHECK_(nvkCreateRenderPass(device, pCreateInfo.address(), NULL, pRenderPass), "Failed to create render pass");
            return memGetLong(pRenderPass);
        }
    }

    private static long[] createFramebuffers() {
        destroyFramebuffers();
        try (MemoryStack stack = stackPush()) {
            LongBuffer pAttachments = stack.mallocLong(1);
            VkFramebufferCreateInfo fci = VkFramebufferCreateInfo(stack)
                    .pAttachments(pAttachments)
                    .width(swapchain.width)
                    .height(swapchain.height)
                    .layers(1)
                    .renderPass(renderPass);
            long[] framebuffers = new long[swapchain.imageViews.length];
            long pFramebuffer = stack.nmalloc(Long.BYTES);
            for (int i = 0; i < swapchain.imageViews.length; i++) {
                pAttachments.put(0, swapchain.imageViews[i]);
                _CHECK_(nvkCreateFramebuffer(device, fci.address(), NULL, pFramebuffer), "Failed to create framebuffer");
                framebuffers[i] = memGetLong(pFramebuffer);
            }
            return framebuffers;
        }
    }

    private static void destroyFramebuffers() {
        if (framebuffers != null) {
            for (long framebuffer : framebuffers)
                nvkDestroyFramebuffer(device, framebuffer, NULL);
            framebuffers = null;
        }
    }

    private static VkCommandBuffer[] createCommandBuffers(long commandPool, int commandBufferCount) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffers = stack.mallocPointer(commandBufferCount);
            _CHECK_(vkAllocateCommandBuffers(device,
                    VkCommandBufferAllocateInfo(stack)
                    .commandBufferCount(commandBufferCount)
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY),
                    pCommandBuffers), "Failed to create command buffers");
            VkCommandBuffer[] cmdBuffers = new VkCommandBuffer[commandBufferCount];
            for (int i = 0; i < commandBufferCount; i++) {
                cmdBuffers[i] = new VkCommandBuffer(pCommandBuffers.get(i), device);
                _CHECK_(nvkBeginCommandBuffer(cmdBuffers[i], VkCommandBufferBeginInfo(stack).address()), "Failed to begin command buffers");
            }
            return cmdBuffers;
        }
    }

    private static VkCommandBuffer[] createRasterCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            VkClearValue.Buffer pClearValues = VkClearValue(stack, 1);
            pClearValues.apply(0, v -> v.color().float32(0, 0.2f).float32(1, 0.4f).float32(2, 0.6f).float32(3, 1));
            VkRenderPassBeginInfo renderPassBeginInfo = VkRenderPassBeginInfo(stack)
                    .renderPass(renderPass)
                    .pClearValues(pClearValues)
                    .renderArea(a -> a.extent().set(swapchain.width, swapchain.height));
            VkCommandBuffer[] cmdBuffers = createCommandBuffers(commandPool, swapchain.imageViews.length);
            for (int i = 0; i < swapchain.imageViews.length; i++) {
                renderPassBeginInfo.framebuffer(framebuffers[i]);
                VkCommandBuffer commandBuffer = cmdBuffers[i];
                nvkCmdBeginRenderPass(commandBuffer, renderPassBeginInfo.address(), VK_SUBPASS_CONTENTS_INLINE);
                vkCmdEndRenderPass(commandBuffer);
                _CHECK_(vkEndCommandBuffer(commandBuffer), "Failed to end command buffer");
            }
            return cmdBuffers;
        }
    }

    private static void createSyncObjects() {
        imageAcquireSemaphores = new long[swapchain.imageViews.length];
        renderCompleteSemaphores = new long[swapchain.imageViews.length];
        renderFences = new long[swapchain.imageViews.length];
        for (int i = 0; i < swapchain.imageViews.length; i++) {
            try (MemoryStack stack = stackPush()) {
                long pSemaphore = stack.nmalloc(Long.BYTES);
                long pCreateInfo = VkSemaphoreCreateInfo(stack).address();
                _CHECK_(nvkCreateSemaphore(device, pCreateInfo, NULL, pSemaphore), "Failed to create image acquire semaphore");
                imageAcquireSemaphores[i] = memGetLong(pSemaphore);
                _CHECK_(nvkCreateSemaphore(device, pCreateInfo, NULL, pSemaphore), "Failed to create raster semaphore");
                renderCompleteSemaphores[i] = memGetLong(pSemaphore);
                LongBuffer pFence = stack.mallocLong(1);
                _CHECK_(vkCreateFence(device, VkFenceCreateInfo(stack).flags(VK_FENCE_CREATE_SIGNALED_BIT), null, pFence), "Failed to create fence");
                renderFences[i] = pFence.get(0);
            }
        }
    }

    private static void destroySyncObjects() {
        for (int i = 0; i < swapchain.imageViews.length; i++) {
            vkDestroySemaphore(device, imageAcquireSemaphores[i], null);
            vkDestroySemaphore(device, renderCompleteSemaphores[i], null);
            vkDestroyFence(device, renderFences[i], null);
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
        framebuffers = createFramebuffers();
        freeCommandBuffers();
        commandBuffers = createRasterCommandBuffers();
    }

    private static void freeCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffers = stack.mallocPointer(commandBuffers.length);
            for (VkCommandBuffer cb : commandBuffers)
                pCommandBuffers.put(cb);
            vkFreeCommandBuffers(device, commandPool, pCommandBuffers.flip());
        }
    }

    private static boolean submitAndPresent(int imageIndex, int idx) {
        try (MemoryStack stack = stackPush()) {
            _CHECK_(vkQueueSubmit(queue,
                    VkSubmitInfo(stack)
                    .pCommandBuffers(stack.pointers(commandBuffers[idx]))
                    .pWaitSemaphores(stack.longs(imageAcquireSemaphores[idx]))
                    .waitSemaphoreCount(1)
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pSignalSemaphores(stack.longs(renderCompleteSemaphores[idx])),
                    renderFences[idx]), "Failed to submit command buffer");
            int result = vkQueuePresentKHR(queue, VkPresentInfoKHR(stack)
                    .pWaitSemaphores(stack.longs(renderCompleteSemaphores[idx]))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(swapchain.swapchain))
                    .pImageIndices(stack.ints(imageIndex)));
            return result != VK_ERROR_OUT_OF_DATE_KHR && result != VK_SUBOPTIMAL_KHR;
        }
    }

    private static boolean acquireSwapchainImage(long pImageIndex, int idx) {
        int res = nvkAcquireNextImageKHR(device, swapchain.swapchain, -1L, imageAcquireSemaphores[idx], VK_NULL_HANDLE, pImageIndex);
        return res != VK_ERROR_OUT_OF_DATE_KHR;
    }

    private static void runWndProcLoop() {
        glfwShowWindow(windowAndCallbacks.window);
        while (!glfwWindowShouldClose(windowAndCallbacks.window)) {
            glfwWaitEvents();
        }
    }

    private static void init() {
        PointerBuffer requiredExtensions = initGlfwAndReturnRequiredExtensions();
        instance = createInstance(requiredExtensions);
        windowAndCallbacks = createWindow();
        surface = createSurface();
        debugCallbackHandle = setupDebugging();
        deviceAndQueueFamilies = createSinglePhysicalDevice();
        queueFamily = deviceAndQueueFamilies.queuesFamilies.findSingleSuitableQueue();
        device = createDevice();
        queue = retrieveQueue();
        swapchain = createSwapchain();
        renderPass = createRasterRenderPass();
        framebuffers = createFramebuffers();
        commandPool = createCommandPool();
        commandBuffers = createRasterCommandBuffers();
        createSyncObjects();
    }

    private static void runOnRenderThread() {
        try (MemoryStack stack = stackPush()) {
            long pImageIndex = stack.nmalloc(Integer.BYTES);
            int idx = 0;
            boolean needRecreate = false;
            while (!glfwWindowShouldClose(windowAndCallbacks.window)) {
                updateFramebufferSize();
                if (!isWindowRenderable()) {
                    continue;
                }
                if (windowSizeChanged()) {
                    needRecreate = true;
                }
                if (needRecreate) {
                    vkDeviceWaitIdle(device);
                    recreateSwapchainAndDependentResources();
                    idx = 0;
                }
                vkWaitForFences(device, renderFences[idx], true, Long.MAX_VALUE);
                vkResetFences(device, renderFences[idx]);
                if (!acquireSwapchainImage(pImageIndex, idx)) {
                    needRecreate = true;
                    continue;
                }
                needRecreate = !submitAndPresent(memGetInt(pImageIndex), idx);
                idx = (idx + 1) % swapchain.imageViews.length;
            }
        }
    }

    private static void destroy() {
        _CHECK_(vkDeviceWaitIdle(device), "Failed to wait for device idle");
        destroySyncObjects();
        vkDestroyCommandPool(device, commandPool, null);
        destroyFramebuffers();
        vkDestroyRenderPass(device, renderPass, null);
        swapchain.free();
        vkDestroyDevice(device, null);
        if (DEBUG) {
            debugCallbackHandle.free();
        }
        vkDestroySurfaceKHR(instance, surface, null);
        windowAndCallbacks.free();
        vkDestroyInstance(instance, null);
    }

    public static void main(String[] args) throws InterruptedException {
        init();
        Thread updateAndRenderThread = new Thread(ClearScreenDemo::runOnRenderThread);
        updateAndRenderThread.start();
        runWndProcLoop();
        updateAndRenderThread.join();
        destroy();
    }

}
