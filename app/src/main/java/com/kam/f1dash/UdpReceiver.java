package com.kam.f1dash;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/** Listens for the game's UDP telemetry and feeds it to Telemetry. */
final class UdpReceiver extends Thread {
    private final Telemetry tel;
    private final int port;
    private volatile boolean running = true;
    private volatile DatagramSocket socket;
    volatile String error = "";
    volatile boolean bound;

    UdpReceiver(Telemetry tel, int port) {
        super("udp-telemetry");
        setDaemon(true);
        this.tel = tel;
        this.port = port;
    }

    @Override public void run() {
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(port));
            s.setSoTimeout(500);
            socket = s;
            bound = true;
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (running) {
                try {
                    p.setLength(buf.length);
                    s.receive(p);
                    tel.parse(buf, p.getLength());
                } catch (SocketTimeoutException ignored) {
                } catch (RuntimeException bad) {
                    // a malformed packet must never stop the dashboard
                }
            }
            s.close();
        } catch (Exception e) {
            error = "Could not listen on UDP " + port + ": " + e.getMessage();
        }
        bound = false;
    }

    void shutdown() {
        running = false;
        DatagramSocket s = socket;
        if (s != null) s.close();
    }
}
