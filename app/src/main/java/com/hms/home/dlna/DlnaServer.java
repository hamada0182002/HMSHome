package com.hms.home.dlna;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import com.hms.home.data.Store;
import com.hms.home.data.Item;
import com.hms.home.data.PlayQueue;
import com.hms.home.player.PlayerActivity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DLNA / UPnP MediaRenderer (what Windows "Cast to device", BubbleUPnP, VLC, Gallery "Cast" etc. look for).
 * No external library: SSDP discovery + device description + AVTransport / RenderingControl / ConnectionManager.
 */
public final class DlnaServer {
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket http;
    private MulticastSocket ssdp;
    private WifiManager.MulticastLock lock;
    private int port;
    private String usn;                      // uuid:xxxx
    private String name;
    private final ConcurrentHashMap<String, String[]> subs = new ConcurrentHashMap<>(); // sid -> {callback url, service}
    private int eventSeq;

    public DlnaServer(Context c) { ctx = c.getApplicationContext(); }

    public synchronized void start() {
        if (running) return;
        name = Store.dlnaName();
        usn = "uuid:" + Store.dlnaUuid();
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { lock = wm.createMulticastLock("hms-dlna"); lock.setReferenceCounted(false); lock.acquire(); }
        } catch (Exception ignored) { }
        try {
            http = new ServerSocket();
            http.setReuseAddress(true);
            try { http.bind(new InetSocketAddress(49152)); } catch (IOException e) { http.close(); http = new ServerSocket(0); }
            port = http.getLocalPort();
        } catch (IOException e) { return; }
        running = true;
        DlnaBridge.onChange = new Runnable() { @Override public void run() { sendEventAll(); } };
        pool.execute(new Runnable() { @Override public void run() { httpLoop(); } });
        pool.execute(new Runnable() { @Override public void run() { ssdpLoop(); } });
        pool.execute(new Runnable() { @Override public void run() { notifyLoop(); } });
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        DlnaBridge.onChange = null;
        try { sendNotify("ssdp:byebye"); } catch (Exception ignored) { }
        try { if (http != null) http.close(); } catch (Exception ignored) { }
        try { if (ssdp != null) ssdp.close(); } catch (Exception ignored) { }
        try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) { }
        pool.shutdownNow();
    }

    // ================= network helpers =================
    private static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private String base(String ip) { return "http://" + ip + ":" + port; }

    // ================= SSDP =================
    private static final String[] TYPES = {
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:service:RenderingControl:1",
            "urn:schemas-upnp-org:service:ConnectionManager:1"};

    private String nt(int i) { return i == -1 ? usn : TYPES[i]; }
    private String usnFor(int i) { return i == -1 ? usn : (i == 0 ? usn + "::upnp:rootdevice" : usn + "::" + TYPES[i]); }

    private void ssdpLoop() {
        while (running) {
            try {
                ssdp = new MulticastSocket(null);
                ssdp.setReuseAddress(true);
                ssdp.bind(new InetSocketAddress(SSDP_PORT));
                ssdp.joinGroup(InetAddress.getByName(SSDP_ADDR));
                byte[] buf = new byte[2048];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ssdp.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                    if (!msg.startsWith("M-SEARCH")) continue;
                    String st = header(msg, "ST");
                    if (st == null) continue;
                    int mx = 2;
                    try { mx = Math.max(1, Math.min(5, Integer.parseInt(header(msg, "MX").trim()))); } catch (Exception ignored) { }
                    respond(p.getAddress(), p.getPort(), st.trim(), mx);
                }
            } catch (Exception e) {
                try { if (ssdp != null) ssdp.close(); } catch (Exception ignored) { }
                if (running) try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void respond(final InetAddress to, final int toPort, final String st, int mx) {
        final String ip = localIp();
        if (ip == null) return;
        final List<Integer> hit = new ArrayList<>();
        if (st.equals("ssdp:all")) { for (int i = -1; i < TYPES.length; i++) hit.add(i); }
        else if (st.equals(usn)) hit.add(-1);
        else for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(st)) hit.add(i);
        if (hit.isEmpty()) return;
        final long delay = new Random().nextInt(mx * 500);
        pool.execute(new Runnable() { @Override public void run() {
            try {
                Thread.sleep(delay);
                for (int i : hit) {
                    String s = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n"
                            + "LOCATION: " + base(ip) + "/desc.xml\r\n"
                            + "SERVER: Android/UPnP/1.0 HMSHome/1.0\r\n"
                            + "ST: " + (i == -1 ? usn : TYPES[i]) + "\r\nUSN: " + usnFor(i) + "\r\n\r\n";
                    byte[] b = s.getBytes("UTF-8");
                    java.net.DatagramSocket ds = new java.net.DatagramSocket();
                    ds.send(new DatagramPacket(b, b.length, to, toPort));
                    ds.close();
                }
            } catch (Exception ignored) { }
        } });
    }

    private void notifyLoop() {
        try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
        while (running) {
            try { sendNotify("ssdp:alive"); } catch (Exception ignored) { }
            try { Thread.sleep(60000); } catch (InterruptedException e) { return; }
        }
    }

    private void sendNotify(String nts) throws Exception {
        String ip = localIp();
        if (ip == null) return;
        InetAddress g = InetAddress.getByName(SSDP_ADDR);
        java.net.DatagramSocket ds = new java.net.DatagramSocket();
        for (int rep = 0; rep < 2; rep++) {
            for (int i = -1; i < TYPES.length; i++) {
                String s = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\n"
                        + "LOCATION: " + base(ip) + "/desc.xml\r\nNT: " + nt(i) + "\r\nNTS: " + nts + "\r\n"
                        + "SERVER: Android/UPnP/1.0 HMSHome/1.0\r\nUSN: " + usnFor(i) + "\r\n\r\n";
                byte[] b = s.getBytes("UTF-8");
                ds.send(new DatagramPacket(b, b.length, g, SSDP_PORT));
            }
        }
        ds.close();
    }

    private static String header(String msg, String key) {
        for (String line : msg.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(key)) return line.substring(c + 1).trim();
        }
        return null;
    }

    // ================= HTTP =================
    private void httpLoop() {
        while (running) {
            try {
                final Socket s = http.accept();
                pool.execute(new Runnable() { @Override public void run() { handle(s); } });
            } catch (Exception e) { if (!running) return; }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            ByteArrayOutputStream hb = new ByteArrayOutputStream();
            int state = 0, c;
            while (state < 4 && (c = in.read()) >= 0) {           // read up to \r\n\r\n
                hb.write(c);
                if ((c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3))) state++; else state = c == '\r' ? 1 : 0;
                if (hb.size() > 16384) return;
            }
            String head = hb.toString("UTF-8");
            String[] lines = head.split("\r\n");
            if (lines.length == 0) return;
            String[] rl = lines[0].split(" ");
            if (rl.length < 2) return;
            String method = rl[0], path = rl[1];
            int q = path.indexOf('?'); if (q > 0) path = path.substring(0, q);
            int len = 0;
            String callback = null, sidIn = null, soapAction = "";
            for (String l : lines) {
                int i = l.indexOf(':'); if (i < 0) continue;
                String k = l.substring(0, i).trim().toLowerCase(Locale.US), v = l.substring(i + 1).trim();
                if (k.equals("content-length")) try { len = Integer.parseInt(v); } catch (Exception ignored) { }
                else if (k.equals("callback")) callback = v;
                else if (k.equals("sid")) sidIn = v;
                else if (k.equals("soapaction")) soapAction = v.replace("\"", "");
            }
            String body = "";
            if (len > 0) {
                byte[] b = new byte[Math.min(len, 262144)];
                int off = 0;
                while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
                body = new String(b, 0, off, "UTF-8");
            }
            String ip = localIp() == null ? "127.0.0.1" : localIp();
            if (method.equals("GET") || method.equals("HEAD")) {
                String xml = null;
                if (path.equals("/desc.xml")) xml = deviceXml();
                else if (path.equals("/AVTransport.xml")) xml = Scpd.AVT;
                else if (path.equals("/RenderingControl.xml")) xml = Scpd.RC;
                else if (path.equals("/ConnectionManager.xml")) xml = Scpd.CM;
                if (xml == null) reply(out, 404, "text/plain", "not found", method.equals("HEAD"));
                else reply(out, 200, "text/xml; charset=\"utf-8\"", xml, method.equals("HEAD"));
            } else if (method.equals("POST")) {
                String svc = path.contains("AVTransport") ? "AVTransport" : path.contains("RenderingControl") ? "RenderingControl" : path.contains("ConnectionManager") ? "ConnectionManager" : null;
                if (svc == null) { reply(out, 404, "text/plain", "not found", false); }
                else {
                    String action = soapAction.contains("#") ? soapAction.substring(soapAction.indexOf('#') + 1) : "";
                    String res = control(svc, action, body);
                    if (res == null) reply(out, 500, "text/xml; charset=\"utf-8\"", soapFault(401, "Invalid Action"), false);
                    else reply(out, 200, "text/xml; charset=\"utf-8\"", res, false);
                }
            } else if (method.equals("SUBSCRIBE")) {
                String sid;
                if (sidIn != null && subs.containsKey(sidIn)) sid = sidIn;
                else {
                    sid = "uuid:" + java.util.UUID.randomUUID();
                    String cb = callback == null ? "" : callback.replaceAll("^<|>.*$", "");
                    if (!cb.isEmpty()) subs.put(sid, new String[]{cb, path});
                }
                String h = "HTTP/1.1 200 OK\r\nSID: " + sid + "\r\nTIMEOUT: Second-1800\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                out.write(h.getBytes("UTF-8")); out.flush();
                if (sidIn == null) { final String fs = sid; pool.execute(new Runnable() { @Override public void run() { try { Thread.sleep(300); } catch (Exception ignored) { } sendEvent(fs); } }); }
            } else if (method.equals("UNSUBSCRIBE")) {
                if (sidIn != null) subs.remove(sidIn);
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("UTF-8")); out.flush();
            } else reply(out, 405, "text/plain", "", false);
        } catch (Exception ignored) {
        } finally { try { s.close(); } catch (Exception ignored) { } }
    }

    private static void reply(OutputStream out, int code, String type, String body, boolean head) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        String h = "HTTP/1.1 " + code + (code == 200 ? " OK" : code == 500 ? " Internal Server Error" : " Error") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + b.length + "\r\nEXT:\r\nSERVER: Android/UPnP/1.0 HMSHome/1.0\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8"));
        if (!head) out.write(b);
        out.flush();
    }

    private String deviceXml() {
        return "<?xml version=\"1.0\"?>\n<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                + "<friendlyName>" + esc(name) + "</friendlyName><manufacturer>HMS</manufacturer><modelName>HMS HOME</modelName>"
                + "<modelDescription>HMS HOME Media Renderer</modelDescription><modelNumber>1</modelNumber><UDN>" + usn + "</UDN>"
                + "<serviceList>"
                + svcXml("AVTransport", "AVTransport") + svcXml("RenderingControl", "RenderingControl") + svcXml("ConnectionManager", "ConnectionManager")
                + "</serviceList></device></root>";
    }

    private static String svcXml(String t, String f) {
        return "<service><serviceType>urn:schemas-upnp-org:service:" + t + ":1</serviceType><serviceId>urn:upnp-org:serviceId:" + t + "</serviceId>"
                + "<SCPDURL>/" + f + ".xml</SCPDURL><controlURL>/ctl/" + f + "</controlURL><eventSubURL>/evt/" + f + "</eventSubURL></service>";
    }

    // ================= SOAP control =================
    /** Text of the first <t>...</t> (namespace prefix and attributes allowed), unescaped. null if missing. */
    private static String tag(String xml, String t) {
        if (xml == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<(?:\\w+:)?" + java.util.regex.Pattern.quote(t) + "(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?" + java.util.regex.Pattern.quote(t) + ">", java.util.regex.Pattern.DOTALL).matcher(xml);
        return m.find() ? unesc(m.group(1)) : null;
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static String unesc(String s) { return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&"); }

    private static String hms(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private static long parseHms(String t) {
        try {
            String[] p = t.trim().split("[:.]");
            return (Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60 + Long.parseLong(p[2])) * 1000;
        } catch (Exception e) { return 0; }
    }

    private static String soapFault(int code, String msg) {
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault>"
                + "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code + "</errorCode><errorDescription>" + msg + "</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>";
    }

    private static String soap(String svc, String action, String inner) {
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + "Response xmlns:u=\"urn:schemas-upnp-org:service:" + svc + ":1\">" + inner + "</u:" + action + "Response></s:Body></s:Envelope>";
    }

    private static String el(String n, String v) { return "<" + n + ">" + esc(v) + "</" + n + ">"; }

    private String control(String svc, String a, String body) {
        if (svc.equals("ConnectionManager")) {
            switch (a) {
                case "GetProtocolInfo": return soap(svc, a, el("Source", "") + el("Sink", Scpd.SINK));
                case "GetCurrentConnectionIDs": return soap(svc, a, el("ConnectionIDs", "0"));
                case "GetCurrentConnectionInfo": return soap(svc, a, el("RcsID", "0") + el("AVTransportID", "0") + el("ProtocolInfo", "") + el("PeerConnectionManager", "") + el("PeerConnectionID", "-1") + el("Direction", "Input") + el("Status", "OK"));
                default: return null;
            }
        }
        if (svc.equals("RenderingControl")) {
            switch (a) {
                case "GetVolume": return soap(svc, a, el("CurrentVolume", String.valueOf(DlnaBridge.volume)));
                case "SetVolume": {
                    int v = 0; try { v = Integer.parseInt(tag(body, "DesiredVolume").trim()); } catch (Exception ignored) { }
                    final int vv = Math.max(0, Math.min(100, v));
                    DlnaBridge.volume = vv;
                    main.post(new Runnable() { @Override public void run() { DlnaBridge.Controller c = DlnaBridge.controller; if (c != null) c.dlnaSetVolume(vv); } });
                    return soap(svc, a, "");
                }
                case "GetMute": return soap(svc, a, el("CurrentMute", DlnaBridge.muted ? "1" : "0"));
                case "SetMute": {
                    String m = tag(body, "DesiredMute");
                    final boolean mm = m != null && (m.trim().equals("1") || m.trim().equalsIgnoreCase("true"));
                    DlnaBridge.muted = mm;
                    main.post(new Runnable() { @Override public void run() { DlnaBridge.Controller c = DlnaBridge.controller; if (c != null) c.dlnaSetMute(mm); } });
                    return soap(svc, a, "");
                }
                case "ListPresets": return soap(svc, a, el("CurrentPresetNameList", "FactoryDefaults"));
                case "SelectPreset": return soap(svc, a, "");
                default: return null;
            }
        }
        // ---- AVTransport ----
        switch (a) {
            case "SetAVTransportURI": {
                String uri = tag(body, "CurrentURI"), meta = tag(body, "CurrentURIMetaData");
                if (uri == null || uri.trim().isEmpty()) return soapFault(402, "Invalid Args");
                openMedia(uri.trim(), meta == null ? "" : meta);
                return soap(svc, a, "");
            }
            case "SetNextAVTransportURI": return soap(svc, a, "");
            case "Play": main.post(new Runnable() { @Override public void run() { DlnaBridge.Controller c = DlnaBridge.controller; if (c != null) c.dlnaPlay(); else if (!DlnaBridge.uri.isEmpty()) launch(DlnaBridge.uri, DlnaBridge.meta); } }); return soap(svc, a, "");
            case "Pause": main.post(new Runnable() { @Override public void run() { DlnaBridge.Controller c = DlnaBridge.controller; if (c != null) c.dlnaPause(); } }); return soap(svc, a, "");
            case "Stop": main.post(new Runnable() { @Override public void run() { DlnaBridge.Controller c = DlnaBridge.controller; if (c != null) c.dlnaStop(); } }); return soap(svc, a, "");
            case "Next": case "Previous": return soap(svc, a, "");
            case "Seek": {
                String unit = tag(body, "Unit"), target = tag(body, "Target");
                if (target != null && unit != null && (unit.contains("REL_TIME") || unit.contains("ABS_TIME"))) {
                    final long ms = parseHms(target);
                    DlnaBridge.posMs = ms;
                    main.post(new Runnable() { @Override public void run() { DlnaBridge.Controller c = DlnaBridge.controller; if (c != null) c.dlnaSeek(ms); } });
                }
                return soap(svc, a, "");
            }
            case "GetTransportInfo": return soap(svc, a, el("CurrentTransportState", DlnaBridge.state) + el("CurrentTransportStatus", "OK") + el("CurrentSpeed", "1"));
            case "GetPositionInfo": return soap(svc, a, el("Track", DlnaBridge.uri.isEmpty() ? "0" : "1") + el("TrackDuration", hms(DlnaBridge.durMs)) + el("TrackMetaData", DlnaBridge.meta)
                    + el("TrackURI", DlnaBridge.uri) + el("RelTime", hms(DlnaBridge.posMs)) + el("AbsTime", hms(DlnaBridge.posMs)) + el("RelCount", "2147483647") + el("AbsCount", "2147483647"));
            case "GetMediaInfo": return soap(svc, a, el("NrTracks", DlnaBridge.uri.isEmpty() ? "0" : "1") + el("MediaDuration", hms(DlnaBridge.durMs)) + el("CurrentURI", DlnaBridge.uri) + el("CurrentURIMetaData", DlnaBridge.meta)
                    + el("NextURI", "") + el("NextURIMetaData", "") + el("PlayMedium", "NETWORK") + el("RecordMedium", "NOT_IMPLEMENTED") + el("WriteStatus", "NOT_IMPLEMENTED"));
            case "GetDeviceCapabilities": return soap(svc, a, el("PlayMedia", "NETWORK") + el("RecMedia", "NOT_IMPLEMENTED") + el("RecQualityModes", "NOT_IMPLEMENTED"));
            case "GetTransportSettings": return soap(svc, a, el("PlayMode", "NORMAL") + el("RecQualityMode", "NOT_IMPLEMENTED"));
            case "GetCurrentTransportActions": return soap(svc, a, el("Actions", "Play,Pause,Stop,Seek"));
            case "SetPlayMode": return soap(svc, a, "");
            default: return null;
        }
    }

    // ================= start playing =================
    private void openMedia(final String uri, final String meta) {
        DlnaBridge.uri = uri; DlnaBridge.meta = meta;
        DlnaBridge.posMs = 0; DlnaBridge.durMs = 0; DlnaBridge.state = "TRANSITIONING";
        main.post(new Runnable() { @Override public void run() {
            DlnaBridge.Controller c = DlnaBridge.controller;
            if (c != null) c.dlnaSetUri(uri, titleOf(meta, uri), isImage(uri, meta));
            else launch(uri, meta);
        } });
        sendEventAll();
    }

    /** Photos are shown as a picture, not played (DIDL class imageItem, an image/* mime type, or an image file name). */
    static boolean isImage(String uri, String meta) {
        String m = meta == null ? "" : meta.toLowerCase(Locale.US);
        if (m.contains("object.item.imageitem") || m.contains(":image/")) return true;
        if (m.contains("object.item.videoitem") || m.contains("object.item.audioitem")) return false;
        String u = uri.toLowerCase(Locale.US);
        int q = u.indexOf('?'); if (q > 0) u = u.substring(0, q);
        return u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png") || u.endsWith(".gif") || u.endsWith(".webp") || u.endsWith(".bmp") || u.endsWith(".heic");
    }

    private static String titleOf(String meta, String uri) {
        try { String t = tag(meta, "dc:title"); if (t != null && !t.trim().isEmpty()) return t.trim(); } catch (Exception ignored) { }
        try { String last = android.net.Uri.parse(uri).getLastPathSegment(); if (last != null && !last.isEmpty()) return last; } catch (Exception ignored) { }
        return "DLNA";
    }

    private volatile long lastLaunch;
    private volatile String lastLaunchUri = "";

    private void launch(String uri, String meta) {
        long now = System.currentTimeMillis();
        if (uri.equals(lastLaunchUri) && now - lastLaunch < 4000) return;   // "SetURI" then "Play" arrive together: open once
        lastLaunch = now; lastLaunchUri = uri;
        Item it = new Item();
        it.type = "dlna"; it.id = uri; it.name = titleOf(meta, uri); it.ext = uri;
        if (isImage(uri, meta)) it.extra = "image";
        List<Item> l = new ArrayList<>(); l.add(it);
        PlayQueue.set(l, 0);
        Intent i = new Intent(ctx, PlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("dlna", true);
        try { ctx.startActivity(i); } catch (Exception ignored) { }
    }

    // ================= eventing (so controllers see state changes) =================
    private void sendEventAll() { for (String sid : subs.keySet()) sendEvent(sid); }

    private void sendEvent(final String sid) {
        final String[] s = subs.get(sid);
        if (s == null) return;
        pool.execute(new Runnable() { @Override public void run() {
            try {
                String props;
                if (s[1].contains("AVTransport"))
                    props = "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange>"
                            + esc("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\"><TransportState val=\"" + DlnaBridge.state + "\"/><TransportStatus val=\"OK\"/>"
                            + "<AVTransportURI val=\"" + esc(DlnaBridge.uri) + "\"/><CurrentTrackDuration val=\"" + hms(DlnaBridge.durMs) + "\"/></InstanceID></Event>")
                            + "</LastChange></e:property></e:propertyset>";
                else
                    props = "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange>"
                            + esc("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\"><Volume channel=\"Master\" val=\"" + DlnaBridge.volume + "\"/><Mute channel=\"Master\" val=\"" + (DlnaBridge.muted ? 1 : 0) + "\"/></InstanceID></Event>")
                            + "</LastChange></e:property></e:propertyset>";
                URL u = new URL(s[0]);
                Socket so = new Socket();
                so.connect(new InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 80), 3000);
                so.setSoTimeout(3000);
                byte[] b = props.getBytes("UTF-8");
                String h = "NOTIFY " + (u.getFile().isEmpty() ? "/" : u.getFile()) + " HTTP/1.1\r\nHOST: " + u.getHost() + ":" + (u.getPort() > 0 ? u.getPort() : 80)
                        + "\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: " + sid + "\r\nSEQ: " + (eventSeq++) + "\r\nCONTENT-LENGTH: " + b.length + "\r\nConnection: close\r\n\r\n";
                OutputStream o = so.getOutputStream();
                o.write(h.getBytes("UTF-8")); o.write(b); o.flush();
                try { so.getInputStream().read(new byte[256]); } catch (Exception ignored) { }
                so.close();
            } catch (Exception e) { subs.remove(sid); }
        } });
    }
}
