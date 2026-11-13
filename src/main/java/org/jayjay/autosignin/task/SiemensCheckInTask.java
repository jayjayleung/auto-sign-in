package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.jayjay.autosignin.entity.NotificationSection;

import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 西门子工业支持中心签到。
 * <p>
 * 除了每日签到，还会读取每日任务列表并把未完成项逐项做掉：收藏、回帖、评论、发帖、提问、回答。
 * 文案由商城「西游 AI 助手」生成，走同一套 ONEID 账号。
 * <p>
 * 具体动作拆分在 {@link SiemensForumActions} 和 {@link SiemensAnswerActions}，本类只负责编排、
 * 登录签到、抽奖、AI 生成以及风控（同日去重、同类间隔、连续失败熔断）。
 */
public class SiemensCheckInTask extends CheckInTask {

    static final String USERNAME_KEY = "SIEMENS_USERNAME";
    static final String PASSWORD_KEY = "SIEMENS_PASSWORD";
    /** 设为 false 可以只签到、不执行发帖类每日任务。 */
    static final String DAILY_TASKS_KEY = "SIEMENS_DAILY_TASKS";

    private static final String DEFAULT_SUPPORT_BASE_URL = "https://www.ad.siemens.com.cn";
    private static final String LOGIN_API_PATH = "/api/bff/v1.2/developer/ciam/login/pwd";
    private static final String TASKS_URL = "/MySupport/AjaxAPI/EventTasksAjax.ashx";
    /** 商城西游 AI 助手，用于生成每日任务里的帖子、回答和评论内容。 */
    private static final String AI_CHAT_URL = "https://dida-server.siemens.com.cn/v1/external_chat/qa";
    private static final String MALL_CLIENT_ID = "455ae4c460b858b72ec04825e8296d6180ZM6Q5JnIM";
    private static final String ONEID_ORIGIN = "https://api.oneid.siemens.com.cn";
    private static final String MALL_REFERER = "https://mall.siemens.com.cn/pcweb/xiyouAi?platform=3";

    private static final int TIMEOUT_MILLIS = 30000;
    /** AI 生成比普通接口慢，单独放宽到 60 秒。 */
    private static final int AI_TIMEOUT_MILLIS = 60000;
    private static final int MAX_REDIRECTS = 10;
    /** 同类提交之间的最小间隔，站点规则要求提问/回答/评论间隔至少 2 分钟。 */
    private static final long MIN_INTERVAL_MILLIS = 120_000L;
    private static final String GROUP_FORUM = "forum";
    private static final String GROUP_ANSWER = "answer";
    /** 连续失败达到上限就停止后续任务，避免风控期间继续请求。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 2;
    private static final double DUPLICATE_THRESHOLD = 0.8;

    private static final Pattern FID_PATTERN = Pattern.compile("fId\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern APP_ID_PATTERN = Pattern.compile("idaasAppId\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern USER_ID_PATTERN = Pattern.compile("userId\\s*=\\s*(\\d+)");
    private static final Pattern COIN_IN_TEXT = Pattern.compile("(\\d+)\\s*西币");

    /** 每日任务的稳定代码，站点改中文名或增删任务时这里才需要跟着改。 */
    static final String TASK_SIGN_IN = "NewMySupport_SignIn";
    static final String TASK_ASK = "NewMySupport_FAQuestion";
    static final String TASK_ANSWER = "NewMySupport_FAAnswer";
    static final String TASK_COLLECT_QUESTION = "NewMySupport_CollectionQA";
    static final String TASK_COMMENT_BEST_ANSWER = "NewMySupport_CommentBestAnswer";
    static final String TASK_PUBLISH_POST = "NewMySupport_BBSPostArticle";
    static final String TASK_PUBLISH_STORY = "NewMySupport_BBSPostStoryArticle";
    static final String TASK_REPLY_POST = "NewMySupport_BBSReplyArticle";
    static final String TASK_COLLECT_POST = "NewMySupport_CollectionArticle";
    static final String TASK_COMMENT_POST = "NewMySupport_CommentArticle";

    private final Map<String, String> environment;
    private final String supportBaseUrl;
    private final String entryUrl;
    private final String signUrl;
    private final CookieJar cookies = new CookieJar();
    private final Map<String, Long> lastSubmitAt = new HashMap<>();
    private final boolean dailyTasksByDefault;

    private String currentUserId = "";
    private String cachedAiToken;
    private int consecutiveFailures;
    private boolean lastTaskFailed;
    /** 本次运行已经处理过的任务代码。 */
    private final Set<String> handled = new HashSet<>();

    public SiemensCheckInTask() {
        this(System.getenv(), DEFAULT_SUPPORT_BASE_URL, true);
    }

    SiemensCheckInTask(Map<String, String> environment, String supportBaseUrl) {
        this(environment, supportBaseUrl, false);
    }

