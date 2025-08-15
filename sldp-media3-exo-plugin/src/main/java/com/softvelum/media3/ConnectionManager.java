package com.softvelum.media3;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.softvelum.sldp.C;
import com.softvelum.sldp.Connection;
import com.softvelum.sldp.PlayRequest;
import com.softvelum.sldp.SldpConnection;
import com.softvelum.sldp.StreamBuffer;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

class ConnectionManager {
    private final Map<Integer, SldpConnection> connectionMap = new ConcurrentHashMap<>();
    private final Queue<SldpConnection> connectionQueue = new ConcurrentLinkedQueue<>();

    private final Selector selector;

    private final Map<Integer, Vector<PlayRequest>> playMap = new ConcurrentHashMap<>();
    private final Map<Integer, Vector<Integer>> cancelMap = new ConcurrentHashMap<>();

    private int connectionId;
    private Thread connectionThread;

    private long lastWaitingConnectsCheckTime;
    private long lastWaitingSendersCheckTime;
    private long lastWaitingCommandsCheckTime;
    private long lastInactivityCheckTime;

    private final StreamBuffer.Factory bufferFactory = new StreamBuffer.Factory() {
        @Override
        @NonNull
        public StreamBuffer createVideoBuffer() {
            return new StreamBuffer(StreamBuffer.Type.VIDEO, 300);
        }

        @Override
        @NonNull
        public StreamBuffer createAudioBuffer() {
            return new StreamBuffer(StreamBuffer.Type.AUDIO, 500);
        }
    };

    ConnectionManager() throws IOException {
        selector = Selector.open();
    }

    private void startConnectionThread() {
        if (connectionThread != null) {
            return;
        }
        resetTimers();
        connectionThread = new Thread() {
            @Override
            public void run() {
                try {
                    while (!isInterrupted()) {
                        selector.select(250);
                        for (SelectionKey selectionKey : selector.selectedKeys()) {
                            if (!selectionKey.isValid()) {
                                continue;
                            }
                            selectionKey.readyOps();
                            SldpConnection connection = (SldpConnection) selectionKey.attachment();
                            if (connection == null) {
                                break;
                            }
                            connection.processEvent(selectionKey);
                        }
                        selector.selectedKeys().clear();
                        final long timeNow = SystemClock.uptimeMillis();
                        processWaitingConnects(timeNow);
                        processWaitingSenders(timeNow);
                        processWaitingCommands(timeNow);
                        processTimeout(timeNow);
                    }
                } catch (IOException ignored) {
                }
            }
        };
        connectionThread.start();
    }

    private void resetTimers() {
        final long timeNow = SystemClock.uptimeMillis();
        lastWaitingConnectsCheckTime = timeNow;
        lastWaitingSendersCheckTime = timeNow;
        lastWaitingCommandsCheckTime = timeNow;
        lastInactivityCheckTime = timeNow;
    }

    private void processWaitingConnects(long timeNow) throws IOException {
        if (timeNow - lastWaitingConnectsCheckTime < 500) {
            return;
        }
        lastWaitingConnectsCheckTime = timeNow;
        while (true) {
            SldpConnection connection = connectionQueue.poll();
            if (connection == null) {
                break;
            }
            connection.connect();
        }
    }

