package com.example.domainpatch;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Scanner;
import org.json.JSONArray;
import org.json.JSONObject;

public class DomainStore {
    public static Context app;
    static final String RAW = "https://raw.githubusercontent.com/darknesslord19/Sancak/main/domains.json";
    static final String TOKEN = "";
    static final String ABOUT = "";
    static final String UPDATE = "https://raw.githubusercontent.com/darknesslord19/Sancak/main/update.json";
    static final String PLUGIN = "DiziPal";
    static String cur = "";
    static String curName = "";
    static String lastEff = "";
    static String lastSrc = "";
    static String toasted = "";

    static Context ctx() {
        if (app != null) return app;
        try {
            Object o = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (o instanceof Context) app = (Context) o;
        } catch (Throwable t) { }
        return app;
    }

    static SharedPreferences sp() {
        Context c = ctx();
        return c == null ? null : c.getSharedPreferences("domain_prefs", 0);
    }

    static String norm(String u) {
        if (u == null) return "";
        u = u.trim().toLowerCase();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    // Eklenti adi: kucuk harf, ".cs3" uzantisi yok
    static String nameKey(String n) {
        if (n == null) return "";
        n = n.trim().toLowerCase();
        if (n.endsWith(".cs3")) n = n.substring(0, n.length() - 4);
        return n;
    }

    static String id(String def, String name) {
        String n = nameKey(name);
        return n.length() > 0 ? n : norm(def);
    }

    // Orijinal string sonunda "/" varsa aynisini koru
    static String same(String def, String v) {
        if (v == null) return def;
        v = v.trim();
        if (v.length() == 0) return def;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return def.endsWith("/") ? v + "/" : v;
    }

    public static String def() { return cur; }

    static final String UA = "Mozilla/5.0 (Android 14; Mobile)";
    static final java.util.LinkedHashMap<String, String[]> seen = new java.util.LinkedHashMap<String, String[]>();

    // taban adres: bulucu > domains.json > varsayilan
    static String baseOf(SharedPreferences p, String def, String name) {
        lastSrc = "varsayilan";
        if (p.getBoolean("auto", true)) {
            if (nameKey(name).length() > 0) {
                String f = p.getString("f:" + nameKey(name), "");
                if (f.length() > 0) { lastSrc = "bulucu"; return same(def, f); }
            }
            String r = lookup(p.getString("remote", ""), def, name);
            if (r != null) { lastSrc = "domains.json"; return same(def, r); }
        }
        return def;
    }

    static String eff(String def, String name) {
        lastSrc = "varsayilan";
        try {
            SharedPreferences p = sp();
            if (p == null) { lastSrc = "varsayilan, ayar dosyasi yok"; return def; }
            if (p.getBoolean("vpn", false) && !vpnActive()) { lastSrc = "vpn"; return same(def, BLOCKED); }
            String m = p.getString("m:" + id(def, name), "");
            if (m.length() > 0) { lastSrc = "manuel"; return same(def, m); }
            String base = baseOf(p, def, name);
            if (p.getBoolean("auto", true)) {
                // yonlendirme takibiyle ogrenilen adres: yalnizca taban hala ayni host ise gecerli
                String rd = p.getString("rd:" + id(def, name), "");
                int bar = rd.indexOf('|');
                if (bar > 0 && hostOf(base).equals(rd.substring(0, bar))) { lastSrc = "yonlendirme"; return same(def, rd.substring(bar + 1)); }
            }
            return base;
        } catch (Throwable t) { }
        return def;
    }

    // Yamali kod "eskiDomain@@EklentiAdi" gonderir
    public static String read(String arg) {
        String def = arg == null ? "" : arg;
        String name = "";
        int i = def.indexOf("@@");
        if (i >= 0) {
            name = def.substring(i + 2);
            def = def.substring(0, i);
        }
        cur = def;
        curName = name;
        String res = eff(def, name);
        lastEff = res;
        String key = id(def, name);
        synchronized (seen) { seen.put(key, new String[] { def, name, res }); }
        if (Hook.DEBUG && toasted.indexOf("|" + key + "|") < 0) {
            toasted += "|" + key + "|";
            Hook.toast(key + ": " + res + " (" + lastSrc + ")");
        }
        return res;
    }

    public static String current() { return eff(cur, curName); }

    // Yuklu saglayicilarin mainUrl degerini canli guncelle (en iyi caba, hata olursa sessiz)
    public static void applyLive() {
        try {
            Object holder = Class.forName("com.lagradost.cloudstream3.APIHolder").getField("INSTANCE").get(null);
            Object lst = holder.getClass().getMethod("getAllProviders").invoke(holder);
            java.util.ArrayList<Object> copy = new java.util.ArrayList<Object>((java.util.Collection<?>) lst);
            java.util.ArrayList<String[]> reg;
            synchronized (seen) { reg = new java.util.ArrayList<String[]>(seen.values()); }
            int n = 0;
            String last = "";
            for (String[] e : reg) {
                String now = eff(e[0], e[1]);
                for (Object api : copy) {
                    try {
                        String mu = (String) api.getClass().getMethod("getMainUrl").invoke(api);
                        if (mu == null) continue;
                        String k = norm(mu);
                        if (k.equals(norm(e[0])) || k.equals(norm(e[2]))) {
                            if (!norm(now).equals(k)) {
                                api.getClass().getMethod("setMainUrl", String.class).invoke(api, same(mu, now));
                                n++; last = now;
                            }
                        }
                    } catch (Throwable t) { }
                }
                e[2] = now;
            }
            if (n > 0) Hook.toast("Canli guncellendi: " + last);
        } catch (Throwable t) { }
    }

    // adresten host (kucuk harf, port ve yol yok)
    static String hostOf(String url) {
        if (url == null) return "";
        String u = url.trim();
        int i = u.indexOf("://");
        if (i >= 0) u = u.substring(i + 3);
        int e = u.length();
        for (int k = 0; k < u.length(); k++) {
            char ch = u.charAt(k);
            if (ch == '/' || ch == '?' || ch == '#') { e = k; break; }
        }
        u = u.substring(0, e);
        int at = u.lastIndexOf('@');
        if (at >= 0) u = u.substring(at + 1);
        int col = u.lastIndexOf(':');
        if (col >= 0 && u.indexOf(']') < col) u = u.substring(0, col);
        return u.toLowerCase();
    }

    // adresten host[:port] (kucuk harf, yol yok)
    static String authOf(String url) {
        if (url == null) return "";
        String u = url.trim();
        int i = u.indexOf("://");
        if (i >= 0) u = u.substring(i + 3);
        int e = u.length();
        for (int k = 0; k < u.length(); k++) {
            char ch = u.charAt(k);
            if (ch == '/' || ch == '?' || ch == '#') { e = k; break; }
        }
        u = u.substring(0, e);
        int at = u.lastIndexOf('@');
        if (at >= 0) u = u.substring(at + 1);
        return u.toLowerCase();
    }

    // gomulu repo token'i yalnizca GitHub adreslerine gider, ucuncu taraf sayfalara asla
    static boolean isGithub(String u) {
        String h = hostOf(u);
        return h.equals("raw.githubusercontent.com") || h.equals("api.github.com") || h.equals("github.com");
    }

    // domains.json kaydinda "follow": true ya da "follow": "regex" ise yonlendirme takibi acik
    // donus: null = kapali, "" = her host, aksi halde yeni host'un tam eslesmesi gereken desen
    static String followRule(String json, String def, String name) {
        try {
            if (json == null || json.length() == 0) return null;
            JSONObject o = new JSONObject(json);
            String wantName = nameKey(name), wantDef = norm(def);
            String byDef = null;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                boolean byName = wantName.length() > 0 && nameKey(k).equals(wantName);
                if (!byName && !norm(k).equals(wantDef)) continue;
                String rule = null;
                Object v = o.opt(k);
                if (v instanceof JSONObject) {
                    Object fo = ((JSONObject) v).opt("follow");
                    if (Boolean.TRUE.equals(fo)) rule = "";
                    else if (fo instanceof String && ((String) fo).length() > 0) rule = (String) fo;
                }
                if (byName) return rule;
                byDef = rule;
            }
            return byDef;
        } catch (Throwable t) { }
        return null;
    }

