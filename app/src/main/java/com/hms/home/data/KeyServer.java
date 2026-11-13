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
import com.hms.home.input.MouseService;

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

    /** Used while no keyboard service is attached: the page still opens (mouse works) and says "open a text field". */
    private static final Host IDLE = new Host() {
        @Override public void type(int del, String add) { }
        @Override public void key(String k) { }
        @Override public long epoch() { return 0; }
        @Override public boolean hasField() { return false; }
    };

    private static KeyServer shared;
    /** True while the mouse service is on: the server stays up even when no text field is open. */
    public static volatile boolean pinned;

    private volatile Host host = IDLE;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket ss;

    private KeyServer() { }

    /** One server for the whole app: the keyboard service and the mouse service share it. */
    public static synchronized KeyServer shared() {
        if (shared == null) shared = new KeyServer();
        return shared;
    }

    public void setHost(Host h) { host = h == null ? IDLE : h; }
    public void clearHost(Host h) { if (host == h) host = IDLE; }
    public boolean hasField() { return host.hasField(); }

    /** Stops the server unless the mouse service needs it. */
    public void stopUnlessPinned() { if (!pinned) stop(); }

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
                reply(out, 200, "application/json", "{\"e\":" + host.epoch() + ",\"f\":" + host.hasField()
                        + ",\"m\":" + MouseService.connected() + ",\"c\":" + MouseService.cursorVisible() + "}");
                return;
            }
            if (method.equals("POST") && rest.equals("/m")) {
                if (len > 2048) len = 2048;
                byte[] mb = new byte[len];
                int mo = 0;
                while (mo < len) { int n = in.read(mb, mo, len - mo); if (n < 0) break; mo += n; }
                final Map<String, String> mf = parse(new String(mb, 0, mo, "UTF-8"));
                if (!MouseService.connected()) { reply(out, 200, "application/json", "{\"ok\":false,\"m\":false}"); return; }
                final String mt = mf.get("t") == null ? "" : mf.get("t");
                final int mx = num(mf.get("x")), my = num(mf.get("y"));
                main.post(new Runnable() { @Override public void run() { MouseService.act(mt, mx, my); } });
                reply(out, 200, "application/json", "{\"ok\":true,\"m\":true}");
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

    private static int num(String v) {
        try { return Math.max(-2000, Math.min(2000, Integer.parseInt(v))); } catch (Exception e) { return 0; }
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
        private static final String PAGE = "<!doctype html><html lang=ar dir=rtl><head><meta charset=utf-8><meta name=viewport content='width=device-width"
            + ",initial-scale=1,maximum-scale=1'><meta name=theme-color content='#1f7af5'><meta name=mobile-web-app-capable c"
            + "ontent=yes><title>HMS Keyboard</title><style>body{font-family:sans-serif;background:#eef2f7;margin:0;padding:1"
            + "6px;color:#1b1f2a}.c{max-width:520px;margin:auto;background:#fff;border-radius:18px;padding:18px;box-shadow:0 "
            + "4px 18px #0002}h2{margin:0 0 10px;font-size:20px}#st{font-size:14px;padding:8px 12px;border-radius:10px;margin"
            + "-bottom:12px;background:#fde8e8;color:#9b1c1c}#st.ok{background:#e3f6e8;color:#1e7a3a}textarea{width:100%;box-"
            + "sizing:border-box;padding:13px;border:1px solid #c7d2e1;border-radius:12px;font-size:20px;min-height:120px;res"
            + "ize:none}.r{display:flex;gap:8px;margin-top:10px}button{flex:1;padding:14px 6px;border:0;border-radius:12px;ba"
            + "ckground:#1f7af5;color:#fff;font-size:17px;font-weight:bold}button.g{background:#566074}button.s{background:#e"
            + "3e8f0;color:#1b1f2a;font-weight:normal;font-size:14px}#pad{height:210px;background:#dfe6f1;border-radius:14px;"
            + "margin-top:10px;display:flex;align-items:center;justify-content:center;text-align:center;font-size:14px;line-h"
            + "eight:1.7;color:#566074;touch-action:none;user-select:none;-webkit-user-select:none}#mn{display:none;font-size"
            + ":14px;padding:8px 12px;border-radius:10px;margin-top:10px;background:#fde8e8;color:#9b1c1c}.dp{direction:ltr;d"
            + "isplay:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:10px}.dp button{padding:18px 6px;font-size:"
            + "22px;touch-action:manipulation;-webkit-user-select:none;user-select:none}</style></head><body><div class=c><h2"
            + ">\u2328 \u0643\u064a\u0628\u0648\u0631\u062f HMS \u0639\u0644\u0649 \u0627\u0644\u0645\u0648\u0628\u0627\u064a\u0644</h2><div id=st>\u062c\u0627\u0631\u064a \u0627\u0644\u0627\u062a\u0635\u0627\u0644\u2026</div><textarea id=t dir=auto placeholder='\u0627\u0643\u062a\u0628 \u0647\u0646\u0627 \u0648\u0647\u064a"
            + "\u0638\u0647\u0631 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a\u2026' autocapitalize=off autocorrect=off spellcheck=false></textarea><div class=r><button id=en>\u21b5 E"
            + "nter / \u0628\u062d\u062b</button></div><div class=r><button class=g id=bk>\u232b \u062d\u0631\u0641</button><button class=g id=cl>\u2716 \u0645\u0633\u062d \u0627\u0644\u062e\u0627\u0646\u0629</"
            + "button></div><div class=r><button class=s id=sw>\u062a\u0628\u062f\u064a\u0644 \u0627\u0644\u0643\u064a\u0628\u0648\u0631\u062f \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a</button></div><div class=r><button c"
            + "lass=s id=mt>\u0645\u0627\u0648\u0633 \u0648\u0631\u064a\u0645\u0648\u062a \u0639\u0644\u0649 \u0627\u0644\u0645\u0648\u0628\u0627\u064a\u0644</button></div><div id=mp style='display:none'><div id=mn>\u062e\u062f\u0645\u0629 \u0627\u0644\u0645\u0627\u0648\u0633 \u0645\u0634 "
            + "\u0634\u063a\u0627\u0644\u0629 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a: \u0627\u0644\u0625\u0639\u062f\u0627\u062f\u0627\u062a \u2190 \u0625\u0645\u0643\u0627\u0646\u064a\u0629 \u0627\u0644\u0648\u0635\u0648\u0644 (Accessibility) \u2190 HMS Mouse \u2190 \u0634\u063a\u0651\u0644\u0647\u0627</div><div id=pad>\u0627\u0633\u062d\u0628 \u0628\u0625\u0635\u0628\u0639 "
            + "\u0644\u062a\u062d\u0631\u064a\u0643 \u0627\u0644\u0645\u0627\u0648\u0633<br>\u0644\u0645\u0633\u0629 = \u0646\u0642\u0631 \u2022 \u0636\u063a\u0637\u0629 \u0637\u0648\u064a\u0644\u0629 = \u0646\u0642\u0631 \u0645\u0637\u0648\u0651\u0644 \u2022 \u0625\u0635\u0628\u0639\u064a\u0646 = \u0633\u0643\u0631\u0648\u0644</div><div class=r><button id=mc>\u0646\u0642\u0631</but"
            + "ton><button class=g id=mlc>\u0646\u0642\u0631 \u0645\u0637\u0648\u0651\u0644</button></div><div class=dp><span></span><button class=g id=mup>\u25b2</button"
            + "><span></span><button class=g id=mlf>\u25c0</button><button id=mok>OK</button><button class=g id=mrt>\u25b6</button><spa"
            + "n></span><button class=g id=mdn>\u25bc</button><span></span></div><div class=r><button class=g id=mbk>\u0631\u062c\u0648\u0639</button>"
            + "<button class=g id=mhm>\u0647\u0648\u0645</button><button class=g id=mrc>\u0622\u062e\u0631 \u0627\u0644\u062a\u0637\u0628\u064a\u0642\u0627\u062a</button></div><div class=r><button cla"
            + "ss=s id=msp>\u0627\u0644\u0633\u0631\u0639\u0629: \u0639\u0627\u062f\u064a</button></div></div></div><script>var B=location.pathname;while(B.length>1&&B.charAt("
            + "B.length-1)=='/')B=B.slice(0,-1);var t=document.getElementById('t'),st=document.getElementById('st'),prev='',e"
            + "p=null,q=Promise.resolve();function show(ok,m){st.className=ok?'ok':'';st.textContent=m}function post(p,b){ret"
            + "urn fetch(B+p,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b}).then(functi"
            + "on(r){return r.json()})}function enq(p,b){q=q.then(function(){return post(p,b)}).then(function(j){if(j&&j.ok=="
            + "=false)show(false,'\u0627\u0641\u062a\u062d \u062e\u0627\u0646\u0629 \u0643\u062a\u0627\u0628\u0629 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a \u0627\u0644\u0623\u0648\u0644')}).catch(function(){show(false,'\u0645\u0641\u064a\u0634 \u0627\u062a\u0635\u0627\u0644 \u0628\u0627\u0644\u062a\u064a\u0641\u064a')})}fu"
            + "nction sync(){var a=prev,b=t.value,i=0;while(i<a.length&&i<b.length&&a.charAt(i)==b.charAt(i))i++;if(i>0){var "
            + "c=a.charCodeAt(i-1);if(c>=55296&&c<=56319)i--}prev=b;if(a.length==i&&b.length==i)return;enq('/t','d='+(a.lengt"
            + "h-i)+'&a='+encodeURIComponent(b.slice(i)))}t.addEventListener('input',sync);document.getElementById('en').oncl"
            + "ick=function(){enq('/k','k=enter');t.focus()};document.getElementById('bk').onclick=function(){t.value=t.value"
            + ".slice(0,-1);sync();t.focus()};document.getElementById('cl').onclick=function(){t.value='';prev='';enq('/k','k"
            + "=clear');t.focus()};document.getElementById('sw').onclick=function(){enq('/k','k=switch')};function ping(){fet"
            + "ch(B+'/p',{cache:'no-store'}).then(function(r){return r.json()}).then(function(j){if(ep!==null&&j.e!==ep){t.va"
            + "lue='';prev=''}ep=j.e;mstat(j);show(j.f,j.f?'\u0645\u062a\u0635\u0644 \u2713 \u0627\u0643\u062a\u0628 \u0648\u0647\u064a\u0638\u0647\u0631 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a':'\u0627\u0641\u062a\u062d \u062e\u0627\u0646\u0629 \u0643\u062a\u0627\u0628\u0629 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a')}).ca"
            + "tch(function(){show(false,'\u0645\u0634 \u0645\u062a\u0635\u0644 \u0628\u0627\u0644\u062a\u064a\u0641\u064a\u2026 \u0627\u0641\u062a\u062d \u062e\u0627\u0646\u0629 \u0643\u062a\u0627\u0628\u0629 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a \u0648\u0647\u064a\u062a\u0648\u0635\u0644 \u0644\u0648\u062d\u062f\u0647')})}var mon=false,mpn=doc"
            + "ument.getElementById('mp'),mnt=document.getElementById('mn'),pad=document.getElementById('pad'),mt=document.ge"
            + "tElementById('mt');var SP=[1.6,2.6,4.2],SN=['\u0628\u0637\u064a\u0621','\u0639\u0627\u062f\u064a','\u0633\u0631\u064a\u0639'],si=1,ax=0,ay=0,busy=false,px=0,py=0,sy=0,scA"
            + "=0,scBusy=false,dist=0,maxF=0,t0=0,tog=0;function mpost(b){return fetch(B+'/m',{method:'POST',headers:{'Conten"
            + "t-Type':'application/x-www-form-urlencoded'},body:b}).then(function(r){return r.json()}).then(function(j){if(j"
            + "&&j.m===false)mnt.style.display='block';return j})}function mp2(b){return mpost(b).catch(function(){})}functio"
            + "n setMon(on,send){mon=on;mpn.style.display=on?'block':'none';mt.textContent=on?'\u0625\u062e\u0641\u0627\u0621 \u0627\u0644\u0645\u0627\u0648\u0633':'\u0645\u0627\u0648\u0633 \u0648\u0631\u064a\u0645\u0648\u062a \u0639\u0644\u0649"
            + " \u0627\u0644\u0645\u0648\u0628\u0627\u064a\u0644';if(send)mp2('t='+(on?'sh':'hd'))}function mstat(j){if(j.c&&!mon&&Date.now()-tog>2500)setMon(true,fa"
            + "lse);mnt.style.display=(mon&&j.m===false)?'block':'none'}mt.onclick=function(){tog=Date.now();setMon(!mon,true"
            + ")};function flush(){if(busy||(Math.abs(ax)<1&&Math.abs(ay)<1))return;var x=Math.round(ax),y=Math.round(ay);ax-"
            + "=x;ay-=y;busy=true;mpost('t=mv&x='+x+'&y='+y).catch(function(){}).then(function(){busy=false;setTimeout(flush,"
            + "12)})}pad.addEventListener('touchstart',function(e){e.preventDefault();var n=e.touches.length;if(n>maxF)maxF=n"
            + ";if(n==1){px=e.touches[0].clientX;py=e.touches[0].clientY;t0=Date.now();dist=0}else if(n>=2){sy=(e.touches[0]."
            + "clientY+e.touches[1].clientY)/2;scA=0}},{passive:false});pad.addEventListener('touchmove',function(e){e.preven"
            + "tDefault();if(e.touches.length==1){var tc=e.touches[0],dx=tc.clientX-px,dy=tc.clientY-py;px=tc.clientX;py=tc.c"
            + "lientY;dist+=Math.abs(dx)+Math.abs(dy);var f=SP[si]*(1+Math.min(Math.sqrt(dx*dx+dy*dy),25)/12);ax+=dx*f;ay+=dy"
            + "*f;flush()}else if(e.touches.length>=2){var y=(e.touches[0].clientY+e.touches[1].clientY)/2;scA+=y-sy;sy=y;dis"
            + "t+=99;if(Math.abs(scA)>=25&&!scBusy){scBusy=true;var v=Math.round(scA*5);scA=0;mp2('t=sc&y='+v).then(function("
            + "){setTimeout(function(){scBusy=false},300)})}}},{passive:false});pad.addEventListener('touchend',function(e){e"
            + ".preventDefault();if(e.touches.length==1){px=e.touches[0].clientX;py=e.touches[0].clientY}if(e.touches.length="
            + "=0){var dt=Date.now()-t0;if(maxF==1&&dist<10){mp2(dt>=550?'t=lc':'t=cl')}maxF=0}},{passive:false});pad.addEven"
            + "tListener('touchcancel',function(){maxF=0});function mk(id,c){document.getElementById(id).onclick=function(){m"
            + "p2('t='+c)}}mk('mc','cl');mk('mlc','lc');mk('mbk','bk');mk('mhm','hm');mk('mrc','rc');mk('mok','ok');function "
            + "hold(id,c){var el=document.getElementById(id),to=0,iv=0;function stop(){clearTimeout(to);clearInterval(iv)}el."
            + "addEventListener('touchstart',function(e){e.preventDefault();mp2('t='+c);to=setTimeout(function(){iv=setInterv"
            + "al(function(){mp2('t='+c)},160)},420)},{passive:false});el.addEventListener('touchend',function(e){e.preventDe"
            + "fault();stop()});el.addEventListener('touchcancel',stop);el.onclick=function(){mp2('t='+c)}}hold('mup','up');h"
            + "old('mdn','dn');hold('mlf','lf');hold('mrt','rt');document.getElementById('msp').onclick=function(){si=(si+1)%"
            + "3;this.textContent='\u0627\u0644\u0633\u0631\u0639\u0629: '+SN[si]};document.addEventListener('contextmenu',function(e){e.preventDefault()})"
            + ";setInterval(ping,1500);ping();t.focus();</script></body></html>";
}
