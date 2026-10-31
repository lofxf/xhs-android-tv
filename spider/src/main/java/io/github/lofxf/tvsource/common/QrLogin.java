package io.github.lofxf.tvsource.common;

import org.json.JSONObject;

/**
 * TV-friendly QR login shared by all sources. The QR code is exposed as the poster of a pseudo
 * video in a "扫码登录" category; a background thread polls the scan status so the user only has
 * to scan with the phone app and then switch category.
 */
public final class QrLogin {

    public static final int WAITING = 0, SCANNED = 1, SUCCESS = 2, EXPIRED = 3;
    private static final long QR_LIFETIME_MS = 170_000L;

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
    private volatile int state = EXPIRED;
    private int generation;

    public QrLogin(String name, String appName, Backend backend) {
        this.name = name;
        this.appName = appName;
        this.backend = backend;
    }

    public synchronized boolean succeeded() {
        return state == SUCCESS;
    }

    /** Pseudo video item that shows the (fresh) QR code. */
    public synchronized JSONObject item() {
        boolean stale = state == EXPIRED || state == SUCCESS
                || System.currentTimeMillis() - createdAt > QR_LIFETIME_MS;
        if (stale) refresh();
        return Vod.item("login", "打开" + appName + "App扫码登录", imageUrl, status);
    }

    public synchronized String status() {
        return status;
    }

    private void refresh() {
        try {
            String text = backend.create();
            imageUrl = ImageServer.publish(name, QrImage.png(text, 600));
            createdAt = System.currentTimeMillis();
            state = WAITING;
            status = "等待扫码";
            startPolling(++generation);
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
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    synchronized (QrLogin.this) {
                        if (gen != generation || state == SUCCESS || state == EXPIRED) return;
                        if (System.currentTimeMillis() - createdAt > QR_LIFETIME_MS) {
                            state = EXPIRED;
                            status = "二维码已过期，请刷新";
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
                        state = s;
                        if (s == SCANNED) status = "已扫码，请在手机上确认";
                        else if (s == SUCCESS) status = "登录成功";
                        else if (s == EXPIRED) status = "二维码已过期，请刷新";
                    }
                    if (s == SUCCESS) {
                        backend.onSuccess();
                        return;
                    }
                    if (s == EXPIRED) return;
                }
            }
        }, "tvsource-qr-" + name);
        t.setDaemon(true);
        t.start();
    }

    /** Detail page body for the login pseudo item. */
    public String detail() {
        try {
            JSONObject vod = item();
            vod.put("type_name", "账号");
            vod.put("vod_content", "1. 打开手机" + appName + "App，使用「扫一扫」扫描海报中的二维码；\n"
                    + "2. 在手机上确认登录；\n"
                    + "3. 返回并切换到其它分类即可浏览。\n\n当前状态：" + status()
                    + "\n（二维码约 3 分钟过期，重新进入本分类即可刷新）");
            return Vod.detail(vod);
        } catch (Exception e) {
            return "{}";
        }
    }
}
