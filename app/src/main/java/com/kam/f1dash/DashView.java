package com.kam.f1dash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/** The whole dashboard, drawn on one canvas so every element scales with the screen. */
final class DashView extends View {
    interface Host {
        void onDemo(boolean on);
        void onUnits(boolean mph);
        void onPage(int page);
        List<String> addresses();
        String netStatus();
    }

    static final int BG = 0xFF0B0D12, CARD = 0xFF151A22, OFF = 0xFF222833, TXT = 0xFFEDEFF4, DIM = 0xFF8C95A6;
    static final int RED = 0xFFE10600, GRN = 0xFF22C55E, YEL = 0xFFFACC15, PUR = 0xFFB44CFF, BLU = 0xFF60A5FA, ORG = 0xFFF59E0B;
    private static final String[] PAGES = {"RACE", "CAR", "SETUP"};
    private static final String[] ERS = {"NONE", "MEDIUM", "HOTLAP", "OVERTAKE"};
    private static final String[] WHEEL = {"RL", "RR", "FL", "FR"};
    private static final int[] SHOW = {2, 3, 0, 1};   // FL FR RL RR (data order is RL RR FL FR)

    private final Telemetry t;
    private final Host host;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final RectF[] tabRect = {new RectF(), new RectF(), new RectF()};
    private final RectF demoBtn = new RectF(), unitBtn = new RectF();
    private float W, H;
    int page;
    boolean mph, demo;
    private int pktRate;
    private long rateT, ratePk;

    private final Runnable tick = new Runnable() {
        @Override public void run() { invalidate(); postDelayed(this, 33); }
    };

    DashView(Context c, Telemetry t, Host host) {
        super(c);
        this.t = t;
        this.host = host;
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

    // ------------------------------------------------------------ helpers
    private RectF R(float x, float y, float w, float h) { return new RectF(x * W, y * H, (x + w) * W, (y + h) * H); }

    private static String fmtT(int ms) {
        if (ms <= 0) return "-:--.---";
        int m = ms / 60000;
        double s = (ms % 60000) / 1000.0;
        return m + ":" + (s < 10 ? "0" : "") + String.format(java.util.Locale.US, "%.3f", s);
    }
    private static String fmtSec(int ms) {
        if (ms <= 0) return "-.---";
        int m = ms / 60000;
        double s = (ms % 60000) / 1000.0;
        String x = String.format(java.util.Locale.US, "%.3f", s);
        return m > 0 ? m + ":" + (s < 10 ? "0" : "") + x : x;
    }
    private static String gap(int ms) { return ms <= 0 ? "--" : "+" + String.format(java.util.Locale.US, "%.3f", ms / 1000.0); }
    private static int mixc(int a, int b, float f) {
        f = Math.max(0, Math.min(1, f));
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
    private static int dmgCol(float pct) { return mixc(GRN, RED, pct / 70f); }
    private static int tyreTempCol(int c) { return c < 75 ? 0xFF3B82F6 : c < 105 ? GRN : c < 120 ? ORG : RED; }
    private static int compoundCol(int id) {
        switch (id) { case 16: return 0xFFEF4444; case 17: return YEL; case 18: return 0xFFE5E7EB; case 7: return GRN; case 8: return 0xFF3B82F6; default: return DIM; }
    }
    private static String compoundName(int id) {
        switch (id) { case 16: return "Soft"; case 17: return "Medium"; case 18: return "Hard"; case 7: return "Inter"; case 8: return "Wet"; default: return "-"; }
    }
    private static int secCol(int c) {
        switch (c) { case 3: return RED; case 2: return PUR; case 1: return GRN; case 0: return YEL; default: return DIM; }
    }

    private void box(Canvas c, RectF r, int fill) {
        p.setStyle(Paint.Style.FILL); p.setColor(fill);
        float rad = Math.min(r.width(), r.height()) * 0.08f;
        c.drawRoundRect(r, rad, rad, p);
    }
    private void outline(Canvas c, RectF r, int col, float w) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w); p.setColor(col);
        float rad = Math.min(r.width(), r.height()) * 0.08f;
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }
    /** Draws text so it fits inside r (shrinks to fit width), vertically centred. */
    private void fit(Canvas c, String s, RectF r, int col, boolean bold, Paint.Align al, float maxFrac) {
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        float size = r.height() * maxFrac;
        p.setTextSize(size);
        float w = p.measureText(s);
        if (w > r.width()) { size *= r.width() / w; p.setTextSize(size); }
        p.setColor(col);
        p.setTextAlign(al);
        float x = al == Paint.Align.LEFT ? r.left : al == Paint.Align.RIGHT ? r.right : r.centerX();
        c.drawText(s, x, r.centerY() - (p.ascent() + p.descent()) / 2f, p);
    }
    private void fit(Canvas c, String s, RectF r, int col, boolean bold, Paint.Align al) { fit(c, s, r, col, bold, al, 0.85f); }

