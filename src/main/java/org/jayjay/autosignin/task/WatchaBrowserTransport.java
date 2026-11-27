package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.cdp.core.Puppeteer;
import com.ruiyun.jvppeteer.cdp.entities.GoToOptions;
import com.ruiyun.jvppeteer.cdp.entities.LaunchOptions;
import com.ruiyun.jvppeteer.cdp.entities.ImageType;
import com.ruiyun.jvppeteer.cdp.entities.RevisionInfo;
import com.ruiyun.jvppeteer.cdp.entities.ScreenshotOptions;
import com.ruiyun.jvppeteer.cdp.entities.UserAgentBrandVersion;
import com.ruiyun.jvppeteer.cdp.entities.UserAgentMetadata;
import com.ruiyun.jvppeteer.cdp.entities.Viewport;
import com.ruiyun.jvppeteer.common.Product;
import com.ruiyun.jvppeteer.common.UserAgentOptions;
import com.ruiyun.jvppeteer.cdp.entities.Protocol;
import com.ruiyun.jvppeteer.common.PuppeteerLifeCycle;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用真浏览器发请求。纯 HTTP 客户端有三样东西改不掉：TLS 握手特征、会话 cookie、页面埋点。
 * 这里把「打开页面」和「提交接口」都放进 Chrome 里做，一次解决三件事：
 * <ul>
 *   <li>握手是 Chrome 自己的，JA3 和 UA 对得上；</li>
 *   <li>登录、浏览、提交在同一个浏览器上下文里，cookie 自然带上；</li>
 *   <li>打开页面会真的触发站点的埋点和统计脚本，不再是「有操作没浏览」。</li>
 * </ul>
 * 代价是每次运行要起一次浏览器，写操作前多一到两次页面加载。
 */
final class WatchaBrowserTransport implements AutoCloseable {

    /** 页面打开超时，站点首屏资源不少，给宽一点。 */
    private static final int PAGE_TIMEOUT_MILLIS = 45000;
    /** 页面内 fetch 的轮询上限。 */
    private static final int FETCH_WAIT_MILLIS = 30000;
    private static final int POLL_INTERVAL_MILLIS = 200;
    /** 页面里暂存结果的槽位，读写都用函数表达式，和 evaluate 的调用约定保持一致。 */
    private static final String CLEAR_REPLY = "() => { window.__watchaReply = null; return null; }";
    private static final String READ_REPLY = "() => window.__watchaReply || null";
    /** 只读页面事实，不做任何操作。 */
    private static final String TRIAL_SCRIPT = "() => JSON.stringify({"
            + "title: document.title || '',"
            + "h1: (document.querySelector('h1') || {}).innerText || '',"
            + "desc: (document.querySelector('meta[name=\"description\"]') || {}).content || '',"
            + "og: (document.querySelector('meta[property=\"og:image\"]') || {}).content || '',"
            + "text: (document.body ? document.body.innerText : '').slice(0, 400)"
            + "})";

    private final Browser browser;
    private final Page page;
    private final String baseUrl;
    /** 浏览器自己的 UA（去掉 HeadlessChrome 标记），后续所有请求都用它，避免和实际引擎版本对不上。 */
    private final String userAgent;
    private final String brandsHeader;
    private final String majorVersion;
    /** 最近一次登录响应里的 refresh_token。 */
    private String lastRefreshToken = "";

    private WatchaBrowserTransport(Browser browser, Page page, String baseUrl, String userAgent,
                                   String brandsHeader, String majorVersion) {
        this.browser = browser;
        this.page = page;
        this.baseUrl = baseUrl;
        this.userAgent = userAgent;
        this.brandsHeader = brandsHeader;
        this.majorVersion = majorVersion;
    }

    static WatchaBrowserTransport open(String baseUrl) throws Exception {
        return open(baseUrl, null);
    }

