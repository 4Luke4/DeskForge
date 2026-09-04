#include <android/hardware_buffer.h>
#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstdint>
#include <limits>
#include <string>

#include "direct_display_config.h"
#include "direct_display_resources.h"

namespace {

constexpr uint64_t kBytesPerPixel = 4;
constexpr const char* kLogTag = "DeskForgeDisplay";

void log_probe_stage(const char* stage) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Direct-display probe: %s", stage);
}

struct HardwareBufferSend {
    const AHardwareBuffer* buffer;
    int socket;
    int result;
};

void* send_hardware_buffer(void* opaque) {
    auto* request = static_cast<HardwareBufferSend*>(opaque);
    request->result = AHardwareBuffer_sendHandleToUnixSocket(request->buffer, request->socket);
    shutdown(request->socket, SHUT_WR);
    return nullptr;
}

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

bool validate_resource_accounting() {
    DirectDisplayResources resources;
    if (resources.import_buffer(0, 1) || resources.import_buffer(1, 0) ||
        resources.import_buffer(1, DESKFORGE_DISPLAY_MAXIMUM_BUFFER_BYTES + 1)) {
        return false;
    }
    for (uint64_t token = 1; token <= 4; ++token) {
        if (!resources.import_buffer(token, DESKFORGE_DISPLAY_MAXIMUM_BUFFER_BYTES)) return false;
    }
    if (resources.import_buffer(5, 1) ||
        resources.aggregate_bytes() != DESKFORGE_DISPLAY_MAXIMUM_AGGREGATE_BYTES) {
        return false;
    }
    resources.reset();

    for (uint64_t token = 1; token <= DESKFORGE_DISPLAY_MAXIMUM_IMPORTED_PIXMAPS; ++token) {
        if (!resources.import_buffer(token, 1)) return false;
    }
    if (resources.import_buffer(DESKFORGE_DISPLAY_MAXIMUM_IMPORTED_PIXMAPS + 1, 1)) return false;
    resources.reset();

    if (!resources.import_buffer(1, 1) || resources.import_buffer(1, 1)) return false;
    for (uint32_t pending = 0; pending < DESKFORGE_DISPLAY_MAXIMUM_PENDING_PRESENTS; ++pending) {
        if (!resources.queue_present(1)) return false;
    }
    if (resources.queue_present(1) || resources.release_buffer(1)) return false;
    for (uint32_t pending = 0; pending < DESKFORGE_DISPLAY_MAXIMUM_PENDING_PRESENTS; ++pending) {
        if (!resources.complete_present(1)) return false;
    }
    return !resources.complete_present(1) && resources.release_buffer(1) &&
           resources.imported_buffer_count() == 0 && resources.aggregate_bytes() == 0 &&
           resources.pending_present_count() == 0;
}

std::string probe_direct_display() {
    static_assert(DESKFORGE_DISPLAY_MAXIMUM_IMPORTED_PIXMAPS > 0);
    static_assert(DESKFORGE_DISPLAY_MAXIMUM_PENDING_PRESENTS > 0);

    if (!validate_resource_accounting()) {
        return "unavailable:Display resource accounting self-test failed";
    }
    log_probe_stage("resource accounting qualified");

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
    log_probe_stage("hardware-buffer usage supported");

    AHardwareBuffer* buffer = nullptr;
    log_probe_stage("allocating hardware buffer");
    if (AHardwareBuffer_allocate(&request, &buffer) != 0 || buffer == nullptr) {
        return "unavailable:Android hardware-buffer allocation failed";
    }
    log_probe_stage("hardware buffer allocated");
    AHardwareBuffer_Desc allocated{};
    AHardwareBuffer_describe(buffer, &allocated);
    if (!validate_buffer(allocated, true)) {
        AHardwareBuffer_release(buffer);
        return "unavailable:Android returned an invalid hardware-buffer descriptor";
    }

    int transport[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, transport) != 0) {
        AHardwareBuffer_release(buffer);
        return "unavailable:Private hardware-buffer transport could not be created";
    }
    HardwareBufferSend send{buffer, transport[0], -1};
    pthread_t sender{};
    // The public socket transfer calls may block, so send and receive must run concurrently.
    if (pthread_create(&sender, nullptr, send_hardware_buffer, &send) != 0) {
        close(transport[0]);
        close(transport[1]);
        AHardwareBuffer_release(buffer);
        return "unavailable:Private hardware-buffer sender could not be created";
    }
    AHardwareBuffer* received = nullptr;
    log_probe_stage("receiving hardware-buffer handle");
    const int receive_result = AHardwareBuffer_recvHandleFromUnixSocket(transport[1], &received);
    log_probe_stage("hardware-buffer receive returned");
    const int join_result = pthread_join(sender, nullptr);
    close(transport[0]);
    close(transport[1]);
    if (send.result != 0 || receive_result != 0 || join_result != 0 || received == nullptr) {
        if (received != nullptr) AHardwareBuffer_release(received);
        AHardwareBuffer_release(buffer);
        return "unavailable:Private hardware-buffer transfer failed";
    }

    AHardwareBuffer_Desc received_descriptor{};
    AHardwareBuffer_describe(received, &received_descriptor);
    uint64_t sent_id = 0;
    uint64_t received_id = 0;
    const bool valid = validate_buffer(received_descriptor, true) &&
                       AHardwareBuffer_getId(buffer, &sent_id) == 0 &&
                       AHardwareBuffer_getId(received, &received_id) == 0 &&
                       sent_id != 0 && sent_id == received_id;
    AHardwareBuffer_release(received);
    AHardwareBuffer_release(buffer);
    return valid
        ? "available:Public Android hardware-buffer and Unix transfer contract qualified"
        : "unavailable:Transferred Android hardware-buffer identity was invalid";
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_deskforge_app_display_DirectDisplayService_nativeProbe(
    JNIEnv* environment,
    jobject) {
    log_probe_stage("entered native probe");
    const std::string result = probe_direct_display();
    return environment->NewStringUTF(result.c_str());
}
