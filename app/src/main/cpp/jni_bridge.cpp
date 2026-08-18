#include <jni.h>

#include "snake/core/version.hpp"

extern "C" JNIEXPORT jstring JNICALL
Java_com_snake_evolutionarena_NativeBridge_coreVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF(snake::core::version().data());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_snake_evolutionarena_NativeBridge_saveFormatVersion(JNIEnv*, jobject) {
    return static_cast<jint>(snake::core::save_format_version());
}
