package org.lwjgl.demo.vulkan;

import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRDisplaySwapchain.*;
import static org.lwjgl.vulkan.EXTDebugReport.*;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.IntBuffer;

import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

public class VkUtil {

    /**
     * Remove if added to spec.
     */
    public static final int VK_FLAGS_NONE = 0;

    /**
     * This is just -1L, but it is nicer as a symbolic constant.
     */
    public static final long UINT64_MAX = 0xFFFFFFFFFFFFFFFFL;

    public static int VK_MAKE_VERSION(int major, int minor, int patch) {
        return (major << 22) | (minor << 12) | (patch << 0);
    }

    /**
     * Taken from GLFW's sources.
     * <p>
     * See: <a href="https://github.com/glfw/glfw/blob/master/src/vulkan.c#L133">vulkan.c</a>
     */
    public static String translateVulkanError(int succ) {
        switch (succ) {
        case VK_SUCCESS:
            return "Success";
        case VK_NOT_READY:
            return "A fence or query has not yet completed";
        case VK_TIMEOUT:
            return "A wait operation has not completed in the specified time";
        case VK_EVENT_SET:
            return "An event is signaled";
        case VK_EVENT_RESET:
            return "An event is unsignaled";
        case VK_INCOMPLETE:
            return "A return array was too small for the result";
        case VK_ERROR_OUT_OF_HOST_MEMORY:
            return "A host memory allocation has failed";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY:
            return "A device memory allocation has failed";
        case VK_ERROR_INITIALIZATION_FAILED:
            return "Initialization of an object could not be completed for implementation-specific reasons";
        case VK_ERROR_DEVICE_LOST:
            return "The logical or physical device has been lost";
        case VK_ERROR_MEMORY_MAP_FAILED:
            return "Mapping of a memory object has failed";
        case VK_ERROR_LAYER_NOT_PRESENT:
            return "A requested layer is not present or could not be loaded";
        case VK_ERROR_EXTENSION_NOT_PRESENT:
            return "A requested extension is not supported";
        case VK_ERROR_FEATURE_NOT_PRESENT:
            return "A requested feature is not supported";
        case VK_ERROR_INCOMPATIBLE_DRIVER:
            return "The requested version of Vulkan is not supported by the driver or is otherwise incompatible";
        case VK_ERROR_TOO_MANY_OBJECTS:
            return "Too many objects of the type have already been created";
        case VK_ERROR_FORMAT_NOT_SUPPORTED:
            return "A requested format is not supported on this device";
        case VK_ERROR_SURFACE_LOST_KHR:
            return "A surface is no longer available";
        case VK_SUBOPTIMAL_KHR:
            return "A swapchain no longer matches the surface properties exactly, but can still be used";
        case VK_ERROR_OUT_OF_DATE_KHR:
            return "A surface has changed in such a way that it is no longer compatible with the swapchain";
        case VK_ERROR_INCOMPATIBLE_DISPLAY_KHR:
            return "The display used by a swapchain does not use the same presentable image layout";
        case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR:
            return "The requested window is already connected to a VkSurfaceKHR, or to some other non-Vulkan API";
        case VK_ERROR_VALIDATION_FAILED_EXT:
            return "A validation layer found an error";
        /* Vendor-specific error codes */
        // Nvidia
        case -1000013000: // Some illegal arguments passed to function (happened with vkCreateSwapchainKHR when old swapchain was wrong)
            return "Invalid/wrong arguments specified to call";
        default:
            return "ERROR: UNKNOWN VULKAN ERROR [" + succ + "]";
        }
    }

    public static boolean getMemoryType(VkPhysicalDeviceMemoryProperties deviceMemoryProperties, int typeBits, int properties, IntBuffer typeIndex) {
        int tb = typeBits;
        for (int i = 0; i < 32; i++) {
            if ((tb & 1) == 1) {
                if ((deviceMemoryProperties.memoryTypes(i).propertyFlags() & properties) == properties) {
                    typeIndex.put(0, i);
                    return true;
                }
            }
            tb >>= 1;
        }
        return false;
    }

}
