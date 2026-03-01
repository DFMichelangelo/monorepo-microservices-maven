package com.defrancesco.market.domain;

/**
 * Snapshot combining the observed order book with GM model output and OFI.
 */
public record SpreadAnalysis(
        BookTicker book,
        GlosenMilgromState gmState,
        double ofi           // Order Flow Imbalance ∈ [-1, +1]
) {
    /** Spread observed on the book in basis points. */
    public double observedSpreadBps() {
        return book.observedSpreadBps();
    }

    /** Spread predicted by GM model in basis points. */
    public double gmSpreadBps() {
        return gmState.gmSpreadBps();
    }

    /**
     * Fraction of the observed spread explained by adverse selection.
     * = gmSpread / observedSpread
     * > 1 means the model predicts a wider spread than observed (e.g. high-vol regime).
     */
    public double adverseSelectionRatio() {
        double obs = observedSpreadBps();
        return obs > 0 ? gmSpreadBps() / obs : 0;
    }
}
