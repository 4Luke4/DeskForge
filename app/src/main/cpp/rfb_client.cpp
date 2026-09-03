#include "rfb_client.h"
#include "rfb_clipboard.h"
#include "rfb_protocol.h"

#include <android/native_window_jni.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <limits>

namespace {

constexpr size_t kPixelBytes = 4;
constexpr int kSocketWaitAttempts = 300;
constexpr int32_t kEncodingRaw = 0;
constexpr int32_t kEncodingCopyRect = 1;
constexpr int32_t kEncodingDesktopSize = -223;
constexpr int32_t kEncodingExtendedDesktopSize = -308;
constexpr auto kClipboardTimeout = std::chrono::seconds(5);

uint16_t read_u16(const uint8_t* value) {
    return static_cast<uint16_t>((static_cast<uint16_t>(value[0]) << 8U) | value[1]);
}

uint32_t read_u32(const uint8_t* value) {
    return (static_cast<uint32_t>(value[0]) << 24U) |
           (static_cast<uint32_t>(value[1]) << 16U) |
           (static_cast<uint32_t>(value[2]) << 8U) |
           static_cast<uint32_t>(value[3]);
}

void append_u16(std::vector<uint8_t>& message, uint16_t value) {
    message.push_back(static_cast<uint8_t>(value >> 8U));
    message.push_back(static_cast<uint8_t>(value));
}

void append_u32(std::vector<uint8_t>& message, uint32_t value) {
    message.push_back(static_cast<uint8_t>(value >> 24U));
    message.push_back(static_cast<uint8_t>(value >> 16U));
    message.push_back(static_cast<uint8_t>(value >> 8U));
    message.push_back(static_cast<uint8_t>(value));
}

}  // namespace

RfbClient::~RfbClient() {
    stop();
}

bool RfbClient::connect_and_start(
    JNIEnv* environment,
    jobject surface,
    const std::string& socket_path,
    int viewport_width,
    int viewport_height) {
    if (!attach_surface(environment, surface, viewport_width, viewport_height)) return false;
    if (!connect_socket(socket_path) || !negotiate()) {
        stop();
        return false;
    }
    connected_.store(true);
    reader_ = std::thread(&RfbClient::read_loop, this);
    return true;
}

bool RfbClient::attach_surface(JNIEnv* environment, jobject surface, int width, int height) {
    if (surface == nullptr || width <= 0 || height <= 0) {
        fail("The Android desktop surface is unavailable");
        return false;
    }
    ANativeWindow* candidate = ANativeWindow_fromSurface(environment, surface);
    if (candidate == nullptr) {
        fail("Unable to acquire the Android desktop surface");
        return false;
    }
    if (ANativeWindow_setBuffersGeometry(candidate, width, height, WINDOW_FORMAT_RGBX_8888) != 0) {
        ANativeWindow_release(candidate);
        fail("Unable to configure the Android desktop surface");
        return false;
    }
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (window_ != nullptr) ANativeWindow_release(window_);
    window_ = candidate;
    viewport_width_ = width;
    viewport_height_ = height;
    render_locked();
    return true;
}

void RfbClient::detach_surface() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

