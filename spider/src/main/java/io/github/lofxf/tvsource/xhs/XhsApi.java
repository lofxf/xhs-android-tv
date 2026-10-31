package io.github.lofxf.tvsource.xhs;

import android.content.Context;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.lofxf.tvsource.common.Http;

/** Xiaohongshu web API (edith.xiaohongshu.com). */
public final class XhsApi {

    public static final String ORIGIN = "https://www.xiaohongshu.com";
    private static final String API = "https://edith.xiaohongshu.com";

    public static class NotLoggedInException extends Exception {
        NotLoggedInException() { super("小红书未登录，请先到「扫码登录」分类扫码"); }
    }

    public static final class Note {
        public String id, xsecToken, title, cover, author, likes, type;
        public boolean isVideo() { return "video".equals(type); }
    }

    public static final class FeedPage {
        public final List<Note> notes = new ArrayList<Note>();
        public String cursor = "";
        public int rawCount;
        public boolean hasMore;
    }

    public static final class Detail {
        public String id, title, desc, cover, author, likes;
        /** Quality / codec name -> stream URLs (first is preferred). */
        public final LinkedHashMap<String, List<String>> streams = new LinkedHashMap<String, List<String>>();
    }

    public static final class Qr {
        public String qrId, code, url;
    }

    public static final class QrStatus {
        /** 0 = waiting, 1 = scanned, 2 = confirmed, other = expired / error. */
        public int codeStatus = -1;
    }

    public static final class User {
        public boolean guest = true;
        public String nickname = "", avatar = "", userId = "";
    }

    private final XhsSigner signer;

    public XhsApi(Context context) {
        signer = new XhsSigner(context);
        signer.start();
    }

    // ---------------------------------------------------------------- account

    public User me() throws Exception {
        JSONObject d = get("/api/sns/web/v2/user/me", null);
        User u = new User();
        u.guest = d.optBoolean("guest", true);
        u.nickname = d.optString("nickname");
        u.avatar = d.optString("images");
        u.userId = d.optString("user_id");
        return u;
    }

    public Qr createQr() throws Exception {
        JSONObject body = new JSONObject();
        body.put("qr_type", 1);
        JSONObject d = post("/api/sns/web/v1/login/qrcode/create", body);
        Qr qr = new Qr();
        qr.qrId = d.optString("qr_id");
        qr.code = d.optString("code");
        qr.url = d.optString("url");
        return qr;
    }

    public QrStatus qrStatus(Qr qr) throws Exception {
        Map<String, String> q = new LinkedHashMap<String, String>();
        q.put("qr_id", qr.qrId);
        q.put("code", qr.code);
        JSONObject d = get("/api/sns/web/v1/login/qrcode/status", q);
        QrStatus s = new QrStatus();
        s.codeStatus = d.optInt("code_status", -1);
        JSONObject info = d.optJSONObject("login_info");
        String session = info == null ? "" : info.optString("session");
        if (s.codeStatus == 2) {
            if (session.length() > 0) setCookie("web_session", session);
            CookieManager.getInstance().flush();
            signer.reload();
        }
        return s;
    }

    public void logout() {
        setCookie("web_session", "");
        CookieManager.getInstance().flush();
        signer.reload();
    }

    // ---------------------------------------------------------------- content

    public FeedPage homefeed(String category, String cursor, int noteIndex) throws Exception {
        JSONObject b = new JSONObject();
        b.put("cursor_score", cursor == null ? "" : cursor);
        b.put("num", 30);
        b.put("refresh_type", 1);
        b.put("note_index", noteIndex);
        b.put("unread_begin_note_id", "");
        b.put("unread_end_note_id", "");
        b.put("unread_note_count", 0);
        b.put("category", category);
        b.put("search_key", "");
        b.put("need_num", 10);
        b.put("image_formats", new JSONArray().put("jpg").put("webp").put("avif"));
        b.put("need_filter_image", false);
        JSONObject d = post("/api/sns/web/v1/homefeed", b);
        FeedPage page = parseItems(d.optJSONArray("items"));
        page.cursor = d.optString("cursor_score");
        page.hasMore = page.rawCount > 0;
        return page;
    }

