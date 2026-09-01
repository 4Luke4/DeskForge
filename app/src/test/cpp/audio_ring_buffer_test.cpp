#include "audio_ring_buffer.h"

#include <array>
#include <cassert>
#include <cstdint>

int main() {
    AudioRingBuffer<int16_t> buffer(4);
    const std::array<int16_t, 3> first{1, 2, 3};
    assert(buffer.capacity() == 4);
    assert(buffer.push(first.data(), first.size()) == first.size());

    std::array<int16_t, 4> output{};
    assert(buffer.pop(output.data(), 2) == 2);
    assert(output[0] == 1 && output[1] == 2);

    const std::array<int16_t, 4> wrapped{4, 5, 6, 7};
    assert(buffer.push(wrapped.data(), wrapped.size()) == 3);
    assert(buffer.pop(output.data(), output.size()) == output.size());
    assert(output[0] == 3 && output[1] == 4 && output[2] == 5 && output[3] == 6);

    assert(buffer.push(first.data(), first.size()) == first.size());
    buffer.clear_and_zero();
    assert(buffer.pop(output.data(), output.size()) == 0);
    const int16_t final_sample = 9;
    assert(buffer.push(&final_sample, 1) == 1);
    assert(buffer.pop(output.data(), 1) == 1 && output[0] == final_sample);
    return 0;
}
