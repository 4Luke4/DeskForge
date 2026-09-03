#pragma once

#include <jni.h>

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

enum class NativePresentationStatus : int {
    Unavailable = 0,
    Starting = 1,
    Ready = 2,
    SurfaceDetached = 3,
    Failed = 4,
    Stopped = 5,
};

enum class NativePresentationPath : int {
    NativeHardwareBuffer = 0,
    NativeEglUpload = 1,
    Rfb = 2,
};

struct NativePresentationSnapshot {
    NativePresentationStatus status = NativePresentationStatus::Unavailable;
    NativePresentationPath path = NativePresentationPath::NativeEglUpload;
    double target_refresh_rate_hz = 0.0;
    double active_refresh_rate_hz = 0.0;
    double submitted_frames_per_second = 0.0;
    uint64_t missed_frame_budget_count = 0;
    double p95_frame_time_ms = 0.0;
    double maximum_frame_time_ms = 0.0;
};

/**
 * Owns the EGL context and presentation thread. RFB decoding remains on its reader thread; only
 * bounded damaged pixels cross into this object, so socket stalls never hold an EGL or Surface lock.
 */
class NativeEglPresenter {
public:
    NativeEglPresenter();
    ~NativeEglPresenter();

    NativeEglPresenter(const NativeEglPresenter&) = delete;
    NativeEglPresenter& operator=(const NativeEglPresenter&) = delete;

    bool start(
        JNIEnv* environment,
        jobject surface,
        int viewport_width,
        int viewport_height,
        float target_refresh_rate_hz,
        float active_refresh_rate_hz);
    bool attach_surface(
        JNIEnv* environment,
        jobject surface,
        int viewport_width,
        int viewport_height,
        float target_refresh_rate_hz,
        float active_refresh_rate_hz);
    void detach_surface();
    void update_display_mode(
        int viewport_width,
        int viewport_height,
        float target_refresh_rate_hz,
        float active_refresh_rate_hz);
    void submit(
        const std::vector<uint8_t>& framebuffer,
        int framebuffer_width,
        int framebuffer_height,
        int damage_x,
        int damage_y,
        int damage_width,
        int damage_height);
    void stop();

    [[nodiscard]] bool ready() const;
    [[nodiscard]] NativePresentationSnapshot snapshot() const;
    [[nodiscard]] std::string detail() const;

private:
    class Impl;
    std::unique_ptr<Impl> implementation_;
};
