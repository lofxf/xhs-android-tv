package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;

import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import io.github.lofxf.tvsource.common.Http;
import io.github.lofxf.tvsource.common.QrLogin;
import io.github.lofxf.tvsource.common.Vod;
import io.github.lofxf.tvsource.xhs.XhsApi;

/** 小红书 video source for FongMi/TV / TVBox: {"type":3,"api":"csp_Xhs"}. */
public class Xhs extends Spider {

    private static final String[][] CHANNELS = {
            {"homefeed_recommend", "推荐"},
            {"homefeed.movie_and_tv_v3", "影视"},
            {"homefeed.food_v3", "美食"},
            {"homefeed.travel_v3", "旅行"},
            {"homefeed.gaming_v3", "游戏"},
            {"homefeed.fitness_v3", "运动健身"},
            {"homefeed.fashion_v3", "穿搭"},
            {"homefeed.cosmetics_v3", "美妆"},
            {"homefeed.career_v3", "职场"},
            {"homefeed.love_v3", "情感"},
            {"homefeed.household_product_v3", "家居"},
    };
    private static final int MIN_PAGE_VIDEOS = 12;

    private XhsApi api;
    private QrLogin login;
    private volatile XhsApi.User user;
    private final Map<String, String> cursors = new HashMap<String, String>();
    private final Map<String, Integer> noteIndex = new HashMap<String, Integer>();
    private final Map<String, String> searchIds = new HashMap<String, String>();
    private XhsApi.Qr qr;

    @Override
    public void init(Context context, String extend) throws Exception {
        api = new XhsApi(context);
        login = new QrLogin("xhs", "小红书", new QrLogin.Backend() {
            @Override
            public String create() throws Exception {
                qr = api.createQr();
                return qr.url;
            }

            @Override
            public int poll() throws Exception {
                int s = api.qrStatus(qr).codeStatus;
                if (s == 0) return QrLogin.WAITING;
                if (s == 1) return QrLogin.SCANNED;
                if (s == 2) return QrLogin.SUCCESS;
                return QrLogin.EXPIRED;
            }

            @Override
            public void onSuccess() {
                user = null;
            }
        });
    }

    private XhsApi.User user() {
        XhsApi.User u = user;
        if (u == null) {
            try {
                u = api.me();
                user = u;
            } catch (Exception e) {
                return new XhsApi.User();
            }
        }
        return u;
    }

    @Override
    public String homeContent(boolean filter) {
        String[][] classes = new String[CHANNELS.length + 1][];
        System.arraycopy(CHANNELS, 0, classes, 0, CHANNELS.length);
        classes[CHANNELS.length] = new String[]{"login", user().guest ? "扫码登录" : "账号"};
        return Vod.classes(classes, null);
    }

