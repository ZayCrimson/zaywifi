package com.zaywifi.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.net.*;
import java.util.*;

public class MainActivity extends Activity {

    private VoucherManager vouchers;
    private PortalServer portal;

    private TextView status;
    private TextView gateway;
    private TextView log;

    private String gw = "";
    private String hotspotInterface = "";

    @Override
    public void onCreate(Bundle b) {

        super.onCreate(b);

        setContentView(
                R.layout.activity_main
        );

        vouchers =
                new VoucherManager(this);

        portal =
                new PortalServer(
                        this,
                        vouchers
                );

        status =
                findViewById(
                        R.id.status
                );

        gateway =
                findViewById(
                        R.id.gateway
                );

        log =
                findViewById(
                        R.id.log
                );

        findViewById(
                R.id.start
        ).setOnClickListener(
                v -> startService()
        );

        findViewById(
                R.id.stop
        ).setOnClickListener(
                v -> stopService()
        );

        findViewById(
                R.id.create
        ).setOnClickListener(
                v -> createDialog()
        );

        findViewById(
                R.id.list
        ).setOnClickListener(
                v -> listDialog()
        );

        findViewById(
                R.id.clients
        ).setOnClickListener(
                v -> clientsDialog()
        );

        refreshGateway();
    }

    /*
     * Coba deteksi interface hotspot.
     *
     * Kita tidak lagi menganggap semua IP private
     * sebagai hotspot.
     */
    private void refreshGateway() {

        new Thread(
                () -> {

                    detectHotspot();

                    runOnUiThread(
                            () -> {

                                if (
                                        gw.isEmpty()
                                ) {

                                    gateway.setText(
                                            "Hotspot: belum terdeteksi"
                                    );

                                } else {

                                    gateway.setText(
                                            "Hotspot: " +
                                                    hotspotInterface +
                                                    "  " +
                                                    gw
                                    );
                                }
                            }
                    );

                }
        ).start();
    }

    /*
     * Deteksi interface yang umum digunakan hotspot Android.
     */
    private void detectHotspot() {

        gw = "";
        hotspotInterface = "";

        String[] interfaces = {
                "ap0",
                "ap1",
                "wlan0",
                "swlan0",
                "softap0",
                "rndis0"
        };

        for (String iface : interfaces) {

            Root.Result r =
                    Root.run(
                            "ip -4 addr show dev " +
                                    iface +
                                    " 2>/dev/null"
                    );

            if (!r.ok || r.output == null) {
                continue;
            }

            String[] lines =
                    r.output.split("\\n");

            for (String line : lines) {

                String t =
                        line.trim();

                if (!t.startsWith("inet ")) {
                    continue;
                }

                String[] parts =
                        t.split("\\s+");

                if (parts.length < 2) {
                    continue;
                }

                String address =
                        parts[1];

                String ip =
                        address.split("/")[0];

                if (isPrivateIPv4(ip)) {

                    hotspotInterface = iface;
                    gw = ip;

                    return;
                }
            }
        }
    }

    private boolean isPrivateIPv4(
            String ip
    ) {

        return
                ip.startsWith("10.") ||
                ip.startsWith("192.168.") ||
                ip.startsWith("172.");
    }

    /*
     * Start server lokal terlebih dahulu.
     * Hotspot tidak menjadi syarat server hidup.
     */
    private void startService() {

        new Thread(
                () -> {

                    /*
                     * 1. Jalankan HTTP server lokal.
                     */
                    if (!portal.start()) {

                        String error =
                                portal.getLastError();

                        ui(
                                "Gagal menjalankan captive portal:\n" +
                                        error,
                                false
                        );

                        return;
                    }

                    /*
                     * 2. Coba deteksi hotspot.
                     */
                    detectHotspot();

                    runOnUiThread(
                            () -> {

                                if (
                                        gw.isEmpty()
                                ) {

                                    gateway.setText(
                                            "Hotspot: belum terdeteksi"
                                    );

                                } else {

                                    gateway.setText(
                                            "Hotspot: " +
                                                    hotspotInterface +
                                                    "  " +
                                                    gw
                                    );
                                }
                            }
                    );

                    /*
                     * 3. Kalau hotspot belum aktif,
                     * server lokal tetap berjalan.
                     */
                    if (gw.isEmpty()) {

                        ui(
                                "Server lokal aktif di http://127.0.0.1:" +
                                        portal.port() +
                                        "\nHotspot belum terdeteksi.\n" +
                                        "Aktifkan hotspot untuk mengaktifkan captive portal.",
                                true
                        );

                        return;
                    }

                    /*
                     * 4. Hotspot ditemukan.
                     * Sekarang baru pasang firewall.
                     */
                    Root.Result f =
                            Firewall.setup(
                                    gw,
                                    portal.port()
                            );

                    if (!f.ok) {

                        ui(
                                "Server lokal aktif, tetapi firewall gagal:\n" +
                                        f.output,
                                false
                        );

                        return;
                    }

                    writeEmptyAuth();

                    ui(
                            "Captive portal aktif.\n" +
                                    "Interface: " +
                                    hotspotInterface +
                                    "\nGateway: " +
                                    gw +
                                    "\nPortal: http://" +
                                    gw +
                                    ":" +
                                    portal.port(),
                            true
                    );

                }
        ).start();
    }

