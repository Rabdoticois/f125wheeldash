package com.kam.f1dash;

import java.util.Random;

/** Fills a Telemetry with a made-up race so the dashboard can be tried without the game. */
final class DemoSource extends Thread {
    private final Telemetry t;
    private volatile boolean running = true;
    private final Random rnd = new Random(7);
    private static final double LAP = 88.0;
    private static final int[] BASE = {28000, 30000, 30000};
    private int lapIdx = 1, s1, s2;
    private boolean gotS1, gotS2;

    DemoSource(Telemetry t) { super("demo"); setDaemon(true); this.t = t; }

    void shutdown() { running = false; }

    private static double speedAt(double ph) {
        double x = ph / LAP * 2 * Math.PI;
        return 175 + 110 * Math.sin(3 * x) + 45 * Math.sin(7 * x + 1);
    }

    @Override public void run() {
        long t0 = System.nanoTime();
        synchronized (t) {
            t.trackId = 99; t.totalLaps = 5; t.trackLength = 2848; t.sessionType = 15; t.haveSession = true;
            t.trackTemp = 33; t.airTemp = 25; t.weather = 0; t.maxRpm = 13000; t.compound = 17; t.position = 4;
            t.brakeBias = 56; t.fuel = 40f; t.fuelCap = 110f; t.engineTemp = 112; t.ersMode = 2;
            t.overallBest[0] = 27800; t.overallBest[1] = 29700; t.overallBest[2] = 29700;
        }
        while (running) {
            double sec = (System.nanoTime() - t0) / 1e9;
            step(sec);
            try { Thread.sleep(33); } catch (InterruptedException e) { return; }
        }
    }

    private void step(double sec) {
        double ph = sec % LAP;
        int lap = (int) (sec / LAP) + 1;
        double v = Math.max(60, speedAt(ph)), v2 = Math.max(60, speedAt(ph + 0.3));
        double acc = v2 - v;
        synchronized (t) {
            t.lastPacketMs = System.currentTimeMillis();
            t.packets += 4;
            t.speed = (int) v;
            t.gear = (int) Math.max(2, Math.min(8, 1 + v / 42));
            double band = (v % 42) / 42.0;
            t.rpm = (int) (6500 + band * 6000);
            t.revPct = (int) Math.min(100, Math.max(0, (t.rpm - 9500) / 3500.0 * 100));
            t.throttle = acc > -0.5 ? 1f : 0f;
            t.brake = acc < -1.2 ? (float) Math.min(1.0, -acc / 4) : 0f;
            t.steer = (float) (0.5 * Math.sin(ph * 0.9));
            t.drs = (ph > 20 && ph < 29) ? 1 : 0;
            t.drsAllowed = (ph > 18 && ph < 29) ? 1 : 0;
            for (int i = 0; i < 4; i++) {
                t.tyreSurf[i] = (int) (88 + 6 * Math.sin(sec / 6 + i) + (i < 2 ? 3 : 0));
                t.tyreInner[i] = t.tyreSurf[i] + 6;
                t.tyrePress[i] = 22.5f + 0.2f * i;
                t.brakeTemp[i] = (int) (350 + t.brake * 400 + 40 * i);
                t.tyreWear[i] = (float) (lap * 4.5 + ph / LAP * 4.5 + i * 0.6);
            }
            t.engineTemp = 108 + (int) (6 * Math.sin(sec / 9));
            t.tyreAge = lap;
            t.ersStore = (float) (Telemetry.ERS_MAX * (0.5 + 0.45 * Math.sin(sec / 14)));
            t.fuel = (float) (40 - sec * 0.0135);
            t.fuelLaps = t.fuel / 1.2f;
            t.lapNum = lap; t.sector = ph < 28 ? 1 : ph < 58 ? 2 : 3;
            t.curLapMs = (int) (ph * 1000);
            t.deltaFrontMs = 1200 + (int) (400 * Math.sin(sec / 5));
            t.deltaLeaderMs = 9500 + (int) (900 * Math.sin(sec / 11));
            t.flWing = lap > 2 ? 6 : 0; t.floor = lap > 3 ? 3 : 0; t.gearbox = 2; t.engineDmg = 4; t.haveDamage = true;
            t.fiaFlag = (ph > 40 && ph < 46) ? 3 : 0;
            if (lap != lapIdx) {   // lap rolled over: finish the sectors, new bests
                lapIdx = lap; gotS1 = gotS2 = false;
                int s3 = 30000 + rnd.nextInt(900) - 450;
                int lapMs = s1 + s2 + s3;
                t.lastLapMs = lapMs;
                t.dispSec[2] = s3; t.dispCol[2] = col(2, s3);
                if (t.bestSec[2] == 0 || s3 < t.bestSec[2]) t.bestSec[2] = s3;
                if (t.bestLapMs == 0 || lapMs < t.bestLapMs) t.bestLapMs = lapMs;
            }
            if (!gotS1 && t.sector >= 2) {
                gotS1 = true; s1 = BASE[0] + rnd.nextInt(900) - 450; t.s1Ms = s1; t.s2Ms = 0;
                t.dispSec[0] = s1; t.dispCol[0] = col(0, s1); t.dispSec[1] = 0; t.dispCol[1] = -1; t.dispSec[2] = 0; t.dispCol[2] = -1;
                if (t.bestSec[0] == 0 || s1 < t.bestSec[0]) t.bestSec[0] = s1;
            }
            if (!gotS2 && t.sector >= 3) {
                gotS2 = true; s2 = BASE[1] + rnd.nextInt(900) - 450; t.s2Ms = s2;
                t.dispSec[1] = s2; t.dispCol[1] = col(1, s2);
                if (t.bestSec[1] == 0 || s2 < t.bestSec[1]) t.bestSec[1] = s2;
            }
        }
    }

    private int col(int k, int ms) {
        if (t.overallBest[k] > 0 && ms <= t.overallBest[k]) return 2;
        if (t.bestSec[k] == 0 || ms <= t.bestSec[k]) return 1;
        return 0;
    }
}
