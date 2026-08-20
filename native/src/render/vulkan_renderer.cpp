#include "snake/render/vulkan_renderer.hpp"

#include <android/log.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <utility>
#include <vector>

namespace snake::render {
namespace {

constexpr char kLogTag[] = "SnakeVulkan";
constexpr std::size_t kFloatsPerVertex = 9;
constexpr std::size_t kFramesInFlight = 2;
constexpr std::size_t kVertexBatchBytes = 4U * 1024U * 1024U;
constexpr std::size_t kVertexBufferBytes = kVertexBatchBytes * kFramesInFlight;

const std::uint32_t kArenaVertexShader[] =
#include "arena.vert.inc"
    ;

const std::uint32_t kArenaFragmentShader[] =
#include "arena.frag.inc"
    ;

void log_error(const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message);
}

bool has_device_extension(VkPhysicalDevice device, const char* required_extension) {
    std::uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) {
        return false;
    }
    std::vector<VkExtensionProperties> extensions(count);
    if (count > 0 &&
        vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data()) != VK_SUCCESS) {
        return false;
    }
    return std::any_of(extensions.begin(), extensions.end(), [required_extension](const auto& extension) {
        return std::strcmp(extension.extensionName, required_extension) == 0;
    });
}

bool has_graphics_queue(VkPhysicalDevice device) {
    std::uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(device, &count, nullptr);
    std::vector<VkQueueFamilyProperties> properties(count);
    if (count > 0) {
        vkGetPhysicalDeviceQueueFamilyProperties(device, &count, properties.data());
    }
    return std::any_of(properties.begin(), properties.end(), [](const auto& property) {
        return property.queueCount > 0 && (property.queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0;
    });
}

std::pair<int, std::string> probe_vulkan_device() {
    VkApplicationInfo application_info{};
    application_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application_info.pApplicationName = "Snake Evolution Arena Probe";
    application_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    application_info.pEngineName = "Snake Engine";
    application_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    application_info.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &application_info;

    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&create_info, nullptr, &instance) != VK_SUCCESS) {
        return {0, {}};
    }

    std::uint32_t count = 0;
    const VkResult enumerate_result = vkEnumeratePhysicalDevices(instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    if (enumerate_result == VK_SUCCESS && count > 0) {
        vkEnumeratePhysicalDevices(instance, &count, devices.data());
    }

    int best_score = 0;
    std::string best_name;
    for (const VkPhysicalDevice device : devices) {
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(device, &properties);
        if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU ||
            !has_graphics_queue(device) ||
            !has_device_extension(device, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
            continue;
        }
        int score = 1;
        if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) score = 4;
        if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) score = 3;
        if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU) score = 2;
        if (score > best_score) {
            best_score = score;
            best_name = properties.deviceName;
        }
    }
    vkDestroyInstance(instance, nullptr);
    return {best_score > 0 ? 1 : 0, best_name};
}

VkCompositeAlphaFlagBitsKHR choose_composite_alpha(const VkSurfaceCapabilitiesKHR& capabilities) {
    constexpr std::array<VkCompositeAlphaFlagBitsKHR, 4> candidates{
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
        VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
    };
    for (const auto candidate : candidates) {
        if ((capabilities.supportedCompositeAlpha & candidate) != 0) return candidate;
    }
    return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
}

}  // namespace

class VulkanRenderer::Impl {
public:
    Impl(ANativeWindow* window, const int requested_width, const int requested_height)
        : window_(window),
          requested_width_(requested_width),
          requested_height_(requested_height) {}

    ~Impl() {
        cleanup();
    }

    bool initialize() {
        if (window_ == nullptr) {
            log_error("A valid ANativeWindow is required");
            return false;
        }
        return create_instance() && create_surface() && select_device() && create_device() &&
            create_command_resources() && create_frame_sync() && create_vertex_buffer() &&
            create_swapchain_resources();
    }

    void resize(const int width, const int height) {
        requested_width_ = width;
        requested_height_ = height;
        resize_pending_ = true;
    }

