// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import java.util.Map;

final class HttpVideoAsset extends VideoAsset {
  @NonNull private final StreamingFormat streamingFormat;
  @NonNull private final Map<String, String> httpHeaders;
  @Nullable private final String userAgent;

  HttpVideoAsset(
      @Nullable String assetUrl,
      @NonNull StreamingFormat streamingFormat,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent) {
    super(assetUrl);
    this.streamingFormat = streamingFormat;
    this.httpHeaders = httpHeaders;
    this.userAgent = userAgent;
  }

  /**
   * VIBEZO FORK — target distance from the live edge, in milliseconds.
   *
   * <p>Upstream never sets a {@link MediaItem.LiveConfiguration}, so ExoPlayer falls back to the
   * playlist's segment-granularity hold-back and parks roughly one whole segment behind the edge.
   * Measured against our Cloudflare LL-HLS output (TARGETDURATION=4, PART-TARGET=0.5,
   * PART-HOLD-BACK=1.5) that left viewers ~4.4 s behind live with a ~2.5 s sawtooth — the
   * low-latency parts were never used.
   *
   * <p>2026-07-13: this was 2000 ms — only 500 ms of head-room above Cloudflare's 1.5 s
   * PART-HOLD-BACK floor. That is not enough to absorb normal part-delivery jitter: the playhead
   * crept onto the edge (measured `edgeOffsetMs` decaying 2682 → 656 over ~3 min) and then
   * starved in a tight micro-stall loop — `isPlaying` flickering true/false ~10×/s with the
   * position frozen, i.e. a permanently juddering picture. 4000 ms keeps a ~2.5 s cushion above
   * the hold-back, which absorbs the jitter and holds a steady playhead. The added latency is
   * paid for by the playhead-alignment layer (gifts / PK score / camera state are rendered at
   * `event.ts + videoDelayMs`), so a bigger offset costs coherence nothing. Lower it only after
   * confirming, on a real device, that `edgeOffsetMs` stays flat instead of decaying.
   */
  private static final long LIVE_TARGET_OFFSET_MS = 4000;

  /**
   * Playback-speed bounds used to converge on {@link #LIVE_TARGET_OFFSET_MS}. Deliberately gentle:
   * the pitch shift stays inaudible. The catch-up ceiling was 1.04f; softened to 1.02f so the
   * player converges on the target without overshooting past it into the edge — overshoot is what
   * fed the starvation loop described above.
   */
  private static final float LIVE_MIN_PLAYBACK_SPEED = 0.97f;

  private static final float LIVE_MAX_PLAYBACK_SPEED = 1.02f;

  @NonNull
  @Override
  public MediaItem getMediaItem() {
    MediaItem.Builder builder = new MediaItem.Builder().setUri(assetUrl);
    String mimeType = null;
    switch (streamingFormat) {
      case SMOOTH:
        mimeType = MimeTypes.APPLICATION_SS;
        break;
      case DYNAMIC_ADAPTIVE:
        mimeType = MimeTypes.APPLICATION_MPD;
        break;
      case HTTP_LIVE:
        mimeType = MimeTypes.APPLICATION_M3U8;
        // VIBEZO FORK: aim at the live edge. ExoPlayer applies a LiveConfiguration only to a LIVE
        // window; on a VOD playlist (the Vibes feed's `hlsMasterUrl`) it is inert. That is why this
        // can be attached to every HLS item instead of plumbing a per-instance flag through pigeon.
        builder.setLiveConfiguration(
            new MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                .setMinPlaybackSpeed(LIVE_MIN_PLAYBACK_SPEED)
                .setMaxPlaybackSpeed(LIVE_MAX_PLAYBACK_SPEED)
                .build());
        break;
    }
    if (mimeType != null) {
      builder.setMimeType(mimeType);
    }
    return builder.build();
  }

  // VIBEZO FORK: back the HTTP(S) data source with the shared OkHttp client
  // (HTTP/2 + warm connection pool) instead of the HTTP/1.1 DefaultHttpDataSource.
  // The manifest, media playlist and LL-HLS parts all hit the same Cloudflare
  // host; H2 multiplexes them over one connection and the pool keeps that
  // connection (and its TLS session) warm across live opens. The
  // DefaultHttpDataSource path below is retained for tests only.
  @OptIn(markerClass = UnstableApi.class)
  @NonNull
  @Override
  public MediaSource.Factory getMediaSourceFactory(@NonNull Context context) {
    OkHttpDataSource.Factory httpFactory =
        new OkHttpDataSource.Factory(SharedOkHttp.client()).setUserAgent(userAgent);
    if (!httpHeaders.isEmpty()) {
      httpFactory.setDefaultRequestProperties(httpHeaders);
    }
    DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, httpFactory);
    return new DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory);
  }

  /**
   * Returns a configured media source factory, starting at the provided factory.
   *
   * <p>This method is provided for ease of testing without making real HTTP calls.
   *
   * @param context application context.
   * @param initialFactory initial factory, to be configured.
   * @return configured factory, or {@code null} if not needed for this asset type.
   */
  @VisibleForTesting
  MediaSource.Factory getMediaSourceFactory(
      Context context, DefaultHttpDataSource.Factory initialFactory) {
    unstableUpdateDataSourceFactory(initialFactory, httpHeaders, userAgent);
    DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(context, initialFactory);
    return new DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory);
  }

  // TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
  @OptIn(markerClass = UnstableApi.class)
  private static void unstableUpdateDataSourceFactory(
      @NonNull DefaultHttpDataSource.Factory factory,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent) {
    factory.setUserAgent(userAgent).setAllowCrossProtocolRedirects(true);
    if (!httpHeaders.isEmpty()) {
      factory.setDefaultRequestProperties(httpHeaders);
    }
  }
}
