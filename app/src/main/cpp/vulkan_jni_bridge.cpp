#include <jni.h>
#include <android/native_window_jni.h>

#include "snake/render/vulkan_renderer.hpp"

#include <cstdint>
#include <memory>
#include <string>

namespace {

snake::render::VulkanRenderer* from_handle(const jlong handle) {
    return reinterpret_cast<snake::render::VulkanRenderer*>(static_cast<std::uintptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_snake_evolutionarena_VulkanBridge_nativeSupportLevel(JNIEnv*, jobject) {
    return static_cast<jint>(snake::render::VulkanRenderer::support_level());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_snake_evolutionarena_VulkanBridge_nativeDeviceName(JNIEnv* env, jobject) {
    const std::string name = snake::render::VulkanRenderer::device_name();
    return env->NewStringUTF(name.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_snake_evolutionarena_VulkanBridge_nativeCreateRenderer(
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
Java_com_snake_evolutionarena_VulkanBridge_nativeResizeRenderer(
    JNIEnv*,
    jobject,
    const jlong handle,
    const jint width,
    const jint height) {
    if (auto* renderer = from_handle(handle); renderer != nullptr) {
        renderer->resize(width, height);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_snake_evolutionarena_VulkanBridge_nativeRender(
    JNIEnv* env,
    jobject,
    const jlong handle,
    jobject vertices,
    const jint vertex_count,
    const jfloat clear_red,
    const jfloat clear_green,
    const jfloat clear_blue) {
    auto* renderer = from_handle(handle);
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
Java_com_snake_evolutionarena_VulkanBridge_nativeDestroyRenderer(
    JNIEnv*, jobject, const jlong handle) {
    delete from_handle(handle);
}
