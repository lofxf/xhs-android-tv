package io.github.lofxf.tvsource.xhs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.lofxf.tvsource.common.Http;

/**
 * A hidden WebView running the real www.xiaohongshu.com page. API calls are executed <em>inside</em>
 * that page so that the website's own code produces every signature / header / cookie and the
 * request carries a genuine browser fingerprint:
 * <ol>
 *   <li>preferred: find the site's own API function for a path in its webpack modules and call it;</li>
 *   <li>fallback: in-page {@code fetch} with an x-s / x-t signature from {@code window._webmsxyw}.</li>
 * </ol>
 */
public final class XhsPage {

    static final String HOME = "https://www.xiaohongshu.com/explore";
    private static final String API = "https://edith.xiaohongshu.com";

    /** Locates the page's webpack runtime and the exported function that calls a given API path. */
    private static final String HELPER = "if(!window.__tvFind){(function(){"
            + "var cache={};"
            + "function wreq(){if(window.__tvReq)return window.__tvReq;"
            + "var key=Object.keys(window).filter(function(k){return k.indexOf('webpackChunk')===0;})[0];"
            + "if(!key||!window[key]||!window[key].push)return null;var r=null;"
            + "try{window[key].push([['__tv'+Date.now()],{},function(q){r=q;}]);}catch(e){}"
            + "window.__tvReq=r;return r;}"
            + "function scan(exp,path,best){if(!exp)return best;var keys;try{keys=Object.keys(exp);}catch(e){return best;}"
            + "if(typeof exp==='function')keys.push('');"
            + "for(var i=0;i<keys.length;i++){var f;try{f=keys[i]===''?exp:exp[keys[i]];}catch(e){continue;}"
            + "if(typeof f!=='function')continue;var s;try{s=Function.prototype.toString.call(f);}catch(e){continue;}"
            + "if(s.indexOf(path)>=0&&s.length<4000&&(!best||s.length<best.len))best={fn:f,len:s.length};}return best;}"
            + "window.__tvFind=function(path){if(cache[path])return cache[path];var r=wreq();if(!r)return null;var best=null;"
            + "if(r.c){for(var id in r.c){try{best=scan(r.c[id]&&r.c[id].exports,path,best);}catch(e){}}}"
            + "if(!best&&r.m){for(var mid in r.m){var src;try{src=Function.prototype.toString.call(r.m[mid]);}catch(e){continue;}"
            + "if(src.indexOf(path)<0)continue;try{best=scan(r(mid),path,best);}catch(e){}}}"
            + "if(best)cache[path]=best.fn;return best?best.fn:null;};"
            + "})();}";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private volatile CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean polling;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<String, Pending> pending = new ConcurrentHashMap<String, Pending>();

    public static final class Signature {
        public String xs, xt, body, b1;
    }

