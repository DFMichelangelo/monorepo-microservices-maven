package com.defrancesco.market.analytics;

import com.defrancesco.market.domain.BookTicker;
import com.defrancesco.market.domain.GlosenMilgromState;
import com.defrancesco.market.domain.Trade;
import io.reactivex.rxjava3.core.Observable;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;

/**
 * Drives the Glosten-Milgrom sequential trade model using live market data.
 *
 * The rolling price buffer uses FastUtil's DoubleArrayList which stores raw
 * double primitives — no boxing, no GC pressure from Double wrappers.
 * At 100-element capacity this saves ~800 bytes of heap per recalibration cycle
 * and eliminates autoboxing overhead on every trade.
 */
public class GlosenMilgromAnalytics {

    public static final double DEFAULT_MU          = 0.25;
    private static final int   RECALIBRATION_TRADES = 100;

    private final double mu;

    public GlosenMilgromAnalytics()         { this(DEFAULT_MU); }
    public GlosenMilgromAnalytics(double mu) { this.mu = mu; }

    public Observable<GlosenMilgromState> compute(
            Observable<Trade> trades,
            Observable<BookTicker> bookTicker
    ) {
        return bookTicker
                .take(1)
                .flatMap(firstBook -> {
                    GlosenMilgromState seed = GlosenMilgromState.initial(firstBook.mid(), mu, 0.0001);
                    return trades
                            .scan(new TradeAccumulator(seed), TradeAccumulator::onTrade)
                            .map(acc -> acc.state);
                });
    }

    // ── internal accumulator ────────────────────────────────────────────────

    /**
     * Immutable-style accumulator for RxJava scan().
     * DoubleArrayList (FastUtil) is used instead of List<Double> to store prices
     * as unboxed primitives. A defensive copy is made on each update, keeping
     * the functional scan() pattern without boxing overhead.
     */
    static final class TradeAccumulator {

        final GlosenMilgromState state;
        final DoubleArrayList    recentPrices;  // unboxed double[] under the hood
        final int                count;

        TradeAccumulator(GlosenMilgromState seed) {
            this(seed, new DoubleArrayList(RECALIBRATION_TRADES), 0);
        }

        private TradeAccumulator(GlosenMilgromState state, DoubleArrayList prices, int count) {
            this.state        = state;
            this.recentPrices = prices;
            this.count        = count;
        }

        TradeAccumulator onTrade(Trade trade) {
            GlosenMilgromState updated = state.update(trade);
            int newCount = count + 1;

            // Defensive copy — keeps scan() functional (no shared mutable state).
            DoubleArrayList newPrices = new DoubleArrayList(recentPrices);
            newPrices.add(trade.price());                              // primitive add, no boxing
            if (newPrices.size() > RECALIBRATION_TRADES) {
                newPrices.removeDouble(0);                             // primitive remove, no boxing
            }

            if (newCount % RECALIBRATION_TRADES == 0 && newPrices.size() >= 2) {
                double vol    = realizedVol(newPrices);
                updated = updated.recalibrate(trade.price(), vol);
            }

            return new TradeAccumulator(updated, newPrices, newCount);
        }

        /** Realized vol = std dev of log-returns. Operates on unboxed primitives throughout. */
        private static double realizedVol(DoubleArrayList prices) {
            int n = prices.size();
            if (n < 2) return 0.0001;

            double mean = 0;
            for (int i = 1; i < n; i++) {
                mean += Math.log(prices.getDouble(i) / prices.getDouble(i - 1));  // getDouble = primitive
            }
            mean /= (n - 1);

            double variance = 0;
            for (int i = 1; i < n; i++) {
                double r = Math.log(prices.getDouble(i) / prices.getDouble(i - 1));
                variance += (r - mean) * (r - mean);
            }
            variance /= (n - 1);

            return Math.max(0.0001, Math.sqrt(variance));
        }
    }
}
