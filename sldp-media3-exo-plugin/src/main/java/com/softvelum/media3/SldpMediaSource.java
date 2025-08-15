package com.softvelum.media3;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.BaseMediaSource;
import androidx.media3.exoplayer.source.ForwardingTimeline;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;

@UnstableApi
public class SldpMediaSource extends BaseMediaSource {

    static {
        MediaLibraryInfo.registerModule("media3.exoplayer.sldp");
    }

    public static final class Factory implements MediaSource.Factory {

        private String userAgent = "SLDPLib/1.0";
        private int offset;
        private boolean trustAllCerts;

        @NonNull
        @Override
        public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
            return this;
        }

        @NonNull
        @Override
        public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
            return this;
        }

        @NonNull
        @Override
        public int[] getSupportedTypes() {
            return new int[]{C.CONTENT_TYPE_OTHER};
        }

        @NonNull
        @Override
        public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
            return new SldpMediaSource(mediaItem, userAgent, offset, trustAllCerts);
        }

        public SldpMediaSource.Factory setUserAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public SldpMediaSource.Factory setOffset(int offset) {
            this.offset = offset;
            return this;
        }

        public SldpMediaSource.Factory setTrustAllCerts(boolean trustAllCerts) {
            this.trustAllCerts = trustAllCerts;
            return this;
        }
    }

    private final MediaItem mediaItem;
    private final String userAgent;
    private final int offset;
    private final boolean trustAllCerts;

    private SldpMediaSource(@NonNull MediaItem mediaItem,
                            String userAgent,
                            int offset,
                            boolean trustAllCerts) {
        this.mediaItem = mediaItem;
        this.userAgent = userAgent;
        this.offset = offset;
        this.trustAllCerts = trustAllCerts;
    }

    @Override
    protected void prepareSourceInternal(@Nullable TransferListener transferListener) {
        notifySourceInfoRefreshed();
    }

    @Override
    protected void releaseSourceInternal() {
        // Do nothing.
    }

    @NonNull
    @Override
    public synchronized MediaItem getMediaItem() {
        return mediaItem;
    }

    @Override
    public void maybeThrowSourceInfoRefreshError() {
        // Do nothing.
    }

    @NonNull
    @Override
    public MediaPeriod createPeriod(@NonNull MediaPeriodId id, @NonNull Allocator allocator, long startPositionUs) {
        return new SldpMediaPeriod(mediaItem.localConfiguration.uri, allocator, userAgent, offset, trustAllCerts);
    }

    @Override
    public void releasePeriod(@NonNull MediaPeriod mediaPeriod) {
        ((SldpMediaPeriod) mediaPeriod).release();
    }

    private void notifySourceInfoRefreshed() {
        Timeline timeline = new SinglePeriodTimeline(
                C.TIME_UNSET,
                false,
                false,
                true,
                null,
                getMediaItem());

        Timeline placeholder = new ForwardingTimeline(timeline) {
            @NonNull
            @Override
            public Window getWindow(
                    int windowIndex, @NonNull Window window, long defaultPositionProjectionUs) {
                super.getWindow(windowIndex, window, defaultPositionProjectionUs);
                window.isPlaceholder = true;
                return window;
            }

            @NonNull
            @Override
            public Period getPeriod(int periodIndex, @NonNull Period period, boolean setIds) {
                super.getPeriod(periodIndex, period, setIds);
                period.isPlaceholder = true;
                return period;
            }
        };

        refreshSourceInfo(placeholder);
    }

}
