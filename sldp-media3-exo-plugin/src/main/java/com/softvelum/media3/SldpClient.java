package com.softvelum.media3;

import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

import com.softvelum.sldp.BufferItem;
import com.softvelum.sldp.C;
import com.softvelum.sldp.Connection;
import com.softvelum.sldp.PlayRequest;
import com.softvelum.sldp.StreamBuffer;
import com.softvelum.sldp.Timestamp;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SldpClient implements Connection.Listener {

    public static final int NO_ID = C.NO_VALUE;

    public static class StreamInfo {
        public int id;
        public String mimeType;
        public int bandwidth;
        public int width;
        public int height;
    }

    public interface Callback {
        void onPrepared(@NonNull Collection<StreamInfo> streams);

        void onDisconnected(int connectionId);
    }

    private final Map<Integer, URI> connections = new ConcurrentHashMap<>();
    private final Map<Integer, StreamBuffer> streamBuffers = new ConcurrentHashMap<>();
    private final Map<Integer, Long> messageIndex = new ConcurrentHashMap<>();

    private Handler handler;
    private final ConnectionManager connectionManager;

    private final Callback callback;

    public SldpClient(Callback callback) throws IOException {
        this.callback = callback;
        handler = new Handler(Looper.getMainLooper());
        connectionManager = new ConnectionManager();
    }

    public void release() {
        for (Integer id : connections.keySet()) {
            connectionManager.releaseConnection(id);
        }
        connections.clear();
        streamBuffers.clear();

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
    }

    public int connect(Uri uri, String userAgent, boolean trustAllCerts) {
        if (handler == null || !connections.isEmpty()) {
            return C.NO_VALUE;
        }
        int id;
        try {
            URI javaUri = URI.create(uri.toString());
            id = connectionManager.createConnection(javaUri, this, userAgent, trustAllCerts);
            if (id != C.NO_VALUE) {
                connections.put(id, javaUri);
            }
        } catch (IllegalArgumentException e) {
            id = C.NO_VALUE;
        }
        return id;
    }

    public void releaseConnection(int id) {
        if (handler == null) {
            return;
        }
        connectionManager.releaseConnection(id);
        connections.remove(id);
    }

    @Override
    public Handler getHandler() {
        return handler;
    }

    @UnstableApi
    @Override
    public void onStreamInfoReceived(int id) {
        if (!connections.containsKey(id) || callback == null) {
            return;
        }
        Set<StreamInfo> streamInfo = new HashSet<>();
        for (StreamBuffer buffer : connectionManager.getStreamInfo(id)) {
            StreamInfo stream = new StreamInfo();
            stream.id = buffer.getStreamId();
            stream.mimeType = getExoMimeType(buffer.getMimeType());
            stream.bandwidth = buffer.getBandwidth();
            stream.width = buffer.getSize().getWidth();
            stream.height = buffer.getSize().getHeight();
            streamInfo.add(stream);
            streamBuffers.put(buffer.getStreamId(), buffer);
            messageIndex.put(buffer.getStreamId(), 0L);
        }
        callback.onPrepared(streamInfo);
    }

    @Override
    public void onStateChanged(int connectionId,
                               Connection.State state,
                               Connection.Status status,
                               JSONObject info) {
        if (state == Connection.State.DISCONNECTED && callback != null) {
            callback.onDisconnected(connectionId);
        }
    }

    public void play(int connectionId, int streamId, int offset) {
        Set<PlayRequest> request = new HashSet<>();
        request.add(new PlayRequest(streamId, offset, 0));
        connectionManager.play(connectionId, request);
        messageIndex.put(streamId, 0L);
    }

    public void cancel(int connectionId, int streamId) {
        connectionManager.cancel(connectionId, streamId);
        messageIndex.put(streamId, 0L);
    }

    @Nullable
    public byte[] readExtradata(int streamId) {
        StreamBuffer buffer = streamBuffers.get(streamId);
        if (buffer == null || buffer.getExtradata() == null) {
            return null;
        }
        byte[] extradata = buffer.getExtradata();
        byte[] ret = new byte[extradata.length];
        System.arraycopy(extradata, 0, ret, 0, extradata.length);
        return ret;
    }

    @Nullable
    public BufferItem read(int streamId) {
        StreamBuffer buffer = streamBuffers.get(streamId);
        Long index = messageIndex.get(streamId);
        if (buffer == null || !buffer.isInitialized() || index == null) {
            return null;
        }
        BufferItem currentItem = buffer.getItem(index);
        if (currentItem == null) {
            return null;
        }
        messageIndex.put(streamId, index + 1);
        return currentItem;
    }

    @Nullable
    public Timestamp getStartTimestamp(int streamId) {
        StreamBuffer buffer = streamBuffers.get(streamId);
        if (buffer == null || !buffer.isInitialized()) {
            return null;
        }
        return buffer.getStartTimestamp();
    }

    @UnstableApi
    private String getExoMimeType(String mimeType) {
        String exoMimeType;
        switch (mimeType) {
            case MediaFormat.MIMETYPE_AUDIO_AAC:
                exoMimeType = MimeTypes.AUDIO_AAC;
                break;
            case MediaFormat.MIMETYPE_AUDIO_MPEG:
                exoMimeType = MimeTypes.AUDIO_MPEG;
                break;
            case MediaFormat.MIMETYPE_AUDIO_OPUS:
                exoMimeType = MimeTypes.AUDIO_OPUS;
                break;
            case MediaFormat.MIMETYPE_VIDEO_AVC:
                exoMimeType = MimeTypes.VIDEO_H264;
                break;
            case MediaFormat.MIMETYPE_VIDEO_HEVC:
                exoMimeType = MimeTypes.VIDEO_H265;
                break;
            case MediaFormat.MIMETYPE_VIDEO_VP8:
                exoMimeType = MimeTypes.VIDEO_VP8;
                break;
            case MediaFormat.MIMETYPE_VIDEO_VP9:
                exoMimeType = MimeTypes.VIDEO_VP9;
                break;
            case MediaFormat.MIMETYPE_VIDEO_AV1:
                exoMimeType = MimeTypes.VIDEO_AV1;
                break;
            default:
                exoMimeType = mimeType;
                break;
        }
        return exoMimeType;
    }

}
