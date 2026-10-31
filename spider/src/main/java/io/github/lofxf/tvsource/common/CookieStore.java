package io.github.lofxf.tvsource.common;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Simple persistent name=value cookie store, one per source. */
public final class CookieStore {

    private final SharedPreferences prefs;

    public CookieStore(Context context, String name) {
        prefs = context.getSharedPreferences("tvsource_cookies_" + name, Context.MODE_PRIVATE);
    }

    public synchronized String get(String name) {
        return prefs.getString(name, "");
    }

    public synchronized void put(String name, String value) {
        prefs.edit().putString(name, value).apply();
    }

    public synchronized void clear() {
        prefs.edit().clear().apply();
    }

    /** Stores every cookie from a list of Set-Cookie header values. */
    public synchronized void putSetCookies(List<String> setCookies) {
        SharedPreferences.Editor ed = prefs.edit();
        for (String sc : setCookies) {
            String pair = sc.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (value.isEmpty() || sc.toLowerCase().contains("max-age=0")) ed.remove(name);
            else ed.putString(name, value);
        }
        ed.apply();
    }

    /** Cookie request header value. */
    public synchronized String header() {
        StringBuilder sb = new StringBuilder();
        Map<String, ?> all = new TreeMap<String, Object>(prefs.getAll());
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
