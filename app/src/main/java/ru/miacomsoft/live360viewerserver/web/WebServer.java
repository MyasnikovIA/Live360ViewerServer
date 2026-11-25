package ru.miacomsoft.live360viewerserver.web;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP/1.1-сервер с SSE, JSON-командами и отдачей статики из assets/.
 *
 *  GET  /                → assets/index.html
 *  GET  /<file>          → любой файл из assets/
 *  GET  /api/status      → JSON статуса
 *  GET  /api/files       → JSON со списком файлов в корне assets/
 *  POST /api/command     → {cmd, payload} → результат JSON
 *  GET  /api/events      → SSE-поток (text/event-stream)
 *
 * start() возвращает false, если порт занять не удалось — тогда сервер
 * не стартует, и вызывающий код может откатиться на старый порт.
 */
public class WebServer {

    private static final String TAG = "WebServer";

    public interface CommandHandler {
        JSONObject handleCommand(String cmd, JSONObject payload) throws Exception;
        JSONObject getStatus();
    }

    private final Context context;
    private final int port;
    private final CommandHandler handler;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final List<Client> sseClients = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    public WebServer(Context ctx, int port, CommandHandler handler) {
        this.context = ctx.getApplicationContext();
        this.port = port;
        this.handler = handler;
    }

    public int getPort() { return port; }
    public boolean isRunning() { return running; }

    // ============ Lifecycle ============

