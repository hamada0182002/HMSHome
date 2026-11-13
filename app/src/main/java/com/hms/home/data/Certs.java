package com.hms.home.data;

import android.content.Context;
import com.hms.home.R;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Enumeration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Old TVs (Android 7 and below) don't know newer root certificates, so some https sites (images, GitHub) fail.
 * We trust the system certificates PLUS an up-to-date root list bundled in the app (res/raw/cacert.pem).
 * Certificates are still fully validated - nothing is disabled.
 * (Used for images + updates only. The IPTV server uses the plain client, exactly like HMS Player.)
 */
public final class Certs {
    private static Context app;
    private static SSLSocketFactory factory;
    private static X509TrustManager trust;
    private static boolean tried;

    public static void init(Context c) { app = c.getApplicationContext(); }

    private static synchronized void build() {
        if (tried) return;
        tried = true;
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int n = 0;
            try {
                KeyStore sys = KeyStore.getInstance("AndroidCAStore");
                sys.load(null);
                Enumeration<String> al = sys.aliases();
                while (al.hasMoreElements()) {
                    Certificate ce = sys.getCertificate(al.nextElement());
                    if (ce != null) ks.setCertificateEntry("sys" + (n++), ce);
                }
            } catch (Exception ignored) { }
            InputStream in = app.getResources().openRawResource(R.raw.cacert);
            try {
                for (Certificate ce : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    ks.setCertificateEntry("app" + (n++), ce);
                }
            } finally { in.close(); }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            X509TrustManager tm = null;
            for (TrustManager t : tmf.getTrustManagers()) if (t instanceof X509TrustManager) { tm = (X509TrustManager) t; break; }
            if (tm == null) return;
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            factory = ctx.getSocketFactory();
            trust = tm;
        } catch (Exception e) { factory = null; trust = null; }
    }

    /** null = use the default factory. */
    public static SSLSocketFactory factory() { build(); return factory; }
    public static X509TrustManager trustManager() { build(); return trust; }
}
