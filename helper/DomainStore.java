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

    static String eff(String def, String name) {
        lastSrc = "varsayilan";
        try {
            SharedPreferences p = sp();
            if (p == null) { lastSrc = "varsayilan, ayar dosyasi yok"; return def; }
            String m = p.getString("m:" + id(def, name), "");
            if (m.length() > 0) { lastSrc = "manuel"; return same(def, m); }
            if (p.getBoolean("auto", true)) {
                if (nameKey(name).length() > 0) {
                    String f = p.getString("f:" + nameKey(name), "");
                    if (f.length() > 0) { lastSrc = "bulucu"; return same(def, f); }
                }
                String r = lookup(p.getString("remote", ""), def, name);
                if (r != null) { lastSrc = "domains.json"; return same(def, r); }
            }
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
            if (cur.length() == 0) return;
            String now = current();
            Object holder = Class.forName("com.lagradost.cloudstream3.APIHolder").getField("INSTANCE").get(null);
            Object lst = holder.getClass().getMethod("getAllProviders").invoke(holder);
            java.util.ArrayList<Object> copy = new java.util.ArrayList<Object>((java.util.Collection<?>) lst);
            int n = 0;
            for (Object api : copy) {
                try {
                    String mu = (String) api.getClass().getMethod("getMainUrl").invoke(api);
                    if (mu == null) continue;
                    String k = norm(mu);
                    if (k.equals(norm(cur)) || k.equals(norm(lastEff))) {
                        api.getClass().getMethod("setMainUrl", String.class).invoke(api, same(mu, now));
                        n++;
                    }
                } catch (Throwable t) { }
            }
            lastEff = now;
            if (n > 0) Hook.toast("Canli guncellendi: " + now);
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
                    String found = findIn(fetchText(page, ms), pat);
                    if (found != null) p.edit().putString(fk, found).apply();
                } catch (Throwable t) { }
            }
        } catch (Throwable t) { }
    }

    // once <a href> baglantilari, yoksa duz metin (eski adres metinde gecse bile bagliyi tercih eder)
    static String findIn(String body, String pat) {
        String r = firstHost(body, "(?i)href\\s*=\\s*[\"']https?://(" + pat + ")");
        if (r == null) r = firstHost(body, "(?i)(?:https?://)?(" + pat + ")");
        return r;
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
        if (TOKEN.length() > 0) c.setRequestProperty("Authorization", "token " + TOKEN);
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
            if (TOKEN.length() > 0) c.setRequestProperty("Authorization", "token " + TOKEN);
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
