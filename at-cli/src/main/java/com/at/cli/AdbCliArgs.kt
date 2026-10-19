// AT.CLI-0.1 — AtCliMain parser + shell-quoting helper (production, pure JVM).
//
// The outer `/system/bin/sh` already did ONE pass of shell parsing, so AtCliMain
// receives the LOGICAL argv (e.g. `adb shell echo "hello world"` ->
// ["shell", "echo", "hello world"]). To run that on the REMOTE shell we must
// re-serialize the logical argv into a remote command line WITHOUT losing
// quoting. Naive `joinToString(" ")` breaks on spaces/quotes/$/etc.
//
// Serialization = POSIX single-quote wrapping (single quotes are literal in
// POSIX sh, so the only character to escape is `'` itself):
//
//   abc      -> 'abc'
//   a b      -> 'a b'
//   a'b      -> 'a'"'"'b'
//   $HOME    -> '$HOME'
//   (empty)  -> ''
//
// This is verified against the real remote shell in AdbCliOneShotTest (Gate F).
// Pure JVM — no Android imports — so it is unit-testable on the host.

package com.at.cli

/**
 * Result of parsing the CLI argv (after the outer shell's own parse).
 *
 * [kind] is one of [Kind]. For [Kind.SHELL], [remoteCommand] is the fully
 * serialized remote command line (single-quoted args joined by spaces) ready to
 * hand to the ADB one-shot backend. [exitCode] is the process exit code for
 * non-exec kinds (help/version=0, unsupported=2, noArgs=2).
 */