bool RfbClient::connect_socket(const std::string& socket_path) {
    sockaddr_un path_limit{};
    if (socket_path.empty() || socket_path.size() >= sizeof(path_limit.sun_path)) {
        fail("The desktop socket path is invalid");
        return false;
    }
    for (int attempt = 0; attempt < kSocketWaitAttempts && !stopping_.load(); ++attempt) {
        const int candidate = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (candidate < 0) {
            fail(std::string("Unable to create the desktop socket: ") + std::strerror(errno));
            return false;
        }
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        std::memcpy(address.sun_path, socket_path.c_str(), socket_path.size() + 1);
        if (connect(candidate, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == 0) {
            const timeval send_timeout{2, 0};
            if (setsockopt(candidate, SOL_SOCKET, SO_SNDTIMEO, &send_timeout, sizeof(send_timeout)) != 0) {
                close(candidate);
                fail("Unable to bound writes to the Fedora desktop host");
                return false;
            }
            socket_ = candidate;
            return true;
        }
        close(candidate);
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
    fail("Timed out waiting for the Fedora desktop host");
    return false;
}

bool RfbClient::negotiate() {
    std::array<char, 12> version{};
    if (!read_exact(version.data(), version.size())) return false;
    if (!rfb_protocol::supported_version(version.data(), version.size())) {
        fail("The desktop host returned an unsupported RFB protocol version");
        return false;
    }
    constexpr std::array<char, 12> requested_version{'R', 'F', 'B', ' ', '0', '0', '3', '.', '0', '0', '8', '\n'};
    if (!write_exact(requested_version.data(), requested_version.size())) return false;

    uint8_t security_count = 0;
    if (!read_exact(&security_count, sizeof(security_count)) || security_count == 0) {
        fail("The desktop host did not offer a usable security mode");
        return false;
    }
    std::vector<uint8_t> security_types(security_count);
    if (!read_exact(security_types.data(), security_types.size()) ||
        std::find(security_types.begin(), security_types.end(), 1) == security_types.end()) {
        fail("The private desktop socket did not offer the expected security mode");
        return false;
    }
    const uint8_t selected_security = 1;
    if (!write_exact(&selected_security, sizeof(selected_security))) return false;
    std::array<uint8_t, 4> security_result{};
    if (!read_exact(security_result.data(), security_result.size()) || read_u32(security_result.data()) != 0) {
        fail("The desktop host rejected the local RFB connection");
        return false;
    }
    const uint8_t exclusive_client = 0;
    if (!write_exact(&exclusive_client, sizeof(exclusive_client))) return false;

    std::array<uint8_t, 24> server_init{};
    if (!read_exact(server_init.data(), server_init.size())) return false;
    const uint16_t width = read_u16(server_init.data());
    const uint16_t height = read_u16(server_init.data() + 2);
    const uint32_t name_length = read_u32(server_init.data() + 20);
    if (name_length > rfb_clipboard::kMaximumTextBytes || !set_framebuffer_size(width, height)) {
        fail("The desktop host declared an invalid framebuffer");
        return false;
    }
    std::vector<uint8_t> name(name_length);
    if (!name.empty() && !read_exact(name.data(), name.size())) return false;

    const std::array<uint8_t, 20> pixel_format{
        0, 0, 0, 0, 32, 24, 0, 1,
        0, 255, 0, 255, 0, 255,
        0, 8, 16, 0, 0, 0,
    };
    if (!write_exact(pixel_format.data(), pixel_format.size())) return false;
    std::vector<uint8_t> encodings{2, 0};
    append_u16(encodings, 5);
    append_u32(encodings, static_cast<uint32_t>(kEncodingRaw));
    append_u32(encodings, static_cast<uint32_t>(kEncodingCopyRect));
    append_u32(encodings, static_cast<uint32_t>(kEncodingDesktopSize));
    append_u32(encodings, static_cast<uint32_t>(kEncodingExtendedDesktopSize));
    append_u32(encodings, rfb_clipboard::kEncoding);
    return write_exact(encodings.data(), encodings.size()) && request_update(false);
}

void RfbClient::read_loop() {
    while (!stopping_.load() && read_server_message()) {
    }
    if (!stopping_.load()) fail("The Fedora desktop connection ended unexpectedly");
    connected_.store(false);
}

bool RfbClient::read_server_message() {
    uint8_t type = 0;
    if (!read_exact(&type, sizeof(type))) return false;
    if (type == 0) return read_framebuffer_update();
    if (type == 2) return true;
    if (type == 1) {
        std::array<uint8_t, 5> header{};
        if (!read_exact(header.data(), header.size())) return false;
        const uint32_t colors = read_u16(header.data() + 3);
        std::vector<uint8_t> values(colors * 6U);
        return values.empty() || read_exact(values.data(), values.size());
    }
    if (type == 3) return read_clipboard_message();
    fail("The desktop host sent an unsupported RFB message");
    return false;
}

bool RfbClient::read_clipboard_message() {
    std::array<uint8_t, 7> header{};
    if (!read_exact(header.data(), header.size())) return false;
    const int32_t signed_length = static_cast<int32_t>(read_u32(header.data() + 3));
    if (signed_length >= 0) {
        const size_t length = static_cast<size_t>(signed_length);
        if (length > rfb_clipboard::kMaximumTextBytes) return false;
        std::vector<uint8_t> ignored(length);
        return ignored.empty() || read_exact(ignored.data(), ignored.size());
    }
    if (signed_length == std::numeric_limits<int32_t>::min()) return false;
    const size_t length = static_cast<size_t>(-signed_length);
    if (length < 4U || length > rfb_clipboard::kMaximumWireBytes) return false;
    std::vector<uint8_t> body(length);
    if (!read_exact(body.data(), body.size())) return false;
    const auto extended = rfb_clipboard::parse_extended(body);
    if (!extended.has_value()) return false;

    if (extended->action == rfb_clipboard::ExtendedAction::Caps) {
        const auto capabilities = rfb_clipboard::parse_capabilities(*extended);
        if (!capabilities.has_value()) return false;
        const uint32_t required = rfb_clipboard::kFormatText |
            rfb_clipboard::kActionRequest | rfb_clipboard::kActionNotify | rfb_clipboard::kActionProvide;
        if ((capabilities->flags & required) != required) return true;
        {
            std::lock_guard<std::mutex> lock(state_mutex_);
            server_clipboard_flags_ = capabilities->flags;
            clipboard_status_ = remote_clipboard_available_
                ? RfbClipboardStatus::RemoteAvailable
                : RfbClipboardStatus::Idle;
            clipboard_failure_ = RfbClipboardFailure::None;
        }
        const auto response = rfb_clipboard::caps_message(6);
        return write_exact(response.data(), response.size());
    }
    if (extended->action == rfb_clipboard::ExtendedAction::Notify) {
        std::lock_guard<std::mutex> lock(state_mutex_);
        if (server_clipboard_flags_ == 0) return false;
        remote_clipboard_available_ = (extended->flags & rfb_clipboard::kFormatText) != 0;
        if (clipboard_status_ != RfbClipboardStatus::Sending &&
            clipboard_status_ != RfbClipboardStatus::Receiving &&
            clipboard_status_ != RfbClipboardStatus::Received) {
            clipboard_status_ = remote_clipboard_available_
                ? RfbClipboardStatus::RemoteAvailable
                : RfbClipboardStatus::Idle;
            clipboard_failure_ = RfbClipboardFailure::None;
        }
        return true;
    }
    if (extended->action == rfb_clipboard::ExtendedAction::Request) {
        if ((extended->flags & rfb_clipboard::kFormatText) == 0) return false;
        std::vector<uint8_t> text;
        {
            std::lock_guard<std::mutex> lock(state_mutex_);
            if (clipboard_status_ != RfbClipboardStatus::Sending || !outbound_clipboard_pending_) return false;
            text = outbound_clipboard_;
        }
        const auto response = rfb_clipboard::provide_message(6, text);
        if (!response.has_value() || !write_exact(response->data(), response->size())) {
            std::lock_guard<std::mutex> lock(state_mutex_);
            fail_clipboard_locked(RfbClipboardFailure::TransferFailed);
            return true;
        }
        std::lock_guard<std::mutex> lock(state_mutex_);
        outbound_clipboard_.clear();
        outbound_clipboard_pending_ = false;
        clipboard_status_ = remote_clipboard_available_
            ? RfbClipboardStatus::RemoteAvailable
            : RfbClipboardStatus::Idle;
        clipboard_failure_ = RfbClipboardFailure::None;
        return true;
    }
    if (extended->action == rfb_clipboard::ExtendedAction::Provide) {
        {
            std::lock_guard<std::mutex> lock(state_mutex_);
            if (clipboard_status_ != RfbClipboardStatus::Receiving) return false;
        }
        const auto text = rfb_clipboard::parse_provided_text(*extended);
        if (!text.has_value()) return false;
        std::lock_guard<std::mutex> lock(state_mutex_);
        received_clipboard_ = *text;
        remote_clipboard_available_ = false;
        clipboard_status_ = RfbClipboardStatus::Received;
        clipboard_failure_ = RfbClipboardFailure::None;
        return true;
    }
    if (extended->action == rfb_clipboard::ExtendedAction::Peek) {
        bool text_available = false;
        {
            std::lock_guard<std::mutex> lock(state_mutex_);
            text_available = outbound_clipboard_pending_;
        }
        const auto response = rfb_clipboard::action_message(
            6,
            rfb_clipboard::kActionNotify |
                (text_available ? rfb_clipboard::kFormatText : 0U));
        return write_exact(response.data(), response.size());
    }
    return false;
}

bool RfbClient::read_framebuffer_update() {
    std::array<uint8_t, 3> update_header{};
    if (!read_exact(update_header.data(), update_header.size())) return false;
    const uint16_t rectangle_count = read_u16(update_header.data() + 1);
    if (rectangle_count > 4096) return false;
    for (uint16_t index = 0; index < rectangle_count; ++index) {
        std::array<uint8_t, 12> rectangle{};
        if (!read_exact(rectangle.data(), rectangle.size())) return false;
        const uint16_t x = read_u16(rectangle.data());
        const uint16_t y = read_u16(rectangle.data() + 2);
        const uint16_t width = read_u16(rectangle.data() + 4);
        const uint16_t height = read_u16(rectangle.data() + 6);
        const int32_t encoding = static_cast<int32_t>(read_u32(rectangle.data() + 8));
        if (encoding == kEncodingDesktopSize) {
            if (!set_framebuffer_size(width, height)) return false;
            continue;
        }
        if (encoding == kEncodingExtendedDesktopSize) {
            std::array<uint8_t, 4> layout_header{};
            if (!read_exact(layout_header.data(), layout_header.size())) return false;
            const size_t layout_bytes = static_cast<size_t>(layout_header[0]) * 16U;
            std::vector<uint8_t> layout(layout_bytes);
            if (!layout.empty() && !read_exact(layout.data(), layout.size())) return false;
            if (x != 1) {
                // A resize request must preserve the server's screen identity and unknown flags.
                supports_resize_.store(layout_header[0] == 1);
                if (layout_header[0] == 1) {
                    screen_id_.store(read_u32(layout.data()));
                    screen_flags_.store(read_u32(layout.data() + 12));
                }
            }
            if (x == 1 && y != 0) {
                // Dimensions are undefined for a rejected client resize; retain the last good frame.
                requested_width_.store(0);
                requested_height_.store(0);
                continue;
            }
            if (!set_framebuffer_size(width, height)) return false;
            const int requested_width = requested_width_.load();
            const int requested_height = requested_height_.load();
            if (requested_width > 0 && requested_height > 0 &&
                (requested_width != width || requested_height != height) &&
                !resize(requested_width, requested_height)) {
                return false;
            }
            continue;
        }
        if (encoding == kEncodingRaw) {
            const size_t pixels = static_cast<size_t>(width) * height;
            if (pixels > rfb_protocol::kMaximumPixels) return false;
            std::vector<uint8_t> source(pixels * kPixelBytes);
            if (!read_exact(source.data(), source.size())) return false;
            // Never hold framebuffer state across a peer-controlled blocking socket read.
            std::lock_guard<std::mutex> lock(state_mutex_);
            if (!rfb_protocol::rectangle_within(
                    framebuffer_width_, framebuffer_height_, x, y, width, height)) {
                return false;
            }
            for (uint16_t row = 0; row < height; ++row) {
                const size_t source_offset = static_cast<size_t>(row) * width * kPixelBytes;
                const size_t destination_offset =
                    ((static_cast<size_t>(y + row) * framebuffer_width_) + x) * kPixelBytes;
                std::memcpy(
                    framebuffer_.data() + destination_offset,
                    source.data() + source_offset,
                    static_cast<size_t>(width) * kPixelBytes);
            }
        } else if (encoding == kEncodingCopyRect) {
            std::array<uint8_t, 4> source_position{};
            if (!read_exact(source_position.data(), source_position.size())) return false;
            const uint16_t source_x = read_u16(source_position.data());
            const uint16_t source_y = read_u16(source_position.data() + 2);
            std::lock_guard<std::mutex> lock(state_mutex_);
            if (!rfb_protocol::rectangle_within(
                    framebuffer_width_, framebuffer_height_, x, y, width, height)) {
                return false;
            }
            if (!rfb_protocol::rectangle_within(
                    framebuffer_width_, framebuffer_height_, source_x, source_y, width, height)) {
                return false;
            }
            std::vector<uint8_t> copied(static_cast<size_t>(width) * height * kPixelBytes);
            for (uint16_t row = 0; row < height; ++row) {
                const size_t source_offset =
                    ((static_cast<size_t>(source_y + row) * framebuffer_width_) + source_x) * kPixelBytes;
                std::memcpy(copied.data() + static_cast<size_t>(row) * width * kPixelBytes,
                            framebuffer_.data() + source_offset,
                            static_cast<size_t>(width) * kPixelBytes);
            }
            for (uint16_t row = 0; row < height; ++row) {
                const size_t destination_offset =
                    ((static_cast<size_t>(y + row) * framebuffer_width_) + x) * kPixelBytes;
                std::memcpy(framebuffer_.data() + destination_offset,
                            copied.data() + static_cast<size_t>(row) * width * kPixelBytes,
                            static_cast<size_t>(width) * kPixelBytes);
            }
        } else {
            return false;
        }
    }
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        render_locked();
    }
    return request_update(true);
}

bool RfbClient::set_framebuffer_size(uint16_t width, uint16_t height) {
    const auto bytes = rfb_protocol::framebuffer_bytes(width, height);
    if (!bytes.has_value()) return false;
    std::lock_guard<std::mutex> lock(state_mutex_);
    framebuffer_width_ = width;
    framebuffer_height_ = height;
    framebuffer_.assign(*bytes, 0);
    return true;
}

void RfbClient::render_locked() {
    if (window_ == nullptr || framebuffer_.empty() || viewport_width_ <= 0 || viewport_height_ <= 0) return;
    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) return;
    auto* destination = static_cast<uint8_t*>(buffer.bits);
    if (buffer.width == framebuffer_width_ && buffer.height == framebuffer_height_) {
        for (int row = 0; row < buffer.height; ++row) {
            std::memcpy(
                destination + static_cast<size_t>(row) * buffer.stride * kPixelBytes,
                framebuffer_.data() + static_cast<size_t>(row) * framebuffer_width_ * kPixelBytes,
                static_cast<size_t>(framebuffer_width_) * kPixelBytes);
        }
        ANativeWindow_unlockAndPost(window_);
        return;
    }
    std::fill(destination, destination + static_cast<size_t>(buffer.stride) * buffer.height * kPixelBytes, 0);
    const double scale = std::min(
        static_cast<double>(buffer.width) / framebuffer_width_,
        static_cast<double>(buffer.height) / framebuffer_height_);
    const int drawn_width = std::max(1, static_cast<int>(framebuffer_width_ * scale));
    const int drawn_height = std::max(1, static_cast<int>(framebuffer_height_ * scale));
    const int left = (buffer.width - drawn_width) / 2;
    const int top = (buffer.height - drawn_height) / 2;
    for (int row = 0; row < drawn_height; ++row) {
        const size_t source_y = std::min<size_t>(
            framebuffer_height_ - 1,
            static_cast<size_t>(row / scale));
        for (int column = 0; column < drawn_width; ++column) {
            const size_t source_x = std::min<size_t>(
                framebuffer_width_ - 1,
                static_cast<size_t>(column / scale));
            const size_t source_offset = (source_y * framebuffer_width_ + source_x) * kPixelBytes;
            const size_t destination_offset =
                (static_cast<size_t>(top + row) * buffer.stride + left + column) * kPixelBytes;
            std::memcpy(destination + destination_offset, framebuffer_.data() + source_offset, kPixelBytes);
        }
    }
    ANativeWindow_unlockAndPost(window_);
}

