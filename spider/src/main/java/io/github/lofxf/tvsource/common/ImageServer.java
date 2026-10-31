package io.github.lofxf.tvsource.common;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny loopback HTTP server that serves in-memory images (login QR codes), so they can be shown
 * as a normal poster URL by any TVBox-style host without relying on host-specific proxy APIs.
 */
public final class ImageServer {

    private static ServerSocket server;
    private static final Map<String, byte[]> IMAGES = new ConcurrentHashMap<String, byte[]>();

    private ImageServer() {}

    /** Publishes {@code png} and returns a http://127.0.0.1 URL for it. */
    public static synchronized String publish(String name, byte[] png) throws Exception {
        ensureStarted();
        String key = name + "_" + System.currentTimeMillis() + ".png";
        IMAGES.clear();
        IMAGES.put(key, png);
        return "http://127.0.0.1:" + server.getLocalPort() + "/" + key;
    }

    private static void ensureStarted() throws Exception {
        if (server != null && !server.isClosed()) return;
        server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        final ServerSocket s = server;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!s.isClosed()) {
                    try {
                        handle(s.accept());
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "tvsource-image-server");
        t.setDaemon(true);
        t.start();
    }

    private static void handle(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String line = r.readLine();
            String path = "";
            if (line != null) {
                String[] parts = line.split(" ");
                if (parts.length > 1) path = parts[1];
            }
            while ((line = r.readLine()) != null && line.length() > 0) { /* skip headers */ }
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.startsWith("/")) path = path.substring(1);
            byte[] img = IMAGES.get(path);
            OutputStream os = socket.getOutputStream();
            if (img == null) {
                os.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
            } else {
                os.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nCache-Control: no-cache\r\nContent-Length: "
                        + img.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                os.write(img);
            }
            os.flush();
        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }
}