    /**
     * @param userDataDir 持久化的浏览器资料目录。传了以后 cookie、localStorage、浏览历史都留着，
     *                    下次运行还是同一个浏览器身份，不用每天重新登录。
     */
    static WatchaBrowserTransport open(String baseUrl, String userDataDir) throws Exception {
        List<String> args = new ArrayList<String>();
        args.add("--no-sandbox");
        args.add("--disable-setuid-sandbox");
        args.add("--disable-dev-shm-usage");
        // 无头模式默认会暴露 navigator.webdriver，关掉这个特性最接近普通 Chrome
        args.add("--disable-blink-features=AutomationControlled");
        args.add("--lang=zh-CN");
        args.add("--no-first-run");
        args.add("--no-default-browser-check");
        LaunchOptions options = LaunchOptions.builder()
                .args(args)
                .userDataDir(StrUtil.isBlank(userDataDir) ? null : userDataDir)
                .defaultViewport(new Viewport(1440, 900))
                .headless(true)
                .protocol(Protocol.CDP)
                .product(Product.Chrome)
                .build();
        Browser browser;
        try {
            browser = Puppeteer.launch(options);
        } catch (Exception e) {
            // 第一次跑没有浏览器，下载一次再启动
            RevisionInfo info = Puppeteer.downloadBrowser();
            System.out.println("[观猹] 已下载浏览器：" + info);
            browser = Puppeteer.launch(options);
        }
        Page page = browser.newPage();
        // 用浏览器自己的版本号，只把 HeadlessChrome 换成 Chrome。
        // 之前把 UA 写死成 Edge 114，但引擎是另一个大版本，navigator.userAgentData 一读就露馅。
        String ua = normalizeUserAgent(browser.userAgent());
        String version = versionOf(ua);
        String major = version.split("\\.")[0];
        String brands = "\"Chromium\";v=\"" + major + "\", \"Google Chrome\";v=\"" + major
                + "\", \"Not_A Brand\";v=\"24\"";
        UserAgentMetadata metadata = new UserAgentMetadata();
        metadata.setPlatform("Linux");
        metadata.setPlatformVersion("6.5.0");
        metadata.setMobile(false);
        metadata.setArchitecture("x86");
        metadata.setBitness("64");
        metadata.setModel("");
        metadata.setBrands(java.util.Arrays.asList(
                new UserAgentBrandVersion("Chromium", major),
                new UserAgentBrandVersion("Google Chrome", major),
                new UserAgentBrandVersion("Not_A Brand", "24")));
        metadata.setFullVersionList(java.util.Arrays.asList(
                new UserAgentBrandVersion("Chromium", version),
                new UserAgentBrandVersion("Google Chrome", version),
                new UserAgentBrandVersion("Not_A Brand", "24.0.0.0")));
        UserAgentOptions agentOptions = new UserAgentOptions(ua);
        agentOptions.setPlatform("Linux");
        agentOptions.setUserAgentMetadata(metadata);
        page.setUserAgent(agentOptions);
        System.out.println("[观猹] 浏览器 UA：" + ua);
        return new WatchaBrowserTransport(browser, page, StrUtil.removeSuffix(baseUrl, "/"),
                ua, brands, major);
    }

    /**
     * 把本地缓存的令牌写进 localStorage，让页面直接处于登录态，省掉一次登录请求。
     * 站点的键名是 watcha.accessToken / watcha.refreshToken。
     */
    void injectTokens(String accessToken, String refreshToken) {
        try {
            visit("/");
            StringBuilder script = new StringBuilder("() => {");
            if (StrUtil.isNotBlank(accessToken)) {
                script.append("localStorage.setItem('watcha.accessToken', ").append(quote(accessToken)).append(");");
            }
            if (StrUtil.isNotBlank(refreshToken)) {
                script.append("localStorage.setItem('watcha.refreshToken', ").append(quote(refreshToken)).append(");");
            }
            script.append("return 'ok'; }");
            page.evaluate(script.toString());
        } catch (Exception e) {
            System.out.println("[观猹] 令牌注入浏览器失败：" + StrUtil.maxLength(e.getMessage(), 100));
        }
    }

    /** 登录响应里同时带 refresh_token，能省掉以后每天的密码登录。 */
    String refreshTokenFromLogin() {
        return lastRefreshToken;
    }

    /** 真打开一个页面：既产生页面浏览，也把 cookie 种进浏览器上下文。 */
    void visit(String path) throws Exception {
        GoToOptions options = new GoToOptions();
        options.setTimeout(PAGE_TIMEOUT_MILLIS);
        options.setWaitUntil(Collections.singletonList(PuppeteerLifeCycle.domcontentloaded));
        page.goTo(baseUrl + path, options);
    }

