package com.zaywifi.app;

import android.content.Context;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class PortalServer {

    private final Context ctx;
    private final VoucherManager vouchers;
    private final File dataDir;

    private volatile boolean running;
    private volatile String lastError = "";

    private ServerSocket server;
    private ExecutorService pool;
    private final int port = 8080;

    public PortalServer(Context ctx, VoucherManager vouchers) {
        this.ctx = ctx;
        this.vouchers = vouchers;
        dataDir = new File(ctx.getFilesDir(), "data");
        if (!dataDir.exists()) dataDir.mkdirs();
    }

    public int port() {
        return port;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean start() {
        if (running) return true;
        lastError = "";

        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), port
            ), 32);

            running = true;
            pool = Executors.newCachedThreadPool();

            Thread acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            final Socket socket = server.accept();
                            if (pool != null) {
                                pool.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        handle(socket);
                                    }
                                });
                            } else {
                                close(socket);
                            }
                        } catch (IOException e) {
                            if (running) lastError = e.toString();
                        }
                    }
                }
            });

            acceptThread.setName("ZAY-Portal-Accept");
            acceptThread.start();
            return true;

        } catch (Exception e) {
            lastError = e.toString();
            running = false;
            close(server);
            server = null;
            if (pool != null) {
                pool.shutdownNow();
                pool = null;
            }
            return false;
        }
    }

    public void stop() {
        running = false;
        close(server);
        server = null;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(10000);

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    socket.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );

            OutputStream out = socket.getOutputStream();

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                close(socket);
                return;
            }

            String[] first = requestLine.split(" ");
            if (first.length < 2) {
                close(socket);
                return;
            }

            String method = first[0];
            String target = first[1];
            int contentLength = 0;
            String userAgent = "";

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) break;

                int p = line.indexOf(':');
                if (p > 0) {
                    String key = line.substring(0, p).trim();
                    String value = line.substring(p + 1).trim();

                    if ("Content-Length".equalsIgnoreCase(key)) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (Exception ignored) {
                        }
                    }

                    if ("User-Agent".equalsIgnoreCase(key)) {
                        userAgent = value;
                    }
                }
            }

            String body = "";

            if (contentLength > 0 && contentLength < 1024 * 1024) {
                char[] buffer = new char[contentLength];
                int read = 0;

                while (read < contentLength) {
                    int n = reader.read(
                        buffer,
                        read,
                        contentLength - read
                    );

                    if (n < 0) break;
                    read += n;
                }

                body = new String(buffer, 0, read);
            }

            route(
                method,
                target,
                body,
                socket.getInetAddress().getHostAddress(),
                userAgent,
                out
            );

        } catch (Exception e) {
            try {
                send(
                    socket.getOutputStream(),
                    500,
                    "text/plain; charset=utf-8",
                    "Internal Server Error"
                );
            } catch (Exception ignored) {
            }
        } finally {
            close(socket);
        }
    }

    private void route(
        String method,
        String target,
        String body,
        String ip,
        String userAgent,
        OutputStream out
    ) throws IOException {

        String path = target;
        int query = path.indexOf('?');

        if (query >= 0) {
            path = path.substring(0, query);
        }

        if ("/status".equals(path)) {
            send(
                out,
                200,
                "application/json; charset=utf-8",
                "{\"running\":true}"
            );
            return;
        }

        if ("/authorize".equals(path) &&
            "POST".equalsIgnoreCase(method)) {

            String code = getParam(body, "voucher");

            if (code == null || code.isEmpty()) {
                sendJson(out, 400, false, "Masukkan kode voucher terlebih dahulu.");
                return;
            }

            VoucherManager.Voucher voucher = vouchers.find(code);

            if (voucher == null) {
                sendJson(out, 403, false, "Voucher tidak ditemukan.");
                return;
            }

            Root.Result result = Firewall.allow(ip);

            if (result == null || !result.ok) {
                sendJson(out, 500, false, "Voucher valid, tetapi akses internet gagal diaktifkan.");
                return;
            }

            vouchers.bind(code, ip);
            appendAuthorized(ip);

            sendJson(
                out,
                200,
                true,
                "Voucher valid. Perangkat berhasil terhubung."
            );
            return;
        }

        if (path.startsWith("/probe/")) {
            handleProbe(path, ip, userAgent, out);
            return;
        }

        if ("/success".equals(path) || "/success.html".equals(path)) {
            send(
                out,
                200,
                "text/html; charset=utf-8",
                successPage(userAgent, authorized(ip))
            );
            return;
        }

        send(
            out,
            200,
            "text/html; charset=utf-8",
            indexPage(userAgent, authorized(ip))
        );
    }

    private void handleProbe(
        String path,
        String ip,
        String userAgent,
        OutputStream out
    ) throws IOException {

        if (!authorized(ip)) {
            send(
                out,
                200,
                "text/html; charset=utf-8",
                indexPage(userAgent, false)
            );
            return;
        }

        String key = path.substring("/probe/".length());

        if ("generate_204".equals(key)) {
            send(out, 204, "text/plain", "");
            return;
        }

        if ("hotspot-detect.html".equals(key) ||
            "success.html".equals(key) ||
            "canonical.html".equals(key) ||
            key.endsWith("/wifiredirect.html")) {

            send(
                out,
                200,
                "text/html; charset=utf-8",
                "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>"
            );
            return;
        }

        if ("connecttest.txt".equals(key)) {
            send(out, 200, "text/plain", "Microsoft Connect Test");
            return;
        }

        if ("ncsi.txt".equals(key)) {
            send(out, 200, "text/plain", "Microsoft NCSI.");
            return;
        }

        if ("success.txt".equals(key)) {
            send(out, 200, "text/plain", "success");
            return;
        }

        if ("check_network_status.txt".equals(key)) {
            send(out, 200, "text/plain", "NetworkManager is online");
            return;
        }

        send(out, 204, "text/plain", "");
    }

    private String getParam(String body, String name) {
        if (body == null) return "";

        String[] parts = body.split("&");

        for (String part : parts) {
            String[] p = part.split("=", 2);

            if (p.length != 2) continue;

            if (name.equals(p[0])) {
                return urlDecode(p[1]);
            }
        }

        return "";
    }

    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private void sendJson(
        OutputStream out,
        int status,
        boolean ok,
        String message
    ) throws IOException {

        String safe = message
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");

        send(
            out,
            status,
            "application/json; charset=utf-8",
            "{\"ok\":" + ok + ",\"message\":\"" + safe + "\"}"
        );
    }

    private void appendAuthorized(String ip) {
        File file = new File(dataDir, "authorized_ips.txt");

        try {
            FileWriter writer = new FileWriter(file, true);
            writer.write(ip);
            writer.write("\n");
            writer.close();
        } catch (IOException ignored) {
        }
    }

    private boolean authorized(String ip) {
        File file = new File(dataDir, "authorized_ips.txt");

        if (!file.exists()) return false;

        try {
            BufferedReader reader =
                new BufferedReader(new FileReader(file));

            String line;

            while ((line = reader.readLine()) != null) {
                if (ip.equals(line.trim())) {
                    reader.close();
                    return true;
                }
            }

            reader.close();

        } catch (IOException ignored) {
        }

        return false;
    }

    private String detectPlatform(String userAgent) {
        String ua = userAgent == null
            ? ""
            : userAgent.toLowerCase(Locale.US);

        if (ua.contains("iphone") ||
            ua.contains("ipad") ||
            ua.contains("ipod")) {
            return "ios";
        }

        if (ua.contains("mac os") ||
            ua.contains("macintosh")) {
            return "macos";
        }

        if (ua.contains("android")) {
            return "android";
        }

        if (ua.contains("windows")) {
            return "windows";
        }

        if (ua.contains("firefox")) {
            return "firefox";
        }

        if (ua.contains("kindle") ||
            ua.contains("silk")) {
            return "kindle";
        }

        return "generic";
    }

    private String platformLabel(String platform) {
        if ("ios".equals(platform)) return "iOS";
        if ("macos".equals(platform)) return "macOS";
        if ("android".equals(platform)) return "Android";
        if ("windows".equals(platform)) return "Windows";
        if ("firefox".equals(platform)) return "Firefox";
        if ("kindle".equals(platform)) return "Kindle";
        return "Wi-Fi";
    }

    private String checkUrls(String platform) {
        if ("android".equals(platform)) {
            return "[\"/probe/generate_204\"]";
        }

        if ("ios".equals(platform) ||
            "macos".equals(platform)) {
            return "[\"/probe/hotspot-detect.html\"]";
        }

        if ("windows".equals(platform)) {
            return "[\"/probe/connecttest.txt\",\"/probe/ncsi.txt\"]";
        }

        if ("firefox".equals(platform)) {
            return "[\"/probe/success.txt\",\"/probe/canonical.html\"]";
        }

        return "[\"/probe/generate_204\",\"/probe/hotspot-detect.html\",\"/probe/connecttest.txt\",\"/probe/success.txt\"]";
    }

    private String indexPage(String userAgent, boolean isAuthorized) {
        String platform = detectPlatform(userAgent);
        String page = indexTemplate();

        String content = isAuthorized
            ? authorizedContent()
            : formContent();

        return page
            .replace("__PLATFORM_LABEL__", escapeHtml(platformLabel(platform)))
            .replace("__PLATFORM__", jsonQuote(platform))
            .replace("__CHECK_URLS__", checkUrls(platform))
            .replace("{{AUTHORIZED_CONTENT}}", content);
    }

    private String successPage(String userAgent, boolean isAuthorized) {
        String platform = detectPlatform(userAgent);
        String page = successTemplate();

        String content = isAuthorized
            ? successAuthorizedContent()
            : successUnauthorizedContent();

        String script = isAuthorized
            ? successScript(platform)
            : "";

        return page
            .replace("__PLATFORM__", jsonQuote(platform))
            .replace("{{SUCCESS_CONTENT}}", content)
            .replace("{{SUCCESS_SCRIPT}}", script);
    }

    private String successScript(String platform) {
        String target = "/probe/generate_204";

        if ("ios".equals(platform) ||
            "macos".equals(platform)) {
            target = "/probe/hotspot-detect.html";
        } else if ("windows".equals(platform)) {
            target = "/probe/connecttest.txt";
        } else if ("firefox".equals(platform)) {
            target = "/probe/success.txt";
        }

        return "<script>(function(){setTimeout(function(){window.location.replace('" +
            target +
            "');},700);setTimeout(function(){window.close();},2200);})();</script>";
    }

    private String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private String jsonQuote(String value) {
        return "\"" +
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") +
            "\"";
    }

    private void send(
        OutputStream out,
        int status,
        String type,
        String body
    ) throws IOException {

        byte[] data = body.getBytes(StandardCharsets.UTF_8);

        String response =
            "HTTP/1.1 " +
            statusText(status) +
            "\r\n" +
            "Content-Type: " +
            type +
            "\r\n" +
            "Content-Length: " +
            data.length +
            "\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n" +
            "\r\n";

        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private String statusText(int status) {
        if (status == 200) return "200 OK";
        if (status == 204) return "204 No Content";
        if (status == 400) return "400 Bad Request";
        if (status == 403) return "403 Forbidden";
        if (status == 500) return "500 Internal Server Error";
        return status + " Error";
    }

    private void close(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    private void close(ServerSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }


    private String indexTemplate() {
        StringBuilder s = new StringBuilder();
        s.append("<!DOCTYPE html>").append("\n");
        s.append("<html lang=\"id\">").append("\n");
        s.append("").append("\n");
        s.append("<head>").append("\n");
        s.append("").append("\n");
        s.append("    <meta charset=\"UTF-8\">").append("\n");
        s.append("").append("\n");
        s.append("    <meta").append("\n");
        s.append("        name=\"viewport\"").append("\n");
        s.append("        content=\"width=device-width, initial-scale=1.0, viewport-fit=cover\"").append("\n");
        s.append("    >").append("\n");
        s.append("").append("\n");
        s.append("    <meta name=\"theme-color\" content=\"#080b12\">").append("\n");
        s.append("    <meta name=\"color-scheme\" content=\"dark\">").append("\n");
        s.append("").append("\n");
        s.append("    <title>ZAY WiFi</title>").append("\n");
        s.append("").append("\n");
        s.append("    <style>").append("\n");
        s.append("").append("\n");
        s.append("        * {").append("\n");
        s.append("            box-sizing: border-box;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        :root {").append("\n");
        s.append("            --bg: #080b12;").append("\n");
        s.append("            --panel: rgba(18, 22, 32, .90);").append("\n");
        s.append("            --line: rgba(255,255,255,.085);").append("\n");
        s.append("            --text: #f5f7fb;").append("\n");
        s.append("            --muted: #8d96a8;").append("\n");
        s.append("            --accent: #7c5cff;").append("\n");
        s.append("            --accent2: #a855f7;").append("\n");
        s.append("            --ok: #38d996;").append("\n");
        s.append("            --danger: #ff7181;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        html,").append("\n");
        s.append("        body {").append("\n");
        s.append("            min-height: 100%;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        body {").append("\n");
        s.append("            margin: 0;").append("\n");
        s.append("            min-height: 100vh;").append("\n");
        s.append("            padding: 20px;").append("\n");
        s.append("").append("\n");
        s.append("            display: flex;").append("\n");
        s.append("            align-items: center;").append("\n");
        s.append("            justify-content: center;").append("\n");
        s.append("").append("\n");
        s.append("            overflow-x: hidden;").append("\n");
        s.append("").append("\n");
        s.append("            font-family:").append("\n");
        s.append("                -apple-system,").append("\n");
        s.append("                BlinkMacSystemFont,").append("\n");
        s.append("                \"Segoe UI\",").append("\n");
        s.append("                Roboto,").append("\n");
        s.append("                Arial,").append("\n");
        s.append("                sans-serif;").append("\n");
        s.append("").append("\n");
        s.append("            color: var(--text);").append("\n");
        s.append("").append("\n");
        s.append("            background:").append("\n");
        s.append("                radial-gradient(").append("\n");
        s.append("                    500px 320px at 8% -5%,").append("\n");
        s.append("                    rgba(124,92,255,.23),").append("\n");
        s.append("                    transparent 65%").append("\n");
        s.append("                ),").append("\n");
        s.append("                radial-gradient(").append("\n");
        s.append("                    480px 300px at 100% 105%,").append("\n");
        s.append("                    rgba(168,85,247,.16),").append("\n");
        s.append("                    transparent 65%").append("\n");
        s.append("                ),").append("\n");
        s.append("                var(--bg);").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        body::before {").append("\n");
        s.append("            content: \"\";").append("\n");
        s.append("").append("\n");
        s.append("            position: fixed;").append("\n");
        s.append("            inset: 0;").append("\n");
        s.append("").append("\n");
        s.append("            pointer-events: none;").append("\n");
        s.append("").append("\n");
        s.append("            background:").append("\n");
        s.append("                linear-gradient(").append("\n");
        s.append("                    120deg,").append("\n");
        s.append("                    transparent 25%,").append("\n");
        s.append("                    rgba(255,255,255,.018) 50%,").append("\n");
        s.append("                    transparent 75%").append("\n");
        s.append("                );").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .wrap {").append("\n");
        s.append("            width: 100%;").append("\n");
        s.append("            max-width: 390px;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .card {").append("\n");
        s.append("            position: relative;").append("\n");
        s.append("            overflow: hidden;").append("\n");
        s.append("").append("\n");
        s.append("            padding: 25px 22px 20px;").append("\n");
        s.append("").append("\n");
        s.append("            border: 1px solid var(--line);").append("\n");
        s.append("            border-radius: 27px;").append("\n");
        s.append("").append("\n");
        s.append("            background: var(--panel);").append("\n");
        s.append("").append("\n");
        s.append("            box-shadow:").append("\n");
        s.append("                0 28px 90px rgba(0,0,0,.48),").append("\n");
        s.append("                inset 0 1px 0 rgba(255,255,255,.035);").append("\n");
        s.append("").append("\n");
        s.append("            backdrop-filter: blur(22px);").append("\n");
        s.append("            -webkit-backdrop-filter: blur(22px);").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        /* TOP LINE */").append("\n");
        s.append("").append("\n");
        s.append("        .topline {").append("\n");
        s.append("            width: 58px;").append("\n");
        s.append("            height: 4px;").append("\n");
        s.append("").append("\n");
        s.append("            margin: 0 auto 24px;").append("\n");
        s.append("").append("\n");
        s.append("            border-radius: 99px;").append("\n");
        s.append("").append("\n");
        s.append("            background:").append("\n");
        s.append("                linear-gradient(").append("\n");
        s.append("                    90deg,").append("\n");
        s.append("                    var(--accent),").append("\n");
        s.append("                    var(--accent2)").append("\n");
        s.append("                );").append("\n");
        s.append("").append("\n");
        s.append("            box-shadow:").append("\n");
        s.append("                0 5px 20px rgba(124,92,255,.45);").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        /* BRAND */").append("\n");
        s.append("").append("\n");
        s.append("        .brand {").append("\n");
        s.append("            display: flex;").append("\n");
        s.append("            flex-direction: column;").append("\n");
        s.append("            align-items: center;").append("\n");
        s.append("").append("\n");
        s.append("            margin-bottom: 27px;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .brand-main {").append("\n");
        s.append("            display: flex;").append("\n");
        s.append("            align-items: center;").append("\n");
        s.append("            justify-content: center;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .brand-name {").append("\n");
        s.append("            font-size: 32px;").append("\n");
        s.append("            line-height: 1;").append("\n");
        s.append("").append("\n");
        s.append("            font-weight: 900;").append("\n");
        s.append("            letter-spacing: -.8px;").append("\n");
        s.append("").append("\n");
        s.append("            background:").append("\n");
        s.append("                linear-gradient(").append("\n");
        s.append("                    90deg,").append("\n");
        s.append("                    #7c5cff,").append("\n");
        s.append("                    #a855f7").append("\n");
        s.append("                );").append("\n");
        s.append("").append("\n");
        s.append("            -webkit-background-clip: text;").append("\n");
        s.append("            background-clip: text;").append("\n");
        s.append("").append("\n");
        s.append("            -webkit-text-fill-color: transparent;").append("\n");
        s.append("            color: transparent;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .brand-status {").append("\n");
        s.append("            display: flex;").append("\n");
        s.append("            align-items: center;").append("\n");
        s.append("            justify-content: center;").append("\n");
        s.append("").append("\n");
        s.append("            gap: 6px;").append("\n");
        s.append("").append("\n");
        s.append("            margin-top: 9px;").append("\n");
        s.append("").append("\n");
        s.append("            color: var(--muted);").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 12px;").append("\n");
        s.append("            line-height: 1;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .dot {").append("\n");
        s.append("            width: 6px;").append("\n");
        s.append("            height: 6px;").append("\n");
        s.append("").append("\n");
        s.append("            border-radius: 50%;").append("\n");
        s.append("").append("\n");
        s.append("            background: var(--ok);").append("\n");
        s.append("").append("\n");
        s.append("            box-shadow:").append("\n");
        s.append("                0 0 10px rgba(56,217,150,.75);").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        /* TITLE */").append("\n");
        s.append("").append("\n");
        s.append("        h1 {").append("\n");
        s.append("            margin: 0;").append("\n");
        s.append("").append("\n");
        s.append("            text-align: center;").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 29px;").append("\n");
        s.append("            line-height: 1.12;").append("\n");
        s.append("").append("\n");
        s.append("            letter-spacing: -.9px;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .subtitle {").append("\n");
        s.append("            margin: 10px 0 0;").append("\n");
        s.append("").append("\n");
        s.append("            color: var(--muted);").append("\n");
        s.append("").append("\n");
        s.append("            text-align: center;").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 14px;").append("\n");
        s.append("            line-height: 1.6;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        /* PLATFORM */").append("\n");
        s.append("").append("\n");
        s.append("        .platform {").append("\n");
        s.append("            display: flex;").append("\n");
        s.append("            align-items: center;").append("\n");
        s.append("            justify-content: center;").append("\n");
        s.append("").append("\n");
        s.append("            width: fit-content;").append("\n");
        s.append("").append("\n");
        s.append("            margin: 17px auto 0;").append("\n");
        s.append("").append("\n");
        s.append("            padding: 7px 10px;").append("\n");
        s.append("").append("\n");
        s.append("            border: 1px solid var(--line);").append("\n");
        s.append("            border-radius: 999px;").append("\n");
        s.append("").append("\n");
        s.append("            color: #b9c1d1;").append("\n");
        s.append("").append("\n");
        s.append("            background: rgba(255,255,255,.025);").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 11px;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .platform-dot {").append("\n");
        s.append("            width: 5px;").append("\n");
        s.append("            height: 5px;").append("\n");
        s.append("").append("\n");
        s.append("            margin-right: 7px;").append("\n");
        s.append("").append("\n");
        s.append("            border-radius: 50%;").append("\n");
        s.append("").append("\n");
        s.append("            background: #8d78ff;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        /* FORM */").append("\n");
        s.append("").append("\n");
        s.append("        .form {").append("\n");
        s.append("            margin-top: 23px;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        label {").append("\n");
        s.append("            display: block;").append("\n");
        s.append("").append("\n");
        s.append("            width: 100%;").append("\n");
        s.append("").append("\n");
        s.append("            margin: 0 0 10px;").append("\n");
        s.append("").append("\n");
        s.append("            color: #dce1eb;").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 13px;").append("\n");
        s.append("            font-weight: 700;").append("\n");
        s.append("").append("\n");
        s.append("            text-align: center;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .input-wrap {").append("\n");
        s.append("            position: relative;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .input {").append("\n");
        s.append("            width: 100%;").append("\n");
        s.append("            height: 57px;").append("\n");
        s.append("").append("\n");
        s.append("            padding: 0 48px 0 16px;").append("\n");
        s.append("").append("\n");
        s.append("            border: 1px solid rgba(255,255,255,.105);").append("\n");
        s.append("            border-radius: 16px;").append("\n");
        s.append("").append("\n");
        s.append("            outline: none;").append("\n");
        s.append("").append("\n");
        s.append("            background: rgba(255,255,255,.045);").append("\n");
        s.append("").append("\n");
        s.append("            color: var(--text);").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 17px;").append("\n");
        s.append("            font-weight: 800;").append("\n");
        s.append("").append("\n");
        s.append("            letter-spacing: 1.7px;").append("\n");
        s.append("").append("\n");
        s.append("            text-align: center;").append("\n");
        s.append("            text-transform: uppercase;").append("\n");
        s.append("").append("\n");
        s.append("            transition: .18s ease;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .input::placeholder {").append("\n");
        s.append("            color: #626c7d;").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 13px;").append("\n");
        s.append("            font-weight: 600;").append("\n");
        s.append("").append("\n");
        s.append("            letter-spacing: .5px;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .input:focus {").append("\n");
        s.append("            border-color: rgba(124,92,255,.75);").append("\n");
        s.append("").append("\n");
        s.append("            background: rgba(124,92,255,.055);").append("\n");
        s.append("").append("\n");
        s.append("            box-shadow:").append("\n");
        s.append("                0 0 0 4px rgba(124,92,255,.10);").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .key-icon {").append("\n");
        s.append("            position: absolute;").append("\n");
        s.append("").append("\n");
        s.append("            right: 16px;").append("\n");
        s.append("            top: 50%;").append("\n");
        s.append("").append("\n");
        s.append("            transform: translateY(-50%);").append("\n");
        s.append("").append("\n");
        s.append("            color: #697387;").append("\n");
        s.append("").append("\n");
        s.append("            pointer-events: none;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        /* BUTTON */").append("\n");
        s.append("").append("\n");
        s.append("        .btn {").append("\n");
        s.append("            width: 100%;").append("\n");
        s.append("            height: 56px;").append("\n");
        s.append("").append("\n");
        s.append("            margin-top: 11px;").append("\n");
        s.append("").append("\n");
        s.append("            border: 0;").append("\n");
        s.append("            border-radius: 16px;").append("\n");
        s.append("").append("\n");
        s.append("            color: #fff;").append("\n");
        s.append("").append("\n");
        s.append("            background:").append("\n");
        s.append("                linear-gradient(").append("\n");
        s.append("                    135deg,").append("\n");
        s.append("                    var(--accent),").append("\n");
        s.append("                    var(--accent2)").append("\n");
        s.append("                );").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 15px;").append("\n");
        s.append("            font-weight: 800;").append("\n");
        s.append("").append("\n");
        s.append("            letter-spacing: .1px;").append("\n");
        s.append("").append("\n");
        s.append("            cursor: pointer;").append("\n");
        s.append("").append("\n");
        s.append("            box-shadow:").append("\n");
        s.append("                0 14px 30px rgba(124,92,255,.24);").append("\n");
        s.append("").append("\n");
        s.append("            transition:").append("\n");
        s.append("                transform .15s ease,").append("\n");
        s.append("                opacity .15s ease,").append("\n");
        s.append("                filter .15s ease;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .btn:active {").append("\n");
        s.append("            transform: scale(.985);").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .btn:disabled {").append("\n");
        s.append("            opacity: .55;").append("\n");
        s.append("            cursor: wait;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        /* HINT */").append("\n");
        s.append("").append("\n");
        s.append("        .hint {").append("\n");
        s.append("            margin: 11px 0 0;").append("\n");
        s.append("").append("\n");
        s.append("            color: #667083;").append("\n");
        s.append("").append("\n");
        s.append("            text-align: center;").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 11px;").append("\n");
        s.append("            line-height: 1.5;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        /* STATUS */").append("\n");
        s.append("").append("\n");
        s.append("        .status {").append("\n");
        s.append("            min-height: 21px;").append("\n");
        s.append("").append("\n");
        s.append("            margin-top: 12px;").append("\n");
        s.append("").append("\n");
        s.append("            text-align: center;").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 12px;").append("\n");
        s.append("            line-height: 1.5;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .status.error {").append("\n");
        s.append("            color: var(--danger);").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .status.ok {").append("\n");
        s.append("            color: #72e8ae;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        /* SUCCESS */").append("\n");
        s.append("").append("\n");
        s.append("        .success {").append("\n");
        s.append("            margin-top: 23px;").append("\n");
        s.append("").append("\n");
        s.append("            padding: 18px 15px;").append("\n");
        s.append("").append("\n");
        s.append("            border: 1px solid rgba(56,217,150,.17);").append("\n");
        s.append("            border-radius: 17px;").append("\n");
        s.append("").append("\n");
        s.append("            background: rgba(56,217,150,.055);").append("\n");
        s.append("").append("\n");
        s.append("            color: #a4efc5;").append("\n");
        s.append("").append("\n");
        s.append("            text-align: center;").append("\n");
        s.append("").append("\n");
        s.append("            line-height: 1.55;").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 13px;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        .success-icon {").append("\n");
        s.append("            width: 45px;").append("\n");
        s.append("            height: 45px;").append("\n");
        s.append("").append("\n");
        s.append("            margin: 0 auto 10px;").append("\n");
        s.append("").append("\n");
        s.append("            display: grid;").append("\n");
        s.append("            place-items: center;").append("\n");
        s.append("").append("\n");
        s.append("            border-radius: 50%;").append("\n");
        s.append("").append("\n");
        s.append("            background: rgba(56,217,150,.10);").append("\n");
        s.append("").append("\n");
        s.append("            color: var(--ok);").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 21px;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        /* FOOTER */").append("\n");
        s.append("").append("\n");
        s.append("        .footer {").append("\n");
        s.append("            margin-top: 20px;").append("\n");
        s.append("").append("\n");
        s.append("            color: #555f70;").append("\n");
        s.append("").append("\n");
        s.append("            text-align: center;").append("\n");
        s.append("").append("\n");
        s.append("            font-size: 10px;").append("\n");
        s.append("").append("\n");
        s.append("            letter-spacing: .25px;").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("        /* SMALL SCREEN */").append("\n");
        s.append("").append("\n");
        s.append("        @media (max-height: 650px) {").append("\n");
        s.append("").append("\n");
        s.append("            body {").append("\n");
        s.append("                padding: 12px;").append("\n");
        s.append("            }").append("\n");
        s.append("").append("\n");
        s.append("            .card {").append("\n");
        s.append("                padding-top: 19px;").append("\n");
        s.append("                padding-bottom: 16px;").append("\n");
        s.append("            }").append("\n");
        s.append("").append("\n");
        s.append("            .topline {").append("\n");
        s.append("                margin-bottom: 17px;").append("\n");
        s.append("            }").append("\n");
        s.append("").append("\n");
        s.append("            .brand {").append("\n");
        s.append("                margin-bottom: 19px;").append("\n");
        s.append("            }").append("\n");
        s.append("").append("\n");
        s.append("            .form {").append("\n");
        s.append("                margin-top: 18px;").append("\n");
        s.append("            }").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("    </style>").append("\n");
        s.append("").append("\n");
        s.append("</head>").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("<body>").append("\n");
        s.append("").append("\n");
        s.append("<div class=\"wrap\">").append("\n");
        s.append("").append("\n");
        s.append("    <main class=\"card\">").append("\n");
        s.append("").append("\n");
        s.append("        <div class=\"topline\"></div>").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("        <!-- BRAND -->").append("\n");
        s.append("").append("\n");
        s.append("        <div class=\"brand\">").append("\n");
        s.append("").append("\n");
        s.append("            <div class=\"brand-main\">").append("\n");
        s.append("").append("\n");
        s.append("                <div class=\"brand-name\">").append("\n");
        s.append("                    ZAY WiFi").append("\n");
        s.append("                </div>").append("\n");
        s.append("").append("\n");
        s.append("            </div>").append("\n");
        s.append("").append("\n");
        s.append("            <div class=\"brand-status\">").append("\n");
        s.append("").append("\n");
        s.append("                <span class=\"dot\"></span>").append("\n");
        s.append("").append("\n");
        s.append("                Jaringan tersedia").append("\n");
        s.append("").append("\n");
        s.append("            </div>").append("\n");
        s.append("").append("\n");
        s.append("        </div>").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("        <!-- TITLE -->").append("\n");
        s.append("").append("\n");
        s.append("        <h1>").append("\n");
        s.append("            Masuk ke Wi-Fi").append("\n");
        s.append("        </h1>").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("        <p class=\"subtitle\">").append("\n");
        s.append("            Gunakan kode voucher untuk mendapatkan akses internet.").append("\n");
        s.append("        </p>").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("        <!-- PLATFORM -->").append("\n");
        s.append("").append("\n");
        s.append("        <div class=\"platform\">").append("\n");
        s.append("").append("\n");
        s.append("            <span class=\"platform-dot\"></span>").append("\n");
        s.append("").append("\n");
        s.append("            __PLATFORM_LABEL__").append("\n");
        s.append("").append("\n");
        s.append("            terdeteksi").append("\n");
        s.append("").append("\n");
        s.append("        </div>").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("        {{AUTHORIZED_CONTENT}}").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("        <div class=\"footer\">").append("\n");
        s.append("            ZAY WiFi · Akses internet pribadi").append("\n");
        s.append("        </div>").append("\n");
        s.append("").append("\n");
        s.append("    </main>").append("\n");
        s.append("").append("\n");
        s.append("</div>").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("<script>").append("\n");
        s.append("").append("\n");
        s.append("(function () {").append("\n");
        s.append("").append("\n");
        s.append("    const platform =").append("\n");
        s.append("        __PLATFORM__;").append("\n");
        s.append("").append("\n");
        s.append("    const checkUrls =").append("\n");
        s.append("        <?= $checkUrls ?>;").append("\n");
        s.append("").append("\n");
        s.append("    const form =").append("\n");
        s.append("        document.getElementById('voucherForm');").append("\n");
        s.append("").append("\n");
        s.append("    const input =").append("\n");
        s.append("        document.getElementById('voucher');").append("\n");
        s.append("").append("\n");
        s.append("    const btn =").append("\n");
        s.append("        document.getElementById('connectBtn');").append("\n");
        s.append("").append("\n");
        s.append("    const status =").append("\n");
        s.append("        document.getElementById('status');").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("    if (").append("\n");
        s.append("        !form ||").append("\n");
        s.append("        !input ||").append("\n");
        s.append("        !btn ||").append("\n");
        s.append("        !status").append("\n");
        s.append("    ) {").append("\n");
        s.append("        return;").append("\n");
        s.append("    }").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("    function setStatus(text, type) {").append("\n");
        s.append("").append("\n");
        s.append("        status.textContent = text;").append("\n");
        s.append("").append("\n");
        s.append("        status.className =").append("\n");
        s.append("            'status' +").append("\n");
        s.append("            (type ? ' ' + type : '');").append("\n");
        s.append("").append("\n");
        s.append("    }").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("    function probeAuthorized(url) {").append("\n");
        s.append("").append("\n");
        s.append("        return fetch(url, {").append("\n");
        s.append("").append("\n");
        s.append("            method: 'GET',").append("\n");
        s.append("").append("\n");
        s.append("            cache: 'no-store',").append("\n");
        s.append("").append("\n");
        s.append("            credentials: 'same-origin'").append("\n");
        s.append("").append("\n");
        s.append("        })").append("\n");
        s.append("").append("\n");
        s.append("        .then(function (response) {").append("\n");
        s.append("").append("\n");
        s.append("            if (").append("\n");
        s.append("                url.indexOf('generate_204') !== -1").append("\n");
        s.append("            ) {").append("\n");
        s.append("                return response.status === 204;").append("\n");
        s.append("            }").append("\n");
        s.append("").append("\n");
        s.append("            return response.ok;").append("\n");
        s.append("").append("\n");
        s.append("        })").append("\n");
        s.append("").append("\n");
        s.append("        .catch(function () {").append("\n");
        s.append("").append("\n");
        s.append("            return false;").append("\n");
        s.append("").append("\n");
        s.append("        });").append("\n");
        s.append("").append("\n");
        s.append("    }").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("    function runPlatformChecks() {").append("\n");
        s.append("").append("\n");
        s.append("        return Promise.all(").append("\n");
        s.append("            checkUrls.map(probeAuthorized)").append("\n");
        s.append("        )").append("\n");
        s.append("").append("\n");
        s.append("        .then(function (results) {").append("\n");
        s.append("").append("\n");
        s.append("            return results.some(Boolean);").append("\n");
        s.append("").append("\n");
        s.append("        });").append("\n");
        s.append("").append("\n");
        s.append("    }").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("    function closeCaptivePortal() {").append("\n");
        s.append("").append("\n");
        s.append("        if (").append("\n");
        s.append("            platform === 'ios' ||").append("\n");
        s.append("            platform === 'macos'").append("\n");
        s.append("        ) {").append("\n");
        s.append("").append("\n");
        s.append("            window.location.replace(").append("\n");
        s.append("                '/probe/hotspot-detect.html'").append("\n");
        s.append("            );").append("\n");
        s.append("").append("\n");
        s.append("            return;").append("\n");
        s.append("").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("        if (platform === 'windows') {").append("\n");
        s.append("").append("\n");
        s.append("            window.location.replace(").append("\n");
        s.append("                '/probe/connecttest.txt'").append("\n");
        s.append("            );").append("\n");
        s.append("").append("\n");
        s.append("            return;").append("\n");
        s.append("").append("\n");
        s.append("        }").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("        window.location.replace(").append("\n");
        s.append("            '/probe/generate_204'").append("\n");
        s.append("        );").append("\n");
        s.append("").append("\n");
        s.append("    }").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("    input.addEventListener(").append("\n");
        s.append("        'input',").append("\n");
        s.append("        function () {").append("\n");
        s.append("").append("\n");
        s.append("            input.value =").append("\n");
        s.append("                input.value").append("\n");
        s.append("                    .toUpperCase()").append("\n");
        s.append("                    .replace(/[^A-Z0-9-]/g, '');").append("\n");
        s.append("").append("\n");
        s.append("        }").append("\n");
        s.append("    );").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("    form.addEventListener(").append("\n");
        s.append("        'submit',").append("\n");
        s.append("        function (event) {").append("\n");
        s.append("").append("\n");
        s.append("            event.preventDefault();").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("            const voucher =").append("\n");
        s.append("                input.value.trim();").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("            if (!voucher) {").append("\n");
        s.append("").append("\n");
        s.append("                setStatus(").append("\n");
        s.append("                    'Masukkan kode voucher terlebih dahulu.',").append("\n");
        s.append("                    'error'").append("\n");
        s.append("                );").append("\n");
        s.append("").append("\n");
        s.append("                input.focus();").append("\n");
        s.append("").append("\n");
        s.append("                return;").append("\n");
        s.append("").append("\n");
        s.append("            }").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("            btn.disabled = true;").append("\n");
        s.append("            input.disabled = true;").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("            setStatus(").append("\n");
        s.append("                'Memeriksa voucher...',").append("\n");
        s.append("                ''").append("\n");
        s.append("            );").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("            const body =").append("\n");
        s.append("                new URLSearchParams();").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("            body.set(").append("\n");
        s.append("                'voucher',").append("\n");
        s.append("                voucher").append("\n");
        s.append("            );").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("            fetch('/authorize', {").append("\n");
        s.append("").append("\n");
        s.append("                method: 'POST',").append("\n");
        s.append("").append("\n");
        s.append("                headers: {").append("\n");
        s.append("").append("\n");
        s.append("                    'Accept':").append("\n");
        s.append("                        'application/json',").append("\n");
        s.append("").append("\n");
        s.append("                    'Content-Type':").append("\n");
        s.append("                        'application/x-www-form-urlencoded;charset=UTF-8'").append("\n");
        s.append("").append("\n");
        s.append("                },").append("\n");
        s.append("").append("\n");
        s.append("                body:").append("\n");
        s.append("                    body.toString(),").append("\n");
        s.append("").append("\n");
        s.append("                cache:").append("\n");
        s.append("                    'no-store',").append("\n");
        s.append("").append("\n");
        s.append("                credentials:").append("\n");
        s.append("                    'same-origin'").append("\n");
        s.append("").append("\n");
        s.append("            })").append("\n");
        s.append("").append("\n");
        s.append("            .then(function (response) {").append("\n");
        s.append("").append("\n");
        s.append("                return response.json()").append("\n");
        s.append("").append("\n");
        s.append("                    .then(function (data) {").append("\n");
        s.append("").append("\n");
        s.append("                        if (").append("\n");
        s.append("                            !response.ok ||").append("\n");
        s.append("                            !data.ok").append("\n");
        s.append("                        ) {").append("\n");
        s.append("").append("\n");
        s.append("                            throw new Error(").append("\n");
        s.append("                                data.message ||").append("\n");
        s.append("                                'Voucher tidak valid.'").append("\n");
        s.append("                            );").append("\n");
        s.append("").append("\n");
        s.append("                        }").append("\n");
        s.append("").append("\n");
        s.append("                        return data;").append("\n");
        s.append("").append("\n");
        s.append("                    });").append("\n");
        s.append("").append("\n");
        s.append("            })").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("            .then(function (data) {").append("\n");
        s.append("").append("\n");
        s.append("                setStatus(").append("\n");
        s.append("                    data.message ||").append("\n");
        s.append("                    'Voucher valid. Memverifikasi koneksi...',").append("\n");
        s.append("                    'ok'").append("\n");
        s.append("                );").append("\n");
        s.append("").append("\n");
        s.append("                return runPlatformChecks();").append("\n");
        s.append("").append("\n");
        s.append("            })").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("            .then(function (ok) {").append("\n");
        s.append("").append("\n");
        s.append("                if (!ok) {").append("\n");
        s.append("").append("\n");
        s.append("                    throw new Error(").append("\n");
        s.append("                        'Akses sudah diberikan, tetapi pengecekan jaringan belum selesai.'").append("\n");
        s.append("                    );").append("\n");
        s.append("").append("\n");
        s.append("                }").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("                setStatus(").append("\n");
        s.append("                    'Berhasil terhubung. Membuka internet...',").append("\n");
        s.append("                    'ok'").append("\n");
        s.append("                );").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("                setTimeout(").append("\n");
        s.append("                    closeCaptivePortal,").append("\n");
        s.append("                    350").append("\n");
        s.append("                );").append("\n");
        s.append("").append("\n");
        s.append("            })").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("            .catch(function (error) {").append("\n");
        s.append("").append("\n");
        s.append("                setStatus(").append("\n");
        s.append("                    error.message ||").append("\n");
        s.append("                    'Gagal terhubung. Coba lagi.',").append("\n");
        s.append("                    'error'").append("\n");
        s.append("                );").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("                btn.disabled = false;").append("\n");
        s.append("                input.disabled = false;").append("\n");
        s.append("").append("\n");
        s.append("                input.focus();").append("\n");
        s.append("").append("\n");
        s.append("            });").append("\n");
        s.append("").append("\n");
        s.append("        }").append("\n");
        s.append("    );").append("\n");
        s.append("").append("\n");
        s.append("})();").append("\n");
        s.append("").append("\n");
        s.append("</script>").append("\n");
        s.append("").append("\n");
        s.append("</body>").append("\n");
        s.append("</html>").append("\n");
        return s.toString();
    }

    private String authorizedContent() {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"success\">").append("\n");
        s.append("").append("\n");
        s.append("                <div class=\"success-icon\">").append("\n");
        s.append("                    ✓").append("\n");
        s.append("                </div>").append("\n");
        s.append("").append("\n");
        s.append("                <strong>").append("\n");
        s.append("                    Akses sudah aktif").append("\n");
        s.append("                </strong>").append("\n");
        s.append("").append("\n");
        s.append("                <br>").append("\n");
        s.append("").append("\n");
        s.append("                Perangkat ini sudah terhubung ke internet.").append("\n");
        s.append("").append("\n");
        s.append("            </div>").append("\n");
        return s.toString();
    }

    private String formContent() {
        StringBuilder s = new StringBuilder();
        s.append("<form").append("\n");
        s.append("                class=\"form\"").append("\n");
        s.append("                id=\"voucherForm\"").append("\n");
        s.append("            >").append("\n");
        s.append("").append("\n");
        s.append("                <label for=\"voucher\">").append("\n");
        s.append("                    Masukkan kode di bawah ini").append("\n");
        s.append("                </label>").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("                <div class=\"input-wrap\">").append("\n");
        s.append("").append("\n");
        s.append("                    <input").append("\n");
        s.append("                        class=\"input\"").append("\n");
        s.append("                        id=\"voucher\"").append("\n");
        s.append("                        name=\"voucher\"").append("\n");
        s.append("                        type=\"text\"").append("\n");
        s.append("                        maxlength=\"32\"").append("\n");
        s.append("                        autocomplete=\"off\"").append("\n");
        s.append("                        autocapitalize=\"characters\"").append("\n");
        s.append("                        spellcheck=\"false\"").append("\n");
        s.append("                        placeholder=\"Masukkan kode voucher\"").append("\n");
        s.append("                        required").append("\n");
        s.append("                    >").append("\n");
        s.append("").append("\n");
        s.append("                    <span").append("\n");
        s.append("                        class=\"key-icon\"").append("\n");
        s.append("                        aria-hidden=\"true\"").append("\n");
        s.append("                    >").append("\n");
        s.append("                        ⌕").append("\n");
        s.append("                    </span>").append("\n");
        s.append("").append("\n");
        s.append("                </div>").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("                <button").append("\n");
        s.append("                    class=\"btn\"").append("\n");
        s.append("                    id=\"connectBtn\"").append("\n");
        s.append("                    type=\"submit\"").append("\n");
        s.append("                >").append("\n");
        s.append("                    Hubungkan ke Internet").append("\n");
        s.append("                </button>").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("                <p class=\"hint\">").append("\n");
        s.append("                    Kode voucher hanya dapat digunakan oleh satu perangkat.").append("\n");
        s.append("                </p>").append("\n");
        s.append("").append("\n");
        s.append("").append("\n");
        s.append("                <div").append("\n");
        s.append("                    id=\"status\"").append("\n");
        s.append("                    class=\"status\"").append("\n");
        s.append("                    role=\"status\"").append("\n");
        s.append("                    aria-live=\"polite\"").append("\n");
        s.append("                ></div>").append("\n");
        s.append("").append("\n");
        s.append("            </form>").append("\n");
        return s.toString();
    }

    private String successTemplate() {
        StringBuilder s = new StringBuilder();
        s.append("</title>").append("\n");
        s.append("    <style>").append("\n");
        s.append("        * { box-sizing: border-box; }").append("\n");
        s.append("        body { margin:0; min-height:100vh; padding:20px; display:grid; place-items:center; background:#080b12; color:#f5f7fb; font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif; text-align:center; }").append("\n");
        s.append("        .box { width:100%; max-width:390px; padding:30px 22px 24px; border:1px solid rgba(255,255,255,.085); border-radius:27px; background:#121620; box-shadow:0 28px 80px rgba(0,0,0,.45); }").append("\n");
        s.append("        .icon { width:58px; height:58px; margin:0 auto 17px; display:grid; place-items:center; border-radius:50%; background:rgba(56,217,150,.10); color:#38d996; font-size:28px; }").append("\n");
        s.append("        h1 { margin:0 0 9px; font-size:25px; letter-spacing:-.5px; }").append("\n");
        s.append("        p { margin:0; color:#8d96a8; line-height:1.6; font-size:13px; }").append("\n");
        s.append("        .back { display:inline-block; margin-top:20px; color:#a995ff; text-decoration:none; font-size:13px; }").append("\n");
        s.append("        .brand { margin-top:22px; color:#555f70; font-size:10px; letter-spacing:.5px; }").append("\n");
        s.append("    </style>").append("\n");
        s.append("</head>").append("\n");
        s.append("<body>").append("\n");
        s.append("<div class=\"box\">").append("\n");
        s.append("    {{SUCCESS_CONTENT}}").append("\n");
        s.append("    <div class=\"brand\">ZAY WI-FI</div>").append("\n");
        s.append("</div>").append("\n");
        s.append("").append("\n");
        s.append("{{SUCCESS_SCRIPT}}").append("\n");
        s.append("</body>").append("\n");
        s.append("</html>").append("\n");
        return s.toString();
    }

    private String successAuthorizedContent() {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"icon\">✓</div>").append("\n");
        s.append("        <h1>Berhasil terhubung</h1>").append("\n");
        s.append("        <p>Akses internet untuk perangkat ini sudah aktif.</p>").append("\n");
        return s.toString();
    }

    private String successUnauthorizedContent() {
        StringBuilder s = new StringBuilder();
        s.append("<div class=\"icon\" style=\"color:#a995ff;background:rgba(124,92,255,.10)\">⌁</div>").append("\n");
        s.append("        <h1>Belum terhubung</h1>").append("\n");
        s.append("        <p>Masukkan voucher terlebih dahulu untuk mendapatkan akses internet.</p>").append("\n");
        s.append("        <a class=\"back\" href=\"/\">Kembali ke login</a>").append("\n");
        return s.toString();
    }
}