bool RfbClient::resize(int width, int height) {
    const auto message = rfb_protocol::set_desktop_size_message(
        width, height, screen_id_.load(), screen_flags_.load());
    if (!message.has_value()) return false;
    requested_width_.store(width);
    requested_height_.store(height);
    // The extension may only be used after ExtendedDesktopSize advertises server support.
    return !supports_resize_.load() || write_exact(message->data(), message->size());
}

bool RfbClient::send_pointer(int x, int y, int button_mask) {
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        if (framebuffer_width_ == 0 || framebuffer_height_ == 0 || viewport_width_ <= 0 || viewport_height_ <= 0) {
            return false;
        }
        const double scale = std::min(
            static_cast<double>(viewport_width_) / framebuffer_width_,
            static_cast<double>(viewport_height_) / framebuffer_height_);
        const int drawn_width = std::max(1, static_cast<int>(framebuffer_width_ * scale));
        const int drawn_height = std::max(1, static_cast<int>(framebuffer_height_ * scale));
        const int left = (viewport_width_ - drawn_width) / 2;
        const int top = (viewport_height_ - drawn_height) / 2;
        x = std::clamp(static_cast<int>((x - left) / scale), 0, static_cast<int>(framebuffer_width_) - 1);
        y = std::clamp(static_cast<int>((y - top) / scale), 0, static_cast<int>(framebuffer_height_) - 1);
    }
    std::array<uint8_t, 6> message{
        5,
        static_cast<uint8_t>(button_mask & 0xff),
        static_cast<uint8_t>(x >> 8),
        static_cast<uint8_t>(x),
        static_cast<uint8_t>(y >> 8),
        static_cast<uint8_t>(y),
    };
    return x >= 0 && y >= 0 && write_exact(message.data(), message.size());
}

