// AT.CLI-0.1 — shell-visible `adb` launcher for the Local /system/bin/sh.
//
// The real Local PTY shell (untrusted_app domain) CANNOT exec the private
// `files/bin/adb` ELF (SELinux: untrusted_app -> app_data_file execute denied,
// RC=126). Instead we expose a SHELL FUNCTION `adb` in the Local session's
// environment. The function launches the app_process-based production
// AdbCliMain (zygote_exec is executable by untrusted_app):
//
//   adb() { /system/bin/app_process -Djava.class.path="$AT_APP_APK" /system/bin
//             com.androidterminal.app.cli.AdbCliMain "$@"; }
//
// The APK path is injected by the APP at Local session bootstrap (no `pm path`
// per command, no hardcoded /data/app path — the install path changes). The
// bridge socket path is also injected so AdbCliMain need not guess it.
//
// `command -v adb` resolves to the function in the real Local session.
// This is NOT an ELF, NOT an alias to files/bin/adb.

package com.androidterminal.app.cli

object LocalSessionBootstrap {

    const val ADB_MAIN_CLASS = "com.androidterminal.app.cli.AdbCliMain"
    const val APP_PROCESS = "/system/bin/app_process"
    const val ENV_APK = "AT_APP_APK"
    const val ENV_SOCK = "AT_CLI_BRIDGE_SOCK"
    const val ENV_SESSION_ID = "AT_SESSION_ID"
    const val BOOTSTRAP_SCRIPT = "adb_bootstrap.sh"

    /**
     * Write a per-session bootstrap script (env exports + the `adb` shell
     * function) to [scriptPath] in the app's private filesDir. The Local
     * /system/bin/sh (same uid) can source it. Returns the script's absolute
     * path.
     *
     * [sessionId] is the Local session's stable page id, exported as
     * $AT_SESSION_ID so AdbCliMain can bind ADB lifecycle status to THIS session
     * (AT.CLI-0.1A). Written to a per-session FILE (not via the PTY) because a
     * multi-line function definition sent through the interactive PTY gets
     * mangled; sourcing a file is robust.
     */
    // AT.CLI-0.2: env vars carrying the Outer Local PTY size to AdbCliMain for
    // the initial remote PTY size (§21). Read from `stty size` by the shell
    // wrapper ONLY on the interactive (no-arg) `adb shell` form.
    const val ENV_ROWS = "AT_CLI_ROWS"
    const val ENV_COLS = "AT_CLI_COLS"

    fun writeScript(scriptPath: String, apkPath: String, sockPath: String, sessionId: Int): String {
        val apk = apkPath.replace("'", "'\"'\"'")
        val sock = sockPath.replace("'", "'\"'\"'")
        // The shell function must reference the shell variables. Emit literal '$'
        // (via char) so Kotlin does not template-expand them.
        val d = "$" // literal dollar
        val shellApkVar = d + ENV_APK   // -> "$AT_APP_APK"
        val script = buildString {
            appendLine("export $ENV_APK='$apk'")
            appendLine("export $ENV_SOCK='$sock'")
            appendLine("export $ENV_SESSION_ID='$sessionId'")
            // AT.CLI-0.2: adb() wrapper.
            //   - `adb shell` (no command, $# -eq 1): INTERACTIVE remote PTY.
            //     Save the Outer Local PTY termios, switch to raw/no-echo so the
            //     relay is byte-oriented (no line buffering, no double-echo,
            //     Ctrl-C 0x03 reaches the remote instead of killing the local
            //     child), capture `stty size` for the initial remote PTY, run
            //     AdbCliMain, then RESTORE the saved termios (hard gate §16) —
            //     regardless of exit path.
            //   - `adb shell <cmd...>` (one-shot) and other forms: plain launch,
            //     NO raw-mode switch (one-shot keeps canonical Local TTY).
            appendLine("adb() {")
            appendLine("  if [ \"\$1\" = shell ] && [ \"\$#\" -eq 1 ]; then")
            appendLine("    local _old _sz _r _c")
            appendLine("    _old=\"\$(stty -g 2>/dev/null)\"")
            appendLine("    _sz=\"\$(stty size 2>/dev/null)\"")
            appendLine("    _r=\$(printf '%s' \"\$_sz\" | awk '{print \$1}')")
            appendLine("    _c=\$(printf '%s' \"\$_sz\" | awk '{print \$2}')")
            appendLine("    stty raw -echo 2>/dev/null")
            appendLine("    $ENV_ROWS=\"\$_r\" $ENV_COLS=\"\$_c\" $APP_PROCESS -Djava.class.path=\"$shellApkVar\" /system/bin $ADB_MAIN_CLASS \"\$@\"")
            appendLine("    local _rc=\$?")
            appendLine("    [ -n \"\$_old\" ] && stty \"\$_old\" 2>/dev/null")
            appendLine("    return \$_rc")
            appendLine("  fi")
            appendLine("  $APP_PROCESS -Djava.class.path=\"$shellApkVar\" /system/bin $ADB_MAIN_CLASS \"\$@\"")
            appendLine("}")
        }
        java.io.File(scriptPath).writeText(script)
        return scriptPath
    }

    /**
     * The single banner line that sources the bootstrap script. Written to the
     * Local PTY (one line, robust). `command -v adb` resolves after this.
     */
    fun sourceLine(scriptPath: String): String =
        ". '$scriptPath'\n"
}
