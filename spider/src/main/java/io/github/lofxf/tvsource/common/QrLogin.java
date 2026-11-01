package io.github.lofxf.tvsource.common;

import org.json.JSONObject;

/**
 * TV-friendly QR login shared by all sources. The QR code is exposed as the poster of a pseudo
 * video in a "扫码登录" category; a background thread polls the scan status so the user only has
 * to scan with the phone app and then switch category.
 *
 * Freshness: the QR is considered valid until the platform reports it expired (or a safety cap
 * passes). When it expires, a new QR is generated immediately in the background (a limited number
 * of times), and every list / detail request returns the current one. Each QR is published under
 * a new image URL so the host's image cache can never show a stale code.
 */
public final class QrLogin {

    public static final int WAITING = 0, SCANNED = 1, SUCCESS = 2, EXPIRED = 3;
    /** Safety cap in case a platform never reports expiry. */
    private static final long QR_MAX_AGE_MS = 5 * 60_000L;
    private static final long POLL_INTERVAL_MS = 2000L;
    /** Background auto-refreshes per user visit (~30 min), to avoid polling forever. */
    private static final int MAX_AUTO_REFRESH = 6;

    public interface Backend {
        /** Creates a new login QR and returns the text to encode. */
        String create() throws Exception;

        /** Polls the QR created by the last {@link #create()} call. */
        int poll() throws Exception;

        /** Called once after a successful login. */
        void onSuccess();
    }

    private final String name;
    private final String appName;
    private final Backend backend;
    private String imageUrl = "";
    private String status = "";
    private long createdAt;
    private int state = EXPIRED;
    private int generation;
    private int autoRefreshLeft;

    public QrLogin(String name, String appName, Backend backend) {
        this.name = name;
        this.appName = appName;
        this.backend = backend;
    }

    public synchronized boolean succeeded() {
        return state == SUCCESS;
    }

    /** Pseudo video item that shows the current (fresh) QR code. */
    public synchronized JSONObject item() {
        autoRefreshLeft = MAX_AUTO_REFRESH; // user is looking at it: re-arm background refresh
        boolean stale = state == EXPIRED || state == SUCCESS
                || System.currentTimeMillis() - createdAt > QR_MAX_AGE_MS;
        if (stale) refresh();
        return Vod.item("login", "打开" + appName + "App扫码登录", imageUrl, status);
    }

    public synchronized String status() {
        return status;
    }

    /** Must hold the lock. */
    private void refresh() {
        int gen = ++generation;
        try {
            String text = backend.create();
            imageUrl = ImageServer.publish(name, QrImage.poster(text, 720));
            createdAt = System.currentTimeMillis();
            state = WAITING;
            status = "等待扫码（" + clock(createdAt) + " 生成）";
            startPolling(gen);
        } catch (Exception e) {
            state = EXPIRED;
            imageUrl = "";
            status = "二维码获取失败: " + e.getMessage();
        }
    }

    private void startPolling(final int gen) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    synchronized (QrLogin.this) {
                        if (gen != generation || state == SUCCESS || state == EXPIRED) return;
                        if (System.currentTimeMillis() - createdAt > QR_MAX_AGE_MS) {
                            expired();
                            return;
                        }
                    }
                    int s;
                    try {
                        s = backend.poll();
                    } catch (Exception e) {
                        continue;
                    }
                    synchronized (QrLogin.this) {
                        if (gen != generation) return;
                        if (s == SCANNED) {
                            state = SCANNED;
                            status = "已扫码，请在手机上确认";
                        } else if (s == SUCCESS) {
                            state = SUCCESS;
                            status = "登录成功";
                        } else if (s == EXPIRED) {
                            expired();
                            return;
                        }
                    }
                    if (s == SUCCESS) {
                        backend.onSuccess();
                        return;
                    }
                }
            }
        }, "tvsource-qr-" + name);
        t.setDaemon(true);
        t.start();
    }

    /** Must hold the lock: replaces an expired QR right away (bounded), or marks it expired. */
    private void expired() {
        if (autoRefreshLeft > 0) {
            autoRefreshLeft--;
            refresh();
            if (state == WAITING) status = "已自动更新二维码（" + clock(createdAt) + "），请返回重新进入本页";
        } else {
            state = EXPIRED;
            status = "二维码已过期，重新进入本分类即可刷新";
        }
    }

    private static String clock(long t) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date(t));
    }

    /** Detail page for the login pseudo item: always shows the current QR and status. */
    public String detail() {
        try {
            JSONObject vod = item();
            vod.put("type_name", "账号");
            vod.put("vod_content", "1. 打开手机" + appName + "App，使用「扫一扫」扫描海报中的二维码；\n"
                    + "2. 在手机上确认登录；\n"
                    + "3. 返回并切换到其它分类即可浏览。\n\n当前状态：" + status()
                    + "\n（二维码过期后会在后台自动更新；如扫码提示失效，返回后重新进入本页即可看到新二维码）");
            return Vod.detail(vod);
        } catch (Exception e) {
            return "{}";
        }
    }
}