    private void stopService() {

        new Thread(
                () -> {

                    Root.Result r =
                            Firewall.cleanup();

                    portal.stop();

                    writeEmptyAuth();

                    ui(
                            r.ok
                                    ? "Voucher mode dihentikan dan firewall dibersihkan."
                                    : "Cleanup selesai dengan catatan: " +
                                    r.output,
                            r.ok
                    );

                }
        ).start();
    }

    private void writeEmptyAuth() {

        try {

            File d =
                    new File(
                            getFilesDir(),
                            "data"
                    );

            d.mkdirs();

            new FileOutputStream(
                    new File(
                            d,
                            "authorized_ips.txt"
                    ),
                    false
            ).close();

        } catch (Exception ignored) {
        }
    }

    private void createDialog() {

        LinearLayout box =
                new LinearLayout(this);

        box.setPadding(
                40,
                10,
                40,
                0
        );

        box.setOrientation(
                LinearLayout.VERTICAL
        );

        RadioGroup mode =
                new RadioGroup(this);

        RadioButton auto =
                new RadioButton(this);

        auto.setId(
                View.generateViewId()
        );

        auto.setText(
                "Otomatis (6 karakter)"
        );

        RadioButton custom =
                new RadioButton(this);

        custom.setId(
                View.generateViewId()
        );

        custom.setText(
                "Custom"
        );

        mode.addView(auto);
        mode.addView(custom);

        auto.setChecked(true);

        EditText count =
                new EditText(this);

        count.setHint(
                "Jumlah voucher (default 1)"
        );

        count.setInputType(2);

        EditText code =
                new EditText(this);

        code.setHint(
                "Kode custom"
        );

        code.setVisibility(
                View.GONE
        );

        mode.setOnCheckedChangeListener(
                (g, id) ->
                        code.setVisibility(
                                id == custom.getId()
                                        ? View.VISIBLE
                                        : View.GONE
                        )
        );

        box.addView(mode);
        box.addView(count);
        box.addView(code);

        new AlertDialog.Builder(this)
                .setTitle("Buat Voucher")
                .setView(box)
                .setPositiveButton(
                        "Generate",
                        (d, w) -> {

                            if (auto.isChecked()) {

                                int n = 1;

                                try {

                                    n =
                                            Math.max(
                                                    1,
                                                    Math.min(
                                                            1000,
                                                            Integer.parseInt(
                                                                    count.getText()
                                                                            .toString()
                                                                            .trim()
                                                            )
                                                    )
                                            );

                                } catch (Exception ignored) {
                                }

                                StringBuilder s =
                                        new StringBuilder();

                                for (
                                        int i = 0;
                                        i < n;
                                        i++
                                ) {

                                    s.append(
                                            vouchers.createAuto()
                                    ).append(
                                            i + 1 < n
                                                    ? "\n"
                                                    : ""
                                    );
                                }

                                showText(
                                        "Voucher dibuat",
                                        s.toString()
                                );

                            } else {

                                try {

                                    showText(
                                            "Voucher dibuat",
                                            vouchers.createCustom(
                                                    code.getText()
                                                            .toString()
                                            )
                                    );

                                } catch (Exception e) {

                                    toast(
                                            e.getMessage()
                                    );
                                }
                            }
                        }
                )
                .setNegativeButton(
                        "Batal",
                        null
                )
                .show();
    }

