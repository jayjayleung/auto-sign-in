package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.NotificationSection;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class WatchaCheckInTask extends CheckInTask {

    private static final String DEFAULT_BASE_URL = "https://watcha.cn";
    private static final String USERNAME_KEY = "WATCHA_USERNAME";
    private static final String PASSWORD_KEY = "WATCHA_PASSWORD";
    private static final String POST_KEY = "WATCHA_PUBLISH_POST";
    private static final String LEGACY_PUBLISH_KEY = "WATCHA_PUBLISH";
    /**
     * 猹评开关。打开后由程序自己挑推荐产品、采集一次官网页面、按分布抽出来的形状成稿并提交。
     * 注意站点规范禁止通篇 AI 生成，这里做的是「真打开一次 + 形状随机化」，风险自负。
     */
    private static final String PUBLISH_REVIEW_KEY = "WATCHA_PUBLISH_REVIEW";
    /** 整周几乎不写的概率（真人会空档，不是每天掷硬币）。旧键 WATCHA_REVIEW_SKIP_RATE 仍兼容。 */
    private static final String QUIET_WEEK_KEY = "WATCHA_REVIEW_QUIET_WEEK_RATE";
    private static final String REVIEW_SKIP_KEY = "WATCHA_REVIEW_SKIP_RATE";
    /** 提交前随机等待的最大分钟数，抹掉每天同一分钟发帖的痕迹。 */
    private static final String REVIEW_DELAY_KEY = "WATCHA_REVIEW_DELAY_MINUTES";
    /** 试运行：走完整个流程但不提交，只把成稿打进日志，方便先看两周文风对不对。 */
    private static final String REVIEW_DRY_RUN_KEY = "WATCHA_REVIEW_DRY_RUN";
    /** 偶发回评：给别人的猹评接一句话，默认跟着自动猹评一起开。 */
    private static final String REPLY_KEY = "WATCHA_REPLY_REVIEW";
    /** 当天回评的概率。 */
    private static final String REPLY_RATE_KEY = "WATCHA_REPLY_RATE";
    /** 任务开跑时的随机延迟上限（分钟），让每天的签到时间不再固定在同一分钟。 */
    private static final String JITTER_KEY = "WATCHA_JITTER_MINUTES";
    /** 用真浏览器登录并提交。TLS 指纹、会话 cookie、页面埋点三样只有浏览器能给。 */
    private static final String BROWSER_KEY = "WATCHA_BROWSER";
    /** 配图用试用截图，而不是产品封面。需要浏览器模式。 */
    private static final String SHOT_KEY = "WATCHA_REVIEW_SHOT";
    /** 临时图床地址，默认 tmpfiles.org。 */
    private static final String SHOT_HOST_KEY = "WATCHA_SHOT_HOST";
    /** 不写当日推荐产品、改从一个普通产品里挑的概率。 */
    private static final String OFF_QUEST_KEY = "WATCHA_REVIEW_OFF_QUEST_RATE";
    /** 账号人格：short / medium / long。真人风格差异极大，固定一种才像人。 */
    private static final String PERSONA_KEY = "WATCHA_REVIEW_PERSONA";
    /** 强制今天写一条，忽略节奏模型的判断。调试和集成测试用。 */
    private static final String FORCE_KEY = "WATCHA_REVIEW_FORCE";
    /** 回评前的随机等待上限（分钟）。 */
    private static final String REPLY_DELAY_KEY = "WATCHA_REPLY_DELAY_MINUTES";
    /** 猹评开关。站点规范禁止猹评通篇 AI 生成，所以这里只做「列推荐清单」的提醒，不自动提交。 */
    private static final String AUTO_REVIEW_KEY = "WATCHA_AUTO_REVIEW";
    /** 旧键，等价于 WATCHA_AUTO_REVIEW。 */
    private static final String LEGACY_REVIEW_KEY = "WATCHA_REVIEW_REMINDER";
    private static final String GUAZI_NAME = "瓜子";
    private static final String[] QUEST_CODES = {
            "daily.product_review", "weekly.review", "weekly.posting",
            "growth.newbie.v2",
            "milestone.review", "milestone.posting", "milestone.product_star",
            "milestone.user_subscribe", "milestone.like", "milestone.emoji_master"
    };
    /** 有子任务的进度，奖励按子任务单独领取。 */
    private static final String[] CLAIM_CODES = {
            "weekly.review", "weekly.posting",
            "growth.newbie.v2",
            "milestone.review", "milestone.posting", "milestone.product_star",
            "milestone.user_subscribe", "milestone.like", "milestone.emoji_master"
    };
    private static final int WEEKLY_TIERS = 3;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int REQUEST_TIMEOUT = 30000;

    private final Map<String, String> environment;
    private final WatchaPause pause;
    private final WatchaAiCopywriter copywriter;
    /** 浏览器模式下复用同一个页面，登录、浏览、提交都在里面完成。 */
    private WatchaBrowserTransport browserTransport;
    /** 猹评台账：发过什么、还在不在。用来在被折叠时自动停手。 */
    /** HTTP 登录拿到的 refresh_token。 */
    private String httpRefreshToken = "";
    /** 所有落地状态都放在这个目录下，测试可以指到临时目录。 */
    private final File stateDir;
    private final File sessionFile;
    private final WatchaReviewLedger ledger;
    private static final String LEDGER_FILE = "watcha-review-ledger.json";
    private final String baseUrl;
    private final String loginUrl;
    private final String userUrl;
    private final String todayUrl;
    private final String checkInUrl;
    private final String balancesUrl;
    private final String questsUrl;

    public WatchaCheckInTask() {
        this(System.getenv(), DEFAULT_BASE_URL, WatchaPause.human());
    }

    WatchaCheckInTask(Map<String, String> environment, String baseUrl) {
        this(environment, baseUrl, WatchaPause.NONE);
    }

    WatchaCheckInTask(Map<String, String> environment, String baseUrl, WatchaPause pause) {
        this.environment = environment;
        this.pause = pause == null ? WatchaPause.NONE : pause;
        String dir = environment == null ? null : environment.get("WATCHA_STATE_DIR");
        this.stateDir = new File(StrUtil.blankToDefault(dir, "state"));
        this.sessionFile = new File(stateDir, "watcha-session.json");
        this.ledger = WatchaReviewLedger.load(new File(stateDir, LEDGER_FILE));
        this.copywriter = new WatchaAiCopywriter(environment);
        String normalizedBaseUrl = trimTrailingSlash(baseUrl);
        this.baseUrl = normalizedBaseUrl;
        this.loginUrl = normalizedBaseUrl + "/api/v2/auth/signin";
        this.userUrl = normalizedBaseUrl + "/api/v2/users/me";
        this.todayUrl = normalizedBaseUrl + "/api/v2/checkins/today";
        this.checkInUrl = normalizedBaseUrl + "/api/v2/checkins";
        this.balancesUrl = normalizedBaseUrl + "/api/v2/promotions/balances";
        this.questsUrl = normalizedBaseUrl + "/api/v2/quests/progresses/batch-by-codes";
    }

    @Override
    public NotificationSection buildNotificationSection() {
        return new NotificationSection("观猹签到", messageBlocks);
    }

    /**
     * 建立会话。开了 WATCHA_BROWSER 就用真浏览器登录，后续写操作也在浏览器里发；
     * 否则还是走原来的纯 HTTP 通道。
     */
    private String openSession(String username, String password) throws Exception {
        boolean browser = optionEnabled(BROWSER_KEY, new MessageBlock());
        WatchaSession session = WatchaSession.load(sessionFile);
        String reused = reuseSession(session, browser);
        if (StrUtil.isNotBlank(reused)) {
            return reused;
        }
        if (!browser) {
            String token = login(username, password);
            WatchaSession.of(token, httpRefreshToken).save(sessionFile);
            return token;
        }
        System.out.println("[观猹] 使用浏览器模式登录");
        try {
            browserTransport = WatchaBrowserTransport.open(baseUrl, browserProfile());
            String token = browserTransport.login(username, password);
            WatchaSession.of(token, browserTransport.refreshTokenFromLogin()).save(sessionFile);
            // 自检：用同一个浏览器上下文读一次自己的资料。读不到就说明 cookie 或 TLS 这条路没通
            WatchaBrowserTransport.Result probe = browserTransport.get("/api/v2/users/me", token);
            if (!probe.ok()) {
                throw new IllegalStateException("自检失败 " + probe.describe());
            }
            System.out.println("[观猹] 浏览器通道自检：" + (probe.apiData() == null ? "通过"
                    : "通过（" + probe.apiData().getStr("nickname") + "）"));
            return token;
        } catch (Exception e) {
            // 浏览器起不来（没装依赖、下载失败、自检不过）不能把整个签到一起拖死，退回原来的 HTTP 通道
            System.out.println("[观猹] 浏览器通道不可用，回退 HTTP：" + safeMessage(e.getMessage()));
            if (browserTransport != null) {
                browserTransport.close();
                browserTransport = null;
            }
        }
        return login(username, password);
    }

    @Override
    public CheckInTask run() {
        try {
            return doRun();
        } finally {
            if (browserTransport != null) {
                browserTransport.close();
                browserTransport = null;
            }
        }
    }

    private CheckInTask doRun() {
        String username = environment == null ? null : environment.get(USERNAME_KEY);
        String password = environment == null ? null : environment.get(PASSWORD_KEY);
        if (StrUtil.isBlank(username) && StrUtil.isBlank(password)) {
            System.out.println("[观猹] 账号未配置，跳过签到");
            enabled = false;
            return this;
        }
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            System.out.println("[观猹] 用户名或密码配置不完整，跳过签到");
            addMessage(new MessageBlock()
                    .addLine("签到结果：已跳过")
                    .addLine("原因：用户名或密码配置不完整"));
            return this;
        }

        System.out.println("[观猹] 签到任务开始");
        awaitStartJitter();
        String token;
        try {
            token = openSession(username, password);
        } catch (Exception e) {
            String error = safeMessage(e.getMessage());
            System.err.println("[观猹] 签到失败：" + error);
            addMessage(new MessageBlock()
                    .addLine("签到结果：签到失败")
                    .addLine("原因：", error));
            System.out.println("[观猹] 签到任务结束");
            return this;
        }
        // 签到前的余额作为基准，任务全部结束后再读一次，差值就是本次签到和领奖的总收入
        Integer guaziBefore = loadGuazi(token);
        int checkInSlot = messageBlocks.size();
        CheckInReport checkIn = null;
        try {
            checkIn = performCheckIn(token, loadDisplayName(token));
        } catch (Exception e) {
            String error = safeMessage(e.getMessage());
            System.err.println("[观猹] 签到失败：" + error);
            addMessage(new MessageBlock()
                    .addLine("签到结果：签到失败")
                    .addLine("原因：", error));
        }
        completeQuests(token);
        reportGuazi(token, checkIn, checkInSlot, guaziBefore);
        System.out.println("[观猹] 签到任务结束");
        return this;
    }

    private CheckInReport performCheckIn(String token, String displayName) {
        JSONObject today = requestData(authorized(HttpRequest.get(todayUrl), token).execute(), "查询今日签到");
        boolean checkedIn = boolValue(today, "checked_in");
        int streak = numberValue(today, "streak_days");
        if (checkedIn) {
            System.out.println("[观猹] 今日已签到");
            return new CheckInReport(displayName, streak, "今日已签到", null);
        }

        System.out.println("[观猹] 开始签到");
        JSONObject checkInData;
        try {
            checkInData = requestData(authorized(HttpRequest.post(checkInUrl), token)
                    .body("{}")
                    .execute(), "签到");
        } catch (IllegalStateException e) {
            if (safeMessage(e.getMessage()).contains("已签到")) {
                System.out.println("[观猹] 今日已签到");
                return new CheckInReport(displayName, streak, "今日已签到", null);
            }
            throw e;
        }
        if (checkInData.containsKey("streak_days")) {
            streak = numberValue(checkInData, "streak_days");
        }
        Integer reward = checkInData.containsKey("reward_count")
                ? numberValue(checkInData, "reward_count") : null;
        System.out.println("[观猹] 签到结果：签到成功");
        return new CheckInReport(displayName, streak, "签到成功", reward);
    }

    /**
     * 任务全部结束后读取瓜子余额：签到信息插回原来的位置，本次获得瓜子写在最后一行。
     */
    private void reportGuazi(String token, CheckInReport checkIn, int checkInSlot, Integer guaziBefore) {
        Integer guaziAfter = loadGuazi(token);
        if (checkIn != null) {
            MessageBlock message = new MessageBlock();
            if (StrUtil.isNotBlank(checkIn.displayName)) {
                message.addLine("用户名：", sanitize(checkIn.displayName));
            }
            if (guaziAfter != null) {
                message.addLine("瓜子：", String.valueOf(guaziAfter));
            } else if (guaziBefore != null) {
                message.addLine("瓜子：", String.valueOf(guaziBefore), "（签到前，任务后读取失败）");
            }
            message.addLine("连续签到：", String.valueOf(checkIn.streak), " 天");
            message.addLine("签到结果：", checkIn.result, rewardSuffix(checkIn.reward));
            messageBlocks.add(Math.min(checkInSlot, messageBlocks.size()), message);
        }
        int checkInReward = checkIn == null || checkIn.reward == null ? 0 : checkIn.reward;
        Integer total = guaziIncrease(guaziBefore, guaziAfter, checkInReward);
        if (total != null && !messageBlocks.isEmpty()) {
            System.out.println("[观猹] 本次获得瓜子：" + total);
            messageBlocks.get(messageBlocks.size() - 1).addLine("本次获得瓜子：", String.valueOf(total));
        }
    }

    /**
     * 本次获得的瓜子总数。只有签到奖励时签到结果里已经写了，不再重复显示，返回 null。
     */
    static Integer guaziIncrease(Integer before, Integer after, int checkInReward) {
        if (before == null || after == null) {
            return null;
        }
        int increase = after - before;
        return increase > 0 && increase != checkInReward ? increase : null;
    }

    private static String rewardSuffix(Integer reward) {
        return reward != null && reward > 0 ? "，获得 " + reward + " 瓜子" : "";
    }

    /**
     * 任务中心：两个开关互相独立。
     * <p>
     * {@code WATCHA_AUTO_REVIEW} 只在通知里列出当日推荐产品和体验入口，由本人体验后手写猹评——
     * 站点规范禁止通篇 AI 生成，自动提交有折叠和封号风险，所以这条链路不自动发布。
     * {@code WATCHA_PUBLISH_POST} 控制讨论帖，发帖目前没有风控问题，可以单独打开。
     * 旧键 {@code WATCHA_PUBLISH} 作为讨论帖开关的后备值，{@code WATCHA_REVIEW_REMINDER} 作为猹评提醒的后备值。
     */
    private void completeQuests(String token) {
        QuestRun run = new QuestRun();
        reconcileLedger(token, run);
        String postKey = StrUtil.isBlank(environment.get(POST_KEY)) ? LEGACY_PUBLISH_KEY : POST_KEY;
        boolean publishPost = optionEnabled(postKey, run.block);
        boolean publishReview = optionEnabled(PUBLISH_REVIEW_KEY, run.block);
        // 提醒默认开启：只读任务进度、不调 AI、不产生站内内容，是本人手写猹评的唯一入口。
        // 旧键显式配置过就以旧键为准，否则看新键（未配置时取默认值 true）。自动发布打开后不再需要提醒。
        boolean remindReview = !publishReview && (legacyConfigured(LEGACY_REVIEW_KEY)
                ? optionEnabled(LEGACY_REVIEW_KEY, run.block)
                : optionEnabled(AUTO_REVIEW_KEY, run.block, true));
        if (publishPost && !copywriter.isReady()) {
            String reason = copywriter.isPartial() ? "文案接口配置不完整" : "文案接口未配置";
            run.block.addLine("本周帖子：已跳过").addLine("原因：", reason);
            publishPost = false;
        }
        if (publishReview && !copywriter.isReady()) {
            String reason = copywriter.isPartial() ? "文案接口配置不完整" : "文案接口未配置";
            run.block.addLine("今日猹评：已跳过").addLine("原因：", reason);
            publishReview = false;
        }
        if (!publishPost && !remindReview && !publishReview) {
            addMessage(run.block);
            return;
        }

        try {
            JSONObject items = fetchQuests(token);
            if (publishReview) {
                publishReview(items, token, run);
            }
            if (publishReview && optionEnabled(REPLY_KEY, run.block, true)) {
                replyToOthers(token, run);
            }
            if (remindReview) {
                remindReview(items, token, run);
            }
            if (publishPost) {
                if (receiveIfNeeded(items, token)) {
                    items = fetchQuests(token);
                }
                publishPosts(items, token, run);
            }
            // 猹评也会让每日任务变成可领取，所以只要今天写过东西就去收一次奖励，
            // 不再要求必须同时开了发帖（以前只开猹评时奖励会一直挂着没人领）
            if (publishPost || publishReview) {
                claimWithRetry(token, run);
            }
        } catch (Exception e) {
            String error = safeMessage(e.getMessage());
            System.err.println("[观猹] 任务失败：" + error);
            run.block.addLine("任务结果：未完成");
            run.block.addLine("原因：", error);
        }
        addMessage(run.block);
    }

    private boolean optionEnabled(String key, MessageBlock block) {
        return optionEnabled(key, block, false);
    }

    /** 未配置时取 defaultValue；非法值在通知里提示并回落到 defaultValue。 */
    private boolean optionEnabled(String key, MessageBlock block, boolean defaultValue) {
        String value = StrUtil.trimToEmpty(environment.get(key));
        if (value.isEmpty()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if (!"false".equalsIgnoreCase(value) && !"0".equals(value)) {
            block.addLine("配置有误：", key, " 仅支持 true、false、1 或 0");
        }
        return false;
    }

    /** 这个键是否被用户显式配置过（空白不算）。 */
    private boolean legacyConfigured(String key) {
        return !StrUtil.trimToEmpty(environment.get(key)).isEmpty();
    }

    private JSONObject fetchQuests(String token) {
        JSONArray codes = new JSONArray();
        for (String code : QUEST_CODES) {
            codes.add(code);
        }
        JSONObject data = requestData(authorized(HttpRequest.post(questsUrl), token)
                .body(JSONUtil.createObj().set("codes", codes).toString())
                .execute(), "读取任务进度");
        JSONObject items = data.getJSONObject("items");
        if (items == null) {
            throw new IllegalStateException("读取任务进度失败：没有任务数据");
        }
        return items;
    }

    private boolean receiveIfNeeded(JSONObject items, String token) {
        boolean received = false;
        for (String code : QUEST_CODES) {
            JSONObject quest = items.getJSONObject(code);
            if (quest == null || !"receivable".equals(quest.getStr("status"))) {
                continue;
            }
            long questId = longValue(quest, "quest_id");
            if (questId <= 0) {
                continue;
            }
            pause.quick();
            requestData(authorized(HttpRequest.post(questUrl(questId, "/receive")), token)
                    .body("{}")
                    .execute(), "接取任务");
            received = true;
        }
        return received;
    }

    private void claimAll(JSONObject items, String token, QuestRun run) {
        if (items == null) {
            return;
        }
        claimQuest(items.getJSONObject("daily.product_review"), token, run);
        for (String code : CLAIM_CODES) {
            claimItems(code, items.getJSONObject(code), token, run);
        }
    }

    /**
     * 取一次进度并领取。发布过的账号再多取一次，服务端进度刷新有延迟；
     * 首轮领到别的奖励不代表刚发布的进度已刷新，所以不能据此跳过补领。
     */
    private void claimWithRetry(String token, QuestRun run) {
        JSONObject items = fetchQuests(token);
        claimAll(items, token, run);
        if (!run.published) {
            return;
        }
        try {
            pause.betweenActions();
            items = fetchQuests(token);
        } catch (RuntimeException e) {
            if (isCaptcha(e)) {
                throw e;
            }
            run.block.addLine("补领奖励失败");
            run.block.addLine("原因：", safeMessage(e.getMessage()));
            return;
        }
        claimAll(items, token, run);
    }

    private void claimQuest(JSONObject quest, String token, QuestRun run) {
        if (quest == null || !"claimable".equals(quest.getStr("status"))) {
            return;
        }
        long questId = longValue(quest, "quest_id");
        if (questId <= 0) {
            return;
        }
        String name = StrUtil.blankToDefault(quest.getStr("name"), "任务奖励");
        pause.quick();
        try {
            requestData(authorized(HttpRequest.post(questUrl(questId, "/claim-reward")), token)
                    .body(claimBody(quest).toString())
                    .execute(), "领取奖励");
            run.block.addLine("领取奖励：", sanitize(name), rewardSuffix(guaziReward(quest.get("reward"))));
        } catch (RuntimeException e) {
            if (isCaptcha(e)) {
                throw e;
            }
            run.block.addLine("领取奖励失败：", sanitize(name));
            run.block.addLine("原因：", safeMessage(e.getMessage()));
        }
    }

    private void claimItems(String code, JSONObject quest, String token, QuestRun run) {
        if (quest == null) {
            return;
        }
        JSONArray items = quest.getJSONArray("completion_items");
        if (items == null) {
            return;
        }
        long questId = longValue(quest, "quest_id");
        String questName = quest.getStr("name");
        for (int index = 0; index < items.size(); index++) {
            JSONObject item = items.getJSONObject(index);
            if (item == null || !"claimable".equals(item.getStr("status"))) {
                continue;
            }
            String itemId = item.getStr("item_id");
            if (questId <= 0 || StrUtil.isBlank(itemId)) {
                continue;
            }
            String title = StrUtil.blankToDefault(item.getStr("title"), itemId);
            pause.quick();
            try {
                String path = "/completion-items/" + encode(itemId) + "/claim-reward";
                requestData(authorized(HttpRequest.post(questUrl(questId, path)), token)
                        .body(claimBody(quest).toString())
                        .execute(), "领取奖励");
                run.block.addLine("领取奖励：", sanitize(claimLabel(code, questName, item, items.size())),
                        rewardSuffix(guaziReward(item.get("reward"))));
            } catch (RuntimeException e) {
                if (isCaptcha(e)) {
                    throw e;
                }
                run.block.addLine("领取奖励失败：", sanitize(title));
                run.block.addLine("原因：", safeMessage(e.getMessage()));
            }
        }
    }

    /**
     * 子任务的展示名。每周任务的标题是「发布 N 篇猹评」，读起来像动作，改成「每周猹评 N/3 篇」；
     * 里程碑附上描述说明累计篇数；其他子任务前面带上任务名。
     */
    static String claimLabel(String code, String questName, JSONObject item, int tierCount) {
        String title = StrUtil.blankToDefault(item.getStr("title"), StrUtil.nullToEmpty(item.getStr("item_id"))).trim();
        String name = StrUtil.trimToEmpty(questName);
        String quest = StrUtil.nullToEmpty(code);
        int target = numberValue(item.getJSONObject("progress"), "target");
        if (quest.startsWith("weekly.") && !name.isEmpty() && target > 0 && tierCount > 0) {
            return name + " " + target + "/" + tierCount + " 篇";
        }
        if (quest.startsWith("milestone.")) {
            Object description = item.get("description");
            String text = description instanceof CharSequence ? description.toString().trim() : "";
            return text.isEmpty() ? title : title + "（" + text + "）";
        }
        if (name.isEmpty() || title.startsWith(name)) {
            return title;
        }
        return name + " · " + title;
    }

    /**
     * 奖励里的瓜子数量，结构为 {"items":[{"promotion_name":"瓜子","count":30}]}，和网页的计算方式一致。
     */
    static int guaziReward(Object reward) {
        if (!(reward instanceof JSONObject)) {
            return 0;
        }
        JSONArray items = ((JSONObject) reward).getJSONArray("items");
        if (items == null) {
            return 0;
        }
        int total = 0;
        for (int index = 0; index < items.size(); index++) {
            JSONObject item = items.getJSONObject(index);
            if (item != null && GUAZI_NAME.equals(item.getStr("promotion_name"))) {
                total += Math.max(0, numberValue(item, "count"));
            }
        }
        return total;
    }

    private void remindReview(JSONObject items, String token, QuestRun run) {
        JSONObject daily = items.getJSONObject("daily.product_review");
        if (daily == null || isClosed(daily.getStr("status"))) {
            run.block.addLine("今日猹评：任务暂不可用");
            return;
        }
        if (reviewFinished(daily)) {
            run.block.addLine("今日猹评：今日已完成");
            JSONObject weekly = items.getJSONObject("weekly.review");
            if (weekly != null) {
                run.block.addLine("本周猹评：", numberValue(weekly.getJSONObject("state"), "count") + "/" + WEEKLY_TIERS);
            }
            return;
        }
        Set<Integer> ids = recommendedIds(daily);
        if (ids.isEmpty()) {
            run.block.addLine("今日猹评：到任务中心查看推荐 ", baseUrl, "/quest");
            return;
        }
        run.block.addLine("今日猹评：体验后可在产品页填写");
        for (int id : ids) {
            String name = "推荐产品 #" + id;
            try {
                name = loadProduct(token, id, run).name;
            } catch (RuntimeException e) {
                if (isCaptcha(e)) {
                    throw e;
                }
                System.err.println("[观猹] 无法读取推荐产品 #" + id + " 的名称：" + safeMessage(e.getMessage()));
            }
            run.block.addLine(sanitize(name), " ", baseUrl, "/products/", String.valueOf(id));
        }
    }

    /**
     * 自动猹评。三步：挑一个还没评过的推荐产品 → 真打开一次官网留下事实 → 按当天抽到的形状成稿并提交。
     * 形状（几段、多长、要不要小标题和引用、配不配图、投什么态度、延迟几分钟）来自 {@link WatchaReviewPlan}，
     * 参数按站点真实猹评的分布抽样，目的是别让每条都长成同一个模子。
     */
    private void publishReview(JSONObject items, String token, QuestRun run) {
        JSONObject daily = items.getJSONObject("daily.product_review");
        if (daily == null || isClosed(daily.getStr("status"))) {
            run.block.addLine("今日猹评：任务暂不可用");
            return;
        }
        if (reviewFinished(daily)) {
            run.block.addLine("今日猹评：今日已完成");
            return;
        }
        if (ledger.paused(LocalDate.now(SHANGHAI))) {
            run.block.addLine("今日猹评：已自动停写（累计 " + ledger.strikes() + " 条不见了）");
            run.block.addLine("建议：", "人工看一眼账号，确认后清空 state/watcha-review-ledger.json 再恢复");
            return;
        }
        LoadedProduct target = null;
        Random pick = new Random(WatchaReviewPlan.mix(WatchaReviewPlan.seed(LocalDate.now(SHANGHAI), 733)));
        if (pick.nextDouble() < doubleOption(OFF_QUEST_KEY, 0.15)) {
            // 真人不是每天刚好写推荐位那个产品，偶尔评一个自己顺手用到的
            target = randomReviewedProduct(token, run);
        }
        boolean fromQuest = target != null;
        if (target == null) {
            for (int id : recommendedIds(daily)) {
                LoadedProduct product = loadProduct(token, id, run);
                pause.quick();
                if (!reviewedByMe(token, id)) {
                    target = product;
                    break;
                }
            }
        }
        if (target == null) {
            run.block.addLine("今日猹评：推荐产品都已评过");
            return;
        }
        if (!fromQuest) {
            run.block.addLine("今日猹评：写的是普通产品（今日任务奖励不领）");
        }
        WatchaReviewPlan.Persona persona = WatchaReviewPlan.Persona.of(environment.get(PERSONA_KEY));
        WatchaReviewPlan plan = optionEnabled(FORCE_KEY, new MessageBlock())
                ? WatchaReviewPlan.planForced(LocalDate.now(SHANGHAI), target.id,
                        intOption(REVIEW_DELAY_KEY, 25), persona)
                : WatchaReviewPlan.plan(LocalDate.now(SHANGHAI), target.id, quietWeekRate(),
                        intOption(REVIEW_DELAY_KEY, 25), persona, accountWeekSeed());
        if (plan.skip) {
            run.block.addLine("今日猹评：今天不写");
            System.out.println("[观猹] 今日猹评跳过");
            return;
        }
        System.out.println("[观猹] 猹评计划：" + plan + "，产品 " + sanitize(target.name));
        // 浏览器模式下一次访问就够：页面事实和截图一起拿；纯 HTTP 模式才退回单抓页面
        WatchaTrialProbe.Record trial;
        File shot = null;
        if (browserTransport != null) {
            File target1 = new File(trialDir(), "shot-" + target.id + "-" + System.currentTimeMillis() + ".png");
            WatchaBrowserTransport.TrialCapture capture = browserTransport.captureTrial(target.websiteUrl,
                    optionEnabled(SHOT_KEY, new MessageBlock()) ? target1 : null);
            if (capture == null) {
                trial = WatchaTrialProbe.probe(target.websiteUrl);
                System.out.println("[观猹] 浏览器采集失败，退回 HTTP 抓取");
            } else {
                trial = capture.record;
                shot = capture.screenshot;
            }
        } else {
            trial = WatchaTrialProbe.probe(target.websiteUrl);
        }
        if (!trial.ok) {
            System.out.println("[观猹] 官网页面没取到：" + trial.failure);
        }
        pause.betweenActions();
        WatchaAiCopywriter.DraftResult draft = copywriter.writeReview(
                target.name, target.slogan, target.categories, target.description, trial.promptText(), plan);
        if (!draft.ok()) {
            run.block.addLine("今日猹评：已跳过");
            run.block.addLine("原因：", safeMessage(draft.failure));
            return;
        }
        // 配图：能截到试用图就用试用图，截不到再退回产品封面。两种都先镜像成站内图再放进去。
        String images = "";
        if (plan.image) {
            // 优先级：本次试用截图 → 产品官网首图 → 平台封面
            images = uploadScreenshot(shot, token, target);
            if (images.isEmpty() && trial.ok && StrUtil.isNotBlank(trial.imageUrl)) {
                images = mirrorImage(token, trial.imageUrl, target.id);
            }
            if (images.isEmpty() && StrUtil.isNotBlank(target.imageUrl)) {
                images = mirrorImage(token, target.imageUrl, target.id);
            }
        }
        JSONObject body = JSONUtil.createObj()
                .set("content", reviewDocument(draft.title, draft.paragraphs, plan, target.name))
                .set("vote_value", draft.voteValue)
                .set("images", images)
                .set("append", false);
        if (optionEnabled(REVIEW_DRY_RUN_KEY, run.block)) {
            System.out.println("[观猹] 试运行，不提交：" + body);
            run.block.addLine("今日猹评：试运行未提交 ", sanitize(target.name), "（", plan.toString(), "）");
            return;
        }
        awaitReviewDelay(plan.delayMinutes);
        postData("/api/v2/products/" + target.id + "/reviews", body, token,
                "/products/" + target.id, "发布猹评");
        run.published = true;
        run.reviewedProductId = target.id;
        long createdId = myReviewId(token, target.id);
        if (createdId > 0) {
            ledger.record(createdId, target.id, java.time.Instant.now().toString());
            ledger.save(new File(stateDir, LEDGER_FILE));
        } else {
            System.out.println("[观猹] 猹评提交成功，但列表里还没查到（服务端可能有延迟）");
        }
        run.trialSummary = trial.ok ? trial.summary : "";
        run.block.addLine("今日猹评：已发布 ", sanitize(target.name), "（", plan.toString(), "）");
        System.out.println("[观猹] 已发布猹评：" + sanitize(target.name) + "（" + plan + "）");
    }

    /**
     * 偶发回评。只在今天评过的产品下面找目标，理由和猹评一样：刚用过的人才可能顺手接一句。
     * 目标条件收紧到「近 10 天、有实质内容、我没回过」，避免回评本身变成可复用的模板。
     */
    private void replyToOthers(String token, QuestRun run) {
        double rate = doubleOption(REPLY_RATE_KEY, 0.3);
        Random random = new Random(WatchaReviewPlan.mix(WatchaReviewPlan.seed(LocalDate.now(SHANGHAI), 991)));
        if (random.nextDouble() > rate) {
            return;
        }
        int productId = run.reviewedProductId;
        if (productId <= 0) {
            return;
        }
        String myId = loadUserId(token);
        if (StrUtil.isBlank(myId)) {
            return;
        }
        LoadedProduct product = run.products.get(productId);
        ReplyTarget target = pickReplyTarget(token, productId, myId, product == null ? "这款产品" : product.name);
        if (target == null) {
            run.block.addLine("回评：今天没找到合适的目标");
            return;
        }
        pause.betweenActions();
        WatchaAiCopywriter.DraftResult draft = copywriter.writeReply(
                target.text, target.productName, run.trialSummary);
        if (!draft.ok()) {
            run.block.addLine("回评：已跳过");
            run.block.addLine("原因：", safeMessage(draft.failure));
            return;
        }
        awaitReviewDelay(intOption(REPLY_DELAY_KEY, 8));
        if (optionEnabled(REVIEW_DRY_RUN_KEY, run.block)) {
            System.out.println("[观猹] 回评试运行，不提交：" + draft.paragraphs.get(0));
            run.block.addLine("回评：试运行未提交 ", sanitize(target.author));
            return;
        }
        // 服务端存的回复正文是 doc 的 JSON 字符串，和猹评接口收对象不同
        String content = replyDocument(draft.paragraphs.get(0)).toString();
        JSONObject body = JSONUtil.createObj().set("content", content).set("images", "");
        postData("/api/v2/reviews/" + target.id + "/replies", body, token,
                "/products/" + productId, "回复猹评");
        run.block.addLine("回评：已回复 ", sanitize(target.author), " 的猹评");
        System.out.println("[观猹] 已回评：" + sanitize(target.author) + " - " + draft.paragraphs.get(0));
    }

    /**
     * 提交类请求：浏览器模式先在对应页面停一下再在页面里发，页面浏览、cookie、TLS 一起解决；
     * 否则还是原来的纯 HTTP 通道。
     */
    private void postData(String path, JSONObject body, String token, String refererPath, String action) {
        if (browserTransport != null) {
            try {
                browserTransport.visit(refererPath);
                WatchaBrowserTransport.Result result = browserTransport.post(path, body, token);
                if (!result.ok()) {
                    throw new IllegalStateException(action + "失败：" + result.describe());
                }
                return;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(action + "失败：" + safeMessage(e.getMessage()));
            }
        }
        requestData(authorized(HttpRequest.post(baseUrl + path), token, refererPath)
                .body(body.toString())
                .execute(), action);
    }

    /** 回评只有一句正文，拼一份最朴素的文档。 */
    static JSONObject replyDocument(String text) {
        JSONArray blocks = new JSONArray();
        blocks.add(JSONUtil.createObj().set("type", "paragraph").set("content", inline(sanitizeText(text))));
        return JSONUtil.createObj().set("type", "doc").set("content", blocks);
    }

    private ReplyTarget pickReplyTarget(String token, int productId, String myId, String productName) {
        JSONObject data;
        try {
            data = requestData(authorized(HttpRequest.get(
                    baseUrl + "/api/v2/products/" + productId + "/reviews?limit=20"), token,
                    "/products/" + productId).execute(), "读取产品猹评");
        } catch (RuntimeException e) {
            if (isCaptcha(e)) {
                throw e;
            }
            System.out.println("[观猹] 读取猹评失败：" + safeMessage(e.getMessage()));
            return null;
        }
        JSONArray items = data.getJSONArray("items");
        if (items == null) {
            return null;
        }
        List<ReplyTarget> candidates = new ArrayList<ReplyTarget>();
        LocalDate limit = LocalDate.now(SHANGHAI).minusDays(10);
        for (int index = 0; index < items.size(); index++) {
            JSONObject item = items.getJSONObject(index);
            if (item == null || boolValue(item, "is_deleted")) {
                continue;
            }
            String authorId = String.valueOf(item.get("user_id"));
            if (myId.equals(authorId) || numberValue(item, "reply_count") > 20) {
                continue;
            }
            String text = WatchaAiCopywriter.plainText(item.get("content"));
            String cleaned = StrUtil.trimToEmpty(text);
            if (cleaned.length() < 40 || cleaned.length() > 800 || !containsChinese(cleaned)) {
                continue;
            }
            if (!after(item.getStr("create_at"), limit)) {
                continue;
            }
            JSONObject user = item.getJSONObject("user");
            String author = user == null ? "有人" : StrUtil.blankToDefault(user.getStr("nickname"), "有人");
            candidates.add(new ReplyTarget(longValue(item, "id"), cleaned, author,
                    StrUtil.blankToDefault(productName, "这款产品")));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        long seed = WatchaReviewPlan.mix(productId * 31L + LocalDate.now(SHANGHAI).toEpochDay());
        Collections.shuffle(candidates, new Random(seed));
        for (ReplyTarget candidate : candidates) {
            if (!alreadyReplied(token, candidate.id, myId)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean alreadyReplied(String token, long reviewId, String myId) {
        try {
            JSONObject data = requestData(authorized(HttpRequest.get(
                    baseUrl + "/api/v2/reviews/" + reviewId + "/replies?limit=50"), token)
                    .execute(), "读取猹评回复");
            JSONArray items = data.getJSONArray("items");
            if (items == null) {
                return false;
            }
            for (int index = 0; index < items.size(); index++) {
                JSONObject item = items.getJSONObject(index);
                if (item != null && myId.equals(String.valueOf(item.get("user_id")))) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            if (isCaptcha(e)) {
                throw e;
            }
            return true;
        }
    }

    private static boolean containsChinese(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (Character.UnicodeScript.of(text.charAt(index)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private static boolean after(String createdAt, LocalDate limit) {
        if (StrUtil.isBlank(createdAt)) {
            return false;
        }
        try {
            return !Instant.parse(createdAt.trim()).atZone(SHANGHAI).toLocalDate().isBefore(limit);
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    /** 开跑时间也抖动一下。固定在同一个分钟开始，是比文案更容易被抓的规律。 */
    private void awaitStartJitter() {
        int fallback = publishesSomething() ? 20 : 0;
        pause.startJitter(intOption(JITTER_KEY, fallback));
    }

    private boolean publishesSomething() {
        return optionEnabled(PUBLISH_REVIEW_KEY, new MessageBlock()) || optionEnabled(POST_KEY, new MessageBlock());
    }

    /** 试用截图的落地目录，顺手建好。 */
    private File trialDir() {
        File dir = new File(stateDir, "trials");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            System.out.println("[观猹] 截图目录建不出来：" + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * 把这次采集到的截图直接传进观猹自己的对象存储（不需要任何外部图床）。
     * 失败返回空，外层退回官网首图或产品封面。
     */
    private String uploadScreenshot(File captured, String token, LoadedProduct product) {
        if (captured == null || !optionEnabled(SHOT_KEY, new MessageBlock())) {
            return "";
        }
        try {
            // 优先直传站内存储；配了外部图床且直传失败时才走图床 + 镜像那条老路
            String hosted = WatchaImageUploader.upload(captured, baseUrl, token);
            if (StrUtil.isNotBlank(hosted)) {
                System.out.println("[观猹] 试用截图已上传到站内：" + hosted);
                return hosted;
            }
            String publicUrl = WatchaImageHost.upload(captured, environment.get(SHOT_HOST_KEY));
            if (StrUtil.isBlank(publicUrl)) {
                return "";
            }
            System.out.println("[观猹] 试用截图已上传到外部图床：" + publicUrl);
            return mirrorImage(token, publicUrl, product.id);
        } finally {
            if (!captured.delete()) {
                System.out.println("[观猹] 临时截图没删掉：" + captured.getAbsolutePath());
            }
        }
    }

    /**
     * 把外链图镜像成站内图。前端贴图走的就是这个接口（GET + url 查询参数），
     * 拿回来的地址在 tos.watcha.cn 上，和真实用户上传的图同源。失败就不配图。
     */
    private String mirrorImage(String token, String imageUrl, int productId) {
        try {
            JSONObject data = requestData(authorized(HttpRequest.get(baseUrl + "/api/v2/infra/external-resources/image")
                    .form("url", imageUrl), token, "/products/" + productId)
                    .execute(), "镜像配图");
            String url = data.getStr("url");
            return StrUtil.isBlank(url) ? "" : url;
        } catch (Exception e) {
            System.out.println("[观猹] 配图镜像失败，这条不配图：" + safeMessage(e.getMessage()));
            return "";
        }
    }

    /** 提交前随机等一会儿，抹掉「每天同一分钟发帖」这条最显眼的机器痕迹。 */
    private void awaitReviewDelay(int minutes) {
        if (minutes <= 0) {
            return;
        }
        System.out.println("[观猹] 猹评提交前等待 " + minutes + " 分钟");
        CheckInTask.sleep(minutes * 60000L);
    }

    /**
     * 回查台账里记过的猹评还在不在。少一条就记一次违规，够两次就自动停写。
     * 只读，不改站内任何东西。
     */
    private void reconcileLedger(String token, QuestRun run) {
        if (ledger.publishedCount() == 0) {
            return;
        }
        Set<Long> alive = new HashSet<Long>();
        try {
            String userId = loadUserId(token);
            if (StrUtil.isBlank(userId)) {
                return;
            }
            JSONObject data = requestData(authorized(HttpRequest.get(
                    baseUrl + "/api/v2/users/" + encode(userId) + "/reviews?limit=50"), token)
                    .execute(), "读取我的猹评");
            JSONArray items = data.getJSONArray("items");
            if (items != null) {
                for (int index = 0; index < items.size(); index++) {
                    JSONObject item = items.getJSONObject(index);
                    if (item != null && !boolValue(item, "is_deleted")) {
                        alive.add(longValue(item, "id"));
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[观猹] 猹评对账失败，跳过：" + safeMessage(e.getMessage()));
            return;
        }
        int lost = ledger.reconcile(alive);
        ledger.save(new File(stateDir, LEDGER_FILE));
        if (lost > 0) {
            run.block.addLine("猹评对账：", String.valueOf(lost), " 条不见了（累计 ", String.valueOf(ledger.strikes()), " 次）");
        }
        if (ledger.paused(LocalDate.now(SHANGHAI))) {
            run.block.addLine("已自动停写猹评，停到 ", ledger.pausedUntilText(LocalDate.now(SHANGHAI)));
        }
    }

    /**
     * 从站点产品列表里随机挑一个我还没评过、且有官网的。
     * 目的是别让「每天都写当天推荐位那个产品」成为规律。
     */
    private LoadedProduct randomReviewedProduct(String token, QuestRun run) {
        try {
            JSONObject data = requestData(authorized(HttpRequest.get(baseUrl + "/api/v2/products?limit=20"), token)
                    .execute(), "读取产品列表");
            JSONArray items = data.getJSONArray("items");
            if (items == null || items.isEmpty()) {
                return null;
            }
            List<Integer> ids = new ArrayList<Integer>();
            for (int index = 0; index < items.size(); index++) {
                JSONObject item = items.getJSONObject(index);
                if (item != null && longValue(item, "id") > 0 && StrUtil.isNotBlank(item.getStr("website_url"))) {
                    ids.add(numberValue(item, "id"));
                }
            }
            if (ids.isEmpty()) {
                return null;
            }
            Random random = new Random(WatchaReviewPlan.mix(WatchaReviewPlan.seed(LocalDate.now(SHANGHAI), 997)));
            Collections.shuffle(ids, random);
            for (int id : ids.subList(0, Math.min(5, ids.size()))) {
                pause.quick();
                if (!reviewedByMe(token, id)) {
                    return loadProduct(token, id, run);
                }
            }
            return null;
        } catch (Exception e) {
            System.out.println("[观猹] 挑普通产品失败：" + safeMessage(e.getMessage()));
            return null;
        }
    }

    /** 我在这个产品下的猹评 id；没有或读不到返回 0。 */
    private long myReviewId(String token, int productId) {
        try {
            JSONObject data = requestData(authorized(HttpRequest.get(
                    baseUrl + "/api/v2/products/" + productId + "/reviews/user"), token,
                    "/products/" + productId).execute(), "读取我的猹评");
            JSONArray reviews = data.getJSONArray("reviews");
            return reviews == null || reviews.isEmpty() ? 0 : longValue(reviews.getJSONObject(0), "id");
        } catch (Exception e) {
            return 0;
        }
    }

    /** 我在这个产品下有没有猹评。读不到时按「没评过」处理，让后续去评，评重了服务端会拦。 */
    private boolean reviewedByMe(String token, int productId) {
        try {
            JSONObject data = requestData(authorized(HttpRequest.get(
                    baseUrl + "/api/v2/products/" + productId + "/reviews/user"), token)
                    .execute(), "读取我的猹评");
            JSONArray reviews = data.getJSONArray("reviews");
            return reviews != null && !reviews.isEmpty();
        } catch (RuntimeException e) {
            if (isCaptcha(e)) {
                throw e;
            }
            System.out.println("[观猹] 无法确认产品 #" + productId + " 是否评过：" + safeMessage(e.getMessage()));
            return false;
        }
    }

    /**
     * 按计划拼 Tiptap 文档：小标题、引用块、首段加粗产品名，段数由计划决定。
     */
    static JSONObject reviewDocument(String heading, List<String> paragraphs, WatchaReviewPlan plan,
                                     String productName) {
        JSONArray blocks = new JSONArray();
        if (plan.heading && StrUtil.isNotBlank(heading)) {
            blocks.add(JSONUtil.createObj()
                    .set("type", "heading")
                    .set("attrs", JSONUtil.createObj().set("level", 2))
                    .set("content", inline(sanitizeText(heading))));
        }
        int last = paragraphs.size() - 1;
        for (int index = 0; index <= last; index++) {
            String text = sanitizeText(paragraphs.get(index));
            JSONObject paragraph = plan.bold && index == 0 && StrUtil.isNotBlank(productName)
                    ? paragraphWithBold(text, productName)
                    : JSONUtil.createObj().set("type", "paragraph").set("content", inline(text));
            if (plan.quote && index == last && last > 0) {
                JSONArray content = new JSONArray();
                content.add(paragraph);
                blocks.add(JSONUtil.createObj().set("type", "blockquote").set("content", content));
            } else {
                blocks.add(paragraph);
            }
        }
        return JSONUtil.createObj().set("type", "doc").set("content", blocks);
    }

    private static JSONArray inline(String text) {
        JSONArray content = new JSONArray();
        content.add(JSONUtil.createObj().set("type", "text").set("text", text));
        return content;
    }

    /** 首段里把产品名标成加粗，和真人偶尔强调一下某个词一样。 */
    static JSONObject paragraphWithBold(String text, String bold) {
        int index = text.indexOf(bold);
        if (index < 0) {
            return JSONUtil.createObj().set("type", "paragraph").set("content", inline(text));
        }
        JSONArray content = new JSONArray();
        if (index > 0) {
            content.add(JSONUtil.createObj().set("type", "text").set("text", text.substring(0, index)));
        }
        JSONArray marks = new JSONArray();
        marks.add(JSONUtil.createObj().set("type", "bold"));
        content.add(JSONUtil.createObj().set("type", "text").set("marks", marks).set("text", bold));
        int end = index + bold.length();
        if (end < text.length()) {
            content.add(JSONUtil.createObj().set("type", "text").set("text", text.substring(end)));
        }
        return JSONUtil.createObj().set("type", "paragraph").set("content", content);
    }

    private static String sanitizeText(String text) {
        return StrUtil.nullToEmpty(text).replace('\r', ' ').replace('\n', ' ').trim();
    }

    /**
     * 先复用会话，能不登录就不登录。
     * 顺序：现成的 access_token 还能用 → 用 refresh_token 换一个 → 都不行返回空，交给调用方登录。
     */
    private String reuseSession(WatchaSession session, boolean browser) {
        if (!session.hasAccess() && !session.hasRefresh()) {
            return "";
        }
        String age = session.ageHours() >= 0 ? session.ageHours() + " 小时前" : "未知时间";
        if (session.hasAccess() && tokenAlive(session.accessToken())) {
            System.out.println("[观猹] 复用会话（" + age + "保存），不重新登录");
            return attachBrowser(session);
        }
        if (session.hasRefresh()) {
            String fresh = refreshAccessToken(session.refreshToken());
            if (StrUtil.isNotBlank(fresh)) {
                WatchaSession.of(fresh, session.refreshToken()).save(sessionFile);
                System.out.println("[观猹] 用 refresh_token 换了新令牌，不重新登录");
                return attachBrowser(WatchaSession.of(fresh, session.refreshToken()));
            }
        }
        System.out.println("[观猹] 会话已失效，需要重新登录");
        return "";
    }

    /** 浏览器模式下把缓存令牌灌进 localStorage，页面就是已登录状态。 */
    private String attachBrowser(WatchaSession session) {
        if (!optionEnabled(BROWSER_KEY, new MessageBlock())) {
            return session.accessToken();
        }
        try {
            browserTransport = WatchaBrowserTransport.open(baseUrl, browserProfile());
            browserTransport.injectTokens(session.accessToken(), session.refreshToken());
            // 复用会话时也要自检一次：注入成功不代表浏览器里真的能读到数据
            WatchaBrowserTransport.Result probe = browserTransport.get("/api/v2/users/me", session.accessToken());
            System.out.println("[观猹] 浏览器通道自检：" + (probe.ok() ? "通过" : "失败 " + probe.describe()));
            if (!probe.ok()) {
                browserTransport.close();
                browserTransport = null;
            }
            return session.accessToken();
        } catch (Exception e) {
            System.out.println("[观猹] 浏览器启动失败，回退 HTTP：" + safeMessage(e.getMessage()));
            browserTransport = null;
            return session.accessToken();
        }
    }

    /** 令牌还有效吗：能读到自己资料就算有效。 */
    private boolean tokenAlive(String token) {
        try {
            requestData(authorized(HttpRequest.get(userUrl), token).execute(), "校验令牌");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用 refresh_token 换新令牌。 */
    private String refreshAccessToken(String refreshToken) {
        try {
            JSONObject data = requestData(HttpRequest.post(baseUrl + "/api/v2/auth/access-token")
                    .headerMap(headers(), true)
                    .disableCookie()
                    .body(JSONUtil.createObj().set("refresh_token", refreshToken).toString())
                    .timeout(REQUEST_TIMEOUT)
                    .execute(), "刷新令牌");
            return StrUtil.blankToDefault(data.getStr("access_token"), "");
        } catch (Exception e) {
            System.out.println("[观猹] 刷新令牌失败：" + safeMessage(e.getMessage()));
            return "";
        }
    }

    /** 浏览器资料目录放在 state 下，容器重建也保留。 */
    private String browserProfile() {
        return new File(stateDir, "watcha-browser").getPath();
    }

    /** 账号自己的节奏偏移，避免多个账号同一天发同一天歇。 */
    private int accountWeekSeed() {
        String account = environment == null ? "" : StrUtil.nullToEmpty(environment.get(USERNAME_KEY));
        return Math.abs(account.hashCode()) % 997;
    }

    /** 静默周概率：先看新键，再兼容旧的 SKIP_RATE。 */
    private double quietWeekRate() {
        if (StrUtil.isNotBlank(environment.get(QUIET_WEEK_KEY))) {
            return doubleOption(QUIET_WEEK_KEY, 0.3);
        }
        return doubleOption(REVIEW_SKIP_KEY, 0.3);
    }

    private double doubleOption(String key, double fallback) {
        String value = StrUtil.trimToEmpty(environment.get(key));
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value);
            return parsed < 0 ? 0 : (parsed > 1 ? 1 : parsed);
        } catch (NumberFormatException e) {
            System.out.println("[观猹] " + key + " 不是数字，按默认值 " + fallback + " 处理");
            return fallback;
        }
    }

    private int intOption(String key, int fallback) {
        String value = StrUtil.trimToEmpty(environment.get(key));
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            System.out.println("[观猹] " + key + " 不是数字，按默认值 " + fallback + " 处理");
            return fallback;
        }
    }

    private void publishPosts(JSONObject items, String token, QuestRun run) {
        JSONObject quest = items.getJSONObject("weekly.posting");
        if (quest == null) {
            run.block.addLine("本周帖子：已跳过");
            run.block.addLine("原因：任务进度里没有每周发帖");
            return;
        }
        if (isClosed(quest.getStr("status"))) {
            run.block.addLine("本周帖子：已跳过");
            run.block.addLine("原因：本周发帖暂不可用");
            return;
        }
        int count = numberValue(quest.getJSONObject("state"), "count");
        if (count >= WEEKLY_TIERS) {
            run.block.addLine("本周帖子：", WEEKLY_TIERS + "/" + WEEKLY_TIERS);
            return;
        }
        if (postedToday(token)) {
            run.block.addLine("本周帖子：今日已发过");
            run.block.addLine("本周帖子：", count + "/" + WEEKLY_TIERS);
            return;
        }
        Set<Integer> ids = recommendedIds(items.getJSONObject("daily.product_review"));
        LoadedProduct target = null;
        for (int id : ids) {
            LoadedProduct product = loadProduct(token, id, run);
            if (WatchaAiCopywriter.findAnchors(product.description).size() >= 2) {
                target = product;
                break;
            }
        }
        if (target == null) {
            run.block.addLine("本周帖子：已跳过");
            run.block.addLine("原因：", ids.isEmpty() ? "没有今日推荐" : "推荐产品介绍太短");
            return;
        }
        pause.betweenActions();
        WatchaAiCopywriter.DraftResult draft = copywriter.writePost(
                target.name, target.slogan, target.categories, target.description, count);
        if (!draft.ok()) {
            run.block.addLine("本周帖子：已跳过");
            run.block.addLine("原因：", safeMessage(draft.failure));
            return;
        }
        JSONObject body = JSONUtil.createObj()
                .set("product_id", target.id)
                .set("topic_ids", new JSONArray())
                .set("title", draft.title)
                .set("content", document(draft.paragraphs))
                .set("images", "");
        requestData(authorized(HttpRequest.post(baseUrl + "/api/v2/discuss/posts"), token)
                .body(body.toString())
                .execute(), "发布帖子");
        run.published = true;
        run.block.addLine("本周帖子：已发布 ", sanitize(draft.title));
        System.out.println("[观猹] 已发布帖子：" + sanitize(draft.title));
    }

    /**
     * 读取本人已发布的帖子，判断今天（上海时区）是否已经发过。读不到时返回 true，宁可少发也不重复。
     */
    private boolean postedToday(String token) {
        try {
            String userId = loadUserId(token);
            if (StrUtil.isBlank(userId)) {
                System.out.println("[观猹] 未取到用户 ID，跳过当日重复检查");
                return false;
            }
            JSONObject data = requestData(authorized(HttpRequest.get(
                    baseUrl + "/api/v2/users/" + encode(userId) + "/posts?limit=20"), token)
                    .execute(), "读取我的帖子");
            JSONArray posts = data.getJSONArray("items");
            if (posts == null) {
                return false;
            }
            LocalDate today = LocalDate.now(SHANGHAI);
            for (int index = 0; index < posts.size(); index++) {
                JSONObject post = posts.getJSONObject(index);
                String createdAt = post == null ? null : post.getStr("create_at");
                if (createdAt != null && sameDayShanghai(createdAt, today)) {
                    System.out.println("[观猹] 今日已发过帖子：" + sanitize(StrUtil.nullToEmpty(post.getStr("title"))));
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            System.out.println("[观猹] 无法确认今日是否已发帖：" + safeMessage(e.getMessage()));
            return true;
        }
    }

    private String loadUserId(String token) {
        JSONObject me = requestData(authorized(HttpRequest.get(baseUrl + "/api/v2/users/me"), token)
                .execute(), "读取用户信息");
        long id = longValue(me, "id");
        return id > 0 ? String.valueOf(id) : null;
    }

    static boolean sameDayShanghai(String createdAt, LocalDate day) {
        if (StrUtil.isBlank(createdAt) || day == null) {
            return false;
        }
        try {
            return Instant.parse(createdAt.trim()).atZone(SHANGHAI).toLocalDate().equals(day);
        } catch (DateTimeParseException e) {
            return createdAt.trim().startsWith(day.toString());
        }
    }

    private static Set<Integer> recommendedIds(JSONObject daily) {
        Set<Integer> ids = new LinkedHashSet<>();
        JSONObject state = daily == null ? null : daily.getJSONObject("state");
        JSONArray products = state == null ? null : state.getJSONArray("product_ids");
        if (products != null) {
            for (Object value : products) {
                int id = intValue(value);
                if (id > 0) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private LoadedProduct loadProduct(String token, int id, QuestRun run) {
        LoadedProduct cached = run.products.get(id);
        if (cached != null) {
            return cached;
        }
        JSONObject product = requestData(authorized(HttpRequest.get(baseUrl + "/api/v2/products/" + id), token)
                .execute(), "读取推荐产品");
        LoadedProduct loaded = new LoadedProduct(id,
                StrUtil.blankToDefault(product.getStr("name"), "推荐产品 #" + id),
                StrUtil.nullToEmpty(product.getStr("slogan")), categoryText(product),
                WatchaAiCopywriter.limit(WatchaAiCopywriter.plainText(product.get("description"))),
                StrUtil.nullToEmpty(product.getStr("website_url")),
                StrUtil.nullToEmpty(product.getStr("image_url")));
        run.products.put(id, loaded);
        return loaded;
    }

    private static boolean reviewFinished(JSONObject quest) {
        String status = quest.getStr("status");
        return boolValue(quest.getJSONObject("state"), "reviewed")
                || "completed".equals(status)
                || "claimable".equals(status);
    }

    private static boolean isClosed(String status) {
        return "paused".equals(status) || "expired".equals(status) || "locked".equals(status);
    }

    private static boolean isCaptcha(RuntimeException exception) {
        return safeMessage(exception.getMessage()).contains("验证码");
    }

    private String questUrl(long questId, String suffix) {
        return baseUrl + "/api/v2/quests/" + questId + suffix;
    }

    private static JSONObject claimBody(JSONObject quest) {
        JSONObject body = JSONUtil.createObj();
        long instanceId = longValue(quest, "quest_instance_id");
        if (instanceId > 0) {
            body.set("instance_id", instanceId);
        }
        return body;
    }

    private static JSONObject document(List<String> paragraphs) {
        JSONArray blocks = new JSONArray();
        for (String paragraph : paragraphs) {
            JSONArray inline = new JSONArray();
            inline.add(JSONUtil.createObj().set("type", "text").set("text", paragraph));
            blocks.add(JSONUtil.createObj().set("type", "paragraph").set("content", inline));
        }
        return JSONUtil.createObj().set("type", "doc").set("content", blocks);
    }

    private static String categoryText(JSONObject product) {
        JSONArray categories = product.getJSONArray("categories");
        if (categories == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < categories.size(); index++) {
            JSONObject category = categories.getJSONObject(index);
            if (category == null || StrUtil.isBlank(category.getStr("name"))) {
                continue;
            }
            if (text.length() > 0) {
                text.append('、');
            }
            text.append(category.getStr("name").trim());
        }
        return text.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private String login(String username, String password) {
        JSONObject body = JSONUtil.createObj()
                .set("account", username)
                .set("password", password);
        JSONObject data = requestData(HttpRequest.post(loginUrl)
                .headerMap(headers(), true)
                .disableCookie()
                .body(body.toString())
                .timeout(REQUEST_TIMEOUT)
                .execute(), "登录");
        String token = data.getStr("access_token");
        if (StrUtil.isBlank(token)) {
            throw new IllegalStateException("登录响应缺少访问令牌");
        }
        // 登录响应里同时给 refresh_token（30 天），存下来以后就不用每天送密码了
        httpRefreshToken = StrUtil.blankToDefault(data.getStr("refresh_token"), "");
        return token;
    }

    private String loadDisplayName(String token) {
        try {
            JSONObject user = requestData(authorized(HttpRequest.get(userUrl), token).execute(), "获取用户信息");
            String nickname = user.getStr("nickname");
            if (StrUtil.isNotBlank(nickname)) {
                return nickname;
            }
            String username = user.getStr("username");
            return StrUtil.isBlank(username) ? null : username;
        } catch (Exception e) {
            System.err.println("[观猹] 获取用户信息失败：" + safeMessage(e.getMessage()));
            return null;
        }
    }

    private Integer loadGuazi(String token) {
        try {
            JSONObject data = requestData(authorized(HttpRequest.get(balancesUrl), token).execute(), "获取瓜子");
            JSONArray balances = data.getJSONArray("balances");
            if (balances == null) {
                return null;
            }
            for (int index = 0; index < balances.size(); index++) {
                JSONObject balance = balances.getJSONObject(index);
                if (balance != null && GUAZI_NAME.equals(balance.getStr("promotion_name"))) {
                    return numberValue(balance, "amount");
                }
            }
            return null;
        } catch (Exception e) {
            System.err.println("[观猹] 获取瓜子失败：" + safeMessage(e.getMessage()));
            return null;
        }
    }

    private HttpRequest authorized(HttpRequest request, String token) {
        return authorized(request, token, "/quest");
    }

    private HttpRequest authorized(HttpRequest request, String token, String referer) {
        Map<String, String> headers = headers(referer);
        headers.put("Authorization", "Bearer " + token);
        // 浏览器发 GET 时不带 Content-Type，也不带 Origin，跟着一起去掉，别让请求头像被拼出来的
        if (!sendsBody(request)) {
            headers.remove("Content-Type");
            headers.remove("Origin");
        }
        return request.headerMap(headers, true).disableCookie().timeout(REQUEST_TIMEOUT);
    }

    private static boolean sendsBody(HttpRequest request) {
        if (request == null || request.getMethod() == null) {
            return false;
        }
        String method = request.getMethod().name();
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
    }

    private JSONObject requestData(HttpResponse response, String action) {
        JSONObject payload = toJSON(response.body());
        if (payload == null) {
            throw new IllegalStateException(action + "失败：服务返回了无效数据");
        }
        int httpStatus = response.getStatus();
        Integer statusCode = payload.getInt("statusCode");
        if (httpStatus == 449 || (statusCode != null && statusCode == 449)) {
            throw new IllegalStateException(action + "失败：需要完成验证码");
        }
        if (httpStatus < 200 || httpStatus >= 300 || (statusCode != null && statusCode != 200)) {
            throw new IllegalStateException(action + "失败：" + safeMessage(payload.getStr("message")));
        }
        JSONObject data = payload.getJSONObject("data");
        return data == null ? JSONUtil.createObj() : data;
    }

    private Map<String, String> headers() {
        return headers("/quest");
    }

    /**
     * 按动作给 Referer。真实浏览器是在哪个页面点的按钮就从哪个页面带 Referer，
     * 全部写成 /quest 反而是个破绽：猹评是在产品页提交的。
     */
    private Map<String, String> headers(String path) {
        Map<String, String> headers = commonHeaders();
        String referer = StrUtil.isBlank(path) ? "/quest" : path;
        headers.put("Origin", DEFAULT_BASE_URL);
        headers.put("Referer", DEFAULT_BASE_URL + referer);
        if (browserTransport != null) {
            // 浏览器模式下，剩下的 HTTP 请求也要和浏览器同一套 UA，否则同一个账号两个指纹打架
            headers.put("User-Agent", browserTransport.userAgent());
            headers.put("sec-ch-ua", browserTransport.brandsHeader());
            headers.put("sec-ch-ua-mobile", "?0");
            headers.put("sec-ch-ua-platform", "\"Linux\"");
        }
        return headers;
    }

    private static boolean boolValue(JSONObject object, String key) {
        if (object == null) {
            return false;
        }
        Object value = object.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static int numberValue(JSONObject object, String key) {
        return intValue(object == null ? null : object.get(key));
    }

    private static int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longValue(JSONObject object, String key) {
        if (object == null || object.get(key) == null) {
            return 0L;
        }
        Object value = object.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String sanitize(String value) {
        String text = value.replaceAll("[\\r\\n<>]+", " ").trim();
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    private static String safeMessage(String message) {
        String value = StrUtil.blankToDefault(message, "未知错误").replaceAll("[\\r\\n]+", " ");
        return value.length() > 160 ? value.substring(0, 160) + "..." : value;
    }

    private static String trimTrailingSlash(String value) {
        if (StrUtil.isBlank(value)) {
            return DEFAULT_BASE_URL;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static final class QuestRun {
        private final MessageBlock block = new MessageBlock();
        private final Map<Integer, LoadedProduct> products = new HashMap<>();
        private boolean published;
        /** 今天评过的产品，回评优先落在同一个产品下面，才像同一个人刚用过。 */
        private int reviewedProductId;
        /** 今天试用的记录，回评时能带上自己那次的具体情况。 */
        private String trialSummary = "";
    }

    private static final class ReplyTarget {
        private final long id;
        private final String text;
        private final String author;
        private final String productName;

        private ReplyTarget(long id, String text, String author, String productName) {
            this.id = id;
            this.text = text;
            this.author = author;
            this.productName = productName;
        }
    }

    private static final class CheckInReport {
        private final String displayName;
        private final int streak;
        private final String result;
        private final Integer reward;

        private CheckInReport(String displayName, int streak, String result, Integer reward) {
            this.displayName = displayName;
            this.streak = streak;
            this.result = result;
            this.reward = reward;
        }
    }

    private static final class LoadedProduct {
        private final int id;
        private final String name;
        private final String slogan;
        private final String categories;
        private final String description;
        /** 官网地址，用来真打开一次采集试用事实。 */
        private final String websiteUrl;
        /** 产品封面，猹评按计划配图时直接用站点自己的图，不额外上传。 */
        private final String imageUrl;

        private LoadedProduct(int id, String name, String slogan, String categories, String description,
                              String websiteUrl, String imageUrl) {
            this.id = id;
            this.name = name;
            this.slogan = slogan;
            this.categories = categories;
            this.description = description;
            this.websiteUrl = websiteUrl;
            this.imageUrl = imageUrl;
        }
    }
}