    private void processWaitingSenders(long timeNow) {
        if (timeNow - lastWaitingSendersCheckTime < 200) {
            return;
        }
        lastWaitingSendersCheckTime = timeNow;
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid()) {
                continue;
            }
            SldpConnection connection = (SldpConnection) key.attachment();
            if (connection == null) {
                continue;
            }
            if (connection.getSendBufferRemaining() != 0) {
                continue;
            }
            connection.onSend();
        }
    }

    synchronized private void processWaitingCommands(long timeNow) {
        if (timeNow - lastWaitingCommandsCheckTime < 200) {
            return;
        }
        lastWaitingCommandsCheckTime = timeNow;
        for (int id : playMap.keySet()) {
            Connection connection = connectionMap.get(id);
            if (connection != null) {
                connection.playStreams(playMap.get(id));
            }
        }
        playMap.clear();
        for (int id : cancelMap.keySet()) {
            Connection connection = connectionMap.get(id);
            if (connection != null) {
                connection.cancelStreams(cancelMap.get(id));
            }
        }
        cancelMap.clear();
    }

    private void processTimeout(long timeNow) {
        if ((timeNow - lastInactivityCheckTime) > (2 * 1000)) {
            for (SelectionKey key : selector.keys()) {
                SldpConnection connection = (SldpConnection) key.attachment();
                if (connection == null) {
                    continue;
                }
                connection.verifyInactivity();
            }
            lastInactivityCheckTime = timeNow;
        }
    }

    private void stopConnectionThread() {
        if (connectionThread != null) {
            try {
                connectionThread.interrupt();
                connectionThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                connectionThread = null;
            }
        }
    }

    int createConnection(URI uri,
                         Connection.Listener listener,
                         String userAgent,
                         boolean trustAllCerts) {
        try {
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            String[] splittedPath = uri.toString().split("/");

            if (!isSldp(scheme) || host == null || splittedPath.length < 5) {
                return C.NO_VALUE;
            }

            SldpConnection.Config config = new SldpConnection.Config();
            config.connectionId = ++connectionId;
            config.ssl = isSecure(scheme);
            config.trustAllCerts = trustAllCerts;
            config.host = host;
            config.port = (port == -1) ? findDefaultPort(scheme) : port;
            config.app = splittedPath[3];
            for (int i = 4; i < splittedPath.length - 1; i++) {
                config.app += "/" + splittedPath[i];
            }
            config.stream = splittedPath[splittedPath.length - 1];
            config.userAgent = userAgent;

            SldpConnection connection = new SldpConnection(config, selector, bufferFactory, listener);
            connectionMap.put(connectionId, connection);

            startConnectionThread();
            connectionQueue.add(connection);
            selector.wakeup();
        } catch (IOException e) {
            return C.NO_VALUE;
        }
        return connectionId;
    }

    void releaseConnection(int id) {
        playMap.remove(id);
        cancelMap.remove(id);
        Connection connection = connectionMap.remove(id);
        if (connection != null) {
            connection.release();
        }
        if (connectionMap.isEmpty()) {
            stopConnectionThread();
        }
    }

    synchronized void play(int id, Collection<PlayRequest> request) {
        if (!connectionMap.containsKey(id)) {
            return;
        }
        if (!playMap.containsKey(id)) {
            playMap.put(id, new Vector<>());
        }
        Objects.requireNonNull(playMap.get(id)).addAll(request);
    }

    synchronized void cancel(int id, int streamId) {
        if (!connectionMap.containsKey(id)) {
            return;
        }
        if (!cancelMap.containsKey(id)) {
            cancelMap.put(id, new Vector<>());
        }
        Objects.requireNonNull(cancelMap.get(id)).add(streamId);
    }

    @NonNull
    Collection<StreamBuffer> getStreamInfo(int id) {
        Connection connection = connectionMap.get(id);
        Collection<StreamBuffer> info = null;
        if (connection != null) {
            info = connection.getStreamInfo();
        }
        return Objects.requireNonNullElseGet(info, ArrayList::new);
    }

    private boolean isSldp(String scheme) {
        return "sldp".equalsIgnoreCase(scheme)
                || "sldps".equalsIgnoreCase(scheme)
                || "ws".equalsIgnoreCase(scheme)
                || "wss".equalsIgnoreCase(scheme);
    }

    private boolean isSecure(String scheme) {
        return "sldps".equalsIgnoreCase(scheme)
                || "wss".equalsIgnoreCase(scheme);
    }

    private int findDefaultPort(String scheme) {
        if (isSecure(scheme)) {
            return 443;
        } else {
            return 80;
        }
    }

}
