package org.jayjay.autosignin.task;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jayjay.autosignin.entity.MessageBlock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SiemensCheckInTaskTest {

    private static final String APP_ID = "siemens_ciam140";
    private static final String FLOW_ID = "fid-1";

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger signActions = new AtomicInteger();
    private final AtomicInteger loginPosts = new AtomicInteger();
    private final AtomicInteger coinDraws = new AtomicInteger();
    private final AtomicInteger bigDraws = new AtomicInteger();
    private final AtomicInteger coinChances = new AtomicInteger();
    private final AtomicInteger bigChances = new AtomicInteger();
    private volatile String loginMode = "success";
    private volatile boolean alreadySigned;
    private volatile boolean anonymousDetail;
    private volatile boolean signedNow;

    @Before
    public void setUp() throws IOException {
        signActions.set(0);
        loginPosts.set(0);
        coinDraws.set(0);
        bigDraws.set(0);
        coinChances.set(0);
        bigChances.set(0);
        loginMode = "success";
        alreadySigned = false;
        anonymousDetail = false;
        signedNow = false;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/MySupport/index.aspx", this::handleIndex);
        server.createContext("/frontend/login", this::handleLoginPage);
        server.createContext("/api/bff/v1.2/developer/ciam/login/pwd", this::handleLogin);
        server.createContext("/sso/callback", this::handleCallback);
        server.createContext("/MySupport/AjaxAPI/SignInAjax.ashx", this::handleSign);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void skipsWhenCredentialsMissing() {
        SiemensCheckInTask task = new SiemensCheckInTask(new HashMap<>(), baseUrl);
        task.run();

        assertFalse(task.isEnabled());
        assertFalse(task.getNotificationSection().hasContent());
        assertEquals(0, loginPosts.get());
    }

    @Test
    public void signsInAndReportsProfile() {
        Map<String, String> environment = environment();
        SiemensCheckInTask task = new SiemensCheckInTask(environment, baseUrl);
        task.run();

        assertEquals("西门子签到", task.buildNotificationSection().getTitle());
        assertEquals("签到结果：签到成功\n已签到：4 天\n西币：88（本次 +0）", plainText(task));
        assertEquals(1, loginPosts.get());
        assertEquals(1, signActions.get());
    }

    @Test
    public void reportsAlreadySignedWithoutPostingAgain() {
        alreadySigned = true;
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        task.run();

        assertEquals("签到结果：今日已签到\n已签到：9 天\n西币：88（本次 +0）", plainText(task));
        assertEquals(0, signActions.get());
    }

    @Test
    public void reportsPasswordError() {
        loginMode = "password-error";
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        task.run();

        assertEquals("签到失败：用户名或密码错误", plainText(task));
        assertEquals(0, signActions.get());
    }

    @Test
    public void reportsTwoFactor() {
        loginMode = "two-factor";
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        task.run();

        assertEquals("签到失败：账号开启了二次验证，无法自动登录", plainText(task));
        assertEquals(0, signActions.get());
    }

    @Test
    public void rejectsAnonymousSignDetail() {
        anonymousDetail = true;
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        task.run();

        assertEquals("签到失败：登录后没有进入支持中心", plainText(task));
        assertEquals(0, signActions.get());
    }

    @Test
    public void sha1MatchesKnownVector() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", SiemensCheckInTask.sha1Hex("abc"));
    }

    @Test
    public void listsOnlyUnfinishedDailyTasks() {
        JSONObject tasks = JSONUtil.parseObj("{\"TaskList\":["
                + "{\"TaskType\":1,\"EventTasksStatus\":2,\"TaskName\":\"NewMySupport_SignIn\",\"TaskNameCN\":\"网站签到\"},"
                + "{\"TaskType\":1,\"EventTasksStatus\":1,\"TaskName\":\"NewMySupport_BBSReplyArticle\",\"TaskNameCN\":\"论坛回帖\"},"
                + "{\"TaskType\":2,\"EventTasksStatus\":1,\"TaskName\":\"BindPhone\",\"TaskNameCN\":\"绑定手机号\"}]}");

        assertEquals(1, SiemensCheckInTask.pendingDailyTasks(tasks).size());
        assertEquals("NewMySupport_BBSReplyArticle", SiemensCheckInTask.pendingDailyTasks(tasks).get(0));
        assertTrue(SiemensCheckInTask.pendingDailyTasks(null).isEmpty());
    }

    @Test
    public void mapsTaskCodesToChineseNames() {
        assertEquals("论坛发故事帖",
                SiemensCheckInTask.taskDisplayName("NewMySupport_BBSPostStoryArticle"));
        assertEquals("评论最佳答案",
                SiemensCheckInTask.taskDisplayName("NewMySupport_CommentBestAnswer"));
        assertEquals("UnknownTask", SiemensCheckInTask.taskDisplayName("UnknownTask"));
    }

    @Test
    public void detectsNearDuplicateContent() {
        String first = "老项目升级选S7-1500更稳妥，S7-300已列入退市计划，备件会逐步收紧。";
        String second = "老项目升级选S7-1500更稳妥，S7-300已列入退市计划，备件会逐步收紧！";
        String different = "先把DP网络保留，用CM1542-5做过渡，再按工位逐个换成PN从站。";

        assertTrue(SiemensCheckInTask.similarity(first, second) > 0.8);
        assertTrue(SiemensCheckInTask.similarity(first, different) < 0.3);
        assertEquals(0.0, SiemensCheckInTask.similarity(null, first), 0.001);
    }

    @Test
    public void drawsLotteryWhenChancesAvailable() {
        coinChances.set(2);
        bigChances.set(1);
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        task.run();

        assertEquals(2, coinDraws.get());
        assertEquals(1, bigDraws.get());
        String text = plainText(task);
        assertTrue(text.contains("抽奖：西币 2 次共 10，实物奖 1 次"));
    }

    @Test
    public void skipsLotteryWhenNoChances() {
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        task.run();

        assertEquals(0, coinDraws.get());
        assertEquals(0, bigDraws.get());
        assertFalse(plainText(task).contains("抽西币"));
    }

    @Test
    public void detectsNewTasksItDoesNotHandleYet() {
        JSONObject tasks = JSONUtil.parseObj("{\"TaskList\":["
                + "{\"TaskType\":1,\"EventTasksStatus\":1,\"TaskName\":\"NewMySupport_SomeNewTask\"}]}");

        assertEquals("NewMySupport_SomeNewTask", SiemensCheckInTask.pendingDailyTasks(tasks).get(0));
    }

    @Test
    public void detectsCaptchaAndThrottleCodes() {
        assertTrue(SiemensCheckInTask.isCaptchaRequired(
                JSONUtil.parseObj("{\"code\":\"Params.Blank.CaptchaText\"}")));
        assertTrue(SiemensCheckInTask.isCaptchaRequired(
                JSONUtil.parseObj("{\"code\":\"Operation.Failure\",\"message\":\"Params.Blank.CaptchaCode\"}")));
        assertFalse(SiemensCheckInTask.isCaptchaRequired(
                JSONUtil.parseObj("{\"code\":\"Operation.Failure.Username.Or.Password.Error\"}")));
        assertTrue(SiemensCheckInTask.isLoginThrottled(
                JSONUtil.parseObj("{\"code\":\"Operation.Failure.Auth.Too.Frequency\"}")));
        assertFalse(SiemensCheckInTask.isLoginThrottled(JSONUtil.parseObj("{\"code\":\"Operation.Success\"}")));
    }

    @Test
    public void describesLockedAndThrottledAccounts() {
        assertEquals("登录太频繁已被限制，请稍后重试",
                SiemensCheckInTask.describeLoginCode("Operation.Failure.Auth.Too.Frequency"));
        assertEquals("账号被临时锁定，请稍后重试",
                SiemensCheckInTask.describeLoginCode("Operation.Failure.User.Locked"));
    }

    @Test
    public void skipsDailyTasksWhenAlreadyRanToday() throws Exception {
        Map<String, String> environment = environment();
        environment.put("SIEMENS_DAILY_TASKS", "true");
        SiemensCheckInTask first = new SiemensCheckInTask(environment, baseUrl, true);
        first.run();

        // 同一天再跑一次时应直接跳过，不再发任何帖子。
        SiemensCheckInTask second = new SiemensCheckInTask(environment, baseUrl, true);
        second.run();
        assertTrue(plainText(second).contains("今天已执行过"));

        java.nio.file.Path marker = SiemensCheckInTask.stateDir().resolve("siemens-ran-"
                + SiemensCheckInTask.sha1Hex("user@example.com").substring(0, 12) + ".txt");
        java.nio.file.Files.deleteIfExists(marker);
    }

    @Test
    public void skipsDailyTasksWhenDisabled() {
        Map<String, String> environment = environment();
        environment.put("SIEMENS_DAILY_TASKS", "false");
        SiemensCheckInTask task = new SiemensCheckInTask(environment, baseUrl, true);
        task.run();

        assertEquals("签到结果：签到成功\n已签到：4 天\n西币：88（本次 +0）", plainText(task));
        assertEquals(1, signActions.get());
    }

    @Test
    public void sendsDomainCookiesOnlyToMatchingHosts() {
        SiemensCheckInTask.CookieJar jar = new SiemensCheckInTask.CookieJar();
        jar.store("https://oso.siemens.com.cn/sso/idaas/ssocallback",
                "SiemensAuth=ok; Domain=.siemens.com.cn; Path=/; Secure");
        jar.store("https://www.ad.siemens.com.cn/MySupport/index.aspx",
                "ASP.NET_SessionId=abc; Path=/; HttpOnly");

        String supportCookie = jar.headerFor("https://www.ad.siemens.com.cn/MySupport/AjaxAPI/SignInAjax.ashx");
        assertTrue(supportCookie.contains("SiemensAuth=ok"));
        assertTrue(supportCookie.contains("ASP.NET_SessionId=abc"));
        assertFalse(jar.headerFor("http://www.ad.siemens.com.cn/MySupport/index.aspx").contains("SiemensAuth"));
        assertFalse(jar.headerFor("https://oso.siemens.com.cn/sso/idaas/ssocallback").contains("ASP.NET_SessionId"));
        assertEquals("", jar.headerFor("https://example.com/"));
    }

    private static String plainText(SiemensCheckInTask task) {
        StringBuilder text = new StringBuilder();
        for (MessageBlock block : task.getMessageBlocks()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(block.toPlainText());
        }
        return text.toString();
    }

    private Map<String, String> environment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("SIEMENS_USERNAME", "user@example.com");
        environment.put("SIEMENS_PASSWORD", "secret-password");
        return environment;
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!authed(exchange)) {
            redirect(exchange, baseUrl + "/frontend/login?client_id=test");
            return;
        }
        write(exchange, 200, "<html><script id=\"navigationjs\" useridattr=\"42\"></script>"
                + "<h6 class=\"singleLine\">西门子用户</h6>"
                + "<a href=\"/MySupport/MyPrize.aspx\">88</a></html>", "text/html; charset=utf-8");
    }

    private void handleLoginPage(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Set-Cookie", "acw_tc=login-cookie; Path=/");
        write(exchange, 200, "<html><script>window.idaas = { fId: \"" + FLOW_ID
                + "\", idaasAppId: \"" + APP_ID + "\" };</script></html>", "text/html; charset=utf-8");
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        loginPosts.incrementAndGet();
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        String timestamp = exchange.getRequestHeaders().getFirst("idaasTimeStamp");
        String sign = exchange.getRequestHeaders().getFirst("idaasSign");
        JSONObject body = JSONUtil.parseObj(readBody(exchange));
        boolean headerOk = cookie != null && cookie.contains("acw_tc=login-cookie")
                && SiemensCheckInTask.sha1Hex(APP_ID + timestamp).equals(sign)
                && FLOW_ID.equals(body.getStr("fId"))
                && "user@example.com".equals(body.getStr("username"))
                && "secret-password".equals(body.getStr("password"));
        if (!headerOk) {
            write(exchange, 500, "{\"success\":false,\"code\":\"BadTestRequest\"}", "application/json");
            return;
        }
        if ("password-error".equals(loginMode)) {
            write(exchange, 200, "{\"success\":false,\"code\":\"Operation.Failure\","
                    + "\"message\":\"Operation.Failure.Username.Or.Password.Error\",\"data\":null}", "application/json");
            return;
        }
        if ("two-factor".equals(loginMode)) {
            write(exchange, 200, "{\"success\":true,\"code\":\"Operation.Success\",\"message\":\"Operation.Success\","
                    + "\"data\":{\"flowType\":\"NEED_TWO_FACTOR\",\"fId\":\"" + FLOW_ID + "\"}}", "application/json");
            return;
        }
        write(exchange, 200, "{\"success\":true,\"code\":\"Operation.Success\",\"message\":\"Operation.Success\","
                + "\"data\":{\"redirectUrl\":\"" + baseUrl + "/sso/callback\"}}", "application/json");
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Set-Cookie", "SiemensAuth=ok; Path=/");
        redirect(exchange, baseUrl + "/MySupport/index.aspx");
    }

    private void handleSign(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        boolean authed = authed(exchange);
        if (body.contains("action=awardcoin")) {
            coinDraws.incrementAndGet();
            int left = coinChances.get() > 0 ? coinChances.decrementAndGet() : 0;
            write(exchange, 200, "{\"awardCoinCount\":" + left
                    + ",\"awardType\":1,\"msg\":\"恭喜获得5西币\"}", "application/json");
            return;
        }
        if (body.contains("action=bigaward")) {
            bigDraws.incrementAndGet();
            int left = bigChances.get() > 0 ? bigChances.decrementAndGet() : 0;
            write(exchange, 200, "{\"bigAwardCount\":" + left
                    + ",\"msg\":\"谢谢参与\"}", "application/json");
            return;
        }
        if (body.contains("action=sign") && !body.contains("getsignbydate")) {
            signActions.incrementAndGet();
            if (authed) {
                signedNow = true;
            }
            write(exchange, 200, authed ? "true" : "false", "text/plain; charset=utf-8");
            return;
        }
        if (anonymousDetail || !authed) {
            write(exchange, 200, "{\"TodayHaveSign\":true,\"SignCount\":2869,"
                    + "\"SignDetail\":{\"UserID\":-1}}", "application/json");
            return;
        }
        boolean signed = alreadySigned || signedNow;
        if (!signed) {
            write(exchange, 200, "{\"TodayHaveSign\":false,\"SignCount\":1,\"SignDetail\":null}", "application/json");
            return;
        }
        int count = alreadySigned ? 9 : 4;
        write(exchange, 200, "{\"TodayHaveSign\":true,\"SignCount\":" + count
                + ",\"SignDetail\":{\"UserID\":42}"
                + ",\"GetCoinAwardCount\":" + coinChances.get()
                + ",\"GetBigAwardCount\":" + bigChances.get() + "}", "application/json");
    }

    private static boolean authed(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        return cookie != null && cookie.contains("SiemensAuth=ok");
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void write(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
        exchange.close();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = exchange.getRequestBody()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
