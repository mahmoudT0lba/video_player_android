// VIBEZO FORK — not part of upstream video_player_android.
//
// A single low-latency LoadControl shared by BOTH player construction paths
// (texture and platform-view) so the HLS live tune can never silently apply to
// only one of them.

package io.flutter.plugins.videoplayer;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;

/**
 * VIBEZO FORK — a {@link DefaultLoadControl} whose only departure from upstream defaults is a much
 * smaller START gate.
 *
 * <p>Upstream leaves {@code bufferForPlaybackMs} at 2500 ms, so ExoPlayer refuses to render the
 * first frame until 2.5 s of media is buffered. On a live stream that 2.5 s is added permanently to
 * the distance from the live edge, and on first entry it is 2.5 s of spinner. Dropping it to 600 ms
 * lets playback begin roughly one Cloudflare part-group in, which the live-offset convergence in
 * {@code HttpVideoAsset} then holds.
 *
 * <p>MIN/MAX buffer are left at the upstream 50 s so steady-state VOD prebuffering — the Vibes feed
 * shares this player — is unchanged. Only the moment playback is ALLOWED to start moves. The
 * rebuffer gate drops 5000 → 1500 ms for the same reason: a live stream that stalls should resume
 * near the edge, not five seconds behind it.
 */
// TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
@UnstableApi
public final class LiveLoadControl {
  private LiveLoadControl() {}

  @NonNull
  public static LoadControl build() {
    return new DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            /* bufferForPlaybackMs= */ 600,
            /* bufferForPlaybackAfterRebufferMs= */ 1500)
        .build();
  }
}