    bool render(
        const float* vertices,
        const std::size_t vertex_count,
        const float clear_red,
        const float clear_green,
        const float clear_blue) {
        if (device_ == VK_NULL_HANDLE || vertices == nullptr || vertex_count == 0) return true;
        const std::size_t bytes = vertex_count * kFloatsPerVertex * sizeof(float);
        if (bytes > kVertexBatchBytes || mapped_vertex_memory_ == nullptr) {
            log_error("Vulkan vertex batch exceeded the persistent buffer");
            return false;
        }
        if (resize_pending_) {
            if (!recreate_swapchain()) return false;
            resize_pending_ = false;
        }
        if (swapchain_ == VK_NULL_HANDLE || extent_.width == 0 || extent_.height == 0) return true;

        FrameResources& frame = frames_[current_frame_];
        if (vkWaitForFences(device_, 1, &frame.in_flight, VK_TRUE, UINT64_MAX) != VK_SUCCESS) {
            log_error("Failed waiting for a Vulkan frame fence");
            return false;
        }

        std::uint32_t image_index = 0;
        const VkResult acquire_result = vkAcquireNextImageKHR(
            device_, swapchain_, UINT64_MAX, frame.image_available, VK_NULL_HANDLE, &image_index);
        if (acquire_result == VK_ERROR_OUT_OF_DATE_KHR) return recreate_swapchain();
        if (acquire_result != VK_SUCCESS && acquire_result != VK_SUBOPTIMAL_KHR) {
            log_error("Failed to acquire a Vulkan swapchain image");
            return false;
        }

        const VkDeviceSize vertex_offset = current_frame_ * kVertexBatchBytes;
        auto* mapped_bytes = static_cast<std::uint8_t*>(mapped_vertex_memory_);
        std::memcpy(mapped_bytes + vertex_offset, vertices, bytes);
        if (!vertex_memory_coherent_) {
            VkMappedMemoryRange range{};
            range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
            range.memory = vertex_memory_;
            range.offset = 0;
            range.size = VK_WHOLE_SIZE;
            if (vkFlushMappedMemoryRanges(device_, 1, &range) != VK_SUCCESS) {
                log_error("Failed to flush Vulkan vertex memory");
                return false;
            }
        }

        if (vkResetFences(device_, 1, &frame.in_flight) != VK_SUCCESS ||
            vkResetCommandBuffer(frame.command_buffer, 0) != VK_SUCCESS ||
            !record_commands(
                frame.command_buffer,
                image_index,
                static_cast<std::uint32_t>(vertex_count),
                vertex_offset,
                clear_red,
                clear_green,
                clear_blue)) {
            log_error("Failed to prepare a Vulkan command buffer");
            return false;
        }

        const VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit_info{};
        submit_info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit_info.waitSemaphoreCount = 1;
        submit_info.pWaitSemaphores = &frame.image_available;
        submit_info.pWaitDstStageMask = &wait_stage;
        submit_info.commandBufferCount = 1;
        submit_info.pCommandBuffers = &frame.command_buffer;
        submit_info.signalSemaphoreCount = 1;
        submit_info.pSignalSemaphores = &frame.render_finished;
        if (vkQueueSubmit(graphics_queue_, 1, &submit_info, frame.in_flight) != VK_SUCCESS) {
            log_error("Failed to submit a Vulkan frame");
            return false;
        }

        VkPresentInfoKHR present_info{};
        present_info.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        present_info.waitSemaphoreCount = 1;
        present_info.pWaitSemaphores = &frame.render_finished;
        present_info.swapchainCount = 1;
        present_info.pSwapchains = &swapchain_;
        present_info.pImageIndices = &image_index;
        const VkResult present_result = vkQueuePresentKHR(graphics_queue_, &present_info);
        current_frame_ = (current_frame_ + 1U) % kFramesInFlight;
        if (present_result == VK_ERROR_OUT_OF_DATE_KHR || present_result == VK_SUBOPTIMAL_KHR) {
            return recreate_swapchain();
        }
        if (present_result != VK_SUCCESS) {
            log_error("Failed to present a Vulkan frame");
            return false;
        }
        return true;
    }

private:
    struct FrameResources {
        VkSemaphore image_available = VK_NULL_HANDLE;
        VkSemaphore render_finished = VK_NULL_HANDLE;
        VkFence in_flight = VK_NULL_HANDLE;
        VkCommandBuffer command_buffer = VK_NULL_HANDLE;
    };

