package com.example.smarthomesecurity;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-performance, zero-dependency embedded MJPEG streaming server.
 * Streams real-time CCTV camera feed to the HomeSentinel Owner App on port 8080.
 * Also receives remote alarm trigger / silence requests.
 */
public class MjpegHttpServer {
    private static final String TAG = "MjpegHttpServer";
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private final List<OutputStream> clientStreams = new CopyOnWriteArrayList<>();
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool();
    private final SirenController sirenController;
    private long lastFrameTime = 0;

    public interface SirenController {
        void triggerSiren();
        void silenceSiren();
    }

    public MjpegHttpServer(int port, SirenController sirenController) {
        this.port = port;
        this.sirenController = sirenController;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                Log.i(TAG, "MJPEG Server running on port " + port);
                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        clientExecutor.execute(() -> handleClient(socket));
                    } catch (IOException e) {
                        if (!isRunning) break;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not bind server socket on port " + port, e);
            }
        }).start();
    }

    private void handleClient(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line = reader.readLine();
            if (line == null) {
                socket.close();
                return;
            }

            Log.i(TAG, "HTTP Request: " + line);

            // Handle Siren control endpoints from Owner app
            if (line.contains("/siren/trigger")) {
                if (sirenController != null) sirenController.triggerSiren();
                sendSimpleResponse(socket, "Siren Triggered");
                return;
            } else if (line.contains("/siren/silence")) {
                if (sirenController != null) sirenController.silenceSiren();
                sendSimpleResponse(socket, "Siren Silenced");
                return;
            }

            // Stream endpoint: /stream or default
            OutputStream out = socket.getOutputStream();
            String header = "HTTP/1.1 200 OK\r\n"
                    + "Server: HomeSentinel-Monitor\r\n"
                    + "Connection: close\r\n"
                    + "Max-Age: 0\r\n"
                    + "Expires: 0\r\n"
                    + "Cache-Control: no-cache, private\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n\r\n";
            out.write(header.getBytes("UTF-8"));
            out.flush();

            clientStreams.add(out);
            Log.i(TAG, "New MJPEG viewer connected. Active viewers: " + clientStreams.size());

            // Keep connection alive until client disconnects
            while (isRunning && !socket.isClosed() && socket.isConnected()) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            // Client disconnected or read timeout
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    private void sendSimpleResponse(Socket socket, String message) throws IOException {
        OutputStream out = socket.getOutputStream();
        String response = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + message.length() + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n"
                + message;
        out.write(response.getBytes("UTF-8"));
        out.flush();
        socket.close();
    }

    public void broadcastFrame(Bitmap bitmap) {
        if (clientStreams.isEmpty() || bitmap == null || bitmap.isRecycled()) return;

        // Throttle to 20 FPS (50ms) to ensure smooth streaming without network congestion
        long now = System.currentTimeMillis();
        if (now - lastFrameTime < 50) return;
        lastFrameTime = now;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)) return;
        byte[] jpegBytes = baos.toByteArray();

        String frameHeader = "--frame\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + jpegBytes.length + "\r\n\r\n";
        byte[] headerBytes = frameHeader.getBytes();
        byte[] tailBytes = "\r\n".getBytes();

        for (OutputStream out : clientStreams) {
            try {
                out.write(headerBytes);
                out.write(jpegBytes);
                out.write(tailBytes);
                out.flush();
            } catch (Exception e) {
                // Viewer disconnected
                clientStreams.remove(out);
                Log.i(TAG, "Viewer disconnected. Active viewers: " + clientStreams.size());
            }
        }
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        clientStreams.clear();
        clientExecutor.shutdownNow();
    }

    public static String getDeviceIp() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error getting IP", ex);
        }
        return "127.0.0.1";
    }
}
