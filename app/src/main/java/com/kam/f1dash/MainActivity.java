package com.kam.f1dash;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity implements DashView.Host {
    private static final int PORT = 20777;
    private final Telemetry tel = new Telemetry();
    private DashView view;
    private UdpReceiver rx;
    private DemoSource demo;
    private WifiManager.MulticastLock lock;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        prefs = getSharedPreferences("f1dash", MODE_PRIVATE);
        view = new DashView(this, tel, this);
        view.page = prefs.getInt("page", 0);
        view.mph = prefs.getBoolean("mph", false);
        view.demo = prefs.getBoolean("demo", false);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { lock = wm.createMulticastLock("f1dash"); lock.setReferenceCounted(false); lock.acquire(); }
        } catch (RuntimeException ignored) { }
        rx = new UdpReceiver(tel, PORT);
        rx.start();
        if (view.demo) startDemo();
    }

    @Override protected void onPause() {
        super.onPause();
        if (rx != null) rx.shutdown();
        rx = null;
        stopDemo();
        if (lock != null && lock.isHeld()) lock.release();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void startDemo() { if (demo == null) { demo = new DemoSource(tel); demo.start(); } }
    private void stopDemo() { if (demo != null) { demo.shutdown(); demo = null; } }

    @Override public void onDemo(boolean on) {
        prefs.edit().putBoolean("demo", on).apply();
        if (on) startDemo(); else stopDemo();
    }
    @Override public void onUnits(boolean mph) { prefs.edit().putBoolean("mph", mph).apply(); }
    @Override public void onPage(int page) { prefs.edit().putInt("page", page).apply(); }
    @Override public String netStatus() { return rx == null ? "" : rx.error; }

    @Override public List<String> addresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
            }
        } catch (Exception ignored) { }
        return out;
    }
}
