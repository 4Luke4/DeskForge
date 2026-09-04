#ifndef DESKFORGE_DIRECT_DISPLAY_RESOURCES_H
#define DESKFORGE_DIRECT_DISPLAY_RESOURCES_H

#include <cstddef>
#include <cstdint>
#include <unordered_map>

class DirectDisplayResources {
public:
    bool import_buffer(uint64_t token, uint64_t bytes);
    bool release_buffer(uint64_t token);
    bool queue_present(uint64_t token);
    bool complete_present(uint64_t token);
    void reset();

    [[nodiscard]] size_t imported_buffer_count() const;
    [[nodiscard]] uint64_t aggregate_bytes() const;
    [[nodiscard]] uint32_t pending_present_count() const;

private:
    struct BufferState {
        uint64_t bytes;
        uint32_t pending_presents;
    };

    std::unordered_map<uint64_t, BufferState> buffers_;
    uint64_t aggregate_bytes_ = 0;
    uint32_t pending_present_count_ = 0;
};

#endif
