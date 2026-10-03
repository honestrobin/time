// SPDX-License-Identifier: AGPL-3.0-only
package com.honestrobin.time.platform.live

import com.honestrobin.time.platform.security.Current
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.context.SmartLifecycle
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tells open web apps and extension popups when someone's time entries change, so a timer started
 * anywhere shows everywhere within a moment (AT-4.1). Changes come from Postgres (a trigger on
 * time_entries notifies on commit), so they reach every server of an instance; each server
 * forwards them to the streams of that membership it holds.
 */
@Component
class LiveEvents(private val dataSource: DataSourceProperties) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private val streams = ConcurrentHashMap<UUID, MutableSet<SseEmitter>>()

    @Volatile private var running = false
    private var listener: Thread? = null
    private var heartbeat: Thread? = null

    fun subscribe(membershipId: UUID): SseEmitter {
        val emitter = SseEmitter(STREAM_LIFETIME.toMillis())
        val set = streams.computeIfAbsent(membershipId) { CopyOnWriteArraySet() }
        set.add(emitter)
        val remove = { set.remove(emitter); Unit }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError { remove() }
        // Tells the client the stream is open, so it can refresh once for anything it missed.
        send(emitter, SseEmitter.event().name("ready").data("{}"))
        return emitter
    }

    /** Streams open on this server (for tests and metrics). */
    fun openStreams(): Int = streams.values.sumOf { it.size }

    private fun dispatch(membershipId: UUID) {
        streams[membershipId]?.forEach { send(it, SseEmitter.event().name("time_entries").data("{}")) }
    }

    private fun send(emitter: SseEmitter, event: SseEmitter.SseEventBuilder) {
        try {
            emitter.send(event)
        } catch (_: Exception) {
            // The browser went away: close quietly (it reconnects if it still wants updates).
            streams.values.forEach { it.remove(emitter) }
            runCatching { emitter.complete() }
        }
    }

    private fun listen() {
        while (running) {
            try {
                DriverManager.getConnection(dataSource.url, dataSource.username, dataSource.password).use { conn ->
                    conn.createStatement().use { it.execute("listen $CHANNEL") }
                    val pg = conn.unwrap(PGConnection::class.java)
                    log.debug("Listening for time entry changes")
                    while (running) {
                        pg.getNotifications(1_000)?.forEach { n ->
                            runCatching { UUID.fromString(n.parameter) }.getOrNull()?.let(::dispatch)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) return
                log.warn("Live updates lost their database connection ({}); reconnecting", e.toString())
                Thread.sleep(2_000)
            }
        }
    }

    /** A comment every so often keeps proxies from closing quiet streams. */
    private fun beat() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT.toMillis())
            } catch (_: InterruptedException) {
                return
            }
            streams.values.forEach { set -> set.forEach { send(it, SseEmitter.event().comment("ping")) } }
        }
    }

    override fun start() {
        running = true
        listener = Thread.ofVirtual().name("live-events-listener").start(::listen)
        heartbeat = Thread.ofVirtual().name("live-events-heartbeat").start(::beat)
    }

    override fun stop() {
        running = false
        heartbeat?.interrupt()
        streams.values.forEach { set -> set.forEach { runCatching { it.complete() } } }
        streams.clear()
    }

    override fun isRunning() = running

    companion object {
        const val CHANNEL = "honestrobin_time_entries"
        val HEARTBEAT: Duration = Duration.ofSeconds(25)
        val STREAM_LIFETIME: Duration = Duration.ofMinutes(30)
    }
}

@RestController
@Tag(name = "me", description = "The authenticated user")
class LiveEventsController(private val live: LiveEvents) {
    /**
     * Server-sent events for the caller's time entries in the current account: `ready` once the
     * stream is open, then `time_entries` whenever one of theirs changes (refresh the timer and the
     * entries). Streams end after 30 minutes; reconnect.
     */
    @GetMapping("/api/v1/me/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(summary = "Live updates of your time entries (server-sent events)")
    fun events(response: HttpServletResponse): SseEmitter {
        val member = Current.member()
        // Reverse proxies (nginx) would otherwise hold the stream back in their buffers.
        response.setHeader("X-Accel-Buffering", "no")
        response.setHeader("Cache-Control", "no-cache")
        return live.subscribe(member.membershipId)
    }
}
