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
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
    }

    public int port() {
        return port;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean start() {
        if (running) {
            return true;
        }

        lastError = "";

        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(
                new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"),
                    port
                ),
                32
            );

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
                            if (running) {
                                lastError = e.toString();
                            }
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

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    break;
                }

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

                    if (n < 0) {
                        break;
                    }

                    read += n;
                }

                body = new String(buffer, 0, read);
            }

            route(
                method,
                target,
                body,
                socket.getInetAddress().getHostAddress(),
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

            String code = getParam(body, "code");

            if (code == null || code.isEmpty()) {
                send(
                    out,
                    400,
                    "text/plain; charset=utf-8",
                    "Kode voucher kosong"
                );
                return;
            }

            VoucherManager.Voucher voucher =
                vouchers.find(code);

            if (voucher == null) {
                send(
                    out,
                    403,
                    "text/plain; charset=utf-8",
                    "Voucher tidak valid"
                );
                return;
            }

            Root.Result result = Firewall.allow(ip);

            if (result == null || !result.ok) {
                send(
                    out,
                    500,
                    "text/plain; charset=utf-8",
                    "Gagal memberikan akses"
                );
                return;
            }

            vouchers.bind(code, ip);

            appendAuthorized(ip);

            send(
                out,
                200,
                "text/html; charset=utf-8",
                successPage()
            );

            return;
        }

        if (path.startsWith("/probe/")) {
            send(
                out,
                204,
                "text/plain; charset=utf-8",
                ""
            );
            return;
        }

        if ("/success.html".equals(path)) {
            send(
                out,
                200,
                "text/html; charset=utf-8",
                successPage()
            );
            return;
        }

        send(
            out,
            200,
            "text/html; charset=utf-8",
            indexPage()
        );
    }

    private String getParam(String body, String name) {
        if (body == null) {
            return "";
        }

        String[] parts = body.split("&");

        for (String part : parts) {
            String[] p = part.split("=", 2);

            if (p.length != 2) {
                continue;
            }

            if (name.equals(p[0])) {
                return urlDecode(p[1]);
            }
        }

        return "";
    }

    private String urlDecode(String value) {
        try {
            return URLDecoder.decode(
                value,
                "UTF-8"
            );
        } catch (Exception e) {
            return value;
        }
    }

    private void appendAuthorized(String ip) {
        File file = new File(
            dataDir,
            "authorized_ips.txt"
        );

        try {
            FileWriter writer = new FileWriter(
                file,
                true
            );

            writer.write(ip);
            writer.write("\n");
            writer.close();

        } catch (IOException ignored) {
        }
    }

    private boolean authorized(String ip) {
        File file = new File(
            dataDir,
            "authorized_ips.txt"
        );

        if (!file.exists()) {
            return false;
        }

        try {
            BufferedReader reader =
                new BufferedReader(
                    new FileReader(file)
                );

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

    private String load(String name) {
        File file = new File(dataDir, name);

        if (!file.exists()) {
            return "";
        }

        ByteArrayOutputStream buffer =
            new ByteArrayOutputStream();

        try {
            FileInputStream input =
                new FileInputStream(file);

            byte[] data = new byte[4096];

            int n;

            while ((n = input.read(data)) != -1) {
                buffer.write(data, 0, n);
            }

            input.close();

            return new String(
                buffer.toByteArray(),
                StandardCharsets.UTF_8
            );

        } catch (IOException e) {
            return "";
        }
    }

    private String indexPage() {
        return "<!doctype html>" +
            "<html><head>" +
            "<meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>ZAY WiFi</title>" +
            "<style>" +
            "body{font-family:sans-serif;background:#111;color:#fff;padding:30px}" +
            "input,button{width:100%;padding:14px;margin-top:10px;box-sizing:border-box}" +
            "button{background:#1683ff;color:#fff;border:0;border-radius:8px}" +
            "</style></head><body>" +
            "<h1>ZAY WiFi</h1>" +
            "<p>Silakan masukkan kode voucher</p>" +
            "<form method=\"post\" action=\"/authorize\">" +
            "<input name=\"code\" placeholder=\"Kode Voucher\" autocomplete=\"off\">" +
            "<button type=\"submit\">Login</button>" +
            "</form>" +
            "</body></html>";
    }

    private String successPage() {
        return "<!doctype html>" +
            "<html><head>" +
            "<meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>ZAY WiFi</title>" +
            "<style>" +
            "body{font-family:sans-serif;background:#111;color:#fff;text-align:center;padding:50px}" +
            "a{display:block;background:#1683ff;color:#fff;padding:14px;border-radius:8px;text-decoration:none;margin-top:20px}" +
            "</style></head><body>" +
            "<h1>Voucher Valid!</h1>" +
            "<p>Selamat, akses internet Anda sudah aktif.</p>" +
            "<a href=\"/success.html\">Lanjutkan</a>" +
            "</body></html>";
    }

    private void send(
        OutputStream out,
        int status,
        String type,
        String body
    ) throws IOException {

        byte[] data = body.getBytes(
            StandardCharsets.UTF_8
        );

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

        out.write(
            response.getBytes(
                StandardCharsets.UTF_8
            )
        );

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
        if (c == null) {
            return;
        }

        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    private void close(ServerSocket s) {
        if (s == null) {
            return;
        }

        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
