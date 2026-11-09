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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WatchaCheckInTask extends CheckInTask {

    private static final String DEFAULT_BASE_URL = "https://watcha.cn";
    private static final String USERNAME_KEY = "WATCHA_USERNAME";
    private static final String PASSWORD_KEY = "WATCHA_PASSWORD";
    private static final String PUBLISH_KEY = "WATCHA_PUBLISH";
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

    @Override
    public CheckInTask run() {
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
        String token;
        try {
            token = login(username, password);
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

    private void completeQuests(String token) {
        String publish = environment == null ? "" : StrUtil.nullToEmpty(environment.get(PUBLISH_KEY)).trim();
        if (!isPublishEnabled(publish)) {
            if (publish.isEmpty() || isPublishDisabled(publish)) {
                System.out.println("[观猹] 自动发布未开启，跳过猹评和发帖");
                return;
            }
            System.err.println("[观猹] WATCHA_PUBLISH 仅支持 true 或 false");
            addMessage(new MessageBlock()
                    .addLine("任务结果：已跳过")
                    .addLine("原因：WATCHA_PUBLISH 仅支持 true 或 false"));
            return;
        }
        if (copywriter.isPartial()) {
            System.out.println("[观猹] 文案接口配置不完整，跳过猹评和发帖");
            addMessage(new MessageBlock()
                    .addLine("任务结果：已跳过")
                    .addLine("原因：文案接口配置不完整"));
            return;
        }
        if (!copywriter.isReady()) {
            System.out.println("[观猹] 文案接口未配置，跳过猹评和发帖");
            return;
        }

        QuestRun run = new QuestRun();
        try {
            System.out.println("[观猹] 开始处理每日和每周任务");
            JSONObject items = prepareQuests(token);
            claimAll(items, token, run);
            publishReviews(items, token, run);
            if (run.aiFailure != null) {
                run.block.addLine("本周帖子：已跳过");
                run.block.addLine("原因：前置猹评处理失败（", run.aiFailure, "）");
            } else {
                publishPosts(items, token, run);
            }
            claimWithRetry(token, run);
        } catch (Exception e) {
            String error = safeMessage(e.getMessage());
            System.err.println("[观猹] 任务失败：" + error);
            run.block.addLine("任务结果：未完成");
            run.block.addLine("原因：", error);
        }
        addMessage(run.block);
    }

    private JSONObject prepareQuests(String token) {
        JSONObject items = fetchQuests(token);
        if (!receiveIfNeeded(items, token)) {
            return items;
        }
        return fetchQuests(token);
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
     * 取一次进度并领取。发布过的账号再多取一次，服务端进度刷新有延迟。
     */
    private void claimWithRetry(String token, QuestRun run) {
        JSONObject items = fetchQuests(token);
        claimAll(items, token, run);
        if (!run.published || hasClaimable(items)) {
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

    private static boolean hasClaimable(JSONObject items) {
        if (items == null) {
            return false;
        }
        JSONObject daily = items.getJSONObject("daily.product_review");
        if (daily != null && "claimable".equals(daily.getStr("status"))) {
            return true;
        }
        for (String code : CLAIM_CODES) {
            JSONObject quest = items.getJSONObject(code);
            JSONArray tiers = quest == null ? null : quest.getJSONArray("completion_items");
            if (tiers == null) {
                continue;
            }
            for (int index = 0; index < tiers.size(); index++) {
                JSONObject tier = tiers.getJSONObject(index);
                if (tier != null && "claimable".equals(tier.getStr("status"))) {
                    return true;
                }
            }
        }
        return false;
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

    private void publishReviews(JSONObject items, String token, QuestRun run) {
        JSONObject daily = items.getJSONObject("daily.product_review");
        JSONObject weekly = items.getJSONObject("weekly.review");
        int weeklyCount = weekly == null ? 0 : numberValue(weekly.getJSONObject("state"), "count");
        if (daily == null) {
            run.block.addLine("今日猹评：已跳过");
            run.block.addLine("原因：任务进度里没有每日猹评");
            return;
        }
        if (isClosed(daily.getStr("status"))) {
            run.block.addLine("今日猹评：已跳过");
            run.block.addLine("原因：今日任务暂不可用");
            return;
        }
        if (reviewFinished(daily)) {
            run.block.addLine("今日猹评：今日已完成");
            run.block.addLine("本周猹评：", weeklyCount + "/" + WEEKLY_TIERS);
            return;
        }
        List<LoadedProduct> products = loadProducts(token, daily, run);
        LoadedProduct target = firstWritable(products, run);
        if (target == null) {
            run.block.addLine("今日猹评：已跳过");
            run.block.addLine("原因：", reviewSkipReason(products));
            return;
        }
        pause.betweenActions();
        WatchaAiCopywriter.DraftResult draft = copywriter.writeReview(
                target.name, target.slogan, target.categories, target.description, target.id);
        if (!draft.ok()) {
            String failure = safeMessage(draft.failure);
            run.block.addLine("今日猹评：已跳过");
            run.block.addLine("原因：", failure);
            if (draft.failure != null && draft.failure.startsWith("文案生成失败")) {
                run.aiFailure = failure;
            }
            return;
        }
        JSONObject body = JSONUtil.createObj()
                .set("content", document(draft.paragraphs))
                .set("vote_value", draft.voteValue)
                .set("images", "")
                .set("append", false);
        requestData(authorized(HttpRequest.post(baseUrl + "/api/v2/products/" + target.id + "/reviews"), token)
                .body(body.toString())
                .execute(), "发布猹评");
        target.reviewedByMe = true;
        run.usedProductIds.add(target.id);
        run.reviewedProductId = target.id;
        run.published = true;
        run.block.addLine("今日猹评：已发布 ", sanitize(target.name));
        run.block.addLine("本周猹评：", Math.min(weeklyCount + 1, WEEKLY_TIERS) + "/" + WEEKLY_TIERS);
        System.out.println("[观猹] 已发布猹评：" + sanitize(target.name));
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
        JSONObject daily = items.getJSONObject("daily.product_review");
        List<LoadedProduct> products = daily == null
                ? new ArrayList<LoadedProduct>() : loadProducts(token, daily, run);
        LoadedProduct target = firstWritable(products, run);
        if (target == null) {
            run.block.addLine("本周帖子：已跳过");
            run.block.addLine("原因：", postSkipReason(products));
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
        run.usedProductIds.add(target.id);
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

    private List<LoadedProduct> loadProducts(String token, JSONObject quest, QuestRun run) {
        if (run.products != null) {
            return run.products;
        }
        List<LoadedProduct> products = new ArrayList<LoadedProduct>();
        JSONObject state = quest.getJSONObject("state");
        JSONArray ids = state == null ? null : state.getJSONArray("product_ids");
        if (ids != null) {
            for (int index = 0; index < ids.size(); index++) {
                int id = intValue(ids.get(index));
                if (id > 0) {
                    products.add(loadProduct(token, id));
                }
            }
        }
        run.products = products;
        return products;
    }

    private LoadedProduct loadProduct(String token, int id) {
        JSONObject product = requestData(authorized(HttpRequest.get(baseUrl + "/api/v2/products/" + id), token)
                .execute(), "读取推荐产品");
        JSONObject mine = requestData(authorized(
                HttpRequest.get(baseUrl + "/api/v2/products/" + id + "/reviews/user"), token)
                .execute(), "读取我的猹评");
        JSONArray reviews = mine.getJSONArray("reviews");
        String description = WatchaAiCopywriter.limit(WatchaAiCopywriter.plainText(product.get("description")));
        return new LoadedProduct(id,
                StrUtil.blankToDefault(product.getStr("name"), "推荐产品"),
                StrUtil.nullToEmpty(product.getStr("slogan")),
                categoryText(product),
                description,
                WatchaAiCopywriter.findAnchors(description),
                reviews != null && !reviews.isEmpty());
    }

    private static LoadedProduct firstWritable(List<LoadedProduct> products, QuestRun run) {
        for (LoadedProduct product : products) {
            if (product.reviewedByMe || run.usedProductIds.contains(product.id) || product.anchors.size() < 2) {
                continue;
            }
            return product;
        }
        return null;
    }

    private static String reviewSkipReason(List<LoadedProduct> products) {
        if (products.isEmpty()) {
            return "没有今日推荐";
        }
        for (LoadedProduct product : products) {
            if (product.anchors.size() >= 2 && !product.reviewedByMe) {
                return "没有可发布的推荐产品";
            }
        }
        for (LoadedProduct product : products) {
            if (product.anchors.size() >= 2) {
                return "推荐产品都已评过";
            }
        }
        return "推荐产品介绍太短";
    }

    private static String postSkipReason(List<LoadedProduct> products) {
        if (products.isEmpty()) {
            return "没有今日推荐";
        }
        for (LoadedProduct product : products) {
            if (product.anchors.size() >= 2) {
                return "没有另一款可写的推荐产品";
            }
        }
        return "推荐产品介绍太短";
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
        Map<String, String> headers = headers();
        headers.put("Authorization", "Bearer " + token);
        return request.headerMap(headers, true).disableCookie().timeout(REQUEST_TIMEOUT);
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
        Map<String, String> headers = commonHeaders();
        headers.put("Origin", DEFAULT_BASE_URL);
        headers.put("Referer", DEFAULT_BASE_URL + "/quest");
        return headers;
    }

    private static boolean isPublishEnabled(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static boolean isPublishDisabled(String value) {
        return "false".equalsIgnoreCase(value) || "0".equals(value);
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
        private final Set<Integer> usedProductIds = new LinkedHashSet<Integer>();
        private List<LoadedProduct> products;
        private boolean published;
        private String aiFailure;
        private int reviewedProductId;
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
        private final List<String> anchors;
        private boolean reviewedByMe;

        private LoadedProduct(int id, String name, String slogan, String categories, String description,
                              List<String> anchors, boolean reviewedByMe) {
            this.id = id;
            this.name = name;
            this.slogan = slogan;
            this.categories = categories;
            this.description = description;
            this.anchors = anchors;
            this.reviewedByMe = reviewedByMe;
        }
    }
}
