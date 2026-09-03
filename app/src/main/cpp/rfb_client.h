#pragma once

#include <android/native_window.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include "native_egl_presenter.h"

enum class RfbClipboardStatus : int {
    Unsupported = 0,
    Idle = 1,
    RemoteAvailable = 2,
    Sending = 3,
    Receiving = 4,
    Received = 5,
    Failed = 6,
};

enum class RfbClipboardFailure : int {
    None = 0,
    TextTooLarge = 1,
    InvalidText = 2,
    Timeout = 3,
    TransferFailed = 4,
};

struct RfbClipboardSnapshot {
    RfbClipboardStatus status;
    bool remote_text_available;
    RfbClipboardFailure failure;
};

class RfbClient {
public:
    RfbClient() = default;
    ~RfbClient();

    RfbClient(const RfbClient&) = delete;
    RfbClient& operator=(const RfbClient&) = delete;

    bool connect_and_start(
        JNIEnv* environment,
        jobject surface,
        const std::string& socket_path,
        int viewport_width,
        int viewport_height,
        bool native_presentation,
        float target_refresh_rate_hz,
        float active_refresh_rate_hz);
    bool attach_surface(
        JNIEnv* environment,
        jobject surface,
        int width,
        int height,
        float target_refresh_rate_hz,
        float active_refresh_rate_hz);
    void detach_surface();
    void update_display_mode(
        int width,
        int height,
        float target_refresh_rate_hz,
        float active_refresh_rate_hz);
    bool resize(int width, int height);
    bool send_pointer(int x, int y, int button_mask);
    bool send_key(uint32_t keysym, bool pressed);
    bool send_text(const std::vector<uint32_t>& keysyms);
    RfbClipboardSnapshot clipboard_snapshot();
    bool offer_clipboard_text(const std::vector<uint8_t>& utf8_text);
    bool request_clipboard_text();
    std::optional<std::vector<uint8_t>> take_clipboard_text();
    [[nodiscard]] NativePresentationSnapshot presentation_snapshot() const;
    [[nodiscard]] std::string presentation_detail() const;
    void stop();

    [[nodiscard]] bool connected() const { return connected_.load(); }
    [[nodiscard]] std::string last_error() const;

private:
    bool connect_socket(const std::string& socket_path);
    bool negotiate();
    void read_loop();
    bool read_server_message();
    bool read_framebuffer_update();
    bool read_clipboard_message();
    bool read_exact(void* destination, size_t size);
    bool write_exact(const void* source, size_t size);
    bool request_update(bool incremental);
    bool set_framebuffer_size(uint16_t width, uint16_t height);
    void render_locked();
    void fail(std::string message);
    void fail_clipboard_locked(RfbClipboardFailure failure);

    mutable std::mutex state_mutex_;
    std::mutex write_mutex_;
    int socket_ = -1;
    ANativeWindow* window_ = nullptr;
    std::thread reader_;
    std::atomic<bool> stopping_{false};
    std::atomic<bool> connected_{false};
    std::atomic<bool> supports_resize_{false};
    std::atomic<int> requested_width_{0};
    std::atomic<int> requested_height_{0};
    std::atomic<uint32_t> screen_id_{1};
    std::atomic<uint32_t> screen_flags_{0};
    std::string last_error_;
    uint16_t framebuffer_width_ = 0;
    uint16_t framebuffer_height_ = 0;
    int viewport_width_ = 0;
    int viewport_height_ = 0;
    std::vector<uint8_t> framebuffer_;
    uint32_t server_clipboard_flags_ = 0;
    bool remote_clipboard_available_ = false;
    RfbClipboardStatus clipboard_status_ = RfbClipboardStatus::Unsupported;
    RfbClipboardFailure clipboard_failure_ = RfbClipboardFailure::None;
    std::vector<uint8_t> outbound_clipboard_;
    bool outbound_clipboard_pending_ = false;
    std::vector<uint8_t> received_clipboard_;
    std::chrono::steady_clock::time_point clipboard_deadline_{};
    bool native_presentation_ = false;
    std::unique_ptr<NativeEglPresenter> native_presenter_;
    NativePresentationSnapshot legacy_presentation_snapshot_{};
};