    @Override
    public String homeVideoContent() {
        try {
            return Vod.list(toVods(api.homefeed("homefeed_recommend", "", 0).notes));
        } catch (Exception e) {
            return Vod.list(new ArrayList<JSONObject>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        int page = parsePage(pg);
        if ("login".equals(tid)) return Vod.page(1, false, accountItems());
        try {
            synchronized (this) {
                if (page == 1) {
                    cursors.remove(tid);
                    noteIndex.remove(tid);
                }
                List<XhsApi.Note> videos = new ArrayList<XhsApi.Note>();
                boolean hasMore = true;
                for (int i = 0; i < 4 && videos.size() < MIN_PAGE_VIDEOS && hasMore; i++) {
                    Integer idx = noteIndex.get(tid);
                    XhsApi.FeedPage p = api.homefeed(tid, cursors.get(tid), idx == null ? 0 : idx);
                    cursors.put(tid, p.cursor);
                    noteIndex.put(tid, (idx == null ? 0 : idx) + p.rawCount);
                    for (XhsApi.Note n : p.notes) if (n.isVideo()) videos.add(n);
                    hasMore = p.hasMore;
                }
                return Vod.page(page, hasMore, toVods(videos));
            }
        } catch (Exception e) {
            return error(page, e);
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        if ("login".equals(id)) return login.detail();
        if ("logout".equals(id)) {
            api.logout();
            user = null;
            JSONObject vod = Vod.item("logout", "已退出登录", "", "");
            vod.put("vod_content", "已退出小红书账号，进入「扫码登录」分类可重新登录。");
            return Vod.detail(vod);
        }
        if (id.startsWith("msg:")) {
            JSONObject vod = Vod.item(id, "提示", "", "");
            vod.put("vod_content", id.substring(4));
            return Vod.detail(vod);
        }
        int at = id.indexOf('@');
        String noteId = at < 0 ? id : id.substring(0, at);
        String token = at < 0 ? "" : id.substring(at + 1);
        XhsApi.Detail d = api.detail(noteId, token);
        JSONObject vod = Vod.item(id, d.title, d.cover, d.likes.length() > 0 ? "♥ " + d.likes : "");
        vod.put("type_name", "小红书");
        vod.put("vod_actor", d.author);
        vod.put("vod_content", d.desc);
        StringBuilder from = new StringBuilder(), url = new StringBuilder();
        String name = Vod.clean(d.title).length() > 0 ? Vod.clean(d.title) : "播放";
        for (Map.Entry<String, List<String>> e : d.streams.entrySet()) {
            if (from.length() > 0) {
                from.append("$$$");
                url.append("$$$");
            }
            from.append(e.getKey());
            url.append(name).append('$').append(e.getValue().get(0));
        }
        if (from.length() == 0) throw new Exception("该笔记没有可播放的视频");
        vod.put("vod_play_from", from.toString());
        vod.put("vod_play_url", url.toString());
        return Vod.detail(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        int page = parsePage(pg);
        try {
            String sid;
            synchronized (this) {
                sid = searchIds.get(key);
                if (sid == null || page == 1) {
                    sid = searchId();
                    searchIds.put(key, sid);
                }
            }
            XhsApi.FeedPage p = api.search(key, page, sid);
            List<XhsApi.Note> videos = new ArrayList<XhsApi.Note>();
            for (XhsApi.Note n : p.notes) if (n.isVideo() || n.type.length() == 0) videos.add(n);
            return Vod.page(page, p.hasMore, toVods(videos));
        } catch (Exception e) {
            return Vod.page(page, false, new ArrayList<JSONObject>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", Http.DESKTOP_UA);
        h.put("Referer", XhsApi.ORIGIN + "/");
        return Vod.play(id, h);
    }

    @Override
    public boolean isVideoFormat(String url) {
        return url.contains("xhscdn.com");
    }

    private List<JSONObject> accountItems() {
        List<JSONObject> list = new ArrayList<JSONObject>();
        user = null; // always refresh when the account category is opened
        XhsApi.User u = user();
        if (!u.guest) {
            list.add(Vod.item("msg:当前已登录：" + u.nickname, u.nickname, u.avatar, "已登录"));
            list.add(Vod.item("logout", "退出登录", "", ""));
        } else {
            list.add(login.item());
        }
        return list;
    }

    private String error(int page, Exception e) {
        List<JSONObject> list = new ArrayList<JSONObject>();
        if (e instanceof XhsApi.NotLoggedInException) list.add(login.item());
        else list.add(Vod.item("msg:" + e.getMessage(), "加载失败", "", e.getMessage()));
        return Vod.page(page, false, list);
    }

    private static List<JSONObject> toVods(List<XhsApi.Note> notes) {
        List<JSONObject> list = new ArrayList<JSONObject>();
        for (XhsApi.Note n : notes) {
            String remarks = n.likes.length() > 0 ? "♥ " + n.likes : n.author;
            list.add(Vod.item(n.id + "@" + n.xsecToken, n.title.length() > 0 ? n.title : n.author, n.cover, remarks));
        }
        return list;
    }

    private static String searchId() {
        BigInteger v = BigInteger.valueOf(System.currentTimeMillis()).shiftLeft(64)
                .add(BigInteger.valueOf(new Random().nextInt(Integer.MAX_VALUE)));
        return v.toString(36);
    }

    static int parsePage(String pg) {
        try {
            return Math.max(1, Integer.parseInt(pg));
        } catch (Exception e) {
            return 1;
        }
    }
}
