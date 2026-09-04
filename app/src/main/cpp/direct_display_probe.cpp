#include <android/hardware_buffer.h>
#include <jni.h>

#include <cstdint>
#include <limits>
#include <string>

#include "direct_display_config.h"

namespace {

constexpr uint64_t kBytesPerPixel = 4;

bool checked_multiply(uint64_t left, uint64_t right, uint64_t* result) {
    if (result == nullptr || (left != 0 && right > std::numeric_limits<uint64_t>::max() / left)) {
        return false;
    }
    *result = left * right;
    return true;
}

bool accepted_format(uint32_t format) {
    return format == AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM ||
           format == AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
}

bool validate_buffer(const AHardwareBuffer_Desc& descriptor, bool allocated) {
    if (descriptor.width == 0 || descriptor.height == 0 || descriptor.layers != 1 ||
        !accepted_format(descriptor.format) ||
        (descriptor.usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT) != 0 ||
        (allocated && descriptor.stride < descriptor.width)) {
        return false;
    }

    const uint64_t stride = allocated ? descriptor.stride : descriptor.width;
    uint64_t pixels = 0;
    uint64_t bytes = 0;
    return checked_multiply(stride, descriptor.height, &pixels) &&
           checked_multiply(pixels, kBytesPerPixel, &bytes) &&
           bytes <= DESKFORGE_DISPLAY_MAXIMUM_BUFFER_BYTES &&
           bytes <= DESKFORGE_DISPLAY_MAXIMUM_AGGREGATE_BYTES;
}

std::string probe_direct_display() {
    static_assert(DESKFORGE_DISPLAY_MAXIMUM_IMPORTED_PIXMAPS > 0);
    static_assert(DESKFORGE_DISPLAY_MAXIMUM_PENDING_PRESENTS > 0);

    AHardwareBuffer_Desc request{};
    request.width = 64;
    request.height = 64;
    request.layers = 1;
    request.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    request.usage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
                    AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    AHardwareBuffer_Desc invalid = request;
    invalid.layers = 2;
    if (validate_buffer(invalid, false)) {
        return "unavailable:Multi-layer display-buffer validation failed";
    }
    invalid = request;
    invalid.format = AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420;
    if (validate_buffer(invalid, false)) {
        return "unavailable:YUV display-buffer validation failed";
    }
    invalid = request;
    invalid.usage |= AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT;
    if (validate_buffer(invalid, false)) {
        return "unavailable:Protected display-buffer validation failed";
    }
    invalid = request;
    invalid.width = static_cast<uint32_t>(
        DESKFORGE_DISPLAY_MAXIMUM_BUFFER_BYTES / kBytesPerPixel + 1);
    if (validate_buffer(invalid, false)) {
        return "unavailable:Oversized display-buffer validation failed";
    }
    if (!validate_buffer(request, false) || AHardwareBuffer_isSupported(&request) == 0) {
        return "unavailable:Required Android hardware-buffer usage is unsupported";
    }

    AHardwareBuffer* buffer = nullptr;
    if (AHardwareBuffer_allocate(&request, &buffer) != 0 || buffer == nullptr) {
        return "unavailable:Android hardware-buffer allocation failed";
    }
    AHardwareBuffer_Desc allocated{};
    AHardwareBuffer_describe(buffer, &allocated);
    const bool valid = validate_buffer(allocated, true);
    AHardwareBuffer_release(buffer);
    return valid
        ? "available:Public Android hardware-buffer contract qualified"
        : "unavailable:Android returned an invalid hardware-buffer descriptor";
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_deskforge_app_display_DirectDisplayService_nativeProbe(
    JNIEnv* environment,
    jobject) {
    const std::string result = probe_direct_display();
    return environment->NewStringUTF(result.c_str());
}
