#include <jni.h>
#include "snake/platform/engine_host.hpp"
#include "snake/core/version.hpp"

#include <cstdint>
#include <memory>

namespace {

snake::platform::EngineHost* from_handle(const jlong handle) {
    return reinterpret_cast<snake::platform::EngineHost*>(static_cast<std::uintptr_t>(handle));
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

extern "C" JNIEXPORT void JNICALL
Java_com_snake_evolutionarena_NativeBridge_activateAbility(
    JNIEnv*,
    jobject,
    const jlong handle,
    const jint ability) {
    if (auto* host = from_handle(handle); host != nullptr) {
        host->activate_ability(static_cast<std::uint32_t>(ability));
    }
}
