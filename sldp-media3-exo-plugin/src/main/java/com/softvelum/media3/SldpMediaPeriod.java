package com.softvelum.media3;

import static com.softvelum.media3.SldpClient.NO_ID;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.StreamKey;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.source.CompositeSequenceableLoaderFactory;
import androidx.media3.exoplayer.source.DefaultCompositeSequenceableLoaderFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.SequenceableLoader;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.extractor.AacUtil;
import androidx.media3.extractor.AvcConfig;
import androidx.media3.extractor.HevcConfig;

import com.google.common.collect.ImmutableList;
import com.softvelum.sldp.BufferItem;
import com.softvelum.sldp.Timestamp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@UnstableApi
public class SldpMediaPeriod implements MediaPeriod, SldpClient.Callback {
    private static final String TAG = "SldpMediaPeriod";

    private static final String TRACK_GROUP_AUDIO = "audio";
    private static final String TRACK_GROUP_VIDEO = "video";

    private final Uri uri;
    private final Allocator allocator;

    private SldpClient sldpClient;
    private int connectionId = NO_ID;

    private MediaPeriod.Callback mediaPeriodCallback;
    private boolean preparationError;

    private final List<TrackGroup> trackGroups = new ArrayList<>();
    private final Map<String, SldpSampleStream> sampleStreams = new HashMap<>();

    private final CompositeSequenceableLoaderFactory compositeSequenceableLoaderFactory;
    private SequenceableLoader compositeSequenceableLoader;

    private final String userAgent;
    private final int offset;
    private final boolean trustAllCerts;

    SldpMediaPeriod(Uri uri,
                    Allocator allocator,
                    String userAgent,
                    int offset,
                    boolean trustAllCerts) {
        this.uri = uri;
        this.allocator = allocator;
        this.userAgent = userAgent;
        this.offset = offset;
        this.trustAllCerts = trustAllCerts;
        this.compositeSequenceableLoaderFactory = new DefaultCompositeSequenceableLoaderFactory();
        this.compositeSequenceableLoader = compositeSequenceableLoaderFactory.empty();
        try {
            this.sldpClient = new SldpClient(this);
        } catch (IOException ioe) {
            this.sldpClient = null;
        }
    }

    void release() {
        if (sldpClient != null) {
            sldpClient.release();
            sldpClient = null;
        }
        for (SldpSampleStream stream : sampleStreams.values()) {
            stream.release();
        }
    }

    @Override
    public void prepare(@NonNull Callback callback, long positionUs) {
        mediaPeriodCallback = callback;

        if (sldpClient == null) {
            preparationError = true;
            return;
        }

        connectionId = sldpClient.connect(uri, userAgent, trustAllCerts);
        if (connectionId == NO_ID) {
            preparationError = true;
        }
    }

    @Override
    public void onPrepared(@NonNull Collection<SldpClient.StreamInfo> streams) {

        int audioIdx = 0;
        LinkedHashMap<Integer, Integer> audioIds = new LinkedHashMap<>();
        LinkedHashMap<Integer, Format> audioFormats = new LinkedHashMap<>();

        int videoIdx = 0;
        Map<Integer, Integer> videoIds = new LinkedHashMap<>();
        Map<Integer, Format> videoFormats = new LinkedHashMap<>();

        for (SldpClient.StreamInfo s : streams) {
            Log.d(TAG, "manifest -> " + s.mimeType);
            Format format;
            switch (s.mimeType) {
                case MimeTypes.AUDIO_AAC:
                case MimeTypes.AUDIO_MPEG:
                case MimeTypes.AUDIO_OPUS:
                    format = new Format.Builder()
                            .setSampleMimeType(s.mimeType)
                            .build();
                    audioIds.put(audioIdx, s.id);
                    audioFormats.put(audioIdx, format);
                    audioIdx++;
                    break;

                case MimeTypes.VIDEO_H264:
                case MimeTypes.VIDEO_H265:
                case MimeTypes.VIDEO_VP8:
                case MimeTypes.VIDEO_VP9:
                case MimeTypes.VIDEO_AV1:
                    format = new Format.Builder()
                            .setSampleMimeType(s.mimeType)
                            .setHeight(s.height)
                            .setWidth(s.width)
                            .build();
                    videoIds.put(videoIdx, s.id);
                    videoFormats.put(videoIdx, format);
                    videoIdx++;
                    break;

                default:
                    break;
            }
        }

        if (audioIdx > 0) {
            Log.d(TAG, "audioIdx -> " + audioIdx);
            trackGroups.add(new TrackGroup(TRACK_GROUP_AUDIO, audioFormats.values().toArray(new Format[0])));
            sampleStreams.put(TRACK_GROUP_AUDIO, new SldpSampleStream(audioIds, audioFormats, C.TRACK_TYPE_AUDIO));
        }

        if (videoIdx > 0) {
            Log.d(TAG, "videoIdx -> " + videoIdx);
            trackGroups.add(new TrackGroup(TRACK_GROUP_VIDEO, videoFormats.values().toArray(new Format[0])));
            sampleStreams.put(TRACK_GROUP_VIDEO, new SldpSampleStream(videoIds, videoFormats, C.TRACK_TYPE_VIDEO));
        }

        if (mediaPeriodCallback != null) {
            mediaPeriodCallback.onPrepared(this);
        }
    }

