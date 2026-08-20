#pragma once

#include <android/native_window.h>

#include <cstddef>
#include <memory>
#include <string>

namespace snake::render {

class VulkanRenderer {
public:
    static int support_level();
    static std::string device_name();
    static std::unique_ptr<VulkanRenderer> create(
        ANativeWindow* window,
        int requested_width,
        int requested_height);

    ~VulkanRenderer();

    VulkanRenderer(const VulkanRenderer&) = delete;
    VulkanRenderer& operator=(const VulkanRenderer&) = delete;

    void resize(int width, int height);
    [[nodiscard]] int drawable_width() const noexcept;
    [[nodiscard]] int drawable_height() const noexcept;
    bool render(
        const float* vertices,
        std::size_t vertex_count,
        float clear_red,
        float clear_green,
        float clear_blue);

private:
    class Impl;

    explicit VulkanRenderer(std::unique_ptr<Impl> implementation);
    std::unique_ptr<Impl> implementation_;
};

}  // namespace snake::render