bool RfbClient::send_key(uint32_t keysym, bool pressed) {
    std::vector<uint8_t> message{4, static_cast<uint8_t>(pressed ? 1 : 0), 0, 0};
    append_u32(message, keysym);
    return write_exact(message.data(), message.size());
}

bool RfbClient::send_text(const std::vector<uint32_t>& keysyms) {
    if (keysyms.size() > 4096) return false;
    std::vector<uint8_t> messages;
    messages.reserve(keysyms.size() * 16U);
    for (const uint32_t keysym : keysyms) {
        if (keysym == 0 || keysym > 0x0110ffffU) return false;
        messages.insert(messages.end(), {4, 1, 0, 0});
        append_u32(messages, keysym);
        messages.insert(messages.end(), {4, 0, 0, 0});
        append_u32(messages, keysym);
    }
    return messages.empty() || write_exact(messages.data(), messages.size());
}

RfbClipboardSnapshot RfbClient::clipboard_snapshot() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    if ((clipboard_status_ == RfbClipboardStatus::Sending ||
         clipboard_status_ == RfbClipboardStatus::Receiving) &&
        std::chrono::steady_clock::now() > clipboard_deadline_) {
        fail_clipboard_locked(RfbClipboardFailure::Timeout);
    }
    return {clipboard_status_, remote_clipboard_available_, clipboard_failure_};
}

