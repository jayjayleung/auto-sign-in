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
