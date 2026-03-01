package com.defrancesco.market.runners;

import com.defrancesco.market.analytics.GlosenMilgromAnalytics;
import com.defrancesco.market.analytics.OFIAnalytics;
import com.defrancesco.market.domain.BookTicker;
import com.defrancesco.market.domain.GlosenMilgromState;
import com.defrancesco.market.domain.SpreadAnalysis;
import com.defrancesco.market.domain.Trade;
import com.defrancesco.market.feed.GateIoFeed;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Full Glosten-Milgrom microstructure monitor.
 *
 * Displays: bid/ask/mid, observed spread vs GM theoretical spread,
 * adverse selection ratio, OFI (30s), and the current π belief.
 *
 * Usage: GlosenMilgromRunner [SYMBOL]   (default: BTC_USDT)
 */
public class GlosenMilgromRunner {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public static void main(String[] args) throws InterruptedException {
        String symbol = args.length > 0 ? args[0] : "BTC_USDT";

        System.out.printf("Glosten-Milgrom monitor  |  %s  |  μ=%.0f%%%n",
                symbol, GlosenMilgromAnalytics.DEFAULT_MU * 100);
        System.out.println("─".repeat(68));

        GateIoFeed feed = new GateIoFeed(symbol);
        feed.connect();

        Observable<BookTicker> book   = feed.bookTicker().share();
        Observable<Trade>      trades = feed.trades().share();

        Observable<GlosenMilgromState> gmStream  = new GlosenMilgromAnalytics().compute(trades, book).share();
        Observable<Double>             ofiStream = new OFIAnalytics(30).compute(trades)
                                                                        .startWithItem(0.0).share();

        CountDownLatch latch = new CountDownLatch(1);

        Observable.combineLatest(book, gmStream, ofiStream, SpreadAnalysis::new)
                  .throttleLast(1, TimeUnit.SECONDS, Schedulers.computation())
                  .subscribe(
                          sa -> print(sa, symbol),
                          err -> { System.err.println(err.getMessage()); latch.countDown(); },
                          latch::countDown
                  );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> { feed.disconnect(); latch.countDown(); }));
        latch.await();
    }


    private static void print(SpreadAnalysis sa, String symbol) {
        BookTicker         book = sa.book();
        GlosenMilgromState gm   = sa.gmState();
        double             ofi  = sa.ofi();

        String ofiLabel = ofi > 0.1 ? "▲ buying " : ofi < -0.1 ? "▼ selling" : "  balanced";
        String sign     = ofi >= 0 ? "+" : "";

        System.out.printf("""
                %s  %s  %s
                 Bid: %,12.2f   Ask: %,12.2f   Mid: %,12.2f
                 Observed spread :  %6.2f bps
                 GM spread (μ=%2.0f%%):  %6.2f bps
                 Adverse selection: %5.1f%%
                 OFI (30s)         : %s%.3f  [%s]
                 π  [P(V=V_H)]     :  %.4f
                %s%n""",
                "─".repeat(38), symbol, FMT.format(Instant.ofEpochMilli(book.timestampMs())),
                book.bid(), book.ask(), book.mid(),
                sa.observedSpreadBps(),
                gm.mu() * 100, sa.gmSpreadBps(),
                sa.adverseSelectionRatio() * 100,
                sign, ofi, ofiLabel,
                gm.pi(),
                "─".repeat(68));
    }
}
