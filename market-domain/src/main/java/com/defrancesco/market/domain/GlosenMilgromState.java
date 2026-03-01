package com.defrancesco.market.domain;

/**
 * Glosten-Milgrom (1985) sequential trade model state.
 *
 * The model assumes:
 *   - The asset has a "true" fundamental value V, which is either V_H (high) or V_L (low).
 *   - A fraction μ (mu) of traders are informed: they know V and trade accordingly.
 *   - A fraction (1-μ) are uninformed liquidity traders who buy or sell with equal probability.
 *   - The market maker observes the trade direction (buy/sell) and updates their belief
 *     π = P(V = V_H) via Bayes' rule.
 *   - The competitive market maker posts bid = E[V | sell arrives] and ask = E[V | buy arrives].
 *
 * Bayesian update after a BUY:
 *   P(buy | V_H) = μ·1 + (1-μ)·0.5 = 0.5 + 0.5μ   (informed buys when V=V_H, uninformed random)
 *   P(buy | V_L) = μ·0 + (1-μ)·0.5 = 0.5 - 0.5μ
 *   P(buy)       = P(buy|V_H)·π + P(buy|V_L)·(1-π)
 *   π'           = P(buy|V_H)·π / P(buy)
 *
 * Analogous for SELL.
 *
 * Recalibration: every N trades we reset π=0.5 and re-anchor V_H/V_L around the current mid.
 */
public record GlosenMilgromState(
        double pi,   // P(V = V_H), current directional belief ∈ (0,1)
        double mu,   // fraction of informed traders ∈ (0,1)
        double vH,   // high fundamental value
        double vL    // low fundamental value
) {
    /**
     * Creates a neutral initial state anchored around a given mid price.
     * V_H = mid*(1+vol), V_L = mid*(1-vol), π = 0.5.
     */
    public static GlosenMilgromState initial(double mid, double mu, double vol) {
        return new GlosenMilgromState(0.5, mu, mid * (1 + vol), mid * (1 - vol));
    }

    public GlosenMilgromState afterBuy() {
        double pBuyVh = 0.5 + 0.5 * mu;
        double pBuyVl = 0.5 - 0.5 * mu;
        double pBuy   = pBuyVh * pi + pBuyVl * (1.0 - pi);
        double newPi  = pBuyVh * pi / pBuy;
        return new GlosenMilgromState(clamp(newPi), mu, vH, vL);
    }

    public GlosenMilgromState afterSell() {
        double pSellVh = 0.5 - 0.5 * mu;
        double pSellVl = 0.5 + 0.5 * mu;
        double pSell   = pSellVh * pi + pSellVl * (1.0 - pi);
        double newPi   = pSellVh * pi / pSell;
        return new GlosenMilgromState(clamp(newPi), mu, vH, vL);
    }

    public GlosenMilgromState update(Trade trade) {
        return trade.isBuy() ? afterBuy() : afterSell();
    }

    /** Competitive ask: E[V | a buy order arrives] */
    public double theoreticalAsk() {
        double pBuyVh = 0.5 + 0.5 * mu;
        double pBuyVl = 0.5 - 0.5 * mu;
        double pBuy   = pBuyVh * pi + pBuyVl * (1.0 - pi);
        return (pBuyVh * pi * vH + pBuyVl * (1.0 - pi) * vL) / pBuy;
    }

    /** Competitive bid: E[V | a sell order arrives] */
    public double theoreticalBid() {
        double pSellVh = 0.5 - 0.5 * mu;
        double pSellVl = 0.5 + 0.5 * mu;
        double pSell   = pSellVh * pi + pSellVl * (1.0 - pi);
        return (pSellVh * pi * vH + pSellVl * (1.0 - pi) * vL) / pSell;
    }

    /** Market maker's current estimate of true value */
    public double expectedValue() {
        return pi * vH + (1.0 - pi) * vL;
    }

    /** GM theoretical spread in basis points */
    public double gmSpreadBps() {
        double mid = expectedValue();
        return mid > 0 ? (theoreticalAsk() - theoreticalBid()) / mid * 10_000 : 0;
    }

    /**
     * Recalibrate: re-anchor V_H/V_L around a new mid price (e.g. every N trades).
     * π resets to 0.5 because E[V] = newMid implies neutrality with symmetric V_H/V_L.
     */
    public GlosenMilgromState recalibrate(double newMid, double vol) {
        return initial(newMid, mu, vol);
    }

    private static double clamp(double v) {
        return Math.max(1e-9, Math.min(1.0 - 1e-9, v));
    }
}
