// ShellV2Protocol implementation.
//
// Reference: AOSP shell_service_protocol.cpp:32-53 (ShellProtocol::Read),
// shell_protocol.h:98-105 (kHeaderSize = 1B ID + 4B length).
//
// Frame: [1B id][4B length LE][payload of `length` bytes]
// A chunk may contain multiple frames; a frame may span multiple chunks.

#include "adb/service/shell_v2_protocol.h"

#include <cstring>

namespace adb {
namespace shell_v2 {

void ShellV2Protocol::reset() {
    buffer_.clear();
}

bool ShellV2Protocol::feed(const uint8_t* data, size_t size,
                           std::vector<Frame>* frames, std::string* error) {
    // 1. Append chunk to persistent buffer.
    buffer_.insert(buffer_.end(), data, data + size);

    // 2. Parse all complete frames.
    size_t offset = 0;
    while (offset + 5 <= buffer_.size()) {
        // Read header: [1B id][4B length LE]
        uint8_t id = buffer_[offset];
        uint32_t length = static_cast<uint32_t>(buffer_[offset + 1]) |
                          (static_cast<uint32_t>(buffer_[offset + 2]) << 8) |
                          (static_cast<uint32_t>(buffer_[offset + 3]) << 16) |
                          (static_cast<uint32_t>(buffer_[offset + 4]) << 24);

        // Safety: reject absurdly large lengths.
        if (length > kMaxFramePayload) {
            if (error) {
                *error = "shell v2: frame length " + std::to_string(length) +
                         " exceeds max " + std::to_string(kMaxFramePayload);
            }
            return false;
        }

        size_t total = 5 + static_cast<size_t>(length);
        if (offset + total > buffer_.size()) {
            // Incomplete frame — wait for more data.
            break;
        }

        // Validate known IDs.
        if (id > 5) {
            if (error) {
                *error = "shell v2: unknown stream id " + std::to_string(id);
            }
            return false;
        }

        // Extract frame.
        Frame frame;
        frame.id = id;
        frame.payload.assign(buffer_.begin() + offset + 5,
                             buffer_.begin() + offset + total);
        frames->push_back(std::move(frame));

        offset += total;
    }

    // 3. Remove consumed bytes, keep incomplete tail.
    if (offset > 0) {
        buffer_.erase(buffer_.begin(), buffer_.begin() + offset);
    }

    return true;
}

bool ShellV2Protocol::exitCode(const Frame& f, int* out_code) {
    // EXIT frame: id=3, payload exactly 1 byte (uint8_t).
    // Reference: AOSP daemon/shell_service.cpp:802-803,
    // client/commandline.cpp:320.
    if (f.id != static_cast<uint8_t>(Id::kExit)) return false;
    if (f.payload.size() != 1) return false;
    *out_code = static_cast<uint8_t>(f.payload[0]);
    return true;
}

}  // namespace shell_v2
}  // namespace adb
