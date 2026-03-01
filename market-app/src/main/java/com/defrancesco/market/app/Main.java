package com.defrancesco.market.app;

import com.defrancesco.market.analytics.GlosenMilgromAnalytics;
import com.defrancesco.market.analytics.OFIAnalytics;
import com.defrancesco.market.domain.BookTicker;
import com.defrancesco.market.domain.GlosenMilgromState;
import com.defrancesco.market.domain.SpreadAnalysis;
import com.defrancesco.market.feed.GateIoFeed;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Entry point.
 *
 * Usage:
 *   java -jar market-app.jar [SYMBOL]
 *   java -jar market-app.jar BTC_USDT       (default)
 *   java -jar market-app.jar ETH_USDT
 *
 * Output (throttled to 1 update/second via throttleLast):
 * ─────────────────────────────────────── BTC_USDT  14:32:01.123 ───
 *  Bid:  84,231.10  Ask:  84,231.80  Mid:  84,231.45
 *  Observed spread :  0.83 bps
 *  GM spread (μ=25%):  0.61 bps
 *  Adverse selection: 73.5%
 *  OFI (30s)         : +0.42  [buying pressure]
 *  π  [P(V=V_H)]     :  0.587
 * ────────────────────────────────────────────────────────────────────
 */
public class Main {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public static void main(String[] args) throws InterruptedException {
        String symbol = args.length > 0 ? args[0] : "BTC_USDT";
        System.out.println("Starting Glosten-Milgrom microstructure monitor for " + symbol);
        System.out.println("μ (informed traders) = " + (GlosenMilgromAnalytics.DEFAULT_MU * 100) + "%");
        System.out.println("─".repeat(68));

        GateIoFeed feed = new GateIoFeed(symbol);
        feed.connect();

        Observable<BookTicker>         book   = feed.bookTicker().share();
        Observable<com.defrancesco.market.domain.Trade> trades = feed.trades().share();

        GlosenMilgromAnalytics gmAnalytics  = new GlosenMilgromAnalytics();
        OFIAnalytics           ofiAnalytics = new OFIAnalytics(30);

        Observable<GlosenMilgromState> gmStream  = gmAnalytics.compute(trades, book).share();
        Observable<Double>             ofiStream = ofiAnalytics.compute(trades)
                                                               .startWithItem(0.0)
                                                               .share();

        // Combine the three streams, throttle to 1 update/second for readability.
        Observable<SpreadAnalysis> analysis = Observable.combineLatest(
                book,
                gmStream,
                ofiStream,
                SpreadAnalysis::new
        ).throttleLast(1, TimeUnit.SECONDS, Schedulers.computation());

        CountDownLatch latch = new CountDownLatch(1);

        analysis.subscribe(
                sa -> printSnapshot(sa, symbol),
                err -> {
                    System.err.println("Stream error: " + err.getMessage());
                    latch.countDown();
                },
                latch::countDown
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            feed.disconnect();
            latch.countDown();
        }));

        latch.await();
    }

    private static void printSnapshot(SpreadAnalysis sa, String symbol) {
        BookTicker        book = sa.book();
        GlosenMilgromState gm = sa.gmState();

        String time = TIME_FMT.format(Instant.ofEpochMilli(book.timestampMs()));
        double ofi  = sa.ofi();

        String ofiBar  = ofi > 0.1 ? "[buying pressure  ▲]" :
                         ofi < -0.1 ? "[selling pressure ▼]" :
                                      "[balanced          ]";
        String ofiSign = ofi >= 0 ? "+" : "";

        System.out.printf("""
                %s  %s  %s%n\
                 Bid: %,12.2f   Ask: %,12.2f   Mid: %,12.2f%n\
                 Observed spread :  %6.2f bps%n\
                 GM spread (μ=%2.0f%%):  %6.2f bps%n\
                 Adverse selection: %5.1f%%%n\
                 OFI (30s)         : %s%.3f  %s%n\
                 π  [P(V=V_H)]     :  %.4f%n\
                %s%n""",
                "─".repeat(38), symbol, time,
                book.bid(), book.ask(), book.mid(),
                sa.observedSpreadBps(),
                gm.mu() * 100, sa.gmSpreadBps(),
                sa.adverseSelectionRatio() * 100,
                ofiSign, ofi, ofiBar,
                gm.pi(),
                "─".repeat(68)
        );
    }
}