    /** 在页面上下文里登录，返回 access_token；顺带让服务端把 refresh cookie 落到浏览器里。 */
    String login(String username, String password) throws Exception {
        visit("/signin");
        JSONObject body = JSONUtil.createObj().set("account", username).set("password", password);
        Result result = post("/api/v2/auth/signin", body, "");
        if (result.status < 200 || result.status >= 300) {
            throw new IllegalStateException("浏览器登录失败：" + result.describe());
        }
        JSONObject payload = JSONUtil.parseObj(result.body);
        JSONObject data = payload.getJSONObject("data");
        if (data != null && StrUtil.isNotBlank(data.getStr("refresh_token"))) {
            lastRefreshToken = data.getStr("refresh_token");
        }
        String token = data == null ? null : data.getStr("access_token");
        if (StrUtil.isBlank(token)) {
            throw new IllegalStateException("浏览器登录没有拿到令牌");
        }
        return token;
    }

    String userAgent() {
        return userAgent;
    }

    /** 和 UA 版本一致的 sec-ch-ua，别再出现「UA 说 114、指纹说 131」这种自相矛盾。 */
    String brandsHeader() {
        return brandsHeader;
    }

    String majorVersion() {
        return majorVersion;
    }

    /**
     * 一次访问拿到两样东西：页面事实（标题、首屏大字、og:image、首屏文字）和首屏截图。
     * <p>
     * 以前是 HTTP 抓一次、浏览器再开一次，同一个站被访问两遍——既多余，也不像人。
     *
     * @return 采集结果；失败返回 null
     */
    TrialCapture captureTrial(String url, File screenshotTarget) {
        if (StrUtil.isBlank(url)) {
            return null;
        }
        Page shotPage = null;
        long started = System.currentTimeMillis();
        try {
            shotPage = browser.newPage();
            GoToOptions options = new GoToOptions();
            options.setTimeout(PAGE_TIMEOUT_MILLIS);
            options.setWaitUntil(Collections.singletonList(PuppeteerLifeCycle.domcontentloaded));
            shotPage.goTo(url.trim(), options);
            Thread.sleep(2500);
            long millis = System.currentTimeMillis() - started;

            String raw = String.valueOf(shotPage.evaluate(TRIAL_SCRIPT));
            JSONObject facts = JSONUtil.parseObj(raw);
            String title = facts.getStr("title", "");
            String h1 = facts.getStr("h1", "");
            String desc = facts.getStr("desc", "");
            String og = WatchaTrialProbe.absolute(url.trim(), facts.getStr("og", ""));
            String text = facts.getStr("text", "");

            File shot = null;
            if (screenshotTarget != null) {
                ScreenshotOptions shotOptions = new ScreenshotOptions();
                shotOptions.setPath(screenshotTarget.getAbsolutePath());
                shotOptions.setType(ImageType.PNG);
                shotPage.screenshot(shotOptions);
                if (screenshotTarget.isFile() && screenshotTarget.length() > 0) {
                    shot = screenshotTarget;
                }
            }
            String summary = WatchaTrialProbe.summarize(title, h1, desc, text);
            if (summary.isEmpty()) {
                return null;
            }
            return new TrialCapture(new WatchaTrialProbe.Record(true, 200, millis, summary, og, h1, title, null),
                    shot, url.trim());
        } catch (Exception e) {
            System.out.println("[观猹] 试用采集失败：" + StrUtil.maxLength(e.getMessage(), 120));
            return null;
        } finally {
            if (shotPage != null) {
                try {
                    shotPage.close();
                } catch (Exception ignored) {
                    // 关不掉就算了，浏览器退出时会一起收掉
                }
            }
        }
    }

    /** 一次访问同时拿到页面事实和截图的结果。 */
    static final class TrialCapture {
        final WatchaTrialProbe.Record record;
        final File screenshot;
        final String url;

        TrialCapture(WatchaTrialProbe.Record record, File screenshot, String url) {
            this.record = record;
            this.screenshot = screenshot;
            this.url = url;
        }
    }

    /**
     * 打开产品官网截一张图，作为「真试用」的证据。用新开的页面，结束就关掉，
     * 不影响主页面里的会话和 cookie。
     *
     * @return 截图文件；失败返回 null
     */
    File screenshot(String url, File target) {
        if (StrUtil.isBlank(url)) {
            return null;
        }
        Page shotPage = null;
        try {
            shotPage = browser.newPage();
            GoToOptions options = new GoToOptions();
            options.setTimeout(PAGE_TIMEOUT_MILLIS);
            // 首屏出来就够了，等完整 load 经常被第三方资源拖死
            options.setWaitUntil(Collections.singletonList(PuppeteerLifeCycle.domcontentloaded));
            shotPage.goTo(url.trim(), options);
            Thread.sleep(2500);
            ScreenshotOptions shot = new ScreenshotOptions();
            shot.setPath(target.getAbsolutePath());
            shot.setType(ImageType.PNG);
            shotPage.screenshot(shot);
            return target.isFile() && target.length() > 0 ? target : null;
        } catch (Exception e) {
            System.out.println("[观猹] 试用截图失败：" + StrUtil.maxLength(e.getMessage(), 120));
            return null;
        } finally {
            if (shotPage != null) {
                try {
                    shotPage.close();
                } catch (Exception ignored) {
                    // 关不掉就算了，浏览器退出时会一起收掉
                }
            }
        }
    }