    /** ★ Биндит сокет синхронно: если порт занят — false. */
    public synchronized boolean start() {
        if (running) return true;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            Log.e(TAG, "Failed to bind port " + port + ": " + e.getMessage());
            try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
            return false;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "WebServer-accept-" + port);
        acceptThread.start();
        Log.i(TAG, "started on port " + port);
        return true;
    }

    public synchronized void stop() {
        if (!running && serverSocket == null) return;
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
        for (Client c : sseClients) c.close();
        sseClients.clear();
        workers.shutdownNow();
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null) {
            try { t.join(2000); } catch (InterruptedException ignored) {}
        }
        Log.i(TAG, "stopped (port " + port + ")");
    }

    /** Разослать событие всем SSE-клиентам. */
    public void broadcast(String event, JSONObject data) {
        if (sseClients.isEmpty()) return;
        List<Client> dead = new ArrayList<>();
        for (Client c : sseClients) {
            if (!c.send(event, data)) dead.add(c);
        }
        sseClients.removeAll(dead);
    }

    // ============ Accept loop ============

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                workers.execute(() -> handleConnection(s));
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept: " + e.getMessage());
            }
        }
    }

    // ============ Per-connection ============

    private void handleConnection(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();

            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) { socket.close(); return; }

            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) { socket.close(); return; }
            String method = parts[0];
            String path = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(Locale.ROOT),
                            line.substring(idx + 1).trim());
                }
            }

            String pathOnly = path;
            int q = path.indexOf('?');
            if (q >= 0) pathOnly = path.substring(0, q);

            if ("OPTIONS".equals(method)) { writeCors(out); return; }
            if (pathOnly.equals("/api/events")) { handleSse(socket, out); return; }
            if (pathOnly.startsWith("/api/"))  { handleApi(method, pathOnly, in, headers, out); return; }
            handleStatic(pathOnly, out);

        } catch (Exception e) {
            Log.w(TAG, "handleConnection: " + e.getMessage());
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // ============ SSE ============

    private void handleSse(Socket socket, OutputStream out) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache, no-transform\r\n"
                + "Connection: keep-alive\r\n"
                + "X-Accel-Buffering: no\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.flush();

        Client client = new Client(socket, out);
        sseClients.add(client);

        try {
            client.send("status", handler.getStatus());
        } catch (Exception e) {
            Log.w(TAG, "SSE initial status: " + e.getMessage());
        }

        try {
            while (running && !socket.isClosed()) {
                Thread.sleep(20000);
                JSONObject ping = new JSONObject();
                try { ping.put("t", System.currentTimeMillis()); } catch (Exception ignored) {}
                if (!client.send("ping", ping)) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sseClients.remove(client);
            client.close();
        }
    }

    // ============ API ============

    private void handleApi(String method, String path, BufferedReader in,
                           Map<String, String> headers, OutputStream out) throws IOException {
        if (path.equals("/api/status") && "GET".equals(method)) {
            try { writeJson(out, 200, handler.getStatus()); }
            catch (Exception e) { writeJson(out, 500, err(e)); }
            return;
        }

        if (path.equals("/api/files") && "GET".equals(method)) {
            try {
                String[] files = context.getAssets().list("");
                JSONArray arr = new JSONArray();
                if (files != null) {
                    for (String f : files) arr.put(f);
                }
                JSONObject r = new JSONObject();
                r.put("files", arr);
                writeJson(out, 200, r);
            } catch (Exception e) {
                writeJson(out, 500, err(e));
            }
            return;
        }

        if (path.equals("/api/command") && "POST".equals(method)) {
            int len = 0;
            try { len = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
            catch (NumberFormatException ignored) {}
            if (len <= 0) { writeJson(out, 400, err("empty body")); return; }

            char[] body = new char[len];
            int read = 0;
            while (read < len) {
                int r = in.read(body, read, len - read);
                if (r < 0) break;
                read += r;
            }
            try {
                JSONObject req = new JSONObject(new String(body));
                String cmd = req.optString("cmd", "");
                if (cmd.isEmpty()) { writeJson(out, 400, err("missing cmd")); return; }
                JSONObject payload = req.optJSONObject("payload");
                if (payload == null) payload = new JSONObject();

                JSONObject result = handler.handleCommand(cmd, payload);
                if (result == null) result = new JSONObject();
                if (!result.has("ok")) result.put("ok", true);
                result.put("cmd", cmd);

                // Внимание: при set_web_port broadcast может уйти в /dev/null
                // (старый сервер останавливается) — это нормально.
                try { broadcast("command", result); } catch (Exception ignored) {}

                writeJson(out, 200, result);
            } catch (Exception e) {
                writeJson(out, 400, err(e));
            }
            return;
        }

        writeJson(out, 404, err("not found: " + path));
    }

    // ============ Static ============

    private void handleStatic(String path, OutputStream out) throws IOException {
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        String assetPath = path.startsWith("/") ? path.substring(1) : path;
        if (assetPath.contains("..")) { writeText(out, 400, "bad path"); return; }

        try {
            InputStream is = context.getAssets().open(assetPath);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
            is.close();
            writeResponse(out, 200, guessMime(assetPath), buf.toByteArray());
        } catch (IOException e) {
            writeText(out, 404, "Not found: " + path);
        }
    }

    private static String guessMime(String p) {
        String l = p.toLowerCase(Locale.ROOT);
        if (l.endsWith(".html") || l.endsWith(".htm")) return "text/html; charset=utf-8";
        if (l.endsWith(".css"))  return "text/css; charset=utf-8";
        if (l.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (l.endsWith(".json")) return "application/json; charset=utf-8";
        if (l.endsWith(".png"))  return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".svg"))  return "image/svg+xml";
        if (l.endsWith(".ico"))  return "image/x-icon";
        return "application/octet-stream";
    }

    // ============ Write helpers ============

    private void writeResponse(OutputStream out, int code, String mime, byte[] body) throws IOException {
        String headers = "HTTP/1.1 " + code + " " + statusText(code) + "\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Cache-Control: no-cache\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void writeJson(OutputStream out, int code, JSONObject obj) throws IOException {
        writeResponse(out, code, "application/json; charset=utf-8",
                obj.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeText(OutputStream out, int code, String text) throws IOException {
        writeResponse(out, code, "text/plain; charset=utf-8",
                text.getBytes(StandardCharsets.UTF_8));
    }

    private void writeCors(OutputStream out) throws IOException {
        String headers = "HTTP/1.1 204 No Content\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Content-Length: 0\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            default:  return "OK";
        }
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false); o.put("error", msg); } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject err(Exception e) {
        return err(e.getMessage() == null ? e.toString() : e.getMessage());
    }

    // ============ SSE client ============

    private static class Client {
        final Socket socket;
        final OutputStream out;
        Client(Socket s, OutputStream o) { socket = s; out = o; }

        synchronized boolean send(String event, JSONObject data) {
            try {
                String msg = "event: " + event + "\n"
                        + "data: " + data.toString() + "\n\n";
                out.write(msg.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        void close() {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
}