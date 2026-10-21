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
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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

    /** WRITE_SECURE_SETTINGS: product capability for post-bootstrap Auto-WD. */
    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    @Volatile
    private var appContext: Context? = null

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

    // ---- AT.CLI-0.1A: read-only lifecycle observation ----------------------

    /**
     * A read-only lifecycle observer. Consumers register to receive state
     * transitions; they CANNOT mutate Runtime state. Single owner of state is
     * still AdbRuntime (the [setState] path is the only writer).
     */
    fun interface AdbRuntimeListener {
        fun onStateChange(old: AdbRuntimeState, new: AdbRuntimeState)
    }

    @Volatile
    private var lifecycleListeners: CopyOnWriteArrayList<AdbRuntimeListener> =
        CopyOnWriteArrayList()

    /**
     * Register a read-only lifecycle listener. Returns a handle whose
     * [AdbRuntimeListenerHandle.remove] unregisters. Additive; does not change
     * state ownership.
     */
    fun addLifecycleListener(l: AdbRuntimeListener): AdbRuntimeListenerHandle {
        lifecycleListeners.add(l)
        return AdbRuntimeListenerHandle(l)
    }

    class AdbRuntimeListenerHandle(private val l: AdbRuntimeListener) {
        fun remove() = lifecycleListeners.remove(l)
    }

    // ---- AT.CLI-0.1A: read-only transport-loss observer --------------------
    //
    // A lightweight, READ-ONLY projection: it polls the *existing* native
    // transportState() (which the native RX owner already advances to OFFLINE on
    // a real loss) and, on a single ONLINE -> !ONLINE edge while we were READY,
    // invokes the EXISTING recovery ingress (onObservedTransportLost ->
    // onOperationResult(true)). It sends no ping, opens no socket, adds no
    // heartbeat, and owns no connection/recovery of its own.
    //
    // Lifecycle is bound to AdbRuntime (start in initialize, stop in
    // disconnect/resetForTest) — NOT to any Activity, so there is exactly ONE
    // observer for the single process-scoped Runtime.

    @Volatile
    private var transportObserverThread: Thread? = null

    @Volatile
    private var transportObserverRunning = AtomicBoolean(false)

    @Volatile
    private var lastTransportOnline = false

    private val TRANSPORT_POLL_MS = 1_500L

    // ---- platform bootstrap ------------------------------------------------

    /**
     * Wire the Android readiness + NSD discovery backends and the endpoint
     * store / recovery coordinator. Idempotent. [context] is a platform
     * assembly dependency, not an execution-semantic input.
     */
    fun initialize(context: Context) {
        if (readinessBackend != null) return
        val app = context.applicationContext
        this.appContext = app
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
        // AT.CLI-0.1A: start the read-only transport-loss observer, bound to the
        // Runtime lifecycle (one process-scoped Runtime -> one observer).
        startTransportObserver()
        Log.i(TAG, "initialize: readiness+discovery backends wired")
    }

    /** True when [initialize] has been called. */
    fun isInitialized(): Boolean = readinessBackend != null

    // ---- AT.CLI-0.1A: read-only transport-loss observer -------------------

    /**
     * Start the read-only transport-loss observer (idempotent). Bound to the
     * Runtime lifecycle: start once in [initialize]; stop in [disconnect]/
     * [resetForTest]. It only READS the existing [transportState] projection and,
     * on a single ONLINE -> !ONLINE edge while [readinessState] is READY, invokes
     * [onObservedTransportLost] (the EXISTING recovery ingress). No ping, no
     * socket, no second heartbeat/recovery owner.
     */
    fun startTransportObserver() {
        if (!transportObserverRunning.compareAndSet(false, true)) return
        lastTransportOnline = transportState().isOnline
        val t = Thread({
            while (transportObserverRunning.get()) {
                try {
                    val online = transportState().isOnline
                    if (lastTransportOnline && !online) {
                        // Edge-triggered: exactly one transition ONLINE -> !ONLINE.
                        if (readinessState() == AdbRuntimeState.READY) {
                            Log.i(TAG, "transport observer: ONLINE->!ONLINE edge; projecting loss")
                            onObservedTransportLost()
                        }
                    }
                    lastTransportOnline = online
                    Thread.sleep(TRANSPORT_POLL_MS)
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "transport observer tick failed", t)
                }
            }
        }, "adb-transport-observer")
        t.isDaemon = true
        transportObserverThread = t
        t.start()
        Log.i(TAG, "transport observer started (read-only projection)")
    }

    /** Stop the transport observer (idempotent). */
    fun stopTransportObserver() {
        if (!transportObserverRunning.compareAndSet(true, false)) return
        try { transportObserverThread?.interrupt() } catch (_: Throwable) {}
        transportObserverThread = null
        lastTransportOnline = false
        Log.i(TAG, "transport observer stopped")
    }

    /**
     * Internal, observed (non-operation) transport-loss ingress. Reuses the
     * EXISTING recovery path (onOperationResult) with zero logic change — the
     * observer never reconnects itself; the single recovery owner does.
     */
    fun onObservedTransportLost() {
        onOperationResult(transportLost = true)
    }

    // ---- AT.CLI-0.1A: Runtime-owned ensureReady ----------------------------

    /**
     * Runtime-owned readiness. If already READY, returns immediately (fast path;
     * no re-discovery / re-TLS). Otherwise runs the Runtime-owned sequence:
     * WD readiness -> (if WRITE_SECURE_SETTINGS effective) auto-enable WD ->
     * discovery + connect via [connectAuto]. Emits [statusListener] events so a
     * CLI can render `[adb] ...` status on stderr.
     *
     * CLI must NOT know port/endpoint/TLS/mDNS/recovery internals.
     */
    fun ensureReady(
        keyDir: String,
        statusListener: (String) -> Unit,
    ): AdbRuntimeState {
        // Fast path: already READY.
        if (state == AdbRuntimeState.READY) return AdbRuntimeState.READY
        statusListener("checking connection...")

        // WD readiness.
        val wd = readiness()
        if (wd == AdbWirelessDebuggingState.DISABLED) {
            statusListener("wireless debugging: off")
            if (canWriteSecureSettings()) {
                statusListener("enabling wireless debugging...")
                val enabled = enableWirelessDebugging()
                if (!enabled) {
                    Log.w(TAG, "ensureReady: auto-WD enable failed")
                    return AdbRuntimeState.USER_ACTION_REQUIRED
                }
                // Wait for the setting to actually take effect (listener starts).
                val wdOk = waitForWdEnabled(8_000L)
                if (!wdOk) {
                    Log.w(TAG, "ensureReady: WD still not enabled after putInt")
                    return AdbRuntimeState.USER_ACTION_REQUIRED
                }
            } else {
                // No capability: explicit user action required (NOT a timeout).
                statusListener("user action required: enable Wireless Debugging")
                return AdbRuntimeState.USER_ACTION_REQUIRED
            }
        }

        // After auto-WD (or any cold start), adbd may need a moment to begin
        // listening on the discovered endpoint. Retry connectAuto a bounded
        // number of times so a slow adbd bring-up does not surface a spurious
        // "connection failed" to the Human (Gate C: real listener must be up).
        var diag = AdbRuntimeDiagnostic(
            code = "CONNECTING",
            message = "connecting",
            readiness = readiness(),
            transport = transportState(),
        )
        var finalState = AdbRuntimeState.CONNECTING
        for (attempt in 1..3) {
            if (attempt == 1) {
                statusListener("discovering...")
                statusListener("connecting...")
            } else {
                statusListener("retrying connection ($attempt of 3)...")
            }
            diag = connectAuto(keyDir)
            finalState = readinessState()
            if (finalState == AdbRuntimeState.READY) break
            // Pairing / explicit user-action are not transient; don't retry.
            if (finalState == AdbRuntimeState.USER_ACTION_REQUIRED) break
            if (diag.code == "PAIRING_REQUIRED" || diag.message.contains("pair", ignoreCase = true)) break
            Thread.sleep(2_000L)
        }
        if (finalState == AdbRuntimeState.READY) {
            statusListener("connected")
        } else if (finalState == AdbRuntimeState.USER_ACTION_REQUIRED) {
            statusListener("user action required: enable Wireless Debugging")
        } else if (diag.code == "PAIRING_REQUIRED" || diag.message.contains("pair", ignoreCase = true)) {
            statusListener("pairing required")
        } else {
            statusListener("connection failed: ${diag.message.ifBlank { diag.code }}")
        }
        return finalState
    }

    /**
     * Whether this process can write Settings.Global (WRITE_SECURE_SETTINGS).
     * Read-only capability check; does NOT enable anything.
     */
    fun canWriteSecureSettings(): Boolean {
        val ctx = appContext ?: return false
        return try {
            ctx.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Auto-enable Wireless Debugging via Settings.Global (requires
     * WRITE_SECURE_SETTINGS). Returns true only when the write succeeded.
     * This is the POST-BOOTSTRAP capability (FIRST_USE_MANUAL_BOOTSTRAP = REQUIRED:
     * the permission is self-granted over the app's own verified ADB shell after
     * the first manual WD enable + trust).
     */
    fun enableWirelessDebugging(): Boolean {
        val ctx = appContext ?: return false
        return try {
            Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
            Log.i(TAG, "auto-WD: Settings.Global adb_wifi_enabled=1 written")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "auto-WD: putInt failed (no WRITE_SECURE_SETTINGS?)", t)
            false
        }
    }

    /** Poll readiness() until WD is ENABLED or [timeoutMs] elapses. */
    private fun waitForWdEnabled(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (readiness() == AdbWirelessDebuggingState.ENABLED) return true
            Thread.sleep(500)
        }
        return readiness() == AdbWirelessDebuggingState.ENABLED
    }

    // ---- AT.V4-4.3: First-use Auto-WD permission self-grant -----------------

    /**
     * Result of the first-use WRITE_SECURE_SETTINGS self-grant bootstrap.
     *
     * This is a FIRST-USE PRODUCT BOOTSTRAP operation, NOT a normal
     * readiness path. It runs exactly ONCE, after the first successful
     * pairing + connect, to grant the app its own WRITE_SECURE_SETTINGS
     * permission via the app's own verified ADB shell (uid=2000).
     *
     * The grant command is executed over the embedded ADB shell
     * ([executeShell], which reuses the ONLINE connection established by
     * [ensureReady]). The final truth is ALWAYS
     * [checkSelfPermission] on the app process — NOT the pm grant exit code.
     */
    enum class SelfGrantResult {
        /** Permission was already granted; no pm grant was executed. */
        SKIPPED_ALREADY_GRANTED,

        /** pm grant executed over embedded ADB shell; checkSelfPermission confirms granted. */
        GRANT_SUCCESS,

        /** pm grant was executed but checkSelfPermission still reports not granted. */
        GRANT_FAILED,

        /** Runtime not initialized or no ADB connection; cannot execute pm grant. */
        NOT_READY,
    }

    /**
     * AT.V4-4.3: First-use WRITE_SECURE_SETTINGS self-grant.
     *
     * MUST be called AFTER a successful pair + connect (state == READY).
     * The method is IDEMPOTENT: if the permission is already granted it
     * returns [SelfGrantResult.SKIPPED_ALREADY_GRANTED] without executing
     * any pm grant command.
     *
     * The pm grant command is executed over the embedded ADB shell
     * (uid=2000) — NOT via PC adb, Runtime.exec, su, or root.
     *
     * The target package is [Context.packageName] from the app's own
     * [appContext] — never hardcoded.
     *
     * Failure semantics: a self-grant failure does NOT invalidate the
     * pairing. The identity is NOT deleted, rotated, or re-paired. The
     * pairing remains SUCCESS; only the auto-WD bootstrap is FAILED.
     *
     * @return the [SelfGrantResult] classification.
     */
    fun selfGrantWriteSecureSettings(): SelfGrantResult {
        // Idempotency: if already granted, skip.
        if (canWriteSecureSettings()) {
            Log.i(TAG, "selfGrantWriteSecureSettings: already granted (skipped)")
            return SelfGrantResult.SKIPPED_ALREADY_GRANTED
        }

        val ctx = appContext ?: run {
            Log.w(TAG, "selfGrantWriteSecureSettings: no appContext")
            return SelfGrantResult.NOT_READY
        }

        // Must have an ONLINE ADB connection to execute pm grant.
        if (state != AdbRuntimeState.READY) {
            Log.w(TAG, "selfGrantWriteSecureSettings: ADB not READY (state=$state)")
            return SelfGrantResult.NOT_READY
        }

        val pkg = ctx.packageName
        val cmd = "pm grant $pkg $WRITE_SECURE_SETTINGS"
        Log.i(TAG, "selfGrantWriteSecureSettings: executing over embedded ADB shell: $cmd")

        val result = try {
            executeShell(cmd, timeoutMs = 10_000)
        } catch (t: Throwable) {
            Log.w(TAG, "selfGrantWriteSecureSettings: executeShell threw", t)
            return SelfGrantResult.GRANT_FAILED
        }

        Log.i(TAG, "selfGrantWriteSecureSettings: pm grant exitCode=${result.exitCode} " +
            "stdout='${result.stdout.trim()}' stderr='${result.stderr.trim()}'")

        // Final truth: checkSelfPermission on the app process.
        // (NOT the pm grant exit code — the permission model is the source of truth.)
        return if (canWriteSecureSettings()) {
            Log.i(TAG, "selfGrantWriteSecureSettings: GRANT_SUCCESS (checkSelfPermission confirms)")
            SelfGrantResult.GRANT_SUCCESS
        } else {
            Log.w(TAG, "selfGrantWriteSecureSettings: GRANT_FAILED (checkSelfPermission still not granted)")
            SelfGrantResult.GRANT_FAILED
        }
    }

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

    /**
     * ADB-domain pairing endpoint discovery (AT.V3-DEBT-1).
     *
     * Scans `_adb-tls-pairing._tcp.` through the SAME NSD backend used for the
     * connect service — no duplicated discovery implementation. Returns the
     * discovered pairing endpoints (host/port), or empty when the pairing
     * dialog is not open (the pairing service is transient: it only exists
     * while the user keeps the system pairing screen foreground).
     *
     * This is a mechanism-level primitive for parity/validation; it does NOT
     * implement a PairingCoordinator / state machine (deferred to ADB Engine
     * Completion).
     */
    fun discoverPairing(timeoutMs: Long = 10_000L): List<AdbDiscoveredService> {
        val backend = discoveryBackend ?: return emptyList()
        return try {
            backend.scan(timeoutMs, AdbDiscoveryServiceType.PAIRING)
        } catch (t: Throwable) {
            Log.w(TAG, "discoverPairing scan threw", t)
            emptyList()
        }
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
        // Explicit manual disconnect is NOT a transport loss; stop the observer so
        // it doesn't re-project a loss from a now-disconnected transport.
        stopTransportObserver()
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
        stopTransportObserver()
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
        val old = state
        if (old == s) return
        state = s
        // Read-only lifecycle notification (single owner still AdbRuntime).
        for (l in lifecycleListeners) {
            try {
                l.onStateChange(old, s)
            } catch (t: Throwable) {
                Log.w(TAG, "lifecycle listener threw", t)
            }
        }
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
