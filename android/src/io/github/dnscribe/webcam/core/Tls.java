package io.github.dnscribe.webcam.core;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

/**
 * The hub uses a locally generated certificate. A native app has no browser warning to click through, so it accepts any
 * certificate - but {@link HubSession} and {@link HubFinder} only ever connect to private/LAN addresses (the same rule the hub applies to clients).
 * Use a token on shared networks.
 */
public final class Tls {
    private Tls() {}

    public static OkHttpClient client() {
        try {
            final X509TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[] {tm}, new SecureRandom());
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sc.getSocketFactory(), tm)
                    .hostnameVerifier((h, s) -> true)
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(20, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
