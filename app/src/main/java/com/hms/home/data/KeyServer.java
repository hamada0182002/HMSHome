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
            + "3e8f0;color:#1b1f2a;font-weight:normal;font-size:14px}#pad{flex:1;height:230px;background:#dfe6f1;border-radiu"
            + "s:14px;display:flex;align-items:center;justify-content:center;text-align:center;direction:rtl;font-size:14px;l"
            + "ine-height:1.7;color:#566074;touch-action:none;user-select:none;-webkit-user-select:none}#scr{width:64px;heigh"
            + "t:230px;background:#cfd8e6;border-radius:14px;display:flex;flex-direction:column;align-items:center;justify-co"
            + "ntent:space-between;padding:12px 0;box-sizing:border-box;direction:rtl;color:#566074;font-size:13px;touch-acti"
            + "on:none;user-select:none;-webkit-user-select:none}.pw{direction:ltr;display:flex;gap:8px;margin-top:10px}#mn{d"
            + "isplay:none;font-size:14px;padding:8px 12px;border-radius:10px;margin-top:10px;background:#fde8e8;color:#9b1c1"
            + "c}.dp{direction:ltr;display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:10px}.dp button{paddin"
            + "g:18px 6px;font-size:22px;touch-action:manipulation;-webkit-user-select:none;user-select:none}button.ic{paddin"
            + "g:12px 6px}button.ic svg{display:block;margin:auto;pointer-events:none}</style></head><body><div class=c><h2>\u2328"
            + " \u0643\u064a\u0628\u0648\u0631\u062f HMS \u0639\u0644\u0649 \u0627\u0644\u0645\u0648\u0628\u0627\u064a\u0644</h2><div id=kbv><div id=st>\u062c\u0627\u0631\u064a \u0627\u0644\u0627\u062a\u0635\u0627\u0644\u2026</div><textarea id=t dir=auto placeholder='\u0627\u0643"
            + "\u062a\u0628 \u0647\u0646\u0627 \u0648\u0647\u064a\u0638\u0647\u0631 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a\u2026' autocapitalize=off autocorrect=off spellcheck=false></textarea><div class=r><button"
            + " id=en>\u21b5 Enter / \u0628\u062d\u062b</button></div><div class=r><button class=g id=bk>\u232b \u062d\u0631\u0641</button><button class=g id=cl>\u2716 \u0645\u0633"
            + "\u062d \u0627\u0644\u062e\u0627\u0646\u0629</button></div><div class=r><button class=s id=sw>\u062a\u0628\u062f\u064a\u0644 \u0627\u0644\u0643\u064a\u0628\u0648\u0631\u062f \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a</button></div></div><div c"
            + "lass=r id=mtr><button class=s id=mt>\u0645\u0627\u0648\u0633 \u0648\u0631\u064a\u0645\u0648\u062a \u0639\u0644\u0649 \u0627\u0644\u0645\u0648\u0628\u0627\u064a\u0644</button></div><div id=mp style='display:none'><di"
            + "v id=mn></div><div class=pw><div id=pad>\u0627\u0633\u062d\u0628 \u0628\u0625\u0635\u0628\u0639 \u0644\u062a\u062d\u0631\u064a\u0643 \u0627\u0644\u0645\u0627\u0648\u0633<br>\u0644\u0645\u0633\u0629 = \u0646\u0642\u0631 \u2022 \u0636\u063a\u0637\u0629 \u0637\u0648\u064a\u0644\u0629 = \u0646\u0642\u0631 \u0645\u0637\u0648\u0651\u0644</div><"
            + "div id=scr><span>\u25b2</span><span>\u0633\u0643\u0631\u0648\u0644</span><span>\u25bc</span></div></div><div class=r><button id=mc>\u0646\u0642\u0631</button><b"
            + "utton class=g id=mlc>\u0646\u0642\u0631 \u0645\u0637\u0648\u0651\u0644</button></div><div class=dp><span></span><button class=g id=mup>\u25b2</button><span"
            + "></span><button class=g id=mlf>\u25c0</button><button id=mok>OK</button><button class=g id=mrt>\u25b6</button><span></sp"
            + "an><button class=g id=mdn>\u25bc</button><span></span></div><div class=r><button class='g ic' id=mbk aria-label=bac"
            + "k><svg width=26 height=26 viewBox='0 0 24 24'><path d='M17 4L6 12l11 8z' fill='#fff'/></svg></button><button c"
            + "lass='g ic' id=mhm aria-label=home><svg width=26 height=26 viewBox='0 0 24 24'><circle cx=12 cy=12 r=7.5 fill="
            + "none stroke='#fff' stroke-width=2.6 /></svg></button><button class='g ic' id=mrc aria-label=recents><svg width"
            + "=26 height=26 viewBox='0 0 24 24'><rect x=5 y=5 width=14 height=14 rx=2.5 fill=none stroke='#fff' stroke-width"
            + "=2.6 /></svg></button></div><div class=r><button class=s id=mkb>\u2328 \u0643\u062a\u0627\u0628\u0629</button><button class=s id=mhd>\u0625\u062e\u0641\u0627\u0621 \u0627"
            + "\u0644\u0645\u0627\u0648\u0633</button><button class=s id=msp>\u0627\u0644\u0633\u0631\u0639\u0629: \u0639\u0627\u062f\u064a</button></div></div></div></div><script>var B=location.pathn"
            + "ame;while(B.length>1&&B.charAt(B.length-1)=='/')B=B.slice(0,-1);var t=document.getElementById('t'),st=document"
            + ".getElementById('st'),prev='',ep=null,q=Promise.resolve();function show(ok,m){st.className=ok?'ok':'';st.textC"
            + "ontent=m}function post(p,b){return fetch(B+p,{method:'POST',headers:{'Content-Type':'application/x-www-form-ur"
            + "lencoded'},body:b}).then(function(r){return r.json()})}function enq(p,b){q=q.then(function(){return post(p,b)}"
            + ").then(function(j){if(j&&j.ok===false)show(false,'\u0627\u0641\u062a\u062d \u062e\u0627\u0646\u0629 \u0643\u062a\u0627\u0628\u0629 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a \u0627\u0644\u0623\u0648\u0644')}).catch(function(){show(f"
            + "alse,'\u0645\u0641\u064a\u0634 \u0627\u062a\u0635\u0627\u0644 \u0628\u0627\u0644\u062a\u064a\u0641\u064a')})}function sync(){var a=prev,b=t.value,i=0;while(i<a.length&&i<b.length&&a.charAt(i"
            + ")==b.charAt(i))i++;if(i>0){var c=a.charCodeAt(i-1);if(c>=55296&&c<=56319)i--}prev=b;if(a.length==i&&b.length=="
            + "i)return;enq('/t','d='+(a.length-i)+'&a='+encodeURIComponent(b.slice(i)))}t.addEventListener('input',sync);doc"
            + "ument.getElementById('en').onclick=function(){enq('/k','k=enter');t.focus()};document.getElementById('bk').onc"
            + "lick=function(){t.value=t.value.slice(0,-1);sync();t.focus()};document.getElementById('cl').onclick=function()"
            + "{t.value='';prev='';enq('/k','k=clear');t.focus()};document.getElementById('sw').onclick=function(){enq('/k','"
            + "k=switch')};function ping(){fetch(B+'/p',{cache:'no-store'}).then(function(r){return r.json()}).then(function("
            + "j){if(ep!==null&&j.e!==ep){t.value='';prev=''}ep=j.e;mstat(j);show(j.f,j.f?'\u0645\u062a\u0635\u0644 \u2713 \u0627\u0643\u062a\u0628 \u0648\u0647\u064a\u0638\u0647\u0631 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a':'\u0627\u0641"
            + "\u062a\u062d \u062e\u0627\u0646\u0629 \u0643\u062a\u0627\u0628\u0629 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a')}).catch(function(){show(false,'\u0645\u0634 \u0645\u062a\u0635\u0644 \u0628\u0627\u0644\u062a\u064a\u0641\u064a\u2026 \u0627\u0641\u062a\u062d \u062e\u0627\u0646\u0629 \u0643\u062a\u0627\u0628\u0629 \u0639\u0644\u0649 \u0627\u0644\u062a\u064a\u0641\u064a \u0648\u0647\u064a\u062a\u0648\u0635\u0644 "
            + "\u0644\u0648\u062d\u062f\u0647')})}function $(i){return document.getElementById(i)}var view='kb',mpn=$('mp'),kbv=$('kbv'),mtr=$('mtr'),"
            + "mnt=$('mn'),pad=$('pad'),scr=$('scr'),mt=$('mt');var SP=[1.6,2.6,4.2],SN=['\u0628\u0637\u064a\u0621','\u0639\u0627\u062f\u064a','\u0633\u0631\u064a\u0639'],si=1,ax=0,ay=0"
            + ",busy=false,px=0,py=0,sy=0,scA=0,scBusy=false,dist=0,maxF=0,t0=0,first=true;var M1='\u062e\u062f\u0645\u0629 \u0627\u0644\u0645\u0627\u0648\u0633 \u0645\u0634 \u0634\u063a\u0627\u0644\u0629 \u0639\u0644\u0649 \u0627"
            + "\u0644\u062a\u064a\u0641\u064a: \u0627\u0644\u0625\u0639\u062f\u0627\u062f\u0627\u062a \u2190 \u0625\u0645\u0643\u0627\u0646\u064a\u0629 \u0627\u0644\u0648\u0635\u0648\u0644 (Accessibility) \u2190 HMS Mouse \u2190 \u0634\u063a\u0651\u0644\u0647\u0627',M2='\u0645\u0641\u064a\u0634 \u0627\u062a\u0635\u0627\u0644 \u0628\u0627\u0644\u062a\u064a\u0641\u064a';function note("
            + "m){if(m){mnt.textContent=m;mnt.style.display='block'}else mnt.style.display='none'}function fail(){note(M2)}fu"
            + "nction mpost(b){return fetch(B+'/m',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'"
            + "},body:b}).then(function(r){return r.json()}).then(function(j){note(j&&j.m===false?M1:'');return j})}function "
            + "mp2(b){return mpost(b).catch(fail)}function setView(v){view=v;var ms=v=='ms';mpn.style.display=ms?'block':'non"
            + "e';kbv.style.display=ms?'none':'block';mtr.style.display=ms?'none':'block';if(!ms){note('');try{t.focus()}catc"
            + "h(e){}}}function mstat(j){if(first){first=false;if(j.c)setView('ms')}if(view=='ms')note(j.m===false?M1:'')}mt."
            + "onclick=function(){mp2('t=sh');setView('ms')};$('mkb').onclick=function(){setView('kb')};$('mhd').onclick=func"
            + "tion(){mp2('t=hd');setView('kb')};setInterval(function(){if(view=='ms')fetch(B+'/p',{cache:'no-store'}).catch("
            + "fail)},2500);function flush(){if(busy||(Math.abs(ax)<1&&Math.abs(ay)<1))return;var x=Math.round(ax),y=Math.rou"
            + "nd(ay);ax-=x;ay-=y;busy=true;mpost('t=mv&x='+x+'&y='+y).catch(fail).then(function(){busy=false;setTimeout(flus"
            + "h,12)})}pad.addEventListener('touchstart',function(e){e.preventDefault();var n=e.touches.length;if(n>maxF)maxF"
            + "=n;if(n==1){px=e.touches[0].clientX;py=e.touches[0].clientY;t0=Date.now();dist=0}else dist+=99},{passive:false"
            + "});pad.addEventListener('touchmove',function(e){e.preventDefault();if(e.touches.length!=1)return;var tc=e.touc"
            + "hes[0],dx=tc.clientX-px,dy=tc.clientY-py;px=tc.clientX;py=tc.clientY;dist+=Math.abs(dx)+Math.abs(dy);var f=SP["
            + "si]*(1+Math.min(Math.sqrt(dx*dx+dy*dy),25)/12);ax+=dx*f;ay+=dy*f;flush()},{passive:false});pad.addEventListene"
            + "r('touchend',function(e){e.preventDefault();if(e.touches.length==1){px=e.touches[0].clientX;py=e.touches[0].cl"
            + "ientY}if(e.touches.length==0){var dt=Date.now()-t0;if(maxF==1&&dist<10)mp2(dt>=550?'t=lc':'t=cl');maxF=0}},{pa"
            + "ssive:false});pad.addEventListener('touchcancel',function(){maxF=0});scr.addEventListener('touchstart',functio"
            + "n(e){e.preventDefault();sy=e.touches[0].clientY;scA=0},{passive:false});scr.addEventListener('touchmove',funct"
            + "ion(e){e.preventDefault();var y=e.touches[0].clientY;scA+=y-sy;sy=y;if(Math.abs(scA)>=18&&!scBusy){scBusy=true"
            + ";var v=Math.round(scA*6);scA=0;mp2('t=sc&y='+v).then(function(){setTimeout(function(){scBusy=false},300)})}},{"
            + "passive:false});function mk(id,c){$(id).onclick=function(){mp2('t='+c)}}mk('mc','cl');mk('mlc','lc');mk('mbk',"
            + "'bk');mk('mhm','hm');mk('mrc','rc');mk('mok','ok');function hold(id,c){var el=$(id),to=0,iv=0;function stop(){"
            + "clearTimeout(to);clearInterval(iv)}el.addEventListener('touchstart',function(e){e.preventDefault();mp2('t='+c)"
            + ";to=setTimeout(function(){iv=setInterval(function(){mp2('t='+c)},160)},420)},{passive:false});el.addEventListe"
            + "ner('touchend',function(e){e.preventDefault();stop()});el.addEventListener('touchcancel',stop);el.onclick=func"
            + "tion(){mp2('t='+c)}}hold('mup','up');hold('mdn','dn');hold('mlf','lf');hold('mrt','rt');$('msp').onclick=funct"
            + "ion(){si=(si+1)%3;this.textContent='\u0627\u0644\u0633\u0631\u0639\u0629: '+SN[si]};document.addEventListener('contextmenu',function(e){e.pr"
            + "eventDefault()});setInterval(ping,1500);ping();t.focus();</script></body></html>";
}
