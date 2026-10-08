package com.zaywifi.app;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private VoucherManager vouchers;
    private PortalServer portal;
    private TextView status, gateway, log, clients;
    private String gw = "";
    private String hotspotInterface = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vouchers = new VoucherManager(this);
        portal = new PortalServer(this, vouchers);
        buildUI();
        refreshGateway();
        refreshVouchers();
    }

    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(Color.WHITE);
        t.setPadding(16, 12, 16, 12);
        return t;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        return b;
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);
        root.setBackgroundColor(Color.rgb(18, 18, 18));

        root.addView(text("ZAY WiFi", 28));

        status = text("Status: berhenti", 18);
        root.addView(status);

        gateway = text("Hotspot: memeriksa...", 16);
        root.addView(gateway);

        Button refresh = button("Refresh Hotspot");
        refresh.setOnClickListener(v -> refreshGateway());
        root.addView(refresh);

        Button start = button("START");
        start.setOnClickListener(v -> startService());
        root.addView(start);

        Button stop = button("STOP");
        stop.setOnClickListener(v -> stopService());
        root.addView(stop);

        Button create = button("Buat Voucher");
        create.setOnClickListener(v -> createVoucher());
        root.addView(create);

        Button list = button("Daftar Voucher");
        list.setOnClickListener(v -> showVouchers());
        root.addView(list);

        clients = text("Client: 0", 16);
        root.addView(clients);

        Button refreshClients = button("Refresh Client");
        refreshClients.setOnClickListener(v -> refreshClients());
        root.addView(refreshClients);

        log = text("", 14);
        root.addView(log);

        setContentView(root);
    }

    private void refreshGateway() {
        new Thread(() -> {
            detectHotspot();
            runOnUiThread(() -> {
                if (gw.isEmpty()) {
                    gateway.setText("Hotspot: belum aktif");
                } else {
                    gateway.setText("Hotspot: " + hotspotInterface + "  " + gw);
                }
            });
        }).start();
    }

    private void detectHotspot() {
        gw = "";
        hotspotInterface = "";

        Root.Result r = Root.run("dumpsys wifi 2>/dev/null");
        if (r == null || r.output == null) return;

        if (!isSoftApActive(r.output)) return;

        String[] interfaces = {
            "wlan1", "ap0", "ap1", "swlan0",
            "softap0", "wlan2", "rndis0"
        };

        for (String iface : interfaces) {
            Root.Result x = Root.run(
                "ip -4 addr show dev " + iface + " 2>/dev/null"
            );

            if (x == null || x.output == null) continue;

            for (String line : x.output.split("\n")) {
                String t = line.trim();
                if (!t.startsWith("inet ")) continue;

                String[] p = t.split("\\s+");
                if (p.length < 2) continue;

                String ip = p[1].split("/")[0];

                if (isPrivateIpv4(ip)) {
                    hotspotInterface = iface;
                    gw = ip;
                    return;
                }
            }
        }
    }

    private boolean isSoftApActive(String dump) {
        boolean foundState = false;
        boolean active = false;

        for (String line : dump.split("\n")) {
            String s = line.trim();
            int pos = s.indexOf("num SoftApManagers:");
            if (pos >= 0) {
                String value = s.substring(pos + "num SoftApManagers:".length()).trim();
                int end = 0;
                while (end < value.length() && Character.isDigit(value.charAt(end))) {
                    end++;
                }
                if (end > 0) {
                    active = "1".equals(value.substring(0, end));
                    foundState = true;
                }
            }
        }

        if (foundState) return active;

        String lower = dump.toLowerCase(Locale.US);
        return lower.contains("softapstate: enabled") ||
               lower.contains("softap state: enabled") ||
               lower.contains("softap state=enabled");
    }

    private boolean isPrivateIpv4(String ip) {
        return ip.startsWith("10.") ||
               ip.startsWith("192.168.") ||
               ip.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }

    private void startService() {
        new Thread(() -> {
            boolean serverStarted = portal.start();

            if (!serverStarted) {
                runOnUiThread(() -> status.setText(
                    "Status: server gagal\n" + portal.getLastError()
                ));
                return;
            }

            detectHotspot();

            if (!gw.isEmpty()) {
                Root.Result r = Firewall.setup(gw, portal.port());

                if (r == null || !r.ok) {
                    runOnUiThread(() -> status.setText(
                        "Status: server aktif, firewall gagal"
                    ));
                    return;
                }

                runOnUiThread(() -> {
                    status.setText("Status: aktif");
                    gateway.setText(
                        "Hotspot: " + hotspotInterface + "  " + gw
                    );
                    log.setText(
                        "Captive portal aktif\nhttp://" + gw + ":8080"
                    );
                });
            } else {
                runOnUiThread(() -> {
                    status.setText("Status: server aktif");
                    gateway.setText("Hotspot: belum aktif");
                    log.setText("http://127.0.0.1:" + portal.port());
                });
            }
        }).start();
    }

    private void stopService() {
        new Thread(() -> {
            Firewall.cleanup();
            portal.stop();

            runOnUiThread(() -> {
                status.setText("Status: berhenti");
                log.setText("");
            });
        }).start();
    }

    private void createVoucher() {
        String code = vouchers.createAuto();

        if (code == null || code.isEmpty()) {
            Toast.makeText(this, "Gagal membuat voucher", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, code, Toast.LENGTH_LONG).show();
        refreshVouchers();
    }

    private void refreshVouchers() {
        if (log == null) return;

        List<VoucherManager.Voucher> list = vouchers.list();
        StringBuilder data = new StringBuilder();
        for (VoucherManager.Voucher v : list) {
            data.append(v.code);
            if (v.ip == null || v.ip.isEmpty()) {
                data.append("  Belum Dipakai");
            } else {
                data.append("  Dipakai - ").append(v.ip);
            }
            data.append("\n");
        }
        log.setText(data.toString());
    }

    private void showVouchers() {
        List<VoucherManager.Voucher> list = vouchers.list();
        StringBuilder data = new StringBuilder();
        for (VoucherManager.Voucher v : list) {
            data.append(v.code);
            if (v.ip == null || v.ip.isEmpty()) {
                data.append("  Belum Dipakai");
            } else {
                data.append("  Dipakai - ").append(v.ip);
            }
            data.append("\n");
        }

        TextView view = text(data.toString(), 15);

        new AlertDialog.Builder(this)
            .setTitle("Daftar Voucher")
            .setView(view)
            .setPositiveButton("Tutup", null)
            .show();
    }

    private void refreshClients() {
        new Thread(() -> {
            Root.Result r = Root.run("ip neigh 2>/dev/null");
            int count = 0;

            if (r != null && r.output != null) {
                for (String line : r.output.split("\n")) {
                    if (line.contains("REACHABLE") ||
                        line.contains("STALE") ||
                        line.contains("DELAY") ||
                        line.contains("PROBE")) {
                        count++;
                    }
                }
            }

            final int total = count;
            runOnUiThread(() -> clients.setText("Client: " + total));
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        new Thread(() -> {
            Firewall.cleanup();
            portal.stop();
        }).start();
    }
}