    /** A card with a small label in the top-left. Returns the value area underneath. */
    private RectF card(Canvas c, RectF r, String label, int border) {
        box(c, r, CARD);
        if (border != 0) outline(c, r, border, Math.max(2f, H * 0.004f));
        float lh = Math.min(r.height() * 0.22f, H * 0.032f);
        RectF lab = new RectF(r.left + r.width() * 0.05f, r.top + r.height() * 0.05f, r.right - r.width() * 0.05f, r.top + r.height() * 0.05f + lh);
        fit(c, label, lab, DIM, true, Paint.Align.LEFT, 0.95f);
        return new RectF(r.left + r.width() * 0.05f, lab.bottom, r.right - r.width() * 0.05f, r.bottom - r.height() * 0.05f);
    }
    private void hbar(Canvas c, RectF r, float frac, int col) {
        box(c, r, OFF);
        frac = Math.max(0, Math.min(1, frac));
        if (frac > 0) { tmp.set(r.left, r.top, r.left + r.width() * frac, r.bottom); p.setColor(col); c.drawRoundRect(tmp, r.height() / 2, r.height() / 2, p); }
    }
    private void vbar(Canvas c, RectF r, float frac, int col, String label) {
        box(c, r, OFF);
        frac = Math.max(0, Math.min(1, frac));
        if (frac > 0) { tmp.set(r.left, r.bottom - r.height() * frac, r.right, r.bottom); p.setColor(col); c.drawRoundRect(tmp, r.width() / 3, r.width() / 3, p); }
        RectF l = new RectF(r.left - r.width(), r.bottom + H * 0.005f, r.right + r.width(), r.bottom + H * 0.04f);
        fit(c, label, l, DIM, true, Paint.Align.CENTER, 0.9f);
    }
    private String speedTxt() { return Integer.toString(Math.round(mph ? t.speed * 0.621371f : t.speed)); }

    // ------------------------------------------------------------ drawing
    @Override protected void onDraw(Canvas c) {
        W = getWidth(); H = getHeight();
        c.drawColor(BG);
        long now = System.currentTimeMillis();
        if (now - rateT >= 1000) { synchronized (t) { pktRate = (int) (t.packets - ratePk); ratePk = t.packets; } rateT = now; }
        synchronized (t) {
            drawTabs(c);
            if (page == 0) drawRace(c);
            else if (page == 1) drawCar(c);
            else drawSetup(c);
            if (page != 2 && !demo && !t.receiving()) drawWaiting(c);
        }
    }

    private void drawTabs(Canvas c) {
        float x = 0.015f * W, h = 0.065f * H, y = 0.012f * H, w = 0.115f * W;
        for (int i = 0; i < 3; i++) {
            tabRect[i].set(x, y, x + w, y + h);
            box(c, tabRect[i], i == page ? RED : CARD);
            fit(c, PAGES[i], tabRect[i], i == page ? 0xFFFFFFFF : DIM, true, Paint.Align.CENTER, 0.5f);
            x += w + 0.008f * W;
        }
        boolean live = demo || t.receiving();
        String s = demo ? "DEMO" : live ? "LIVE  " + pktRate + " pkt/s" : "NO SIGNAL";
        fit(c, s, new RectF(0.6f * W, y, 0.985f * W, y + h), demo ? YEL : live ? GRN : RED, true, Paint.Align.RIGHT, 0.5f);
    }