bool RfbClient::offer_clipboard_text(const std::vector<uint8_t>& utf8_text) {
    if (utf8_text.size() > rfb_clipboard::kMaximumTextBytes) {
        std::lock_guard<std::mutex> lock(state_mutex_);
        fail_clipboard_locked(RfbClipboardFailure::TextTooLarge);
        return false;
    }
    if (!rfb_clipboard::valid_utf8(utf8_text)) {
        std::lock_guard<std::mutex> lock(state_mutex_);
        fail_clipboard_locked(RfbClipboardFailure::InvalidText);
        return false;
    }
    if (rfb_clipboard::normalize_android_text(utf8_text).size() >
        rfb_clipboard::kMaximumTextBytes) {
        std::lock_guard<std::mutex> lock(state_mutex_);
        fail_clipboard_locked(RfbClipboardFailure::TextTooLarge);
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        if (server_clipboard_flags_ == 0 || clipboard_status_ == RfbClipboardStatus::Sending ||
            clipboard_status_ == RfbClipboardStatus::Receiving ||
            clipboard_status_ == RfbClipboardStatus::Received) {
            return false;
        }
        outbound_clipboard_ = utf8_text;
        outbound_clipboard_pending_ = true;
        clipboard_status_ = RfbClipboardStatus::Sending;
        clipboard_failure_ = RfbClipboardFailure::None;
        clipboard_deadline_ = std::chrono::steady_clock::now() + kClipboardTimeout;
    }
    const auto notification = rfb_clipboard::action_message(
        6, rfb_clipboard::kActionNotify | rfb_clipboard::kFormatText);
    if (write_exact(notification.data(), notification.size())) return true;
    std::lock_guard<std::mutex> lock(state_mutex_);
    fail_clipboard_locked(RfbClipboardFailure::TransferFailed);
    return false;
}