    @Override
    public void onDisconnected(int connectionId) {
        if (this.connectionId == connectionId) {
            if (sldpClient != null) {
                sldpClient.releaseConnection(connectionId);
                this.connectionId = NO_ID;
            }
        }
    }

    @Override
    public void maybeThrowPrepareError() throws IOException {
        if (preparationError) {
            throw new IOException();
        }
    }

    @NonNull
    @Override
    public TrackGroupArray getTrackGroups() {
        return new TrackGroupArray(trackGroups.toArray(new TrackGroup[0]));
    }

    @NonNull
    @Override
    public ImmutableList<StreamKey> getStreamKeys(@NonNull List<ExoTrackSelection> trackSelections) {
        return ImmutableList.of();
    }

    @Override
    public long selectTracks(@NonNull @NullableType ExoTrackSelection[] selections,
                             @NonNull boolean[] mayRetainStreamFlags,
                             @NonNull @NullableType SampleStream[] streams,
                             @NonNull boolean[] streamResetFlags,
                             long positionUs) {

        // Deselect old tracks.
        // Input array streams contains the streams selected in the previous track selection.
        for (int i = 0; i < selections.length; i++) {
            if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                streams[i] = null;
            }
        }

        List<SldpSampleStream> loaders = new ArrayList<>();
        List<List<Integer>> loaderTrackTypes = new ArrayList<>();

        // Select new tracks.
        for (int i = 0; i < selections.length; i++) {
            TrackSelection selection = selections[i];
            if (selection == null || selection.length() == 0) {
                continue;
            }

            TrackGroup trackGroup = selection.getTrackGroup();
            for (int j = 0; j < selection.length(); j++) {
                Log.d(TAG, String.format("selection[%d] -> %s/%d", j, trackGroup.id, selection.getIndexInTrackGroup(j)));
            }

            if (!trackGroups.contains(trackGroup)) {
                continue;
            }

            int indexInTrackGroup = selection.getIndexInTrackGroup(0);
            Log.d(TAG, String.format("selected %s -> %s", trackGroup.id, indexInTrackGroup));

            if (streams[i] == null) {
                SldpSampleStream stream = sampleStreams.get(trackGroup.id);
                if (stream != null) {
                    streams[i] = stream;
                    if (!stream.isActive()) {
                        stream.play(indexInTrackGroup);
                        streamResetFlags[i] = true;
                    } else {
                        stream.select(indexInTrackGroup);
                    }
                }
            }

            if (streams[i] instanceof SldpSampleStream) {
                SldpSampleStream stream = (SldpSampleStream) streams[i];
                loaders.add(stream);
                loaderTrackTypes.add(ImmutableList.of(stream.primaryTrackType));
            }
        }

        for (SldpSampleStream stream : sampleStreams.values()) {
            if (!loaders.contains(stream)) {
                stream.cancel();
            }
        }

        compositeSequenceableLoader =
                compositeSequenceableLoaderFactory.create(loaders, loaderTrackTypes);

