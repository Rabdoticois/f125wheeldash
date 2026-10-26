package com.kam.f1dash;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Parses F1 25 UDP packets (format 2025) for the player's car. No Android code in here. */
public final class Telemetry {
    public static final int HEADER = 29, CARS = 22;

    // connection
    public long lastPacketMs;
    public int packets;
    // car telemetry (packet 6)
    public int speed, gear, rpm, drs, revPct, engineTemp;
    public float throttle, steer, brake;
    public final int[] brakeTemp = new int[4], tyreSurf = new int[4], tyreInner = new int[4];
    public final float[] tyrePress = new float[4];
    // lap data (packet 2)
    public int lastLapMs, curLapMs, s1Ms, s2Ms, deltaFrontMs, deltaLeaderMs;
    public int position, lapNum, pit, sector, invalid;
    // car status (packet 7)
    public int brakeBias, maxRpm = 13000, drsAllowed, compound, tyreAge, fiaFlag, ersMode;
    public float fuel, fuelCap, fuelLaps, ersStore;
    public static final float ERS_MAX = 4000000f;
    // car damage (packet 10)
    public final float[] tyreWear = new float[4];
    public final int[] tyreDmg = new int[4], blisters = new int[4];
    public int flWing, frWing, rearWing, floor, diffuser, sidepod, gearbox, engineDmg;
    public boolean drsFault, ersFault, blown, seized, haveDamage;
    // session (packet 1)
    public int weather, trackTemp, airTemp, totalLaps, trackLength, sessionType, trackId;
    public boolean haveSession;
    // session history (packet 11): best lap + best sectors, for the purple/green/yellow colours
    public int bestLapMs;
    public final int[] bestSec = new int[3], overallBest = new int[3];
    private final int[][] carBest = new int[CARS][3];
    // sectors of the lap on screen (S1, S2 live, S3 once the lap is finished)
    public final int[] dispSec = new int[3];
    public final int[] dispCol = {-1, -1, -1};          // frozen when each sector is completed
    private final boolean[] secDone = new boolean[3];
    private int prevLapNum = -1, trackS1, trackS2, lastInvalid;
    public int player;

    public static final String[] TRACKS = new String[100];
    static {
        String[] n = {"Melbourne", "Paul Ricard", "Shanghai", "Sakhir (Bahrain)", "Catalunya", "Monaco", "Montreal", "Silverstone",
            "Hockenheim", "Hungaroring", "Spa", "Monza", "Singapore", "Suzuka", "Abu Dhabi", "Texas (COTA)", "Brazil (Interlagos)",
            "Austria", "Sochi", "Mexico", "Baku (Azerbaijan)", "Sakhir Short", "Silverstone Short", "Texas Short", "Suzuka Short",
            "Hanoi", "Zandvoort", "Imola", "Portimao", "Jeddah", "Miami", "Las Vegas", "Losail (Qatar)", "Silverstone (Reverse)",
            "Austria (Reverse)", "Zandvoort (Reverse)"};
        for (int i = 0; i < n.length; i++) TRACKS[i] = n[i];
        TRACKS[99] = "Demo Circuit";
    }
    public static final String[] WEATHER = {"Clear", "Light cloud", "Overcast", "Light rain", "Heavy rain", "Storm"};

    public String trackName() {
        return trackId >= 0 && trackId < TRACKS.length && TRACKS[trackId] != null ? TRACKS[trackId] : "Track " + trackId;
    }
    public String weatherName() { return weather >= 0 && weather < WEATHER.length ? WEATHER[weather] : "?"; }
    public String sessionName() {
        int t = sessionType;
        if (t >= 1 && t <= 4) return "Practice";
        if (t >= 5 && t <= 9) return "Qualifying";
        if (t == 10) return "Short qualifying";
        if (t == 13) return "Time trial";
        if (t >= 15 && t <= 17) return "Race";
        return "Session";
    }

    /** Colour of sector i: 3 invalid, 2 purple (overall best), 1 green (personal best), 0 yellow (slower), -1 none. */
    public int sectorColour(int i) { return dispCol[i]; }

