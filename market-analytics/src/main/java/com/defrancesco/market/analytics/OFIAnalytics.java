package com.defrancesco.market.analytics;

import com.defrancesco.market.domain.Trade;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observable;
import io.smallrye.mutiny.Multi;
import org.reactivestreams.FlowAdapters;

import java.time.Duration;

/**
 * Order Flow Imbalance (OFI) over a rolling time window.
 *
 *   OFI = Σ signedVolume / Σ totalVolume  ∈ [-1, +1]
 *
 * Implemented with SmallRye Mutiny to demonstrate reactive interop.
 * The two reactive libraries use different Publisher contracts:
 *
 *   RxJava Flowable → org.reactivestreams.Publisher  (pre-JDK9 standard)
 *   Mutiny Multi    → java.util.concurrent.Flow.Publisher  (JDK9+)
 *
 * org.reactivestreams.FlowAdapters (from reactive-streams 1.0.3+,
 * already on the classpath as an RxJava transitive dependency) bridges them
 * with zero allocation.
 *
 * Conversion chain:
 *   Observable<Trade>
 *     → toFlowable(LATEST)           [Observable → org.reactivestreams.Publisher]
 *     → FlowAdapters.toFlowPublisher [org.reactivestreams → Flow.Publisher]
 *     → Multi.createFrom().publisher [Flow.Publisher → Multi]
 *     → group().intoMultis().every() [time-based windowing]
 *     → collect per window           [Multi<Trade> → Uni<Double>]
 *     → FlowAdapters.toPublisher     [Flow.Publisher → org.reactivestreams.Publisher]
 *     → Flowable.fromPublisher       [org.reactivestreams.Publisher → Flowable]
 *     → toObservable()               [Flowable → Observable]
 */
public class OFIAnalytics {

    private final Duration window;

    public OFIAnalytics()                   { this(30); }
    public OFIAnalytics(long windowSeconds) { this.window = Duration.ofSeconds(windowSeconds); }

    /**
     * Returns an Observable emitting one OFI value at the end of each time window.
     * Emits 0.0 for empty windows (no trades).
     */
    public Observable<Double> compute(Observable<Trade> trades) {
        // ── Step 1: Observable → Flowable (adds backpressure required by Publisher) ─
        Flowable<Trade> flowable = trades.toFlowable(BackpressureStrategy.LATEST);

        // ── Step 2: org.reactivestreams.Publisher → Flow.Publisher ───────────────
        Multi<Trade> tradeMulti = Multi.createFrom().publisher(
                FlowAdapters.toFlowPublisher(flowable)
        );

        // ── Step 3: time-based windowing + per-window OFI (pure Mutiny) ──────────
        Multi<Double> ofiMulti = tradeMulti
                .group().intoMultis().every(window)
                .onItem().transformToUniAndMerge(windowMulti ->
                        windowMulti
                                .collect().in(
                                        () -> new double[]{0.0, 0.0},   // [signedVol, totalVol]
                                        (acc, trade) -> {
                                            acc[0] += trade.signedAmount();
                                            acc[1] += trade.amount();
                                        })
                                .map(acc -> acc[1] == 0.0 ? 0.0 : acc[0] / acc[1])
                );

        // ── Step 4: Flow.Publisher → org.reactivestreams.Publisher → Observable ──
        return Flowable.<Double>fromPublisher(FlowAdapters.toPublisher(ofiMulti))
                       .toObservable();
    }
}
