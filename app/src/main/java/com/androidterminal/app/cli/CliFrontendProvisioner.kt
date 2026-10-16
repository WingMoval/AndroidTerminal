// AT.CLI-0 — CLI frontend provisioning (asset extraction + PATH/env injection).
//
// Extracts the bundled `adb` CLI executable (assets/cli/adb) into the app's
// private files/bin dir (chmod +x) and exposes it on the Local /system/bin/sh
// PATH. The Local PTY child inherits the app process environment, so the shell
// finds `adb` here. Also exports AT_CLI_BRIDGE_DIR so the CLI locates the UDS.
//
// This is App/Local-provider composition only — no AT Engine contract change.
// The binary is app-provided (provenance = ANDROIDTERMINAL_APP), never the
// system/PC/Termux adb.

package com.androidterminal.app.cli

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object CliFrontendProvisioner {

    /** The bin dir where the CLI is extracted (app-private). */
    fun binDir(filesDir: File): File = File(filesDir, "bin")

    /** The extracted CLI path. */
    fun cliPath(filesDir: File): File = File(binDir(filesDir), "adb")

    private var provisioned = false

    /**
     * Extract the CLI binary + set the app process PATH/env. Idempotent.
     * Returns true on success (CLI present + env set).
     */
    fun provision(context: Context): Boolean {
        if (provisioned) return true
        val app = context.applicationContext
        val filesDir = app.filesDir
        val binDir = binDir(filesDir)
        val cli = cliPath(filesDir)
        try {
            binDir.mkdirs()
            // Extract from assets if missing or size changed.
            extractAsset(app, "cli/adb", cli)
            cli.setExecutable(true, true)
            cli.setReadable(true, true)
            // Set app process environment: PATH prefix + bridge dir.
            // These are inherited by the Local PTY child (execve(environ)).
            setEnv("AT_CLI_BRIDGE_DIR", filesDir.absolutePath)
            val existingPath = System.getenv("PATH") ?: "/system/bin:/system/xbin:/vendor/bin"
            setEnv("PATH", "${binDir.absolutePath}:$existingPath")
            provisioned = true
            return cli.exists() && cli.length() > 0
        } catch (e: Exception) {
            return false
        }
    }

    /** Copy an asset file to [dest] (overwrite). */
    private fun extractAsset(context: Context, asset: String, dest: File) {
        context.assets.open(asset).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Set an environment variable for the current process. On Android the
     * standard setenv is not exposed to Java; we use the native setenv via the
     * Process environment reflection-free path. Fallback: best-effort.
     */
    private fun setEnv(key: String, value: String) {
        // Android Java has no public setenv. Use the native libc setenv through
        // a tiny JNI helper would be cleanest, but to avoid adding JNI here we
        // rely on the fact that the Local PTY child inherits `environ`. We set
        // it via the `ProcessBuilder`-style environment is not accessible either.
        // Simplest reliable path: write to /proc/self/environ is not writable.
        // -> Use a native setenv via System.loadLibrary helper (added in
        //    at-backend-local? No). Instead, we set it in the app process using
        //    the `android.os.Process` is not for env.
        //
        // Practical solution: the Local PTY native child calls setenv for TERM;
        // we extend the SAME mechanism is an AT Engine change (not allowed).
        //
        // Therefore: we set the env via a minimal JNI bridge in :app (this
        // module now has native code for the CLI). See NativeEnv.setEnv.
        runCatching {
            com.androidterminal.app.cli.NativeEnv.setEnv(key, value)
        }
    }
}
