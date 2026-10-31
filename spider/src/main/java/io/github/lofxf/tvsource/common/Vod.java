package io.github.lofxf.tvsource.common;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/** Builders for the CatVod / TVBox JSON result format. */
public final class Vod {
    private Vod() {}

    public static JSONObject item(String id, String name, String pic, String remarks) {
        JSONObject o = new JSONObject();
        try {
            o.put("vod_id", id);
            o.put("vod_name", name == null ? "" : name);
            o.put("vod_pic", pic == null ? "" : pic);
            o.put("vod_remarks", remarks == null ? "" : remarks);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static String classes(String[][] classes, List<JSONObject> list) {
        try {
            JSONArray arr = new JSONArray();
            for (String[] c : classes) {
                JSONObject o = new JSONObject();
                o.put("type_id", c[0]);
                o.put("type_name", c[1]);
                arr.put(o);
            }
            JSONObject r = new JSONObject();
            r.put("class", arr);
            r.put("filters", new JSONObject());
            if (list != null) r.put("list", new JSONArray(list));
            return r.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static String page(int page, boolean hasMore, List<JSONObject> list) {
        try {
            JSONObject r = new JSONObject();
            r.put("page", page);
            r.put("pagecount", hasMore ? page + 1 : page);
            r.put("limit", list.size());
            r.put("total", hasMore ? Integer.MAX_VALUE : list.size());
            r.put("list", new JSONArray(list));
            return r.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static String list(List<JSONObject> list) {
        try {
            JSONObject r = new JSONObject();
            r.put("list", new JSONArray(list));
            return r.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static String detail(JSONObject vod) {
        try {
            JSONObject r = new JSONObject();
            r.put("list", new JSONArray().put(vod));
            return r.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** playerContent result for a direct URL with request headers. */
    public static String play(String url, Map<String, String> headers) {
        try {
            JSONObject r = new JSONObject();
            r.put("parse", 0);
            r.put("jx", 0);
            r.put("url", url);
            if (headers != null && !headers.isEmpty()) r.put("header", new JSONObject(headers).toString());
            return r.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Safe text for the "name$url#name$url" play list format. */
    public static String clean(String s) {
        if (s == null) return "";
        return s.replace("$", " ").replace("#", " ").replace("\n", " ").trim();
    }

    public static String count(long n) {
        if (n >= 100000000L) return String.format(java.util.Locale.US, "%.1f亿", n / 100000000.0);
        if (n >= 10000L) return String.format(java.util.Locale.US, "%.1f万", n / 10000.0);
        return String.valueOf(n);
    }
}
