package ru.miacomsoft.live360viewerserver.web;

import android.content.Context;
import android.util.Log;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public class WebServerManager {
    private static final String TAG = "WebServerManager";

    public interface CommandHandler {
        JSONObject handleCommand(String cmd, JSONObject payload) throws Exception;
        JSONObject getStatus();
    }

    private final Context context;
    private final int port;
    private final CommandHandler handler;
    private HttpServer server;
    private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();

    public WebServerManager(Context ctx, int port, CommandHandler handler) {
        this.context = ctx.getApplicationContext();
        this.port = port;
        this.handler = handler;
    }

    public boolean start() {
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.setExecutor(Executors.newCachedThreadPool());

            server.createContext("/api/status", this::handleStatus);
            server.createContext("/api/files", this::handleFiles);
            server.createContext("/api/command", this::handleCommand);
            server.createContext("/api/events", this::handleEvents);
            server.createContext("/", this::handleStatic);

            server.start();
            Log.i(TAG, "Web server started on port " + port);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start web server on port " + port, e);
            return false;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        for (SseClient c : sseClients) {
            c.close();
        }
        sseClients.clear();
        Log.i(TAG, "Web server stopped");
    }

    public void broadcast(String event, JSONObject data) {
        if (sseClients.isEmpty()) return;
        List<SseClient> dead = new ArrayList<>();
        for (SseClient c : sseClients) {
            if (!c.send(event, data)) dead.add(c);
        }
        sseClients.removeAll(dead);
    }

    // ============ API Handlers ============

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try {
            JSONObject s = handler.getStatus();
            sendJson(exchange, 200, s);
        } catch (Exception e) {
            sendJson(exchange, 500, error(e.getMessage()));
        }
    }

    private void handleFiles(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try {
            String[] files = context.getAssets().list("");
            JSONArray arr = new JSONArray();
            if (files != null) for (String f : files) arr.put(f);
            JSONObject r = new JSONObject();
            r.put("files", arr);
            sendJson(exchange, 200, r);
        } catch (Exception e) {
            sendJson(exchange, 500, error(e.getMessage()));
        }
    }

    private void handleCommand(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = exchange.getRequestBody()) {
                byte[] tmp = new byte[4096];
                int n;
                while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            }
            JSONObject req = new JSONObject(buf.toString("UTF-8"));
            String cmd = req.optString("cmd", "");
            if (cmd.isEmpty()) {
                sendJson(exchange, 400, error("missing cmd"));
                return;
            }
            JSONObject payload = req.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();

            JSONObject result = handler.handleCommand(cmd, payload);
            if (result == null) result = new JSONObject();
            if (!result.has("ok")) result.put("ok", true);
            result.put("cmd", cmd);

            broadcast("command", result);
            sendJson(exchange, 200, result);
        } catch (Exception e) {
            sendJson(exchange, 400, error(e.getMessage() != null ? e.getMessage() : "Bad request"));
        }
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache, no-transform");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("X-Accel-Buffering", "no");

        // Длина 0 включает Chunked Transfer Encoding в вашем HttpExchange
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();

        SseClient client = new SseClient(out);
        sseClients.add(client);

        try {
            // Отправляем начальный статус
            client.send("status", handler.getStatus());

            // Keep-alive пинг каждые 20 секунд, чтобы прокси/браузер не закрыли соединение
            while (true) {
                Thread.sleep(20000);
                JSONObject ping = new JSONObject();
                ping.put("t", System.currentTimeMillis());
                if (!client.send("ping", ping)) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w(TAG, "SSE error: " + e.getMessage());
        } finally {
            sseClients.remove(client);
            exchange.close();
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        String assetPath = path.startsWith("/") ? path.substring(1) : path;

        if (assetPath.contains("..")) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        try {
            InputStream is = context.getAssets().open(assetPath);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = is.read(tmp)) > 0) buf.write(tmp, 0, n);
            is.close();
            byte[] data = buf.toByteArray();

            exchange.getResponseHeaders().add("Content-Type", guessMime(assetPath));
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, data.length);

            OutputStream os = exchange.getResponseBody();
            os.write(data);
            os.close();
        } catch (IOException e) {
            exchange.sendResponseHeaders(404, -1);
        }
    }

    // ============ Helpers ============

    private void sendJson(HttpExchange exchange, int code, JSONObject obj) throws IOException {
        byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private JSONObject error(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", msg != null ? msg : "unknown");
        } catch (Exception ignored) {}
        return o;
    }

    private String guessMime(String p) {
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

    // ============ SSE Client ============

    private static class SseClient {
        private final OutputStream out;

        SseClient(OutputStream out) {
            this.out = out;
        }

        synchronized boolean send(String event, JSONObject data) {
            try {
                String msg = "event: " + event + "\n" + "data: " + data.toString() + "\n\n";
                out.write(msg.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        void close() {
            try { out.close(); } catch (IOException ignored) {}
        }
    }
}