bool RfbClient::request_clipboard_text() {
    {
        std::lock_guard<std::mutex> lock(state_mutex_);
        if (server_clipboard_flags_ == 0 || !remote_clipboard_available_ ||
            (clipboard_status_ != RfbClipboardStatus::RemoteAvailable &&
             clipboard_status_ != RfbClipboardStatus::Failed)) {
            return false;
        }
        clipboard_status_ = RfbClipboardStatus::Receiving;
        clipboard_failure_ = RfbClipboardFailure::None;
        clipboard_deadline_ = std::chrono::steady_clock::now() + kClipboardTimeout;
    }
    const auto request = rfb_clipboard::action_message(
        6, rfb_clipboard::kActionRequest | rfb_clipboard::kFormatText);
    if (write_exact(request.data(), request.size())) return true;
    std::lock_guard<std::mutex> lock(state_mutex_);
    fail_clipboard_locked(RfbClipboardFailure::TransferFailed);
    return false;
}

std::optional<std::vector<uint8_t>> RfbClient::take_clipboard_text() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    if (clipboard_status_ != RfbClipboardStatus::Received) return std::nullopt;
    std::vector<uint8_t> text = std::move(received_clipboard_);
    received_clipboard_.clear();
    clipboard_status_ = RfbClipboardStatus::Idle;
    clipboard_failure_ = RfbClipboardFailure::None;
    return text;
}

