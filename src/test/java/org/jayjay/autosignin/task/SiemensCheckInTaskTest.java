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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
    /** 每日任务列表里未完成的项，按顺序返回。 */
    private final AtomicInteger tasksLeft = new AtomicInteger();
    /** 发帖被站点拒绝时置位，模拟「请选择帖子类别」。 */
    private volatile boolean rejectPost;
    /** 任务列表接口不可用时置位，模拟接口故障。 */
    private volatile boolean tasksUnreadable;

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
        tasksLeft.set(0);
        rejectPost = false;
        tasksUnreadable = false;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/MySupport/index.aspx", this::handleIndex);
        server.createContext("/frontend/login", this::handleLoginPage);
        server.createContext("/api/bff/v1.2/developer/ciam/login/pwd", this::handleLogin);
        server.createContext("/sso/callback", this::handleCallback);
        server.createContext("/MySupport/AjaxAPI/SignInAjax.ashx", this::handleSign);
        server.createContext("/MySupport/AjaxAPI/EventTasksAjax.ashx", this::handleTasks);
        server.createContext("/club/bbs/AjaxApi/PostCommitAjax.ashx", this::handlePostCommit);
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
    public void readsFirstSortOptionForDiscussPost() {
        String html = "<option  value=\"84\" >软件编程</option> <option  value=\"85\" >通信</option>";
        assertEquals("84", SiemensForumActions.sortIdFrom(html));
        assertEquals(null, SiemensForumActions.sortIdFrom("<html></html>"));
        assertEquals(null, SiemensForumActions.sortIdFrom(null));
    }

    @Test
    public void detectsAlreadyFavoritedQuestion() {
        // 弹窗对已收藏问答返回「您已经收藏过了」，不能当成可提交表单重复提交。
        assertTrue(SiemensAnswerActions.isAlreadyFavorited("<div>您已经收藏过了！<a>查看我的收藏夹</a></div>"));
        assertFalse(SiemensAnswerActions.isAlreadyFavorited("<form id=\"form1\"><input name=\"tagSave\"></form>"));
        assertFalse(SiemensAnswerActions.isAlreadyFavorited(null));
    }

    @Test
    public void favoriteAlwaysCarriesTag() {
        // 站点要求收藏必须带标签，不带标签只会原样返回表单、不报错也不生效。
        assertFalse(SiemensAnswerActions.favoriteTag().trim().isEmpty());
    }

    @Test
    public void recognizesNewUserPostQuotaMessage() {
        // 新注册账号当日主贴上限，站点文案是「新注册用户当日主贴数量不能超过2个」。
        assertTrue(SiemensForumActions.isQuotaLimited("新注册用户当日主贴数量不能超过2个，请稍候重试！"));
        assertFalse(SiemensForumActions.isQuotaLimited("请选择帖子类别。"));
        assertFalse(SiemensForumActions.isQuotaLimited(null));
    }

    @Test
    public void doesNotReportStoryPostAsDoneWhenItFailed() {
        List<String> pending = Collections.singletonList("NewMySupport_BBSPostStoryArticle");

        // 故事帖失败：必须仍然列为未完成，不能报「已全部完成」。
        assertEquals("未完成 [论坛发故事帖]",
                SiemensCheckInTask.remainingText(pending, false, null));
        // 故事帖提交成功：站点审核期间状态还是未完成，但不该再列出来。
        assertEquals("已全部完成，故事帖待审核",
                SiemensCheckInTask.remainingText(pending, true, null));
    }

    @Test
    public void listsRemainingTasksAfterAbort() {
        List<String> pending = Arrays.asList("NewMySupport_FAQuestion",
                "NewMySupport_CollectionQA", "NewMySupport_BBSPostStoryArticle");

        assertEquals("连续失败 2 次，已中止，未完成 [找答案提问, 收藏问答, 论坛发故事帖]",
                SiemensCheckInTask.remainingText(pending, false, "连续失败 2 次，已中止"));
    }

    @Test
    public void fallsBackToBuiltInContentWhenAiUnavailable() {
        Map<String, String> environment = environment();
        SiemensCheckInTask task = new SiemensCheckInTask(environment, baseUrl);
        String title = task.fallbackContent("posttitle");
        String body = task.fallbackContent("postbody");
        assertFalse(title.trim().isEmpty());
        assertFalse(body.trim().isEmpty());
        assertEquals(null, task.fallbackContent("unknown-kind"));
        // 同一次运行内重复取会换一条，避免同轮多个帖子用同一个标题。
        String second = task.fallbackContent("posttitle");
        assertNotEquals(title, second);
        // 连取一轮后仍要能取到内容，不会因为计数越界而返回 null。
        for (int i = 0; i < 10; i++) {
            assertFalse(task.fallbackContent("posttitle").trim().isEmpty());
        }
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
    public void reportsStoryPostFailureInsteadOfAllDone() throws Exception {
        tasksLeft.set(1);
        rejectPost = true;
        Map<String, String> environment = environment();
        environment.put("SIEMENS_DAILY_TASKS", "true");
        SiemensCheckInTask task = new SiemensCheckInTask(environment, baseUrl, true);
        try {
            task.run();
            String text = plainText(task);
            // 故事帖被站点拒绝时不能报「已全部完成」，必须照实列出未完成。
            assertTrue("应报未完成，实际：" + text, text.contains("未完成 [论坛发故事帖]"));
            assertFalse("不能报已全部完成，实际：" + text, text.contains("已全部完成"));
        } finally {
            clearRunMarker();
        }
    }

    @Test
    public void doesNotClaimAllDoneWhenTaskListUnreadable() throws Exception {
        tasksUnreadable = true;
        Map<String, String> environment = environment();
        environment.put("SIEMENS_DAILY_TASKS", "true");
        SiemensCheckInTask task = new SiemensCheckInTask(environment, baseUrl, true);
        try {
            task.run();
            String text = plainText(task);
            // 拿不到任务列表时既不能报「已全部完成」，也不能写当天标记。
            assertFalse("不能报已全部完成，实际：" + text, text.contains("已全部完成"));
            assertTrue("应说明读取失败，实际：" + text, text.contains("读取任务列表失败"));
        } finally {
            clearRunMarker();
            tasksUnreadable = false;
        }
    }

    /** 清掉当天「已执行」标记，避免测试之间互相影响。 */
    private static void clearRunMarker() throws Exception {
        java.nio.file.Path marker = SiemensCheckInTask.stateDir().resolve("siemens-ran-"
                + SiemensCheckInTask.sha1Hex("user@example.com").substring(0, 12) + ".txt");
        java.nio.file.Files.deleteIfExists(marker);
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

    @Test
    public void savesAndRestoresSessionCookies() throws Exception {
        SiemensCheckInTask.CookieJar jar = new SiemensCheckInTask.CookieJar();
        jar.store("https://oso.siemens.com.cn/sso/idaas/ssocallback",
                "SiemensAuth=ok; Domain=.siemens.com.cn; Path=/; Secure");
        jar.store("https://www.ad.siemens.com.cn/MySupport/index.aspx",
                "ASP.NET_SessionId=abc; Path=/; HttpOnly");
        java.nio.file.Path file = java.nio.file.Files.createTempFile("siemens-session", ".txt");
        try {
            jar.save(file);

            SiemensCheckInTask.CookieJar restored = new SiemensCheckInTask.CookieJar();
            assertTrue(restored.load(file));
            String header = restored.headerFor("https://www.ad.siemens.com.cn/MySupport/index.aspx");
            assertTrue(header.contains("SiemensAuth=ok"));
            assertTrue(header.contains("ASP.NET_SessionId=abc"));
            // domain cookie 和 secure 标记都要完整保留。
            assertFalse(restored.headerFor("http://www.ad.siemens.com.cn/MySupport/index.aspx")
                    .contains("SiemensAuth"));

            restored.clear();
            assertEquals("", restored.headerFor("https://www.ad.siemens.com.cn/MySupport/index.aspx"));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test
    public void ignoresBrokenSessionFile() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("siemens-session", ".txt");
        try {
            java.nio.file.Files.write(file, "garbage-line\n".getBytes(StandardCharsets.UTF_8));
            SiemensCheckInTask.CookieJar jar = new SiemensCheckInTask.CookieJar();
            assertFalse(jar.load(file));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
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

    private void handleTasks(HttpExchange exchange) throws IOException {
        if (tasksUnreadable) {
            write(exchange, 500, "upstream error", "text/plain; charset=utf-8");
            return;
        }
        int left = tasksLeft.get();
        StringBuilder tasks = new StringBuilder();
        tasks.append("{\"TaskType\":1,\"EventTasksStatus\":2,\"TaskName\":\"NewMySupport_SignIn\"}");
        if (left >= 1) {
            tasks.append(",{\"TaskType\":1,\"EventTasksStatus\":1,\"TaskName\":\"NewMySupport_BBSPostStoryArticle\"}");
        }
        write(exchange, 200, "{\"TaskList\":[" + tasks + "]}", "application/json; charset=utf-8");
    }

    private void handlePostCommit(HttpExchange exchange) throws IOException {
        readBody(exchange);
        if (rejectPost) {
            write(exchange, 200, "{\"Head\":{\"Ret\":false,\"Message\":\"请选择帖子类别。\"}}",
                    "application/json; charset=utf-8");
            return;
        }
        write(exchange, 200, "{\"Head\":{\"Ret\":true}}", "application/json; charset=utf-8");
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
