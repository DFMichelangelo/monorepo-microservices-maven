package com.defrancesco.market.runners;

import com.defrancesco.market.analytics.OFIAnalytics;
import com.defrancesco.market.feed.GateIoFeed;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;

/**
 * Standalone OFI (Order Flow Imbalance) monitor.
 *
 * Prints one line per time window showing the net directional pressure
 * and a small ASCII bar chart for quick visual scanning.
 *
 * Usage: OFIRunner [SYMBOL] [WINDOW_SECONDS]
 *        OFIRunner BTC_USDT 30     (default)
 *        OFIRunner ETH_USDT 60
 */
public class OFIRunner {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int BAR_WIDTH = 20;

    public static void main(String[] args) throws Exception {
        String symbol  = args.length > 0 ? args[0] : "BTC_USDT";
        long   window  = args.length > 1 ? Long.parseLong(args[1]) : 30;

        System.out.printf("OFI monitor  |  %s  |  window=%ds%n", symbol, window);
        System.out.printf("%-10s  %8s  %s%n", "time", "OFI", "");
        System.out.println("─".repeat(50));

        GateIoFeed feed = new GateIoFeed(symbol);
        feed.connect();

        CountDownLatch latch = new CountDownLatch(1);

        new OFIAnalytics(window).compute(feed.trades())
                .subscribe(
                        ofi -> System.out.printf("%-10s  %+7.3f  %s%n",
                                FMT.format(LocalTime.now()), ofi, bar(ofi)),
                        err -> { System.err.println(err.getMessage()); latch.countDown(); },
                        latch::countDown
                );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> { feed.disconnect(); latch.countDown(); }));
        latch.await();
    }

    /** Renders OFI ∈ [-1, +1] as a centred ASCII bar. */
    private static String bar(double ofi) {
        int half   = BAR_WIDTH / 2;
        int filled = (int) Math.round(Math.abs(ofi) * half);
        filled = Math.min(filled, half);

        String left  = ofi < 0 ? " ".repeat(half - filled) + "█".repeat(filled)
                                : " ".repeat(half);
        String right = ofi > 0 ? "█".repeat(filled) + " ".repeat(half - filled)
                                : " ".repeat(half);
        String label = ofi > 0.1 ? "buy ▲" : ofi < -0.1 ? "sell ▼" : "flat";

        return "[" + left + "|" + right + "] " + label;
    }
}
