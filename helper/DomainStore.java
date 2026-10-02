package com.example.domainpatch;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Scanner;
import org.json.JSONObject;

public class DomainStore {
    public static Context app;
    static final String RAW = "https://raw.githubusercontent.com/darknesslord19/Sancak/main/domains.json";
    static final String TOKEN = "";
    static final String ABOUT = "https://raw.githubusercontent.com/darknesslord19/Sancak/main/about.json";
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
                } catch (Throwable t) { }
            }
        } catch (Throwable t) { }
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
                    if (found != null) p.edit().putString(fk, found).apply();
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
            return null;
        } catch (Exception e) {
            return "Okunamadi: " + e.getMessage();
        }
    }
}