    SiemensCheckInTask(Map<String, String> environment, String supportBaseUrl, boolean dailyTasksByDefault) {
        this.environment = environment;
        this.supportBaseUrl = trimTrailingSlash(StrUtil.blankToDefault(supportBaseUrl, DEFAULT_SUPPORT_BASE_URL));
        this.entryUrl = this.supportBaseUrl + "/MySupport/index.aspx";
        this.signUrl = this.supportBaseUrl + "/MySupport/AjaxAPI/SignInAjax.ashx";
        this.dailyTasksByDefault = dailyTasksByDefault;
    }

    @Override
    public NotificationSection buildNotificationSection() {
        return new NotificationSection("西门子签到", messageBlocks);
    }

    @Override
    public CheckInTask run() {
        try {
            System.out.println("[西门子] 签到任务开始");
            String username = environment == null ? null : environment.get(USERNAME_KEY);
            String password = environment == null ? null : environment.get(PASSWORD_KEY);
            if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
                System.out.println("[西门子] 用户名或密码未配置，跳过签到");
                enabled = false;
                return this;
            }
            String failure = execute(username.trim(), password);
            if (failure != null) {
                addMessage("签到失败：", failure);
            }
        } catch (Exception e) {
            e.printStackTrace();
            addMessage("西门子签到失败，请查看日志");
        }
        System.out.println("[西门子] 签到任务结束");
        return this;
    }

    private String execute(String username, String password) {
        System.out.println("[西门子] 打开登录页");
        Page loginPage = follow(entryUrl);
        if (loginPage == null || !isOneIdLoginPage(loginPage.body)) {
            return "没有打开登录页";
        }
        String flowId = group(FID_PATTERN, loginPage.body);
        String appId = group(APP_ID_PATTERN, loginPage.body);
        if (StrUtil.isBlank(flowId) || StrUtil.isBlank(appId)) {
            return "登录页缺少会话信息";
        }

        System.out.println("[西门子] 开始登录");
        JSONObject loginBody = login(loginPage.url, appId, flowId, username, password);
        if (loginBody == null) {
            return "登录没有返回结果";
        }
        if (!isLoginSuccess(loginBody)) {
            String reason = describeLoginFailure(loginBody);
            System.out.println("[西门子] 登录失败：" + reason);
            return reason;
        }
        JSONObject data = loginBody.getJSONObject("data");
        String flowType = data == null ? null : data.getStr("flowType");
        if (StrUtil.isNotBlank(flowType) && flowType.startsWith("NEED_")) {
            String reason = describeLoginCode(flowType);
            System.out.println("[西门子] 登录未完成：" + reason);
            return reason;
        }
        String redirectUrl = data == null ? null : data.getStr("redirectUrl");
        if (StrUtil.isBlank(redirectUrl)) {
            return "登录成功但没有返回跳转地址";
        }

        System.out.println("[西门子] 跟随登录跳转");
        Page landed = follow(resolveUrl(loginPage.url, redirectUrl));
        if (landed == null || isOneIdLoginPage(landed.body)) {
            return "登录后仍停在登录页";
        }

        System.out.println("[西门子] 查询签到状态");
        JSONObject detail = readSignDetail();
        if (!isTrustedSignDetail(detail)) {
            return "登录后没有进入支持中心";
        }
        String result = "今日已签到";
        if (!Boolean.TRUE.equals(detail.getBool("TodayHaveSign"))) {
            System.out.println("[西门子] 开始签到");
            String signBody = postSign("sign");
            System.out.println("[西门子] 签到响应：" + abbreviate(signBody));
            if (!signedSuccessfully(signBody)) {
                return "签到接口返回失败";
            }
            result = "签到成功";
            JSONObject refreshed = readSignDetail();
            if (isTrustedSignDetail(refreshed)) {
                detail = refreshed;
            }
        } else {
            System.out.println("[西门子] 今日已签到");
        }

        Profile profile = loadProfile();
        Integer coinBefore = parseIntOrNull(profile.coin);
        if (StrUtil.isNotBlank(profile.userId)) {
            currentUserId = profile.userId;
        }
        addMessage("签到结果：", result);
        if (detail.get("SignCount") != null) {
            addMessage("已签到：", detail.getStr("SignCount"), " 天");
        }
        playLottery(readSignDetail());

        if (dailyTasksEnabled()) {
            completeDailyTasks();
        }

        // 任务都做完后再读一次余额，用差额体现今天实际增加了多少西币。
        Profile after = loadProfile();
        String coinValue = StrUtil.blankToDefault(after.coin, profile.coin);
        if (StrUtil.isNotBlank(coinValue)) {
            Integer coinAfter = parseIntOrNull(coinValue);
            if (coinBefore != null && coinAfter != null && coinAfter >= coinBefore) {
                addMessage("西币：", coinValue, "（本次 +" + (coinAfter - coinBefore) + "）");
            } else {
                addMessage("西币：", coinValue);
            }
        }
        return null;
    }

    // ==================== 每日任务编排 ====================

    private boolean dailyTasksEnabled() {
        String flag = environment == null ? null : environment.get(DAILY_TASKS_KEY);
        if (StrUtil.isNotBlank(flag)) {
            return !"false".equalsIgnoreCase(flag.trim());
        }
        return dailyTasksByDefault;
    }

    /** 把剩下的每日任务做掉，逐项独立执行，某一项失败不影响其他项。 */
    private void completeDailyTasks() {
        if (alreadyRanToday()) {
            System.out.println("[西门子] 今天已经执行过每日任务，跳过，避免重复发帖");
            addMessage("每日任务：", "今天已执行过，跳过");
            return;
        }
        List<String> pending = pendingDailyTasks(readTasks());
        if (pending.isEmpty()) {
            System.out.println("[西门子] 每日任务已全部完成");
            markRanToday();
            return;
        }
        System.out.println("[西门子] 待完成每日任务：" + pending);
        SiemensForumActions forum = new SiemensForumActions(this);
        SiemensAnswerActions answer = new SiemensAnswerActions(this);
        String questionId = null;
        for (String code : pending) {
            lastTaskFailed = false;
            try {
                // 按任务代码分发，中文名和排序随时会被站点调整。
                if (TASK_COLLECT_QUESTION.equals(code)) {
                    questionId = answer.collectQuestion(questionId);
                } else if (TASK_COLLECT_POST.equals(code)) {
                    forum.collectPost();
                } else if (TASK_ANSWER.equals(code)) {
                    answer.answerQuestion();
                } else if (TASK_COMMENT_BEST_ANSWER.equals(code)) {
                    answer.commentBestAnswer();
                } else if (TASK_REPLY_POST.equals(code)) {
                    forum.replyPost();
                } else if (TASK_COMMENT_POST.equals(code)) {
                    forum.commentPost();
                } else if (TASK_PUBLISH_POST.equals(code)) {
                    forum.publishPost();
                } else if (TASK_PUBLISH_STORY.equals(code)) {
                    forum.publishStory();
                } else if (TASK_ASK.equals(code)) {
                    answer.askQuestion();
                } else {
                    System.out.println("[西门子] 新增的未支持任务：" + code + "，跳过");
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("[西门子] 任务失败：" + code);
                lastTaskFailed = true;
            }
            if (lastTaskFailed) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    System.out.println("[西门子] 连续失败 " + consecutiveFailures + " 次，停止剩余任务，避免触发风控");
                    addMessage("每日任务：", "连续失败 " + consecutiveFailures + " 次，已中止");
                    return;
                }
            } else {
                consecutiveFailures = 0;
                handled.add(code);
            }
        }
        List<String> left = pendingDailyTasks(readTasks());
        markRanToday();
        // 故事帖提交后进入站点审核，任务状态当天不会变成已完成，但已经算处理过了。
        boolean submittedStory = handled.contains(TASK_PUBLISH_STORY);
        left.remove(TASK_PUBLISH_STORY);
        if (left.isEmpty()) {
            addMessage("每日任务：", submittedStory ? "已全部完成，故事帖待审核" : "已全部完成");
        } else {
            List<String> names = new ArrayList<>();
            for (String code : left) {
                names.add(taskDisplayName(code));
            }
            String text = "未完成 " + names;
            if (submittedStory) {
                text = text + "（故事帖已提交，待审核）";
            }
            addMessage("每日任务：", text);
        }
    }

    /**
     * 判断今天是否已经跑过每日任务。同一账号被多个容器或 Actions 同时配置时，
     * 重复运行会重复发帖被判灌水，所以用账号维度记录当天是否已执行。
     */
    private boolean alreadyRanToday() {
        String username = environment == null ? null : environment.get(USERNAME_KEY);
        if (StrUtil.isBlank(username)) {
            return false;
        }
        Path file = runMarkerPath(username.trim());
        try {
            if (!Files.exists(file)) {
                return false;
            }
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            return LocalDate.now().toString().equals(content);
        } catch (Exception e) {
            return false;
        }
    }

    private void markRanToday() {
        String username = environment == null ? null : environment.get(USERNAME_KEY);
        if (StrUtil.isBlank(username)) {
            return;
        }
        try {
            Path file = runMarkerPath(username.trim());
            Files.createDirectories(file.getParent());
            Files.write(file, LocalDate.now().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 写不进去就当没记录，下次继续执行。
        }
    }

    private Path runMarkerPath(String username) {
        String key = sha1Hex(username).substring(0, 12);
        return stateDir().resolve("siemens-ran-" + key + ".txt");
    }

    /**
     * 状态文件目录。容器里 /app 在重启后仍然保留，只有重建镜像或 docker compose down
     * 才会丢；本地运行时退回系统临时目录。
     */
    static Path stateDir() {
        Path app = Paths.get("/app");
        if (Files.isDirectory(app) && Files.isWritable(app)) {
            return app.resolve("state");
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "auto-sign-in");
    }

    // ==================== 任务列表 ====================

    /** 读取每日任务列表，TaskType 1 是每日任务，EventTasksStatus 2 表示已完成。 */
    private JSONObject readTasks() {
        HttpResult result = post(supportBaseUrl + TASKS_URL,
                "action=GetAllEventTasksByActiveType&activityType=10", signHeaders());
        return parseObject(result.body);
    }

    /** 找出还没完成的每日任务代码。 */
    static List<String> pendingDailyTasks(JSONObject tasks) {
        List<String> pending = new ArrayList<>();
        if (tasks == null) {
            return pending;
        }
        JSONArray list = tasks.getJSONArray("TaskList");
        if (list == null) {
            return pending;
        }
        for (int i = 0; i < list.size(); i++) {
            JSONObject task = list.getJSONObject(i);
            if (task == null || task.getInt("TaskType", 0) != 1) {
                continue;
            }
            if (task.getInt("EventTasksStatus", 0) != 2) {
                String code = task.getStr("TaskName");
                pending.add(StrUtil.blankToDefault(code, task.getStr("TaskNameCN", "unknown")));
            }
        }
        return pending;
    }

    /** 把任务代码翻译成中文名，写进通知。 */
    static String taskDisplayName(String code) {
        if (code == null) {
            return "";
        }
        switch (code) {
            case TASK_SIGN_IN:
                return "网站签到";
            case TASK_ASK:
                return "找答案提问";
            case TASK_ANSWER:
                return "找答案回答";
            case TASK_COLLECT_QUESTION:
                return "收藏问答";
            case TASK_COMMENT_BEST_ANSWER:
                return "评论最佳答案";
            case TASK_PUBLISH_POST:
                return "论坛发帖";
            case TASK_PUBLISH_STORY:
                return "论坛发故事帖";
            case TASK_REPLY_POST:
                return "论坛回帖";
            case TASK_COLLECT_POST:
                return "收藏帖子";
            case TASK_COMMENT_POST:
                return "评论帖子";
            default:
                return code;
        }
    }

    // ==================== 风控 ====================

    /** 同类提交间隔不足 2 分钟时先等待，避免被判「短时间内多次发帖」。 */
    void waitBeforeSubmit(String group) {
        Long last = lastSubmitAt.get(group);
        if (last == null) {
            return;
        }
        long wait = MIN_INTERVAL_MILLIS - (System.currentTimeMillis() - last);
        if (wait <= 0) {
            return;
        }
        System.out.println("[西门子] " + group + " 距上次提交不足 2 分钟，等待 "
                + Math.round(wait / 1000.0) + " 秒");
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void markSubmitted(String group) {
        lastSubmitAt.put(group, System.currentTimeMillis());
    }

    void markTaskFailed(boolean failed) {
        lastTaskFailed = failed;
    }

    // ==================== 签到与抽奖 ====================

    private JSONObject readSignDetail() {
        return parseObject(postSign("getsignbydate"));
    }

    private String postSign(String action) {
        HttpResult result = post(signUrl, "action=" + action, signHeaders());
        return result.body;
    }

    private Map<String, String> signHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Origin", origin(supportBaseUrl));
        headers.put("Referer", entryUrl);
        return headers;
    }

    /**
     * 签到后把攒下的抽奖机会用掉。次数由签到发放，NextSignGiveCoinAwardCount 为 true
     * 表示下一次签到会再给一次，所以每天要先检查再抽，否则机会会一直攒着。
     */
    private void playLottery(JSONObject detail) {
        int coinChances = detail == null ? 0 : detail.getInt("GetCoinAwardCount", 0);
        int bigChances = detail == null ? 0 : detail.getInt("GetBigAwardCount", 0);
        if (coinChances <= 0 && bigChances <= 0) {
            System.out.println("[西门子] 今天没有抽奖机会");
            return;
        }
        int coinDraws = 0;
        int coinTotal = 0;
        for (int i = 0; i < Math.max(coinChances, 0); i++) {
            JSONObject prize = parseObject(postSign("awardcoin"));
            if (prize == null) {
                break;
            }
            if (prize.getInt("awardType", -1) == -1) {
                System.out.println("[西门子] 抽西币未成功：" + prize.getStr("msg"));
                break;
            }
            coinDraws++;
            coinTotal += coinAmount(prize);
            System.out.println("[西门子] 抽西币：" + prize.getStr("msg"));
            if (prize.getInt("awardCoinCount", 0) <= 0) {
                break;
            }
        }
        String bigResult = "";
        int bigDraws = 0;
        for (int i = 0; i < Math.max(bigChances, 0); i++) {
            JSONObject prize = parseObject(postSign("bigaward"));
            if (prize == null) {
                break;
            }
            bigDraws++;
            String message = prize.getStr("msg");
            System.out.println("[西门子] 抽实物奖：" + message);
            if (StrUtil.isNotBlank(message)) {
                bigResult = message;
            }
            if (prize.getInt("bigAwardCount", 0) <= 0) {
                break;
            }
        }
        List<String> summary = new ArrayList<>();
        if (coinDraws > 0) {
            summary.add("西币 " + coinDraws + " 次" + (coinTotal > 0 ? "共 " + coinTotal : ""));
        }
        if (bigDraws > 0) {
            summary.add("实物奖 " + bigDraws + " 次" + (StrUtil.isNotBlank(bigResult) ? "：" + bigResult : ""));
        }
        if (!summary.isEmpty()) {
            addMessage("抽奖：", String.join("，", summary));
        }
    }

    /** 从返回里尽量解析本次抽到的西币数，解析不出按 0 处理。 */
    private static int coinAmount(JSONObject prize) {
        for (String key : new String[]{"awardCoin", "coin", "awardValue", "awardNum"}) {
            Integer value = prize.getInt(key);
            if (value != null && value > 0) {
                return value;
            }
        }
        Matcher matcher = COIN_IN_TEXT.matcher(StrUtil.blankToDefault(prize.getStr("msg"), ""));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    // ==================== AI 生成 ====================

    /** 生成内容并和上次发过的内容比对，太像就重写一次。 */
    String generateUnique(String prompt, String kind) {
        String previous = loadLastContent(kind);
        String content = generateWithAi(prompt, previous);
        if (StrUtil.isBlank(content)) {
            lastTaskFailed = true;
            return null;
        }
        if (StrUtil.isNotBlank(previous) && similarity(content, previous) > DUPLICATE_THRESHOLD) {
            System.out.println("[西门子] " + kind + "内容与上次过于接近，重写一次");
            String retry = generateWithAi(prompt + "\n（务必换一个完全不同的开头和例子。）", previous);
            if (StrUtil.isNotBlank(retry)) {
                content = retry;
            }
        }
        rememberContent(kind, content);
        return content;
    }

    /** 每次请求带上当天日期和随机角度，避免连续几天生成雷同内容。 */
    String generateWithAi(String prompt, String avoid) {
        String token = mallAccessToken();
        if (StrUtil.isBlank(token)) {
            return null;
        }
        String varied = prompt + "\n（今天是 " + LocalDate.now() + "，请换一个与以往不同的角度和说法"
                + (StrUtil.isBlank(avoid) ? "。" : "，不要与下面这段雷同：" + avoid + "。");
        String answer = askAi(token, varied);
        if (StrUtil.isBlank(answer) && StrUtil.isNotBlank(avoid)) {
            answer = askAi(token, prompt + "\n（今天是 " + LocalDate.now() + "，换个说法重新写一遍。）");
        }
        return answer;
    }

    private String askAi(String token, String prompt) {
        JSONObject payload = JSONUtil.createObj();
        payload.set("input", prompt);
        payload.set("channel_is", true);
        payload.set("internal", true);
        // chatId 传空串时服务端会新建会话；传随机值会被判为会话失效。
        payload.set("chatId", "");
        payload.set("are", "");
        payload.set("location", "");
        payload.set("organization", "");
        payload.set("username", "");
        payload.set("category", "");
        payload.set("no_rag", false);
        payload.set("stream", false);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + token);
        headers.put("Origin", "https://mall.siemens.com.cn");
        headers.put("Referer", MALL_REFERER);
        HttpResult result = post(AI_CHAT_URL, payload.toString(), "application/json;charset=UTF-8",
                headers, AI_TIMEOUT_MILLIS);
        JSONObject body = parseObject(result.body);
        if (body == null) {
            System.out.println("[西门子] AI 助手 HTTP " + result.status);
            return null;
        }
        String answer = body.getStr("Ans");
        if (StrUtil.isBlank(answer)) {
            System.out.println("[西门子] AI 助手没有返回内容：" + body.getStr("message"));
            return null;
        }
        return StrUtil.trim(answer);
    }

    /** 用商城 client_id 走 ONEID 密码登录，拿到调用 AI 用的 access_token。 */
    private String mallAccessToken() {
        if (StrUtil.isNotBlank(cachedAiToken)) {
            return cachedAiToken;
        }
        String authUrl = ONEID_ORIGIN + "/api/v1.2/sso/" + MALL_CLIENT_ID
                + "?prompt=login&baseUrl=" + encode(ONEID_ORIGIN)
                + "&showFrameLogo=0&showFrameName=0&source=IDaaSLoginSDK";
        Page page = follow(authUrl);
        if (page == null || !isOneIdLoginPage(page.body)) {
            return null;
        }
        String flowId = group(FID_PATTERN, page.body);
        String appId = group(APP_ID_PATTERN, page.body);
        if (StrUtil.isBlank(flowId) || StrUtil.isBlank(appId)) {
            return null;
        }
        String username = environment == null ? null : environment.get(USERNAME_KEY);
        String password = environment == null ? null : environment.get(PASSWORD_KEY);
        JSONObject body = login(page.url, appId, flowId, username.trim(), password);
        if (!isLoginSuccess(body)) {
            System.out.println("[西门子] AI 助手登录失败：" + describeLoginFailure(body));
            return null;
        }
        JSONObject data = body.getJSONObject("data");
        cachedAiToken = data == null ? null : data.getStr("access_token");
        return cachedAiToken;
    }

    private void rememberContent(String kind, String content) {
        try {
            Path dir = stateDir();
            Files.createDirectories(dir);
            Files.write(dir.resolve("siemens-" + kind + ".txt"), content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 写不进去不影响本次执行。
        }
    }

    /** 取上次运行留下的内容，作为今天的对照。 */
    private String loadLastContent(String kind) {
        try {
            Path file = stateDir().resolve("siemens-" + kind + ".txt");
            if (Files.exists(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            // 读不到就当没有历史。
        }
        return null;
    }

    /** 粗略比较两段文字的相似度，只用来判断是否几乎一样。 */
    static double similarity(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return 0;
        }
        Set<String> a = shingles(left);
        Set<String> b = shingles(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private static Set<String> shingles(String text) {
        String cleaned = text.replaceAll("\\s+", "");
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 3 <= cleaned.length(); i++) {
            set.add(cleaned.substring(i, i + 3));
        }
        return set;
    }

    // ==================== 登录 ====================

    private JSONObject login(String loginPageUrl, String appId, String flowId, String username, String password) {
        JSONObject payload = JSONUtil.createObj();
        payload.set("agreeConsent", true);
        payload.set("username", username);
        payload.set("password", password);
        payload.set("captchaCode", "");
        payload.set("captchaText", "");
        payload.set("fId", flowId);
        payload.set("userType", "default");
        payload.set("language", "zh");
        payload.set("readTerms", new JSONArray());
        String timestamp = String.valueOf(System.currentTimeMillis());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Origin", origin(loginPageUrl));
        headers.put("Referer", loginPageUrl);
        headers.put("idaasAppId", appId);
        headers.put("idaasTimeStamp", timestamp);
        headers.put("idaasSign", sha1Hex(appId + timestamp));
        HttpResult result = post(origin(loginPageUrl) + LOGIN_API_PATH, payload.toString(),
                "application/json;charset=UTF-8", headers, TIMEOUT_MILLIS);
        JSONObject loginJson = parseObject(result.body);
        System.out.println("[西门子] 登录 HTTP " + result.status
                + (loginJson == null ? "" : " " + loginJson.getStr("code")));
        return loginJson;
    }

    static boolean isCaptchaRequired(JSONObject body) {
        String code = body == null ? null : body.getStr("code");
        String message = body == null ? null : body.getStr("message");
        return "Params.Blank.CaptchaText".equals(code) || "Params.Blank.CaptchaCode".equals(code)
                || "Operation.Failure.Captcha.Invalid".equals(code)
                || (message != null && message.contains("Captcha"));
    }

    static boolean isLoginThrottled(JSONObject body) {
        String code = body == null ? null : body.getStr("code");
        return code != null && code.contains("Too.Frequency");
    }

    static boolean isLoginSuccess(JSONObject body) {
        return body != null && ("Operation.Success".equals(body.getStr("code"))
                || Boolean.TRUE.equals(body.getBool("success")));
    }

    static boolean isOneIdLoginPage(String html) {
        return html != null && html.contains("window.idaas") && html.contains("idaasAppId");
    }

    static String describeLoginFailure(JSONObject body) {
        String message = body == null ? null : body.getStr("message");
        String code = body == null ? null : body.getStr("code");
        String token = StrUtil.blankToDefault(message, code);
        if (StrUtil.isBlank(token)) {
            return "登录失败";
        }
        if (containsHan(token)) {
            return token;
        }
        return describeLoginCode(token);
    }

    static String describeLoginCode(String code) {
        if ("Operation.Failure.Username.Or.Password.Error".equals(code)) {
            return "用户名或密码错误";
        }
        if ("Operation.Failure.Auth.Too.Frequency".equals(code)
                || code != null && code.contains("Too.Frequency")) {
            return "登录太频繁已被限制，请稍后重试";
        }
        if ("Operation.Failure.User.Locked".equals(code) || code != null && code.contains("User.Locked")) {
            return "账号被临时锁定，请稍后重试";
        }
        if ("Params.Blank.CaptchaText".equals(code) || "Params.Blank.CaptchaCode".equals(code)
                || "Operation.Failure.Captcha.Invalid".equals(code)) {
            return "登录需要图形验证码，请稍后重试";
        }
        if ("Operation.Failure.FId.Not.Found".equals(code) || "Params.Blank.FId".equals(code)) {
            return "登录会话已失效";
        }
        if (code != null && code.contains("Captcha")) {
            return "登录需要验证码，请稍后重试";
        }
        if ("NEED_TWO_FACTOR".equals(code)) {
            return "账号开启了二次验证，无法自动登录";
        }
        if ("NEED_UPDATE_PASSWORD".equals(code)) {
            return "账号需要先修改密码";
        }
        if ("NEED_READ_TERMS".equals(code)) {
            return "账号需要先同意协议";
        }
        return code;
    }

    // ==================== 签到结果判定 ====================

    /**
     * 未登录时接口返回 TodayHaveSign=true，且 SignDetail.UserID 为 -1。
     * 已登录但今天还没签到时，SignDetail 为 null，TodayHaveSign 为 false。
     */
    static boolean isTrustedSignDetail(JSONObject detail) {
        if (detail == null || !detail.containsKey("TodayHaveSign")) {
            return false;
        }
        JSONObject signDetail = detail.getJSONObject("SignDetail");
        if (signDetail == null || signDetail.isEmpty()) {
            return !Boolean.TRUE.equals(detail.getBool("TodayHaveSign"));
        }
        Integer userId = signDetail.getInt("UserID");
        return userId != null && userId > 0;
    }

    static boolean signedSuccessfully(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.trim();
        if ("true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) {
            return true;
        }
        if (trimmed.isEmpty() || "false".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
            return false;
        }
        JSONObject json = parseObject(trimmed);
        if (json == null) {
            return trimmed.contains("成功");
        }
        if (json.containsKey("TodayHaveSign")) {
            return Boolean.TRUE.equals(json.getBool("TodayHaveSign"));
        }
        if (json.containsKey("success") && !Boolean.TRUE.equals(json.getBool("success"))) {
            return false;
        }
        if (json.containsKey("status")) {
            return json.getInt("status", 0) == 1;
        }
        return true;
    }

    // ==================== 请求与工具 ====================

    HttpResult get(String url) {
        return exchange("GET", url, null, null, null, TIMEOUT_MILLIS);
    }

    HttpResult post(String url, String body, Map<String, String> headers) {
        return exchange("POST", url, body, "application/x-www-form-urlencoded", headers, TIMEOUT_MILLIS);
    }

    HttpResult post(String url, String body, String contentType, Map<String, String> headers, int timeoutMillis) {
        return exchange("POST", url, body, contentType, headers, timeoutMillis);
    }

    Page follow(String url) {
        String current = url;
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpResult result = get(current);
            if (result.status >= 300 && result.status < 400 && StrUtil.isNotBlank(result.location)) {
                current = resolveUrl(current, result.location.trim());
                continue;
            }
            return new Page(current, result.body);
        }
        return null;
    }

    private HttpResult exchange(String method, String url, String body, String contentType,
                                Map<String, String> headers, int timeoutMillis) {
        HttpRequest request = "POST".equals(method) ? HttpRequest.post(url) : HttpRequest.get(url);
        // cookie("") 关闭 Hutool 全局 Cookie，避免把别的站点会话带进来；本任务自己维护 Cookie。
        request.cookie(StrUtil.blankToDefault(cookies.headerFor(url), ""))
                .setFollowRedirects(false)
                .timeout(timeoutMillis)
                .header("User-Agent", userAgent);
        if (headers != null) {
            request.headerMap(headers, true);
        }
        if (body != null) {
            request.body(body);
        }
        if (contentType != null) {
            request.contentType(contentType);
        }
        HttpResponse response = request.execute();
        cookies.store(url, response);
        return new HttpResult(response.getStatus(), response.body(), response.header("Location"));
    }

    private Profile loadProfile() {
        Page page = follow(entryUrl);
        if (page == null || isOneIdLoginPage(page.body)) {
            return Profile.empty();
        }
        Matcher userId = USER_ID_PATTERN.matcher(page.body);
        return new Profile(userId.find() ? userId.group(1) : "",
                readableText(elementText(page.body, "h6.singleLine")),
                readableText(elementText(page.body, "a[href*=MyPrize]")));
    }

    /** 取首页上的用户名和西币，用 Jsoup 解析，失败返回 null。 */
    private static String elementText(String html, String selector) {
        try {
            org.jsoup.nodes.Element element = org.jsoup.Jsoup.parse(html).selectFirst(selector);
            return element == null ? null : element.text();
        } catch (Exception e) {
            return null;
        }
    }

    String supportBaseUrl() {
        return supportBaseUrl;
    }

    String currentUserId() {
        return currentUserId;
    }

    Map<String, String> supportHeaders(String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Origin", origin(supportBaseUrl));
        headers.put("Referer", referer);
        return headers;
    }

    static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String resolveUrl(String current, String location) {
        return URI.create(current).resolve(location).toString();
    }

    static String origin(String url) {
        URI uri = URI.create(url);
        if (uri.getPort() < 0) {
            return uri.getScheme() + "://" + uri.getHost();
        }
        return uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
    }

    static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static String cleanTitle(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("标题：", "").replace("标题:", "").trim();
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    static String inputValue(String html, String name) {
        if (html == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("name=\"" + name + "\"[^>]*value=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE)
                .matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String attributeValue(String html, String name) {
        if (html == null) {
            return null;
        }
        Matcher matcher = Pattern.compile(name + "=\"([^\"]*)\"").matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    static String readableText(String text) {
        if (StrUtil.isBlank(text) || text.contains("@")) {
            return null;
        }
        return StrUtil.trim(text);
    }

    static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180);
    }

    static JSONObject parseObject(String body) {
        if (StrUtil.isBlank(body) || !JSONUtil.isTypeJSONObject(body)) {
            return null;
        }
        return JSONUtil.parseObj(body);
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean containsHan(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) >= 0x4E00) {
                return true;
            }
        }
        return false;
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return StrUtil.isBlank(value) ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static final class Page {
        final String url;
        final String body;

        Page(String url, String body) {
            this.url = url;
            this.body = body;
        }
    }

    static final class HttpResult {
        final int status;
        final String body;
        final String location;

        HttpResult(int status, String body, String location) {
            this.status = status;
            this.body = body;
            this.location = location;
        }
    }

    static final class Profile {
        final String userId;
        final String name;
        final String coin;

        Profile(String userId, String name, String coin) {
            this.userId = userId;
            this.name = name;
            this.coin = coin;
        }

        static Profile empty() {
            return new Profile("", null, null);
        }
    }

    static final class CookieJar {
        private final List<StoredCookie> cookies = new ArrayList<>();

        void store(String requestUrl, HttpResponse response) {
            if (response == null || response.headers() == null) {
                return;
            }
            for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
                if (entry.getKey() != null && "set-cookie".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                    for (String header : entry.getValue()) {
                        store(requestUrl, header);
                    }
                }
            }
        }

        void store(String requestUrl, String setCookie) {
            if (StrUtil.isBlank(setCookie)) {
                return;
            }
            List<HttpCookie> parsed;
            try {
                parsed = HttpCookie.parse(setCookie);
            } catch (IllegalArgumentException e) {
                return;
            }
            String host = hostOf(requestUrl);
            for (HttpCookie cookie : parsed) {
                if (StrUtil.isBlank(cookie.getName())) {
                    continue;
                }
                if (cookie.getMaxAge() == 0) {
                    remove(cookie.getName(), host);
                    continue;
                }
                StoredCookie stored = new StoredCookie();
                stored.name = cookie.getName();
                stored.value = cookie.getValue() == null ? "" : cookie.getValue();
                if (StrUtil.isBlank(cookie.getDomain())) {
                    stored.domain = host;
                    stored.hostOnly = true;
                } else {
                    stored.domain = stripDot(cookie.getDomain().toLowerCase());
                    stored.hostOnly = false;
                }
                stored.path = StrUtil.blankToDefault(cookie.getPath(), "/");
                stored.secure = cookie.getSecure();
                replace(stored);
            }
        }

        String headerFor(String url) {
            StringBuilder header = new StringBuilder();
            for (StoredCookie cookie : cookies) {
                if (!matches(cookie, url)) {
                    continue;
                }
                if (header.length() > 0) {
                    header.append("; ");
                }
                header.append(cookie.name).append('=').append(cookie.value);
            }
            return header.toString();
        }

        private void replace(StoredCookie stored) {
            Iterator<StoredCookie> iterator = cookies.iterator();
            while (iterator.hasNext()) {
                StoredCookie current = iterator.next();
                if (current.name.equals(stored.name) && current.domain.equals(stored.domain)
                        && current.path.equals(stored.path)) {
                    iterator.remove();
                }
            }
            cookies.add(stored);
        }

        private void remove(String name, String host) {
            cookies.removeIf(cookie -> cookie.name.equals(name)
                    && (cookie.domain.equals(host) || host.endsWith("." + cookie.domain)));
        }

        private static boolean matches(StoredCookie cookie, String url) {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            if (cookie.secure && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            if (cookie.hostOnly) {
                if (!host.equals(cookie.domain)) {
                    return false;
                }
            } else if (!host.equals(cookie.domain) && !host.endsWith("." + cookie.domain)) {
                return false;
            }
            String path = StrUtil.blankToDefault(uri.getPath(), "/");
            String cookiePath = cookie.path;
            if (cookiePath.endsWith("/")) {
                return path.startsWith(cookiePath);
            }
            return path.equals(cookiePath) || path.startsWith(cookiePath + "/");
        }

        private static String hostOf(String url) {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase();
        }

        private static String stripDot(String domain) {
            return domain.startsWith(".") ? domain.substring(1) : domain;
        }
    }

    private static final class StoredCookie {
        private String name;
        private String value;
        private String domain;
        private String path;
        private boolean hostOnly;
        private boolean secure;
    }
}