    /** Raw result of an in-page call. */
    public static final class Result {
        public boolean ok;
        public String via = "";
        public int status;
        public Object payload;
        public String message = "";
    }

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String json;
    }

    public final class Bridge {
        @JavascriptInterface
        public void onResult(String id, String json) {
            Pending p = pending.get(id);
            if (p != null) {
                p.json = json;
                p.latch.countDown();
            }
        }
    }

    public XhsPage(Context context) {
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

    /** Reloads the page, e.g. after login / logout so it picks up the new session. */
    public void reload() {
        ready = new CountDownLatch(1);
        start();
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface"})
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
            v.addJavascriptInterface(new Bridge(), "__tvBridge");
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
        webView.evaluateJavascript("typeof window._webmsxyw === 'function' || "
                + "Object.keys(window).some(function(k){return k.indexOf('webpackChunk')===0;})", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if ("true".equals(value) && attempt >= 4) { // give the app bundle ~1 s to finish booting
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
            throw new Exception("小红书网页组件加载超时，请检查网络后重试");
        }
    }

    // ------------------------------------------------------------------ calls

    /**
     * Calls the website's own API function for {@code path} with {@code argsJson}.
     * @return null when the page has no such function (caller should fall back).
     */
    public Result callPageApi(String path, String argsJson) throws Exception {
        awaitReady();
        String js = "(function(){try{" + HELPER
                + "var f=window.__tvFind(" + JSONObject.quote(path) + ");if(!f)return 'notfound';"
                + "var a=" + (argsJson == null ? "undefined" : "JSON.parse(" + JSONObject.quote(argsJson) + ")") + ";"
                + "var p=f(a);if(!p||typeof p.then!=='function')return 'notpromise';"
                + "p.then(function(r){__tvDone(true,'page',0,r,'');},function(e){"
                + "var resp=e&&e.response;__tvDone(false,'page',resp&&resp.status||0,resp&&resp.data||e,String(e&&(e.msg||e.message)||e));});"
                + "return 'started';}catch(e){return 'error:'+e;}})()";
        return run(js);
    }

    /** In-page fetch to edith.xiaohongshu.com with the given headers (signature fallback). */
    public Result fetch(String method, String uri, Map<String, String> headers, String body) throws Exception {
        awaitReady();
        JSONObject h = new JSONObject();
        for (Map.Entry<String, String> e : headers.entrySet()) h.put(e.getKey(), e.getValue());
        String js = "(function(){try{"
                + "fetch(" + JSONObject.quote(API + uri) + ",{method:" + JSONObject.quote(method) + ",credentials:'include',"
                + "headers:" + h + (body == null ? "" : ",body:" + JSONObject.quote(body)) + "})"
                + ".then(function(r){return r.text().then(function(t){var d;try{d=JSON.parse(t);}catch(x){d=t;}"
                + "__tvDone(r.ok,'fetch',r.status,d,'');});},function(e){__tvDone(false,'fetch',0,null,String(e));});"
                + "return 'started';}catch(e){return 'error:'+e;}})()";
        return run(js);
    }

    private Result run(String body) throws Exception {
        final String id = "c" + seq.incrementAndGet();
        Pending p = new Pending();
        pending.put(id, p);
        try {
            String done = "window.__tvDone_" + id;
            final String js = "(function(){" + done + "=function(ok,via,status,payload,msg){"
                    + "var s;try{s=JSON.stringify({ok:ok,via:via,status:status,payload:payload,message:msg});}"
                    + "catch(e){s=JSON.stringify({ok:false,via:via,status:status,message:'unserializable: '+e});}"
                    + "try{delete " + done + ";}catch(e){}"
                    + "window.__tvBridge.onResult(" + JSONObject.quote(id) + ",s);};})();"
                    + body.replace("__tvDone(", done + "(");
            String started = evaluate(js);
            if (started == null || started.startsWith("error:")) throw new Exception("小红书网页调用失败: " + started);
            if (!"started".equals(started)) return null; // notfound / notpromise
            if (!p.latch.await(20, TimeUnit.SECONDS)) throw new Exception("小红书请求超时");
            JSONObject o = new JSONObject(p.json);
            Result r = new Result();
            r.ok = o.optBoolean("ok");
            r.via = o.optString("via");
            r.status = o.optInt("status");
            r.payload = o.opt("payload");
            r.message = o.optString("message");
            return r;
        } finally {
            pending.remove(id);
        }
    }

    /** Signs {@code uri} (path + query) and the JSON {@code body} (null for GET). */
    public Signature sign(String uri, String body) throws Exception {
        awaitReady();
        String js = "(function(){try{"
                + "if(typeof window._webmsxyw!=='function')return JSON.stringify({error:'no _webmsxyw'});"
                + "var d=" + (body == null ? "undefined" : "JSON.parse(" + JSONObject.quote(body) + ")") + ";"
                + "var r=window._webmsxyw(" + JSONObject.quote(uri) + ",d);"
                + "return JSON.stringify({xs:r['X-s'],xt:String(r['X-t']),"
                + "body:d===undefined?null:JSON.stringify(d),"
                + "b1:(window.localStorage&&localStorage.getItem('b1'))||''});"
                + "}catch(e){return JSON.stringify({error:String(e)});}})()";
        JSONObject o = new JSONObject(evaluate(js));
        if (o.has("error")) throw new Exception("小红书签名失败: " + o.optString("error"));
        Signature sig = new Signature();
        sig.xs = o.optString("xs");
        sig.xt = o.optString("xt");
        sig.body = o.isNull("body") ? null : o.optString("body");
        sig.b1 = o.optString("b1");
        return sig;
    }

    /** Evaluates JS on the main thread and returns its (string) result. Blocking. */
    private String evaluate(final String js) throws Exception {
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
        if (!done.await(10, TimeUnit.SECONDS)) throw new Exception("小红书网页无响应");
        Object decoded = new JSONTokener(result.get()).nextValue();
        return decoded instanceof String ? (String) decoded : String.valueOf(decoded);
    }
}