    private void listDialog() {

        List<VoucherManager.Voucher> vs =
                vouchers.list();

        if (vs.isEmpty()) {

            showText(
                    "Daftar Voucher",
                    "Belum ada voucher."
            );

            return;
        }

        LinearLayout box =
                new LinearLayout(this);

        box.setOrientation(
                LinearLayout.VERTICAL
        );

        for (
                VoucherManager.Voucher v :
                vs
        ) {

            LinearLayout row =
                    new LinearLayout(this);

            row.setPadding(
                    10,
                    12,
                    10,
                    12
            );

            row.setOrientation(
                    LinearLayout.HORIZONTAL
            );

            TextView t =
                    new TextView(this);

            t.setText(
                    v.code +
                            "\n" +
                            (
                                    v.used()
                                            ? "Dipakai · " + v.ip
                                            : "Belum dipakai"
                            )
            );

            t.setTextColor(
                    Color.WHITE
            );

            t.setTextSize(15);

            row.addView(
                    t,
                    new LinearLayout.LayoutParams(
                            0,
                            -2,
                            1
                    )
            );

            Button reset =
                    new Button(this);

            reset.setText(
                    "Reset"
            );

            reset.setOnClickListener(
                    x -> {

                        if (v.used()) {

                            Firewall.revoke(
                                    v.ip
                            );

                            portal.writeAuth(
                                    v.ip,
                                    false
                            );
                        }

                        vouchers.reset(
                                v.code
                        );

                        toast(
                                "Voucher di-reset"
                        );
                    }
            );

            row.addView(reset);

            Button del =
                    new Button(this);

            del.setText(
                    "Hapus"
            );

            del.setOnClickListener(
                    x -> {

                        new AlertDialog.Builder(this)
                                .setTitle(
                                        "Hapus voucher?"
                                )
                                .setMessage(
                                        v.code
                                )
                                .setNegativeButton(
                                        "Batal",
                                        null
                                )
                                .setPositiveButton(
                                        "Hapus",
                                        (d, w) -> {

                                            if (v.used()) {

                                                Firewall.revoke(
                                                        v.ip
                                                );
                                            }

                                            vouchers.delete(
                                                    v.code
                                            );

                                            if (v.used()) {

                                                portal.writeAuth(
                                                        v.ip,
                                                        false
                                                );
                                            }

                                            toast(
                                                    "Voucher dihapus"
                                            );
                                        }
                                )
                                .show();
                    }
            );

            row.addView(del);

            box.addView(row);
        }

        new AlertDialog.Builder(this)
                .setTitle(
                        "Daftar Voucher"
                )
                .setView(box)
                .setPositiveButton(
                        "Tutup",
                        null
                )
                .show();
    }

    private void clientsDialog() {

        List<String> cs =
                portal.clients();

        if (cs.isEmpty()) {

            showText(
                    "Client Aktif",
                    "Belum ada client yang diberi akses."
            );

            return;
        }

        LinearLayout box =
                new LinearLayout(this);

        box.setOrientation(
                LinearLayout.VERTICAL
        );

        for (String ip : cs) {

            LinearLayout row =
                    new LinearLayout(this);

            row.setGravity(
                    Gravity.CENTER_VERTICAL
            );

            TextView t =
                    new TextView(this);

            t.setText(ip);
            t.setTextColor(
                    Color.WHITE
            );
            t.setTextSize(16);

            row.addView(
                    t,
                    new LinearLayout.LayoutParams(
                            0,
                            -2,
                            1
                    )
            );

            Button cut =
                    new Button(this);

            cut.setText(
                    "Putuskan"
            );
            cut.setOnClickListener(
                    v -> {

                        Firewall.revoke(ip);

                        portal.writeAuth(
                                ip,
                                false
                        );

                        toast(
                                "Akses " +
                                        ip +
                                        " diputuskan"
                        );
                    }
            );

            row.addView(cut);

            box.addView(row);
        }

        new AlertDialog.Builder(this)
                .setTitle(
                        "Client Aktif"
                )
                .setView(box)
                .setPositiveButton(
                        "Tutup",
                        null
                )
                .show();
    }

    private void showText(
            String title,
            String text
    ) {

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(
                        "OK",
                        null
                )
                .show();
    }

    private void ui(
            String s,
            boolean ok
    ) {

        runOnUiThread(
                () -> {

                    String first =
                            s == null
                                    ? ""
                                    : s.split(
                                            "\\n"
                                    )[0];

                    status.setText(
                            ok
                                    ? "● " + first
                                    : "● " + first
                    );

                    status.setTextColor(
                            ok
                                    ? Color.rgb(
                                            56,
                                            217,
                                            150
                                    )
                                    : Color.rgb(
                                            255,
                                            113,
                                            129
                                    )
                    );

                    log.setText(
                            s
                    );
                }
        );
    }

    private void toast(
            String s
    ) {

        runOnUiThread(
                () ->
                        Toast.makeText(
                                this,
                                s == null
                                        ? "Terjadi kesalahan"
                                        : s,
                                Toast.LENGTH_SHORT
                        ).show()
        );
    }
}
