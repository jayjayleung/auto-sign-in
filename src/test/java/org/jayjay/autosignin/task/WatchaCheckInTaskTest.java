package org.jayjay.autosignin.task;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WatchaCheckInTaskTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger checkInRequests = new AtomicInteger();
    private final AtomicInteger balanceRequests = new AtomicInteger();
    private final AtomicInteger claimedGuazi = new AtomicInteger();
    private volatile boolean balanceFailsAfterFirst;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> loginBody = new AtomicReference<>();
    private volatile boolean checkedIn;
    private volatile int loginStatus = 200;
    private volatile String loginMessage = "";
    private volatile boolean questServerEnabled;
    private volatile boolean product7Reviewed;
    private volatile boolean dailyClaimed;
    private volatile boolean dailyReceivable;
    private volatile boolean dailyReceived;
    private volatile boolean reviewItemClaimed;
    private volatile boolean postingClaimed;
    private volatile boolean thinProducts;
    private volatile int weeklyPostCount;
    private volatile String aiMode = "ok";
    private volatile boolean postedToday;
    private volatile boolean postedYesterday;
    private volatile boolean userPostsFail;
    private int[] productIds = new int[]{7, 8};
    private final AtomicInteger pauses = new AtomicInteger();
    private final AtomicInteger aiRequests = new AtomicInteger();
    private final AtomicInteger questFetches = new AtomicInteger();
    private final AtomicInteger reviewRequests = new AtomicInteger();
    private final AtomicInteger discussRequests = new AtomicInteger();
    private final AtomicInteger receiveRequests = new AtomicInteger();
    private final AtomicReference<String> aiAuthorization = new AtomicReference<>();
    private final AtomicReference<String> reviewBody = new AtomicReference<>();
    private final AtomicReference<String> discussBody = new AtomicReference<>();
    private final List<String> aiBodies = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> claimBodies = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> claimPaths = Collections.synchronizedList(new ArrayList<String>());

    @Before
    public void setUp() throws IOException {
        checkInRequests.set(0);
        balanceRequests.set(0);
        claimedGuazi.set(0);
        balanceFailsAfterFirst = false;
        authorization.set(null);
        loginBody.set(null);
        checkedIn = false;
        loginStatus = 200;
        loginMessage = "";
        questServerEnabled = false;
        product7Reviewed = false;
        dailyClaimed = false;
        dailyReceivable = false;
        dailyReceived = false;
        reviewItemClaimed = false;
        postingClaimed = false;
        thinProducts = false;
        weeklyPostCount = 0;
        aiMode = "ok";
        postedToday = false;
        postedYesterday = false;
        userPostsFail = false;
        productIds = new int[]{7, 8};
        pauses.set(0);
        aiRequests.set(0);
        questFetches.set(0);
        reviewRequests.set(0);
        discussRequests.set(0);
        receiveRequests.set(0);
        aiAuthorization.set(null);
        reviewBody.set(null);
        discussBody.set(null);
        aiBodies.clear();
        claimBodies.clear();
        claimPaths.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/auth/signin", this::handleLogin);
        server.createContext("/api/v2/users/me", this::handleUser);
        server.createContext("/api/v2/checkins/today", this::handleToday);
        server.createContext("/api/v2/checkins", this::handleCheckIn);
        server.createContext("/api/v2/promotions/balances", this::handleBalances);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void skipsWhenAccountIsNotConfigured() {
        WatchaCheckInTask task = task(new HashMap<String, String>());

        task.run();

        assertFalse(task.isEnabled());
        assertTrue(task.getMessageBlocks().isEmpty());
        assertEquals(0, checkInRequests.get());
    }

    @Test
    public void reportsIncompleteAccountConfiguration() {
        Map<String, String> environment = new HashMap<>();
        environment.put("WATCHA_USERNAME", "user@example.com");

        WatchaCheckInTask task = task(environment);
        task.run();

        assertTrue(task.isEnabled());
        assertEquals("签到结果：已跳过\n原因：用户名或密码配置不完整", task.getMessageBlocks().get(0).toString());
        assertEquals(0, checkInRequests.get());
    }

    @Test
    public void reportsExistingCheckInWithoutPostingAgain() {
        checkedIn = true;
        Map<String, String> environment = account();

        WatchaCheckInTask task = task(environment);
        task.run();

        assertEquals(0, checkInRequests.get());
        assertEquals("Bearer login-token", authorization.get());
        assertEquals(1, task.getMessageBlocks().size());
        assertEquals("用户名：观猹用户\n瓜子：120\n连续签到：4 天\n签到结果：今日已签到",
                task.getMessageBlocks().get(0).toString());
        assertFalse(task.getMessageBlocks().toString().contains("secret-password"));
        assertFalse(task.getMessageBlocks().toString().contains("login-token"));
    }

    @Test
    public void checksInAndReportsReward() {
        Map<String, String> environment = account();

        WatchaCheckInTask task = task(environment);
        task.run();

        JSONObject login = JSONUtil.parseObj(loginBody.get());
        assertEquals("user@example.com", login.getStr("account"));
        assertEquals("secret-password", login.getStr("password"));
        assertEquals(1, checkInRequests.get());
        assertTrue(checkedIn);
        assertEquals(1, task.getMessageBlocks().size());
        assertEquals("用户名：观猹用户\n瓜子：128\n连续签到：5 天\n签到结果：签到成功，获得 8 瓜子",
                task.getMessageBlocks().get(0).toString());
        assertFalse(task.buildNotificationSection().toString().contains("secret-password"));
    }

    @Test
    public void keepsBalanceBeforeCheckInWhenFinalBalanceIsUnavailable() {
        balanceFailsAfterFirst = true;

        WatchaCheckInTask task = task(account());
        task.run();

        assertEquals(1, task.getMessageBlocks().size());
        assertEquals("用户名：观猹用户\n瓜子：120（签到前，任务后读取失败）\n连续签到：5 天\n签到结果：签到成功，获得 8 瓜子",
                task.getMessageBlocks().get(0).toString());
    }

    @Test
    public void reportsTotalWhenOnlyQuestRewardsAreEarned() {
        checkedIn = true;
        product7Reviewed = true;
        postedToday = true;

        WatchaCheckInTask task = questTask(accountWithAi());
        task.run();

        assertEquals("用户名：观猹用户\n瓜子：180\n连续签到：4 天\n签到结果：今日已签到",
                task.getMessageBlocks().get(0).toString());
        String quest = task.getMessageBlocks().get(1).toString();
        assertTrue(quest.contains("领取奖励：每日产品猹评，获得 30 瓜子\n"));
        assertTrue(quest.contains("领取奖励：每周猹评 1/3 篇，获得 30 瓜子\n"));
        assertTrue(quest.endsWith("\n本次获得瓜子：60"));
    }

    @Test
    public void labelsClaimedItemsByQuestType() {
        JSONObject weekly = JSONUtil.createObj()
                .set("title", "发布 2 篇猹评")
                .set("progress", JSONUtil.createObj().set("current", 2).set("target", 2));
        JSONObject milestone = JSONUtil.createObj()
                .set("title", "笔耕不辍 I")
                .set("description", "发布 20 篇猹评")
                .set("progress", JSONUtil.createObj().set("current", 20).set("target", 20));
        JSONObject newbie = JSONUtil.createObj().set("title", "完善个人资料");
        JSONObject noTarget = JSONUtil.createObj().set("title", "发布 1 篇帖子");

        assertEquals("每周猹评 2/3 篇", WatchaCheckInTask.claimLabel("weekly.review", "每周猹评", weekly, 3));
        assertEquals("笔耕不辍 I（发布 20 篇猹评）",
                WatchaCheckInTask.claimLabel("milestone.review", "笔耕不辍", milestone, 3));
        assertEquals("新手任务 · 完善个人资料",
                WatchaCheckInTask.claimLabel("growth.newbie.v2", "新手任务", newbie, 4));
        assertEquals("每周发帖 · 发布 1 篇帖子",
                WatchaCheckInTask.claimLabel("weekly.posting", "每周发帖", noTarget, 3));
    }

    @Test
    public void sumsOnlyGuaziRewards() {
        JSONObject reward = JSONUtil.parseObj("{\"items\":[{\"promotion_name\":\"瓜子\",\"count\":30},"
                + "{\"promotion_name\":\"兑换码\",\"count\":1},{\"promotion_name\":\"瓜子\",\"count\":5}]}");

        assertEquals(35, WatchaCheckInTask.guaziReward(reward));
        assertEquals(0, WatchaCheckInTask.guaziReward(JSONUtil.parseObj("{\"items\":[]}")));
        assertEquals(0, WatchaCheckInTask.guaziReward(null));
    }

    @Test
    public void omitsTotalWhenItOnlyRepeatsCheckInReward() {
        assertNull(WatchaCheckInTask.guaziIncrease(120, 128, 8));
        assertNull(WatchaCheckInTask.guaziIncrease(120, 120, 0));
        assertNull(WatchaCheckInTask.guaziIncrease(null, 128, 0));
        assertNull(WatchaCheckInTask.guaziIncrease(120, null, 0));
        assertEquals(Integer.valueOf(83), WatchaCheckInTask.guaziIncrease(120, 203, 8));
        assertEquals(Integer.valueOf(60), WatchaCheckInTask.guaziIncrease(120, 180, 0));
    }

    @Test
    public void reportsLoginFailureWithoutPassword() {
        loginStatus = 400;
        loginMessage = "账号或密码错误";

        WatchaCheckInTask task = task(account());
        task.run();

        assertEquals("签到结果：签到失败\n原因：登录失败：账号或密码错误", task.getMessageBlocks().get(0).toString());
        assertFalse(task.getMessageBlocks().toString().contains("secret-password"));
        assertEquals(0, checkInRequests.get());
    }

    @Test
    public void reportsCaptchaRequirement() {
        loginStatus = 449;
        loginMessage = "需要验证码";

        WatchaCheckInTask task = task(account());
        task.run();

        assertEquals("签到结果：签到失败\n原因：登录失败：需要完成验证码", task.getMessageBlocks().get(0).toString());
    }

    @Test
    public void publishesReviewAndPostThenClaimsRewards() {
        WatchaCheckInTask task = questTask(accountWithAi());

        task.run();

        assertEquals(2, pauses.get());
        assertEquals(2, aiRequests.get());
        assertEquals(1, reviewRequests.get());
        assertEquals(1, discussRequests.get());
        assertEquals("Bearer ai-test-key", aiAuthorization.get());
        assertEquals("Bearer login-token", authorization.get());
        assertTrue(aiBodies.get(0).contains("\"model\":\"test-model\""));
        assertTrue(aiBodies.get(0).contains("体裁：猹评"));
        assertTrue(aiBodies.get(1).contains("体裁：帖子"));
        assertTrue(aiBodies.get(1).contains("只抛一个它没讲清的问题"));
        assertTrue(aiBodies.get(0).contains("不要写成对材料的转述"));
        assertFalse(aiBodies.toString().contains("secret-password"));
        assertFalse(aiBodies.toString().contains("login-token"));
        JSONObject review = JSONUtil.parseObj(reviewBody.get());
        assertEquals(Integer.valueOf(1), review.getInt("vote_value"));
        assertEquals(Boolean.FALSE, review.getBool("append"));
        assertEquals("doc", review.getJSONObject("content").getStr("type"));
        JSONObject post = JSONUtil.parseObj(discussBody.get());
        assertEquals(Integer.valueOf(8), post.getInt("product_id"));
        assertEquals(POST_TITLE, post.getStr("title"));
        assertEquals(3, claimBodies.size());
        assertTrue(claimPaths.get(0).contains("/quests/19/claim-reward"));
        assertTrue(claimPaths.get(1).contains("review_1"));
        assertTrue(claimPaths.get(2).contains("posting_1"));
        assertTrue(claimBodies.get(0).contains("100"));
        assertTrue(claimBodies.get(1).contains("101"));
        assertTrue(claimBodies.get(2).contains("102"));
        assertEquals("用户名：观猹用户\n瓜子：203\n连续签到：5 天\n签到结果：签到成功，获得 8 瓜子",
                task.getMessageBlocks().get(0).toString());
        assertEquals("今日猹评：已发布 切问\n"
                + "本周猹评：1/3\n"
                + "本周帖子：已发布 " + POST_TITLE + "\n"
                + "领取奖励：每日产品猹评，获得 30 瓜子\n"
                + "领取奖励：每周猹评 1/3 篇，获得 30 瓜子\n"
                + "领取奖励：每周发帖 1/3 篇，获得 15 瓜子\n"
                + "本次获得瓜子：83", task.getMessageBlocks().get(1).toString());
    }

    @Test
    public void skipsPostWhenTodayAlreadyPosted() {
        postedToday = true;

        WatchaCheckInTask task = questTask(accountWithAi());
        task.run();

        assertEquals(0, discussRequests.get());
        String quest = task.getMessageBlocks().get(1).toString();
        assertTrue(quest.contains("本周帖子：今日已发过"));
    }

    @Test
    public void postsAgainWhenLatestPostWasYesterday() {
        postedYesterday = true;

        WatchaCheckInTask task = questTask(accountWithAi());
        task.run();

        assertEquals(1, discussRequests.get());
    }

    @Test
    public void skipsPostWhenPostListIsUnavailable() {
        userPostsFail = true;

        WatchaCheckInTask task = questTask(accountWithAi());
        task.run();

        assertEquals(0, discussRequests.get());
        assertTrue(task.getMessageBlocks().get(1).toString().contains("本周帖子：今日已发过"));
    }

    @Test
    public void comparesPostDateInShanghaiTime() {
        LocalDate day = LocalDate.of(2026, 11, 4);

        assertTrue(WatchaCheckInTask.sameDayShanghai("2026-11-03T16:49:30.813Z", day));
        assertFalse(WatchaCheckInTask.sameDayShanghai("2026-11-03T15:12:36.997Z", day));
        assertFalse(WatchaCheckInTask.sameDayShanghai(null, day));
        assertTrue(WatchaCheckInTask.sameDayShanghai("2026-11-04 08:00:00", day));
    }

    @Test
    public void publishesBadVoteWhenCopywriterSaysSo() {
        aiMode = "bad";

        WatchaCheckInTask task = questTask(accountWithAi());
        task.run();

        assertEquals(1, reviewRequests.get());
        JSONObject review = JSONUtil.parseObj(reviewBody.get());
        assertEquals(Integer.valueOf(2), review.getInt("vote_value"));
    }

    @Test
    public void receivesQuestBeforePublishing() {
        dailyReceivable = true;

        WatchaCheckInTask task = questTask(accountWithAi());
        task.run();

        assertEquals(1, receiveRequests.get());
        assertEquals(1, reviewRequests.get());
    }

    @Test
    public void skipsWritingWhenTodayAndThisWeekAreDone() {
        product7Reviewed = true;
        dailyClaimed = true;
        reviewItemClaimed = true;
        weeklyPostCount = 3;
        postingClaimed = true;

        WatchaCheckInTask task = questTask(accountWithAi());
        task.run();

        assertEquals(0, pauses.get());
        assertEquals(0, aiRequests.get());
        assertEquals(0, reviewRequests.get());
        assertEquals(0, discussRequests.get());
        assertEquals("今日猹评：今日已完成\n本周猹评：1/3\n本周帖子：3/3",
                task.getMessageBlocks().get(1).toString());
    }

    @Test
    public void skipsThinProductWithoutCallingAi() {
        thinProducts = true;

        WatchaCheckInTask task = questTask(accountWithAi());
        task.run();

        assertEquals(0, aiRequests.get());
        assertEquals(0, reviewRequests.get());
        assertEquals("今日猹评：已跳过\n原因：推荐产品介绍太短\n本周帖子：已跳过\n原因：推荐产品介绍太短",
                task.getMessageBlocks().get(1).toString());
    }

    @Test
    public void doesNotPublishWhenCopyIsRejected() {
        aiMode = "banned";

        WatchaCheckInTask task = questTask(accountWithAi());
        task.run();

        assertEquals(2, pauses.get());
        assertEquals(3, aiRequests.get());
        assertEquals(0, reviewRequests.get());
        assertEquals(1, discussRequests.get());
        assertEquals(Integer.valueOf(7), JSONUtil.parseObj(discussBody.get()).getInt("product_id"));
        assertTrue(task.getMessageBlocks().get(1).toString().startsWith(
                "今日猹评：已跳过\n原因：出现了不适合的说法\n本周帖子：已发布 "));
    }

    @Test
    public void skipsPostWhenCopyServiceIsDown() {
        aiMode = "error";

        WatchaCheckInTask task = questTask(accountWithAi());
        task.run();

        assertEquals(2, aiRequests.get());
        assertEquals(0, reviewRequests.get());
        assertEquals(0, discussRequests.get());
        String quest = task.getMessageBlocks().get(1).toString();
        assertTrue(quest.contains("文案生成失败"));
        assertTrue(quest.contains("busy"));
        assertTrue(quest.contains("本周帖子：已跳过"));
        assertTrue(quest.contains("文案接口不可用"));
    }

    @Test
    public void reportsIncompleteCopyConfigurationAfterCheckIn() {
        Map<String, String> environment = account();
        environment.put("WATCHA_PUBLISH", "true");
        environment.put("AI_MODEL", "test-model");

        WatchaCheckInTask task = task(environment);
        task.run();

        assertEquals(1, checkInRequests.get());
        assertEquals(0, aiRequests.get());
        assertEquals("任务结果：已跳过\n原因：文案接口配置不完整", task.getMessageBlocks().get(1).toString());
    }

    @Test
    public void skipsPublishingWhenSwitchIsOff() {
        WatchaCheckInTask task = questTask(accountWithAiDisabled());
        task.run();

        assertEquals(1, checkInRequests.get());
        assertEquals(0, aiRequests.get());
        assertEquals(0, reviewRequests.get());
        assertEquals(0, discussRequests.get());
        assertEquals(1, task.getMessageBlocks().size());
    }

    @Test
    public void reportsInvalidPublishSwitch() {
        Map<String, String> environment = accountWithAi();
        environment.put("WATCHA_PUBLISH", "maybe");

        WatchaCheckInTask task = task(environment);
        task.run();

        assertEquals(1, checkInRequests.get());
        assertEquals(0, aiRequests.get());
        assertEquals("任务结果：已跳过\n原因：WATCHA_PUBLISH 仅支持 true 或 false",
                task.getMessageBlocks().get(1).toString());
    }

    private static final String PRODUCT_7 = "面向科研人员。提供深度检索，还没写清推荐依据。";
    private static final String PRODUCT_8 = "面向排盘学习者。重点是时间规则，没有写明起算日期。";
    private static final String REVIEW_P1 = "我查资料最烦的是翻到第五页才发现全是同义词凑出来的结果，这款工具面向科研人员，把深度检索单独拎出来做，我猜它是想把这一层筛掉。";
    private static final String REVIEW_P2 = "让我犹豫的是推荐依据，为什么给你这几条，我还没看到能说服自己的说法。真拿它查资料前，我会先挑一个自己熟的题目，看结果还在不在点上。";
    private static final String POST_TITLE = "时间规则从哪一天起算";
    private static final String POST_BODY = "这类排盘工具面向排盘学习者，起算日期是我最先要问清的一件事。我会先拿一个自己会排的例子对一下口径，再决定要不要继续看。";
    private static final String POST_FOR_PRODUCT_7 = "这款工具面向科研人员，推荐依据是我最先要问清的一件事。正式用之前我会先看它给的理由站不站得住，再决定要不要继续。";

    private WatchaCheckInTask task(Map<String, String> environment) {
        return new WatchaCheckInTask(environment, baseUrl);
    }

    private WatchaCheckInTask questTask(Map<String, String> environment) {
        enableQuestServer();
        return new WatchaCheckInTask(environment, baseUrl, new WatchaPause() {
            @Override
            public void betweenActions() {
                pauses.incrementAndGet();
            }
        });
    }

    private Map<String, String> accountWithAi() {
        Map<String, String> environment = account();
        environment.put("WATCHA_PUBLISH", "true");
        environment.put("AI_BASE_URL", baseUrl + "/v1");
        environment.put("AI_API_KEY", "ai-test-key");
        environment.put("AI_MODEL", "test-model");
        return environment;
    }

    private Map<String, String> accountWithAiDisabled() {
        Map<String, String> environment = accountWithAi();
        environment.put("WATCHA_PUBLISH", "false");
        return environment;
    }

    private void enableQuestServer() {
        if (questServerEnabled) {
            return;
        }
        questServerEnabled = true;
        server.createContext("/api/v2/quests/", this::handleQuest);
        server.createContext("/api/v2/products/", this::handleProduct);
        server.createContext("/api/v2/discuss/posts", this::handleDiscuss);
        server.createContext("/v1/chat/completions", this::handleAi);
        server.createContext("/api/v2/users/10174946/posts", this::handleUserPosts);
    }

    private Map<String, String> account() {
        Map<String, String> environment = new HashMap<>();
        environment.put("WATCHA_USERNAME", "user@example.com");
        environment.put("WATCHA_PASSWORD", "secret-password");
        return environment;
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        loginBody.set(readBody(exchange));
        if (loginStatus != 200) {
            json(exchange, loginStatus, "{\"statusCode\":" + loginStatus + ",\"message\":\"" + loginMessage + "\"}");
            return;
        }
        json(exchange, 200, "{\"statusCode\":200,\"data\":{\"access_token\":\"login-token\",\"refresh_token\":\"refresh-token\"}}");
    }

    private void handleUser(HttpExchange exchange) throws IOException {
        readBody(exchange);
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        json(exchange, 200, "{\"statusCode\":200,\"data\":{\"id\":10174946,\"nickname\":\"观猹用户\",\"username\":\"watcha-user\"}}");
    }

    private void handleUserPosts(HttpExchange exchange) throws IOException {
        readBody(exchange);
        if (userPostsFail) {
            json(exchange, 500, "{\"statusCode\":500,\"message\":\"busy\"}");
            return;
        }
        JSONArray items = new JSONArray();
        if (postedToday) {
            items.add(JSONUtil.createObj()
                    .set("id", 31)
                    .set("title", "今天的帖子")
                    .set("create_at", todayIso()));
        }
        if (postedYesterday) {
            items.add(JSONUtil.createObj()
                    .set("id", 30)
                    .set("title", "昨天的帖子")
                    .set("create_at", yesterdayIso()));
        }
        json(exchange, 200, JSONUtil.createObj()
                .set("statusCode", 200)
                .set("data", JSONUtil.createObj().set("count", items.size()).set("items", items))
                .toString());
    }

    private static String todayIso() {
        return java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant().toString();
    }

    private static String yesterdayIso() {
        return java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .minusDays(1).toInstant().toString();
    }

    private void handleToday(HttpExchange exchange) throws IOException {
        readBody(exchange);
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        int streak = checkedIn ? 4 : 3;
        json(exchange, 200, "{\"statusCode\":200,\"data\":{\"checked_in\":" + checkedIn + ",\"streak_days\":" + streak + "}}");
    }

    private void handleCheckIn(HttpExchange exchange) throws IOException {
        readBody(exchange);
        if (!"POST".equals(exchange.getRequestMethod())) {
            json(exchange, 405, "{\"statusCode\":405,\"message\":\"method not allowed\"}");
            return;
        }
        checkInRequests.incrementAndGet();
        checkedIn = true;
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        json(exchange, 200, "{\"statusCode\":200,\"data\":{\"reward_count\":8,\"streak_days\":5}}");
    }

    private void handleBalances(HttpExchange exchange) throws IOException {
        readBody(exchange);
        int count = balanceRequests.incrementAndGet();
        if (balanceFailsAfterFirst && count > 1) {
            json(exchange, 500, "{\"statusCode\":500,\"message\":\"busy\"}");
            return;
        }
        int amount = 120 + (checkInRequests.get() > 0 ? 8 : 0) + claimedGuazi.get();
        json(exchange, 200, "{\"statusCode\":200,\"data\":{\"balances\":[{\"promotion_name\":\"瓜子\",\"amount\":"
                + amount + "},{\"promotion_name\":\"其他\",\"amount\":1}]}}");
    }

    private void handleQuest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = readBody(exchange);
        if (path.endsWith("/receive")) {
            receiveRequests.incrementAndGet();
            dailyReceived = true;
            json(exchange, 200, "{\"statusCode\":200,\"data\":{}}");
            return;
        }
        if (path.endsWith("/claim-reward")) {
            claimPaths.add(path);
            claimBodies.add(body);
            if (path.contains("/completion-items/review_1/")) {
                reviewItemClaimed = true;
                claimedGuazi.addAndGet(30);
            } else if (path.contains("/completion-items/posting_1/")) {
                postingClaimed = true;
                claimedGuazi.addAndGet(15);
            } else {
                dailyClaimed = true;
                claimedGuazi.addAndGet(30);
            }
            json(exchange, 200, "{\"statusCode\":200,\"data\":{}}");
            return;
        }
        questFetches.incrementAndGet();
        json(exchange, 200, questPayload());
    }

    private void handleProduct(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = readBody(exchange);
        int id = productId(path);
        if (path.endsWith("/reviews/user")) {
            boolean reviewed = id == 7 && product7Reviewed;
            json(exchange, 200, "{\"statusCode\":200,\"data\":{\"reviews\":" + (reviewed ? "[{\"id\":1}]" : "[]") + "}}");
            return;
        }
        if (path.endsWith("/reviews")) {
            reviewRequests.incrementAndGet();
            reviewBody.set(body);
            product7Reviewed = true;
            json(exchange, 200, "{\"statusCode\":200,\"data\":{\"id\":11}}");
            return;
        }
        String name = id == 8 ? "时者" : "切问";
        String description = thinProducts ? "一个工具。" : (id == 8 ? PRODUCT_8 : PRODUCT_7);
        JSONObject product = JSONUtil.createObj()
                .set("id", id)
                .set("name", name)
                .set("slogan", "看看它解决什么")
                .set("description", description)
                .set("categories", JSONUtil.createArray().put(JSONUtil.createObj().set("name", "效率工具")));
        json(exchange, 200, JSONUtil.createObj().set("statusCode", 200).set("data", product).toString());
    }

    private void handleDiscuss(HttpExchange exchange) throws IOException {
        discussBody.set(readBody(exchange));
        discussRequests.incrementAndGet();
        weeklyPostCount++;
        json(exchange, 200, "{\"statusCode\":200,\"data\":{\"id\":21}}");
    }

    private void handleAi(HttpExchange exchange) throws IOException {
        String request = readBody(exchange);
        aiRequests.incrementAndGet();
        aiBodies.add(request);
        aiAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        if ("error".equals(aiMode)) {
            json(exchange, 500, "{\"error\":{\"message\":\"busy\"}}");
            return;
        }
        boolean reviewRequest = !request.contains("体裁：帖子");
        JSONObject content = new JSONObject();
        if (reviewRequest) {
            JSONArray paragraphs = new JSONArray();
            if ("banned".equals(aiMode)) {
                paragraphs.add("亲测很好用。");
                paragraphs.add("赋能闭环。");
            } else {
                paragraphs.add(REVIEW_P1);
                paragraphs.add(REVIEW_P2);
            }
            content.set("paragraphs", paragraphs);
            content.set("vote", aiMode.startsWith("bad") ? "BAD" : "GOOD");
        } else {
            content.set("title", POST_TITLE);
            JSONArray paragraphs = new JSONArray();
            paragraphs.add(request.contains("面向科研人员") ? POST_FOR_PRODUCT_7 : POST_BODY);
            content.set("paragraphs", paragraphs);
        }
        JSONObject message = JSONUtil.createObj().set("content", content.toString());
        JSONArray choices = JSONUtil.createArray().put(JSONUtil.createObj().set("message", message));
        json(exchange, 200, JSONUtil.createObj().set("choices", choices).toString());
    }

    private String questPayload() {
        JSONObject items = JSONUtil.createObj();
        items.set("daily.product_review", dailyQuest());
        items.set("weekly.review", weeklyQuest(17, "每周猹评", "review", product7Reviewed ? 1 : 0,
                product7Reviewed, reviewItemClaimed));
        items.set("weekly.posting", weeklyQuest(18, "每周发帖", "posting", weeklyPostCount,
                weeklyPostCount >= 1, postingClaimed));
        return JSONUtil.createObj()
                .set("statusCode", 200)
                .set("data", JSONUtil.createObj().set("items", items))
                .toString();
    }

    private JSONObject dailyQuest() {
        String status;
        if (dailyReceivable && !dailyReceived) {
            status = "receivable";
        } else if (!product7Reviewed) {
            status = "in_progress";
        } else if (!dailyClaimed) {
            status = "claimable";
        } else {
            status = "completed";
        }
        JSONArray ids = new JSONArray();
        for (int id : productIds) {
            ids.add(id);
        }
        return JSONUtil.createObj()
                .set("quest_id", 19)
                .set("name", "每日产品猹评")
                .set("status", status)
                .set("quest_instance_id", 100)
                .set("state", JSONUtil.createObj().set("product_ids", ids).set("reviewed", product7Reviewed))
                .set("reward", guazi(30))
                .set("completion_items", new JSONArray());
    }

    private static JSONObject guazi(int count) {
        JSONObject item = JSONUtil.createObj()
                .set("type", "asset")
                .set("template_id", 103)
                .set("promotion_name", "瓜子")
                .set("count", count);
        return JSONUtil.createObj().set("items", JSONUtil.createArray().put(item));
    }

    private static JSONObject weeklyQuest(int questId, String name, String itemPrefix, int count,
                                           boolean firstDone, boolean firstClaimed) {
        JSONArray completion = new JSONArray();
        String label = "review".equals(itemPrefix) ? "猹评" : "帖子";
        for (int tier = 1; tier <= 3; tier++) {
            String itemStatus = "in_progress";
            if (tier == 1 && firstDone) {
                itemStatus = firstClaimed ? "claimed" : "claimable";
            }
            completion.add(JSONUtil.createObj()
                    .set("item_id", itemPrefix + "_" + tier)
                    .set("title", "发布 " + tier + " 篇" + label)
                    .set("reward", guazi("review".equals(itemPrefix) ? 30 : 15))
                    .set("status", itemStatus)
                    .set("progress", JSONUtil.createObj().set("current", Math.min(count, tier)).set("target", tier)));
        }
        return JSONUtil.createObj()
                .set("quest_id", questId)
                .set("name", name)
                .set("status", "in_progress")
                .set("quest_instance_id", questId == 17 ? 101 : 102)
                .set("state", JSONUtil.createObj().set("count", count))
                .set("completion_items", completion);
    }

    private static int productId(String path) {
        String rest = path.substring(path.indexOf("/products/") + "/products/".length());
        int slash = rest.indexOf('/');
        return Integer.parseInt(slash < 0 ? rest : rest.substring(0, slash));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = exchange.getRequestBody()) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                output.write(buffer, 0, length);
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
