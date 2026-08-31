#include "rfb_protocol.h"

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
    return 0;
}
