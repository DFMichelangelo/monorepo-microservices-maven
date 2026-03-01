package com.defrancesco.market.runners;

import com.defrancesco.market.feed.GateIoFeed;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Raw book ticker monitor. Prints best bid/ask and spread for every
 * throttled snapshot. Useful for sanity-checking the feed without
 * any analytical overhead.
 *
 * Usage: BookTickerRunner [SYMBOL]   (default: BTC_USDT)
 */
public class BookTickerRunner {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "BTC_USDT";

        System.out.printf("Book ticker  |  %s%n", symbol);
        System.out.printf("%-14s  %14s  %14s  %12s%n", "time", "bid", "ask", "spread(bps)");
        System.out.println("─".repeat(60));

        GateIoFeed feed = new GateIoFeed(symbol);
        feed.connect();

        CountDownLatch latch = new CountDownLatch(1);

        feed.bookTicker()
            .throttleLast(1, TimeUnit.SECONDS, Schedulers.computation())
            .subscribe(
                    bt -> System.out.printf("%-14s  %,14.2f  %,14.2f  %12.4f%n",
                            FMT.format(Instant.ofEpochMilli(bt.timestampMs())),
                            bt.bid(), bt.ask(), bt.observedSpreadBps()),
                    err -> { System.err.println(err.getMessage()); latch.countDown(); },
                    latch::countDown
            );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> { feed.disconnect(); latch.countDown(); }));
        latch.await();
    }
}