    public FeedPage search(String keyword, int pageNo, String searchId) throws Exception {
        JSONObject b = new JSONObject();
        b.put("keyword", keyword);
        b.put("page", pageNo);
        b.put("page_size", 20);
        b.put("search_id", searchId);
        b.put("sort", "general");
        b.put("note_type", 1); // 1 = video only
        JSONObject d = post("/api/sns/web/v1/search/notes", b);
        FeedPage page = parseItems(d.optJSONArray("items"));
        page.hasMore = d.optBoolean("has_more", page.rawCount > 0);
        return page;
    }

    public Detail detail(String noteId, String xsecToken) throws Exception {
        JSONObject b = new JSONObject();
        b.put("source_note_id", noteId);
        b.put("image_formats", new JSONArray().put("jpg").put("webp").put("avif"));
        b.put("extra", new JSONObject().put("need_body_topic", "1"));
        b.put("xsec_source", "pc_feed");
        b.put("xsec_token", xsecToken == null ? "" : xsecToken);
        JSONObject d = post("/api/sns/web/v1/feed", b);
        JSONArray items = d.optJSONArray("items");
        if (items == null || items.length() == 0) throw new Exception("笔记不存在或已删除");
        JSONObject card = items.getJSONObject(0).optJSONObject("note_card");
        if (card == null) throw new Exception("笔记不存在或已删除");
        Detail det = new Detail();
        det.id = noteId;
        det.title = card.optString("title");
        det.desc = card.optString("desc");
        JSONObject user = card.optJSONObject("user");
        det.author = user == null ? "" : first(user.optString("nickname"), user.optString("nick_name"));
        JSONObject interact = card.optJSONObject("interact_info");
        det.likes = interact == null ? "" : interact.optString("liked_count");
        JSONArray images = card.optJSONArray("image_list");
        if (images != null && images.length() > 0) {
            JSONObject img = images.getJSONObject(0);
            det.cover = first(img.optString("url_default"), img.optString("url_pre"), img.optString("url"));
        }
        JSONObject video = card.optJSONObject("video");
        if (video != null) {
            JSONObject stream = video.optJSONObject("media") == null ? null
                    : video.optJSONObject("media").optJSONObject("stream");
            if (stream != null) {
                addStreams(det, "H264", stream.optJSONArray("h264"));
                addStreams(det, "H265", stream.optJSONArray("h265"));
                addStreams(det, "AV1", stream.optJSONArray("av1"));
            }
            JSONObject consumer = video.optJSONObject("consumer");
            String key = consumer == null ? "" : consumer.optString("origin_video_key");
            if (key.length() > 0) {
                List<String> l = new ArrayList<String>();
                l.add("https://sns-video-bd.xhscdn.com/" + key);
                det.streams.put("原画", l);
            }
        }
        if (det.title.length() == 0) det.title = det.desc.length() > 30 ? det.desc.substring(0, 30) : det.desc;
        return det;
    }

