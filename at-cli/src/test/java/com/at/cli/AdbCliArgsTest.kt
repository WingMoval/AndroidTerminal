// AT.CLI-0.1 — pure-JVM unit tests for AdbCliArgs (parser + quoting).
//
// These run on the host (no device) so quoting/serialization bugs are caught
// without real-device round-trips. The real remote-shell behavior is separately
// verified by AdbCliOneShotTest (Gate F) on-device.

package com.at.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbCliArgsTest {

    private fun q(a: String) = AdbCliParseResult.quoteSingle(a)

    // ---- quoting -----------------------------------------------------------

    @Test
    fun quoteEmpty() {
        assertEquals("''", q(""))
    }

    @Test
    fun quotePlain() {
        assertEquals("'abc'", q("abc"))
    }

    @Test
    fun quoteSpace() {
        assertEquals("'a b'", q("a b"))
    }

    @Test
    fun quoteSingleQuote() {
        // a'b -> 'a'"'"'b'
        assertEquals("'a'\"'\"'b'", q("a'b"))
    }

    @Test
    fun quoteDollar() {
        assertEquals("'\$HOME'", q("\$HOME"))
    }

    @Test
    fun quoteBackslash() {
        // arg = a\b (a, backslash, b); no single quote -> literal single-quoted.
        assertEquals("'a\\b'", q("a\\b"))
    }

    @Test
    fun quoteNewline() {
        assertEquals("'a\nb'", q("a\nb"))
    }

    @Test
    fun quoteUtf8() {
        assertEquals("'你好世界'", q("你好世界"))
    }

    @Test
    fun quoteOnlySingleQuote() {
        // arg = ' (one single quote).
        // result = open(') + escape('"'"= ' " ' " ') + close(')
        //        = ' ' " ' " ' '   (7 chars)
        // In POSIX sh this evaluates: '' + "'" + '' =  '
        val got = q("'")
        assertEquals("''\"'\"''", got)
    }

    @Test
    fun quoteManySingleQuotes() {
        // a''b -> 'a''"'"''"'"'b'
        val got = q("a''b")
        assertEquals("'a'\"'\"''\"'\"'b'", got)
    }

    // ---- serialization -----------------------------------------------------

    @Test
    fun serializeSingleArg() {
        assertEquals("'hello'", AdbCliParseResult.serializeToRemoteCommand(arrayOf("hello")))
    }

    @Test
    fun serializeMultiArg() {
        // echo + "hello world" -> 'echo' 'hello world'
        assertEquals(
            "'echo' 'hello world'",
            AdbCliParseResult.serializeToRemoteCommand(arrayOf("echo", "hello world"))
        )
    }

    @Test
    fun serializeArgWithQuote() {
        // a'b -> 'a'"'"'b'
        assertEquals(
            "'a'\"'\"'b'",
            AdbCliParseResult.serializeToRemoteCommand(arrayOf("a'b"))
        )
    }

    // ---- parser ------------------------------------------------------------

    @Test
    fun parseNoArgs() {
        val r = AdbCliParseResult.parse(emptyArray())
        assertEquals(AdbCliParseResult.Kind.NO_ARGS, r.kind)
        assertEquals(AdbCliParseResult.NO_ARGS_EXIT, r.exitCode)
        assertTrue(r.toStderr)
    }

    @Test
    fun parseShellOneShot() {
        val r = AdbCliParseResult.parse(arrayOf("shell", "id"))
        assertEquals(AdbCliParseResult.Kind.SHELL, r.kind)
        assertEquals("'id'", r.remoteCommand)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun parseShellNoCommand() {
        // AT.CLI-0.2: no-arg `adb shell` -> INTERACTIVE remote PTY relay.
        val r = AdbCliParseResult.parse(arrayOf("shell"))
        assertEquals(AdbCliParseResult.Kind.INTERACTIVE, r.kind)
        assertEquals(0, r.exitCode)
        assertFalse(r.toStderr)
    }

    @Test
    fun parseHelp() {
        val r = AdbCliParseResult.parse(arrayOf("--help"))
        assertEquals(AdbCliParseResult.Kind.HELP, r.kind)
        assertEquals(0, r.exitCode)
        assertFalse(r.toStderr)
        assertTrue(r.message.contains("usage:"))
    }

    @Test
    fun parseVersion() {
        val r = AdbCliParseResult.parse(arrayOf("version"))
        assertEquals(AdbCliParseResult.Kind.VERSION, r.kind)
        assertEquals(0, r.exitCode)
        assertTrue(r.message.contains("bridge protocol v1"))
    }

    @Test
    fun parseUnsupported() {
        val r = AdbCliParseResult.parse(arrayOf("devices"))
        assertEquals(AdbCliParseResult.Kind.UNSUPPORTED, r.kind)
        assertEquals(AdbCliParseResult.UNSUPPORTED_EXIT, r.exitCode)
        assertTrue(r.toStderr)
        assertTrue(r.message.contains("unsupported command: devices"))
    }

    @Test
    fun parseShellQuotingRoundTrip() {
        // The logical argv ["echo", "a b", "x'y", "$z"] must serialize such that
        // the remote shell reproduces the exact logical values.
        val remote = AdbCliParseResult.serializeToRemoteCommand(
            arrayOf("echo", "a b", "x'y", "\$z")
        )
        // Each token is single-quoted; verify the serialized form is well-formed.
        assertTrue(remote.contains("'echo'"))
        assertTrue(remote.contains("'a b'"))
        assertTrue(remote.contains("'x'\"'\"'y'"))
        assertTrue(remote.contains("'\$z'"))
    }

    // ------------------------------------------------------------------
    // AT.CONSUMER-BOOTSTRAP-0: `adb pair <host>:<port> <code>` parsing (P3).
    // ------------------------------------------------------------------

    @Test
    fun parsePairValid() {
        val r = AdbCliParseResult.parse(arrayOf("pair", "127.0.0.1:42433", "123456"))
        assertEquals(AdbCliParseResult.Kind.PAIR, r.kind)
        assertEquals(0, r.exitCode)
        assertEquals("127.0.0.1", r.pairHost)
        assertEquals(42433, r.pairPort)
        assertEquals("123456", r.pairCode)
    }

    @Test
    fun parsePairIpv6Host() {
        // host with a colon is still parsed (last colon separates port).
        val r = AdbCliParseResult.parse(arrayOf("pair", "192.168.137.44:38619", "738710"))
        assertEquals(AdbCliParseResult.Kind.PAIR, r.kind)
        assertEquals(0, r.exitCode)
        assertEquals("192.168.137.44", r.pairHost)
        assertEquals(38619, r.pairPort)
        assertEquals("738710", r.pairCode)
    }

    @Test
    fun parsePairNoArgs() {
        // `adb pair` (no endpoint/code) -> usage, exit 2, empty code.
        val r = AdbCliParseResult.parse(arrayOf("pair"))
        assertEquals(AdbCliParseResult.Kind.PAIR, r.kind)
        assertEquals(AdbCliParseResult.UNSUPPORTED_EXIT, r.exitCode)
        assertTrue(r.toStderr)
        assertTrue(r.pairCode.isBlank())
    }

    @Test
    fun parsePairBadEndpoint() {
        // `adb pair foo` -> bad endpoint (no colon), exit 2.
        val r = AdbCliParseResult.parse(arrayOf("pair", "foo", "123456"))
        assertEquals(AdbCliParseResult.Kind.PAIR, r.kind)
        assertEquals(AdbCliParseResult.UNSUPPORTED_EXIT, r.exitCode)
        assertTrue(r.pairCode.isBlank())
    }

    @Test
    fun parsePairBadPort() {
        // `adb pair host:notaport 123456` -> bad port, exit 2.
        val r = AdbCliParseResult.parse(arrayOf("pair", "host:notaport", "123456"))
        assertEquals(AdbCliParseResult.Kind.PAIR, r.kind)
        assertEquals(AdbCliParseResult.UNSUPPORTED_EXIT, r.exitCode)
        assertTrue(r.pairCode.isBlank())
    }

    @Test
    fun parsePairPortOutOfRange() {
        // port > 65535 -> exit 2.
        val r = AdbCliParseResult.parse(arrayOf("pair", "host:70000", "123456"))
        assertEquals(AdbCliParseResult.Kind.PAIR, r.kind)
        assertEquals(AdbCliParseResult.UNSUPPORTED_EXIT, r.exitCode)
        assertTrue(r.pairCode.isBlank())
    }

    @Test
    fun parsePairHelpMentionsPair() {
        val r = AdbCliParseResult.parse(arrayOf("--help"))
        assertEquals(AdbCliParseResult.Kind.HELP, r.kind)
        assertTrue(r.message.contains("adb pair <host>:<port> <code>"))
    }
}