    private void drawWaiting(Canvas c) {
        p.setStyle(Paint.Style.FILL); p.setColor(0xCC0B0D12); c.drawRect(0, 0.09f * H, W, H, p);
        fit(c, "WAITING FOR F1 25 TELEMETRY", R(0.1f, 0.38f, 0.8f, 0.1f), TXT, true, Paint.Align.CENTER, 0.8f);
        fit(c, "Open the SETUP tab to see what to enter in the game.", R(0.1f, 0.5f, 0.8f, 0.05f), DIM, false, Paint.Align.CENTER, 0.8f);
    }

    private void drawRace(Canvas c) {
        // shift lights
        RectF lights = R(0.22f, 0.09f, 0.56f, 0.065f);
        int n = 15;
        float cw = lights.width() / n, d = Math.min(cw * 0.78f, lights.height());
        float lit = t.revPct / 100f * n;
        for (int i = 0; i < n; i++) {
            int base = i < 5 ? GRN : i < 10 ? RED : BLU;
            p.setStyle(Paint.Style.FILL);
            p.setColor(i < lit ? base : OFF);
            c.drawCircle(lights.left + cw * (i + 0.5f), lights.centerY(), d / 2, p);
        }

        // ---- left column
        RectF a = card(c, R(0.015f, 0.17f, 0.27f, 0.12f), "LAP  /  POSITION", 0);
        String lap = t.lapNum > 0 ? t.lapNum + (t.totalLaps > 0 ? " / " + t.totalLaps : "") : "-";
        fit(c, lap, new RectF(a.left, a.top, a.left + a.width() * 0.55f, a.bottom), TXT, true, Paint.Align.LEFT);
        fit(c, t.position > 0 ? "P" + t.position : "-", new RectF(a.left + a.width() * 0.55f, a.top, a.right, a.bottom), YEL, true, Paint.Align.RIGHT);

        a = card(c, R(0.015f, 0.30f, 0.27f, 0.15f), t.invalid != 0 ? "CURRENT LAP  -  INVALID" : "CURRENT LAP", t.invalid != 0 ? RED : 0);
        fit(c, fmtT(t.curLapMs), a, t.invalid != 0 ? RED : TXT, true, Paint.Align.CENTER);

        a = card(c, R(0.015f, 0.47f, 0.131f, 0.14f), "LAST", 0);
        fit(c, fmtT(t.lastLapMs), a, TXT, true, Paint.Align.CENTER, 0.6f);
        a = card(c, R(0.154f, 0.47f, 0.131f, 0.14f), "BEST", 0);
        fit(c, fmtT(t.bestLapMs), a, PUR, true, Paint.Align.CENTER, 0.6f);

        RectF sec = R(0.015f, 0.63f, 0.27f, 0.15f);
        float sw = (sec.width() - 2 * 0.008f * W) / 3f;
        for (int i = 0; i < 3; i++) {
            float x = sec.left + i * (sw + 0.008f * W);
            RectF r = new RectF(x, sec.top, x + sw, sec.bottom);
            int col = secCol(t.sectorColour(i));
            box(c, r, t.sectorColour(i) >= 0 ? mixc(CARD, col, 0.25f) : CARD);
            outline(c, r, col, Math.max(2f, H * 0.005f));
            fit(c, "S" + (i + 1), new RectF(r.left, r.top + r.height() * 0.06f, r.right, r.top + r.height() * 0.38f), DIM, true, Paint.Align.CENTER, 0.9f);
            fit(c, fmtSec(t.dispSec[i]), new RectF(r.left + 4, r.top + r.height() * 0.4f, r.right - 4, r.bottom - r.height() * 0.08f), TXT, true, Paint.Align.CENTER, 0.6f);
        }

        a = card(c, R(0.015f, 0.80f, 0.27f, 0.15f), "GAPS", 0);
        RectF row1 = new RectF(a.left, a.top, a.right, a.top + a.height() / 2), row2 = new RectF(a.left, a.top + a.height() / 2, a.right, a.bottom);
        fit(c, "AHEAD", row1, DIM, true, Paint.Align.LEFT, 0.6f); fit(c, gap(t.deltaFrontMs), row1, TXT, true, Paint.Align.RIGHT, 0.75f);
        fit(c, "LEADER", row2, DIM, true, Paint.Align.LEFT, 0.6f); fit(c, gap(t.deltaLeaderMs), row2, TXT, true, Paint.Align.RIGHT, 0.75f);

        // ---- centre: gear, speed, pedals
        String g = t.gear == 0 ? "N" : t.gear < 0 ? "R" : Integer.toString(t.gear);
        boolean redline = t.maxRpm > 0 && t.rpm > t.maxRpm * 0.96f;
        fit(c, g, R(0.33f, 0.16f, 0.34f, 0.47f), redline ? RED : TXT, true, Paint.Align.CENTER, 1.0f);
        fit(c, speedTxt(), R(0.33f, 0.63f, 0.25f, 0.16f), TXT, true, Paint.Align.RIGHT, 0.95f);
        fit(c, mph ? "mph" : "km/h", R(0.585f, 0.68f, 0.085f, 0.07f), DIM, true, Paint.Align.LEFT, 0.7f);
        vbar(c, R(0.292f, 0.17f, 0.024f, 0.60f), t.brake, 0xFFEF4444, "BRK");
        vbar(c, R(0.684f, 0.17f, 0.024f, 0.60f), t.throttle, GRN, "THR");
        fit(c, t.rpm + " rpm", R(0.36f, 0.795f, 0.28f, 0.03f), DIM, false, Paint.Align.CENTER, 0.9f);

        RectF drsR = R(0.36f, 0.835f, 0.28f, 0.07f);
        if (t.drs == 1) { box(c, drsR, GRN); fit(c, "DRS OPEN", drsR, 0xFF04140A, true, Paint.Align.CENTER, 0.6f); }
        else if (t.drsAllowed == 1) { box(c, drsR, CARD); outline(c, drsR, YEL, H * 0.005f); fit(c, "DRS AVAILABLE", drsR, YEL, true, Paint.Align.CENTER, 0.6f); }
        else { box(c, drsR, CARD); fit(c, "DRS", drsR, DIM, true, Paint.Align.CENTER, 0.6f); }

        RectF ban = R(0.36f, 0.915f, 0.28f, 0.04f);
        String bs = null; int bc = 0;
        if (t.fiaFlag == 3) { bs = "YELLOW FLAG"; bc = YEL; }
        else if (t.fiaFlag == 4) { bs = "RED FLAG"; bc = RED; }
        else if (t.fiaFlag == 2) { bs = "BLUE FLAG"; bc = 0xFF3B82F6; }
        else if (t.pit > 0) { bs = t.pit == 2 ? "IN PIT" : "PIT LANE"; bc = ORG; }
        if (bs != null) { box(c, ban, bc); fit(c, bs, ban, 0xFF000000, true, Paint.Align.CENTER, 0.7f); }
        else {
            box(c, ban, OFF);
            float mid = ban.centerX(), half = ban.width() / 2;
            float sx = mid + Math.max(-1f, Math.min(1f, t.steer)) * (half - ban.height());
            p.setColor(BLU); c.drawCircle(sx, ban.centerY(), ban.height() * 0.4f, p);
        }

        // ---- right column
        a = card(c, R(0.715f, 0.17f, 0.27f, 0.16f), "ERS  -  " + ERS[Math.max(0, Math.min(3, t.ersMode))], 0);
        float ers = t.ersStore / Telemetry.ERS_MAX;
        fit(c, Math.round(ers * 100) + "%", new RectF(a.left, a.top, a.right, a.top + a.height() * 0.65f), ers < 0.15f ? RED : TXT, true, Paint.Align.LEFT);
        hbar(c, new RectF(a.left, a.bottom - a.height() * 0.22f, a.right, a.bottom), ers, ers < 0.15f ? RED : YEL);

        a = card(c, R(0.715f, 0.35f, 0.27f, 0.12f), "FUEL", 0);
        fit(c, String.format(java.util.Locale.US, "%.1f kg", t.fuel), new RectF(a.left, a.top, a.left + a.width() * 0.55f, a.bottom), TXT, true, Paint.Align.LEFT, 0.7f);
        fit(c, String.format(java.util.Locale.US, "%+.1f laps", t.fuelLaps), new RectF(a.left + a.width() * 0.5f, a.top, a.right, a.bottom), t.fuelLaps < 0 ? RED : GRN, true, Paint.Align.RIGHT, 0.6f);

        a = card(c, R(0.715f, 0.49f, 0.27f, 0.12f), "BRAKE BIAS  /  ENGINE", 0);
        fit(c, t.brakeBias + "%", new RectF(a.left, a.top, a.left + a.width() * 0.45f, a.bottom), TXT, true, Paint.Align.LEFT, 0.7f);
        fit(c, t.engineTemp + "°C", new RectF(a.left + a.width() * 0.5f, a.top, a.right, a.bottom), t.engineTemp > 125 ? RED : TXT, true, Paint.Align.RIGHT, 0.7f);

        a = card(c, R(0.715f, 0.63f, 0.27f, 0.17f), "TYRES  -  " + compoundName(t.compound).toUpperCase() + "  " + t.tyreAge + " LAPS", 0);
        float cwid = a.width() / 4f;
        for (int i = 0; i < 4; i++) {
            int w = SHOW[i];
            RectF cell = new RectF(a.left + i * cwid + 3, a.top, a.left + (i + 1) * cwid - 3, a.bottom);
            box(c, cell, mixc(CARD, tyreTempCol(t.tyreSurf[w]), 0.3f));
            fit(c, WHEEL[w], new RectF(cell.left, cell.top + 2, cell.right, cell.top + cell.height() * 0.28f), DIM, true, Paint.Align.CENTER, 0.9f);
            fit(c, Integer.toString(t.tyreSurf[w]), new RectF(cell.left, cell.top + cell.height() * 0.28f, cell.right, cell.top + cell.height() * 0.66f), TXT, true, Paint.Align.CENTER, 0.9f);
            fit(c, Math.round(t.tyreWear[w]) + "%", new RectF(cell.left, cell.top + cell.height() * 0.66f, cell.right, cell.bottom), dmgCol(t.tyreWear[w] * 1.2f), true, Paint.Align.CENTER, 0.85f);
        }

        a = card(c, R(0.715f, 0.82f, 0.27f, 0.13f), t.haveSession ? t.trackName().toUpperCase() : "SESSION", 0);
        String inf = t.haveSession ? t.sessionName() + "  -  " + t.weatherName() + "  -  " + t.trackTemp + "° / " + t.airTemp + "°" : "-";
        fit(c, inf, a, TXT, false, Paint.Align.LEFT, 0.5f);
    }

