#include "rfb_protocol.h"
#include "rfb_clipboard.h"

#include <cassert>

int main() {
    assert(rfb_protocol::supported_version("RFB 003.008\n", 12));
    assert(!rfb_protocol::supported_version("RFB 003.007\n", 12));
    assert(!rfb_protocol::supported_version("RFB 003.003\n", 12));
    assert(!rfb_protocol::supported_version("NOT 003.008\n", 12));

    assert(rfb_protocol::framebuffer_bytes(2560, 1600).value() == 16'384'000);
    assert(!rfb_protocol::framebuffer_bytes(4097, 1600).has_value());
    assert(!rfb_protocol::framebuffer_bytes(4096, 4097).has_value());

    assert(rfb_protocol::rectangle_within(2560, 1600, 0, 0, 2560, 1600));
    assert(rfb_protocol::rectangle_within(2560, 1600, 2559, 1599, 1, 1));
    assert(!rfb_protocol::rectangle_within(2560, 1600, 2559, 1599, 2, 1));
    assert(!rfb_protocol::rectangle_within(2560, 1600, 0xffffffffU, 0, 1, 1));

    const auto resize = rfb_protocol::set_desktop_size_message(2560, 1600, 0x01020304, 0xa0b0c0d0);
    assert(resize.has_value());
    assert(resize->size() == 25);
    assert((*resize)[0] == 251 && (*resize)[1] == 0 && (*resize)[2] == 0);
    assert((*resize)[3] == 10 && (*resize)[4] == 0);
    assert((*resize)[5] == 6 && (*resize)[6] == 64);
    assert((*resize)[7] == 1 && (*resize)[8] == 0);
    assert((*resize)[9] == 1 && (*resize)[10] == 2 && (*resize)[11] == 3 && (*resize)[12] == 4);
    assert((*resize)[21] == 0xa0 && (*resize)[24] == 0xd0);
    assert(!rfb_protocol::set_desktop_size_message(4097, 1600, 1, 0).has_value());

    const std::vector<uint8_t> multilingual{'H', 'i', ' ', 0xd0, 0x96, '\n'};
    assert(rfb_clipboard::valid_utf8(multilingual));
    assert(!rfb_clipboard::valid_utf8({0xc0, 0xaf}));
    const auto provided = rfb_clipboard::provide_message(6, multilingual);
    assert(provided.has_value());
    assert((*provided)[0] == 6);
    const size_t provided_body_size =
        (static_cast<size_t>((*provided)[4]) << 24U) |
        (static_cast<size_t>((*provided)[5]) << 16U) |
        (static_cast<size_t>((*provided)[6]) << 8U) |
        static_cast<size_t>((*provided)[7]);
    const size_t signed_body_size = static_cast<size_t>(
        -static_cast<int32_t>(static_cast<uint32_t>(provided_body_size)));
    assert(signed_body_size == provided->size() - 8U);
    const std::vector<uint8_t> provided_body(provided->begin() + 8, provided->end());
    const auto parsed_provide = rfb_clipboard::parse_extended(provided_body);
    assert(parsed_provide.has_value());
    const auto parsed_text = rfb_clipboard::parse_provided_text(*parsed_provide);
    assert(parsed_text.has_value());
    assert(*parsed_text == multilingual);

    const auto caps = rfb_clipboard::caps_message(6);
    const std::vector<uint8_t> caps_body(caps.begin() + 8, caps.end());
    const auto parsed_caps_message = rfb_clipboard::parse_extended(caps_body);
    assert(parsed_caps_message.has_value());
    const auto parsed_caps = rfb_clipboard::parse_capabilities(*parsed_caps_message);
    assert(parsed_caps.has_value());
    assert((parsed_caps->flags & rfb_clipboard::kFormatText) != 0);
    assert(parsed_caps->maximum_text_bytes == 0);

    const auto maximum_text = rfb_clipboard::provide_message(
        6, std::vector<uint8_t>(rfb_clipboard::kMaximumTextBytes, 'x'));
    assert(maximum_text.has_value());
    const std::vector<uint8_t> maximum_body(maximum_text->begin() + 8, maximum_text->end());
    const auto parsed_maximum = rfb_clipboard::parse_extended(maximum_body);
    assert(parsed_maximum.has_value());
    const auto maximum_round_trip = rfb_clipboard::parse_provided_text(*parsed_maximum);
    assert(maximum_round_trip.has_value());
    assert(maximum_round_trip->size() == rfb_clipboard::kMaximumTextBytes);

    assert(!rfb_clipboard::provide_message(
        6, std::vector<uint8_t>(rfb_clipboard::kMaximumTextBytes + 1U, 'x')).has_value());
    assert(!rfb_clipboard::parse_extended({0, 0, 0}).has_value());
    assert(!rfb_clipboard::parse_extended({0xff, 0xff, 0xff, 0xff}).has_value());
    return 0;
}
