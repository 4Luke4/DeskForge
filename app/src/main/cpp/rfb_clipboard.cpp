#include "rfb_clipboard.h"

#include <zlib.h>

#include <algorithm>
#include <array>
#include <bit>
#include <limits>

namespace rfb_clipboard {
namespace {

uint32_t read_u32(const uint8_t* value) {
    return (static_cast<uint32_t>(value[0]) << 24U) |
           (static_cast<uint32_t>(value[1]) << 16U) |
           (static_cast<uint32_t>(value[2]) << 8U) |
           static_cast<uint32_t>(value[3]);
}

void append_u32(std::vector<uint8_t>& output, uint32_t value) {
    output.push_back(static_cast<uint8_t>(value >> 24U));
    output.push_back(static_cast<uint8_t>(value >> 16U));
    output.push_back(static_cast<uint8_t>(value >> 8U));
    output.push_back(static_cast<uint8_t>(value));
}

std::optional<ExtendedAction> decode_action(uint32_t flags) {
    if ((flags & kActionCaps) != 0) return ExtendedAction::Caps;
    switch (flags & kActionMask) {
        case kActionRequest: return ExtendedAction::Request;
        case kActionPeek: return ExtendedAction::Peek;
        case kActionNotify: return ExtendedAction::Notify;
        case kActionProvide: return ExtendedAction::Provide;
        default: return std::nullopt;
    }
}

std::optional<std::vector<uint8_t>> deflate_exact(const std::vector<uint8_t>& source) {
    uLongf capacity = compressBound(static_cast<uLong>(source.size()));
    std::vector<uint8_t> compressed(static_cast<size_t>(capacity));
    const int result = compress2(
        compressed.data(), &capacity, source.data(), static_cast<uLong>(source.size()), Z_BEST_COMPRESSION);
    if (result != Z_OK || capacity + 4U > kMaximumWireBytes) return std::nullopt;
    compressed.resize(static_cast<size_t>(capacity));
    return compressed;
}

std::optional<std::vector<uint8_t>> inflate_bounded(const std::vector<uint8_t>& source) {
    if (source.empty() || source.size() > kMaximumWireBytes - 4U ||
        source.size() > static_cast<size_t>(std::numeric_limits<uInt>::max())) {
        return std::nullopt;
    }
    std::vector<uint8_t> output(kMaximumTextBytes + 5U);
    z_stream stream{};
    stream.next_in = const_cast<Bytef*>(source.data());
    stream.avail_in = static_cast<uInt>(source.size());
    stream.next_out = output.data();
    stream.avail_out = static_cast<uInt>(output.size());
    if (inflateInit(&stream) != Z_OK) return std::nullopt;
    const int result = inflate(&stream, Z_FINISH);
    const bool complete = result == Z_STREAM_END && stream.avail_in == 0 &&
        stream.total_out <= kMaximumTextBytes + 5U;
    const size_t output_size = static_cast<size_t>(stream.total_out);
    inflateEnd(&stream);
    if (!complete) return std::nullopt;
    output.resize(output_size);
    return output;
}

}  // namespace

bool valid_utf8(const std::vector<uint8_t>& text) {
    size_t index = 0;
    while (index < text.size()) {
        const uint8_t first = text[index++];
        if (first == 0) return false;
        if (first <= 0x7fU) continue;
        uint32_t code_point = 0;
        size_t continuation_count = 0;
        uint32_t minimum = 0;
        if ((first & 0xe0U) == 0xc0U) {
            code_point = first & 0x1fU;
            continuation_count = 1;
            minimum = 0x80U;
        } else if ((first & 0xf0U) == 0xe0U) {
            code_point = first & 0x0fU;
            continuation_count = 2;
            minimum = 0x800U;
        } else if ((first & 0xf8U) == 0xf0U) {
            code_point = first & 0x07U;
            continuation_count = 3;
            minimum = 0x10000U;
        } else {
            return false;
        }
        if (index + continuation_count > text.size()) return false;
        for (size_t count = 0; count < continuation_count; ++count) {
            const uint8_t continuation = text[index++];
            if ((continuation & 0xc0U) != 0x80U) return false;
            code_point = (code_point << 6U) | (continuation & 0x3fU);
        }
        if (code_point < minimum || code_point > 0x10ffffU ||
            (code_point >= 0xd800U && code_point <= 0xdfffU)) {
            return false;
        }
    }
    return true;
}

std::vector<uint8_t> normalize_android_text(const std::vector<uint8_t>& text) {
    std::vector<uint8_t> output;
    output.reserve(text.size());
    for (size_t index = 0; index < text.size(); ++index) {
        if (text[index] == '\r') {
            if (index + 1 < text.size() && text[index + 1] == '\n') ++index;
            output.push_back('\r');
            output.push_back('\n');
        } else if (text[index] == '\n') {
            output.push_back('\r');
            output.push_back('\n');
        } else {
            output.push_back(text[index]);
        }
    }
    return output;
}

std::optional<std::vector<uint8_t>> normalize_rfb_text(const std::vector<uint8_t>& text) {
    if (!valid_utf8(text)) return std::nullopt;
    std::vector<uint8_t> output;
    output.reserve(text.size());
    for (size_t index = 0; index < text.size(); ++index) {
        if (text[index] == '\r') {
            if (index + 1 < text.size() && text[index + 1] == '\n') ++index;
            output.push_back('\n');
        } else {
            output.push_back(text[index]);
        }
    }
    return output;
}

std::vector<uint8_t> caps_message(uint8_t message_type) {
    std::vector<uint8_t> payload;
    append_u32(payload, kActionCaps | kActionRequest | kActionNotify | kActionProvide | kFormatText);
    append_u32(payload, 0);
    std::vector<uint8_t> message{message_type, 0, 0, 0};
    append_u32(message, static_cast<uint32_t>(-static_cast<int32_t>(payload.size())));
    message.insert(message.end(), payload.begin(), payload.end());
    return message;
}

std::vector<uint8_t> action_message(uint8_t message_type, uint32_t flags) {
    std::vector<uint8_t> message{message_type, 0, 0, 0};
    append_u32(message, static_cast<uint32_t>(-4));
    append_u32(message, flags);
    return message;
}

std::optional<std::vector<uint8_t>> provide_message(
    uint8_t message_type, const std::vector<uint8_t>& utf8_text) {
    if (utf8_text.size() > kMaximumTextBytes || !valid_utf8(utf8_text)) return std::nullopt;
    std::vector<uint8_t> normalized = normalize_android_text(utf8_text);
    if (normalized.size() > kMaximumTextBytes) return std::nullopt;
    normalized.push_back(0);
    std::vector<uint8_t> uncompressed;
    uncompressed.reserve(normalized.size() + 4U);
    append_u32(uncompressed, static_cast<uint32_t>(normalized.size()));
    uncompressed.insert(uncompressed.end(), normalized.begin(), normalized.end());
    const auto compressed = deflate_exact(uncompressed);
    if (!compressed.has_value()) return std::nullopt;
    const size_t body_size = 4U + compressed->size();
    if (body_size > kMaximumWireBytes || body_size > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        return std::nullopt;
    }
    std::vector<uint8_t> message{message_type, 0, 0, 0};
    append_u32(message, static_cast<uint32_t>(-static_cast<int32_t>(body_size)));
    append_u32(message, kActionProvide | kFormatText);
    message.insert(message.end(), compressed->begin(), compressed->end());
    return message;
}

std::optional<ExtendedMessage> parse_extended(const std::vector<uint8_t>& body) {
    if (body.size() < 4U || body.size() > kMaximumWireBytes) return std::nullopt;
    const uint32_t flags = read_u32(body.data());
    const auto action = decode_action(flags);
    if (!action.has_value()) return std::nullopt;
    ExtendedMessage message{*action, flags, {}};
    message.payload.assign(body.begin() + 4, body.end());
    if (*action != ExtendedAction::Caps && (flags & kActionMask) !=
        (flags & (kActionRequest | kActionPeek | kActionNotify | kActionProvide))) {
        return std::nullopt;
    }
    if (*action != ExtendedAction::Caps && *action != ExtendedAction::Provide && !message.payload.empty()) {
        return std::nullopt;
    }
    return message;
}

std::optional<Capabilities> parse_capabilities(const ExtendedMessage& message) {
    if (message.action != ExtendedAction::Caps) return std::nullopt;
    const uint32_t format_flags = message.flags & 0xffffU;
    const size_t format_count = static_cast<size_t>(std::popcount(format_flags));
    if (message.payload.size() != format_count * 4U) return std::nullopt;
    Capabilities capabilities{message.flags, 0};
    size_t size_index = 0;
    for (uint32_t bit = 0; bit < 16U; ++bit) {
        if ((format_flags & (1U << bit)) == 0) continue;
        const uint32_t maximum = read_u32(message.payload.data() + size_index * 4U);
        if (bit == 0) capabilities.maximum_text_bytes = maximum;
        ++size_index;
    }
    return capabilities;
}

std::optional<std::vector<uint8_t>> parse_provided_text(const ExtendedMessage& message) {
    if (message.action != ExtendedAction::Provide || (message.flags & kFormatText) == 0 ||
        (message.flags & 0xffffU) != kFormatText) {
        return std::nullopt;
    }
    const auto uncompressed = inflate_bounded(message.payload);
    if (!uncompressed.has_value() || uncompressed->size() < 5U) return std::nullopt;
    const uint32_t text_size = read_u32(uncompressed->data());
    if (text_size == 0 || text_size > kMaximumTextBytes + 1U ||
        uncompressed->size() != static_cast<size_t>(text_size) + 4U || uncompressed->back() != 0) {
        return std::nullopt;
    }
    std::vector<uint8_t> text(uncompressed->begin() + 4, uncompressed->end() - 1);
    return normalize_rfb_text(text);
}

}  // namespace rfb_clipboard