    bool create_instance() {
        VkApplicationInfo application_info{};
        application_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        application_info.pApplicationName = "Snake Evolution Arena";
        application_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
        application_info.pEngineName = "Snake Engine";
        application_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
        application_info.apiVersion = VK_API_VERSION_1_0;

        const std::array<const char*, 2> extensions{
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
        };
        VkInstanceCreateInfo create_info{};
        create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        create_info.pApplicationInfo = &application_info;
        create_info.enabledExtensionCount = static_cast<std::uint32_t>(extensions.size());
        create_info.ppEnabledExtensionNames = extensions.data();
        if (vkCreateInstance(&create_info, nullptr, &instance_) != VK_SUCCESS) {
            log_error("Failed to create a Vulkan instance");
            return false;
        }
        return true;
    }

    bool create_surface() {
        VkAndroidSurfaceCreateInfoKHR create_info{};
        create_info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        create_info.window = window_;
        if (vkCreateAndroidSurfaceKHR(instance_, &create_info, nullptr, &surface_) != VK_SUCCESS) {
            log_error("Failed to create the Vulkan Android surface");
            return false;
        }
        return true;
    }

    bool select_device() {
        std::uint32_t device_count = 0;
        if (vkEnumeratePhysicalDevices(instance_, &device_count, nullptr) != VK_SUCCESS || device_count == 0) {
            log_error("No Vulkan physical device was found");
            return false;
        }
        std::vector<VkPhysicalDevice> devices(device_count);
        if (vkEnumeratePhysicalDevices(instance_, &device_count, devices.data()) != VK_SUCCESS) return false;

        int best_score = -1;
        for (const VkPhysicalDevice device : devices) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(device, &properties);
            if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU ||
                !has_device_extension(device, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                continue;
            }
            std::uint32_t queue_count = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(device, &queue_count, nullptr);
            std::vector<VkQueueFamilyProperties> queues(queue_count);
            vkGetPhysicalDeviceQueueFamilyProperties(device, &queue_count, queues.data());
            for (std::uint32_t index = 0; index < queue_count; ++index) {
                VkBool32 present_supported = VK_FALSE;
                vkGetPhysicalDeviceSurfaceSupportKHR(device, index, surface_, &present_supported);
                if (queues[index].queueCount == 0 ||
                    (queues[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0 ||
                    present_supported != VK_TRUE) {
                    continue;
                }
                int score = 1;
                if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) score = 4;
                if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) score = 3;
                if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU) score = 2;
                if (score > best_score) {
                    best_score = score;
                    physical_device_ = device;
                    queue_family_index_ = index;
                }
            }
        }
        if (physical_device_ == VK_NULL_HANDLE) {
            log_error("No hardware Vulkan queue supports this Android surface");
            return false;
        }
        return true;
    }

    bool create_device() {
        constexpr float queue_priority = 1f;
        VkDeviceQueueCreateInfo queue_info{};
        queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queue_info.queueFamilyIndex = queue_family_index_;
        queue_info.queueCount = 1;
        queue_info.pQueuePriorities = &queue_priority;

        const char* extension = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
        VkDeviceCreateInfo create_info{};
        create_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        create_info.queueCreateInfoCount = 1;
        create_info.pQueueCreateInfos = &queue_info;
        create_info.enabledExtensionCount = 1;
        create_info.ppEnabledExtensionNames = &extension;
        if (vkCreateDevice(physical_device_, &create_info, nullptr, &device_) != VK_SUCCESS) {
            log_error("Failed to create the Vulkan logical device");
            return false;
        }
        vkGetDeviceQueue(device_, queue_family_index_, 0, &graphics_queue_);
        return graphics_queue_ != VK_NULL_HANDLE;
    }

    bool create_command_resources() {
        VkCommandPoolCreateInfo pool_info{};
        pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pool_info.queueFamilyIndex = queue_family_index_;
        if (vkCreateCommandPool(device_, &pool_info, nullptr, &command_pool_) != VK_SUCCESS) {
            log_error("Failed to create the Vulkan command pool");
            return false;
        }
        std::array<VkCommandBuffer, kFramesInFlight> command_buffers{};
        VkCommandBufferAllocateInfo allocate_info{};
        allocate_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocate_info.commandPool = command_pool_;
        allocate_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocate_info.commandBufferCount = static_cast<std::uint32_t>(command_buffers.size());
        if (vkAllocateCommandBuffers(device_, &allocate_info, command_buffers.data()) != VK_SUCCESS) {
            log_error("Failed to allocate Vulkan command buffers");
            return false;
        }
        for (std::size_t index = 0; index < frames_.size(); ++index) {
            frames_[index].command_buffer = command_buffers[index];
        }
        return true;
    }

    bool create_frame_sync() {
        VkSemaphoreCreateInfo semaphore_info{};
        semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        VkFenceCreateInfo fence_info{};
        fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        for (auto& frame : frames_) {
            if (vkCreateSemaphore(device_, &semaphore_info, nullptr, &frame.image_available) != VK_SUCCESS ||
                vkCreateSemaphore(device_, &semaphore_info, nullptr, &frame.render_finished) != VK_SUCCESS ||
                vkCreateFence(device_, &fence_info, nullptr, &frame.in_flight) != VK_SUCCESS) {
                log_error("Failed to create Vulkan frame synchronization");
                return false;
            }
        }
        return true;
    }

    bool create_vertex_buffer() {
        VkBufferCreateInfo buffer_info{};
        buffer_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        buffer_info.size = kVertexBufferBytes;
        buffer_info.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(device_, &buffer_info, nullptr, &vertex_buffer_) != VK_SUCCESS) {
            log_error("Failed to create the Vulkan vertex buffer");
            return false;
        }

        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(device_, vertex_buffer_, &requirements);
        VkPhysicalDeviceMemoryProperties memory_properties{};
        vkGetPhysicalDeviceMemoryProperties(physical_device_, &memory_properties);
        std::uint32_t selected_type = std::numeric_limits<std::uint32_t>::max();
        for (std::uint32_t index = 0; index < memory_properties.memoryTypeCount; ++index) {
            if ((requirements.memoryTypeBits & (1U << index)) == 0) continue;
            const VkMemoryPropertyFlags flags = memory_properties.memoryTypes[index].propertyFlags;
            if ((flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) == 0) continue;
            selected_type = index;
            vertex_memory_coherent_ = (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
            if (vertex_memory_coherent_) break;
        }
        if (selected_type == std::numeric_limits<std::uint32_t>::max()) {
            log_error("No host-visible Vulkan memory is available");
            return false;
        }

        VkMemoryAllocateInfo allocation_info{};
        allocation_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocation_info.allocationSize = requirements.size;
        allocation_info.memoryTypeIndex = selected_type;
        if (vkAllocateMemory(device_, &allocation_info, nullptr, &vertex_memory_) != VK_SUCCESS ||
            vkBindBufferMemory(device_, vertex_buffer_, vertex_memory_, 0) != VK_SUCCESS ||
            vkMapMemory(device_, vertex_memory_, 0, VK_WHOLE_SIZE, 0, &mapped_vertex_memory_) != VK_SUCCESS) {
            log_error("Failed to allocate persistent Vulkan vertex memory");
            return false;
        }
        return true;
    }

    bool create_swapchain_resources() {
        VkSurfaceCapabilitiesKHR capabilities{};
        if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical_device_, surface_, &capabilities) != VK_SUCCESS) {
            log_error("Failed to query Vulkan surface capabilities");
            return false;
        }

        std::uint32_t format_count = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical_device_, surface_, &format_count, nullptr);
        if (format_count == 0) return false;
        std::vector<VkSurfaceFormatKHR> formats(format_count);
        vkGetPhysicalDeviceSurfaceFormatsKHR(physical_device_, surface_, &format_count, formats.data());
        surface_format_ = formats.front();
        for (const auto& format : formats) {
            if ((format.format == VK_FORMAT_R8G8B8A8_UNORM || format.format == VK_FORMAT_B8G8R8A8_UNORM) &&
                format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                surface_format_ = format;
                break;
            }
        }

        std::uint32_t present_mode_count = 0;
        vkGetPhysicalDeviceSurfacePresentModesKHR(physical_device_, surface_, &present_mode_count, nullptr);
        std::vector<VkPresentModeKHR> present_modes(present_mode_count);
        if (present_mode_count > 0) {
            vkGetPhysicalDeviceSurfacePresentModesKHR(
                physical_device_, surface_, &present_mode_count, present_modes.data());
        }
        VkPresentModeKHR present_mode = VK_PRESENT_MODE_FIFO_KHR;
        if (std::find(present_modes.begin(), present_modes.end(), VK_PRESENT_MODE_MAILBOX_KHR) !=
            present_modes.end()) {
            present_mode = VK_PRESENT_MODE_MAILBOX_KHR;
        }

        if (capabilities.currentExtent.width != std::numeric_limits<std::uint32_t>::max()) {
            extent_ = capabilities.currentExtent;
        } else {
            const std::uint32_t requested_width = static_cast<std::uint32_t>(
                std::max(1, requested_width_ > 0 ? requested_width_ : ANativeWindow_getWidth(window_)));
            const std::uint32_t requested_height = static_cast<std::uint32_t>(
                std::max(1, requested_height_ > 0 ? requested_height_ : ANativeWindow_getHeight(window_)));
            extent_.width = std::clamp(
                requested_width, capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
            extent_.height = std::clamp(
                requested_height, capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
        }

        std::uint32_t image_count = capabilities.minImageCount + 1;
        if (capabilities.maxImageCount > 0) image_count = std::min(image_count, capabilities.maxImageCount);
        VkSwapchainCreateInfoKHR swapchain_info{};
        swapchain_info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        swapchain_info.surface = surface_;
        swapchain_info.minImageCount = image_count;
        swapchain_info.imageFormat = surface_format_.format;
        swapchain_info.imageColorSpace = surface_format_.colorSpace;
        swapchain_info.imageExtent = extent_;
        swapchain_info.imageArrayLayers = 1;
        swapchain_info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        swapchain_info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        swapchain_info.preTransform = capabilities.currentTransform;
        swapchain_info.compositeAlpha = choose_composite_alpha(capabilities);
        swapchain_info.presentMode = present_mode;
        swapchain_info.clipped = VK_TRUE;
        if (vkCreateSwapchainKHR(device_, &swapchain_info, nullptr, &swapchain_) != VK_SUCCESS) {
            log_error("Failed to create the Vulkan swapchain");
            return false;
        }

        std::uint32_t swapchain_image_count = 0;
        vkGetSwapchainImagesKHR(device_, swapchain_, &swapchain_image_count, nullptr);
        swapchain_images_.resize(swapchain_image_count);
        vkGetSwapchainImagesKHR(device_, swapchain_, &swapchain_image_count, swapchain_images_.data());
        if (!create_image_views() || !create_render_pass() || !create_pipeline()) return false;
        return create_framebuffers();
    }

    bool create_image_views() {
        swapchain_image_views_.resize(swapchain_images_.size(), VK_NULL_HANDLE);
        for (std::size_t index = 0; index < swapchain_images_.size(); ++index) {
            VkImageViewCreateInfo view_info{};
            view_info.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            view_info.image = swapchain_images_[index];
            view_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
            view_info.format = surface_format_.format;
            view_info.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
            view_info.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
            view_info.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
            view_info.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
            view_info.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            view_info.subresourceRange.levelCount = 1;
            view_info.subresourceRange.layerCount = 1;
            if (vkCreateImageView(device_, &view_info, nullptr, &swapchain_image_views_[index]) != VK_SUCCESS) {
                log_error("Failed to create a Vulkan swapchain image view");
                return false;
            }
        }
        return true;
    }

    bool create_render_pass() {
        VkAttachmentDescription color_attachment{};
        color_attachment.format = surface_format_.format;
        color_attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        color_attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        color_attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        color_attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        color_attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        color_attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        color_attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        VkAttachmentReference color_reference{};
        color_reference.attachment = 0;
        color_reference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &color_reference;
        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        VkRenderPassCreateInfo render_pass_info{};
        render_pass_info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        render_pass_info.attachmentCount = 1;
        render_pass_info.pAttachments = &color_attachment;
        render_pass_info.subpassCount = 1;
        render_pass_info.pSubpasses = &subpass;
        render_pass_info.dependencyCount = 1;
        render_pass_info.pDependencies = &dependency;
        if (vkCreateRenderPass(device_, &render_pass_info, nullptr, &render_pass_) != VK_SUCCESS) {
            log_error("Failed to create the Vulkan render pass");
            return false;
        }
        return true;
    }

    bool create_pipeline() {
        VkShaderModule vertex_module = create_shader_module(kArenaVertexShader, sizeof(kArenaVertexShader));
        VkShaderModule fragment_module = create_shader_module(kArenaFragmentShader, sizeof(kArenaFragmentShader));
        if (vertex_module == VK_NULL_HANDLE || fragment_module == VK_NULL_HANDLE) {
            if (vertex_module != VK_NULL_HANDLE) vkDestroyShaderModule(device_, vertex_module, nullptr);
            if (fragment_module != VK_NULL_HANDLE) vkDestroyShaderModule(device_, fragment_module, nullptr);
            return false;
        }

        std::array<VkPipelineShaderStageCreateInfo, 2> shader_stages{};
        shader_stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        shader_stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        shader_stages[0].module = vertex_module;
        shader_stages[0].pName = "main";
        shader_stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        shader_stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        shader_stages[1].module = fragment_module;
        shader_stages[1].pName = "main";

        VkVertexInputBindingDescription binding{};
        binding.binding = 0;
        binding.stride = static_cast<std::uint32_t>(kFloatsPerVertex * sizeof(float));
        binding.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;
        std::array<VkVertexInputAttributeDescription, 4> attributes{};
        attributes[0] = {0, 0, VK_FORMAT_R32G32_SFLOAT, 0};
        attributes[1] = {1, 0, VK_FORMAT_R32G32_SFLOAT, static_cast<std::uint32_t>(2U * sizeof(float))};
        attributes[2] = {
            2, 0, VK_FORMAT_R32G32B32A32_SFLOAT, static_cast<std::uint32_t>(4U * sizeof(float))};
        attributes[3] = {3, 0, VK_FORMAT_R32_SFLOAT, static_cast<std::uint32_t>(8U * sizeof(float))};
        VkPipelineVertexInputStateCreateInfo vertex_input{};
        vertex_input.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
        vertex_input.vertexBindingDescriptionCount = 1;
        vertex_input.pVertexBindingDescriptions = &binding;
        vertex_input.vertexAttributeDescriptionCount = static_cast<std::uint32_t>(attributes.size());
        vertex_input.pVertexAttributeDescriptions = attributes.data();
        VkPipelineInputAssemblyStateCreateInfo input_assembly{};
        input_assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
        input_assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo viewport_state{};
        viewport_state.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
        viewport_state.viewportCount = 1;
        viewport_state.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo rasterizer{};
        rasterizer.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
        rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
        rasterizer.cullMode = VK_CULL_MODE_NONE;
        rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterizer.lineWidth = 1f;
        VkPipelineMultisampleStateCreateInfo multisampling{};
        multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState blend_attachment{};
        blend_attachment.blendEnable = VK_TRUE;
        blend_attachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        blend_attachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blend_attachment.colorBlendOp = VK_BLEND_OP_ADD;
        blend_attachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        blend_attachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        blend_attachment.alphaBlendOp = VK_BLEND_OP_ADD;
        blend_attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo color_blending{};
        color_blending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
        color_blending.attachmentCount = 1;
        color_blending.pAttachments = &blend_attachment;
        const std::array<VkDynamicState, 2> dynamic_states{VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dynamic_state{};
        dynamic_state.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
        dynamic_state.dynamicStateCount = static_cast<std::uint32_t>(dynamic_states.size());
        dynamic_state.pDynamicStates = dynamic_states.data();
        VkPipelineLayoutCreateInfo layout_info{};
        layout_info.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        if (vkCreatePipelineLayout(device_, &layout_info, nullptr, &pipeline_layout_) != VK_SUCCESS) {
            vkDestroyShaderModule(device_, vertex_module, nullptr);
            vkDestroyShaderModule(device_, fragment_module, nullptr);
            return false;
        }
        VkGraphicsPipelineCreateInfo pipeline_info{};
        pipeline_info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
        pipeline_info.stageCount = static_cast<std::uint32_t>(shader_stages.size());
        pipeline_info.pStages = shader_stages.data();
        pipeline_info.pVertexInputState = &vertex_input;
        pipeline_info.pInputAssemblyState = &input_assembly;
        pipeline_info.pViewportState = &viewport_state;
        pipeline_info.pRasterizationState = &rasterizer;
        pipeline_info.pMultisampleState = &multisampling;
        pipeline_info.pColorBlendState = &color_blending;
        pipeline_info.pDynamicState = &dynamic_state;
        pipeline_info.layout = pipeline_layout_;
        pipeline_info.renderPass = render_pass_;
        pipeline_info.subpass = 0;
        const VkResult result = vkCreateGraphicsPipelines(
            device_, VK_NULL_HANDLE, 1, &pipeline_info, nullptr, &pipeline_);
        vkDestroyShaderModule(device_, vertex_module, nullptr);
        vkDestroyShaderModule(device_, fragment_module, nullptr);
        if (result != VK_SUCCESS) {
            log_error("Failed to create the Vulkan graphics pipeline");
            return false;
        }
        return true;
    }

    VkShaderModule create_shader_module(const std::uint32_t* code, const std::size_t size) const {
        VkShaderModuleCreateInfo create_info{};
        create_info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        create_info.codeSize = size;
        create_info.pCode = code;
        VkShaderModule module = VK_NULL_HANDLE;
        if (vkCreateShaderModule(device_, &create_info, nullptr, &module) != VK_SUCCESS) {
            log_error("Failed to create a Vulkan shader module");
            return VK_NULL_HANDLE;
        }
        return module;
    }

    bool create_framebuffers() {
        framebuffers_.resize(swapchain_image_views_.size(), VK_NULL_HANDLE);
        for (std::size_t index = 0; index < swapchain_image_views_.size(); ++index) {
            const VkImageView attachment = swapchain_image_views_[index];
            VkFramebufferCreateInfo framebuffer_info{};
            framebuffer_info.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            framebuffer_info.renderPass = render_pass_;
            framebuffer_info.attachmentCount = 1;
            framebuffer_info.pAttachments = &attachment;
            framebuffer_info.width = extent_.width;
            framebuffer_info.height = extent_.height;
            framebuffer_info.layers = 1;
            if (vkCreateFramebuffer(device_, &framebuffer_info, nullptr, &framebuffers_[index]) != VK_SUCCESS) {
                log_error("Failed to create a Vulkan framebuffer");
                return false;
            }
        }
        return true;
    }

    bool record_commands(
        const VkCommandBuffer command_buffer,
        const std::uint32_t image_index,
        const std::uint32_t vertex_count,
        const VkDeviceSize vertex_offset,
        const float clear_red,
        const float clear_green,
        const float clear_blue) const {
        if (image_index >= framebuffers_.size()) return false;
        VkCommandBufferBeginInfo begin_info{};
        begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(command_buffer, &begin_info) != VK_SUCCESS) return false;
        VkClearValue clear_value{};
        clear_value.color.float32[0] = clear_red;
        clear_value.color.float32[1] = clear_green;
        clear_value.color.float32[2] = clear_blue;
        clear_value.color.float32[3] = 1f;
        VkRenderPassBeginInfo render_pass_info{};
        render_pass_info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        render_pass_info.renderPass = render_pass_;
        render_pass_info.framebuffer = framebuffers_[image_index];
        render_pass_info.renderArea.extent = extent_;
        render_pass_info.clearValueCount = 1;
        render_pass_info.pClearValues = &clear_value;
        vkCmdBeginRenderPass(command_buffer, &render_pass_info, VK_SUBPASS_CONTENTS_INLINE);
        VkViewport viewport{};
        viewport.width = static_cast<float>(extent_.width);
        viewport.height = static_cast<float>(extent_.height);
        viewport.minDepth = 0f;
        viewport.maxDepth = 1f;
        VkRect2D scissor{};
        scissor.extent = extent_;
        vkCmdSetViewport(command_buffer, 0, 1, &viewport);
        vkCmdSetScissor(command_buffer, 0, 1, &scissor);
        vkCmdBindPipeline(command_buffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
        vkCmdBindVertexBuffers(command_buffer, 0, 1, &vertex_buffer_, &vertex_offset);
        vkCmdDraw(command_buffer, vertex_count, 1, 0, 0);
        vkCmdEndRenderPass(command_buffer);
        return vkEndCommandBuffer(command_buffer) == VK_SUCCESS;
    }

    bool recreate_swapchain() {
        if (device_ == VK_NULL_HANDLE) return false;
        vkDeviceWaitIdle(device_);
        destroy_swapchain_resources();
        return create_swapchain_resources();
    }

    void destroy_swapchain_resources() {
        for (const VkFramebuffer framebuffer : framebuffers_) {
            if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device_, framebuffer, nullptr);
        }
        framebuffers_.clear();
        if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipeline_, nullptr);
        pipeline_ = VK_NULL_HANDLE;
        if (pipeline_layout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device_, pipeline_layout_, nullptr);
        pipeline_layout_ = VK_NULL_HANDLE;
        if (render_pass_ != VK_NULL_HANDLE) vkDestroyRenderPass(device_, render_pass_, nullptr);
        render_pass_ = VK_NULL_HANDLE;
        for (const VkImageView image_view : swapchain_image_views_) {
            if (image_view != VK_NULL_HANDLE) vkDestroyImageView(device_, image_view, nullptr);
        }
        swapchain_image_views_.clear();
        swapchain_images_.clear();
        if (swapchain_ != VK_NULL_HANDLE) vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }

    void cleanup() {
        if (device_ != VK_NULL_HANDLE) vkDeviceWaitIdle(device_);
        if (device_ != VK_NULL_HANDLE) {
            destroy_swapchain_resources();
            if (mapped_vertex_memory_ != nullptr) vkUnmapMemory(device_, vertex_memory_);
            mapped_vertex_memory_ = nullptr;
            if (vertex_buffer_ != VK_NULL_HANDLE) vkDestroyBuffer(device_, vertex_buffer_, nullptr);
            if (vertex_memory_ != VK_NULL_HANDLE) vkFreeMemory(device_, vertex_memory_, nullptr);
            for (auto& frame : frames_) {
                if (frame.image_available != VK_NULL_HANDLE) {
                    vkDestroySemaphore(device_, frame.image_available, nullptr);
                }
                if (frame.render_finished != VK_NULL_HANDLE) {
                    vkDestroySemaphore(device_, frame.render_finished, nullptr);
                }
                if (frame.in_flight != VK_NULL_HANDLE) vkDestroyFence(device_, frame.in_flight, nullptr);
            }
            if (command_pool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, command_pool_, nullptr);
            vkDestroyDevice(device_, nullptr);
        }
        if (surface_ != VK_NULL_HANDLE && instance_ != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance_, surface_, nullptr);
        }
        if (instance_ != VK_NULL_HANDLE) vkDestroyInstance(instance_, nullptr);
        if (window_ != nullptr) ANativeWindow_release(window_);
        window_ = nullptr;
    }

    ANativeWindow* window_ = nullptr;
    int requested_width_ = 0;
    int requested_height_ = 0;
    bool resize_pending_ = false;
    bool vertex_memory_coherent_ = false;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physical_device_ = VK_NULL_HANDLE;
    std::uint32_t queue_family_index_ = 0;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue graphics_queue_ = VK_NULL_HANDLE;
    VkCommandPool command_pool_ = VK_NULL_HANDLE;
    std::array<FrameResources, kFramesInFlight> frames_{};
    std::size_t current_frame_ = 0;
    VkBuffer vertex_buffer_ = VK_NULL_HANDLE;
    VkDeviceMemory vertex_memory_ = VK_NULL_HANDLE;
    void* mapped_vertex_memory_ = nullptr;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkSurfaceFormatKHR surface_format_{};
    VkExtent2D extent_{};
    std::vector<VkImage> swapchain_images_;
    std::vector<VkImageView> swapchain_image_views_;
    VkRenderPass render_pass_ = VK_NULL_HANDLE;
    VkPipelineLayout pipeline_layout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    std::vector<VkFramebuffer> framebuffers_;
};

int VulkanRenderer::support_level() {
    return probe_vulkan_device().first;
}

std::string VulkanRenderer::device_name() {
    return probe_vulkan_device().second;
}

std::unique_ptr<VulkanRenderer> VulkanRenderer::create(
    ANativeWindow* window,
    const int requested_width,
    const int requested_height) {
    auto implementation = std::make_unique<Impl>(window, requested_width, requested_height);
    if (!implementation->initialize()) return nullptr;
    return std::unique_ptr<VulkanRenderer>(new VulkanRenderer(std::move(implementation)));
}

VulkanRenderer::VulkanRenderer(std::unique_ptr<Impl> implementation)
    : implementation_(std::move(implementation)) {}

VulkanRenderer::~VulkanRenderer() = default;

void VulkanRenderer::resize(const int width, const int height) {
    implementation_->resize(width, height);
}

bool VulkanRenderer::render(
    const float* vertices,
    const std::size_t vertex_count,
    const float clear_red,
    const float clear_green,
    const float clear_blue) {
    return implementation_->render(vertices, vertex_count, clear_red, clear_green, clear_blue);
}

}  // namespace snake::render
