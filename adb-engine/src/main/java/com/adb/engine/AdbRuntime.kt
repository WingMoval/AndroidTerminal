// ADB Engine — ADB-domain connection lifecycle facade (AT.V3-2.3).
//
// This is the narrow composition/lifecycle owner for the post-trust ADB
// connection. It wires the previously-migrated-but-unwired pieces (readiness,
// mDNS discovery, endpoint store, recovery coordinator) into a real lifecycle:
//
//   initialize(context)
//     -> readiness()
//     -> connectAuto(keyDir)      readiness gate + discovery + endpoint select + connect
//     -> connect(request)         explicit endpoint
//     -> state()
//     -> disconnect()
//     -> (operation-driven) bounded connection recovery
//
// Ownership boundary (Canonical V1 + V2 AT.RUNTIME-2D, re-owned by ADB domain):
//   - ADB lifecycle ownership  = this facade + AdbEngine (ADB domain)
//   - AT execution ownership   = ATEngine -> AdbBackend -> AdbEngine (AT domain)
// The execution consumer does NOT call AdbRuntime to run commands; it goes
// through ATEngine. AdbRuntime only establishes/maintains the ADB connection
// that the ADB Backend reuses.
//
// This facade exposes ONLY ADB-domain types. It has NO dependency on :at-engine
// and NEVER imports AT execution result / TerminalSession / Emulator / Screen
// State. It is the ADB-domain counterpart of the V2 AtRuntime facade.

package com.adb.engine

import android.content.Context
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ADB Engine connection lifecycle facade (process-scoped, post-trust).
 *
 * - `initialize(context)` is a platform bootstrap (wires Android readiness +
 *   NSD discovery backends). It is `:app`-independent: any consumer can call it
 *   with its own Context.
 * - `connectAuto(keyDir)` = readiness gate -> endpoint store rediscover ->
 *   multi-candidate connect (loopback-first) -> READY or bounded failure.
 * - `connect(request)` = explicit endpoint; records Explicit intent.
 * - After an operation, the caller MAY invoke [onOperationResult] with the
 *   transport outcome so the runtime can run bounded connection recovery
 *   (operation-driven). Recovery only restores the CONNECTION; it never
 *   re-executes the failed operation (no transparent command replay).
 *
 * NOT thread-safe by contract across the whole lifecycle; a single ReentrantLock
 * serializes connect/disconnect/recovery so there is exactly ONE recovery owner
 * (no parallel recovery coordinators, no mDNS scan storms, no connection storms).
 */
object AdbRuntime {

    private const val TAG = "AdbRuntime"

    @Volatile
    private var readinessBackend: AdbWirelessDebuggingReadinessBackend? = null

    @Volatile
    private var discoveryBackend: AdbDiscoveryBackend? = null

    @Volatile
    private var endpointStore: AdbEndpointStore? = null

    @Volatile
    private var recoveryCoordinator: AdbRecoveryCoordinator? = null

    @Volatile
    var state: AdbRuntimeState = AdbRuntimeState.DISCONNECTED
        private set

    /** The connection intent (how the connection was established). ADB-domain. */
    @Volatile
    var connectionIntent: AdbConnectionIntent = AdbConnectionIntent.None
        private set

    /** Test-only: override the recovery policy (bounded) for deterministic tests. */
    @Volatile
    var policyOverride: AdbRecoveryPolicy? = null

    private val lock = ReentrantLock()

    // ---- platform bootstrap ------------------------------------------------

    /**
     * Wire the Android readiness + NSD discovery backends and the endpoint
     * store / recovery coordinator. Idempotent. [context] is a platform
     * assembly dependency, not an execution-semantic input.
     */
    fun initialize(context: Context) {
        if (readinessBackend != null) return
        val app = context.applicationContext
        val readiness = AdbAndroidWirelessDebuggingReadinessBackend(app)
        val discovery = AdbAndroidNsdDiscoveryBackend(app)
        val store = AdbEndpointStore({ discovery })
        val coordinator = AdbRecoveryCoordinator(
            endpointStore = store,
            discoveryProvider = { discovery },
            readinessProvider = { readiness.query() },
            policyProvider = { policyOverride ?: AdbRecoveryPolicy.production },
        )
        this.readinessBackend = readiness
        this.discoveryBackend = discovery
        this.endpointStore = store
        this.recoveryCoordinator = coordinator
        Log.i(TAG, "initialize: readiness+discovery backends wired")
    }

    /** True when [initialize] has been called. */
    fun isInitialized(): Boolean = readinessBackend != null

    /**
     * Test-only bootstrap with injectable backends (deterministic lifecycle
     * tests). Production code must use [initialize].
     */
    fun initializeForTest(
        readiness: AdbWirelessDebuggingReadinessBackend,
        discovery: AdbDiscoveryBackend,
        policyProvider: () -> AdbRecoveryPolicy = { AdbRecoveryPolicy.production },
    ) {
        val store = AdbEndpointStore({ discovery })
        val coordinator = AdbRecoveryCoordinator(
            endpointStore = store,
            discoveryProvider = { discovery },
            readinessProvider = { readiness.query() },
            policyProvider = policyProvider,
        )
        this.readinessBackend = readiness
        this.discoveryBackend = discovery
        this.endpointStore = store
        this.recoveryCoordinator = coordinator
    }

