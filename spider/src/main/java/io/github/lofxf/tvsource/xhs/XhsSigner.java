package io.github.lofxf.tvsource.xhs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.lofxf.tvsource.common.Http;

/**
 * Xiaohongshu web APIs require an x-s / x-t signature computed by obfuscated JS shipped with the
 * website. Instead of re-implementing it, a hidden WebView loads www.xiaohongshu.com and we call
 * the page's own {@code window._webmsxyw(uri, data)}. The WebView also obtains the a1 / webId
 * device cookies, which are shared with our HTTP calls through {@link CookieManager}.
 */
public final class XhsSigner {

    static final String HOME = "https://www.xiaohongshu.com/explore";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private volatile CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean polling;

    public static final class Signature {
        public String xs, xt, body, b1;
    }

    public XhsSigner(Context context) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
    }

    public void start() {
        main.post(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
    }

    /** Reloads the page, e.g. after login / logout so the page picks up the new session. */
    public void reload() {
        ready = new CountDownLatch(1);
        start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void load() {
        if (webView == null) {
            WebView v = new WebView(context);
            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setBlockNetworkImage(true);
            s.setMediaPlaybackRequiresUserGesture(true);
            s.setUserAgentString(Http.DESKTOP_UA);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(v, true);
            v.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    poll(0);
                }
            });
            webView = v;
        }
        webView.loadUrl(HOME);
    }

    private void poll(final int attempt) {
        if (ready.getCount() == 0 || (attempt == 0 && polling)) return;
        polling = true;
        webView.evaluateJavascript("typeof window._webmsxyw === 'function'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if ("true".equals(value)) {
                    polling = false;
                    CookieManager.getInstance().flush();
                    ready.countDown();
                } else if (attempt < 80) {
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            poll(attempt + 1);
                        }
                    }, 250);
                } else {
                    polling = false;
                }
            }
        });
    }

    public void awaitReady() throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("must not be called on main thread");
        if (!ready.await(30, TimeUnit.SECONDS)) {
            reload();
            throw new Exception("小红书签名组件加载超时，请检查网络后重试");
        }
    }

    /** Signs {@code uri} (path + query) and the JSON {@code body} (null for GET). Blocking. */
    public Signature sign(String uri, String body) throws Exception {
        awaitReady();
        final String js = "(function(){try{"
                + "var d=" + (body == null ? "undefined" : "JSON.parse(" + JSONObject.quote(body) + ")") + ";"
                + "var r=window._webmsxyw(" + JSONObject.quote(uri) + ",d);"
                + "return JSON.stringify({xs:r['X-s'],xt:String(r['X-t']),"
                + "body:d===undefined?null:JSON.stringify(d),"
                + "b1:(window.localStorage&&localStorage.getItem('b1'))||''});"
                + "}catch(e){return JSON.stringify({error:String(e)});}})()";
        final AtomicReference<String> result = new AtomicReference<String>();
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(js, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        result.set(value);
                        done.countDown();
                    }
                });
            }
        });
        if (!done.await(10, TimeUnit.SECONDS)) throw new Exception("小红书签名超时");
        Object decoded = new JSONTokener(result.get()).nextValue();
        if (!(decoded instanceof String)) throw new Exception("小红书签名失败");
        JSONObject o = new JSONObject((String) decoded);
        if (o.has("error")) throw new Exception("小红书签名失败: " + o.optString("error"));
        Signature sig = new Signature();
        sig.xs = o.optString("xs");
        sig.xt = o.optString("xt");
        sig.body = o.isNull("body") ? null : o.optString("body");
        sig.b1 = o.optString("b1");
        return sig;
    }
}