    private static void addStreams(Detail det, String codec, JSONArray arr) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            if (s == null) continue;
            List<String> urls = new ArrayList<String>();
            String master = s.optString("master_url");
            if (master.length() > 0) urls.add(master);
            JSONArray backups = s.optJSONArray("backup_urls");
            if (backups != null) for (int k = 0; k < backups.length(); k++) urls.add(backups.optString(k));
            if (urls.isEmpty()) continue;
            int height = Math.min(s.optInt("width"), s.optInt("height"));
            String name = codec + (height > 0 ? " " + height + "P" : "");
            while (det.streams.containsKey(name)) name = name + "+";
            det.streams.put(name, urls);
        }
    }

    private static FeedPage parseItems(JSONArray items) {
        FeedPage page = new FeedPage();
        if (items == null) return page;
        page.rawCount = items.length();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            JSONObject card = it.optJSONObject("note_card");
            if (card == null) continue;
            Note n = new Note();
            n.id = first(it.optString("id"), card.optString("note_id"));
            n.xsecToken = first(it.optString("xsec_token"), card.optString("xsec_token"));
            n.type = card.optString("type");
            n.title = first(card.optString("display_title"), card.optString("title"));
            JSONObject cover = card.optJSONObject("cover");
            if (cover != null) {
                n.cover = first(cover.optString("url_default"), cover.optString("url_pre"), cover.optString("url"));
                if (n.cover.length() == 0) {
                    JSONArray info = cover.optJSONArray("info_list");
                    if (info != null && info.length() > 0) n.cover = info.optJSONObject(info.length() - 1).optString("url");
                }
            }
            JSONObject user = card.optJSONObject("user");
            n.author = user == null ? "" : first(user.optString("nickname"), user.optString("nick_name"));
            JSONObject interact = card.optJSONObject("interact_info");
            n.likes = interact == null ? "" : interact.optString("liked_count");
            if (n.id.length() > 0) page.notes.add(n);
        }
        return page;
    }

    // ---------------------------------------------------------------- transport

    private JSONObject get(String path, Map<String, String> query) throws Exception {
        StringBuilder uri = new StringBuilder(path);
        if (query != null && !query.isEmpty()) {
            char sep = '?';
            for (Map.Entry<String, String> e : query.entrySet()) {
                uri.append(sep).append(e.getKey()).append('=').append(Http.encode(e.getValue()));
                sep = '&';
            }
        }
        XhsSigner.Signature sig = signer.sign(uri.toString(), null);
        return check(Http.get(API + uri, headers(sig)));
    }

    private JSONObject post(String path, JSONObject body) throws Exception {
        XhsSigner.Signature sig = signer.sign(path, body.toString());
        Map<String, String> h = headers(sig);
        h.put("Content-Type", "application/json;charset=UTF-8");
        // Send exactly the JSON string that was signed.
        return check(Http.post(API + path, h, sig.body != null ? sig.body : body.toString()));
    }

    private Map<String, String> headers(XhsSigner.Signature sig) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", Http.DESKTOP_UA);
        h.put("Origin", ORIGIN);
        h.put("Referer", ORIGIN + "/");
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Cookie", cookieHeader());
        h.put("x-s", sig.xs);
        h.put("x-t", sig.xt);
        h.put("x-s-common", XsCommon.build(cookie("a1"), sig.b1, sig.xs, sig.xt));
        return h;
    }

    private JSONObject check(Http.Response r) throws Exception {
        for (String sc : r.header("Set-Cookie")) CookieManager.getInstance().setCookie(ORIGIN, sc);
        if (r.code == 461 || r.code == 471) throw new Exception("小红书触发了安全验证，请稍后再试或在手机端打开一次小红书");
        JSONObject o;
        try {
            o = new JSONObject(r.body);
        } catch (Exception e) {
            throw new Exception("小红书接口返回异常 (HTTP " + r.code + ")");
        }
        if (!o.optBoolean("success", false)) {
            int code = o.optInt("code");
            if (code == -100 || code == -101 || code == -104) throw new NotLoggedInException();
            String msg = o.optString("msg");
            throw new Exception(msg.length() > 0 ? "小红书: " + msg : "小红书接口错误 (" + code + ")");
        }
        JSONObject data = o.optJSONObject("data");
        return data == null ? new JSONObject() : data;
    }

    private static String cookieHeader() {
        String c = CookieManager.getInstance().getCookie(ORIGIN);
        return c == null ? "" : c;
    }

    private static String cookie(String name) {
        for (String part : cookieHeader().split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return "";
    }

    private static void setCookie(String name, String value) {
        String c = name + "=" + value + "; domain=.xiaohongshu.com; path=/"
                + (value.length() == 0 ? "; max-age=0" : "; max-age=31536000");
        CookieManager.getInstance().setCookie(ORIGIN, c);
    }

    static String first(String... values) {
        for (String v : values) if (v != null && v.length() > 0 && !"null".equals(v)) return v;
        return "";
    }
}