    // ---- readiness ---------------------------------------------------------

    /**
     * Query Wireless Debugging readiness (detect + report; NEVER auto-enable).
     * Returns [AdbWirelessDebuggingState.UNKNOWN] when not initialized.
     */
    fun readiness(): AdbWirelessDebuggingState {
        val backend = readinessBackend
            ?: return AdbWirelessDebuggingState.UNKNOWN
        return backend.query()
    }

    // ---- connection lifecycle ---------------------------------------------

    /**
     * Auto-connect: readiness gate -> rediscover -> multi-candidate connect
     * (loopback-first, resolved fallback) -> READY or bounded failure.
     *
     * The caller must NOT know the port; discovery finds it.
     */
    fun connectAuto(keyDir: String): AdbRuntimeDiagnostic = lock.withLock {
        val store = requireStore()
        val wd = readiness()
        if (wd == AdbWirelessDebuggingState.DISABLED) {
            setState(AdbRuntimeState.USER_ACTION_REQUIRED)
            Log.i(TAG, "connectAuto: WD DISABLED -> USER_ACTION_REQUIRED")
            return AdbRuntimeDiagnostic(
                code = "USER_ACTION_REQUIRED_WD_DISABLED",
                message = "Wireless Debugging is disabled; enable it in Developer Options",
                readiness = wd,
                transport = transportState(),
            )
        }
        setState(AdbRuntimeState.CONNECTING)
        val candidates = store.rediscover()
        if (candidates.isEmpty()) {
            setState(AdbRuntimeState.ENDPOINT_NOT_FOUND)
            Log.w(TAG, "connectAuto: no endpoint discovered")
            return AdbRuntimeDiagnostic(
                code = "ENDPOINT_NOT_FOUND",
                message = "No Wireless ADB connect endpoint discovered",
                readiness = wd,
                transport = transportState(),
            )
        }
        if (tryConnectCandidates(candidates, keyDir)) {
            connectionIntent = AdbConnectionIntent.Auto(keyDir)
            setState(AdbRuntimeState.READY)
            val e = store.current()
            Log.i(TAG, "connectAuto READY via ${e?.host}:${e?.port}")
            return AdbRuntimeDiagnostic(
                code = "CONNECT_OK",
                message = "Connected via auto-discovered endpoint",
                readiness = wd,
                transport = transportState(),
            )
        }
        connectionIntent = AdbConnectionIntent.None
        setState(AdbRuntimeState.CONNECT_FAILED)
        return AdbRuntimeDiagnostic(
            code = "CONNECT_FAILED",
            message = "All discovered endpoints failed to connect",
            readiness = wd,
            transport = transportState(),
        )
    }

    /**
     * Explicit-endpoint connect. Records an Explicit intent. No mDNS.
     */
    fun connect(request: AdbConnectionRequest): AdbRuntimeDiagnostic = lock.withLock {
        val store = requireStore()
        setState(AdbRuntimeState.CONNECTING)
        if (connectOne(request.host, request.port, request.keyDir)) {
            store.setExplicit(request.host, request.port)
            connectionIntent = AdbConnectionIntent.Explicit(request.host, request.port, request.keyDir)
            setState(AdbRuntimeState.READY)
            return AdbRuntimeDiagnostic(
                code = "CONNECT_OK",
                message = "Connected to explicit endpoint ${request.host}:${request.port}",
                readiness = readiness(),
                transport = transportState(),
            )
        }
        connectionIntent = AdbConnectionIntent.None
        setState(AdbRuntimeState.CONNECT_FAILED)
        return AdbRuntimeDiagnostic(
            code = "CONNECT_FAILED",
            message = "Explicit connect failed for ${request.host}:${request.port}",
            readiness = readiness(),
            transport = transportState(),
        )
    }