    private int colourOf(int k, int ms, int inv) {
        if (ms <= 0) return -1;
        if (inv != 0) return 3;
        if (overallBest[k] > 0 && ms <= overallBest[k]) return 2;
        if (bestSec[k] == 0 || ms <= bestSec[k]) return 1;
        return 0;
    }

    // ---- little-endian readers
    private static int u8(ByteBuffer b, int o) { return b.get(o) & 0xFF; }
    private static int u16(ByteBuffer b, int o) { return b.getShort(o) & 0xFFFF; }
    private static long u32(ByteBuffer b, int o) { return b.getInt(o) & 0xFFFFFFFFL; }

    public synchronized void parse(byte[] data, int len) {
        if (len < HEADER) return;
        ByteBuffer b = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN);
        int id = u8(b, 6), pl = u8(b, 27), body = len - HEADER;
        if (pl >= CARS) return;
        player = pl;
        lastPacketMs = System.currentTimeMillis();
        packets++;
        switch (id) {
            case 1: if (len >= HEADER + 120) {
                weather = u8(b, HEADER); trackTemp = b.get(HEADER + 1); airTemp = b.get(HEADER + 2);
                totalLaps = u8(b, HEADER + 3); trackLength = u16(b, HEADER + 4);
                sessionType = u8(b, HEADER + 6); trackId = b.get(HEADER + 7); haveSession = true;
            } break;
            case 2: lap(b, body); break;
            case 6: tel(b, body); break;
            case 7: status(b, body); break;
            case 10: damage(b, body); break;
            case 11: history(b, body); break;
            default: break;
        }
    }

    private void tel(ByteBuffer b, int body) {
        int stride = (body - 3) / CARS;
        if (stride < 60) return;
        int o = HEADER + player * stride;
        speed = u16(b, o); throttle = b.getFloat(o + 2); steer = b.getFloat(o + 6); brake = b.getFloat(o + 10);
        gear = b.get(o + 15); rpm = u16(b, o + 16); drs = u8(b, o + 18); revPct = u8(b, o + 19);
        for (int i = 0; i < 4; i++) {
            brakeTemp[i] = u16(b, o + 22 + 2 * i);
            tyreSurf[i] = u8(b, o + 30 + i);
            tyreInner[i] = u8(b, o + 34 + i);
            tyrePress[i] = b.getFloat(o + 40 + 4 * i);
        }
        engineTemp = u16(b, o + 38);
    }

    private void lap(ByteBuffer b, int body) {
        int stride = (body - 2) / CARS;
        if (stride < 38) return;
        int o = HEADER + player * stride;
        lastLapMs = (int) u32(b, o); curLapMs = (int) u32(b, o + 4);
        s1Ms = u16(b, o + 8) + u8(b, o + 10) * 60000;
        s2Ms = u16(b, o + 11) + u8(b, o + 13) * 60000;
        deltaFrontMs = u16(b, o + 14) + u8(b, o + 16) * 60000;
        deltaLeaderMs = u16(b, o + 17) + u8(b, o + 19) * 60000;
        position = u8(b, o + 32); int ln = u8(b, o + 33); pit = u8(b, o + 34);
        sector = u8(b, o + 36) + 1; invalid = u8(b, o + 37);
        if (s1Ms > 0) trackS1 = s1Ms;
        if (s2Ms > 0) trackS2 = s2Ms;
        if (ln != prevLapNum) {
            if (prevLapNum > 0 && ln == prevLapNum + 1 && lastLapMs > 0 && trackS1 > 0 && trackS2 > 0 && lastLapMs > trackS1 + trackS2) {
                int s3 = lastLapMs - trackS1 - trackS2;
                dispSec[0] = trackS1; dispSec[1] = trackS2; dispSec[2] = s3;
                if (!secDone[0]) dispCol[0] = colourOf(0, trackS1, lastInvalid);
                if (!secDone[1]) dispCol[1] = colourOf(1, trackS2, lastInvalid);
                dispCol[2] = colourOf(2, s3, lastInvalid);
            }
            secDone[0] = secDone[1] = secDone[2] = false;
            trackS1 = trackS2 = 0; prevLapNum = ln;
        }
        lapNum = ln;
        if (s1Ms > 0 && !secDone[0]) {
            secDone[0] = true; dispSec[0] = s1Ms; dispCol[0] = colourOf(0, s1Ms, invalid);
            dispSec[1] = dispSec[2] = 0; dispCol[1] = dispCol[2] = -1;
        }
        if (s2Ms > 0 && !secDone[1]) {
            secDone[1] = true; dispSec[1] = s2Ms; dispCol[1] = colourOf(1, s2Ms, invalid);
        }
        lastInvalid = invalid;
    }

    private void status(ByteBuffer b, int body) {
        int stride = body / CARS;
        if (stride < 42) return;
        int o = HEADER + player * stride;
        brakeBias = u8(b, o + 3); fuel = b.getFloat(o + 5); fuelCap = b.getFloat(o + 9); fuelLaps = b.getFloat(o + 13);
        maxRpm = u16(b, o + 17); drsAllowed = u8(b, o + 22); compound = u8(b, o + 26); tyreAge = u8(b, o + 27);
        fiaFlag = b.get(o + 28); ersStore = b.getFloat(o + 37); ersMode = u8(b, o + 41);
    }

    private void damage(ByteBuffer b, int body) {
        int stride = body / CARS;
        if (stride < 46) return;
        int o = HEADER + player * stride;
        for (int i = 0; i < 4; i++) {
            tyreWear[i] = b.getFloat(o + 4 * i); tyreDmg[i] = u8(b, o + 16 + i); blisters[i] = u8(b, o + 24 + i);
        }
        flWing = u8(b, o + 28); frWing = u8(b, o + 29); rearWing = u8(b, o + 30); floor = u8(b, o + 31);
        diffuser = u8(b, o + 32); sidepod = u8(b, o + 33); drsFault = u8(b, o + 34) != 0; ersFault = u8(b, o + 35) != 0;
        gearbox = u8(b, o + 36); engineDmg = u8(b, o + 37); blown = u8(b, o + 44) != 0; seized = u8(b, o + 45) != 0;
        haveDamage = true;
    }

    private void history(ByteBuffer b, int body) {
        if (body < 7) return;
        int car = u8(b, HEADER), n = u8(b, HEADER + 1);
        int bestLap = u8(b, HEADER + 3), s1 = u8(b, HEADER + 4), s2 = u8(b, HEADER + 5), s3 = u8(b, HEADER + 6);
        if (car >= CARS) return;
        int base = HEADER + 7;
        int[] best = {s1, s2, s3};
        for (int k = 0; k < 3; k++) {
            int lapIdx = best[k];
            int v = 0;
            if (lapIdx >= 1 && lapIdx <= n && base + lapIdx * 14 <= b.limit()) {
                int o = base + (lapIdx - 1) * 14;
                v = k == 0 ? u16(b, o + 4) + u8(b, o + 6) * 60000
                  : k == 1 ? u16(b, o + 7) + u8(b, o + 9) * 60000
                  : u16(b, o + 10) + u8(b, o + 12) * 60000;
            }
            carBest[car][k] = v;
        }
        for (int k = 0; k < 3; k++) {
            int m = 0;
            for (int c = 0; c < CARS; c++) { int v = carBest[c][k]; if (v > 0 && (m == 0 || v < m)) m = v; }
            overallBest[k] = m;
        }
        if (car == player) {
            System.arraycopy(carBest[car], 0, bestSec, 0, 3);
            if (bestLap >= 1 && bestLap <= n && base + bestLap * 14 <= b.limit()) bestLapMs = (int) u32(b, base + (bestLap - 1) * 14);
        }
    }

    public synchronized boolean receiving() { return System.currentTimeMillis() - lastPacketMs < 3000; }
}