    Result post(String path, JSONObject body, String token) throws Exception {
        return request("POST", path, body == null ? null : body.toString(), token);
    }

    Result get(String path, String token) throws Exception {
        return request("GET", path, null, token);
    }

    private Result request(String method, String path, String body, String token) throws Exception {
        String url = path.startsWith("http") ? path : baseUrl + path;
        String script = fetchScript(method, url, token, body);
        page.evaluate(CLEAR_REPLY);
        page.evaluate(script);
        long deadline = System.nanoTime() + FETCH_WAIT_MILLIS * 1000000L;
        while (System.nanoTime() < deadline) {
            Object value = page.evaluate(READ_REPLY);
            if (value != null && !"null".equals(String.valueOf(value))) {
                return Result.parse(String.valueOf(value));
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("页面内请求超时：" + method + " " + path);
    }

    /**
     * 页面里发请求的脚本。cookie 由 credentials 带上，鉴权头用登录拿到的 token，
     * 和站点自己的前端在同一个上下文里发出去，TLS、Origin、Sec-Fetch 都由浏览器决定。
     */
    static String fetchScript(String method, String url, String token, String body) {
        StringBuilder headers = new StringBuilder("{\"content-type\":\"application/json\"");
        if (StrUtil.isNotBlank(token)) {
            headers.append(",\"authorization\":\"Bearer ").append(escape(token)).append('"');
        }
        headers.append('}');
        StringBuilder script = new StringBuilder();
        // 注意：jvppeteer 会把这个字符串当作函数体立即调用，所以这里给的是函数表达式，
        // 不能写成 (async()=>{...})() 这种自执行形式，否则它会认为「表达式不是函数」
        script.append("async () => { try { const r = await fetch(").append(quote(url))
                .append(",{method:").append(quote(method))
                .append(",credentials:'include',headers:").append(headers);
        if (body != null) {
            script.append(",body:").append(quote(body));
        }
        script.append("}); window.__watchaReply = JSON.stringify({status: r.status, body: await r.text()});")
                .append(" } catch (e) { window.__watchaReply = JSON.stringify({status: 0, body: String(e)}); } }");
        return script.toString();
    }

    /** 把无头标记换成普通 Chrome，其余保持引擎原样。 */
    static String normalizeUserAgent(String real) {
        String value = StrUtil.blankToDefault(real, CheckInTask.userAgent);
        String ua = value.replace("HeadlessChrome", "Chrome").trim();
        String version = versionOf(ua);
        return "0.0.0.0".equals(version) ? CheckInTask.userAgent : ua;
    }

    /** 从 UA 里取版本号。 */
    static String versionOf(String userAgent) {
        if (StrUtil.isBlank(userAgent)) {
            return "0.0.0.0";
        }
        String version = userAgent.replaceAll("^.*?(?:Chrome)/([0-9.]+).*$", "$1");
        return version.matches("[0-9.]+") ? version : "0.0.0.0";
    }

    private static String quote(String value) {
        return JSONUtil.quote(value == null ? "" : value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() {
        try {
            page.close();
        } catch (Exception ignored) {
            // 关闭失败不影响主流程
        }
        try {
            browser.close();
        } catch (Exception ignored) {
            // 同上
        }
    }

    /** 页面里 fetch 回来的结果。 */
    static final class Result {
        final int status;
        final String body;

        private Result(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }

        static Result parse(String json) {
            try {
                JSONObject object = JSONUtil.parseObj(json);
                return new Result(object.getInt("status", 0), object.getStr("body", ""));
            } catch (Exception e) {
                return new Result(0, json);
            }
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }

        JSONObject apiData() {
            try {
                return JSONUtil.parseObj(body).getJSONObject("data");
            } catch (Exception e) {
                return null;
            }
        }

        String describe() {
            String text = body.replaceAll("\\s+", " ");
            return "HTTP " + status + " " + (text.length() > 120 ? text.substring(0, 120) + "..." : text);
        }
    }
}
