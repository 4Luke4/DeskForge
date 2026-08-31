#pragma once

#include <android/native_window.h>
#include <jni.h>

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

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
        int viewport_height);
    bool attach_surface(JNIEnv* environment, jobject surface, int width, int height);
    void detach_surface();
    bool resize(int width, int height);
    bool send_pointer(int x, int y, int button_mask);
    bool send_key(uint32_t keysym, bool pressed);
    void stop();

    [[nodiscard]] bool connected() const { return connected_.load(); }
    [[nodiscard]] std::string last_error() const;

private:
    bool connect_socket(const std::string& socket_path);
    bool negotiate();
    void read_loop();
    bool read_server_message();
    bool read_framebuffer_update();
    bool read_exact(void* destination, size_t size);
    bool write_exact(const void* source, size_t size);
    bool request_update(bool incremental);
    bool set_framebuffer_size(uint16_t width, uint16_t height);
    void render_locked();
    void fail(std::string message);

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
};
