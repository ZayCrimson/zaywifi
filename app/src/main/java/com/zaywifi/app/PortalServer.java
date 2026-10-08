package com.zaywifi.app;

import android.content.Context;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class PortalServer {

    private final Context ctx;
    private final VoucherManager vouchers;
    private final File dataDir;

    private volatile boolean running;
    private ServerSocket server;
    private ExecutorService pool;

    private final int port = 8080;

    // Menyimpan error terakhir supaya MainActivity bisa menampilkan
    // penyebab sebenarnya kalau server gagal start.
    private volatile String lastError = "";

    public PortalServer(Context c, VoucherManager v) {
        ctx = c;
        vouchers = v;
        dataDir = new File(c.getFilesDir(), "data");
        dataDir.mkdirs();
    }

    public int port() {
        return port;
    }

    public String getLastError() {
        return lastError;
    }

    public synchronized boolean start() {
        if (running) {
            return true;
        }

        lastError = "";

        try {
            server = new ServerSocket(
                    port,
                    32,
                    InetAddress.getByName("0.0.0.0")
            );

            pool = Executors.newCachedThreadPool();
            running = true;

            pool.submit(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            final Socket s = server.accept();

                            pool.submit(new Runnable() {
                                @Override
                                public void run() {
                                    handle(s);
                                }
                            });

                        } catch (IOException e) {
                            if (running) {
                                lastError = e.toString();
                                e.printStackTrace();
                            }
                        }
                    }
                }
            });

            return true;

        } catch (IOException e) {
            lastError = e.toString();
            e.printStackTrace();

            try {
                if (server != null) {
                    server.close();
                }
            } catch (Exception ignored) {
            }

            server = null;
            running = false;

            if (pool != null) {
                pool.shutdownNow();
            }

            pool = null;

            return false;
        }
    }

    public synchronized void stop() {
        running = false;

        try {
            if (server != null) {
                server.close();
            }
        } catch (Exception ignored) {
        }

        server = null;

        if (pool != null) {
            pool.shutdownNow();
        }

        pool = null;
    }

    public boolean isRunning() {
        return running;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(
                            s.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            String first = r.readLine();

            if (first == null) {
                s.close();
                return;
            }

            String[] f = first.split(" ");

            String method = f.length > 0 ? f[0] : "GET";
            String target = f.length > 1 ? f[1] : "/";

            int len = 0;

            String line;

            while ((line = r.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(Locale.US);

                if (lower.startsWith("content-length:")) {
                    try {
                        len = Integer.parseInt(
                                line.substring(15).trim()
                        );
                    } catch (NumberFormatException ignored) {
                        len = 0;
                    }
                }
            }

            String body = "";

            if (len > 0) {
                char[] b = new char[len];
                int n = r.read(b);

                if (n > 0) {
                    body = new String(b, 0, n);
                }
            }

            String path;

            try {
                path = URLDecoder.decode(
                        target.split("\\?", 2)[0],
                        "UTF-8"
                );
            } catch (Exception e) {
                path = "/";
            }

            String ip = s.getInetAddress().getHostAddress();

            String out = route(
                    method,
                    path,
                    body,
                    ip
            );

            byte[] bytes = out.getBytes(
                    StandardCharsets.UTF_8
            );

            OutputStream o = s.getOutputStream();

            o.write(bytes);
            o.flush();

            s.close();

        } catch (Exception e) {
            // Client yang putus sendiri atau request rusak
            // tidak boleh membuat server mati.
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String route(
            String method,
            String path,
            String body,
            String ip
    ) {

        if (path.equals("/status")) {
            return json(
                    200,
                    "{\"authorized\":" +
                            authorized(ip) +
                            "}"
            );
        }

        if (
                path.equals("/authorize") &&
                method.equalsIgnoreCase("POST")
        ) {
            String v = form(body, "voucher");

            return authorize(v, ip);
        }

        if (path.startsWith("/probe/")) {
            String p = path.substring(7);

            if (authorized(ip)) {
                return response(p);
            }

            return html(
                    200,
                    load("index.html")
            );
        }

        if (path.equals("/success.html")) {
            return html(
                    200,
                    load("success.html")
            );
        }

        return html(
                200,
                load("index.html")
        );
    }

    private String authorize(
            String code,
            String ip
    ) {

        code = code == null
                ? ""
                : code.trim().toUpperCase(Locale.US);

        if (!code.matches(
                "[A-Z0-9][A-Z0-9-]{2,31}"
        )) {

            return json(
                    400,
                    "{\"ok\":false," +
                            "\"message\":\"Format voucher tidak valid.\"}"
            );
        }

        VoucherManager.Voucher v =
                vouchers.find(code);

        if (v == null) {
            return json(
                    200,
                    "{\"ok\":false," +
                            "\"message\":\"Voucher tidak ditemukan.\"}"
            );
        }

        if (v.used() && !v.ip.equals(ip)) {
            return json(
                    200,
                    "{\"ok\":false," +
                            "\"message\":\"Voucher ini sudah terikat ke perangkat lain.\"}"
            );
        }

        Root.Result rr =
                Firewall.allow(ip);

        if (!rr.ok) {
            return json(
                    500,
                    "{\"ok\":false," +
                            "\"message\":\"Voucher valid, tetapi akses internet gagal diaktifkan.\"}"
            );
        }

        vouchers.bind(code, ip);

        writeAuth(ip, true);

        return json(
                200,
                "{\"ok\":true," +
                        "\"ip\":\"" +
                        esc(ip) +
                        "\"," +
                        "\"message\":\"Voucher valid. Perangkat berhasil terhubung.\"}"
        );
    }

    private boolean authorized(String ip) {
        try {
            File f = new File(
                    dataDir,
                    "authorized_ips.txt"
            );

            if (!f.exists()) {
                return false;
            }

            List<String> lines =
                    java.nio.file.Files.readAllLines(
                            f.toPath()
                    );

            for (String l : lines) {
                if (l.trim().equals(ip)) {
                    return true;
                }
            }

        } catch (Exception ignored) {
        }

        return false;
    }

    public void writeAuth(
            String ip,
            boolean add
    ) {

        File f = new File(
                dataDir,
                "authorized_ips.txt"
        );

        List<String> a =
                new ArrayList<>();

        try {

            if (f.exists()) {

                List<String> lines =
                        java.nio.file.Files.readAllLines(
                                f.toPath()
                        );

                for (String l : lines) {

                    l = l.trim();

                    if (
                            !l.isEmpty() &&
                            !l.equals(ip)
                    ) {
                        a.add(l);
                    }
                }
            }

            if (add) {
                a.add(ip);
            }

            java.nio.file.Files.write(
                    f.toPath(),
                    a,
                    StandardCharsets.UTF_8
            );

        } catch (Exception ignored) {
        }
    }

    public List<String> clients() {

        try {

            File f = new File(
                    dataDir,
                    "authorized_ips.txt"
            );

            if (f.exists()) {

                return java.nio.file.Files.readAllLines(
                        f.toPath()
                );
            }

        } catch (Exception ignored) {
        }

        return new ArrayList<>();
    }

    private String response(String p) {

        if (p.equals("generate_204")) {
            return raw(
                    204,
                    "text/plain",
                    ""
            );
        }

        if (p.equals("connecttest.txt")) {
            return raw(
                    200,
                    "text/plain",
                    "Microsoft Connect Test"
            );
        }

        if (p.equals("ncsi.txt")) {
            return raw(
                    200,
                    "text/plain",
                    "Microsoft NCSI."
            );
        }

        return raw(
                200,
                "text/html",
                "<HTML><HEAD>" +
                        "<TITLE>Success</TITLE>" +
                        "</HEAD><BODY>" +
                        "Success" +
                        "</BODY></HTML>"
        );
    }

    private String load(String n) {

        try {

            InputStream in =
                    ctx.getAssets().open(
                            "portal/" + n
                    );

            ByteArrayOutputStream out =
                    new ByteArrayOutputStream();

            byte[] buffer =
                    new byte[4096];

            int count;

            while (
                    (count = in.read(buffer)) != -1
            ) {
                out.write(
                        buffer,
                        0,
                        count
                );
            }

            in.close();

            return new String(
                    out.toByteArray(),
                    StandardCharsets.UTF_8
            );

        } catch (Exception e) {

            return "<h1>ZAY WiFi</h1>";
        }
    }

    private static String form(
            String body,
            String key
    ) {

        if (body == null) {
            return "";
        }

        for (String x : body.split("&")) {

            String[] p =
                    x.split("=", 2);

            if (p.length == 2) {

                try {

                    String k =
                            URLDecoder.decode(
                                    p[0],
                                    "UTF-8"
                            );

                    if (k.equals(key)) {

                        return URLDecoder.decode(
                                p[1],
                                "UTF-8"
                        );
                    }

                } catch (Exception ignored) {
                }
            }
        }

        return "";
    }

    private static String json(
            int c,
            String b
    ) {

        return raw(
                c,
                "application/json; charset=utf-8",
                b
        );
    }

    private static String html(
            int c,
            String b
    ) {

        return raw(
                c,
                "text/html; charset=utf-8",
                b
        );
    }

    private static String raw(
            int c,
            String type,
            String b
    ) {

        String status;

        if (c == 200) {
            status = "OK";
        } else if (c == 204) {
            status = "No Content";
        } else if (c == 400) {
            status = "Bad Request";
        } else if (c == 500) {
            status = "Internal Server Error";
        } else {
            status = "Error";
        }

        byte[] bytes =
                b.getBytes(
                        StandardCharsets.UTF_8
                );

        return "HTTP/1.1 " +
                c +
                " " +
                status +
                "\r\n" +
                "Content-Type: " +
                type +
                "\r\n" +
                "Cache-Control: no-cache, no-store\r\n" +
                "Connection: close\r\n" +
                "Content-Length: " +
                bytes.length +
                "\r\n\r\n" +
                b;
    }

    private static String esc(String s) {

        if (s == null) {
            return "";
        }

        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
