// ShellV2Protocol: independent parser for ADB shell v2 frames.
//
// Frame format (AOSP shell_protocol.h:103-104, Reference v1):
//   [1 byte ID][4 byte length LE][payload of `length` bytes]
//   Header = 5 bytes.
//
// IDs (AOSP shell_protocol.h:44-58):
//   0 = stdin, 1 = stdout, 2 = stderr, 3 = exit (1 byte),
//   4 = close_stdin, 5 = window_size_change
//
// This class is completely independent of ADB packets, TCP, TLS,
// OPEN/WRTE/CLSE, local_id, remote_id. It only consumes byte chunks
// and produces parsed frames. It maintains a persistent internal
// buffer to handle frames that span multiple chunks.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace adb {
namespace shell_v2 {

// A single parsed shell v2 frame.
struct Frame {
    uint8_t id;
    std::vector<uint8_t> payload;
};

// Stream IDs (AOSP shell_protocol.h:44-58).
enum class Id : uint8_t {
    kStdin = 0,
    kStdout = 1,
    kStderr = 2,
    kExit = 3,        // payload = exactly 1 byte (uint8_t exit code)
    kCloseStdin = 4,
    kWindowSizeChange = 5,
};

// Maximum allowed frame payload size (safety limit).
// Reference: AOSP MAX_PAYLOAD = 1 MiB. A single shell v2 frame
// should never exceed the ADB WRTE payload limit.
constexpr uint32_t kMaxFramePayload = 1024 * 1024;

class ShellV2Protocol {
public:
    ShellV2Protocol() = default;
    ~ShellV2Protocol() = default;

    // Reset the internal buffer (e.g. between shell commands).
    void reset();

    // Feed a chunk of bytes (from AdbStream WRTE data).
    // Appends to the persistent buffer, then parses all complete frames.
    //
    // `frames` receives all complete frames parsed from this chunk
    // plus any previously buffered partial frame.
    //
    // Returns true on success, false on protocol error (e.g. invalid
    // length, unknown ID with strict mode). The `error` string gets a
    // human-readable message on failure.
    bool feed(const uint8_t* data, size_t size,
              std::vector<Frame>* frames, std::string* error);

    // Extract the 1-byte exit code from an EXIT frame.
    // Returns true if the frame is a valid EXIT frame (id=3, payload=1).
    static bool exitCode(const Frame& f, int* out_code);

    // Number of bytes currently in the internal buffer (unparsed tail).
    size_t buffered() const { return buffer_.size(); }

private:
    std::vector<uint8_t> buffer_;
};

}  // namespace shell_v2
}  // namespace adb
