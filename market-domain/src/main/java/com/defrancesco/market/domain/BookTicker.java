package com.defrancesco.market.domain;

/**
 * Best bid/ask snapshot from Gate.io spot.book_ticker channel.
 */
public record BookTicker(
        String symbol,
        double bid,
        double bidSize,
        double ask,
        double askSize,
        long timestampMs
) {
    public double mid() {
        return (bid + ask) / 2.0;
    }

    public double observedSpreadBps() {
        double m = mid();
        return m > 0 ? (ask - bid) / m * 10_000 : 0;
    }
}
