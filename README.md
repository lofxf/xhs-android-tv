# xhs-android-tv · FongMi/TV 视频源（小红书 / 哔哩哔哩）

在 **Fire TV / Android TV** 上看小红书视频，**手机扫码登录**。

本项目没有另做一个播放器 App，而是按 [TVBox](https://github.com/CatVodTVOfficial/TVBoxOSC) / [FongMi/TV](https://github.com/FongMi/TV) 的 **CatVod Spider** 规范实现了一个 `custom_spider.jar`。
界面、播放器、遥控器操作、历史记录、收藏都直接用 FongMi/TV 的，这里只负责「网页源」：

| 站点 | api | 分类 | 搜索 | 扫码登录 |
| --- | --- | --- | --- | --- |
| 小红书 | `csp_Xhs` | 推荐 / 影视 / 美食 / 旅行 / 游戏 …（只保留视频笔记） | ✅ 视频笔记 | ✅ 小红书 App 扫一扫 |
| 哔哩哔哩 | `csp_BiliWeb` | 推荐 / 热门 / 全站排行 / 各分区排行 | ✅ | ✅ 哔哩哔哩 App 扫一扫（登录后清晰度更高） |

以后要接其它网站，只需在 `spider/src/main/java/com/github/catvod/spider/` 下再加一个 `Spider` 子类，
并在 `config.template.json` 里加一条 `"api": "csp_类名"` 即可。

## 在 Fire TV 上使用

1. 在 Fire TV 上安装 [FongMi/TV](https://github.com/FongMi/TV/releases)（leanback 版本 apk，可用 Downloader 等工具侧载）。
2. 在 FongMi/TV 的「设置 → 点播」中填写配置地址：
   ```
   https://raw.githubusercontent.com/lofxf/xhs-android-tv/dist/config.json
   ```
   （国内网络可改用 jsDelivr 镜像 `https://cdn.jsdelivr.net/gh/lofxf/xhs-android-tv@dist/config.json`，注意其分支缓存约 12 小时。
   `dist` 分支由 GitHub Actions 在 `main` 分支每次 push 后自动生成；也可以把 `dist/` 目录放到任意 HTTP 服务器 / U 盘上用 `file://` 地址。）
3. 首页切换站点到「小红书」，进入最后一个分类 **「扫码登录」**，海报上就是登录二维码：
   用手机小红书 App「扫一扫」扫码并确认，状态会在后台自动轮询，登录成功后切换到其它分类即可浏览。
   二维码约 3 分钟过期，重新进入该分类会自动刷新。
4. 哔哩哔哩同理（不登录也能看，登录后可解锁 1080P 等清晰度）。

> 也可以把这两个站点合并进你已有的 TVBox 配置：把 `dist/custom_spider.jar` 作为某个站点的独立 `jar`：
> ```json
> {"key":"csp_Xhs","name":"小红书","type":3,"api":"csp_Xhs","searchable":1,"quickSearch":1,"jar":"https://raw.githubusercontent.com/lofxf/xhs-android-tv/dist/custom_spider.jar"}
> ```

## 实现说明

- **小红书**：网页接口（`edith.xiaohongshu.com`）需要 `x-s` / `x-t` 签名。Spider 在 `init` 时用宿主传入的 `Context`
  创建一个隐藏 WebView 加载 `www.xiaohongshu.com`，直接调用页面自带的 `window._webmsxyw()` 生成签名，`x-s-common`
  在 Java 中计算；`a1` 等设备 Cookie 与登录后的 `web_session` 通过 `android.webkit.CookieManager` 共享并持久化。
  - 扫码登录：`/api/sns/web/v1/login/qrcode/create` → `/login/qrcode/status` 轮询。
  - 视频地址：`/api/sns/web/v1/feed` 中的 `video.media.stream.h264/h265/av1`，按编码/分辨率分为不同「线路」，Fire TV 默认选 H264 兼容性最好。
- **哔哩哔哩**：WBI 签名（`w_rid`）、`buvid3` 自动获取，扫码登录走 `passport.bilibili.com/x/passport-login/web/qrcode/*`，
  播放使用 `playurl` 的 durl（MP4/FLV）直链并带 `Referer`。
- 登录二维码由 Spider 内置的 `127.0.0.1` 小型 HTTP 服务提供图片，不依赖宿主的本地代理接口，所以 TVBox 各分支都能显示。
- 只使用 Android SDK 自带 API（`HttpURLConnection`、`org.json`、`WebView`），唯一打包进 jar 的第三方库是 zxing（生成二维码），
  避免与宿主 App 的 OkHttp / Gson 版本冲突。

> 小红书网页签名算法会不定期更新；如果出现「签名失败」或「安全验证」，通常需要更新 `XhsSigner` / `XsCommon`。

## 构建

不需要 Android Gradle Plugin，只需要 JDK 17 + Android SDK（`platforms/android-*/android.jar` 与 `build-tools/*/d8`）：

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :spider:spiderJar
```

输出在 `dist/`：`custom_spider.jar`（含 `classes.dex`）、`config.json`（已写入 jar 的 md5）。

## 目录

```
catvod-stub/   宿主 Spider 基类的编译期桩（不打包）
spider/        Spider 实现
  com/github/catvod/spider/Xhs.java       小红书入口 (csp_Xhs)
  com/github/catvod/spider/BiliWeb.java   哔哩哔哩入口 (csp_BiliWeb)
  io/github/lofxf/tvsource/xhs|bili|common
config.template.json
```

## 声明

本项目仅供个人学习研究，内容版权归原平台及作者所有，请遵守相关平台的用户协议。