    private void drawCar(Canvas c) {
        for (int i = 0; i < 4; i++) {
            int w = SHOW[i];
            float x = i % 2 == 0 ? 0.015f : 0.305f, y = i < 2 ? 0.10f : 0.50f;
            RectF a = card(c, R(x, y, 0.28f, 0.38f), WHEEL[w] + "  TYRE", t.blisters[w] > 0 || t.tyreDmg[w] > 0 ? ORG : 0);
            RectF big = new RectF(a.left, a.top, a.left + a.width() * 0.55f, a.top + a.height() * 0.42f);
            fit(c, t.tyreSurf[w] + "°", big, tyreTempCol(t.tyreSurf[w]), true, Paint.Align.LEFT, 0.9f);
            fit(c, "inner " + t.tyreInner[w] + "°", new RectF(a.left + a.width() * 0.55f, a.top, a.right, a.top + a.height() * 0.2f), DIM, false, Paint.Align.RIGHT, 0.8f);
            fit(c, String.format(java.util.Locale.US, "%.1f psi", t.tyrePress[w]), new RectF(a.left + a.width() * 0.55f, a.top + a.height() * 0.22f, a.right, a.top + a.height() * 0.42f), TXT, true, Paint.Align.RIGHT, 0.8f);
            fit(c, "WEAR " + Math.round(t.tyreWear[w]) + "%", new RectF(a.left, a.top + a.height() * 0.46f, a.right, a.top + a.height() * 0.62f), DIM, true, Paint.Align.LEFT, 0.9f);
            hbar(c, new RectF(a.left, a.top + a.height() * 0.64f, a.right, a.top + a.height() * 0.74f), t.tyreWear[w] / 100f, dmgCol(t.tyreWear[w] * 1.2f));
            String extra = "Brake " + t.brakeTemp[w] + "°C" + (t.tyreDmg[w] > 0 ? "   Damage " + t.tyreDmg[w] + "%" : "") + (t.blisters[w] > 0 ? "   Blisters " + t.blisters[w] + "%" : "");
            fit(c, extra, new RectF(a.left, a.top + a.height() * 0.78f, a.right, a.bottom), TXT, false, Paint.Align.LEFT, 0.7f);
        }
        RectF a = card(c, R(0.60f, 0.10f, 0.385f, 0.55f), "CAR DAMAGE", 0);
        String[] names = {"Front wing L", "Front wing R", "Rear wing", "Floor", "Diffuser", "Sidepods", "Gearbox", "Engine"};
        int[] vals = {t.flWing, t.frWing, t.rearWing, t.floor, t.diffuser, t.sidepod, t.gearbox, t.engineDmg};
        float rh = a.height() / 8f;
        for (int i = 0; i < 8; i++) {
            float y = a.top + i * rh;
            fit(c, names[i], new RectF(a.left, y, a.left + a.width() * 0.34f, y + rh), TXT, false, Paint.Align.LEFT, 0.55f);
            hbar(c, new RectF(a.left + a.width() * 0.36f, y + rh * 0.25f, a.right - a.width() * 0.17f, y + rh * 0.75f), vals[i] / 100f, dmgCol(vals[i]));
            fit(c, vals[i] + "%", new RectF(a.right - a.width() * 0.15f, y, a.right, y + rh), TXT, true, Paint.Align.RIGHT, 0.6f);
        }
        StringBuilder f = new StringBuilder();
        if (t.drsFault) f.append("DRS fault   ");
        if (t.ersFault) f.append("ERS fault   ");
        if (t.blown) f.append("ENGINE BLOWN   ");
        if (t.seized) f.append("ENGINE SEIZED   ");
        boolean bad = f.length() > 0;
        a = card(c, R(0.60f, 0.67f, 0.385f, 0.12f), "FAULTS", bad ? RED : 0);
        fit(c, bad ? f.toString().trim() : "None", a, bad ? RED : GRN, true, Paint.Align.LEFT, 0.6f);
        a = card(c, R(0.60f, 0.81f, 0.385f, 0.14f), "ENGINE  /  BRAKE BIAS  /  FUEL", 0);
        fit(c, t.engineTemp + "°C   " + t.brakeBias + "%   " + String.format(java.util.Locale.US, "%.1f kg", t.fuel), a, TXT, true, Paint.Align.LEFT, 0.6f);
        RectF info = R(0.015f, 0.90f, 0.57f, 0.07f);
        box(c, info, CARD);
        String inf = t.haveSession ? t.trackName() + "  -  " + t.sessionName() + "  -  " + t.weatherName() + "  -  track " + t.trackTemp + "\u00B0C  air " + t.airTemp + "\u00B0C" : "No session data yet";
        fit(c, inf, new RectF(info.left + info.width() * 0.03f, info.top, info.right - info.width() * 0.03f, info.bottom), DIM, false, Paint.Align.LEFT, 0.5f);
    }

