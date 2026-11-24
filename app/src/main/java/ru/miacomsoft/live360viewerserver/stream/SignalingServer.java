package ru.miacomsoft.live360viewerserver.stream;

import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;

public class SignalingServer extends WebSocketServer {
    private static final String TAG = "SignalingServer";
    private final int port;

    public interface Listener {
        void onClientConnected(WebSocket conn);
        void onMessage(WebSocket conn, String message);
        void onClientDisconnected(WebSocket conn);
    }

    private final Listener listener;

    public SignalingServer(int port, Listener listener) {
        super(new InetSocketAddress(port));
        this.port = port;
        this.listener = listener;
        setReuseAddr(true); // ★ КРИТИЧЕСКИ ВАЖНО: Освобождает порт при крашах
    }

    public int getPort() { return port; }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Log.i(TAG, "WebRTC client connected: " + conn.getRemoteSocketAddress());
        if (listener != null) listener.onClientConnected(conn);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (listener != null) listener.onMessage(conn, message);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Log.i(TAG, "WebRTC client disconnected");
        if (listener != null) listener.onClientDisconnected(conn);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        Log.e(TAG, "WebSocket error", ex);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "WebSocket server started on port " + port);
    }
}