// shell v2 framing (shell protocol) constants.
//
// Reference: AOSP packages/modules/adb
//   - shell_protocol.h:44-48        (stream ids)
//   - shell_service_protocol.cpp    (1-byte id + 4-byte BE length frame)
//
// Step 0: constants only. Frame encode/decode lands in Step 1.

#pragma once

#include <cstdint>

namespace adb {
namespace shell {

// Shell v2 stream identifiers (shell_protocol.h:44-48).
enum class StreamId : uint8_t {
    kStdin = 0,
    kStdout = 1,
    kStderr = 2,
    kExit = 3,
    kCloseStdin = 4,
    kWindowSizeChange = 5,
};

// Frame layout: [1 byte id][4 byte big-endian length][payload].
constexpr size_t kFrameHeaderSize = 5;

// Service-string argument tokens (AOSP services.h:24-26).
// A shell OPEN looks like: "shell[,arg1,arg2,...]:[command]",
// e.g. "shell,v2,raw:id".
constexpr char kServiceArgV2[] = "v2";
constexpr char kServiceArgRaw[] = "raw";
constexpr char kServiceArgPty[] = "pty";

// Service names (AOSP daemon/services.cpp:276 dispatch prefixes).
constexpr char kServiceShell[] = "shell";
constexpr char kServiceSync[] = "sync";
constexpr char kServiceExecPrefix[] = "exec:";

}  // namespace shell
}  // namespace adb
