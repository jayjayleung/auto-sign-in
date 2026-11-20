package org.jayjay.autosignin.task;

import cn.hutool.json.JSONArray;
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
    /** 消息中心里待领取的获奖通知 ID，按顺序返回。 */
    private final java.util.Set<String> coinNotices = new java.util.LinkedHashSet<>();
    /** 领取接口挂掉时置位，用来验证失败不记账。 */
    private volatile boolean pickupBroken;
    /** 领取接口被调用的次数。 */
    private final AtomicInteger pickupPosts = new AtomicInteger();
    /** 7 天挑战赛的题目与正确答案，站点接口会一起下发。 */
    private volatile String challengeQuestionId = "501";
    private volatile String challengeAnswer = "A,B";
    /** 站点是否认为这次答题正确；与 challengeAnswer 分开，便于模拟「答错」。 */
    private volatile boolean challengeCorrect = true;
    /** 站点是否允许领取第一天奖品。 */
    private volatile boolean challengeOneDayPrize = true;
    /** 答题页上「点击领奖」按钮是否可见，模拟前一天遗留的未领奖品。 */
    private volatile boolean challengePageOneDay;
    /** 答题页上「点击抽奖」按钮是否可见。 */
    private volatile boolean challengePageSevenDay;
    /** 记录接口调用顺序，用于验证领奖排在答题之前。 */
    private volatile java.util.List<String> challengeCallLog;
    private final AtomicInteger challengeSubmits = new AtomicInteger();
    private final AtomicInteger challengeClaims = new AtomicInteger();

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
        coinNotices.clear();
        pickupBroken = false;
        pickupPosts.set(0);
        challengeQuestionId = "501";
        challengeAnswer = "A,B";
        challengeCorrect = true;
        challengeOneDayPrize = true;
        challengePageOneDay = false;
        challengePageSevenDay = false;
        challengeSubmits.set(0);
        challengeClaims.set(0);
        challengeCallLog = null;
        clearChallengeMarker();
        clearCoinNotices();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/MySupport/index.aspx", this::handleIndex);
        server.createContext("/frontend/login", this::handleLoginPage);
        server.createContext("/api/bff/v1.2/developer/ciam/login/pwd", this::handleLogin);
        server.createContext("/sso/callback", this::handleCallback);
        server.createContext("/MySupport/AjaxAPI/SignInAjax.ashx", this::handleSign);
        server.createContext("/MySupport/AjaxAPI/EventTasksAjax.ashx", this::handleTasks);
        server.createContext("/MySupport/MyMsgmain.aspx", this::handleMessageList);
        server.createContext("/MySupport/MyMsgDetail.aspx", this::handleMessageDetail);
        server.createContext("/club/bbs/PickupCoin.aspx", this::handlePickupCoin);
        server.createContext("/event/AjaxAPI/SevenDaysChallenge.ashx", this::handleChallenge);
        server.createContext("/event/AjaxAPI/SevenDaysPrize.ashx", this::handleChallengePrize);
        server.createContext("/Event/7DaysChallenge/Question.aspx", this::handleChallengePage);
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
        assertEquals("签到成功，连续 4 天，西币 88", plainText(task));
        assertEquals(1, loginPosts.get());
        assertEquals(1, signActions.get());
    }

    @Test
    public void reportsAlreadySignedWithoutPostingAgain() {
        alreadySigned = true;
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        task.run();

        assertEquals("已签到，连续 9 天，西币 88", plainText(task));
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
    public void dateLogicIgnoresJvmDefaultTimeZone() throws Exception {
        // GitHub Actions 的 runner 是 UTC，容器是 Asia/Shanghai，
        // 日期判断必须显式按北京时间，不能跟着 JVM 默认时区走。
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            Map<String, String> environment = environment();
            SiemensCheckInTask utcTask = new SiemensCheckInTask(environment, baseUrl, true);
            java.lang.reflect.Method mark =
                    SiemensCheckInTask.class.getDeclaredMethod("markRanToday");
            mark.setAccessible(true);
            mark.invoke(utcTask);
            java.lang.reflect.Method ran =
                    SiemensCheckInTask.class.getDeclaredMethod("alreadyRanToday");
            ran.setAccessible(true);
            assertEquals(true, ran.invoke(utcTask));

            // 标记文件里存的应该是北京时间的当天，而不是 UTC 的当天。
            java.nio.file.Path marker = SiemensCheckInTask.stateDir().resolve("siemens-ran-"
                    + SiemensCheckInTask.sha1Hex("user@example.com").substring(0, 12) + ".txt");
            String saved = new String(java.nio.file.Files.readAllBytes(marker),
                    StandardCharsets.UTF_8).trim();
            assertEquals(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(), saved);
        } finally {
            java.util.TimeZone.setDefault(original);
            clearRunMarker();
        }
    }

    @Test
    public void firstFillStaysBelowLowWatermark() {
        // 首次只补到 FIRST_FILL，且水位线必须低于它，
        // 否则第二天就会立刻再次触发补货，失去「逐步累积」的意义。
        assertTrue("水位线必须低于首次补货量", ContentBank.LOW_WATERMARK < ContentBank.FIRST_FILL);
        assertTrue("首次补货量不应超过目标库存", ContentBank.FIRST_FILL < ContentBank.TARGET_SIZE);
    }

    @Test
    public void countsOnlyActuallyStoredItems() throws Exception {
        // 库补货时如果标题全撞车，不能虚报已补充条数。
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("bank");
        java.nio.file.Path file = dir.resolve("bank.json");
        try {
            SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
            ContentBank bank = new ContentBank(task, file);
            // 直接构造一个含重复标题的数组，验证 append 的返回值语义。
            JSONArray items = JSONUtil.parseArray("[{\"kind\":\"ask\",\"title\":\"同一条\",\"body\":\"正文\",\"status\":\"ready\"}]");
            java.lang.reflect.Field field = ContentBank.class.getDeclaredField("items");
            field.setAccessible(true);
            field.set(bank, items);

            java.lang.reflect.Method append = ContentBank.class.getDeclaredMethod(
                    "append", String.class, String.class, String.class, String.class);
            append.setAccessible(true);
            assertEquals(false, append.invoke(bank, "ask", "同一条", "另一段正文", "ready"));
            assertEquals(true, append.invoke(bank, "ask", "新标题", "正文", "ready"));
            assertEquals(2, items.size());
        } finally {
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    @Test
    public void parsesContentBankJsonWithWrapper() {
        // 模型常把 JSON 包在 ```json 里，也会在前后加解释文字。
        String raw = "```json\n[{\"title\":\"标题一\",\"body\":\"正文一\"},"
                + "{\"title\":\"标题二\",\"body\":\"正文二\"}]\n```";
        assertEquals(2, ContentBank.parseItems(raw).size());
        assertEquals("标题一", ContentBank.parseItems(raw).get(0)[0]);

        // 前后有杂字时按第一个 [ 到最后一个 ] 截取。
        String noisy = "好的，结果如下：\n[{\"title\":\"A\",\"body\":\"B\"}]\n以上。";
        assertEquals(1, ContentBank.parseItems(noisy).size());

        // 缺字段、标题重复、完全不是 JSON 的情况都要丢掉。
        assertEquals(0, ContentBank.parseItems("[{\"title\":\"\",\"body\":\"x\"}]").size());
        assertEquals(1, ContentBank.parseItems(
                "[{\"title\":\"同\",\"body\":\"1\"},{\"title\":\"同\",\"body\":\"2\"}]").size());
        assertEquals(0, ContentBank.parseItems("这不是 JSON").size());
        assertEquals(0, ContentBank.parseItems(null).size());
    }

    @Test
    public void spreadsTargetsAcrossAccounts() {
        // 三个容器账号要落在不同序号上，否则每天都操作同一条问答或帖子。
        java.util.Set<Integer> indexes = new java.util.HashSet<>();
        for (String username : new String[]{"15913213996", "15952755546", "18711281575"}) {
            Map<String, String> environment = environment();
            environment.put("SIEMENS_USERNAME", username);
            indexes.add(new SiemensCheckInTask(environment, baseUrl).accountIndex());
        }
        assertEquals(3, indexes.size());
    }

    @Test
    public void fallbackModelRescuesTimedOutPrimary() throws Exception {
        // 早上三个容器同时打同一个文案网关，主模型经常超时；
        // 这时必须换备用模型再试一次，而不是直接把这一项任务判失败。
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        List<String> models = Collections.synchronizedList(new java.util.ArrayList<>());
        HttpServer ai = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ai.createContext("/v1/chat/completions", exchange -> {
            String request = readBody(exchange);
            String model = JSONUtil.parseObj(request).getStr("model");
            models.add(model);
            if ("main-model".equals(model)) {
                primaryCalls.incrementAndGet();
                // 主模型：不返回响应体，模拟超时/网关异常。
                exchange.sendResponseHeaders(504, -1);
                exchange.close();
                return;
            }
            fallbackCalls.incrementAndGet();
            String content = JSONUtil.createObj()
                    .set("choices", new JSONArray().put(JSONUtil.createObj()
                            .set("message", JSONUtil.createObj().set("content", "备用模型写的回答"))))
                    .toString();
            write(exchange, 200, content, "application/json; charset=utf-8");
        });
        ai.start();
        try {
            Map<String, String> environment = environment();
            environment.put("AI_BASE_URL", "http://127.0.0.1:" + ai.getAddress().getPort() + "/v1");
            environment.put("AI_API_KEY", "key");
            environment.put("AI_MODEL", "main-model");
            environment.put("AI_FALLBACK_MODEL", "fallback-model");
            SiemensCheckInTask task = new SiemensCheckInTask(environment, baseUrl);

            assertEquals("备用模型写的回答", task.generateWithAi("写点什么", null));
            assertEquals(1, primaryCalls.get());
            assertEquals(1, fallbackCalls.get());
            assertEquals(Arrays.asList("main-model", "fallback-model"), models);
            // 救回来之后不该被判成熔断。
            assertFalse(task.aiExhausted());
        } finally {
            ai.stop(0);
        }
    }

    @Test
    public void givesUpAfterRepeatedAiFailures() throws Exception {
        // 主备都失败时，连续两次之后就熔断本轮，避免每项任务都白等两轮超时。
        HttpServer ai = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ai.createContext("/v1/chat/completions", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        ai.start();
        try {
            Map<String, String> environment = environment();
            environment.put("AI_BASE_URL", "http://127.0.0.1:" + ai.getAddress().getPort() + "/v1");
            environment.put("AI_API_KEY", "key");
            environment.put("AI_MODEL", "main-model");
            environment.put("AI_FALLBACK_MODEL", "fallback-model");
            SiemensCheckInTask task = new SiemensCheckInTask(environment, baseUrl);

            assertEquals(null, task.generateWithAi("第一次", null));
            assertFalse("只失败一次还不能熔断", task.aiExhausted());

            assertEquals(null, task.generateWithAi("第二次", null));
            assertTrue("连续两次失败后应熔断", task.aiExhausted());
        } finally {
            ai.stop(0);
        }
    }

    @Test
    public void contentBankFallsBackToSecondModel() throws Exception {
        // 补库走的是另一条直连链路，同样要在主模型失败后换备用模型。
        List<String> models = Collections.synchronizedList(new java.util.ArrayList<>());
        HttpServer ai = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ai.createContext("/v1/chat/completions", exchange -> {
            String model = JSONUtil.parseObj(readBody(exchange)).getStr("model");
            models.add(model);
            if ("main-model".equals(model)) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            String content = "[{\"title\":\"备用模型标题\",\"body\":\"备用模型正文\"}]";
            String body = JSONUtil.createObj()
                    .set("choices", new JSONArray().put(JSONUtil.createObj()
                            .set("message", JSONUtil.createObj().set("content", content))))
                    .toString();
            write(exchange, 200, body, "application/json; charset=utf-8");
        });
        ai.start();
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("bank-ai");
            java.nio.file.Path file = dir.resolve("bank.json");
            try {
                Map<String, String> environment = environment();
                environment.put("AI_BASE_URL", "http://127.0.0.1:" + ai.getAddress().getPort() + "/v1");
                environment.put("AI_API_KEY", "key");
                environment.put("AI_MODEL", "main-model");
                environment.put("AI_FALLBACK_MODEL", "fallback-model");
                SiemensCheckInTask task = new SiemensCheckInTask(environment, baseUrl);
                ContentBank bank = new ContentBank(task, file);

                java.lang.reflect.Method generate = ContentBank.class.getDeclaredMethod(
                        "generate", String.class, int.class);
                generate.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<String[]> generated = (List<String[]>) generate.invoke(bank, ContentBank.ASK, 1);

                assertEquals(1, generated.size());
                assertEquals("备用模型标题", generated.get(0)[0]);
                assertEquals(Arrays.asList("main-model", "fallback-model"), models);
            } finally {
                java.nio.file.Files.deleteIfExists(file);
                java.nio.file.Files.deleteIfExists(dir);
            }
        } finally {
            ai.stop(0);
        }
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
    public void readsQuestionTitleFromCurrentMarkup() {
        // 站点改版后详情页标题在 h1，老页面是 h4.singleLine，两个都要能取到。
        assertEquals("报表脚本解析",
                SiemensAnswerActions.firstElementText("<html><h1>报表脚本解析</h1></html>", "h1", "h4.singleLine"));
        assertEquals("老页面标题",
                SiemensAnswerActions.firstElementText("<html><h4 class=\"singleLine\">老页面标题</h4></html>",
                        "h1", "h4.singleLine"));
        // 都取不到时必须返回 null，让调用方走兜底，而不是把 null 塞进提示词。
        assertEquals(null, SiemensAnswerActions.firstElementText("<html></html>", "h1", "h4.singleLine"));
        assertEquals(null, SiemensAnswerActions.firstElementText(null, "h1"));
    }

    @Test
    public void stripsHtmlFromAnswerText() {
        // 列表接口的最佳答案正文是带标签的 HTML，喂给模型前要清掉。
        assertEquals("先看物理层 再查程序",
                SiemensAnswerActions.plainText("<p>先看物理层</p><p>再查程序</p>", 50));
        assertEquals("", SiemensAnswerActions.plainText(null, 50));
        assertEquals(5, SiemensAnswerActions.plainText("一二三四五六七八", 5).length());
    }

    @Test
    public void allThreeAccountsCommentOnTheSameDayAndRestTogether() throws Exception {
        // 关键点：不是「每个账号各自隔天」，而是三个账号共用一份日历、同一天一起评。
        // 各自隔天会漂移成 A/C 在 17 号、B 在 19 号，结果每天只有一两个账号能动。
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("pipeline");
        java.nio.file.Path file = dir.resolve(SiemensPipeline.FILE_NAME);
        try {
            String a = "1820833";
            String b = "1579302";
            String c = "1820814";
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            java.lang.reflect.Constructor<SiemensPipeline> ctor =
                    SiemensPipeline.class.getDeclaredConstructor(SiemensCheckInTask.class);
            ctor.setAccessible(true);
            SiemensPipeline pipeline = ctor.newInstance(task);
            java.lang.reflect.Field field = SiemensPipeline.class.getDeclaredField("file");
            field.setAccessible(true);
            field.set(pipeline, file);

            java.lang.reflect.Method allowed =
                    SiemensPipeline.class.getDeclaredMethod("siblingCommentAllowed", String.class);
            allowed.setAccessible(true);
            java.lang.reflect.Method mark =
                    SiemensPipeline.class.getDeclaredMethod("markSiblingComment", String.class);
            mark.setAccessible(true);

            // 从没互评过：三个账号都允许。
            for (String uid : new String[]{a, b, c}) {
                assertTrue(uid + " 首次应允许", (Boolean) allowed.invoke(pipeline, uid));
            }

            // 三个账号今天一起评过：当天再跑谁都不允许，保证同一天不重复。
            for (String uid : new String[]{a, b, c}) {
                mark.invoke(pipeline, uid);
            }
            for (String uid : new String[]{a, b, c}) {
                assertFalse(uid + " 当天已评过不应再评", (Boolean) allowed.invoke(pipeline, uid));
            }

            // 第二天（隔天休息日）：三个账号一起休息，不会只有某一个偷跑。
            setCommentDates(file, today.minusDays(1), a, b, c);
            resetStore(pipeline);
            for (String uid : new String[]{a, b, c}) {
                assertFalse(uid + " 休息日不应互评", (Boolean) allowed.invoke(pipeline, uid));
            }

            // 后天（隔天开口日）：三个账号一起恢复。
            setCommentDates(file, today.minusDays(2), a, b, c);
            resetStore(pipeline);
            for (String uid : new String[]{a, b, c}) {
                assertTrue(uid + " 开口日应允许互评", (Boolean) allowed.invoke(pipeline, uid));
            }

            // 错位状态（A/C 是 2 天前、B 是 1 天前）：日历取最早那天当锚点，所以今天是开口日。
            // A/C 距上次已满 2 天，正常参与；B 昨天刚评过，自己再休息一天。
            // 这样日历不会因为某个账号晚评一天就把另外两个也一起拖住。
            setCommentDates(file, today.minusDays(1), b);
            setCommentDates(file, today.minusDays(2), a, c);
            resetStore(pipeline);
            assertTrue(a + " 错位状态下日历开口、自身已满间隔，应允许",
                    (Boolean) allowed.invoke(pipeline, a));
            assertTrue(c + " 错位状态下日历开口、自身已满间隔，应允许",
                    (Boolean) allowed.invoke(pipeline, c));
            assertFalse(b + " 昨天刚评过，不应连续两天评", (Boolean) allowed.invoke(pipeline, b));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    /** 把指定账号的上次互评日期改成给定日期，其余账号保持原样。 */
    private static void setCommentDates(java.nio.file.Path file, java.time.LocalDate day, String... uids)
            throws IOException {
        JSONObject store = JSONUtil.parseObj(
                new String(java.nio.file.Files.readAllBytes(file), StandardCharsets.UTF_8));
        JSONObject comments = store.getJSONObject("siblingComments");
        if (comments == null) {
            comments = JSONUtil.createObj();
            store.set("siblingComments", comments);
        }
        for (String uid : uids) {
            comments.set(uid, day.toString());
        }
        java.nio.file.Files.write(file, store.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 清掉内存缓存，让下次读取以文件为准。 */
    private static void resetStore(SiemensPipeline pipeline) throws Exception {
        java.lang.reflect.Field storeField = SiemensPipeline.class.getDeclaredField("store");
        storeField.setAccessible(true);
        storeField.set(pipeline, null);
    }

    @Test
    public void siblingCommentUsesTheActualAnswerText() throws Exception {
        // 互评必须按该条答案的原文生成，不能套用通用措辞——否则和评论区内容对不上。
        // 这里验证取答案原文的兜底路径：拿不到时返回空串，由调用方改用内置文案。
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("pipeline");
        java.nio.file.Path file = dir.resolve(SiemensPipeline.FILE_NAME);
        try {
            java.lang.reflect.Constructor<SiemensPipeline> ctor =
                    SiemensPipeline.class.getDeclaredConstructor(SiemensCheckInTask.class);
            ctor.setAccessible(true);
            SiemensPipeline pipeline = ctor.newInstance(task);
            java.lang.reflect.Field field = SiemensPipeline.class.getDeclaredField("file");
            field.setAccessible(true);
            field.set(pipeline, file);

            java.lang.reflect.Method answersMethod =
                    SiemensPipeline.class.getDeclaredMethod("answerText", String.class, String.class);
            answersMethod.setAccessible(true);
            // 本地假站点没有问答接口，取不到原文时必须返回空串而不是 null，调用方据此兜底。
            assertEquals("", answersMethod.invoke(pipeline, "999999", "1"));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    @Test
    public void skippingStoryDoesNotReportQuotaLimit() {
        // 按配置关闭的故事帖不能当成「额度受限」，否则通知会误报明天重试。
        List<String> pending = Collections.singletonList("NewMySupport_BBSPostStoryArticle");
        assertEquals("全部完成（故事帖已关闭）",
                SiemensCheckInTask.remainingText(pending, false, null, true));
    }

    @Test
    public void skipsClosedQuestionsWhenAdopting() {
        // 站点 Q_Status：0 待解决、1 已关闭、3 已解决；只有 0 才可能采纳。
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        try {
            java.lang.reflect.Method method =
                    SiemensPipeline.class.getDeclaredMethod("isQuestionClosed", String.class);
            method.setAccessible(true);
            java.lang.reflect.Constructor<SiemensPipeline> ctor =
                    SiemensPipeline.class.getDeclaredConstructor(SiemensCheckInTask.class);
            ctor.setAccessible(true);
            SiemensPipeline pipeline = ctor.newInstance(task);
            // 本地假站点没有问答接口，读不到状态时必须返回 false（继续尝试），
            // 否则一次接口抖动就会把还能采纳的问题永久标记成不可采纳。
            assertEquals(false, method.invoke(pipeline, "999999"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void onlyAlertsStoryRejectionAfterSeveralStraightDays() {
        // 故事帖天天被拒是常态，每天报一次只会变噪音；连续 3 天才提醒关开关。
        List<String[]> allRejected = Arrays.asList(
                new String[]{"标题一", "未通过审核：非原创内容"},
                new String[]{"标题二", "未通过审核：非原创内容"});
        assertTrue(SiemensForumActions.allRejected(allRejected));

        // 有任一篇被降级成普通帖就不算「全被拒」，计数应清零。
        List<String[]> mixed = Arrays.asList(
                new String[]{"标题一", "未通过审核：非原创内容"},
                new String[]{"标题二", "被转成普通帖"});
        assertFalse(SiemensForumActions.allRejected(mixed));

        // 没有结果时不计入连续失败，避免把没提交成功的那天也算上。
        assertFalse(SiemensForumActions.allRejected(Collections.emptyList()));
        assertFalse(SiemensForumActions.allRejected(null));

        // 只在正好第 3 天提醒，之后不重复刷。
        assertEquals("", SiemensForumActions.rejectStreakAlert(1));
        assertEquals("", SiemensForumActions.rejectStreakAlert(2));
        assertTrue(SiemensForumActions.rejectStreakAlert(3).contains("SIEMENS_STORY_POST=false"));
        assertEquals("", SiemensForumActions.rejectStreakAlert(4));
    }

    @Test
    public void countsConsecutiveRejectDays() throws Exception {
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        Class<?> actions = SiemensForumActions.class;
        java.lang.reflect.Constructor<?> ctor = actions.getDeclaredConstructor(SiemensCheckInTask.class);
        ctor.setAccessible(true);
        Object forum = ctor.newInstance(task);
        java.lang.reflect.Method record = actions.getDeclaredMethod("recordReviewOutcome", boolean.class);
        record.setAccessible(true);

        java.nio.file.Path streakFile = task.storyRejectStreakPath();
        try {
            java.nio.file.Files.deleteIfExists(streakFile);
            assertEquals(1, record.invoke(forum, true));
            assertEquals(2, record.invoke(forum, true));
            assertEquals(3, record.invoke(forum, true));
            // 有一天放行就清零，之后重新从 1 开始。
            assertEquals(0, record.invoke(forum, false));
            assertEquals(1, record.invoke(forum, true));
        } finally {
            java.nio.file.Files.deleteIfExists(streakFile);
        }
    }

    @Test
    public void buildsCompactSignSummary() {
        // 通知本身就是当天结果，「今日」「当前」这类词属于冗余，全部省掉。
        assertEquals("签到成功，连续 5 天，西币 153（+9）",
                SiemensCheckInTask.signSummary("签到成功", "5", "153", 9));
        // 今天已签过、没有西币变化时不显示括号。
        assertEquals("已签到，连续 5 天，西币 153",
                SiemensCheckInTask.signSummary("今日已签到", "5", "153", 0));
        // 站点自带「今日」前缀时要去掉，避免「今日今日」或缺字。
        assertEquals("已签到，连续 9 天，西币 88",
                SiemensCheckInTask.signSummary("今日已签到", "9", "88", 0));
        // 读不到余额时只报签到。
        assertEquals("签到成功，连续 3 天",
                SiemensCheckInTask.signSummary("签到成功", "3", null, null));
        assertEquals("签到成功", SiemensCheckInTask.signSummary("签到成功", null, null, null));
    }

    @Test
    public void rotatesCandidatesByAccountIndex() {
        // 已解决列表头部几天不变，固定索引会让各账号第二天就撞上自己评过的目标。
        // 这里验证「按账号序号轮转起点、遇到已评过的继续往后」的排序逻辑本身。
        assertEquals(Arrays.asList("b", "c", "a"), SiemensAnswerActions.rotate(Arrays.asList("a", "b", "c"), 1));
        assertEquals(Arrays.asList("c", "a", "b"), SiemensAnswerActions.rotate(Arrays.asList("a", "b", "c"), 2));
        assertEquals(Arrays.asList("a", "b", "c"), SiemensAnswerActions.rotate(Arrays.asList("a", "b", "c"), 0));
        // 序号超过候选数时取模，不能越界。
        assertEquals(Arrays.asList("b", "c", "a"), SiemensAnswerActions.rotate(Arrays.asList("a", "b", "c"), 4));
        // 空列表与单元素不能炸。
        assertEquals(Collections.emptyList(), SiemensAnswerActions.rotate(Collections.emptyList(), 2));
        assertEquals(Collections.singletonList("x"), SiemensAnswerActions.rotate(Collections.singletonList("x"), 5));
    }

    @Test
    public void parsesSiteDateMillis() {
        // 站点接口的时间是 /Date(毫秒)/，要能转成北京时间的日期。
        assertEquals("2026-11-16", SiemensPipeline.dateOf("/Date(1794791942350)/"));
        assertEquals("", SiemensPipeline.dateOf(null));
        assertEquals("", SiemensPipeline.dateOf("2026-11-16"));
    }

    @Test
    public void picksTheNewestSiblingQuestionAndSkipsStaleOnes() throws Exception {
        // 待解决列表按时间倒序，老问题几小时后就沉到第二页以后。
        // 必须从最新一条往回挑，否则会一直盯着最早那条没答上的问题，闭环永远转不起来。
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("pipeline");
        java.nio.file.Path file = dir.resolve(SiemensPipeline.FILE_NAME);
        try {
            SiemensPipeline pipeline = newPipeline(task, file);
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            writeRounds(file, cn.hutool.json.JSONUtil.createArray()
                    // 六天前的老问题：超过等待窗口，提问方采纳期也快过了，不能再一直等它。
                    .put(cn.hutool.json.JSONUtil.createObj()
                            .set("qid", "100").set("cid", "1260").set("askerUid", "1579302")
                            .set("askedAt", today.minusDays(6).toString())
                            .set("title", "过期问题"))
                    .put(cn.hutool.json.JSONUtil.createObj()
                            .set("qid", "101").set("cid", "1260").set("askerUid", "1579302")
                            .set("askedAt", today.toString())
                            .set("title", "昨天的兄弟问题")));
            pipeline = newPipeline(task, file);
            String[] picked = pipeline.nextQuestionToAnswer("1820833");
            // 100 已超期被跳过，101 是新问题，两个账号都该命中它。
            assertNotEquals(null, picked);
            assertEquals("101", picked[0]);
            assertEquals("昨天的兄弟问题", picked[2]);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    @Test
    public void siblingQuestionIsClaimedSoTwoAccountsDoNotPickTheSameOne() throws Exception {
        // 三个容器启动时间只差一两分钟：不认领的话后启动的会挑中同一问题，
        // 结果提问最晚的账号反而没人答。
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("pipeline");
        java.nio.file.Path file = dir.resolve(SiemensPipeline.FILE_NAME);
        try {
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            writeRounds(file, cn.hutool.json.JSONUtil.createArray()
                    .put(cn.hutool.json.JSONUtil.createObj()
                            .set("qid", "200").set("cid", "1260").set("askerUid", "1579302")
                            .set("askedAt", today.toString()).set("title", "A 的问题"))
                    .put(cn.hutool.json.JSONUtil.createObj()
                            .set("qid", "201").set("cid", "1260").set("askerUid", "1820814")
                            .set("askedAt", today.toString()).set("title", "C 的问题")));

            SiemensPipeline forNo1 = newPipeline(task, file);
            String[] first = forNo1.nextQuestionToAnswer("1820814");
            assertEquals("200", first[0]);

            // 另一个账号再挑时必须换一条，不能又扑到 200 上。
            SiemensPipeline forFanfan = newPipeline(task, file);
            String[] second = forFanfan.nextQuestionToAnswer("1579302");
            assertEquals("201", second[0]);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
            java.nio.file.Files.deleteIfExists(dir);
        }
    }

    @Test
    public void recordsQuestionWithTheRedirectedId() {
        // 提问成功后站点 302 到 solve_{qid}_{cid}.html，直接据此记账，
        // 不必回查待排序的列表（那时自己的问题可能已经翻页了）。
        SiemensCheckInTask.HttpResult redirected = new SiemensCheckInTask.HttpResult(302, null,
                "/service/answer/solve_359432_1260.html");
        assertEquals("359432", SiemensAnswerActions.questionIdFromResult(redirected));

        // 站点有时把跳转写成整页 HTML，也要能取到。
        SiemensCheckInTask.HttpResult html = new SiemensCheckInTask.HttpResult(200,
                "<h2>Object moved to <a href=\"/service/answer/solve_359433_1260.html\">here</a>.</h2>", null);
        assertEquals("359433", SiemensAnswerActions.questionIdFromResult(html));

        // 提交被拒时没有 ID，调用方退回「我的提问」里发现。
        assertEquals(null, SiemensAnswerActions.questionIdFromResult(null));
        assertEquals(null, SiemensAnswerActions.questionIdFromResult(
                new SiemensCheckInTask.HttpResult(200, "此问题已经存在", null)));
    }

    @Test
    public void readsStoryRejectionReasonFromNotice() throws Exception {
        // 故事审核通知的正文格式：您提交的故事“标题”未通过审核,未通过原因：xxx。
        java.lang.reflect.Field field = SiemensForumActions.class.getDeclaredField("STORY_REVIEW");
        field.setAccessible(true);
        java.util.regex.Pattern pattern = (java.util.regex.Pattern) field.get(null);

        java.util.regex.Matcher rejected = pattern.matcher(
                "您好！ 您提交的故事“深夜排查组网风暴干扰”未通过审核,未通过原因：非原创内容。 建议您重新编辑后再次提交");
        assertTrue(rejected.find());
        assertEquals("深夜排查组网风暴干扰", rejected.group(1));
        assertEquals("非原创内容", rejected.group(2));
        assertEquals(null, rejected.group(3));

        // 被降级成普通帖的通知里没有「未通过原因」，要用另一段文案识别。
        java.util.regex.Matcher converted = pattern.matcher(
                "您提交的故事“第一次独立调试”被转换为普通帖发布到“谈天说地”，您可以点击此处查看");
        assertTrue(converted.find());
        assertEquals("第一次独立调试", converted.group(1));
        assertNotEquals(null, converted.group(3));

        // 正常通知不能误判。
        assertFalse(pattern.matcher("您的回答被赞啦").find());
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
        assertEquals("还有 1 项未完成：论坛发故事帖",
                SiemensCheckInTask.remainingText(pending, false, null));
        // 故事帖提交成功：站点审核期间状态还是未完成，但不该再列出来。
        assertEquals("全部完成，故事帖已提交待审核",
                SiemensCheckInTask.remainingText(pending, true, null));
    }

    @Test
    public void listsRemainingTasksAfterAbort() {
        List<String> pending = Arrays.asList("NewMySupport_FAQuestion",
                "NewMySupport_CollectionQA", "NewMySupport_BBSPostStoryArticle");

        assertEquals("连续失败 2 次，已中止，未完成 找答案提问、收藏问答、论坛发故事帖",
                SiemensCheckInTask.remainingText(pending, false, "连续失败 2 次，已中止"));
    }

    @Test
    public void storySwitchOffIsNotReportedAsUnfinished() {
        List<String> pending = Arrays.asList("NewMySupport_FAQuestion",
                "NewMySupport_BBSPostStoryArticle");

        // 关掉故事帖开关后，剩余项里不该再出现它，也不该说「未完成」。
        assertEquals("还有 1 项未完成：找答案提问",
                SiemensCheckInTask.remainingText(pending, false, null, true));
        // 只剩故事帖时直接报完成，并注明是按配置关闭。
        assertEquals("全部完成（故事帖已关闭）",
                SiemensCheckInTask.remainingText(Collections.singletonList("NewMySupport_BBSPostStoryArticle"),
                        false, null, true));
        // 开关打开时行为不变。
        assertEquals("还有 1 项未完成：论坛发故事帖",
                SiemensCheckInTask.remainingText(Collections.singletonList("NewMySupport_BBSPostStoryArticle"),
                        false, null, false));
    }

    @Test
    public void storySwitchDefaultsToEnabled() {
        // 留空或没配时默认发故事帖，只有显式 false 才跳过。
        SiemensCheckInTask defaultOn = new SiemensCheckInTask(environment(), baseUrl);
        assertTrue(storyPostEnabled(defaultOn));
        Map<String, String> off = environment();
        off.put("SIEMENS_STORY_POST", "false");
        assertFalse(storyPostEnabled(new SiemensCheckInTask(off, baseUrl)));
        Map<String, String> on = environment();
        on.put("SIEMENS_STORY_POST", "true");
        assertTrue(storyPostEnabled(new SiemensCheckInTask(on, baseUrl)));
    }

    private static boolean storyPostEnabled(SiemensCheckInTask task) {
        try {
            java.lang.reflect.Method method =
                    SiemensCheckInTask.class.getDeclaredMethod("storyPostEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(task);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void rejectsRepliesThatDriftAwayFromTheOriginalPost() {
        String post = "1516 带 ET200SP 从站，运行几小时后偶发掉站，诊断缓冲区报 F07452。";
        // 贴着原帖、引用了型号和报警码的回帖要放行。
        assertTrue(SiemensCheckInTask.mentionsSource(
                "F07452 一般先看站点的更新时间设置，ET200SP 站多时别调得太短。", post));
        // 只写型号简写也算命中。
        assertTrue(SiemensCheckInTask.mentionsSource("我们那条线的 1516 也这么调过。", post));
        // 和原帖毫无关系的通用建议必须拦下。
        assertFalse(SiemensCheckInTask.mentionsSource(
                "这个思路挺实用的，现场调试时确实能少走弯路。", post));
        assertFalse(SiemensCheckInTask.mentionsSource("感谢分享，参考价值很大。", post));

        // 纯中文、没有技术锚点时退回字符重合度判断。
        String chinese = "产线停机后上位机全是通讯报警，顺着线查过去是施工碰松的网线。";
        assertTrue(SiemensCheckInTask.mentionsSource(
                "顺着线查过去这个做法对，通讯报警先查物理层。", chinese));
        assertFalse(SiemensCheckInTask.mentionsSource(
                "建议先算一下 IO 点数的增长余量，别只按现在的需求配。", chinese));

        assertFalse(SiemensCheckInTask.mentionsSource(null, post));
        assertFalse(SiemensCheckInTask.mentionsSource("随便一句话", null));
    }

    @Test
    public void extractsTechnicalAnchorsFromPostText() {
        List<String> anchors = SiemensCheckInTask.technicalAnchors(
                "S7-1500 通过 PROFINET 读 V90 参数，报 F07452，用 TIA 查诊断缓冲区。");
        assertTrue(anchors.contains("S7-1500"));
        assertTrue(anchors.contains("PROFINET"));
        assertTrue(anchors.contains("V90"));
        assertTrue(anchors.contains("F07452"));
        // 纯数字不该被当成锚点，否则任何内容都能命中。
        assertFalse(SiemensCheckInTask.technicalAnchors("一共 15 个从站，跑了 3 小时").contains("15"));
    }

    @Test
    public void relatedContentHasNoBuiltInFallback() {
        // 回帖、评论、回答必须贴合原帖，这三类不许有通用兜底文案。
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        assertEquals(null, task.fallbackContent("reply"));
        assertEquals(null, task.fallbackContent("postcomment"));
        assertEquals(null, task.fallbackContent("bestcomment"));
        assertEquals(null, task.fallbackContent("answer"));
        // 自己发起的内容仍然保留兜底，避免 AI 不可用时整项任务失败。
        assertFalse(task.fallbackContent("posttitle").trim().isEmpty());
        assertFalse(task.fallbackContent("askbody").trim().isEmpty());
    }

    @Test
    public void onlyRepliesToPostsWithATechnicalTopic() {
        // 「最新发帖」里混着谈天说地、活动、版务，这类帖没有技术主题，
        // 对着它们写出来的回帖必然答非所问，必须在挑帖阶段就排除。
        assertFalse(SiemensForumActions.isTechnicalText("今天天气不错，大家周末都去哪儿玩"));
        assertFalse(SiemensForumActions.isTechnicalText("【活动】2026 年会报名开始啦"));
        assertFalse(SiemensForumActions.isTechnicalText("版规更新公告，请各位注意"));
        assertFalse(SiemensForumActions.isTechnicalText(""));
        assertFalse(SiemensForumActions.isTechnicalText(null));

        // 含型号、报警码、技术用语的就是技术帖。
        assertTrue(SiemensForumActions.isTechnicalText("S7-1500 与第三方 PN 设备通讯超时"));
        assertTrue(SiemensForumActions.isTechnicalText("ET200SP 偶发掉站，诊断缓冲区报 F07452"));
        assertTrue(SiemensForumActions.isTechnicalText("V90 回零偏移怎么调"));
        assertTrue(SiemensForumActions.isTechnicalText("博途里怎么批量分配设备名"));

        // 标题是生活话题、正文也没技术内容时整帖判为非技术帖。
        assertFalse(SiemensForumActions.isTechnicalTopic(
                new SiemensForumActions.Topic("1", "66", "周末去哪儿玩", "天气不错出去走走", "42", "")));
        assertTrue(SiemensForumActions.isTechnicalTopic(
                new SiemensForumActions.Topic("2", "66", "掉站排查", "1516 带 ET200 偶发掉站", "42", "")));
        assertFalse(SiemensForumActions.isTechnicalTopic(null));
    }

    @Test
    public void doesNotTreatCommercialOrLinkOnlyPostsAsTechnical() {
        // 「国内几大直流电机厂商销售情况」是商业帖，命中「电机」不该算技术帖。
        assertFalse(SiemensForumActions.isTechnicalText("国内几大直流电机厂商销售情况"));
        // 官网更新公告：正文里的链接不能被当成技术线索。
        assertFalse(SiemensForumActions.isTechnicalText(
                "今天去官网浏览，发现网站刷新了新网址 https://www.ad.siemens.com.cn/topicportal/index_90.html"));
        assertFalse(SiemensForumActions.isTechnicalText("详见 www.example.com 或 siemens.com.cn"));
        // 链接剔掉后仍保留真正的型号锚点。
        assertTrue(SiemensForumActions.isTechnicalText(
                "参考 https://www.ad.siemens.com.cn/a.html 里的 S7-1500 组态说明"));
    }

    @Test
    public void activityPostsAreRejectedEvenWhenTheyMentionProducts() {
        // 活动帖正文常提到产品名，会被关键词命中，但主题不是技术问题，必须否决。
        SiemensForumActions.Topic activity = new SiemensForumActions.Topic("1", "25",
                "一路上有你｜问答20条得限量纪念杯 · 找答案20周年专场",
                "参与就有机会获得 S7-200 SMART 纪念品，报名开始", "42", "");
        assertTrue("正文命中技术词", SiemensForumActions.isTechnicalText(activity.body));
        assertFalse("活动标题应否决", SiemensForumActions.isTechnicalTopic(activity));

        assertTrue(SiemensForumActions.isNonTechnicalPost(new SiemensForumActions.Topic(
                "2", "66", "【活动】2026 年会报名开始啦", "技术交流", "42", "")));
        assertTrue(SiemensForumActions.isNonTechnicalPost(new SiemensForumActions.Topic(
                "3", "66", "二手模块转让", "S7-1500", "42", "")));
        // 正常技术帖不能被否决项误杀：正文提到「领取」也不行，因为只看标题。
        assertFalse(SiemensForumActions.isNonTechnicalPost(new SiemensForumActions.Topic(
                "4", "66", "S7-1500 掉站排查",
                "报警后需要到现场领取诊断记录，再分析缓冲区", "42", "")));
    }

    @Test
    public void keepsProgrammingDiscussionsAsTechnical() {
        // 「L 区临时变量在各系列都不保持」是纯技术帖，正文里没有型号也没有常见关键词，
        // 收紧词表时曾被误判成非技术帖，这里固化住。
        SiemensForumActions.Topic programming = new SiemensForumActions.Topic("1", "50",
                "L 区临时变量在各系列都不保持，哪些场景误用 L 区会出问题？",
                "公司新招的同事把状态位放在 L 区，块一执行完就丢了，中断 OB 和 FB 嵌套调用尤其危险",
                "42", "");
        assertTrue("编程术语应判为技术帖", SiemensForumActions.isTechnicalTopic(programming));
        assertTrue(SiemensForumActions.isTechnicalText("中断 OB 里用临时变量要注意什么"));
        assertTrue(SiemensForumActions.isTechnicalText("背景 DB 和多重背景的区别"));
        assertTrue(SiemensForumActions.isTechnicalText("用 SCL 写循环比 STL 慢吗"));
    }

    @Test
    public void urlsDoNotCountAsTechnicalAnchors() {
        // 链接里的 https/www/siemens 若被当成锚点，任何带链接的公告帖都会判成技术帖。
        assertTrue(SiemensCheckInTask.technicalAnchors(
                "见 https://www.ad.siemens.com.cn/abc.html 页面").isEmpty());
        // 真正的型号仍然能提取出来。
        assertFalse(SiemensCheckInTask.technicalAnchors(
                "见 https://www.ad.siemens.com.cn/abc.html 的 S7-1500 说明").isEmpty());
    }

    @Test
    public void noTargetSkipDoesNotTripTheFailureBreaker() throws Exception {
        // 挑不到技术帖属于「没有合适目标」，绝不能计入连续失败，
        // 否则一项跳过就会把后面本来能做的任务全部熔断。
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        java.lang.reflect.Field failedField = SiemensCheckInTask.class.getDeclaredField("lastTaskFailed");
        failedField.setAccessible(true);
        java.lang.reflect.Field noTargetField = SiemensCheckInTask.class.getDeclaredField("lastTaskNoTarget");
        noTargetField.setAccessible(true);
        java.lang.reflect.Field skippedField = SiemensCheckInTask.class.getDeclaredField("lastTaskSkipped");
        skippedField.setAccessible(true);

        task.markTaskNoTarget();
        assertEquals(false, failedField.get(task));
        assertEquals(true, noTargetField.get(task));
        assertEquals(false, skippedField.get(task));

        // 额度受限是另一套语义，不能被混成「无目标」。
        SiemensCheckInTask other = new SiemensCheckInTask(environment(), baseUrl);
        other.markTaskSkipped();
        assertEquals(false, noTargetField.get(other));
        assertEquals(true, skippedField.get(other));
    }

    @Test
    public void reportsNoTargetSeparatelyFromQuotaLimit() throws Exception {
        // 通知里两种跳过要说清，不能都写成「受每日主贴上限限制」。
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        java.lang.reflect.Field noTargetField = SiemensCheckInTask.class.getDeclaredField("noTargetTasks");
        noTargetField.setAccessible(true);
        java.util.Set<String> noTarget = (java.util.Set<String>) noTargetField.get(task);
        noTarget.add(SiemensCheckInTask.TASK_REPLY_POST);

        java.lang.reflect.Method notes = SiemensCheckInTask.class.getDeclaredMethod("skipNotes");
        notes.setAccessible(true);
        String text = (String) notes.invoke(task);
        assertTrue("应说明没有合适目标，实际：" + text, text.contains("没有合适的目标"));
        assertFalse("不能误报额度受限，实际：" + text, text.contains("主贴上限"));
        assertTrue("应点名是哪一项，实际：" + text, text.contains("论坛回帖"));
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

        assertEquals("签到成功，连续 4 天，西币 88", plainText(task));
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
            assertTrue("应报未完成，实际：" + text, text.contains("还有 1 项未完成：论坛发故事帖"));
            assertFalse("不能报已全部完成，实际：" + text, text.contains("全部完成"));
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
            assertFalse("不能报已全部完成，实际：" + text, text.contains("全部完成"));
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

    /** 清掉西币奖励通知的处理记录，避免测试之间互相影响。 */
    private static void clearCoinNotices() {
        java.nio.file.Path file = SiemensCheckInTask.stateDir().resolve("siemens-coin-notices-"
                + SiemensCheckInTask.sha1Hex("user@example.com").substring(0, 12) + ".txt");
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (Exception e) {
            // 清不掉不影响断言：每条用例用的 ID 都不同。
        }
    }

    /** 清掉 7 天挑战赛的当天答题标记，避免测试之间互相影响。 */
    private static void clearChallengeMarker() {
        java.nio.file.Path file = SiemensCheckInTask.stateDir().resolve("siemens-challenge-"
                + SiemensCheckInTask.sha1Hex("user@example.com").substring(0, 12) + ".txt");
        try {
            java.nio.file.Files.deleteIfExists(file);
        } catch (Exception e) {
            // 清不掉不影响断言。
        }
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

    @Test
    public void claimsCoinRewardsFromMessageCenter() {
        coinNotices.add("1001");
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        task.run();

        // 消息里带领取链接的必须真的提交一次，金额写进通知。
        assertEquals(1, pickupPosts.get());
        assertTrue(plainText(task).contains("消息奖励：领取 1 笔，西币 10"));
        clearCoinNotices();
        clearChallengeMarker();
    }

    @Test
    public void skipsCoinNoticeWithoutPickupLink() {
        coinNotices.add("1002");
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        task.run();

        // 贺信类通知没有领取链接，不该发起领取请求，但要记下来免得天天重复抓。
        assertEquals(0, pickupPosts.get());
        assertFalse(plainText(task).contains("消息奖励"));
        assertTrue(task.handledCoinNotices().contains("1002"));
        clearCoinNotices();
    }

    @Test
    public void reportsCoinRewardsAlreadyClaimed() {
        coinNotices.add("1003");
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        task.run();

        // 站点回「已领取过」不是失败，也不该每天再问一次。
        assertEquals(1, pickupPosts.get());
        assertFalse(plainText(task).contains("未成功"));
        assertTrue(task.handledCoinNotices().contains("1003"));
        clearCoinNotices();
    }

    @Test
    public void doesNotRememberFailedClaim() {
        coinNotices.add("1004");
        pickupBroken = true;
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl);
        task.run();

        // 领取失败不能记账，奖励 60 天内都能补领，下次必须重试。
        assertFalse(task.handledCoinNotices().contains("1004"));
        assertTrue(plainText(task).contains("1 笔领取未成功"));
        clearCoinNotices();
    }

    @Test
    public void parsesOnlyAwardNotices() {
        String html = "<dl class=\"\"><dt><a href=\"MyMsgDetail.aspx?noticeId=1\">"
                + "<span class=\"tag\">获奖通知</span><div class=\"center\" title=\"奖励\"></div></a></dt></dl>"
                + "<dl class=\"\"><dt><a href=\"MyMsgDetail.aspx?noticeId=2\">"
                + "<span class=\"tag\">管理员通知</span><div class=\"center\" title=\"故事审核通知\"></div></a></dt>"
                + "</dl>";
        java.util.List<?> notices = SiemensCoinRewards.parseNotices(html);

        // 故事审核、关注提醒这类通知没有西币，不能白白多抓详情页。
        assertEquals(1, notices.size());
        // 分页总数取列表页脚本里的 initPage(1, N)，取不到按单页处理。
        assertEquals(2, SiemensCoinRewards.pageCount("<script>initPage(1, 2);</script>"));
        assertEquals(1, SiemensCoinRewards.pageCount("没有分页脚本"));
    }

    @Test
    public void answersChallengeQuestionWithSiteAnswer() throws Exception {
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl, true);
        try {
            task.run();

            // 题库接口直接带正确答案，程序按答案提交即可，不需要猜。
            assertEquals(1, challengeSubmits.get());
            assertTrue(plainText(task).contains("7 天挑战：答题正确"));
        } finally {
            clearRunMarker();
            clearChallengeMarker();
        }
    }

    @Test
    public void skipsChallengeWhenAlreadyAnsweredToday() throws Exception {
        Map<String, String> environment = environment();
        SiemensCheckInTask first = new SiemensCheckInTask(environment, baseUrl, true);
        try {
            first.run();
            assertTrue(challengeSubmits.get() >= 1);

            // 站点本身不拦当天重复答题，必须靠本地记录去重，否则多容器会反复刷同一题。
            int before = challengeSubmits.get();
            SiemensCheckInTask second = new SiemensCheckInTask(environment, baseUrl, true);
            second.run();
            assertEquals(before, challengeSubmits.get());
        } finally {
            clearRunMarker();
            clearChallengeMarker();
        }
    }

    @Test
    public void claimsOneDayPrizeOnlyWhenSiteAllows() throws Exception {
        challengeOneDayPrize = false;
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl, true);
        try {
            task.run();
            assertEquals(0, challengeClaims.get());

            clearRunMarker();
            clearChallengeMarker();
            challengeOneDayPrize = true;
            SiemensCheckInTask again = new SiemensCheckInTask(environment(), baseUrl, true);
            again.run();
            assertEquals(1, challengeClaims.get());
            assertTrue(plainText(again).contains("已领答题奖励"));
        } finally {
            clearRunMarker();
            clearChallengeMarker();
        }
    }

    @Test
    public void claimsPendingPrizeShownOnPageEvenWhenAnswerIsDone() throws Exception {
        // 真实场景（fanfan 账号）：前一天答完但没点领奖，第二天答题页上「点击领奖」仍可见，
        // 而本次提交返回 IsCanOneDayPrize=false。只认提交返回就会把这笔奖永久漏掉。
        // 而且站点在答题后会把按钮收回，所以必须先领再答。
        challengePageOneDay = true;
        challengeOneDayPrize = false;
        Map<String, String> environment = environment();
        SiemensCheckInTask task = new SiemensCheckInTask(environment, baseUrl, true);
        try {
            task.run();
            assertEquals(1, challengeClaims.get());
            assertTrue(plainText(task).contains("已领答题奖励"));
        } finally {
            clearRunMarker();
            clearChallengeMarker();
        }
    }

    @Test
    public void claimsPagePrizeBeforeSubmittingAnswer() throws Exception {
        // 站点答题后会把页面上那笔待领奖品收回，所以领奖请求必须排在答题请求之前，
        // 否则这笔奖会一直挂着领不到。
        challengePageOneDay = true;
        challengeOneDayPrize = false;
        java.util.List<String> calls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        challengeCallLog = calls;
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl, true);
        try {
            task.run();
            int claim = calls.indexOf("claim");
            int submit = calls.indexOf("submit");
            assertTrue("未领奖：" + calls, claim >= 0);
            assertTrue("未答题：" + calls, submit >= 0);
            assertTrue("领奖必须早于答题，实际顺序：" + calls, claim < submit);
        } finally {
            clearRunMarker();
            clearChallengeMarker();
            challengeCallLog = null;
        }
    }

    @Test
    public void claimsPendingPrizeWhenAlreadyAnsweredToday() throws Exception {
        // 当天已答过（本地有标记）也应补领页面上遗留的奖品，而不是直接跳过整段流程。
        Map<String, String> environment = environment();
        SiemensCheckInTask first = new SiemensCheckInTask(environment, baseUrl, true);
        try {
            first.run();
            int submits = challengeSubmits.get();
            challengePageOneDay = true;
            challengePageSevenDay = false;
            SiemensCheckInTask second = new SiemensCheckInTask(environment, baseUrl, true);
            second.run();
            // 不重复提交答案，但要把页面上那笔奖领掉。
            assertEquals(submits, challengeSubmits.get());
            assertTrue("实际：" + plainText(second), challengeClaims.get() >= 1);
        } finally {
            clearRunMarker();
            clearChallengeMarker();
        }
    }

    @Test
    public void readsPrizeButtonVisibilityFromPage() {
        String shown = "<button class=\"prime\" style='display: inline-block' id=\"btnPrizeOneDay\">点击领奖</button>";
        String hidden = "<button class=\"prime\" style=\"display: none;\" id=\"btnPrizeOneDay\">点击领奖</button>";

        // 按钮可见才代表有奖可领；隐藏时不能平白多打一次领奖接口。
        assertTrue(SiemensChallenge.isPrizeButtonShown(shown, "btnPrizeOneDay"));
        assertFalse(SiemensChallenge.isPrizeButtonShown(hidden, "btnPrizeOneDay"));
        assertFalse(SiemensChallenge.isPrizeButtonShown("", "btnPrizeOneDay"));
        assertFalse(SiemensChallenge.isPrizeButtonShown(shown, "btnPrizeSevenDay"));
    }

    @Test
    public void reportsWrongAnswerInsteadOfSuccess() throws Exception {
        // 正常流程答案取自站点不会错，这里让假站点回 IsCorrect=false，
        // 验证通知照实写「答题错误」而不是无脑报成功。
        challengeCorrect = false;
        SiemensCheckInTask task = new SiemensCheckInTask(environment(), baseUrl, true);
        try {
            task.run();

            // 答案取自站点，正常不会错；真错了也要照实写，不能报「答题正确」。
            String text = plainText(task);
            assertTrue("实际：" + text, text.contains("7 天挑战：答题错误"));
            assertFalse("实际：" + text, text.contains("7 天挑战：答题正确"));
        } finally {
            clearRunMarker();
            clearChallengeMarker();
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

    /**
     * 模拟消息中心列表：只有获奖通知，且都带「领取获奖西币」链接。
     * 1004 的领取接口故意返回非成功页，用来验证失败不记账。
     */
    private void handleMessageList(HttpExchange exchange) throws IOException {
        StringBuilder html = new StringBuilder("<html><body>");
        for (String id : coinNotices) {
            html.append("<dl class=\"\"><dt class=\"clearfix\"><a href=\"MyMsgDetail.aspx?noticeId=")
                    .append(id).append("\"><img src=\"NewResources/img/msg/msg-no.png\" />")
                    .append("<span class=\"tag\">获奖通知</span><div class=\"left\"></div>")
                    .append("<div class=\"center\" title=\"奖励通知\"></div></a></dt></dl>");
        }
        html.append("<script>initPage(1, 1);</script></body></html>");
        write(exchange, 200, html.toString(), "text/html; charset=utf-8");
    }

    private void handleMessageDetail(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String id = query == null ? "" : query.replaceAll(".*noticeId=(\\d+).*", "$1");
        // 1002 是贺信：有获奖通知但内容里不带领取链接。
        String body = "1002".equals(id)
                ? "<div class=\"msg-detail-btm\">恭喜您获得「找答案快侠」称号！</div>"
                : "<div class=\"msg-detail-btm\">恭喜您获得奖励西币10枚！请您<a href=\""
                + baseUrl + "/club/bbs/PickupCoin.aspx?ulevel=0&id=9" + id
                + "\">领取获奖西币10枚</a>。</div>";
        write(exchange, 200, body, "text/html; charset=utf-8");
    }

    private void handlePickupCoin(HttpExchange exchange) throws IOException {
        pickupPosts.incrementAndGet();
        if (pickupBroken) {
            write(exchange, 500, "error", "text/plain; charset=utf-8");
            return;
        }
        String id = exchange.getRequestURI().getQuery();
        // 1003 表示这笔之前已经领过，站点对同一笔奖励是幂等的。
        boolean claimed = !(id != null && id.contains("91003"));
        write(exchange, 200, claimed
                ? "<div class=\"bs_jm\">恭喜您，成功领取，奖励西币 <span>10</span> 枚！</div>"
                : "<div class=\"title-content\"><b>对不起，您已领取过该奖励！</b></div>",
                "text/html; charset=utf-8");
    }

    /**
     * 模拟 7 天挑战赛答题页：真实页面渲染时把当前登录用户 ID 写进 hiduid，
     * 程序靠它判断有没有登录，所以假站点也要给一个。
     */
    private void handleChallengePage(HttpExchange exchange) throws IOException {
        String uid = authed(exchange) ? "42" : "";
        // 按钮可见性模拟「有一笔奖品待领」，用来验证按页面状态补领的分支。
        String oneDay = challengePageOneDay ? "display: inline-block" : "display: none";
        String sevenDay = challengePageSevenDay ? "display: inline-block" : "display: none";
        write(exchange, 200, "<html><body>"
                + "<input type=\"hidden\" id=\"hiduid\" value=\"" + uid + "\" />"
                + "<button class=\"prime\" style='" + oneDay + "' id=\"btnPrizeOneDay\">点击领奖</button>"
                + "<button class=\"prime\" style=\"" + sevenDay + "\" id=\"btnPrizeSevenDay\">点击抽奖</button>"
                + "</body></html>", "text/html; charset=utf-8");
    }

    /**
     * 模拟 7 天挑战赛的题库与答题接口：getquestion 直接返回题目和正确答案，
     * adduseranswerrecord 按「提交的是不是正确答案」回 IsCorrect。
     */
    private void handleChallenge(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body.contains("type=getquestion")) {
            write(exchange, 200, "{\"QuestionAndAnswerBankList\":[{\"Id\":" + challengeQuestionId
                    + ",\"QuestionTitle\":\"S7-1200 支持哪些通信协议？\","
                    + "\"AnswerA\":\"S7\",\"AnswerB\":\"PROFINET\",\"AnswerC\":\"Modbus\",\"AnswerD\":\"CANopen\","
                    + "\"TrueAnswer\":\"" + challengeAnswer + "\",\"IsMultiAnswer\":true,"
                    + "\"TipMsg\":\"《S7-1200 通信》讲解了该问题。\"}],"
                    + "\"Error\":{\"ErrorCode\":0,\"ErrorDetail\":\"\"}}", "application/json; charset=utf-8");
            return;
        }
        if (body.contains("type=adduseranswerrecord")) {
            challengeSubmits.incrementAndGet();
            if (challengeCallLog != null) {
                challengeCallLog.add("submit");
            }
            boolean correct = challengeCorrect && body.contains("answerOptions="
                    + SiemensCheckInTask.encode(challengeAnswer));            write(exchange, 200, "{\"IsCorrect\":" + correct + ",\"TrueAnswer\":\"" + challengeAnswer + "\","
                    + "\"TipMsg\":\"讲解\",\"AnswerOptions\":\"A,B\","
                    + "\"IsCanSevenDayPrize\":false,\"IsCanOneDayPrize\":" + challengeOneDayPrize + ","
                    + "\"Error\":{\"ErrorCode\":0,\"ErrorDetail\":\"\"}}", "application/json; charset=utf-8");
            return;
        }
        write(exchange, 200, "{\"Error\":{\"ErrorCode\":0,\"ErrorDetail\":\"\"}}", "application/json");
    }

    private void handleChallengePrize(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body.contains("InsertSevenDaysOneDayAward")) {
            challengeClaims.incrementAndGet();
            if (challengeCallLog != null) {
                challengeCallLog.add("claim");
            }
        }
        write(exchange, 200, "{\"ErrorCode\":0,\"ErrorDetail\":null,\"AwardType\":0,\"AwardMsg\":null}",
                "application/json; charset=utf-8");
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

    /** 用临时文件建一个轮次对象，替代默认的 /app/shared 目录。 */
    private static SiemensPipeline newPipeline(SiemensCheckInTask task, java.nio.file.Path file)
            throws Exception {
        java.lang.reflect.Constructor<SiemensPipeline> ctor =
                SiemensPipeline.class.getDeclaredConstructor(SiemensCheckInTask.class);
        ctor.setAccessible(true);
        SiemensPipeline pipeline = ctor.newInstance(task);
        java.lang.reflect.Field field = SiemensPipeline.class.getDeclaredField("file");
        field.setAccessible(true);
        field.set(pipeline, file);
        return pipeline;
    }

    /** 预置轮次文件，模拟兄弟容器已经写进来的提问。 */
    private static void writeRounds(java.nio.file.Path file, JSONArray rounds) throws IOException {
        JSONObject store = JSONUtil.createObj();
        store.set("accounts", new JSONArray().put("1820833").put("1579302").put("1820814"));
        store.set("rounds", rounds);
        store.set("siblingComments", JSONUtil.createObj());
        java.nio.file.Files.write(file, store.toString().getBytes(StandardCharsets.UTF_8));
    }
}
