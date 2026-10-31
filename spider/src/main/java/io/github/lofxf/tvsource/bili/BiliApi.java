package io.github.lofxf.tvsource.bili;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.lofxf.tvsource.common.CookieStore;
import io.github.lofxf.tvsource.common.Http;

/** Bilibili web API with WBI signing and cookie (SESSDATA) persistence. */
public final class BiliApi {

    public static final String REFERER = "https://www.bilibili.com/";
    private static final String API = "https://api.bilibili.com";
    private static final int[] MIXIN = {46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52};

    public static final class Video {
        public String bvid, title, cover, author, remarks;
    }

    public static final class Page {
        public final List<Video> videos = new ArrayList<Video>();
        public boolean hasMore;
    }

    public static final class Detail {
        public String bvid, title, cover, author, desc, remarks, type;
        /** "part name" -> cid. */
        public final List<String[]> parts = new ArrayList<String[]>();
    }

    public static final class User {
        public boolean login;
        public String name = "", face = "";
        public int vipStatus;
    }

    private final CookieStore cookies;
    private String mixinKey;
    private long mixinKeyTime;
    private String qrKey;

    public BiliApi(Context context) {
        cookies = new CookieStore(context, "bili");
    }

    // ---------------------------------------------------------------- account

    public User nav() throws Exception {
        JSONObject o = request(API + "/x/web-interface/nav", false);
        JSONObject d = o.optJSONObject("data");
        User u = new User();
        if (d != null) {
            u.login = d.optBoolean("isLogin");
            u.name = d.optString("uname");
            u.face = d.optString("face");
            u.vipStatus = d.optInt("vipStatus");
            updateMixinKey(d);
        }
        return u;
    }

    public String createQr() throws Exception {
        JSONObject d = data(request("https://passport.bilibili.com/x/passport-login/web/qrcode/generate", true));
        qrKey = d.optString("qrcode_key");
        return d.optString("url");
    }

