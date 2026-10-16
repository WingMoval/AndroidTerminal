// AT.CLI-0.1 — AdbCliMain parser + shell-quoting helper (production, pure JVM).
//
// The outer `/system/bin/sh` already did ONE pass of shell parsing, so AdbCliMain
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

package com.androidterminal.app.cli

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
) {
    enum class Kind { SHELL, INTERACTIVE, HELP, VERSION, UNSUPPORTED, NO_ARGS }

    companion object {
        const val UNSUPPORTED_EXIT = 2
        const val NO_ARGS_EXIT = 2

        val HELP_TEXT = "usage: adb shell <command...>\n" +
            "       adb shell              (interactive remote PTY shell)\n" +
            "       adb --help | adb version\n" +
            "AndroidTerminal product CLI (app_process entrypoint; one-shot + interactive)."

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
                "--help", "-h", "help" ->
                    AdbCliParseResult(kind = Kind.HELP, message = HELP_TEXT, exitCode = 0, toStderr = false)
                "version" ->
                    AdbCliParseResult(kind = Kind.VERSION, message = VERSION_TEXT, exitCode = 0, toStderr = false)
                else ->
                    AdbCliParseResult(
                        kind = Kind.UNSUPPORTED,
                        message = "adb: unsupported command: $sub (this build supports only `shell <command...>`) " +
                            "[AT.CLI-0.1]",
                        exitCode = UNSUPPORTED_EXIT,
                        toStderr = true,
                    )
            }
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
