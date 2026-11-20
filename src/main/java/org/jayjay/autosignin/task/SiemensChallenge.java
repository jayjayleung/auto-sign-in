package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 西门子「7天挑战赛」每日答题。
 * <p>
 * 入口在签到弹层底部（/Event/7DaysChallenge/Index.aspx 的「去答题」），活动规则是每天答一题、
 * 连续 7 天算完成一轮；第 1、4、7 天答完可以领奖，其中第 1 天是电子资料、第 7 天是抽西币。
 * 中断一天就从头开始，所以每天都要跟。
 * <p>
 * 站点把题库和正确答案一起下发了：{@code type=getquestion} 直接返回题目和 {@code TrueAnswer}，
 * 不需要猜。我们取题、按答案提交，再看返回的 {@code IsCorrect} 和
 * {@code IsCanOneDayPrize} / {@code IsCanSevenDayPrize} 决定要不要领奖。
 * <p>
 * 答题接口本身不限制当天次数（重复提交同一题也返回成功），所以「今天答过没有」只能自己记：
 * 当天答过就不再提交，避免把站点当成刷分工具。状态文件按账号存，
 * 只有站点确认处理过才记账。
 */
final class SiemensChallenge {

    private static final String QUESTION_URL = "/Event/7DaysChallenge/Question.aspx";
    private static final String CHALLENGE_API = "/event/AjaxAPI/SevenDaysChallenge.ashx";
    private static final String PRIZE_API = "/event/AjaxAPI/SevenDaysPrize.ashx";
    /** 第一天答完的领奖动作，站点只认这个 type。 */
    private static final String CLAIM_ONE_DAY = "InsertSevenDaysOneDayAward";
    private static final String DRAW_PRIZE = "drawprize";

    private final SiemensCheckInTask task;

    SiemensChallenge(SiemensCheckInTask task) {
        this.task = task;
    }

    /** 答题页渲染出的当前登录用户 ID，形如 &lt;input id="hiduid" value="1820833" /&gt;。 */
    private static final Pattern HID_UID = Pattern.compile("id=\"hiduid\"\\s+value=\"(\\d+)\"");

    /** 本次答题结果，供通知拼一句话。 */
    static final class Result {
        /** 今天是否提交了答案。false 表示今天已经答过、或拿不到题目。 */
        boolean answered;
        /** 答案是否与站点给的正确答案一致。 */
        boolean correct;
        /** 站点说明可以领第一天的奖品。 */
        boolean oneDayPrize;
        /** 今天是否真的点过领奖。 */
        boolean claimed;
        /** 站点在活动第 7 天给的抽奖机会，本次是否抽过。 */
        boolean drew;
        /** 站点说明本次答完可以抽奖（第 7 天）。 */
        boolean drawAllowed;
        /** 抽奖结果文案。 */
        String drawMessage = "";
        /** 拿不到题目或提交失败的原因，空表示没出错。 */
        String skipReason = "";
    }

    /**
     * 完成今天的答题（已答过则跳过），并在站点允许时领取奖品。
     * 任何一步失败都不抛异常，只记日志，不影响签到和每日任务。
     */
    Result run() {
        Result result = new Result();
        // 用户 ID 以答题页渲染出的 hiduid 为准：签到首页的 userId 只用来拼资料，
        // 两个页面取值口径不同，这里直接读页面上的，避免拿到空值白跑。
        SiemensCheckInTask.Page page = task.follow(task.supportBaseUrl() + QUESTION_URL);
        if (page == null || StrUtil.isBlank(page.body)) {
            result.skipReason = "打不开答题页";
            return result;
        }
        String userId = group(HID_UID, page.body);
        if (StrUtil.isBlank(userId) || "0".equals(userId)) {
            result.skipReason = "答题页未登录";
            return result;
        }
        // 页面上的「点击领奖」按钮可见，说明有一笔奖品还没领。实测这笔奖可能在答题之后就
        // 被站点收回去（按钮变回隐藏），所以必须在答题**之前**先领，不能等提交返回再说。
        if (isPrizeButtonShown(page.body, "btnPrizeOneDay")) {
            result.claimed = claimOneDayPrize(userId);
        }
        // 第 7 天答完首页会显示「点击抽奖」，有次数就先抽掉，和答题互不影响。
        if (isPrizeButtonShown(page.body, "btnPrizeSevenDay")) {
            drawPrize(userId, result);
        }

        if (task.challengeAnsweredToday()) {
            // 站点不拦重复答题，所以「今天答过没有」由本地记录决定，避免多容器同时刷同一题。
            System.out.println("[西门子] 今天已经答过 7 天挑战赛的题，跳过");
            return result;
        }
        answerToday(userId, result);
        // 本次答完又拿到新奖品时补领一次；已经领过的不重复点。
        if (result.oneDayPrize && !result.claimed) {
            result.claimed = claimOneDayPrize(userId);
        }
        if (result.drawAllowed && !result.drew) {
            drawPrize(userId, result);
        }
        return result;
    }