    /** @return 0 waiting, 1 scanned, 2 success, 3 expired */
    public int pollQr() throws Exception {
        JSONObject d = data(request("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key="
                + Http.encode(qrKey), true));
        int code = d.optInt("code");
        if (code == 0) {
            // Cookies are also delivered in the redirect URL query.
            String url = d.optString("url");
            int q = url.indexOf('?');
            if (q >= 0) {
                for (String kv : url.substring(q + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String k = kv.substring(0, eq);
                    if (k.equals("SESSDATA") || k.equals("bili_jct") || k.equals("DedeUserID")
                            || k.equals("DedeUserID__ckMd5")) {
                        cookies.put(k, kv.substring(eq + 1));
                    }
                }
            }
            String refresh = d.optString("refresh_token");
            if (refresh.length() > 0) cookies.put("ac_time_value", refresh);
            return 2;
        }
        if (code == 86090) return 1;
        if (code == 86101) return 0;
        return 3;
    }

    public void logout() {
        cookies.clear();
        mixinKey = null;
    }

    // ---------------------------------------------------------------- content

    public Page recommend(int page) throws Exception {
        Map<String, String> p = new TreeMap<String, String>();
        p.put("ps", "20");
        p.put("fresh_idx", String.valueOf(page));
        p.put("fresh_idx_1h", String.valueOf(page));
        p.put("fresh_type", "4");
        p.put("feed_version", "V8");
        p.put("brush", String.valueOf(page));
        JSONObject d = data(request(API + "/x/web-interface/wbi/index/top/feed/rcmd?" + wbi(p), false));
        Page r = new Page();
        JSONArray items = d.optJSONArray("item");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || !"av".equals(it.optString("goto")) || it.optString("bvid").length() == 0) continue;
                r.videos.add(video(it));
            }
        }
        r.hasMore = true;
        return r;
    }

    public Page popular(int page) throws Exception {
        JSONObject d = data(request(API + "/x/web-interface/popular?ps=20&pn=" + page, false));
        Page r = new Page();
        addList(r, d.optJSONArray("list"));
        r.hasMore = !d.optBoolean("no_more", false);
        return r;
    }

    public Page ranking(String rid) throws Exception {
        Map<String, String> p = new TreeMap<String, String>();
        p.put("rid", rid);
        p.put("type", "all");
        JSONObject d = data(request(API + "/x/web-interface/ranking/v2?" + wbi(p), false));
        Page r = new Page();
        addList(r, d.optJSONArray("list"));
        r.hasMore = false;
        return r;
    }

    public Page search(String keyword, int page) throws Exception {
        Map<String, String> p = new TreeMap<String, String>();
        p.put("search_type", "video");
        p.put("keyword", keyword);
        p.put("page", String.valueOf(page));
        JSONObject d = data(request(API + "/x/web-interface/wbi/search/type?" + wbi(p), false));
        Page r = new Page();
        JSONArray arr = d.optJSONArray("result");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null || it.optString("bvid").length() == 0) continue;
                Video v = new Video();
                v.bvid = it.optString("bvid");
                v.title = stripTags(it.optString("title"));
                v.cover = fixUrl(it.optString("pic"));
                v.author = it.optString("author");
                v.remarks = it.optString("duration");
                r.videos.add(v);
            }
        }
        r.hasMore = page < d.optInt("numPages", page);
        return r;
    }

    public Detail detail(String bvid) throws Exception {
        JSONObject d = data(request(API + "/x/web-interface/view?bvid=" + Http.encode(bvid), false));
        Detail det = new Detail();
        det.bvid = bvid;
        det.title = d.optString("title");
        det.cover = fixUrl(d.optString("pic"));
        det.desc = d.optString("desc");
        det.type = d.optString("tname");
        JSONObject owner = d.optJSONObject("owner");
        det.author = owner == null ? "" : owner.optString("name");
        JSONObject stat = d.optJSONObject("stat");
        det.remarks = stat == null ? "" : "▶ " + io.github.lofxf.tvsource.common.Vod.count(stat.optLong("view"));
        JSONArray pages = d.optJSONArray("pages");
        if (pages != null) {
            for (int i = 0; i < pages.length(); i++) {
                JSONObject pg = pages.optJSONObject(i);
                if (pg == null) continue;
                String part = pg.optString("part");
                det.parts.add(new String[]{part.length() > 0 ? part : "P" + (i + 1), String.valueOf(pg.optLong("cid"))});
            }
        }
        return det;
    }

    /** Direct (durl, mp4/flv) stream URL. */
    public String playUrl(String bvid, String cid) throws Exception {
        Map<String, String> p = new TreeMap<String, String>();
        p.put("bvid", bvid);
        p.put("cid", cid);
        p.put("qn", "116");
        p.put("fnval", "1");
        p.put("fnver", "0");
        p.put("fourk", "1");
        p.put("platform", "pc");
        JSONObject d = data(request(API + "/x/player/wbi/playurl?" + wbi(p), false));
        JSONArray durl = d.optJSONArray("durl");
        if (durl == null || durl.length() == 0) throw new Exception("哔哩哔哩: 无可用播放地址");
        return durl.getJSONObject(0).optString("url");
    }

    public Map<String, String> playHeaders() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", Http.DESKTOP_UA);
        h.put("Referer", REFERER);
        return h;
    }

    // ---------------------------------------------------------------- helpers

    private void addList(Page r, JSONArray list) {
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject it = list.optJSONObject(i);
            if (it != null && it.optString("bvid").length() > 0) r.videos.add(video(it));
        }
    }

    private static Video video(JSONObject it) {
        Video v = new Video();
        v.bvid = it.optString("bvid");
        v.title = it.optString("title");
        v.cover = fixUrl(it.optString("pic"));
        JSONObject owner = it.optJSONObject("owner");
        v.author = owner == null ? "" : owner.optString("name");
        JSONObject stat = it.optJSONObject("stat");
        long view = stat == null ? 0 : stat.optLong("view", stat.optLong("play"));
        v.remarks = view > 0 ? "▶ " + io.github.lofxf.tvsource.common.Vod.count(view) : v.author;
        return v;
    }

    private static String fixUrl(String u) {
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("http://")) return "https://" + u.substring(7);
        return u;
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "").replace("&quot;", "\"").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'");
    }

    private JSONObject request(String url, boolean passport) throws Exception {
        ensureBuvid();
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", Http.DESKTOP_UA);
        h.put("Referer", REFERER);
        h.put("Origin", "https://www.bilibili.com");
        h.put("Cookie", cookies.header());
        Http.Response r = Http.get(url, h);
        cookies.putSetCookies(r.header("Set-Cookie"));
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            throw new Exception("哔哩哔哩接口返回异常 (HTTP " + r.code + ")");
        }
    }

    private static JSONObject data(JSONObject o) throws Exception {
        int code = o.optInt("code");
        if (code != 0) {
            if (code == -101) throw new Exception("哔哩哔哩: 账号未登录");
            if (code == -412 || code == -352) throw new Exception("哔哩哔哩: 请求被风控拦截，请稍后再试或先扫码登录");
            String msg = o.optString("message");
            throw new Exception("哔哩哔哩: " + (msg.length() > 0 ? msg : "错误 " + code));
        }
        JSONObject d = o.optJSONObject("data");
        return d == null ? new JSONObject() : d;
    }

    private synchronized void ensureBuvid() {
        if (cookies.get("buvid3").length() > 0) return;
        try {
            Map<String, String> h = new HashMap<String, String>();
            h.put("User-Agent", Http.DESKTOP_UA);
            Http.Response r = Http.get(API + "/x/frontend/finger/spi", h);
            JSONObject d = new JSONObject(r.body).optJSONObject("data");
            if (d != null) {
                cookies.put("buvid3", d.optString("b_3"));
                cookies.put("buvid4", d.optString("b_4"));
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- WBI

    private void updateMixinKey(JSONObject navData) {
        JSONObject img = navData.optJSONObject("wbi_img");
        if (img == null) return;
        String raw = fileStem(img.optString("img_url")) + fileStem(img.optString("sub_url"));
        if (raw.length() < 64) return;
        StringBuilder sb = new StringBuilder();
        for (int i : MIXIN) sb.append(raw.charAt(i));
        mixinKey = sb.substring(0, 32);
        mixinKeyTime = System.currentTimeMillis();
    }

    private synchronized String mixinKey() throws Exception {
        if (mixinKey == null || System.currentTimeMillis() - mixinKeyTime > 3600_000L) {
            JSONObject o = request(API + "/x/web-interface/nav", false); // works (code -101) when logged out
            JSONObject d = o.optJSONObject("data");
            if (d != null) updateMixinKey(d);
        }
        if (mixinKey == null) throw new Exception("哔哩哔哩: 获取 WBI 密钥失败");
        return mixinKey;
    }

    /** Returns the signed query string for {@code params}. */
    private String wbi(Map<String, String> params) throws Exception {
        TreeMap<String, String> p = new TreeMap<String, String>(params);
        p.put("wts", String.valueOf(System.currentTimeMillis() / 1000));
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (q.length() > 0) q.append('&');
            String v = e.getValue().replaceAll("[!'()*]", "");
            q.append(Http.encode(e.getKey())).append('=').append(Http.encode(v));
        }
        String query = q.toString();
        return query + "&w_rid=" + md5(query + mixinKey());
    }

    private static String fileStem(String url) {
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.indexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
