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
import static org.lwjgl.vulkan.EXTDebugReport.*;
import static org.lwjgl.vulkan.KHRDedicatedAllocation.*;
import static org.lwjgl.vulkan.KHRGetMemoryRequirements2.*;
import static org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

import java.io.IOException;
import java.nio.*;
import java.util.*;

import org.joml.Vector2i;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/**
 * Renders a simple cornflower blue image on a GLFW window with Vulkan.
 * 
 * @author Kai Burjack
 */
public class ClearScreenDemo {
    private static boolean debug = System.getProperty("NDEBUG") == null;

    static {
        if (debug) {
            System.setProperty("org.lwjgl.util.Debug", "true");
            System.setProperty("org.lwjgl.util.NoChecks", "false");
            System.setProperty("org.lwjgl.util.DebugLoader", "true");
            System.setProperty("org.lwjgl.util.DebugAllocator", "true");
            System.setProperty("org.lwjgl.util.DebugStack", "true");
        }
    }

    private static int INITIAL_WINDOW_WIDTH = 800;
    private static int INITIAL_WINDOW_HEIGHT = 600;
    private static VkInstance instance;
    private static WindowAndCallbacks windowAndCallbacks;
    private static long surface;
    private static DebugCallbackAndHandle debugCallbackHandle;
    private static DeviceAndQueueFamilies deviceAndQueueFamilies;
    private static int queueFamily;
    private static VkDevice device;
    private static VkQueue queue;
    private static Swapchain swapchain;
    private static Long commandPool;
    private static long renderPass;
    private static long[] framebuffers;
    private static VkCommandBuffer[] rasterCommandBuffers;
    private static long[] imageAcquireSemaphores;
    private static long[] renderCompleteSemaphores;
    private static long[] renderFences;
    private static boolean[] keydown = new boolean[GLFW_KEY_LAST + 1];
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
            assertAvailable(pProperties, VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME);
            PointerBuffer extensions = stack.mallocPointer(2 + 1);
            extensions.put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME))
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
            return isDiscrete && !queuesFamilies.graphicsFamilies.isEmpty() && !queuesFamilies.presentFamilies.isEmpty();
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
        ColorFormatAndSpace surfaceFormat;

        Swapchain(long swapchain, long[] images, long[] imageViews, int width, int height, ColorFormatAndSpace surfaceFormat) {
            this.swapchain = swapchain;
            this.images = images;
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
            long[] imageViews = new long[pImageCount.get(0)];
            pSwapchainImages.get(images, 0, images.length);
            LongBuffer pImageView = stack.mallocLong(1);
            for (int i = 0; i < pImageCount.get(0); i++) {
                _CHECK_(vkCreateImageView(device, VkImageViewCreateInfo(stack)
                        .format(surfaceFormat.colorFormat)
                        .viewType(VK_IMAGE_TYPE_2D)
                        .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1).levelCount(1))
                        .image(images[i]), null, pImageView), "Failed to create image view");
                imageViews[i] = pImageView.get(0);
            }
            return new Swapchain(pSwapChain.get(0), images, imageViews, swapchainExtents.x, swapchainExtents.y, surfaceFormat);
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
        List<Integer> presentFamilies = new ArrayList<>();

        int findSingleSuitableQueue() {
            return graphicsFamilies
                    .stream()
                    .filter(i -> presentFamilies.contains(i)).findAny().orElseThrow(
                                    () -> new AssertionError("No suitable queue found"));
        }
    }

    private static void freeCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pCommandBuffers = stack.mallocPointer(
                    rasterCommandBuffers.length);
            for (VkCommandBuffer cb : rasterCommandBuffers)
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

    private static long createRasterRenderPass() {
        try (MemoryStack stack = stackPush()) {
            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo(stack)
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
                                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)));
            LongBuffer pRenderPass = stack.mallocLong(1);
            _CHECK_(vkCreateRenderPass(device, renderPassInfo, null, pRenderPass), "Failed to create render pass");
            return pRenderPass.get(0);
        }
    }

    private static VkCommandBuffer[] createRasterCommandBuffers() {
        try (MemoryStack stack = stackPush()) {
            VkClearValue.Buffer clearValues = VkClearValue(stack, 1);
            clearValues.apply(0, v -> v.color().float32(0, 0.5f).float32(1, 0.7f).float32(2, 0.9f).float32(3, 1));
            VkRenderPassBeginInfo renderPassBeginInfo = VkRenderPassBeginInfo(stack).renderPass(renderPass)
                    .pClearValues(clearValues)
                    .renderArea(a -> a.extent().set(swapchain.width, swapchain.height));
            VkCommandBuffer[] cmdBuffers = createCommandBuffers(commandPool, swapchain.images.length);
            for (int i = 0; i < swapchain.images.length; i++) {
                renderPassBeginInfo.framebuffer(framebuffers[i]);
                VkCommandBuffer cmdBuffer = cmdBuffers[i];
                vkCmdBeginRenderPass(cmdBuffer, renderPassBeginInfo, VK_SUBPASS_CONTENTS_INLINE);
                vkCmdEndRenderPass(cmdBuffer);
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
            LongBuffer pAttachments = stack.mallocLong(1);
            VkFramebufferCreateInfo fci = VkFramebufferCreateInfo(stack)
                    .pAttachments(pAttachments)
                    .width(swapchain.width)
                    .height(swapchain.height)
                    .layers(1)
                    .renderPass(renderPass);
            long[] framebuffers = new long[swapchain.images.length];
            LongBuffer pFramebuffer = stack.mallocLong(1);
            for (int i = 0; i < swapchain.images.length; i++) {
                pAttachments.put(0, swapchain.imageViews[i]);
                _CHECK_(vkCreateFramebuffer(device, fci, null, pFramebuffer), "Failed to create framebuffer");
                framebuffers[i] = pFramebuffer.get(0);
            }
            return framebuffers;
        }
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

        WindowAndCallbacks(long window, GLFWKeyCallback keyCallback) {
            this.window = window;
            this.keyCallback = keyCallback;
        }
        void free() {
            glfwDestroyWindow(window);
            keyCallback.free();
        }
    }

    private static WindowAndCallbacks createWindow() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        long window = glfwCreateWindow(INITIAL_WINDOW_WIDTH, INITIAL_WINDOW_HEIGHT, "Clear Screen Demo", NULL, NULL);
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
        return new WindowAndCallbacks(window, keyCallback);
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
        queue = retrieveQueue();
        swapchain = createSwapChain();
        commandPool = createCommandPool(0);
        renderPass = createRasterRenderPass();
        framebuffers = createFramebuffers();
        rasterCommandBuffers = createRasterCommandBuffers();
        createSyncObjects();
    }

    private static void cleanup() {
        _CHECK_(vkDeviceWaitIdle(device), "Failed to wait for device idle");
        for (int i = 0; i < swapchain.images.length; i++) {
            vkDestroySemaphore(device, imageAcquireSemaphores[i], null);
            vkDestroySemaphore(device, renderCompleteSemaphores[i], null);
            vkDestroyFence(device, renderFences[i], null);
        }
        freeCommandBuffers();
        swapchain.free();
        for (long framebuffer : framebuffers)
            vkDestroyFramebuffer(device, framebuffer, null);
        vkDestroyRenderPass(device, renderPass, null);
        vkDestroyCommandPool(device, commandPool, null);
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
        framebuffers = createFramebuffers();
        freeCommandBuffers();
        rasterCommandBuffers = createRasterCommandBuffers();
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

    private static void submitAndPresent(int imageIndex, int idx) {
        try (MemoryStack stack = stackPush()) {
            _CHECK_(vkQueueSubmit(queue, VkSubmitInfo(stack)
                    .pCommandBuffers(stack.pointers(rasterCommandBuffers[idx]))
                    .pWaitSemaphores(stack.longs(imageAcquireSemaphores[idx]))
                    .waitSemaphoreCount(1)
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
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
            int idx = 0;
            while (!glfwWindowShouldClose(windowAndCallbacks.window)) {
                glfwPollEvents();
                updateFramebufferSize();
                if (!isWindowRenderable()) {
                    continue;
                }
                if (windowSizeChanged()) {
                    vkDeviceWaitIdle(device);
                    recreateOnResize();
                    idx = 0;
                }
                vkWaitForFences(device, renderFences[idx], true, Long.MAX_VALUE);
                vkResetFences(device, renderFences[idx]);
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
