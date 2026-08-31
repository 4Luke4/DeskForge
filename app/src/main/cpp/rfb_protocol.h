#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <optional>
#include <vector>

namespace rfb_protocol {

constexpr uint32_t kMaximumPixels = 16'777'216;

inline bool supported_version(const char* version, size_t size) {
    return size == 12 && std::memcmp(version, "RFB 003.", 8) == 0 &&
           version[8] == '0' && version[9] == '0' && version[10] >= '8' && version[10] <= '9' &&
           version[11] == '\n';
}

inline std::optional<size_t> framebuffer_bytes(uint32_t width, uint32_t height) {
    if (width < 640 || height < 480 || width > 4096 || height > 4096) return std::nullopt;
    const uint64_t pixels = static_cast<uint64_t>(width) * height;
    if (pixels > kMaximumPixels) return std::nullopt;
    return static_cast<size_t>(pixels * 4U);
}

inline bool rectangle_within(
    uint32_t framebuffer_width,
    uint32_t framebuffer_height,
    uint32_t x,
    uint32_t y,
    uint32_t width,
    uint32_t height) {
    return width > 0 && height > 0 && x <= framebuffer_width && y <= framebuffer_height &&
           width <= framebuffer_width - x && height <= framebuffer_height - y;
}

inline std::optional<std::vector<uint8_t>> set_desktop_size_message(
    uint32_t width,
    uint32_t height,
    uint32_t screen_id,
    uint32_t screen_flags) {
    if (!framebuffer_bytes(width, height).has_value()) return std::nullopt;
    const auto append_u16 = [](std::vector<uint8_t>& message, uint16_t value) {
        message.push_back(static_cast<uint8_t>(value >> 8U));
        message.push_back(static_cast<uint8_t>(value));
    };
    const auto append_u32 = [](std::vector<uint8_t>& message, uint32_t value) {
        message.push_back(static_cast<uint8_t>(value >> 24U));
        message.push_back(static_cast<uint8_t>(value >> 16U));
        message.push_back(static_cast<uint8_t>(value >> 8U));
        message.push_back(static_cast<uint8_t>(value));
    };
    // SetDesktopSize has two padding bytes before its dimensions and one before SCREEN.
    std::vector<uint8_t> message{251, 0, 0};
    append_u16(message, static_cast<uint16_t>(width));
    append_u16(message, static_cast<uint16_t>(height));
    message.push_back(1);
    message.push_back(0);
    append_u32(message, screen_id);
    append_u16(message, 0);
    append_u16(message, 0);
    append_u16(message, static_cast<uint16_t>(width));
    append_u16(message, static_cast<uint16_t>(height));
    append_u32(message, screen_flags);
    return message;
}

}  // namespace rfb_protocol
