package com.hms.home.data;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny web server that lives only while the "scan QR" dialog is open.
 * The TV shows a QR with http://TV_IP:port/TOKEN ; the phone (same Wi-Fi) opens it, fills the form
 * and the data comes back here. Nothing leaves the local network. The random token blocks other devices.
 */
public final class PairServer {
    public interface Listener { void onData(String name, String server, String user, String pass); }

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String token;
    private ServerSocket ss;
    private volatile boolean stopped;

    public PairServer(Listener l) {
        listener = l;
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        token = sb.toString();
    }

    /** Starts listening and returns the address to put in the QR. Throws if there is no local network. */
    public String start() throws IOException {
        String ip = localIp();
        if (ip == null) throw new IOException("مفيش شبكة محلية (Wi-Fi / Ethernet) متوصلة");
        try { ss = new ServerSocket(); ss.setReuseAddress(true); ss.bind(new InetSocketAddress(8765)); }
        catch (IOException e) { ss = new ServerSocket(0); }
        final ServerSocket server = ss;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (!stopped) {
                    try { final Socket s = server.accept(); handleAsync(s); }
                    catch (IOException e) { if (stopped) return; }
                }
            }
        }, "pair-server");
        t.setDaemon(true);
        t.start();
        return "http://" + ip + ":" + server.getLocalPort() + "/" + token;
    }

    public void stop() {
        stopped = true;
        try { if (ss != null) ss.close(); } catch (IOException ignored) { }
    }

    private static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String n = ni.getName().toLowerCase();
                    if (a.isSiteLocalAddress() && (n.startsWith("wlan") || n.startsWith("eth"))) return a.getHostAddress();
                    if (a.isSiteLocalAddress() && fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) { return null; }
    }

    // ---------------- http ----------------
    private void handleAsync(final Socket s) {
        Thread t = new Thread(new Runnable() { @Override public void run() { handle(s); } });
        t.setDaemon(true);
        t.start();
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int state = 0, b;
            while (state < 4 && (b = in.read()) >= 0 && head.size() < 8192) {
                head.write(b);
                state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            }
            String[] lines = head.toString("UTF-8").split("\r\n");
            String[] rl = lines[0].split(" ");
            if (rl.length < 2) return;
            String method = rl[0], path = rl[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            int len = 0;
            for (String l : lines) if (l.toLowerCase().startsWith("content-length:")) { try { len = Integer.parseInt(l.substring(15).trim()); } catch (Exception ignored) { } }
            OutputStream out = s.getOutputStream();
            if (!path.equals("/" + token)) { reply(out, 404, "<h3>404</h3>"); return; }
            if (method.equals("POST")) {
                if (len > 8192) len = 8192;
                byte[] body = new byte[len];
                int off = 0;
                while (off < len) { int n = in.read(body, off, len - off); if (n < 0) break; off += n; }
                final Map<String, String> f = parse(new String(body, 0, off, "UTF-8"));
                reply(out, 200, page("<h2>تم الإرسال ✓</h2><p>شوف شاشة التيفي، هيتحقق من الحساب ويحفظه.</p>"));
                main.post(new Runnable() { @Override public void run() {
                    if (!stopped) listener.onData(f.get("name"), f.get("server"), f.get("user"), f.get("pass"));
                } });
            } else {
                reply(out, 200, page(FORM));
            }
        } catch (Exception ignored) {
        } finally { try { s.close(); } catch (IOException ignored) { } }
    }

    private static Map<String, String> parse(String body) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
        }
        return m;
    }

    private static void reply(OutputStream out, int code, String html) throws IOException {
        byte[] b = html.getBytes("UTF-8");
        String h = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    private static String page(String inner) {
        return "<!doctype html><html lang=ar dir=rtl><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>HMS HOME</title><style>body{font-family:sans-serif;background:#eef2f7;margin:0;padding:20px;color:#1b1f2a}"
                + ".c{max-width:460px;margin:auto;background:#fff;border-radius:18px;padding:22px;box-shadow:0 4px 18px #0002}"
                + "input{width:100%;box-sizing:border-box;padding:13px;margin:6px 0 14px;border:1px solid #c7d2e1;border-radius:10px;font-size:16px;direction:ltr;text-align:left}"
                + "button{width:100%;padding:14px;border:0;border-radius:12px;background:#1f7af5;color:#fff;font-size:18px;font-weight:bold}"
                + "label{font-size:14px;color:#566074}</style></head><body><div class=c>" + inner + "</div></body></html>";
    }

    private static final String FORM = "<h2>HMS HOME - إضافة حساب IPTV</h2>"
            + "<form method=post>"
            + "<label>اسم الحساب (اختياري)</label><input name=name autocapitalize=off>"
            + "<label>رابط السيرفر  http://example.com:8080</label><input name=server type=url autocapitalize=off autocorrect=off required>"
            + "<label>اليوزر</label><input name=user autocapitalize=off autocorrect=off>"
            + "<label>الباسورد</label><input name=pass autocapitalize=off autocorrect=off>"
            + "<button type=submit>إرسال للتيفي</button></form>"
            + "<p style='font-size:13px;color:#566074'>تقدر تلصق لينك get.php كامل في خانة السيرفر وسيب اليوزر والباسورد فاضيين.</p>";
}
