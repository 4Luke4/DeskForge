#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace rfb_clipboard {

constexpr size_t kMaximumTextBytes = 1024U * 1024U;
// Bounded zlib framing can be slightly larger than an incompressible one-mebibyte payload.
constexpr size_t kMaximumWireBytes = kMaximumTextBytes + 4096U;
constexpr uint32_t kEncoding = 0xc0a1e5ceU;
constexpr uint32_t kFormatText = 1U << 0U;
constexpr uint32_t kActionCaps = 1U << 24U;
constexpr uint32_t kActionRequest = 1U << 25U;
constexpr uint32_t kActionPeek = 1U << 26U;
constexpr uint32_t kActionNotify = 1U << 27U;
constexpr uint32_t kActionProvide = 1U << 28U;
constexpr uint32_t kActionMask = 0xff000000U;

struct Capabilities {
    uint32_t flags = 0;
    uint32_t maximum_text_bytes = 0;
};

enum class ExtendedAction { Caps, Request, Peek, Notify, Provide };

struct ExtendedMessage {
    ExtendedAction action;
    uint32_t flags;
    std::vector<uint8_t> payload;
};

[[nodiscard]] bool valid_utf8(const std::vector<uint8_t>& text);
[[nodiscard]] std::vector<uint8_t> normalize_android_text(const std::vector<uint8_t>& text);
[[nodiscard]] std::optional<std::vector<uint8_t>> normalize_rfb_text(const std::vector<uint8_t>& text);
[[nodiscard]] std::vector<uint8_t> caps_message(uint8_t message_type);
[[nodiscard]] std::vector<uint8_t> action_message(uint8_t message_type, uint32_t flags);
[[nodiscard]] std::optional<std::vector<uint8_t>> provide_message(
    uint8_t message_type, const std::vector<uint8_t>& utf8_text);
[[nodiscard]] std::optional<ExtendedMessage> parse_extended(const std::vector<uint8_t>& body);
[[nodiscard]] std::optional<Capabilities> parse_capabilities(const ExtendedMessage& message);
[[nodiscard]] std::optional<std::vector<uint8_t>> parse_provided_text(const ExtendedMessage& message);

}  // namespace rfb_clipboard
