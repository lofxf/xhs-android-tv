package com.github.catvod.spider;

import android.content.Context;

import com.github.catvod.crawler.Spider;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import io.github.lofxf.tvsource.bili.BiliApi;
import io.github.lofxf.tvsource.common.QrLogin;
import io.github.lofxf.tvsource.common.Vod;

/** 哔哩哔哩 source for FongMi/TV / TVBox: {"type":3,"api":"csp_BiliWeb"}. */
public class BiliWeb extends Spider {

    private static final String[][] CLASSES = {
            {"rcmd", "推荐"}, {"popular", "热门"},
            {"rank:0", "全站排行"}, {"rank:1", "动画"}, {"rank:3", "音乐"}, {"rank:129", "舞蹈"},
            {"rank:4", "游戏"}, {"rank:36", "知识"}, {"rank:188", "科技"}, {"rank:234", "运动"},
            {"rank:223", "汽车"}, {"rank:160", "生活"}, {"rank:211", "美食"}, {"rank:217", "动物圈"},
            {"rank:119", "鬼畜"}, {"rank:155", "时尚"}, {"rank:5", "娱乐"}, {"rank:181", "影视"},
    };

    private BiliApi api;
    private QrLogin login;

    @Override
    public void init(Context context, String extend) throws Exception {
        api = new BiliApi(context);
        login = new QrLogin("bili", "哔哩哔哩", new QrLogin.Backend() {
            @Override
            public String create() throws Exception {
                return api.createQr();
            }

            @Override
            public int poll() throws Exception {
                return api.pollQr();
            }

            @Override
            public void onSuccess() {
            }
        });
    }

    private BiliApi.User user() {
        try {
            return api.nav();
        } catch (Exception e) {
            return new BiliApi.User();
        }
    }

    @Override
    public String homeContent(boolean filter) {
        String[][] classes = new String[CLASSES.length + 1][];
        System.arraycopy(CLASSES, 0, classes, 0, CLASSES.length);
        classes[CLASSES.length] = new String[]{"login", user().login ? "账号" : "扫码登录"};
        return Vod.classes(classes, null);
    }

    @Override
    public String homeVideoContent() {
        try {
            return Vod.list(toVods(api.recommend(1).videos));
        } catch (Exception e) {
            return Vod.list(new ArrayList<JSONObject>());
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        int page = Xhs.parsePage(pg);
        try {
            if ("login".equals(tid)) return Vod.page(1, false, accountItems());
            BiliApi.Page p;
            if ("rcmd".equals(tid)) p = api.recommend(page);
            else if ("popular".equals(tid)) p = api.popular(page);
            else if (tid.startsWith("rank:")) p = api.ranking(tid.substring(5));
            else p = new BiliApi.Page();
            return Vod.page(page, p.hasMore, toVods(p.videos));
        } catch (Exception e) {
            List<JSONObject> list = new ArrayList<JSONObject>();
            list.add(Vod.item("msg:" + e.getMessage(), "加载失败", "", e.getMessage()));
            return Vod.page(page, false, list);
        }
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        if ("login".equals(id)) return login.detail();
        if ("logout".equals(id)) {
            api.logout();
            JSONObject vod = Vod.item("logout", "已退出登录", "", "");
            vod.put("vod_content", "已退出哔哩哔哩账号，进入「扫码登录」分类可重新登录。");
            return Vod.detail(vod);
        }
        if (id.startsWith("msg:")) {
            JSONObject vod = Vod.item(id, "提示", "", "");
            vod.put("vod_content", id.substring(4));
            return Vod.detail(vod);
        }
        BiliApi.Detail d = api.detail(id);
        JSONObject vod = Vod.item(id, d.title, d.cover, d.remarks);
        vod.put("type_name", d.type);
        vod.put("vod_actor", d.author);
        vod.put("vod_content", d.desc);
        StringBuilder url = new StringBuilder();
        for (String[] part : d.parts) {
            if (url.length() > 0) url.append('#');
            url.append(Vod.clean(part[0])).append('$').append(d.bvid).append('_').append(part[1]);
        }
        vod.put("vod_play_from", "哔哩哔哩");
        vod.put("vod_play_url", url.toString());
        return Vod.detail(vod);
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        int page = Xhs.parsePage(pg);
        try {
            BiliApi.Page p = api.search(key, page);
            return Vod.page(page, p.hasMore, toVods(p.videos));
        } catch (Exception e) {
            return Vod.page(page, false, new ArrayList<JSONObject>());
        }
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        int sep = id.lastIndexOf('_');
        String url = api.playUrl(id.substring(0, sep), id.substring(sep + 1));
        return Vod.play(url, api.playHeaders());
    }

    private List<JSONObject> accountItems() {
        List<JSONObject> list = new ArrayList<JSONObject>();
        BiliApi.User u = user();
        if (u.login) {
            list.add(Vod.item("msg:当前已登录：" + u.name, u.name, u.face, u.vipStatus == 1 ? "大会员" : "已登录"));
            list.add(Vod.item("logout", "退出登录", "", ""));
        } else {
            list.add(login.item());
        }
        return list;
    }

    private static List<JSONObject> toVods(List<BiliApi.Video> videos) {
        List<JSONObject> list = new ArrayList<JSONObject>();
        for (BiliApi.Video v : videos) list.add(Vod.item(v.bvid, v.title, v.cover, v.remarks));
        return list;
    }
}
