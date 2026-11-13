package com.hms.home.data;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny web server behind the "HMS Phone Keyboard". The phone opens http://TV_IP:8766/TOKEN, types in a box and every
 * change is applied to the text field that is open on the TV. Local network only, the token in the link is the key.
 * The port and token are fixed so the page can be saved on the phone's home screen.
 */
public final class KeyServer {
    public static final int PORT = 8766;

    /** Implemented by the keyboard service. type() / key() are always called on the main thread. */
    public interface Host {
        void type(int del, String add);
        void key(String k);
        long epoch();
        boolean hasField();
    }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket ss;

    public KeyServer(Host h) { host = h; }

    /** The fixed address for the QR / saved page, or null when the box has no local network. */
    public static String url() {
        String ip = PairServer.localIp();
        return ip == null ? null : "http://" + ip + ":" + PORT + "/" + Store.kbToken();
    }

    public synchronized boolean isRunning() { return ss != null && !ss.isClosed(); }

    public synchronized boolean start() {
        if (isRunning()) return true;
        ServerSocket s0 = null;
        try {
            s0 = new ServerSocket();
            s0.setReuseAddress(true);
            s0.bind(new InetSocketAddress(PORT));
        } catch (IOException e) {
            try { if (s0 != null) s0.close(); } catch (IOException ignored) { }
            return false;
        }
        final ServerSocket server = s0;
        ss = server;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (!server.isClosed()) {
                    try { handleAsync(server.accept()); }
                    catch (IOException e) { if (server.isClosed()) return; }
                }
            }
        }, "kb-server");
        t.setDaemon(true);
        t.start();
        return true;
    }

    public synchronized void stop() {
        try { if (ss != null) ss.close(); } catch (IOException ignored) { }
        ss = null;
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

            String base = "/" + Store.kbToken();
            if (!path.startsWith(base)) { reply(out, 404, "text/html; charset=utf-8", "<h3>404</h3>"); return; }
            String rest = path.substring(base.length());

            if (rest.isEmpty() || rest.equals("/")) { reply(out, 200, "text/html; charset=utf-8", PAGE); return; }
            if (rest.equals("/p")) {
                reply(out, 200, "application/json", "{\"e\":" + host.epoch() + ",\"f\":" + host.hasField() + "}");
                return;
            }
            if (method.equals("POST") && (rest.equals("/t") || rest.equals("/k"))) {
                if (len > 32768) len = 32768;
                byte[] body = new byte[len];
                int off = 0;
                while (off < len) { int n = in.read(body, off, len - off); if (n < 0) break; off += n; }
                final Map<String, String> f = parse(new String(body, 0, off, "UTF-8"));
                if (!host.hasField()) { reply(out, 200, "application/json", "{\"ok\":false}"); return; }
                if (rest.equals("/t")) {
                    int d = 0;
                    try { d = Integer.parseInt(f.get("d")); } catch (Exception ignored) { }
                    final int del = Math.max(0, Math.min(d, 5000));
                    final String add = f.get("a") == null ? "" : f.get("a");
                    main.post(new Runnable() { @Override public void run() { host.type(del, add); } });
                } else {
                    final String k = f.get("k") == null ? "" : f.get("k");
                    main.post(new Runnable() { @Override public void run() { host.key(k); } });
                }
                reply(out, 200, "application/json", "{\"ok\":true}");
                return;
            }
            reply(out, 404, "text/html; charset=utf-8", "<h3>404</h3>");
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

    private static void reply(OutputStream out, int code, String type, String text) throws IOException {
        byte[] b = text.getBytes("UTF-8");
        String h = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    /** The phone page. Sends only what changed (delete N characters, then add text), polls every 1.5 s so it reconnects alone. */
    private static final String PAGE = "<!doctype html><html lang=ar dir=rtl><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + "<meta name=theme-color content='#1f7af5'><meta name=mobile-web-app-capable content=yes>"
            + "<title>HMS Keyboard</title><style>"
            + "body{font-family:sans-serif;background:#eef2f7;margin:0;padding:16px;color:#1b1f2a}"
            + ".c{max-width:520px;margin:auto;background:#fff;border-radius:18px;padding:18px;box-shadow:0 4px 18px #0002}"
            + "h2{margin:0 0 10px;font-size:20px}"
            + "#st{font-size:14px;padding:8px 12px;border-radius:10px;margin-bottom:12px;background:#fde8e8;color:#9b1c1c}"
            + "#st.ok{background:#e3f6e8;color:#1e7a3a}"
            + "textarea{width:100%;box-sizing:border-box;padding:13px;border:1px solid #c7d2e1;border-radius:12px;font-size:20px;min-height:120px;resize:none}"
            + ".r{display:flex;gap:8px;margin-top:10px}"
            + "button{flex:1;padding:14px 6px;border:0;border-radius:12px;background:#1f7af5;color:#fff;font-size:17px;font-weight:bold}"
            + "button.g{background:#566074}button.s{background:#e3e8f0;color:#1b1f2a;font-weight:normal;font-size:14px}"
            + "</style></head><body><div class=c><h2>\u2328 \u0643\u064a\u0628\u0648\u0631\u062f HMS \u0639\u0644\u0649 \u0627\u0644\u0645\u0648\u0628\u0627\u064a\u0644</h2>"
            + "<div id=st>\u062c\u0627\u0631\u064a \u0627\u0644\u0627\u062a\u0635\u0627\u0644\u2026</div>"
            + "<textarea id=t dir=auto placeholder='\u0627\u0643\u062a\u0628 \u0647\u0646\u0627 \u0648\u0647\u064a\u0638\u0647\u0631 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a\u2026' autocapitalize=off autocorrect=off spellcheck=false></textarea>"
            + "<div class=r><button id=en>\u21b5 Enter / \u0628\u062d\u062b</button></div>"
            + "<div class=r><button class=g id=bk>\u232b \u062d\u0631\u0641</button><button class=g id=cl>\u2716 \u0645\u0633\u062d \u0627\u0644\u062e\u0627\u0646\u0629</button></div>"
            + "<div class=r><button class=s id=sw>\u062a\u0628\u062f\u064a\u0644 \u0627\u0644\u0643\u064a\u0628\u0648\u0631\u062f \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a</button></div></div>"
            + "<script>"
            + "var B=location.pathname;while(B.length>1&&B.charAt(B.length-1)=='/')B=B.slice(0,-1);"
            + "var t=document.getElementById('t'),st=document.getElementById('st'),prev='',ep=null,q=Promise.resolve();"
            + "function show(ok,m){st.className=ok?'ok':'';st.textContent=m}"
            + "function post(p,b){return fetch(B+p,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b}).then(function(r){return r.json()})}"
            + "function enq(p,b){q=q.then(function(){return post(p,b)}).then(function(j){if(j&&j.ok===false)show(false,'\u0627\u0641\u062a\u062d \u062e\u0627\u0646\u0629 \u0643\u062a\u0627\u0628\u0629 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a \u0627\u0644\u0623\u0648\u0644')}).catch(function(){show(false,'\u0645\u0641\u064a\u0634 \u0627\u062a\u0635\u0627\u0644 \u0628\u0627\u0644\u062a\u064a\u0641\u064a')})}"
            + "function sync(){var a=prev,b=t.value,i=0;while(i<a.length&&i<b.length&&a.charAt(i)==b.charAt(i))i++;"
            + "if(i>0){var c=a.charCodeAt(i-1);if(c>=55296&&c<=56319)i--}"
            + "prev=b;if(a.length==i&&b.length==i)return;enq('/t','d='+(a.length-i)+'&a='+encodeURIComponent(b.slice(i)))}"
            + "t.addEventListener('input',sync);"
            + "document.getElementById('en').onclick=function(){enq('/k','k=enter');t.focus()};"
            + "document.getElementById('bk').onclick=function(){t.value=t.value.slice(0,-1);sync();t.focus()};"
            + "document.getElementById('cl').onclick=function(){t.value='';prev='';enq('/k','k=clear');t.focus()};"
            + "document.getElementById('sw').onclick=function(){enq('/k','k=switch')};"
            + "function ping(){fetch(B+'/p',{cache:'no-store'}).then(function(r){return r.json()}).then(function(j){"
            + "if(ep!==null&&j.e!==ep){t.value='';prev=''}ep=j.e;"
            + "show(j.f,j.f?'\u0645\u062a\u0635\u0644 \u2713 \u0627\u0643\u062a\u0628 \u0648\u0647\u064a\u0638\u0647\u0631 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a':'\u0627\u0641\u062a\u062d \u062e\u0627\u0646\u0629 \u0643\u062a\u0627\u0628\u0629 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a')"
            + "}).catch(function(){show(false,'\u0645\u0634 \u0645\u062a\u0635\u0644 \u0628\u0627\u0644\u062a\u064a\u0641\u064a\u2026 \u0627\u0641\u062a\u062d \u062e\u0627\u0646\u0629 \u0643\u062a\u0627\u0628\u0629 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a \u0648\u0647\u064a\u062a\u0648\u0635\u0644 \u0644\u0648\u062d\u062f\u0647')})}"
            + "setInterval(ping,1500);ping();t.focus();"
            + "</script></body></html>";
}
