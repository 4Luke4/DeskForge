#include "direct_display_resources.h"

#include "direct_display_config.h"

bool DirectDisplayResources::import_buffer(uint64_t token, uint64_t bytes) {
    if (token == 0 || bytes == 0 || bytes > DESKFORGE_DISPLAY_MAXIMUM_BUFFER_BYTES ||
        bytes > DESKFORGE_DISPLAY_MAXIMUM_AGGREGATE_BYTES ||
        buffers_.size() >= DESKFORGE_DISPLAY_MAXIMUM_IMPORTED_PIXMAPS ||
        buffers_.contains(token) ||
        aggregate_bytes_ > DESKFORGE_DISPLAY_MAXIMUM_AGGREGATE_BYTES - bytes) {
        return false;
    }
    buffers_.emplace(token, BufferState{bytes, 0});
    aggregate_bytes_ += bytes;
    return true;
}

bool DirectDisplayResources::release_buffer(uint64_t token) {
    const auto buffer = buffers_.find(token);
    if (buffer == buffers_.end() || buffer->second.pending_presents != 0) return false;
    aggregate_bytes_ -= buffer->second.bytes;
    buffers_.erase(buffer);
    return true;
}

bool DirectDisplayResources::queue_present(uint64_t token) {
    const auto buffer = buffers_.find(token);
    if (buffer == buffers_.end() ||
        pending_present_count_ >= DESKFORGE_DISPLAY_MAXIMUM_PENDING_PRESENTS) {
        return false;
    }
    ++buffer->second.pending_presents;
    ++pending_present_count_;
    return true;
}

bool DirectDisplayResources::complete_present(uint64_t token) {
    const auto buffer = buffers_.find(token);
    if (buffer == buffers_.end() || buffer->second.pending_presents == 0 ||
        pending_present_count_ == 0) {
        return false;
    }
    --buffer->second.pending_presents;
    --pending_present_count_;
    return true;
}

void DirectDisplayResources::reset() {
    buffers_.clear();
    aggregate_bytes_ = 0;
    pending_present_count_ = 0;
}

size_t DirectDisplayResources::imported_buffer_count() const {
    return buffers_.size();
}

uint64_t DirectDisplayResources::aggregate_bytes() const {
    return aggregate_bytes_;
}

uint32_t DirectDisplayResources::pending_present_count() const {
    return pending_present_count_;
}