    // yonlendirmeleri elle izle (http<->https dahil), en fazla 6 adim: {sonUrl, durumKodu}
    static String[] resolve(String url, int ms) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(ms);
            c.setReadTimeout(ms);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.length() == 0) return new String[] { cur, String.valueOf(code) };
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            c.disconnect();
            return new String[] { cur, String.valueOf(code) };
        }
        return new String[] { cur, "0" };
    }

    // StreamCenter yontemi: kaynak siteye istek at; 2xx ile baska bir host'a yonlendirdiyse yeni host'u ogren.
    // Yalnizca domains.json kaydinda "follow" olan ve elle degistirilmemis domainlerde calisir.
    public static void runRedirects(int ms) {
        try {
            SharedPreferences p = sp();
            if (p == null || !p.getBoolean("auto", true)) return;
            String json = p.getString("remote", "");
            if (json.length() == 0) return;
            java.util.ArrayList<String[]> list;
            synchronized (seen) { list = new java.util.ArrayList<String[]>(seen.values()); }
            for (String[] e : list) {
                try {
                    String def = e[0], name = e[1], id = id(def, name);
                    String rule = followRule(json, def, name);
                    if (rule == null) continue;
                    if (p.getString("m:" + id, "").length() > 0) continue;
                    String base = baseOf(p, def, name);
                    String[] r = resolve(base, ms);
                    int code = Integer.parseInt(r[1]);
                    if (code < 200 || code >= 300) continue;
                    String fh = hostOf(r[0]), bh = hostOf(base);
                    if (fh.length() == 0) continue;
                    if (fh.equals(bh)) { p.edit().remove("rd:" + id).apply(); continue; }
                    if (rule.length() > 0 && !java.util.regex.Pattern.compile(rule, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(fh).matches()) continue;
                    String scheme = r[0].toLowerCase().startsWith("http://") ? "http" : "https";
                    p.edit().putString("rd:" + id, bh + "|" + scheme + "://" + authOf(r[0])).apply();
                    log("Yonlendirme: " + bh + " -> " + authOf(r[0]));
                } catch (Throwable t) { }
            }
        } catch (Throwable t) { }
    }

    static final String BLOCKED = "https://vpn-gerekli.invalid";
    public static Object pluginRef;
    static final java.util.ArrayList<String> LOG = new java.util.ArrayList<String>();
    static final java.util.HashMap<Object, java.util.List<?>> origHome = new java.util.HashMap<Object, java.util.List<?>>();
    static boolean vpnWatching = false;

    // ---- gunluk (bellekte son 200 satir) ----
    public static void log(String m) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        synchronized (LOG) {
            LOG.add(ts + "  " + m);
            while (LOG.size() > 200) LOG.remove(0);
        }
    }

    public static String logText() {
        StringBuilder b = new StringBuilder();
        synchronized (LOG) { for (String l : LOG) b.append(l).append('\n'); }
        return b.toString();
    }

    // ---- VPN ----
    static boolean vpnActive() {
        try {
            Object o = ctx().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (o instanceof android.net.ConnectivityManager) {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) o;
                android.net.Network n = cm.getActiveNetwork();
                if (n != null) {
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    return nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
                }
            }
        } catch (Throwable t) { }
        return false;
    }

    public static boolean vpnGuard() {
        SharedPreferences p = sp();
        return p != null && p.getBoolean("vpn", false);
    }

    public static void setVpnGuard(boolean b) {
        SharedPreferences p = sp();
        if (p != null) p.edit().putBoolean("vpn", b).apply();
        log("VPN korumasi " + (b ? "acildi" : "kapandi"));
        applyLive();
    }

    // VPN durumu degisince adresleri yeniden uygula (5 sn'de bir, yalnizca koruma aciksa is yapar)
    public static void startVpnWatch() {
        if (vpnWatching) return;
        vpnWatching = true;
        Thread th = new Thread(new Runnable() {
            public void run() {
                boolean last = vpnActive();
                while (true) {
                    try {
                        Thread.sleep(5000);
                        if (vpnGuard()) {
                            boolean now = vpnActive();
                            if (now != last) { last = now; log("VPN durumu degisti: " + (now ? "acik" : "kapali")); applyLive(); }
                        }
                    } catch (Throwable t) { }
                }
            }
        });
        th.setDaemon(true);
        th.start();
    }

    // ---- ana sayfa bolumleri (MainAPI.mainPage) ----
    static java.util.ArrayList<Object> myProviders() throws Exception {
        java.util.ArrayList<Object> out = new java.util.ArrayList<Object>();
        if (pluginRef == null) return out;
        ClassLoader cl = pluginRef.getClass().getClassLoader();
        Object holder = Class.forName("com.lagradost.cloudstream3.APIHolder").getField("INSTANCE").get(null);
        Object lst = holder.getClass().getMethod("getAllProviders").invoke(holder);
        for (Object api : new java.util.ArrayList<Object>((java.util.Collection<?>) lst))
            if (api != null && api.getClass().getClassLoader() == cl) out.add(api);
        return out;
    }

    static java.util.List<?> origOf(Object api) throws Exception {
        synchronized (origHome) {
            java.util.List<?> l = origHome.get(api);
            if (l == null) {
                Object cur = api.getClass().getMethod("getMainPage").invoke(api);
                if (cur instanceof java.util.List) {
                    l = new java.util.ArrayList<Object>((java.util.List<?>) cur);
                    origHome.put(api, l);
                }
            }
            return l;
        }
    }

    static String str(Object o, String getter) throws Exception {
        return String.valueOf(o.getClass().getMethod(getter).invoke(o));
    }

    static boolean homeOn(SharedPreferences p, String pn, String sn) {
        return p == null || !"0".equals(p.getString("h:" + nameKey(pn) + "|" + nameKey(sn), "1"));
    }

    public static String homeJson() {
        try {
            SharedPreferences p = sp();
            StringBuilder b = new StringBuilder("{\"ok\":true,\"providers\":[");
            boolean fp = true;
            int total = 0, on = 0;
            for (Object api : myProviders()) {
                java.util.List<?> orig = origOf(api);
                if (orig == null) continue;
                String pn = str(api, "getName");
                if (!fp) b.append(",");
                fp = false;
                b.append("{\"p\":").append(q(pn)).append(",\"s\":[");
                boolean fs = true;
                for (Object sec : orig) {
                    String sn = str(sec, "getName");
                    boolean en = homeOn(p, pn, sn);
                    if (!fs) b.append(",");
                    fs = false;
                    b.append("{\"n\":").append(q(sn)).append(",\"on\":").append(en ? "true" : "false").append("}");
                    total++;
                    if (en) on++;
                }
                b.append("]}");
            }
            return b.append("],\"total\":").append(total).append(",\"on\":").append(on).append("}").toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"err\":" + q(String.valueOf(t)) + "}";
        }
    }

    static boolean setMainPage(Object api, java.util.List<Object> v) throws Exception {
        for (Class<?> c = api.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getName().equals("mainPage") && java.util.List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    f.set(api, v);
                    return true;
                }
            }
        }
        return false;
    }

    // ana sayfa bolumleri: elle > update.json > eklentinin kendi bolumleri; ustune kategoriler eklenir
    public static void applyHome() {
        try {
            SharedPreferences p = sp();
            java.util.ArrayList<Object> provs = myProviders();
            if (provs.isEmpty()) return;
            Object api = provs.get(0);
            java.util.List<?> orig = origOf(api);
            if (orig == null) return;
            String pn = str(api, "getName");
            Object sample = orig.isEmpty() ? null : orig.get(0);
            java.util.ArrayList<Object> out = new java.util.ArrayList<Object>();
            if (sectionsEdited()) {
                for (String[] s : sectionList()) {
                    Object base = findOrig(orig, s[2].length() > 0 ? s[2] : s[0]);
                    out.add(makeSection(base != null ? base : sample, s[0], absUrl(api, s[1]), base != null && horiz(base)));
                }
            } else out.addAll(orig);
            for (String[] c : categoryList()) out.add(makeSection(sample, c[0], absUrl(api, c[1]), false));
            java.util.ArrayList<Object> keep = new java.util.ArrayList<Object>();
            int hidden = 0;
            for (Object sec : out) { if (homeOn(p, pn, str(sec, "getName"))) keep.add(sec); else hidden++; }
            if (setMainPage(api, keep)) log("Ana sayfa: " + keep.size() + " bolum" + (hidden > 0 ? ", " + hidden + " gizli" : ""));
        } catch (Throwable t) { log("Ana sayfa uygulanamadi: " + t); }
    }

    public static void setHomeSection(String pn, String sn, boolean on) {
        SharedPreferences p = sp();
        if (p == null) return;
        String k = "h:" + nameKey(pn) + "|" + nameKey(sn);
        if (on) p.edit().remove(k).apply(); else p.edit().putString(k, "0").apply();
        applyHome();
    }

    // ---- yedek / onbellek / sifirlama ----
    static boolean managed(String k) {
        return k.startsWith("m:") || k.startsWith("h:") || k.startsWith("o:") || k.equals("sec") || k.equals("cat") || k.equals("auto") || k.equals("vpn");
    }

    public static String exportJson() {
        SharedPreferences p = sp();
        StringBuilder b = new StringBuilder("{\"v\":1,\"data\":{");
        boolean first = true;
        if (p != null) {
            for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
                String k = e.getKey();
                if (!managed(k)) continue;
                Object v = e.getValue();
                if (!first) b.append(",");
                first = false;
                b.append(q(k)).append(":");
                if (v instanceof Boolean) b.append(((Boolean) v).booleanValue() ? "true" : "false");
                else b.append(q(String.valueOf(v)));
            }
        }
        return b.append("}}").toString();
    }

    // donus: uygulanan ayar sayisi, hata = -1
    public static int importJson(String s) {
        try {
            SharedPreferences p = sp();
            if (p == null) return -1;
            JSONObject o = new JSONObject(s);
            JSONObject d = o.optJSONObject("data");
            if (d == null) return -1;
            int n = 0;
            Iterator<String> it = d.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!managed(k)) continue;
                Object v = d.opt(k);
                if (v instanceof Boolean) p.edit().putBoolean(k, ((Boolean) v).booleanValue()).apply();
                else if (v instanceof String) p.edit().putString(k, (String) v).apply();
                else continue;
                n++;
            }
            log("Ayarlar iceri aktarildi: " + n);
            applyHome();
            applyLive();
            return n;
        } catch (Throwable t) { return -1; }
    }

    public static int clearCache() {
        SharedPreferences p = sp();
        if (p == null) return 0;
        int n = 0;
        for (String k : new java.util.ArrayList<String>(p.getAll().keySet())) {
            if (k.equals("remote") || k.equals("upd") || k.startsWith("f:") || k.startsWith("rd:")) { p.edit().remove(k).apply(); n++; }
        }
        log("Onbellek temizlendi: " + n);
        applyLive();
        return n;
    }

    public static void resetAll() {
        SharedPreferences p = sp();
        if (p != null) p.edit().clear().apply();
        SC.clear();
        updLoaded = false;
        log("Tum ayarlar sifirlandi");
        applyHome();
        applyLive();
    }

    // ================= ayarlanabilir sabitler + update.json =================
    static final java.util.concurrent.ConcurrentHashMap<String, String> SC = new java.util.concurrent.ConcurrentHashMap<String, String>();
    static final java.util.concurrent.ConcurrentHashMap<String, String> SEENS = new java.util.concurrent.ConcurrentHashMap<String, String>();
    static volatile JSONObject updEntry = null;
    static volatile java.util.HashMap<String, String> optMap = new java.util.HashMap<String, String>();
    static volatile boolean updLoaded = false;

    // yamali kod "orijinal@@@anahtar" gonderir; yanit: elle > update.json > orijinal
    public static String setting(String arg) {
        if (arg == null) return "";
        int i = arg.lastIndexOf("@@@");
        if (i < 0) return arg;
        String orig = arg.substring(0, i), key = arg.substring(i + 3);
        SEENS.put(key, orig);
        String c = SC.get(key);
        if (c != null) return c;
        String v = orig;
        try {
            SharedPreferences p = sp();
            String local = p == null ? "" : p.getString("o:" + key, "");
            if (local.length() > 0) v = local;
            else if (p == null || p.getBoolean("auto", true)) {
                String r = remoteOpt(key);
                if (r != null && r.length() > 0) v = r;
            }
        } catch (Throwable t) { }
        SC.put(key, v);
        return v;
    }

    static JSONObject pickEntry(JSONObject root) {
        String want = nameKey(PLUGIN);
        Iterator<String> it = root.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (want.length() > 0 && nameKey(k).equals(want)) {
                JSONObject e = root.optJSONObject(k);
                if (e != null) return e;
            }
        }
        if (root.opt("options") != null || root.opt("sections") != null || root.opt("categories") != null) return root;
        return null;
    }

    static void loadUpd() {
        updEntry = null;
        java.util.HashMap<String, String> m = new java.util.HashMap<String, String>();
        try {
            SharedPreferences p = sp();
            String raw = p == null ? "" : p.getString("upd", "");
            if (raw.length() > 0) {
                updEntry = pickEntry(new JSONObject(raw));
                JSONArray arr = updEntry == null ? null : updEntry.optJSONArray("options");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject x = arr.optJSONObject(i);
                    if (x != null) m.put(x.optString("key", ""), x.optString("value", ""));
                }
            }
        } catch (Throwable t) { }
        optMap = m;
        updLoaded = true;
    }

    static JSONObject entry() { if (!updLoaded) loadUpd(); return updEntry; }

    static String remoteOpt(String key) {
        if (!updLoaded) loadUpd();
        return optMap.get(key);
    }

    // null = basarili, aksi halde hata metni
    public static String fetchUpdate(int ms) {
        if (UPDATE.length() == 0) return "update.json linki tanimli degil";
        try {
            String u = UPDATE + (UPDATE.indexOf('?') >= 0 ? "&" : "?") + "t=" + System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setRequestProperty("User-Agent", UA);
            if (TOKEN.length() > 0 && isGithub(UPDATE)) c.setRequestProperty("Authorization", "token " + TOKEN);
            c.setConnectTimeout(ms);
            c.setReadTimeout(ms);
            Scanner s = new Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A");
            String body = s.hasNext() ? s.next() : "";
            s.close();
            new JSONObject(body);
            SharedPreferences p = sp();
            if (p == null) return "Uygulama baglami yok";
            p.edit().putString("upd", body).apply();
            updLoaded = false;
            SC.clear();
            log("update.json alindi (" + body.length() + " bayt)");
            return null;
        } catch (Exception e) {
            log("update.json okunamadi: " + e.getMessage());
            return "Okunamadi: " + e.getMessage();
        }
    }

    // ---- bolumler / kategoriler ----
    static String mainUrlOf(Object api) {
        try { String m = str(api, "getMainUrl"); while (m.endsWith("/")) m = m.substring(0, m.length() - 1); return "null".equals(m) ? "" : m; }
        catch (Throwable t) { return ""; }
    }

    static String relPath(String mu, String d) {
        if (d == null) return "";
        if (mu.length() > 0 && d.toLowerCase().startsWith(mu.toLowerCase())) { String r = d.substring(mu.length()); return r.length() == 0 ? "/" : r; }
        return d;
    }

    static String absUrl(Object api, String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.toLowerCase().startsWith("http://") || p.toLowerCase().startsWith("https://")) return p;
        if (!p.startsWith("/")) p = "/" + p;
        return mainUrlOf(api) + p;
    }

    static boolean horiz(Object s) {
        try { return Boolean.TRUE.equals(s.getClass().getMethod("getHorizontalImages").invoke(s)); } catch (Throwable t) { return false; }
    }

    static Object findOrig(java.util.List<?> orig, String name) throws Exception {
        for (Object o : orig) if (str(o, "getName").equals(name)) return o;
        return null;
    }

    static Object makeSection(Object sample, String name, String data, boolean h) throws Exception {
        Class<?> c = sample != null ? sample.getClass() : Class.forName("com.lagradost.cloudstream3.MainPageData");
        try { return c.getConstructor(String.class, String.class, boolean.class).newInstance(name, data, h); }
        catch (NoSuchMethodException e) { return c.getConstructor(String.class, String.class).newInstance(name, data); }
    }

    static JSONArray localArr(String k) {
        try {
            SharedPreferences p = sp();
            String s = p == null ? "" : p.getString(k, "");
            return s.length() == 0 ? null : parseArr(s);
        } catch (Throwable t) { return null; }
    }

    static JSONArray parseArr(String s) throws Exception {
        JSONObject o = new JSONObject("{\"a\":" + s + "}");
        return o.optJSONArray("a");
    }

    static java.util.ArrayList<String[]> toList(JSONArray a, boolean orig) {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            String n = x.optString("name", ""), p = x.optString("path", "");
            if (n.length() == 0 && p.length() == 0) continue;
            out.add(new String[] { n, p, orig ? x.optString("orig", "") : "", x.optBoolean("enabled", true) ? "1" : "0" });
        }
        return out;
    }

    static java.util.ArrayList<String[]> liveSections() {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<String[]>();
        try {
            java.util.ArrayList<Object> provs = myProviders();
            if (provs.isEmpty()) return out;
            Object api = provs.get(0);
            java.util.List<?> orig = origOf(api);
            if (orig == null) return out;
            String mu = mainUrlOf(api);
            for (Object s : orig) { String n = str(s, "getName"); out.add(new String[] { n, relPath(mu, str(s, "getData")), n, "1" }); }
        } catch (Throwable t) { }
        return out;
    }

    static java.util.ArrayList<String[]> remoteSections() {
        JSONObject e = entry();
        return toList(e == null ? null : e.optJSONArray("sections"), true);
    }

    static java.util.ArrayList<String[]> remoteCategories() {
        JSONObject e = entry();
        return toList(e == null ? null : e.optJSONArray("categories"), false);
    }

    static boolean sectionsEdited() { return localArr("sec") != null || !remoteSections().isEmpty(); }

    // elle > update.json > eklentinin kendi bolumleri
    static java.util.ArrayList<String[]> sectionList() {
        JSONArray loc = localArr("sec");
        if (loc != null) return toList(loc, true);
        java.util.ArrayList<String[]> rem = remoteSections();
        if (!rem.isEmpty()) {
            java.util.ArrayList<String[]> live = liveSections();
            for (String[] r : rem) {
                if (r[2].length() > 0) continue;
                for (String[] l : live) if (l[0].equals(r[0])) r[2] = l[0];
            }
            return rem;
        }
        return liveSections();
    }

    static java.util.ArrayList<String[]> baseSections() {
        java.util.ArrayList<String[]> rem = remoteSections();
        return rem.isEmpty() ? liveSections() : rem;
    }

    static java.util.ArrayList<String[]> categoryList() {
        JSONArray loc = localArr("cat");
        return loc != null ? toList(loc, false) : remoteCategories();
    }

    static boolean sameList(java.util.ArrayList<String[]> a, java.util.ArrayList<String[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
            if (!a.get(i)[0].trim().equals(b.get(i)[0].trim()) || !a.get(i)[1].trim().equals(b.get(i)[1].trim()) || ("1".equals(a.get(i).length > 3 ? a.get(i)[3] : "1") != "1".equals(b.get(i).length > 3 ? b.get(i)[3] : "1"))) return false;
        return true;
    }

    static String listJson(java.util.ArrayList<String[]> l, boolean orig) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) b.append(",");
            b.append("{\"name\":").append(q(l.get(i)[0])).append(",\"path\":").append(q(l.get(i)[1]));
            if (orig) b.append(",\"orig\":").append(q(l.get(i)[2]));
            b.append(",\"enabled\":").append("1".equals(l.get(i).length > 3 ? l.get(i)[3] : "1"));
            b.append("}");
        }
        return b.append("]").toString();
    }

    // ---- birincil domain (ana ekrandaki tek adres) ----
    static String[] primary() {
        synchronized (seen) { for (String[] e : seen.values()) return e; }
        return null;
    }

    public static String primaryEff() {
        String[] pr = primary();
        return pr == null ? current() : eff(pr[0], pr[1]);
    }

    public static void savePrimary(String u) {
        String[] pr = primary();
        if (pr == null) { setManual(u); return; }
        SharedPreferences p = sp();
        if (p == null) return;
        String base = baseOf(p, pr[0], pr[1]);
        if (u != null && norm(u).equals(norm(base))) setManualFor(id(pr[0], pr[1]), "");
        else setManualFor(id(pr[0], pr[1]), u);
    }

    public static String remoteForPrimary() {
        String[] pr = primary();
        SharedPreferences p = sp();
        if (pr == null || p == null) return remoteFor();
        return lookup(p.getString("remote", ""), pr[0], pr[1]);
    }

    // ---- popup icin ayar belgesi ----
    public static String settingsJson() {
        try {
            JSONObject e = entry();
            SharedPreferences p = sp();
            int ver = 0;
            try { ver = e == null ? 0 : Integer.parseInt(e.optString("version", "0").trim()); } catch (Throwable x) { }
            StringBuilder b = new StringBuilder("{\"version\":" + ver);
            b.append(",\"sections\":").append(listJson(sectionList(), true));
            b.append(",\"categories\":").append(listJson(categoryList(), false));
            b.append(",\"domains\":[");
            java.util.ArrayList<String[]> reg;
            synchronized (seen) { reg = new java.util.ArrayList<String[]>(seen.values()); }
            if (reg.size() > 1 && p != null) {
                boolean f = true;
                for (String[] d : reg) {
                    if (!f) b.append(",");
                    f = false;
                    String nm = d[1].length() > 0 ? d[1] : hostOf(d[0]);
                    int ci = nm.indexOf(':');
                    b.append("{\"id\":").append(q(id(d[0], d[1]))).append(",\"label\":").append(q(ci >= 0 ? nm.substring(ci + 1) : nm))
                     .append(",\"value\":").append(q(eff(d[0], d[1]))).append(",\"default\":").append(q(baseOf(p, d[0], d[1])))
                     .append(",\"enabled\":").append(p == null || !p.contains("en:d:" + id(d[0], d[1])) ? true : p.getBoolean("en:d:" + id(d[0], d[1]), true)).append("}");
                }
            }
            b.append("],\"options\":[");
            java.util.HashSet<String> done = new java.util.HashSet<String>();
            boolean fo = true;
            JSONArray arr = e == null ? null : e.optJSONArray("options");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null) continue;
                String k = x.optString("key", "");
                if (k.length() == 0 || !done.add(k)) continue;
                String def = x.optString("default", SEENS.containsKey(k) ? SEENS.get(k) : x.optString("value", ""));
                String val = p != null && p.getString("o:" + k, "").length() > 0 ? p.getString("o:" + k, "") : (x.optString("value", "").length() > 0 ? x.optString("value", "") : def);
                if (!fo) b.append(",");
                fo = false;
                b.append("{\"key\":").append(q(k)).append(",\"group\":").append(q(x.optString("group", "other"))).append(",\"label\":").append(q(x.optString("label", k)))
                 .append(",\"value\":").append(q(val)).append(",\"default\":").append(q(def))
                 .append(",\"enabled\":").append(p == null || !p.contains("en:" + k) ? x.optBoolean("enabled", true) : p.getBoolean("en:" + k, true)).append("}");
            }
            for (java.util.Map.Entry<String, String> s : SEENS.entrySet()) {
                if (!done.add(s.getKey())) continue;
                String val = p != null && p.getString("o:" + s.getKey(), "").length() > 0 ? p.getString("o:" + s.getKey(), "") : s.getValue();
                if (!fo) b.append(",");
                fo = false;
                b.append("{\"key\":").append(q(s.getKey())).append(",\"group\":\"other\",\"label\":").append(q("Sabit " + s.getKey()))
                 .append(",\"value\":").append(q(val)).append(",\"default\":").append(q(s.getValue())).append(",\"enabled\":true}");
            }
            return b.append("]}").toString();
        } catch (Throwable t) { log("Ayarlar okunamadi: " + t); return "{}"; }
    }

    // Ayarlar ekranındaki mevcut değerleri indirilen update.json kaydına da yazar.
    // Bu, uzak GitHub dosyasını değiştirmez; uygulamadaki son indirilen JSON kaydını günceller.
    public static boolean saveSettingsToUpdateJson(String json) {
        try {
            SharedPreferences p = sp();
            if (p == null) return false;
            JSONObject incoming = new JSONObject(json);
            JSONObject root = new JSONObject(p.getString("upd", "{}"));
            boolean rootSingle = root.has("sections") || root.has("categories") || root.has("options") || root.has("domains");
            String[] fields = new String[]{"version","sections","categories","domains","options"};
            if (rootSingle) {
                for (String f : fields) if (incoming.has(f)) root.put(f, incoming.get(f));
                p.edit().putString("upd", root.toString()).apply();
            } else {
                JSONObject target = pickEntry(root);
                if (target == null) target = new JSONObject();
                for (String f : fields) if (incoming.has(f)) target.put(f, incoming.get(f));
                String want = nameKey(PLUGIN);
                boolean placed = false;
                java.util.Iterator<String> it = root.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (nameKey(k).equals(want)) { root.put(k, target); placed = true; break; }
                }
                if (!placed) root.put(PLUGIN.length() > 0 ? PLUGIN : "plugin", target);
                p.edit().putString("upd", root.toString()).apply();
            }
            updLoaded = false;
            SC.clear();
            loadUpd();
            return true;
        } catch (Throwable t) { log("JSON kaydedilemedi: " + t); return false; }
    }

    public static boolean saveSettingsJson(String json) {
        try {
            SharedPreferences p = sp();
            if (p == null) return false;
            JSONObject o = new JSONObject(json);
            JSONArray opts = o.optJSONArray("options");
            int changed = 0;
            if (opts != null) for (int i = 0; i < opts.length(); i++) {
                JSONObject x = opts.optJSONObject(i);
                if (x == null) continue;
                String k = x.optString("key", ""), v = x.optString("value", ""), def = x.optString("default", "");
                if (k.length() == 0) continue;
                String rem = remoteOpt(k);
                String base = rem != null && rem.length() > 0 ? rem : def;
                if (v.length() == 0 || v.equals(base)) p.edit().remove("o:" + k).apply();
                else { p.edit().putString("o:" + k, v).apply(); changed++; }
                if (x.optBoolean("enabled", true)) p.edit().remove("en:" + k).apply();
                else p.edit().putBoolean("en:" + k, false).apply();
            }
            JSONArray doms = o.optJSONArray("domains");
            if (doms != null) for (int i = 0; i < doms.length(); i++) {
                JSONObject x = doms.optJSONObject(i);
                if (x == null) continue;
                String did = x.optString("id", ""), v = x.optString("value", "");
                String[] hit = null;
                synchronized (seen) { for (String[] d : seen.values()) if (id(d[0], d[1]).equals(did)) hit = d; }
                if (hit == null) continue;
                String base = baseOf(p, hit[0], hit[1]);
                if (v.length() == 0 || norm(v).equals(norm(base))) setManualFor(did, ""); else { setManualFor(did, v); changed++; }
                if (x.optBoolean("enabled", true)) p.edit().remove("en:d:" + did).apply();
                else p.edit().putBoolean("en:d:" + did, false).apply();
            }
            java.util.ArrayList<String[]> secs = toList(o.optJSONArray("sections"), true);
            if (o.optJSONArray("sections") != null) {
                if (sameList(secs, baseSections())) p.edit().remove("sec").apply();
                else { p.edit().putString("sec", listJson(secs, true)).apply(); changed++; }
            }
            java.util.ArrayList<String[]> cats = toList(o.optJSONArray("categories"), false);
            if (o.optJSONArray("categories") != null) {
                if (sameList(cats, remoteCategories())) p.edit().remove("cat").apply();
                else { p.edit().putString("cat", listJson(cats, false)).apply(); changed++; }
            }
            SC.clear();
            log("Ayarlar kaydedildi (" + changed + " degisiklik)");
            applyHome();
            applyLive();
            return true;
        } catch (Throwable t) { log("Ayarlar kaydedilemedi: " + t); return false; }
    }

    static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    // menu icin: bu eklentinin okudugu tum domainler (JSON dizi metni)
    public static String domainsJson() {
        StringBuilder b = new StringBuilder("[");
        java.util.ArrayList<String[]> reg;
        synchronized (seen) { reg = new java.util.ArrayList<String[]>(seen.values()); }
        SharedPreferences p = sp();
        boolean first = true;
        for (String[] e : reg) {
            String id = id(e[0], e[1]);
            String cur = eff(e[0], e[1]);
            String src = lastSrc;
            boolean man = p != null && p.getString("m:" + id, "").length() > 0;
            if (!first) b.append(",");
            first = false;
            b.append("{\"id\":").append(q(id)).append(",\"name\":").append(q(e[1].length() > 0 ? e[1] : hostOf(e[0])))
             .append(",\"def\":").append(q(e[0])).append(",\"cur\":").append(q(cur)).append(",\"src\":").append(q(src))
             .append(",\"manual\":").append(man ? "true" : "false").append("}");
        }
        return b.append("]").toString();
    }

    // tek bir domain icin elle adres (bos = otomatige don)
    public static void setManualFor(String idKey, String u) {
        SharedPreferences p = sp();
        if (p == null || idKey == null || idKey.length() == 0) return;
        u = u == null ? "" : u.trim();
        if (u.length() > 0 && !u.toLowerCase().startsWith("http")) u = "https://" + u;
        if (u.length() == 0) p.edit().remove("m:" + idKey).apply();
        else p.edit().putString("m:" + idKey, u).apply();
    }

    public static void clearManual() {
        SharedPreferences p = sp();
        if (p == null) return;
        java.util.ArrayList<String[]> reg;
        synchronized (seen) { reg = new java.util.ArrayList<String[]>(seen.values()); }
        for (String[] e : reg) p.edit().remove("m:" + id(e[0], e[1])).apply();
    }

    // ag bilgisi: VPN, DNS, cevrimici mi
    public static String netInfoJson() {
        boolean vpn = false, online = false;
        String dns = "";
        try {
            Object o = ctx().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (o instanceof android.net.ConnectivityManager) {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) o;
                android.net.Network n = cm.getActiveNetwork();
                if (n != null) {
                    online = true;
                    android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null) vpn = nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
                    android.net.LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null && lp.getDnsServers() != null && !lp.getDnsServers().isEmpty())
                        dns = lp.getDnsServers().get(0).getHostAddress();
                }
            }
        } catch (Throwable t) { }
        return "{\"vpn\":" + (vpn ? "true" : "false") + ",\"online\":" + (online ? "true" : "false") + ",\"dns\":" + q(dns) + "}";
    }

    // domains.json: once eklenti adi, yoksa eski domain anahtari
    static String lookup(String json, String def, String name) {
        try {
            if (json == null || json.length() == 0) return null;
            JSONObject o = new JSONObject(json);
            String wantName = nameKey(name);
            String wantDef = norm(def);
            String byDef = null;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = urlOf(o.opt(k));
                if (v.length() == 0) continue;
                if (wantName.length() > 0 && nameKey(k).equals(wantName)) return v;
                if (norm(k).equals(wantDef)) byDef = v;
            }
            return byDef;
        } catch (Throwable t) { }
        return null;
    }

    static String urlOf(Object v) {
        if (v == null) return "";
        if (v instanceof JSONObject) return ((JSONObject) v).optString("url", "");
        String s = String.valueOf(v);
        return "null".equals(s) ? "" : s;
    }

    // domains.json icindeki "finder" tanimlarini calistir: sayfadan guncel adresi bul
    public static void runFinders(int ms) {
        try {
            SharedPreferences p = sp();
            if (p == null) return;
            String json = p.getString("remote", "");
            if (json.length() == 0) return;
            JSONObject o = new JSONObject(json);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                String fk = "f:" + nameKey(k);
                JSONObject f = null;
                Object v = o.opt(k);
                if (v instanceof JSONObject) f = ((JSONObject) v).optJSONObject("finder");
                if (f == null) { p.edit().remove(fk).apply(); continue; }
                String page = f.optString("page", "");
                String pat = f.optString("pattern", "");
                if (page.length() == 0 || pat.length() == 0) continue;
                try {
                    String found = findIn(fetchText(page, ms), pat, Boolean.TRUE.equals(f.opt("text")));
                    if (found != null) { p.edit().putString(fk, found).apply(); log("Bulucu: " + k + " -> " + found); }
                } catch (Throwable t) { }
            }
        } catch (Throwable t) { }
    }

    // StreamCenter yontemi: sayfadaki <a href> baglantilarini sirayla gez, host'u kucuk harfe cevir,
    // desenle TAM eslesen ilk host'u https://host olarak dondur. textFallback ise duz metne de bakar.
    static String findIn(String body, String pat) { return findIn(body, pat, false); }

    static String findIn(String body, String pat, boolean textFallback) {
        try {
            java.util.regex.Pattern pp = java.util.regex.Pattern.compile(pat, java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)<a\\s[^>]*?href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(body);
            while (m.find()) {
                String href = m.group(1) != null ? m.group(1) : m.group(2);
                String hl = href.trim().toLowerCase();
                if (!hl.startsWith("http://") && !hl.startsWith("https://")) continue;
                String host = hostOf(href);
                if (host.length() > 0 && pp.matcher(host).matches()) return "https://" + host;
            }
        } catch (Throwable t) { }
        return textFallback ? firstHost(body, "(?i)(?:https?://)?(" + pat + ")") : null;
    }

    static String firstHost(String body, String rx) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(rx).matcher(body);
            while (m.find()) {
                String host = m.group(1).toLowerCase().replaceAll("[.-]+$", "");
                if (host.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}(:[0-9]+)?")) return "https://" + host;
            }
        } catch (Throwable t) { }
        return null;
    }

    // domains.json'daki kayit (yoksa null)
    public static String remoteFor() {
        SharedPreferences p = sp();
        if (p == null) return null;
        return lookup(p.getString("remote", ""), cur, curName);
    }

    public static boolean hasManual() {
        SharedPreferences p = sp();
        return p != null && p.getString("m:" + id(cur, curName), "").length() > 0;
    }

    public static void setManual(String u) {
        SharedPreferences p = sp();
        if (p == null || cur.length() == 0) return;
        u = u == null ? "" : u.trim();
        if (u.length() > 0 && !u.toLowerCase().startsWith("http")) u = "https://" + u;
        String key = "m:" + id(cur, curName);
        if (u.length() == 0) p.edit().remove(key).apply();
        else p.edit().putString(key, u).apply();
    }

    public static boolean auto() {
        SharedPreferences p = sp();
        return p == null || p.getBoolean("auto", true);
    }

    public static void setAuto(boolean b) {
        SharedPreferences p = sp();
        if (p != null) p.edit().putBoolean("auto", b).apply();
    }

    // Verilen adresten metin oku (about.json / about.txt)
    public static String fetchText(String url, int ms) throws Exception {
        String u = url + (url.indexOf('?') >= 0 ? "&" : "?") + "t=" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestProperty("User-Agent", UA);
        if (TOKEN.length() > 0 && isGithub(url)) c.setRequestProperty("Authorization", "token " + TOKEN);
        c.setConnectTimeout(ms);
        c.setReadTimeout(ms);
        Scanner s = new Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A");
        String body = s.hasNext() ? s.next() : "";
        s.close();
        if (body.length() > 20000) body = body.substring(0, 20000);
        return body;
    }

    // null = basarili, aksi halde hata metni
    public static String fetchRemote() { return fetchRemote(8000); }

    public static String fetchRemote(int ms) {
        if (RAW.length() == 0) return "domains.json linki tanimli degil";
        try {
            String u = RAW + (RAW.indexOf('?') >= 0 ? "&" : "?") + "t=" + System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            if (TOKEN.length() > 0 && isGithub(RAW)) c.setRequestProperty("Authorization", "token " + TOKEN);
            c.setConnectTimeout(ms);
            c.setReadTimeout(ms);
            Scanner s = new Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A");
            String body = s.hasNext() ? s.next() : "";
            s.close();
            new JSONObject(body);
            SharedPreferences p = sp();
            if (p == null) return "Uygulama baglami yok";
            p.edit().putString("remote", body).apply();
            log("domains.json alindi (" + body.length() + " bayt)");
            return null;
        } catch (Exception e) {
            log("domains.json okunamadi: " + e.getMessage());
            return "Okunamadi: " + e.getMessage();
        }
    }
}
