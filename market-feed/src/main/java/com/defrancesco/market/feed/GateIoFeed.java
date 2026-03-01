package com.defrancesco.market.feed;

import com.defrancesco.market.domain.BookTicker;
import com.defrancesco.market.domain.Trade;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import org.agrona.concurrent.OneToOneConcurrentArrayQueue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Connects to Gate.io Spot WebSocket v4 and exposes:
 *   - Observable<Trade>      from spot.trades
 *   - Observable<BookTicker> from spot.book_ticker (best bid/ask, 10ms updates)
 *
 * Architecture:
 *   WebSocket I/O thread → OneToOneConcurrentArrayQueue (Agrona SPSC, lock-free)
 *                        → virtual drainer thread → JSON parse → PublishSubject
 *
 * The WebSocket I/O thread is never blocked by JSON parsing.
 * The SPSC queue is sized at 4096 to absorb bursts without allocating.
 */
public class GateIoFeed {

    private static final String WS_URL             = "wss://api.gateio.ws/ws/v4/";
    private static final int    PING_INTERVAL_SECS  = 10;
    private static final int    QUEUE_CAPACITY       = 4096;

    private final String       symbol;
    private final ObjectMapper mapper = new ObjectMapper();

    private final PublishSubject<Trade>      tradesSubject = PublishSubject.create();
    private final PublishSubject<BookTicker> bookSubject   = PublishSubject.create();

    /** Lock-free SPSC queue: WebSocket thread offers, drainer thread polls. */
    private final OneToOneConcurrentArrayQueue<String> messageQueue =
            new OneToOneConcurrentArrayQueue<>(QUEUE_CAPACITY);

    private volatile boolean running = false;
    private WebSocket webSocket;

    public GateIoFeed(String symbol) {
        this.symbol = symbol;
    }

    public void connect() {
        running = true;
        startDrainer();

        HttpClient client = HttpClient.newHttpClient();
        webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create(WS_URL), new Listener())
                .join();

        subscribe();
        schedulePing();
    }

    public Observable<Trade>      trades()     { return tradesSubject.hide(); }
    public Observable<BookTicker> bookTicker() { return bookSubject.hide(); }

    public void disconnect() {
        running = false;
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        tradesSubject.onComplete();
        bookSubject.onComplete();
    }

    // ── private ─────────────────────────────────────────────────────────────

    /**
     * Drains the message queue on a dedicated virtual thread.
     * Virtual threads are cheap — blocking here has zero cost on the carrier thread pool.
     * Uses Thread.onSpinWait() when idle to hint the CPU without busy-burning a platform thread.
     */
    private void startDrainer() {
        Thread.ofVirtual().name("msg-drainer").start(() -> {
            while (running || !messageQueue.isEmpty()) {
                String msg = messageQueue.poll();
                if (msg != null) {
                    handleMessage(msg);
                } else {
                    Thread.onSpinWait();
                }
            }
        });
    }

    private void subscribe() {
        long time = System.currentTimeMillis() / 1000;
        sendJson(Map.of(
                "time",    time,
                "channel", "spot.book_ticker",
                "event",   "subscribe",
                "payload", List.of(symbol)
        ));
        sendJson(Map.of(
                "time",    time,
                "channel", "spot.trades",
                "event",   "subscribe",
                "payload", List.of(symbol)
        ));
    }

    private void schedulePing() {
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-ping");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> sendJson(Map.of(
                "time",    System.currentTimeMillis() / 1000,
                "channel", "spot.ping"
        )), PING_INTERVAL_SECS, PING_INTERVAL_SECS, TimeUnit.SECONDS);
    }

    private void sendJson(Object payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            webSocket.sendText(json, true);
        } catch (Exception e) {
            System.err.println("[feed] send error: " + e.getMessage());
        }
    }

    private void handleMessage(String raw) {
        try {
            JsonNode root    = mapper.readTree(raw);
            String   channel = root.path("channel").asText();
            String   event   = root.path("event").asText();

            if ("subscribe".equals(event) || "spot.pong".equals(channel)) return;

            JsonNode result = root.path("result");
            if (result.isMissingNode() || result.isNull()) return;

            switch (channel) {
                case "spot.book_ticker" -> bookSubject.onNext(parseBookTicker(result));
                case "spot.trades"      -> {
                    if (result.isArray()) {
                        result.forEach(node -> tradesSubject.onNext(parseTrade(node)));
                    } else {
                        tradesSubject.onNext(parseTrade(result));
                    }
                }
                default -> { /* ignore */ }
            }
        } catch (Exception e) {
            System.err.println("[feed] parse error: " + e.getMessage());
        }
    }

    private BookTicker parseBookTicker(JsonNode n) {
        return new BookTicker(
                n.path("s").asText(),
                n.path("b").asDouble(),
                n.path("B").asDouble(),
                n.path("a").asDouble(),
                n.path("A").asDouble(),
                n.path("t").asLong()
        );
    }

    private Trade parseTrade(JsonNode n) {
        long tsMs = (long) n.path("create_time_ms").asDouble();
        return new Trade(
                n.path("currency_pair").asText(),
                n.path("side").asText(),
                n.path("price").asDouble(),
                n.path("amount").asDouble(),
                tsMs
        );
    }

    // ── WebSocket listener ───────────────────────────────────────────────────

    private class Listener implements WebSocket.Listener {

        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            System.out.println("[feed] connected to " + WS_URL);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                // Hand off to the drainer — never block the WebSocket I/O thread.
                if (!messageQueue.offer(buf.toString())) {
                    System.err.println("[feed] message queue full, dropping message");
                }
                buf.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            System.err.println("[feed] WebSocket error: " + error.getMessage());
            tradesSubject.onError(error);
            bookSubject.onError(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            System.out.println("[feed] closed: " + statusCode + " " + reason);
            tradesSubject.onComplete();
            bookSubject.onComplete();
            return null;
        }
    }
}
