// VIBEZO FORK — not part of upstream video_player_android.
//
// A single process-wide OkHttp client backing the media data source (see
// HttpVideoAsset). Upstream uses DefaultHttpDataSource (HTTP/1.1, a fresh
// connection per request). On a live HLS stream the multivariant playlist, the
// media playlist and every LL-HLS part hit the SAME Cloudflare host, so:
//
//   * HTTP/2 (negotiated via ALPN by default) multiplexes those requests over
//     one connection instead of serialising them, and
//   * the connection pool keeps that connection — and its TLS session — warm
//     across live opens, so a second open to the same host skips the
//     DNS/TCP/TLS handshake.
//
// Both cut the cold round-trips that dominate viewer join latency.

package io.flutter.plugins.videoplayer;

import androidx.annotation.NonNull;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;

final class SharedOkHttp {
  private static volatile OkHttpClient client;

  private SharedOkHttp() {}

  /** The shared client, lazily built on first use (double-checked locking). */
  @NonNull
  static OkHttpClient client() {
    OkHttpClient local = client;
    if (local == null) {
      synchronized (SharedOkHttp.class) {
        local = client;
        if (local == null) {
          local =
              new OkHttpClient.Builder()
                  // Keep up to 8 idle connections warm for 5 minutes so repeated
                  // live opens to the same Cloudflare host reuse the pooled
                  // connection (and TLS session) instead of re-handshaking.
                  .connectionPool(new ConnectionPool(8, 5, TimeUnit.MINUTES))
                  .retryOnConnectionFailure(true)
                  .connectTimeout(8, TimeUnit.SECONDS)
                  .readTimeout(8, TimeUnit.SECONDS)
                  .build();
          client = local;
        }
      }
    }
    return local;
  }
}
