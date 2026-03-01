package com.defrancesco.market.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GlosenMilgromStateTest {

    private static final double MID = 100.0;
    private static final double MU  = 0.25;
    private static final double VOL = 0.001;
    private static final double EPS = 1e-10;

    private GlosenMilgromState neutral() {
        return GlosenMilgromState.initial(MID, MU, VOL);
    }

    // ── initial state ────────────────────────────────────────────────────────

    @Test
    void initial_piIsHalf() {
        assertEquals(0.5, neutral().pi(), EPS);
    }

    @Test
    void initial_expectedValueEqualsInputMid() {
        assertEquals(MID, neutral().expectedValue(), EPS);
    }

    @Test
    void initial_vHAboveMidVLBelow() {
        var s = neutral();
        assertTrue(s.vH() > MID);
        assertTrue(s.vL() < MID);
    }

    @Test
    void initial_spreadIsPositive() {
        assertTrue(neutral().gmSpreadBps() > 0);
    }

    // ── Bayesian updates ─────────────────────────────────────────────────────

    @Test
    void afterBuy_piIncreases() {
        double before = neutral().pi();
        double after  = neutral().afterBuy().pi();
        assertTrue(after > before, "buy should raise P(V=V_H)");
    }

    @Test
    void afterSell_piDecreases() {
        double before = neutral().pi();
        double after  = neutral().afterSell().pi();
        assertTrue(after < before, "sell should lower P(V=V_H)");
    }

    @Test
    void buyThenSell_returnsToNeutral() {
        // From π=0.5: buy then sell is symmetric → back to 0.5
        double pi = neutral().afterBuy().afterSell().pi();
        assertEquals(0.5, pi, EPS);
    }

    @Test
    void sellThenBuy_returnsToNeutral() {
        double pi = neutral().afterSell().afterBuy().pi();
        assertEquals(0.5, pi, EPS);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 50})
    void manyBuys_piApproachesOne(int n) {
        var state = neutral();
        for (int i = 0; i < n; i++) state = state.afterBuy();
        assertTrue(state.pi() > 0.5, "after " + n + " buys π should be > 0.5");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 50})
    void manySells_piApproachesZero(int n) {
        var state = neutral();
        for (int i = 0; i < n; i++) state = state.afterSell();
        assertTrue(state.pi() < 0.5, "after " + n + " sells π should be < 0.5");
    }

    @Test
    void pi_neverReachesExtremes() {
        var state = neutral();
        for (int i = 0; i < 1000; i++) state = state.afterBuy();
        assertTrue(state.pi() < 1.0, "π must stay below 1");
        assertTrue(state.pi() > 0.0, "π must stay above 0");
    }

    // ── bid / ask quotes ─────────────────────────────────────────────────────

    @Test
    void ask_alwaysAboveBid() {
        var s = neutral();
        assertTrue(s.theoreticalAsk() > s.theoreticalBid());
    }

    @Test
    void mid_betweenBidAndAsk() {
        var s = neutral();
        double mid = s.expectedValue();
        assertTrue(s.theoreticalBid() < mid);
        assertTrue(s.theoreticalAsk() > mid);
    }

    @Test
    void withZeroInformedTraders_spreadCollapses() {
        // μ=0 → no adverse selection → bid == ask == E[V]
        var s = GlosenMilgromState.initial(MID, 0.0, VOL);
        assertEquals(s.theoreticalBid(), s.theoreticalAsk(), EPS);
        assertEquals(0.0, s.gmSpreadBps(), EPS);
    }

    @Test
    void withAllInformedTraders_spreadMaximal() {
        // μ=1 → ask=V_H, bid=V_L
        var s = GlosenMilgromState.initial(MID, 1.0, VOL);
        assertEquals(s.vH(), s.theoreticalAsk(), EPS);
        assertEquals(s.vL(), s.theoreticalBid(),  EPS);
    }

    // ── spread formula ───────────────────────────────────────────────────────

    @Test
    void neutralSpread_matchesClosedFormula() {
        // At π=0.5 the GM half-spread = μ * (V_H - V_L) / 2
        // Full spread = μ * (V_H - V_L)
        // In bps      = μ * 2 * vol * 10_000
        var s = neutral();
        double expected = MU * 2 * VOL * 10_000;
        assertEquals(expected, s.gmSpreadBps(), 1e-6);
    }

    // ── recalibration ────────────────────────────────────────────────────────

    @Test
    void recalibrate_resetsToNeutral() {
        var skewed = neutral().afterBuy().afterBuy().afterBuy();
        assertTrue(skewed.pi() > 0.5);

        var reset = skewed.recalibrate(MID, VOL);
        assertEquals(0.5, reset.pi(), EPS);
    }

    @Test
    void recalibrate_updatesVHAndVL() {
        double newMid = 200.0;
        var reset = neutral().recalibrate(newMid, VOL);
        assertEquals(newMid * (1 + VOL), reset.vH(), EPS);
        assertEquals(newMid * (1 - VOL), reset.vL(), EPS);
    }

    // ── update(Trade) dispatch ───────────────────────────────────────────────

    @Test
    void update_buyTrade_delegatesToAfterBuy() {
        var trade = new Trade("BTC_USDT", "buy", MID, 1.0, 0L);
        assertEquals(neutral().afterBuy().pi(), neutral().update(trade).pi(), EPS);
    }

    @Test
    void update_sellTrade_delegatesToAfterSell() {
        var trade = new Trade("BTC_USDT", "sell", MID, 1.0, 0L);
        assertEquals(neutral().afterSell().pi(), neutral().update(trade).pi(), EPS);
    }
}