    /**
     * Explicit manual disconnect. Clears the connection intent and endpoint.
     * This is NOT a transport loss: no auto-recovery is triggered.
     */
    fun disconnect(): AdbRuntimeDiagnostic = lock.withLock {
        try {
            AdbEngine.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "disconnect threw (ignored)", t)
        }
        endpointStore?.invalidate()
        connectionIntent = AdbConnectionIntent.None
        setState(AdbRuntimeState.DISCONNECTED)
        return AdbRuntimeDiagnostic(
            code = "DISCONNECT_OK",
            message = "Disconnected",
            readiness = readiness(),
            transport = transportState(),
        )
    }

    // ---- operation-driven recovery ----------------------------------------

    /**
     * Operation-driven loss hook. Call after an ADB operation with its transport
     * outcome classification. If we were READY and the operation hit a transport
     * failure and we hold a non-None intent, run a bounded recovery cycle.
     *
     * IMPORTANT (no transparent command replay): this only restores the
     * CONNECTION. The original operation's failure result is what the caller
     * returns to its consumer; AdbRuntime never re-executes it.
     *
     * @param transportLost true when the operation failed for a transport
     *        reason (the caller, e.g. the ADB Backend / AdbEngine result,
     *        classifies it — non-zero exit is NOT a transport loss).
     */
    fun onOperationResult(transportLost: Boolean) {
        if (!transportLost) return
        val wasReady = state == AdbRuntimeState.READY
        val intent = connectionIntent
        if (!wasReady || intent is AdbConnectionIntent.None) {
            // Not a "previously READY connection lost" situation; just reflect.
            setState(AdbRuntimeState.TRANSPORT_LOST)
            return
        }
        setState(AdbRuntimeState.TRANSPORT_LOST)
        Log.i(TAG, "transport loss detected; starting bounded recovery")
        recover(intent)
    }

    /**
     * Test-only: directly drive a recovery cycle for the given intent (R5/R6/R7).
     */
    fun recoverForTest(intent: AdbConnectionIntent) = lock.withLock {
        setState(AdbRuntimeState.TRANSPORT_LOST)
        recover(intent)
    }

    private fun recover(intent: AdbConnectionIntent) {
        val coordinator = recoveryCoordinator ?: run {
            setState(AdbRuntimeState.RECOVERY_FAILED)
            return
        }
        setState(AdbRuntimeState.RECOVERING)
        val ok = coordinator.recover(intent)
        if (ok) {
            connectionIntent = intent
            setState(AdbRuntimeState.READY)
            Log.i(TAG, "recovery SUCCEEDED -> READY")
        } else {
            // Keep the intent so a later manual reconnect (future) can resume the
            // same mode; state reflects bounded failure.
            setState(AdbRuntimeState.RECOVERY_FAILED)
            Log.w(TAG, "recovery FAILED (bounded) -> RECOVERY_FAILED")
        }
    }

    // ---- execution passthrough (ADB-domain; the AT execution path uses these) --

    /** ADB-domain one-shot shell. The AT execution path (ATEngine -> AdbBackend)
     *  reuses the same ONLINE connection established by this lifecycle. */
    fun executeShell(command: String, timeoutMs: Int = 0): AdbShellResult =
        AdbEngine.executeShell(command, timeoutMs)

    /** ADB-domain interactive stream open (remote PTY). */
    fun openInteractive(service: String): AdbInteractiveStream? = AdbEngine.openInteractive(service)

    // ---- diagnostics -------------------------------------------------------

    /** Factual transport projection (AdbConnectionState), for diagnostics. */
    fun transportState(): AdbConnectionState = AdbEngine.connectionState()

    /** Current lifecycle state (read-only). */
    fun readinessState(): AdbRuntimeState = state

    /**
     * Test-only reset so each deterministic lifecycle test starts clean.
     */
    fun resetForTest() = lock.withLock {
        try { AdbEngine.disconnect() } catch (_: Throwable) {}
        endpointStore?.invalidate()
        connectionIntent = AdbConnectionIntent.None
        state = AdbRuntimeState.DISCONNECTED
    }

    // ---- internals ---------------------------------------------------------

    private fun requireStore(): AdbEndpointStore =
        endpointStore ?: error("AdbRuntime.initialize(context) not called")

    /** Multi-candidate connect: loopback-first for each candidate, then resolved. */
    private fun tryConnectCandidates(candidates: List<AdbEndpoint>, keyDir: String): Boolean {
        for (candidate in candidates) {
            if (connectOne("127.0.0.1", candidate.port, keyDir)) {
                endpointStore?.set("127.0.0.1", candidate.port, AdbEndpointSource.MDNS_LOOPBACK)
                return true
            }
            if (!candidate.isLoopback && candidate.host != "127.0.0.1" &&
                connectOne(candidate.host, candidate.port, keyDir)
            ) {
                endpointStore?.set(candidate.host, candidate.port, AdbEndpointSource.MDNS_RESOLVED)
                return true
            }
        }
        return false
    }

    private fun connectOne(host: String, port: Int, keyDir: String): Boolean = try {
        AdbEngine.connect(host, port, keyDir)
    } catch (t: Throwable) {
        Log.e(TAG, "connect $host:$port threw", t)
        false
    }

    private fun setState(s: AdbRuntimeState) {
        state = s
    }
}

/**
 * ADB-domain connection diagnostic (code / message / readiness / transport).
 * Mirrors the V2 AtRuntimeDiagnostic shape, re-owned by the ADB domain.
 */
data class AdbRuntimeDiagnostic(
    val code: String,
    val message: String,
    val readiness: AdbWirelessDebuggingState,
    val transport: AdbConnectionState,
) {
    val isReady: Boolean get() = code == "CONNECT_OK"
}