    private void drawSetup(Canvas c) {
        RectF a = card(c, R(0.015f, 0.10f, 0.58f, 0.85f), "CONNECT F1 25 TO THIS TABLET", 0);
        List<String> ips = host.addresses();
        String ip = ips.isEmpty() ? "(connect the tablet to Wi-Fi)" : ips.get(0);
        String[] lines = {
            "1.  Put the Xbox and this tablet on the same Wi-Fi.",
            "2.  In F1 25 open  Settings > Telemetry Settings.",
            "3.  UDP Telemetry: On      UDP Format: 2025",
            "4.  UDP IP Address: the address below",
            "5.  UDP Port: 20777      Broadcast Mode: Off",
            "6.  Send Rate: 30 Hz or 60 Hz, then start a session."};
        float y = a.top + a.height() * 0.02f, lh = a.height() * 0.085f;
        for (String s : lines) { fit(c, s, new RectF(a.left, y, a.right, y + lh), TXT, false, Paint.Align.LEFT, 0.6f); y += lh; }
        y += lh * 0.3f;
        fit(c, "THIS TABLET'S ADDRESS", new RectF(a.left, y, a.right, y + lh * 0.6f), DIM, true, Paint.Align.LEFT, 0.8f); y += lh * 0.6f;
        fit(c, ip, new RectF(a.left, y, a.right, y + lh * 1.5f), YEL, true, Paint.Align.LEFT, 0.9f); y += lh * 1.6f;
        for (int i = 1; i < ips.size() && i < 3; i++) { fit(c, ips.get(i), new RectF(a.left, y, a.right, y + lh * 0.7f), DIM, false, Paint.Align.LEFT, 0.7f); y += lh * 0.7f; }
        y += lh * 0.3f;
        String net = host.netStatus();
        boolean ok = t.receiving();
        fit(c, !net.isEmpty() ? net : ok ? "Receiving telemetry: " + pktRate + " packets/s" : demo ? "Demo mode is on" : "Listening on UDP 20777 - nothing received yet",
            new RectF(a.left, y, a.right, y + lh), !net.isEmpty() ? RED : ok || demo ? GRN : ORG, true, Paint.Align.LEFT, 0.65f);

        demoBtn.set(R(0.62f, 0.10f, 0.365f, 0.16f));
        box(c, demoBtn, demo ? YEL : CARD);
        fit(c, demo ? "DEMO MODE: ON" : "DEMO MODE: OFF", demoBtn, demo ? 0xFF000000 : TXT, true, Paint.Align.CENTER, 0.4f);
        unitBtn.set(R(0.62f, 0.30f, 0.365f, 0.16f));
        box(c, unitBtn, CARD);
        fit(c, mph ? "SPEED UNITS: MPH" : "SPEED UNITS: KM/H", unitBtn, TXT, true, Paint.Align.CENTER, 0.4f);
        RectF note = R(0.62f, 0.50f, 0.365f, 0.45f);
        box(c, note, CARD);
        String[] n = {"Demo mode shows a pretend race", "so you can see every screen without", "the game running.", "", "Tip: the tablet needs to be awake and", "this app open while you race."};
        float ny = note.top + note.height() * 0.05f, nh = note.height() / 8.5f;
        for (String s : n) { fit(c, s, new RectF(note.left + note.width() * 0.05f, ny, note.right - note.width() * 0.05f, ny + nh), DIM, false, Paint.Align.LEFT, 0.55f); ny += nh; }
    }

    // ------------------------------------------------------------ touch
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() != MotionEvent.ACTION_UP) return true;
        float x = e.getX(), y = e.getY();
        for (int i = 0; i < 3; i++) if (tabRect[i].contains(x, y)) { page = i; host.onPage(i); invalidate(); return true; }
        if (page == 2) {
            if (demoBtn.contains(x, y)) { demo = !demo; host.onDemo(demo); invalidate(); }
            else if (unitBtn.contains(x, y)) { mph = !mph; host.onUnits(mph); invalidate(); }
        } else if (x > W * 0.3f && x < W * 0.7f && y > H * 0.2f && y < H * 0.8f) {
            // tap the middle of a screen to flip between RACE and CAR
            page = page == 0 ? 1 : 0; host.onPage(page); invalidate();
        }
        return true;
    }
}
