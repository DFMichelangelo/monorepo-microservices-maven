package com.defrancesco.market.domain;

/**
 * Taker trade from Gate.io spot.trades channel.
 * side = "buy"  → taker lifted the ask (aggressive buy)
 * side = "sell" → taker hit the bid  (aggressive sell)
 */
public record Trade(
        String symbol,
        String side,
        double price,
        double amount,
        long timestampMs
) {
    public boolean isBuy() {
        return "buy".equalsIgnoreCase(side);
    }

    public double signedAmount() {
        return isBuy() ? amount : -amount;
    }
}
