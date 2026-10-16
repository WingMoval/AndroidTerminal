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
    const val BOOTSTRAP_SCRIPT = "adb_bootstrap.sh"

    /**
     * Write the bootstrap script (env exports + the `adb` shell function) to
     * [scriptPath] in the app's private filesDir. The Local /system/bin/sh
     * (same uid) can source it. Returns the script's absolute path.
     *
     * Written to a FILE (not via the PTY) because a multi-line function
     * definition sent through the interactive PTY gets mangled; sourcing a
     * file is robust.
     */
    fun writeScript(scriptPath: String, apkPath: String, sockPath: String): String {
        val apk = apkPath.replace("'", "'\"'\"'")
        val sock = sockPath.replace("'", "'\"'\"'")
        // The shell function must reference the shell variable $AT_APP_APK.
        // Emit a literal '$' (via char) so Kotlin does not template-expand it.
        val shellApkVar = "$" + ENV_APK   // -> "$AT_APP_APK"
        val script = buildString {
            appendLine("export $ENV_APK='$apk'")
            appendLine("export $ENV_SOCK='$sock'")
            appendLine(
                "adb() { $APP_PROCESS -Djava.class.path=\"$shellApkVar\" /system/bin " +
                    "$ADB_MAIN_CLASS \"\$@\"; }"
            )
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
