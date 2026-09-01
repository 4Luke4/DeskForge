#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <vector>

/** Fixed-capacity SPSC storage suitable for AAudio callbacks after construction. */
template <typename Sample>
class AudioRingBuffer {
public:
    explicit AudioRingBuffer(size_t capacity) : storage_(capacity) {}

    AudioRingBuffer(const AudioRingBuffer&) = delete;
    AudioRingBuffer& operator=(const AudioRingBuffer&) = delete;

    size_t push(const Sample* source, size_t count) {
        const size_t write = write_index_.load(std::memory_order_relaxed);
        const size_t read = read_index_.load(std::memory_order_acquire);
        const size_t available = storage_.size() - std::min(storage_.size(), write - read);
        const size_t accepted = std::min(count, available);
        copy_in(source, accepted, write);
        write_index_.store(write + accepted, std::memory_order_release);
        return accepted;
    }

    size_t pop(Sample* destination, size_t count) {
        const size_t read = read_index_.load(std::memory_order_relaxed);
        const size_t write = write_index_.load(std::memory_order_acquire);
        const size_t accepted = std::min(count, write - read);
        copy_out(destination, accepted, read);
        read_index_.store(read + accepted, std::memory_order_release);
        return accepted;
    }

    void clear_and_zero() {
        std::fill(storage_.begin(), storage_.end(), Sample{});
        const size_t write = write_index_.load(std::memory_order_acquire);
        read_index_.store(write, std::memory_order_release);
    }

    size_t capacity() const { return storage_.size(); }

private:
    void copy_in(const Sample* source, size_t count, size_t write) {
        if (count == 0) return;
        const size_t first = std::min(count, storage_.size() - (write % storage_.size()));
        std::copy_n(source, first, storage_.begin() + static_cast<ptrdiff_t>(write % storage_.size()));
        std::copy_n(source + first, count - first, storage_.begin());
    }

    void copy_out(Sample* destination, size_t count, size_t read) {
        if (count == 0) return;
        const size_t first = std::min(count, storage_.size() - (read % storage_.size()));
        std::copy_n(storage_.begin() + static_cast<ptrdiff_t>(read % storage_.size()), first, destination);
        std::copy_n(storage_.begin(), count - first, destination + first);
    }

    std::vector<Sample> storage_;
    alignas(64) std::atomic<size_t> write_index_{0};
    alignas(64) std::atomic<size_t> read_index_{0};
};