data class AdbCliParseResult(
    val kind: Kind,
    val remoteCommand: String = "",
    val message: String = "",
    val exitCode: Int = 0,
    val toStderr: Boolean = false,
    // AT.CONSUMER-BOOTSTRAP-0: for Kind.PAIR, the parsed explicit endpoint + code.
    val pairHost: String = "",
    val pairPort: Int = 0,
    val pairCode: String = "",
) {
    enum class Kind { SHELL, INTERACTIVE, HELP, VERSION, UNSUPPORTED, NO_ARGS, PAIR }

    companion object {
        const val UNSUPPORTED_EXIT = 2
        const val NO_ARGS_EXIT = 2

        val HELP_TEXT = "usage: adb shell <command...>\n" +
            "       adb shell              (interactive remote PTY shell)\n" +
            "       adb pair <host>:<port> <code>\n" +
            "       adb --help | adb version\n" +
            "AndroidTerminal product CLI (app_process entrypoint; one-shot + interactive + pair)."

        const val VERSION_TEXT = "AndroidTerminal adb CLI (app_process entrypoint, bridge protocol v1)"

        /**
         * Parse the logical argv (WITHOUT the leading "adb" token, which the
         * shell function consumed). Returns the action to take.
         */
        fun parse(args: Array<String>): AdbCliParseResult {
            if (args.isEmpty()) {
                return AdbCliParseResult(
                    kind = Kind.NO_ARGS,
                    message = "adb: missing command (try `adb --help`)",
                    exitCode = NO_ARGS_EXIT,
                    toStderr = true,
                )
            }
            val sub = args[0]
            return when (sub) {
                "shell" -> {
                    val rest = args.copyOfRange(1, args.size)
                    if (rest.isEmpty()) {
                        // AT.CLI-0.2: interactive `adb shell` (no command) ->
                        // remote PTY relay. The shell wrapper (LocalSessionBootstrap)
                        // puts the Outer Local PTY into raw/no-echo mode for this
                        // form ONLY; one-shot `adb shell <cmd...>` does not.
                        AdbCliParseResult(
                            kind = Kind.INTERACTIVE,
                            message = "",
                            exitCode = 0,
                            toStderr = false,
                        )
                    } else {
                        AdbCliParseResult(
                            kind = Kind.SHELL,
                            remoteCommand = serializeToRemoteCommand(rest),
                            exitCode = 0,
                        )
                    }
                }
                "pair" -> parsePair(args.copyOfRange(1, args.size))
                "--help", "-h", "help" ->
                    AdbCliParseResult(kind = Kind.HELP, message = HELP_TEXT, exitCode = 0, toStderr = false)
                "version" ->
                    AdbCliParseResult(kind = Kind.VERSION, message = VERSION_TEXT, exitCode = 0, toStderr = false)
                else ->
                    AdbCliParseResult(
                        kind = Kind.UNSUPPORTED,
                        message = "adb: unsupported command: $sub (this build supports `shell <command...>` and `pair <host>:<port> <code>`) " +
                            "[AT.CLI-0.1]",
                        exitCode = UNSUPPORTED_EXIT,
                        toStderr = true,
                    )
            }
        }

        /**
         * Parse `adb pair <host>:<port> <code>` (AT.CONSUMER-BOOTSTRAP-0).
         *
         * Standard form (REQUIRED): `adb pair <host>:<port> <code>`.
         * The host:port is the EXPLICIT pairing endpoint (the user reads it from
         * the system "pair device with pairing code" dialog). The code is the
         * 6-digit pairing code.
         *
         * Validation failure (bad args) returns Kind.PAIR with a non-zero
         * exitCode and an empty pairCode, so the frontend prints usage WITHOUT
         * triggering the underlying pairing (P3 gate). No shorthand `adb pair
         * <code>` is implemented (DEFERRED per §4 — keeps scope minimal).
         */
        private fun parsePair(rest: Array<String>): AdbCliParseResult {
            if (rest.size != 2) {
                return AdbCliParseResult(
                    kind = Kind.PAIR,
                    message = "usage: adb pair <host>:<port> <code>",
                    exitCode = UNSUPPORTED_EXIT,
                    toStderr = true,
                )
            }
            val endpoint = rest[0]
            val code = rest[1]
            val colon = endpoint.lastIndexOf(':')
            if (colon <= 0 || colon == endpoint.length - 1) {
                return AdbCliParseResult(
                    kind = Kind.PAIR,
                    message = "adb pair: bad endpoint '$endpoint' (expected host:port)",
                    exitCode = UNSUPPORTED_EXIT,
                    toStderr = true,
                )
            }
            val host = endpoint.substring(0, colon)
            val portText = endpoint.substring(colon + 1)
            val port = portText.toIntOrNull()
            if (port == null || port <= 0 || port > 65535) {
                return AdbCliParseResult(
                    kind = Kind.PAIR,
                    message = "adb pair: bad port '$portText' (expected 1..65535)",
                    exitCode = UNSUPPORTED_EXIT,
                    toStderr = true,
                )
            }
            if (code.isBlank()) {
                return AdbCliParseResult(
                    kind = Kind.PAIR,
                    message = "adb pair: missing pairing code",
                    exitCode = UNSUPPORTED_EXIT,
                    toStderr = true,
                )
            }
            return AdbCliParseResult(
                kind = Kind.PAIR,
                pairHost = host,
                pairPort = port,
                pairCode = code,
                exitCode = 0,
            )
        }

        /**
         * Serialize the logical argv (the remote command + its args, as parsed by
         * the OUTER shell) into a single remote command line. Each element is
         * single-quoted (POSIX-literal), so spaces/quotes/$/backslash/newline/
         * UTF-8 are preserved exactly as the outer shell intended.
         */
        fun serializeToRemoteCommand(args: Array<String>): String =
            args.joinToString(" ") { quoteSingle(it) }

        /**
         * POSIX single-quote wrap. The only metacharacter that must be escaped
         * inside single quotes is `'` itself, emitted as `'` + `'"'"` + `'`
         * (close quote, literal double-quoted quote, open quote).
         */
        fun quoteSingle(arg: String): String {
            if (arg.isEmpty()) return "''"
            // Fast path: no single quote present.
            if (!arg.contains('\'')) return "'" + arg + "'"
            val sb = StringBuilder(arg.length + 8)
            sb.append('\'')
            for (ch in arg) {
                if (ch == '\'') {
                    sb.append("'\"'\"'")
                } else {
                    sb.append(ch)
                }
            }
            sb.append('\'')
            return sb.toString()
        }
    }
}
