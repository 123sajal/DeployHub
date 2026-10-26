package com.deployhub.platform.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tiny TCP proxy for outage tests: the app connects to the proxy, the proxy forwards to the
 * real database. {@link #cut()} drops every open connection and refuses new ones (the network to
 * the database is gone); {@link #restore()} accepts connections again. The database itself is
 * never touched.
 */
public final class OutageProxy implements AutoCloseable {

    private final String targetHost;
    private final int targetPort;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile ServerSocket listener;
    private volatile int port;

    public OutageProxy(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        listen(0);
    }

    public int port() {
        return port;
    }

    public synchronized void cut() {
        closeQuietly(listener);
        sockets.forEach(OutageProxy::closeQuietly);
        sockets.clear();
    }

    public synchronized void restore() throws IOException {
        if (listener.isClosed()) {
            listen(port);
        }
    }

    @Override
    public void close() {
        cut();
    }

    private void listen(int requestedPort) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress("127.0.0.1", requestedPort));
        listener = socket;
        port = socket.getLocalPort();
        Thread acceptor = new Thread(() -> acceptLoop(socket), "outage-proxy-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                Socket upstream = new Socket(targetHost, targetPort);
                sockets.add(client);
                sockets.add(upstream);
                pump(client, upstream);
                pump(upstream, client);
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void pump(Socket from, Socket to) {
        Thread thread = new Thread(() -> {
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // connection cut
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }, "outage-proxy-pump");
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
            // already closed
        }
    }
}