    /** 取题、作答、记录「今天答过了」。 */
    private void answerToday(String userId, Result result) {
        JSONObject question = fetchQuestion(userId);
        if (question == null) {
            result.skipReason = "拿不到题目";
            return;
        }
        String questionId = question.getStr("Id");
        String answer = StrUtil.trim(question.getStr("TrueAnswer"));
        if (StrUtil.isBlank(questionId) || StrUtil.isBlank(answer)) {
            result.skipReason = "题目缺少答案";
            return;
        }
        System.out.println("[西门子] 7 天挑战赛答题：" + StrUtil.maxLength(
                StrUtil.blankToDefault(question.getStr("QuestionTitle"), ""), 40));
        JSONObject response = submit(userId, questionId, answer);
        if (response == null || response.getJSONObject("Error") == null
                || response.getJSONObject("Error").getInt("ErrorCode", -1) != 0) {
            result.skipReason = "答题提交失败";
            return;
        }
        result.answered = true;
        result.correct = Boolean.TRUE.equals(response.getBool("IsCorrect"));
        result.oneDayPrize = Boolean.TRUE.equals(response.getBool("IsCanOneDayPrize"));
        result.drawAllowed = Boolean.TRUE.equals(response.getBool("IsCanSevenDayPrize"));
        task.markChallengeAnswered();
        System.out.println("[西门子] 7 天挑战赛答题" + (result.correct ? "正确" : "错误")
                + "，正确答案 " + StrUtil.blankToDefault(response.getStr("TrueAnswer"), answer));
    }

    /** 答题页上的领奖/抽奖按钮是否可见，可见说明这笔奖还没领。 */
    static boolean isPrizeButtonShown(String html, String buttonId) {
        if (StrUtil.isBlank(html)) {
            return false;
        }
        Matcher button = Pattern.compile("<button[^>]*id=\"" + buttonId + "\"[^>]*>").matcher(html);
        return button.find() && !button.group().contains("display: none");
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 取今天的题目，站点直接把正确答案一起返回。 */
    private JSONObject fetchQuestion(String userId) {
        JSONObject body = post(CHALLENGE_API, "type=getquestion&uid=" + userId, QUESTION_URL);
        JSONArray list = body == null ? null : body.getJSONArray("QuestionAndAnswerBankList");
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.getJSONObject(0);
    }

    /** 提交答案。answerCount 是页面上的答题次数，站点不校验，固定传 0。 */
    private JSONObject submit(String userId, String questionId, String answer) {
        return post(CHALLENGE_API, "type=adduseranswerrecord"
                + "&answerOptions=" + SiemensCheckInTask.encode(answer)
                + "&qId=" + SiemensCheckInTask.encode(questionId)
                + "&answerCount=0&uid=" + userId, QUESTION_URL);
    }

    /**
     * 领取第一天的电子资料奖品。
     * 站点对重复领取也返回成功，所以只要本次答完、站点说能领就点一次，不做额外判重。
     */
    private boolean claimOneDayPrize(String userId) {
        JSONObject body = post(PRIZE_API,
                "type=" + CLAIM_ONE_DAY + "&uid=" + userId + "&platform=1", QUESTION_URL);
        // 这个接口成功时只有 ErrorCode=0，没有其它字段可判断，按错误码认定。
        boolean ok = body != null && body.getInt("ErrorCode", -1) == 0;
        System.out.println("[西门子] 7 天挑战赛领取第一天奖品" + (ok ? "成功" : "未成功"));
        return ok;
    }

    /** 第 7 天抽奖。没机会时站点回「您当前没有抽奖机会」，照记日志即可。 */
    private void drawPrize(String userId, Result result) {
        JSONObject body = post(PRIZE_API,
                "type=" + DRAW_PRIZE + "&uid=" + userId + "&platform=1", QUESTION_URL);
        int code = body == null ? -1 : body.getInt("ErrorCode", -1);
        String message = body == null ? "" : StrUtil.blankToDefault(body.getStr("AwardMsg"),
                StrUtil.blankToDefault(body.getStr("ErrorDetail"), ""));
        if (code == 0) {
            result.drew = true;
        }
        result.drawMessage = message;
        System.out.println("[西门子] 7 天挑战赛抽奖：" + (StrUtil.isBlank(message) ? "无返回文案" : message));
    }

    private JSONObject post(String path, String body, String refererPath) {
        String url = task.supportBaseUrl() + path;
        try {
            SiemensCheckInTask.HttpResult response = task.post(url, body,
                    task.supportHeaders(task.supportBaseUrl() + refererPath));
            return SiemensCheckInTask.parseObject(response.body);
        } catch (Exception e) {
            System.out.println("[西门子] 7 天挑战赛接口异常：" + e.getMessage());
            return null;
        }
    }
}