        return positionUs;
    }

    @Override
    public void discardBuffer(long positionUs, boolean toKeyframe) {
        // Do nothing.
    }

    @Override
    public long readDiscontinuity() {
        return C.TIME_UNSET;
    }

    @Override
    public long seekToUs(long positionUs) {
        return positionUs;
    }

    @Override
    public long getAdjustedSeekPositionUs(long positionUs, @NonNull SeekParameters seekParameters) {
        return positionUs;
    }

    @Override
    public long getBufferedPositionUs() {
        return compositeSequenceableLoader.getBufferedPositionUs();
    }

    @Override
    public long getNextLoadPositionUs() {
        return compositeSequenceableLoader.getNextLoadPositionUs();
    }

    @Override
    public boolean continueLoading(@NonNull LoadingInfo loadingInfo) {
        return compositeSequenceableLoader.continueLoading(loadingInfo);
    }

    @Override
    public boolean isLoading() {
        return compositeSequenceableLoader.isLoading();
    }

    @Override
    public void reevaluateBuffer(long positionUs) {
        compositeSequenceableLoader.reevaluateBuffer(positionUs);
    }

    class SldpSampleStream implements SampleStream, SequenceableLoader {

        private final int[][] MPEG_SAMPLE_RATES = new int[][]{
                {11025, 12000, 8000},
                {0, 0, 0},
                {22050, 24000, 16000},
                {44100, 48000, 32000}
        };

        private final Map<Integer, Integer> ids;
        private final Map<Integer, Format> formats;
        private final int primaryTrackType;

        private int streamId = NO_ID;
        private int nextStreamId = NO_ID;
        private String mimeType;

        private final SampleQueue queue = SampleQueue.createWithoutDrm(allocator);
        private boolean hasOutputFormat;

        SldpSampleStream(Map<Integer, Integer> ids,
                         Map<Integer, Format> formats,
                         int primaryTrackType) {
            this.ids = ids;
            this.formats = formats;
            this.primaryTrackType = primaryTrackType;
        }

        void release() {
            queue.preRelease();
        }

        boolean isActive() {
            return streamId != NO_ID;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void maybeThrowError() throws IOException {
            if (connectionId == NO_ID) {
                throw new IOException();
            }
        }

        @Override
        public int readData(@NonNull FormatHolder formatHolder,
                            @NonNull DecoderInputBuffer buffer,
                            int readFlags) {

            if (sldpClient == null || connectionId == NO_ID) {
                return C.RESULT_END_OF_INPUT;
            }

            if (!isActive()) {
                return C.RESULT_NOTHING_READ;
            }

            //Log.d(TAG, String.format("readData -> %s", mimeType));

            BufferItem currentItem = sldpClient.read(streamId);

            if (!hasOutputFormat && currentItem != null) {
                pushFormat();
            }

            if (nextStreamId != NO_ID && currentItem != null) {
                Timestamp start = sldpClient.getStartTimestamp(nextStreamId);

                if (start != null) {
                    long startTs = start.getPts();
                    long currentTs = currentItem.getTimestamp().getPts();

                    if (startTs <= currentTs) {
                        Log.d(TAG, "select done -> " + nextStreamId);
                        if (startTs < currentTs) {
                            Log.d(TAG, "select late -> try reset");
                            queue.reset(true);
                        }

                        sldpClient.cancel(connectionId, streamId);
                        streamId = nextStreamId;
                        nextStreamId = NO_ID;

                        hasOutputFormat = false;
                        pushFormat();

                        currentItem = sldpClient.read(streamId);
                    }
                }
            }

            if (hasOutputFormat && currentItem != null) {
                byte[] frame = currentItem.getData();
                int len = frame.length;
                long timeUs = currentItem.getTimestamp().getPtsUs();
                boolean isKeyFrame = currentItem.isKeyFrame();

                queue.sampleData(new ParsableByteArray(frame), len);
                queue.sampleMetadata(
                        timeUs,
                        isKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0,
                        len,
                        0,
                        null);

//                Log.d(TAG, String.format(
//                        "readData -> %s %d %s %d",
//                        mimeType,
//                        timeUs,
//                        isKeyFrame ? "key:true" : "key:false",
//                        currentItem.getMessageIndex()));
            }

            if (currentItem != null) {
                int result = queue.read(formatHolder, buffer, readFlags, false);
                queue.discardToRead();
                return result;
            } else {
                return C.RESULT_NOTHING_READ;
            }
        }

        @Override
        public int skipData(long positionUs) {
            return C.RESULT_NOTHING_READ;
        }

        void play(int index) {
            if (sldpClient == null || connectionId == NO_ID || isActive()) {
                return;
            }

            Integer id = ids.get(index);
            Format format = formats.get(index);
            if (id == null || format == null) {
                return;
            }

            sldpClient.play(connectionId, id, offset);
            streamId = id;
            mimeType = format.sampleMimeType;

            Log.d(TAG, String.format("play -> %d", streamId));
        }

        void select(int index) {
            Log.d(TAG, "select -> request " + index);

            if (sldpClient == null || connectionId == NO_ID || !isActive()) {
                return;
            }

            Integer id = ids.get(index);
            Format format = formats.get(index);
            if (id == null || format == null) {
                Log.d(TAG, "select -> can't find id");
                return;
            }

            // Selection attempt already in progress, do nothing
            if (nextStreamId == id) {
                Log.d(TAG, "select -> selection attempt already in progress");
                return;
            }

            // Cancel ongoing selection attempt
            if (nextStreamId != NO_ID) {
                Log.d(TAG, "select -> request to select active stream");
                sldpClient.cancel(connectionId, nextStreamId);
                nextStreamId = NO_ID;
            }

            // Request to select active stream, do nothing
            if (streamId == id) {
                Log.d(TAG, "select -> request to select active stream");
                return;
            }

            sldpClient.play(connectionId, id, offset);
            nextStreamId = id;

            Log.d(TAG, String.format("start select -> %d", nextStreamId));
        }

        void cancel() {
            if (sldpClient == null || connectionId == NO_ID) {
                return;
            }
            Log.d(TAG, String.format("cancel -> type=%d, stream=%d, next=%d", primaryTrackType, streamId, nextStreamId));

            if (nextStreamId != NO_ID) {
                sldpClient.cancel(connectionId, nextStreamId);
                nextStreamId = NO_ID;
            }
            if (streamId != NO_ID) {
                sldpClient.cancel(connectionId, streamId);
                streamId = NO_ID;
            }
            hasOutputFormat = false;
        }

        private void pushFormat() {
            Format format = null;
            switch (mimeType) {
                case MimeTypes.VIDEO_VP8:
                case MimeTypes.VIDEO_VP9:
                    format = new Format.Builder()
                            .setSampleMimeType(mimeType)
                            .setWidth(320)
                            .setHeight(240)
                            .build();
                    break;

                case MimeTypes.VIDEO_AV1:
                    format = new Format.Builder()
                            .setSampleMimeType(MimeTypes.VIDEO_AV1)
                            .build();
                    break;

                case MimeTypes.AUDIO_OPUS:
                    format = new Format.Builder()
                            .setSampleMimeType(MimeTypes.AUDIO_OPUS)
                            .setChannelCount(2)
                            .setSampleRate(48_000)
                            .setInitializationData(buildOpusInitializationData())
                            .build();
                    break;

                case MimeTypes.AUDIO_AAC:
                case MimeTypes.AUDIO_MPEG:
                case MimeTypes.VIDEO_H264:
                case MimeTypes.VIDEO_H265:
                    byte[] extradata = sldpClient.readExtradata(streamId);
                    if (extradata != null) {
                        format = getFormatFromConfigRecord(extradata);
                    }
                    break;

                default:
                    break;
            }

            if (format != null) {
                Log.d(TAG, String.format(Locale.ENGLISH, "push format -> %s [%d]", mimeType, format.height));
                queue.format(format);
                hasOutputFormat = true;
            }
        }

        private Format getFormatFromConfigRecord(byte[] extradata) {
            Format format = null;
            try {
                switch (mimeType) {
                    case MimeTypes.AUDIO_AAC:
                        AacUtil.Config aacConfig = AacUtil.parseAudioSpecificConfig(extradata);
                        format = new Format.Builder()
                                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                                .setCodecs(aacConfig.codecs)
                                .setChannelCount(aacConfig.channelCount)
                                .setSampleRate(aacConfig.sampleRateHz)
                                .setInitializationData(Collections.singletonList(extradata))
                                .build();
                        break;

                    case MimeTypes.AUDIO_MPEG:
                        format = parseMpegSeqHeader(extradata);
                        break;

                    case MimeTypes.VIDEO_H264:
                        AvcConfig avcConfig = AvcConfig.parse(new ParsableByteArray(extradata));
                        format = new Format.Builder()
                                .setSampleMimeType(MimeTypes.VIDEO_H264)
                                .setCodecs(avcConfig.codecs)
                                .setWidth(avcConfig.width)
                                .setHeight(avcConfig.height)
                                .setPixelWidthHeightRatio(avcConfig.pixelWidthHeightRatio)
                                .setInitializationData(avcConfig.initializationData)
                                .build();
                        break;

                    case MimeTypes.VIDEO_H265:
                        HevcConfig hevcConfig = HevcConfig.parse(new ParsableByteArray(extradata));
                        format = new Format.Builder()
                                .setSampleMimeType(MimeTypes.VIDEO_H265)
                                .setCodecs(hevcConfig.codecs)
                                .setWidth(hevcConfig.width)
                                .setHeight(hevcConfig.height)
                                .setPixelWidthHeightRatio(hevcConfig.pixelWidthHeightRatio)
                                .setInitializationData(hevcConfig.initializationData)
                                .build();
                        break;

                    default:
                        break;
                }
            } catch (ParserException ignored) {
            }
            return format;
        }

        private Format parseMpegSeqHeader(byte[] buffer) {
            if (buffer.length < 4) {
                return null;
            }
            if ((toUnsigned(buffer[0]) & 255) == 255
                    && (toUnsigned(buffer[1]) & 224) == 224) {
                // AAAAAAAA   AAABBCCD   EEEEFFGH   IIJJKLMM
                final int version = (toUnsigned(buffer[1]) & 24) >> 3; //get BB (0 -> 3)
                final int srIndex = (toUnsigned(buffer[2]) & 12) >> 2; //get FF (0 -> 3)
                final int channels = (toUnsigned(buffer[3]) & 192) >> 6; //get II (0 -> 3)
                //valid frame header
                if (version != 1 && srIndex < 3) {
                    // Channel Mode
                    // 00 - Stereo
                    // 01 - Joint stereo (Stereo)
                    // 10 - Dual channel (2 mono channels)
                    // 11 - Single channel (Mono)
                    return new Format.Builder()
                            .setSampleMimeType(MimeTypes.AUDIO_MPEG)
                            .setChannelCount((channels == 3) ? 1 : 2)
                            .setSampleRate(MPEG_SAMPLE_RATES[version][srIndex])
                            .build();
                }
            }
            return null;
        }

        private int toUnsigned(byte b) {
            return b & 0xff;
        }

        private byte[] buildNativeOrderByteArray(long value) {
            return ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).array();
        }

        private List<byte[]> buildOpusInitializationData() {
            final byte[] opusHead = "OpusHead".getBytes(StandardCharsets.UTF_8);
            final byte[] preSkipNanos = buildNativeOrderByteArray(0);
            final byte[] seekPreRollNanos = buildNativeOrderByteArray(0);

            final byte[] header = new byte[19];

            System.arraycopy(opusHead, 0, header, 0, 8);
            header[8] = 0x1; // version
            header[9] = (byte) 2; // channels
            // 10-11: Pre skip
            final int sampleRate = 48_000;
            header[12] = (byte) ((sampleRate) & 0xFF);
            header[13] = (byte) ((sampleRate >> 8) & 0xFF);
            header[14] = (byte) ((sampleRate >> 16) & 0xFF);
            header[15] = (byte) ((sampleRate >> 24) & 0xFF);
            // 16-17: Output gain
            // 18: Channel mapping family

            return ImmutableList.of(header, preSkipNanos, seekPreRollNanos);
        }

        @Override
        public long getBufferedPositionUs() {
            return queue.getLargestQueuedTimestampUs();
        }

        @Override
        public long getNextLoadPositionUs() {
            return queue.getLargestQueuedTimestampUs();
        }

        @Override
        public boolean continueLoading(@NonNull LoadingInfo loadingInfo) {
            return false;
        }

        @Override
        public boolean isLoading() {
            return isActive();
        }

        @Override
        public void reevaluateBuffer(long positionUs) {
            // Do nothing.
        }
    }

}
