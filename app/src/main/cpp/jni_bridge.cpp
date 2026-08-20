#include <jni.h>
#include <android/native_window_jni.h>

#include "snake/platform/engine_host.hpp"
#include "snake/core/version.hpp"
#include "snake/render/vulkan_renderer.hpp"

#include <cstdint>
#include <memory>
#include <string>

namespace {

snake::platform::EngineHost* from_handle(const jlong handle) {
    return reinterpret_cast<snake::platform::EngineHost*>(static_cast<std::uintptr_t>(handle));
}

snake::render::VulkanRenderer* vulkan_from_handle(const jlong handle) {
    return reinterpret_cast<snake::render::VulkanRenderer*>(static_cast<std::uintptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_snake_evolutionarena_NativeBridge_coreVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(snake::core::version().data());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_snake_evolutionarena_NativeBridge_saveFormatVersion(JNIEnv*, jobject) {
    return static_cast<jint>(snake::core::save_format_version());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_snake_evolutionarena_NativeBridge_createWorld(
    JNIEnv*,
    jobject,
    const jfloat width,
    const jfloat height,
    const jlong seed,
    const jint map_index,
    const jint mode_index,
    const jint ai_level,
    const jint archetype_index) {
    auto host = std::make_unique<snake::platform::EngineHost>(
        width,
        height,
        static_cast<std::uint64_t>(seed),
        static_cast<std::uint32_t>(map_index),
        static_cast<std::uint32_t>(mode_index),
        static_cast<std::uint32_t>(ai_level),
        static_cast<std::uint32_t>(archetype_index));
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(host.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_snake_evolutionarena_NativeBridge_destroyWorld(JNIEnv*, jobject, const jlong handle) {
    delete from_handle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_snake_evolutionarena_NativeBridge_advanceWorld(
    JNIEnv*,
    jobject,
    const jlong handle,
    const jlong frame_time_nanoseconds,
    const jfloat direction_x,
    const jfloat direction_y,
    const jboolean boost) {
    if (auto* host = from_handle(handle); host != nullptr) {
        host->advance(
            static_cast<std::int64_t>(frame_time_nanoseconds),
            {{direction_x, direction_y}, boost == JNI_TRUE});
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_snake_evolutionarena_NativeBridge_writeWorldSnapshot(
    JNIEnv* env,
    jobject,
    const jlong handle,
    jfloatArray output) {
    auto* host = from_handle(handle);
    if (host == nullptr || output == nullptr) {
        return 0;
    }
    const jsize length = env->GetArrayLength(output);
    jboolean is_copy = JNI_FALSE;
    jfloat* values = env->GetFloatArrayElements(output, &is_copy);
    if (values == nullptr) {
        return 0;
    }
    const auto written = host->write_snapshot(values, static_cast<std::size_t>(length));
    env->ReleaseFloatArrayElements(output, values, 0);
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT void JNICALL
Java_com_snake_evolutionarena_NativeBridge_chooseUpgrade(
    JNIEnv*,
    jobject,
    const jlong handle,
    const jint choice) {
    if (auto* host = from_handle(handle); host != nullptr) {
        host->choose_upgrade(static_cast<std::uint32_t>(choice));
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_snake_evolutionarena_NativeBridge_vulkanSupportLevel(JNIEnv*, jobject) {
    return static_cast<jint>(snake::render::VulkanRenderer::support_level());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_snake_evolutionarena_NativeBridge_vulkanDeviceName(JNIEnv* env, jobject) {
    const std::string name = snake::render::VulkanRenderer::device_name();
    return env->NewStringUTF(name.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_snake_evolutionarena_NativeBridge_createVulkanRenderer(
    JNIEnv* env,
    jobject,
    jobject surface,
    const jint width,
    const jint height) {
    if (surface == nullptr) return 0;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return 0;
    auto renderer = snake::render::VulkanRenderer::create(window, width, height);
    if (!renderer) return 0;
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(renderer.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_snake_evolutionarena_NativeBridge_resizeVulkanRenderer(
    JNIEnv*,
    jobject,
    const jlong handle,
    const jint width,
    const jint height) {
    if (auto* renderer = vulkan_from_handle(handle); renderer != nullptr) {
        renderer->resize(width, height);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_snake_evolutionarena_NativeBridge_renderVulkan(
    JNIEnv* env,
    jobject,
    const jlong handle,
    jobject vertices,
    const jint vertex_count,
    const jfloat clear_red,
    const jfloat clear_green,
    const jfloat clear_blue) {
    auto* renderer = vulkan_from_handle(handle);
    if (renderer == nullptr || vertices == nullptr || vertex_count <= 0) return JNI_FALSE;
    const auto* data = static_cast<const float*>(env->GetDirectBufferAddress(vertices));
    const jlong capacity = env->GetDirectBufferCapacity(vertices);
    const jlong required = static_cast<jlong>(vertex_count) * 9;
    if (data == nullptr || capacity < required) return JNI_FALSE;
    return renderer->render(
               data,
               static_cast<std::size_t>(vertex_count),
               clear_red,
               clear_green,
               clear_blue)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_snake_evolutionarena_NativeBridge_destroyVulkanRenderer(
    JNIEnv*, jobject, const jlong handle) {
    delete vulkan_from_handle(handle);
}
