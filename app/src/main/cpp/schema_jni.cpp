#include <jni.h>
#include <cstring>
#include <memory>
#include <string>
#include "rolithax_schema.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_wludy_rolithax_launcher_shared_console_SchemaNativeBridge_nativeCreate(JNIEnv*, jobject) {
    return static_cast<jlong>(rolithax_schema_create());
}

extern "C" JNIEXPORT void JNICALL
Java_com_wludy_rolithax_launcher_shared_console_SchemaNativeBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    rolithax_schema_destroy(static_cast<uint64_t>(handle));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wludy_rolithax_launcher_shared_console_SchemaNativeBridge_nativeRequest(JNIEnv* env, jobject, jlong handle, jbyteArray request) {
    try {
        if (!request || env->GetArrayLength(request) > 524288) {
            env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Schema request is null or too large");
            return nullptr;
        }
        const auto size = env->GetArrayLength(request);
        std::string input(static_cast<size_t>(size), '\0');
        env->GetByteArrayRegion(request, 0, size, reinterpret_cast<jbyte*>(input.data()));
        if (env->ExceptionCheck()) return nullptr;
        std::unique_ptr<char, decltype(&rolithax_schema_free)> response(
            rolithax_schema_request(static_cast<uint64_t>(handle), input.c_str()), rolithax_schema_free);
        if (!response) {
            env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Schema response allocation failed");
            return nullptr;
        }
        const auto length = static_cast<jsize>(std::strlen(response.get()));
        auto bytes = env->NewByteArray(length);
        if (bytes) env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte*>(response.get()));
        return bytes;
    } catch (...) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Schema JNI allocation failed");
        return nullptr;
    }
}
