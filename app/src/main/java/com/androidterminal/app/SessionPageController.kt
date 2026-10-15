// AndroidTerminal V3 — Session Page Controller（App/UI 层）。
//
// AT.DEVAPP-0.2. Dynamically creates / switches / closes independent session
// pages. Each page owns its own AppTerminal -> ATTerminalSession. Switching a
// page only changes which page is the "active" one; it never closes / recreates
// / resets an existing session (PTY / cwd/env / output preserved).

package com.androidterminal.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * Dynamic session page controller.
 *
 * Maintains an ordered list of [SessionPage] and a single active index.
 * Operations:
 *  - [add] create a new independent session page (returns it).
 *  - [closeActive] close the active page's session and remove it.
 *  - [prev]/[next] move active index without touching sessions.
 *  - [closeAll] close every session (Activity destroy).
 *
 * Not thread-safe on its own; UI calls are main-thread.
 */
class SessionPageController {

    private val idSeq = AtomicInteger(0)
    private val pages = ArrayList<SessionPage>()
    private var activeIndex = -1

    val size: Int get() = pages.size
    val isEmpty: Boolean get() = pages.isEmpty()

    /** The active page, or null when no pages exist. */
    val activePage: SessionPage?
        get() = if (activeIndex in pages.indices) pages[activeIndex] else null

    fun allPages(): List<SessionPage> = pages.toList()

    /**
     * Create a new independent session page and make it active.
     * The page's backend kind + AppTerminal are supplied by the caller
     * (Human UI / debug); the controller does not choose a backend.
     */
    fun add(kind: SessionBackendKind, terminal: AppTerminal): SessionPage {
        val page = SessionPage(
            pageId = idSeq.incrementAndGet(),
            kind = kind,
            terminal = terminal,
        )
        pages.add(page)
        activeIndex = pages.size - 1
        return page
    }

    /** Move to the previous page (wraps). Never touches sessions. */
    fun prev() {
        if (pages.size <= 1) return
        activeIndex = (activeIndex - 1 + pages.size) % pages.size
    }

    /** Move to the next page (wraps). Never touches sessions. */
    fun next() {
        if (pages.size <= 1) return
        activeIndex = (activeIndex + 1) % pages.size
    }

    /** Close the active page's session and remove it. */
    fun closeActive(): SessionPage? {
        if (pages.isEmpty()) return null
        val removed = pages.removeAt(activeIndex)
        removed.close()
        if (pages.isEmpty()) {
            activeIndex = -1
        } else if (activeIndex >= pages.size) {
            activeIndex = pages.size - 1
        }
        return removed
    }

    /** Close every session and clear all pages. */
    fun closeAll() {
        for (p in pages) p.close()
        pages.clear()
        activeIndex = -1
    }
}