bool RfbClient::request_update(bool incremental) {
    std::array<uint8_t, 10> message{
        3,
        static_cast<uint8_t>(incremental ? 1 : 0),
        0,
        0,
        0,
        0,
        static_cast<uint8_t>(framebuffer_width_ >> 8U),
        static_cast<uint8_t>(framebuffer_width_),
        static_cast<uint8_t>(framebuffer_height_ >> 8U),
        static_cast<uint8_t>(framebuffer_height_),
    };
    return write_exact(message.data(), message.size());
}

bool RfbClient::read_exact(void* destination, size_t size) {
    auto* output = static_cast<uint8_t*>(destination);
    size_t offset = 0;
    while (offset < size && !stopping_.load()) {
        const ssize_t count = recv(socket_, output + offset, size - offset, 0);
        if (count == 0) return false;
        if (count < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        offset += static_cast<size_t>(count);
    }
    return offset == size;
}

bool RfbClient::write_exact(const void* source, size_t size) {
    std::lock_guard<std::mutex> lock(write_mutex_);
    const auto* input = static_cast<const uint8_t*>(source);
    size_t offset = 0;
    while (offset < size && !stopping_.load()) {
        const ssize_t count = send(socket_, input + offset, size - offset, MSG_NOSIGNAL);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) {
            connected_.store(false);
            shutdown(socket_, SHUT_RDWR);
            return false;
        }
        offset += static_cast<size_t>(count);
    }
    return offset == size;
}

void RfbClient::stop() {
    stopping_.store(true);
    connected_.store(false);
    if (socket_ >= 0) {
        shutdown(socket_, SHUT_RDWR);
    }
    if (reader_.joinable() && reader_.get_id() != std::this_thread::get_id()) reader_.join();
    if (socket_ >= 0) {
        close(socket_);
        socket_ = -1;
    }
    detach_surface();
    std::lock_guard<std::mutex> lock(state_mutex_);
    server_clipboard_flags_ = 0;
    remote_clipboard_available_ = false;
    outbound_clipboard_.clear();
    outbound_clipboard_pending_ = false;
    received_clipboard_.clear();
    clipboard_status_ = RfbClipboardStatus::Unsupported;
    clipboard_failure_ = RfbClipboardFailure::None;
}

void RfbClient::fail_clipboard_locked(RfbClipboardFailure failure) {
    outbound_clipboard_.clear();
    outbound_clipboard_pending_ = false;
    received_clipboard_.clear();
    clipboard_status_ = RfbClipboardStatus::Failed;
    clipboard_failure_ = failure;
}

void RfbClient::fail(std::string message) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    last_error_ = std::move(message);
}

std::string RfbClient::last_error() const {
    std::lock_guard<std::mutex> lock(state_mutex_);
    return last_error_;
}
