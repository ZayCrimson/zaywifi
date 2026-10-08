package com.zaywifi.app;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.DialogInterface;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private VoucherManager vouchers;
    private PortalServer portal;
    private TextView statusText;
    private TextView hotspotText;
    private TextView clientStatText;
    private TextView clientInfoText;
    private TextView voucherCountText;
    private TextView usedCountText;
    private TextView activityText;
    private String gw = "";
    private String hotspotInterface = "";

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private TextView title(String value) {
        TextView t = label(value, 22, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable strokeBg(int color, int strokeColor, float radius) {
        GradientDrawable d = bg(color, radius);
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(strokeBg(Color.rgb(25, 27, 34), Color.rgb(48, 51, 62), 18));
        return box;
    }

    private Button actionButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setMinHeight(dp(48));
        b.setBackground(bg(color, 14));
        return b;
    }

    private LinearLayout.LayoutParams lp(int width, int height, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.topMargin = dp(top);
        return p;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vouchers = new VoucherManager(this);
        portal = new PortalServer(this, vouchers);
        buildUI();
        refreshGateway();
        refreshStats();
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(12, 13, 17));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = title("ZAY WiFi");
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView version = label("HOTSPOT MANAGER", 10, Color.rgb(145, 151, 165));
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(10), 0, dp(10), 0);
        version.setBackground(bg(Color.rgb(28, 30, 39), 10));
        header.addView(version, new LinearLayout.LayoutParams(dp(122), dp(30)));
        root.addView(header);

        LinearLayout statusCard = card();
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = label("●", 20, Color.rgb(245, 92, 92));
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(28), dp(32)));
        statusText = label("Berhenti", 18, Color.WHITE);
        statusText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, dp(32), 1));
        statusCard.addView(statusRow);
        hotspotText = label("Memeriksa hotspot...", 13, Color.rgb(151, 157, 172));
        statusCard.addView(hotspotText, lp(-1, dp(24), 4));
        root.addView(statusCard, lp(-1, -2, 8));

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.addView(statCard("CLIENT", "0", 0), new LinearLayout.LayoutParams(0, dp(88), 1));
        stats.addView(statCard("VOUCHER", "0", 1), new LinearLayout.LayoutParams(0, dp(88), 1));
        stats.addView(statCard("TERPAKAI", "0", 2), new LinearLayout.LayoutParams(0, dp(88), 1));
        root.addView(stats, lp(-1, dp(88), 10));

        LinearLayout control = card();
        control.addView(sectionTitle("Kontrol Hotspot"));
        LinearLayout controls1 = new LinearLayout(this);
        controls1.setOrientation(LinearLayout.HORIZONTAL);
        Button start = actionButton("▶  Mulai", Color.rgb(37, 160, 108));
        start.setOnClickListener(v -> startService());
        Button stop = actionButton("■  Berhenti", Color.rgb(190, 61, 69));
        stop.setOnClickListener(v -> stopService());
        controls1.addView(start, new LinearLayout.LayoutParams(0, dp(50), 1));
        controls1.addView(stop, new LinearLayout.LayoutParams(0, dp(50), 1));
        root.addView(control, lp(-1, -2, 10));
        control.addView(controls1, lp(-1, dp(50), 10));

        Button refresh = actionButton("↻  Refresh Hotspot", Color.rgb(43, 47, 58));
        refresh.setOnClickListener(v -> refreshGateway());
        control.addView(refresh, lp(-1, dp(48), 8));

        LinearLayout voucherCard = card();
        voucherCard.addView(sectionTitle("Voucher"));
        TextView voucherHint = label("Buat voucher otomatis atau tentukan kode sendiri.", 13, Color.rgb(145, 151, 165));
        voucherCard.addView(voucherHint, lp(-1, dp(24), 2));

        LinearLayout voucherButtons = new LinearLayout(this);
        voucherButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button auto = actionButton("+ Otomatis", Color.rgb(91, 75, 210));
        auto.setOnClickListener(v -> showCreateVoucherDialog(false));
        Button custom = actionButton("✎ Custom", Color.rgb(65, 73, 91));
        custom.setOnClickListener(v -> showCreateVoucherDialog(true));
        voucherButtons.addView(auto, new LinearLayout.LayoutParams(0, dp(50), 1));
        voucherButtons.addView(custom, new LinearLayout.LayoutParams(0, dp(50), 1));
        voucherCard.addView(voucherButtons, lp(-1, dp(50), 10));

        Button list = actionButton("☷  Kelola Voucher", Color.rgb(32, 35, 44));
        list.setOnClickListener(v -> showVouchers());
        voucherCard.addView(list, lp(-1, dp(48), 8));
        root.addView(voucherCard, lp(-1, -2, 10));

        LinearLayout clientCard = card();
        clientCard.addView(sectionTitle("Client Aktif"));
        clientInfoText = label("0 perangkat terdeteksi", 13, Color.rgb(145, 151, 165));
        clientCard.addView(clientInfoText, lp(-1, dp(24), 2));
        Button refreshClient = actionButton("↻  Refresh Client", Color.rgb(32, 35, 44));
        refreshClient.setOnClickListener(v -> refreshClients());
        clientCard.addView(refreshClient, lp(-1, dp(48), 8));
        root.addView(clientCard, lp(-1, -2, 10));

        LinearLayout activityCard = card();
        activityCard.addView(sectionTitle("Aktivitas"));
        activityText = label("Belum ada aktivitas.", 12, Color.rgb(145, 151, 165));
        activityText.setGravity(Gravity.TOP);
        activityCard.addView(activityText, lp(-1, dp(52), 4));
        root.addView(activityCard, lp(-1, -2, 10));

        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout statCard(String name, String value, int type) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(bg(Color.rgb(25, 27, 34), 16));
        TextView number = label(value, 22, Color.WHITE);
        number.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (type == 0) clientStatText = number;
        if (type == 1) voucherCountText = number;
        if (type == 2) usedCountText = number;
        box.addView(number, new LinearLayout.LayoutParams(-1, dp(34)));
        TextView text = label(name, 9, Color.rgb(135, 141, 156));
        box.addView(text, new LinearLayout.LayoutParams(-1, dp(22)));
        return box;
    }

    private TextView sectionTitle(String value) {
        TextView t = title(value);
        t.setTextSize(16);
        return t;
    }

    private void refreshGateway() {
        new Thread(() -> {
            detectHotspot();
            runOnUiThread(() -> {
                if (gw.isEmpty()) {
                    hotspotText.setText("Hotspot belum aktif");
                } else {
                    hotspotText.setText("Hotspot  •  " + hotspotInterface + "  •  " + gw);
                }
            });
        }).start();
    }

    private void detectHotspot() {
        gw = "";
        hotspotInterface = "";
        Root.Result r = Root.run("dumpsys wifi 2>/dev/null");
        if (r == null || r.output == null || !isSoftApActive(r.output)) return;

        String[] interfaces = {"wlan1", "ap0", "ap1", "swlan0", "softap0", "wlan2", "rndis0"};
        for (String iface : interfaces) {
            Root.Result x = Root.run("ip -4 addr show dev " + iface + " 2>/dev/null");
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
        boolean found = false;
        boolean active = false;
        for (String line : dump.split("\n")) {
            String s = line.trim();
            int pos = s.indexOf("num SoftApManagers:");
            if (pos >= 0) {
                String value = s.substring(pos + 19).trim();
                int end = 0;
                while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
                if (end > 0) {
                    active = "1".equals(value.substring(0, end));
                    found = true;
                }
            }
        }
        if (found) return active;
        String lower = dump.toLowerCase(Locale.US);
        return lower.contains("softapstate: enabled") || lower.contains("softap state: enabled") || lower.contains("softap state=enabled");
    }

    private boolean isPrivateIpv4(String ip) {
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }

    private void startService() {
        new Thread(() -> {
            boolean started = portal.start();
            if (!started) {
                runOnUiThread(() -> {
                    statusText.setText("Server gagal");
                    activityText.setText(portal.getLastError());
                });
                return;
            }
            detectHotspot();
            if (!gw.isEmpty()) {
                Root.Result r = Firewall.setup(gw, portal.port());
                if (r == null || !r.ok) {
                    runOnUiThread(() -> {
                        statusText.setText("Server aktif, firewall gagal");
                        activityText.setText("Firewall: " + (r == null ? "tidak ada hasil" : r.output));
                    });
                    return;
                }
                runOnUiThread(() -> {
                    statusText.setText("Aktif");
                    hotspotText.setText("Hotspot  •  " + hotspotInterface + "  •  " + gw);
                    activityText.setText("Captive portal aktif\nhttp://" + gw + ":8080");
                });
            } else {
                runOnUiThread(() -> {
                    statusText.setText("Server lokal aktif");
                    hotspotText.setText("Hotspot belum aktif");
                    activityText.setText("Server lokal: http://127.0.0.1:" + portal.port());
                });
            }
        }).start();
    }

    private void stopService() {
        new Thread(() -> {
            Firewall.cleanup();
            portal.stop();
            runOnUiThread(() -> {
                statusText.setText("Berhenti");
                activityText.setText("Captive portal dihentikan.");
            });
        }).start();
    }

    private void refreshStats() {
        List<VoucherManager.Voucher> list = vouchers.list();
        int used = 0;
        for (VoucherManager.Voucher v : list) if (v.used()) used++;
        if (voucherCountText != null) voucherCountText.setText(String.valueOf(list.size()));
        if (usedCountText != null) usedCountText.setText(String.valueOf(used));
        refreshClients();
    }

    private void refreshClients() {
        new Thread(() -> {
            Root.Result r = Root.run("ip neigh 2>/dev/null");
            int count = 0;
            if (r != null && r.output != null) {
                for (String line : r.output.split("\n")) {
                    if (line.contains("REACHABLE") || line.contains("STALE") || line.contains("DELAY") || line.contains("PROBE")) count++;
                }
            }
            final int total = count;
            runOnUiThread(() -> {
                String text = total + " perangkat terdeteksi";
                clientInfoText.setText(text);
                if (clientStatText != null) clientStatText.setText(String.valueOf(total));
            });
        }).start();
    }

    private void showCreateVoucherDialog(boolean custom) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);

        if (custom) {
            EditText input = new EditText(this);
            input.setHint("Contoh: JAY2026");
            input.setSingleLine(true);
            input.setTextColor(Color.WHITE);
            input.setHintTextColor(Color.rgb(120, 126, 140));
            input.setBackground(strokeBg(Color.rgb(29, 31, 39), Color.rgb(65, 69, 84), 14));
            input.setPadding(dp(14), 0, dp(14), 0);
            box.addView(input, new LinearLayout.LayoutParams(-1, dp(54)));

            TextView hint = label("4–32 karakter • A-Z, 0-9, dan -", 12, Color.rgb(140, 146, 160));
            box.addView(hint, lp(-1, dp(34), 6));

            AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Buat Voucher Custom")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", null)
                .create();
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String code = vouchers.createCustom(input.getText().toString());
                    dialog.dismiss();
                    activityText.setText("Voucher custom dibuat: " + code);
                    refreshStats();
                    showVoucherCode(code);
                } catch (Exception e) {
                    input.setError(e.getMessage());
                }
            }));
            dialog.show();
        } else {
            TextView hint = label("Kode dibuat otomatis 6 karakter dan tidak memakai 0, 1, O, atau I.", 13, Color.rgb(145, 151, 165));
            box.addView(hint, new LinearLayout.LayoutParams(-1, dp(50)));
            AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Buat Voucher Otomatis")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Generate", null)
                .create();
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String code = vouchers.createAuto();
                dialog.dismiss();
                activityText.setText("Voucher dibuat: " + code);
                refreshStats();
                showVoucherCode(code);
            }));
            dialog.show();
        }
    }

    private void showVoucherCode(String code) {
        TextView t = label(code, 28, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(20), 0, dp(20));
        new AlertDialog.Builder(this)
            .setTitle("Voucher berhasil dibuat")
            .setView(t)
            .setPositiveButton("Tutup", null)
            .show();
    }

    private void showVouchers() {
        final List<VoucherManager.Voucher> list = vouchers.list();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(4), dp(18), 0);

        if (list.isEmpty()) {
            TextView empty = label("Belum ada voucher.", 14, Color.rgb(145, 151, 165));
            box.addView(empty, new LinearLayout.LayoutParams(-1, dp(60)));
        } else {
            for (VoucherManager.Voucher v : list) addVoucherRow(box, v);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        int maxHeight = dp(460);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(-1, maxHeight));

        new AlertDialog.Builder(this)
            .setTitle("Daftar Voucher  •  " + list.size())
            .setView(scroll)
            .setPositiveButton("Tutup", null)
            .show();
    }

    private void addVoucherRow(LinearLayout parent, VoucherManager.Voucher voucher) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        row.setBackground(strokeBg(Color.rgb(25, 27, 34), Color.rgb(48, 51, 62), 14));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView code = label(voucher.code, 15, Color.WHITE);
        code.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        info.addView(code, new LinearLayout.LayoutParams(-1, dp(26)));
        String state = voucher.used() ? "Dipakai • " + voucher.ip : "Belum Dipakai";
        TextView stateText = label(state, 11, voucher.used() ? Color.rgb(100, 171, 255) : Color.rgb(74, 210, 148));
        info.addView(stateText, new LinearLayout.LayoutParams(-1, dp(22)));
        row.addView(info, new LinearLayout.LayoutParams(0, dp(54), 1));

        Button menu = actionButton("⋮", Color.rgb(40, 43, 53));
        menu.setMinWidth(dp(46));
        menu.setOnClickListener(v -> showVoucherActions(voucher));
        row.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        parent.addView(row, lp(-1, dp(70), 6));
    }

    private void showVoucherActions(VoucherManager.Voucher voucher) {
        String[] items = voucher.used()
            ? new String[]{"Reset binding", "Hapus voucher"}
            : new String[]{"Hapus voucher"};

        new AlertDialog.Builder(this)
            .setTitle(voucher.code)
            .setItems(items, (dialog, which) -> {
                if (voucher.used()) {
                    if (which == 0) resetVoucher(voucher);
                    else deleteVoucher(voucher);
                } else {
                    deleteVoucher(voucher);
                }
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    private void resetVoucher(VoucherManager.Voucher voucher) {
        new Thread(() -> {
            Root.Result r = Firewall.revoke(voucher.ip);
            vouchers.reset(voucher.code);
            runOnUiThread(() -> {
                activityText.setText(r != null && r.ok ? "Akses " + voucher.ip + " dicabut dan voucher di-reset." : "Voucher di-reset, tetapi firewall gagal mencabut " + voucher.ip);
                refreshStats();
            });
        }).start();
    }

    private void deleteVoucher(VoucherManager.Voucher voucher) {
        new AlertDialog.Builder(this)
            .setTitle("Hapus voucher?")
            .setMessage(voucher.code + (voucher.used() ? "\nAkses " + voucher.ip + " juga akan dicabut." : ""))
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus", (d, w) -> new Thread(() -> {
                boolean revoked = true;
                if (voucher.used()) {
                    Root.Result r = Firewall.revoke(voucher.ip);
                    revoked = r != null && r.ok;
                }
                vouchers.delete(voucher.code);
                final boolean finalRevoked = revoked;
                runOnUiThread(() -> {
                    activityText.setText(finalRevoked ? "Voucher " + voucher.code + " dihapus." : "Voucher dihapus, tetapi akses IP gagal dicabut.");
                    refreshStats();
                });
            }).start())
            .show();
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
