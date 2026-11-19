package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三个账号互相配合产出「最佳答案」的闭环：A 提问、B 回答、A 采纳、C 评论。
 * <p>
 * 站点上最佳答案只能由提问者采纳，而「评论最佳答案」这个每日任务又要求评论别人的最佳答案，
 * 单个账号自己转不起来。三个容器各跑一个账号、互不共享 cookie，所以这里用一个共享的轮次文件
 * 把动作串起来，每个容器每次运行做三件事：
 * <ol>
 * <li>登记自己的账号，并把「自己提的、兄弟账号答的」问题采纳掉；</li>
 * <li>挑一条「别人提的、别人答的、已采纳」的问答去评论；</li>
 * <li>提问和回答时优先接上轮次里空着的位置，让下一轮还有料。</li>
 * </ol>
 * 文件必须放在三个容器都能读写的共享目录，见 {@link #sharedDir()}。只做兄弟账号之间的配合，
 * 不会去采纳站上陌生人的回答，避免把别人的答案误判成最佳。
 */
final class SiemensPipeline {

    static final String FILE_NAME = "siemens-pipeline.json";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 详情页链接里的问答 ID，出现在「我的提问」等页面。 */
    private static final Pattern QUESTION_LINK = Pattern.compile("(?:solve|solved)_(\\d+)_(\\d+)\\.html");
    /** 接口里的时间字段格式是 /Date(毫秒数)/。 */
    private static final Pattern DATE_MS = Pattern.compile("/Date\\((\\d+)\\)/");
    /** 轮次文件保留的最大条数，避免无限增长。 */
    private static final int MAX_ROUNDS = 60;
    /**
     * 同一个账号两次互评之间至少间隔的天数：隔天一次。
     * <p>
     * 光靠这个天数只能做到「每个账号各自隔天」，实际会漂移成
     * A/C 在 11-17、B 在 11-19 这种错位状态。所以真正决定哪天互评的是
     * {@link #isSiblingCommentDay(LocalDate)}：三个账号共用同一份日历，
     * 隔天统一开口，要么三个一起评，要么三个一起休息。
     */
    private static final int SIBLING_COMMENT_INTERVAL_DAYS = 2;
    /**
     * 轮次里的问题超过这么多天还没被兄弟账号回答，就不再等它。
     * 站点只给提问者 7 天采纳期，而且待解决列表按时间倒序，老问题几小时后就沉到第二页以后，
     * 一直盯着最早那条会永远撞上「已不在待解决列表」，闭环就断了。
     */
    private static final int ANSWER_WINDOW_DAYS = 3;
    /** 每次运行最多回查几条漏记的兄弟回答，避免对站上打太多请求。 */
    private static final int BACKFILL_LIMIT = 3;

    private final SiemensCheckInTask task;
    private final Path file;
    private JSONObject store;

    SiemensPipeline(SiemensCheckInTask task) {
        this.task = task;
        this.file = sharedDir().resolve(FILE_NAME);
    }

    /**
     * 共享目录：容器里挂载的 /app/shared，三个容器都挂同一个宿主机目录。
     * 本地运行或单元测试没有这个目录时退回单账号的 state 目录，功能退化成只记录不协同。
     */
    static Path sharedDir() {
        Path shared = Paths.get("/app/shared");
        if (Files.isDirectory(shared) && Files.isWritable(shared)) {
            return shared;
        }
        return SiemensCheckInTask.stateDir();
    }

    // ==================== 对外动作 ====================

    /**
     * 每轮每日任务开始前调用：登记账号、发现自己的问题、采纳兄弟账号的回答。
     * 任何一步失败都只是少一条可用的最佳答案，不影响签到和其他任务。
     */
    void beforeDailyTasks(String myUid) {
        if (StrUtil.isBlank(myUid)) {
            return;
        }
        try {
            registerAccount(myUid);
            discoverOwnQuestions(myUid);
            backfillMissingAnswers(myUid);
            adoptOwnAnswers(myUid);
        } catch (Exception e) {
            System.out.println("[西门子] 轮次准备失败：" + e.getMessage());
        }
    }

    /**
     * 评论一条兄弟账号产出的最佳答案，成功返回 true。
     * 评论内容由调用方按该条答案的原文生成：不同答案讨论的问题完全不同，
     * 用一句通用评论会答非所问，也是之前被判「非原创」的同类问题。
     *
     * @param commentFor 收到答案原文，返回针对它写的评论；返回空表示放弃这条
     */
    boolean commentSiblingAnswer(String myUid, java.util.function.Function<String, String> commentFor) {
        if (StrUtil.isBlank(myUid)) {
            return false;
        }
        // 兄弟容器的采纳是几分钟前刚写进文件的，重读一次才看得到新的最佳答案。
        reload();
        JSONArray rounds = store.getJSONArray("rounds");
        boolean hasCandidate = false;
        // 从最新一条往回找：刚采纳的答案才有评论价值，也避免总盯着同一批旧问答。
        for (int i = rounds.size() - 1; i >= 0; i--) {
            JSONObject round = rounds.getJSONObject(i);
            if (round == null || !Boolean.TRUE.equals(round.getBool("adopted"))) {
                continue;
            }
            String asker = round.getStr("askerUid");
            JSONObject answer = round.getJSONObject("answer");
            String answerer = answer == null ? null : answer.getStr("uid");
            String aid = answer == null ? null : answer.getStr("aid");
            // 评论自己的提问或自己的回答都会被站点拦下，直接跳过。
            if (myUid.equals(asker) || myUid.equals(answerer) || StrUtil.isBlank(aid)) {
                continue;
            }
            if (alreadyCommented(round, myUid)) {
                continue;
            }
            hasCandidate = true;
            // 间隔检查放在确认有候选之后：没有候选时不必打这条日志。
            if (!siblingCommentAllowed(myUid)) {
                System.out.println("[西门子] 今天不是互评日（三个账号每隔 "
                        + (SIBLING_COMMENT_INTERVAL_DAYS - 1) + " 天同一天互评），改评站上问答");
                return false;
            }
            String qid = round.getStr("qid");
            String cid = round.getStr("cid");
            if (commentedBefore(qid, cid)) {
                // 页面上的标记更权威，这里同步一下，避免天天重试。
                markCommented(round, myUid);
                continue;
            }
            String answerText = answerText(qid, aid);
            String comment = commentFor.apply(answerText);
            if (StrUtil.isBlank(comment)) {
                continue;
            }
            if (postComment(qid, cid, aid, comment)) {
                markCommented(round, myUid);
                markSiblingComment(myUid);
                System.out.println("[西门子] 评论兄弟账号的最佳答案：" + qid + "/" + aid);
                return true;
            }
        }
        if (!hasCandidate) {
            System.out.println("[西门子] 轮次里没有可互评的答案，改评站上问答");
        }
        return false;
    }

    /** 取某条回答的正文，用于生成贴合内容的评论；取不到返回空串。 */
    private String answerText(String qid, String aid) {
        JSONArray answers = answers(qid);
        for (int i = 0; i < answers.size(); i++) {
            JSONObject item = answers.getJSONObject(i);
            if (item != null && aid.equals(item.getStr("A_ID"))) {
                return SiemensAnswerActions.plainText(item.getStr("A_Content"), 200);
            }
        }
        return "";
    }

    /**
     * 挑一条兄弟账号提的、还没人回答的问题，返回 {qid, cid, title}；没有就返回 null。
     * <p>
     * 必须从最新一条往前找：待解决列表按时间倒序，老问题几小时后就沉到第二页以后，
     * 从前往后找会一直盯着最早那条没答上的问题，每次都撞「已不在待解决列表」，闭环永远转不起来。
     * 超过 {@link #ANSWER_WINDOW_DAYS} 天的问题直接跳过，提问方的采纳期也快过了。
     */
    String[] nextQuestionToAnswer(String myUid) {
        if (StrUtil.isBlank(myUid)) {
            return null;
        }
        // 兄弟容器的提问是几分钟前刚写进文件的，重读一次才看得到。
        reload();
        JSONArray rounds = store.getJSONArray("rounds");
        for (int i = rounds.size() - 1; i >= 0; i--) {
            JSONObject round = rounds.getJSONObject(i);
            if (round == null || Boolean.TRUE.equals(round.getBool("adopted"))) {
                continue;
            }
            String asker = round.getStr("askerUid");
            if (StrUtil.isBlank(asker) || asker.equals(myUid)) {
                continue;
            }
            if (round.getJSONObject("answer") != null) {
                continue;
            }
            if (Boolean.TRUE.equals(round.getBool("adoptClosed"))) {
                continue;
            }
            if (isStaleToAnswer(round) || claimedByOther(round, myUid)) {
                continue;
            }
            String qid = round.getStr("qid");
            String cid = round.getStr("cid");
            String title = StrUtil.blankToDefault(round.getStr("title"), "");
            String body = StrUtil.blankToDefault(round.getStr("body"), "");
            claimQuestion(round, myUid);
            return new String[]{qid, cid, title, body};
        }
        return null;
    }

    /**
     * 这条是否已被另一个账号今天认领。
     * 三个容器的启动时间只差一两分钟，不认领的话后启动的会挑中同一问题，
     * 结果提问最晚的账号反而没人答，闭环又断在另一头。
     */
    private static boolean claimedByOther(JSONObject round, String myUid) {
        if (Boolean.TRUE.equals(round.getBool("answerFailed"))) {
            // 认领方明确失败过，今天让给别人试一次。
            return false;
        }
        String claimUid = round.getStr("claimUid");
        String claimAt = round.getStr("claimAt");
        if (StrUtil.isBlank(claimUid) || claimUid.equals(myUid) || StrUtil.isBlank(claimAt)) {
            return false;
        }
        return LocalDate.now(SHANGHAI).toString().equals(claimAt);
    }

    private void claimQuestion(JSONObject round, String myUid) {
        round.set("claimUid", myUid);
        round.set("claimAt", LocalDate.now(SHANGHAI).toString());
        save();
    }

    /** 认领方回答失败时标记一下，让另外两个账号今天还能接手。 */
    void releaseClaim(String qid, String myUid) {
        if (StrUtil.isBlank(qid) || StrUtil.isBlank(myUid)) {
            return;
        }
        reload();
        JSONObject round = findRound(store.getJSONArray("rounds"), qid);
        if (round == null || !myUid.equals(round.getStr("claimUid"))) {
            return;
        }
        round.set("answerFailed", true);
        save();
    }

    /**
     * 兄弟账号的问题已关闭，谁都答不了，直接标掉。
     * 不标的话另外两个账号还会依次挑到它，每次都白打一轮请求。
     */
    void markQuestionClosed(String qid) {
        if (StrUtil.isBlank(qid)) {
            return;
        }
        reload();
        JSONObject round = findRound(store.getJSONArray("rounds"), qid);
        if (round == null || Boolean.TRUE.equals(round.getBool("adoptClosed"))) {
            return;
        }
        round.set("adoptClosed", true);
        round.set("adoptClosedAt", LocalDate.now(SHANGHAI).toString());
        save();
    }

    /** 问题问出来太久就不再等兄弟账号回答：站点采纳期有限，列表里也早翻不到它了。 */
    private static boolean isStaleToAnswer(JSONObject round) {
        String askedAt = round == null ? null : round.getStr("askedAt");
        if (StrUtil.isBlank(askedAt)) {
            // 老记录没写日期时按还能试处理，最多多打一次请求。
            return false;
        }
        try {
            long gap = java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.parse(askedAt.trim()), LocalDate.now(SHANGHAI));
            return gap > ANSWER_WINDOW_DAYS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 轮次里还没登记回答的兄弟问题 ID。
     * 兜底「站上随机挑一条」时优先命中这些，顺手把闭环接上，而不是靠列表顺序碰运气。
     */
    Set<String> pendingSiblingQuestions(String myUid) {
        Set<String> ids = new LinkedHashSet<>();
        if (StrUtil.isBlank(myUid)) {
            return ids;
        }
        reload();
        JSONArray rounds = store.getJSONArray("rounds");
        for (int i = 0; i < rounds.size(); i++) {
            JSONObject round = rounds.getJSONObject(i);
            if (round == null || Boolean.TRUE.equals(round.getBool("adopted"))
                    || Boolean.TRUE.equals(round.getBool("adoptClosed"))) {
                continue;
            }
            String asker = round.getStr("askerUid");
            if (StrUtil.isBlank(asker) || asker.equals(myUid) || round.getJSONObject("answer") != null) {
                continue;
            }
            if (isStaleToAnswer(round)) {
                continue;
            }
            ids.add(round.getStr("qid"));
        }
        return ids;
    }

    /** 丢弃内存副本，下次读取时以文件为准，能看到兄弟容器本轮刚写入的轮次。 */
    private void reload() {
        store = null;
        load();
    }

    /**
     * 兄弟账号已经答过的问题 ID。
     * 兜底路径从站上的公共列表挑问题时，要避开这些：
     * 三个账号答同一道题既像刷号，也会让提问方在多个兄弟答案之间做无用选择。
     */
    Set<String> questionsAnsweredBySiblings(String myUid) {
        Set<String> ids = new LinkedHashSet<>();
        reload();
        JSONArray rounds = store.getJSONArray("rounds");
        for (int i = 0; i < rounds.size(); i++) {
            JSONObject round = rounds.getJSONObject(i);
            if (round == null) {
                continue;
            }
            String asker = round.getStr("askerUid");
            JSONObject answer = round.getJSONObject("answer");
            String answerer = answer == null ? null : answer.getStr("uid");
            if (StrUtil.isBlank(answerer) || answerer.equals(myUid) || StrUtil.isBlank(asker)) {
                continue;
            }
            // 只有兄弟账号之间的问答才需要避开，陌生人的问题照常参与挑选。
            if (accountIds().contains(asker)) {
                ids.add(round.getStr("qid"));
            }
        }
        return ids;
    }

    /** 记下刚提的问题，供兄弟账号回答、自己下一次采纳。 */
    void recordNewQuestion(String myUid) {
        if (StrUtil.isBlank(myUid)) {
            return;
        }
        try {
            JSONObject newest = newestOwnQuestion(myUid);
            if (newest == null) {
                return;
            }
            recordQuestion(myUid, newest.getStr("Q_ID"), newest.getStr("C_ID"),
                    newest.getStr("Q_Title"), null);
        } catch (Exception e) {
            System.out.println("[西门子] 记录提问失败：" + e.getMessage());
        }
    }

    /**
     * 按已知的问答 ID 记一条轮次。
     * <p>
     * 提问接口提交成功后会 302 到 solve_{qid}_{cid}.html，据此直接记账，
     * 不必再回查待解决列表：列表按时间倒序，等我们回查时自己的问题可能已经翻页了。
     * 标题和正文一起存下来，兄弟账号回答时就不用再去列表里找（那边早就找不到了）。
     */
    void recordQuestion(String myUid, String qid, String cid, String title, String body) {
        if (StrUtil.isBlank(myUid) || StrUtil.isBlank(qid)) {
            return;
        }
        try {
            // 兄弟容器可能刚写过文件，先重读再改，否则保存时会把别人的轮次盖掉。
            reload();
            JSONArray rounds = store.getJSONArray("rounds");
            if (findRound(rounds, qid) != null) {
                return;
            }
            JSONObject round = JSONUtil.createObj();
            round.set("qid", qid);
            round.set("cid", StrUtil.blankToDefault(cid, SiemensAnswerActions.BOARD_ID));
            round.set("askerUid", myUid);
            round.set("askedAt", LocalDate.now(SHANGHAI).toString());
            if (StrUtil.isNotBlank(title)) {
                round.set("title", title);
            }
            if (StrUtil.isNotBlank(body)) {
                round.set("body", body);
            }
            rounds.put(round);
            trim(rounds);
            save();
            System.out.println("[西门子] 记录本轮提问：" + qid);
        } catch (Exception e) {
            System.out.println("[西门子] 记录提问失败：" + e.getMessage());
        }
    }

    /** 记下自己刚提交的回答，提问方下次运行就能采纳。 */
    void recordAnswer(String myUid, String qid) {
        if (StrUtil.isBlank(myUid) || StrUtil.isBlank(qid)) {
            return;
        }
        try {
            JSONObject mine = newestOwnAnswer(qid, myUid);
            if (mine == null) {
                return;
            }
            // 提问方可能刚写过文件，先重读再改，否则保存时会把它的新轮次盖掉。
            reload();
            JSONArray rounds = store.getJSONArray("rounds");
            JSONObject round = findRound(rounds, qid);
            if (round == null || round.getJSONObject("answer") != null) {
                return;
            }
            JSONObject answer = JSONUtil.createObj();
            answer.set("aid", mine.getStr("A_ID"));
            answer.set("uid", myUid);
            answer.set("at", LocalDate.now(SHANGHAI).toString());
            round.set("answer", answer);
            save();
            System.out.println("[西门子] 记录本轮回答：" + qid + "/" + mine.getStr("A_ID"));
        } catch (Exception e) {
            System.out.println("[西门子] 记录回答失败：" + e.getMessage());
        }
    }

    /** 兄弟问题能否由我来回答的判定结果。 */
    enum AnswerState {
        /** 可以回答。 */
        OPEN("可以回答"),
        /** 问题已关闭或已解决，谁都答不了。 */
        CLOSED("已关闭"),
        /** 我其实已经答过了，只是轮次文件里没记上。 */
        ALREADY_ANSWERED("我已答过");

        final String label;

        AnswerState(String label) {
            this.label = label;
        }
    }

    /**
     * 兄弟账号的问题现在能不能由我回答。
     * <p>
     * 一次请求同时判三件事：Q_Status 是否为待解决、我是不是已经答过这条
     * （昨天答过但没记账时轮次里仍然是空的，不挡住就会重复回答同一个问题）、以及问题是否还在。
     * 读不到回答列表时按 {@link AnswerState#OPEN} 处理，避免一次接口抖动就放弃整轮。
     */
    AnswerState answerState(String qid, String myUid) {
        if (StrUtil.isBlank(qid)) {
            return AnswerState.CLOSED;
        }
        JSONArray answers = answers(qid);
        if (answers.isEmpty()) {
            return AnswerState.OPEN;
        }
        JSONObject first = answers.getJSONObject(0);
        int status = first == null ? 0 : parseInt(first.getStr("Q_Status"));
        if (status == 1 || status == 3) {
            return AnswerState.CLOSED;
        }
        if (StrUtil.isNotBlank(myUid)) {
            for (int i = 0; i < answers.size(); i++) {
                JSONObject item = answers.getJSONObject(i);
                if (item != null && myUid.equals(item.getStr("U_ID"))) {
                    return AnswerState.ALREADY_ANSWERED;
                }
            }
        }
        return AnswerState.OPEN;
    }

    /**
     * 回查「已采纳但轮次里没登记回答」之外的情况里更常见的一种：
     * 兄弟账号确实回答了，但它当时走的兜底路径（站上随机挑）没记进轮次文件，
     * 于是提问方第二天找不到可采纳的答案，闭环就断了。
     * <p>
     * 这里在自己提的问题上回查回答列表，发现兄弟账号的回答就补登记。
     * 只处理自己名下的、最近还没采纳的轮次，且每次限 {@link #BACKFILL_LIMIT} 条。
     */
    private void backfillMissingAnswers(String myUid) {
        load();
        JSONArray rounds = store.getJSONArray("rounds");
        Set<String> siblings = accountIds();
        int checked = 0;
        boolean changed = false;
        for (int i = rounds.size() - 1; i >= 0; i--) {
            JSONObject round = rounds.getJSONObject(i);
            if (round == null || Boolean.TRUE.equals(round.getBool("adopted"))
                    || Boolean.TRUE.equals(round.getBool("adoptClosed"))) {
                continue;
            }
            if (!myUid.equals(round.getStr("askerUid")) || round.getJSONObject("answer") != null) {
                continue;
            }
            if (isStaleToAnswer(round)) {
                continue;
            }
            if (checked >= BACKFILL_LIMIT) {
                break;
            }
            checked++;
            String qid = round.getStr("qid");
            JSONArray answers = answers(qid);
            for (int j = 0; j < answers.size(); j++) {
                JSONObject answer = answers.getJSONObject(j);
                String uid = answer == null ? null : answer.getStr("U_ID");
                if (StrUtil.isBlank(uid) || myUid.equals(uid) || !siblings.contains(uid)) {
                    continue;
                }
                JSONObject recorded = JSONUtil.createObj();
                recorded.set("aid", answer.getStr("A_ID"));
                recorded.set("uid", uid);
                recorded.set("at", dateOf(answer.getStr("A_InputTime")));
                round.set("answer", recorded);
                changed = true;
                System.out.println("[西门子] 补登记兄弟账号的回答：" + qid + "/" + answer.getStr("A_ID"));
                if (answer.getInt("IsBestAnswer", 0) == 1) {
                    round.set("adopted", true);
                    round.set("adoptedAt", dateOf(answer.getStr("A_UpdateTime")));
                }
                break;
            }
        }
        if (changed) {
            save();
        }
    }

    // ==================== 采纳 ====================

    /**
     * 把自己名下「兄弟账号已回答」的问题采纳为最佳答案。
     * 问答任务做完后再调一次：兄弟容器可能刚在本轮回答了昨天的问题，这时才够条件采纳，
     * 当天就能产出可供第三个账号互评的最佳答案，不用等到明天。
     */
    void adoptAfterAnswering(String myUid) {
        if (StrUtil.isBlank(myUid)) {
            return;
        }
        try {
            backfillMissingAnswers(myUid);
            adoptOwnAnswers(myUid);
        } catch (Exception e) {
            System.out.println("[西门子] 采纳兄弟回答失败：" + e.getMessage());
        }
    }

    /** 把「我的问题 + 兄弟账号的回答」采纳为最佳答案。 */
    private void adoptOwnAnswers(String myUid) {
        // 采纳前重读，避免把兄弟容器刚写入的轮次在 save() 时盖掉。
        reload();
        JSONArray rounds = store.getJSONArray("rounds");
        Set<String> siblings = accountIds();
        for (int i = 0; i < rounds.size(); i++) {
            JSONObject round = rounds.getJSONObject(i);
            if (round == null || Boolean.TRUE.equals(round.getBool("adopted"))) {
                continue;
            }
            // 已经确认过无法采纳的轮次直接跳过，别每天白打一次请求。
            if (Boolean.TRUE.equals(round.getBool("adoptClosed"))) {
                continue;
            }
            if (!myUid.equals(round.getStr("askerUid"))) {
                continue;
            }
            JSONObject answer = round.getJSONObject("answer");
            String aid = answer == null ? null : answer.getStr("aid");
            String answerer = answer == null ? null : answer.getStr("uid");
            // 只采纳兄弟账号的回答：陌生人的答案质量不可控，不该替自己拍板。
            if (StrUtil.isBlank(aid) || StrUtil.isBlank(answerer) || !siblings.contains(answerer)) {
                continue;
            }
            String qid = round.getStr("qid");
            String cid = round.getStr("cid");
            // 站点允许提问者 7 天内采纳，超期或用「无满意答案」关闭后就再也采纳不了。
            if (isQuestionClosed(qid)) {
                round.set("adoptClosed", true);
                round.set("adoptClosedAt", LocalDate.now(SHANGHAI).toString());
                save();
                System.out.println("[西门子] 问题已关闭，跳过采纳：" + qid);
                continue;
            }
            if (adopt(qid, cid, aid, answerer)) {
                round.set("adopted", true);
                round.set("adoptedAt", LocalDate.now(SHANGHAI).toString());
                save();
                System.out.println("[西门子] 已采纳兄弟账号的回答：" + qid + "/" + aid);
            }
        }
    }

    /**
     * 问题是否已经无法再采纳。
     * 站点问答接口的 Q_Status：0 待解决、1 已关闭（无满意答案）、3 已解决；
     * 只有 0 才可能采纳，读不到状态时按「还能试」处理，避免误判后彻底放弃。
     */
    private boolean isQuestionClosed(String qid) {
        JSONArray answers = answers(qid);
        if (answers.isEmpty()) {
            return false;
        }
        JSONObject first = answers.getJSONObject(0);
        if (first == null) {
            return false;
        }
        int status = parseInt(first.getStr("Q_Status"));
        return status == 1 || status == 3;
    }

    /**
     * 提交采纳。站点入口是弹窗页 question_answercomment.aspx，表单实际提交到
     * solution_P.aspx?PType=ba，成功后 302 跳转到 sysmessage.aspx。
     * <p>
     * 这里再校验一次回答者必须是已登记的兄弟账号：采纳是不可撤销的动作，
     * 不能因为上层传错参数就替自己采纳了陌生人的答案。
     */
    private boolean adopt(String qid, String cid, String aid, String answererUid) {
        if (StrUtil.isBlank(qid) || StrUtil.isBlank(cid) || StrUtil.isBlank(aid)
                || StrUtil.isBlank(answererUid) || !accountIds().contains(answererUid)) {
            System.out.println("[西门子] 采纳参数不合法，跳过：" + qid + "/" + aid);
            return false;
        }
        String pageUrl = task.supportBaseUrl() + "/service/answer/question_answercomment.aspx"
                + "?ba_uid=" + answererUid + "&A_ID=" + aid + "&Q_ID=" + qid
                + "&cid=" + cid + "&ba_reward=1&overdue=N";
        SiemensCheckInTask.Page form = task.follow(pageUrl);
        if (form == null || !form.body.contains("PType=ba")) {
            System.out.println("[西门子] 采纳页不可用：" + qid);
            return false;
        }
        StringBuilder body = new StringBuilder();
        body.append("Q_ID=").append(qid)
                .append("&A_ID=").append(aid)
                .append("&ba_uid=").append(answererUid)
                .append("&CID=").append(cid)
                .append("&overdue=N&ba_reward=1&Q_BARemark=");
        String viewState = SiemensCheckInTask.inputValue(form.body, "__VIEWSTATE");
        if (StrUtil.isNotBlank(viewState)) {
            body.append("&__VIEWSTATE=").append(SiemensCheckInTask.encode(viewState))
                    .append("&__VIEWSTATEGENERATOR=").append(SiemensCheckInTask.encode(
                            SiemensCheckInTask.inputValue(form.body, "__VIEWSTATEGENERATOR")));
        }
        String submit = task.supportBaseUrl() + "/service/answer/solution_P.aspx?PType=ba";
        SiemensCheckInTask.HttpResult result = task.post(submit, body.toString(),
                task.supportHeaders(pageUrl));
        if (result.status != 200 && result.status != 302) {
            System.out.println("[西门子] 采纳 HTTP " + result.status);
            return false;
        }
        // 接口不返回明确结果，回读答案列表确认真的生效。
        return isBestAnswer(qid, aid);
    }

    private boolean isBestAnswer(String qid, String aid) {
        JSONArray answers = answers(qid);
        for (int i = 0; i < answers.size(); i++) {
            JSONObject item = answers.getJSONObject(i);
            if (item != null && aid.equals(item.getStr("A_ID"))) {
                return item.getInt("IsBestAnswer", 0) == 1;
            }
        }
        return false;
    }

    // ==================== 评论 ====================

    private boolean postComment(String qid, String cid, String aid, String content) {
        String url = task.supportBaseUrl() + "/service/answer/answerGeneric.ashx?action=AddEvaluate"
                + "&aid=" + aid + "&qid=" + qid + "&cid=" + cid
                + "&content=" + SiemensCheckInTask.encode(content)
                + "&uid=" + task.currentUserId();
        task.waitBeforeSubmit("answer");
        SiemensCheckInTask.HttpResult result = task.post(url, "", task.supportHeaders(url));
        task.markSubmitted("answer");
        String response = StrUtil.blankToDefault(result.body, "").trim();
        System.out.println("[西门子] 评论最佳答案：" + SiemensCheckInTask.abbreviate(response));
        // 站点成功时返回「评论成功」；被每日次数或间隔限制时返回空。
        return response.contains("成功");
    }

    /** 页面上的 data-currentuseriscomment 是站点自己维护的「我评论过没有」，最可信。 */
    private boolean commentedBefore(String qid, String cid) {
        SiemensCheckInTask.Page page = task.follow(
                task.supportBaseUrl() + "/service/answer/solved_" + qid + "_" + cid + ".html");
        return page != null && page.body.contains("data-currentuseriscomment='True'");
    }

    private static boolean alreadyCommented(JSONObject round, String uid) {
        JSONArray commentedBy = round.getJSONArray("commentedBy");
        if (commentedBy == null) {
            return false;
        }
        for (int i = 0; i < commentedBy.size(); i++) {
            if (uid.equals(commentedBy.getStr(i))) {
                return true;
            }
        }
        return false;
    }

    private void markCommented(JSONObject round, String uid) {
        JSONArray commentedBy = round.getJSONArray("commentedBy");
        if (commentedBy == null) {
            commentedBy = new JSONArray();
            round.set("commentedBy", commentedBy);
        }
        commentedBy.put(uid);
        save();
    }

    /** 这个账号上次互评是哪天；没互评过或记录读不到时返回 null。 */
    private LocalDate lastSiblingComment(String uid) {
        load();
        JSONObject comments = store.getJSONObject("siblingComments");
        String value = comments == null ? null : comments.getStr(uid);
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 是否允许这个账号今天互评。
     * <p>
     * 先看共享的日历：三个账号必须落在同一天互评，否则会漂移成各自不同的日期，
     * 结果每天只有一两个账号能评，剩下的最佳答案一直排不上队。
     * 再看本账号自己有没有评过，保证同一天不会重复提交。
     */
    private boolean siblingCommentAllowed(String uid) {
        LocalDate today = LocalDate.now(SHANGHAI);
        if (!isSiblingCommentDay(today)) {
            return false;
        }
        LocalDate last = lastSiblingComment(uid);
        if (last == null) {
            return true;
        }
        long gap = java.time.temporal.ChronoUnit.DAYS.between(last, today);
        return gap >= SIBLING_COMMENT_INTERVAL_DAYS;
    }

    /**
     * 三个账号共用的互评日历：隔天开口，所有账号在同一天一起评。
     * <p>
     * 用轮次文件里最早的那个互评日期当锚点，之后每
     * {@link #SIBLING_COMMENT_INTERVAL_DAYS} 天开口一次。
     * 锚点固定不动，所以不管哪个容器先启动、谁先写文件，大家算出来的日期都一样。
     * 还没有任何互评记录时直接开口，让功能上线当天就能用起来。
     */
    private boolean isSiblingCommentDay(LocalDate today) {
        LocalDate anchor = commentAnchor();
        if (anchor == null) {
            return true;
        }
        long gap = java.time.temporal.ChronoUnit.DAYS.between(anchor, today);
        // gap 为负说明系统时间被往回调过，按开口处理，别把人永久卡死。
        return gap < 0 || gap % SIBLING_COMMENT_INTERVAL_DAYS == 0;
    }

    /** 互评日历的锚点：轮次文件里记录过的所有互评日期中最早的一个。 */
    private LocalDate commentAnchor() {
        load();
        JSONObject comments = store.getJSONObject("siblingComments");
        if (comments == null || comments.isEmpty()) {
            return null;
        }
        LocalDate anchor = null;
        for (String key : comments.keySet()) {
            String value = comments.getStr(key);
            if (StrUtil.isBlank(value)) {
                continue;
            }
            try {
                LocalDate day = LocalDate.parse(value.trim());
                if (anchor == null || day.isBefore(anchor)) {
                    anchor = day;
                }
            } catch (Exception e) {
                // 单条坏记录不该拖垮整个日历，跳过即可。
            }
        }
        return anchor;
    }

    private void markSiblingComment(String uid) {
        load();
        JSONObject comments = store.getJSONObject("siblingComments");
        if (comments == null) {
            comments = JSONUtil.createObj();
            store.set("siblingComments", comments);
        }
        comments.set(uid, LocalDate.now(SHANGHAI).toString());
        save();
    }

    // ==================== 账号与问答发现 ====================

    private void registerAccount(String myUid) {
        load();
        JSONArray accounts = store.getJSONArray("accounts");
        for (int i = 0; i < accounts.size(); i++) {
            if (myUid.equals(accounts.getStr(i))) {
                return;
            }
        }
        accounts.put(myUid);
        save();
        System.out.println("[西门子] 登记账号到轮次文件：" + myUid + "（共 " + accounts.size() + " 个）");
    }

    /** 已经登记过的兄弟账号 ID。 */
    private Set<String> accountIds() {
        load();
        Set<String> ids = new LinkedHashSet<>();
        JSONArray accounts = store.getJSONArray("accounts");
        for (int i = 0; i < accounts.size(); i++) {
            ids.add(accounts.getStr(i));
        }
        return ids;
    }

    /**
     * 把自己名下已回答但还没采纳的问题补进轮次文件。
     * 这一步让功能上线后立刻能用上历史问答，不依赖轮次文件从第一天开始积累。
     * 只处理最近的 {@link #DISCOVER_LIMIT} 条：每条都要额外请求回答列表，
     * 全量扫描会在账号积累很多提问后连着打几十个请求，容易触发风控。
     */
    private void discoverOwnQuestions(String myUid) {
        List<String[]> own = ownQuestions();
        if (own.isEmpty()) {
            return;
        }
        load();
        JSONArray rounds = store.getJSONArray("rounds");
        int added = 0;
        for (String[] item : own) {
            if (added >= DISCOVER_LIMIT) {
                System.out.println("[西门子] 历史问答已扫描 " + added + " 条，其余留给后续轮次");
                break;
            }
            String qid = item[0];
            String cid = item[1];
            if (findRound(rounds, qid) != null) {
                continue;
            }
            added++;
            JSONArray answers = answers(qid);
            JSONObject mine = null;
            for (int i = 0; i < answers.size(); i++) {
                JSONObject answer = answers.getJSONObject(i);
                if (answer == null || myUid.equals(answer.getStr("U_ID"))) {
                    continue;
                }
                // 已经采纳过的问答直接把状态补齐，避免重复处理。
                if (answer.getInt("IsBestAnswer", 0) == 1) {
                    mine = answer;
                    break;
                }
                if (mine == null) {
                    mine = answer;
                }
            }
            JSONObject round = JSONUtil.createObj();
            round.set("qid", qid);
            round.set("cid", cid);
            round.set("askerUid", myUid);
            round.set("askedAt", dateOf(answers.size() > 0 ? answers.getJSONObject(0).getStr("A_InputTime") : null));
            if (mine != null) {
                JSONObject answer = JSONUtil.createObj();
                answer.set("aid", mine.getStr("A_ID"));
                answer.set("uid", mine.getStr("U_ID"));
                answer.set("at", dateOf(mine.getStr("A_InputTime")));
                round.set("answer", answer);
                if (mine.getInt("IsBestAnswer", 0) == 1) {
                    round.set("adopted", true);
                    round.set("adoptedAt", dateOf(mine.getStr("A_UpdateTime")));
                }
            }
            // 已经关闭的问题（Q_Status 为 1 或 3）不可能再采纳，先标好，省得每轮重试。
            if (mine != null && !Boolean.TRUE.equals(round.getBool("adopted"))) {
                int status = parseInt(mine.getStr("Q_Status"));
                if (status == 1 || status == 3) {
                    round.set("adoptClosed", true);
                }
            }
            rounds.put(round);
        }
        trim(rounds);
        save();
    }

    /** 「我的提问」页面上的问答，返回 {qid, cid} 列表。 */
    private List<String[]> ownQuestions() {
        List<String[]> result = new ArrayList<>();
        SiemensCheckInTask.Page page = task.follow(task.supportBaseUrl() + "/MySupport/MyAnswerAsk.aspx");
        if (page == null || page.body == null) {
            return result;
        }
        Matcher matcher = QUESTION_LINK.matcher(page.body);
        Set<String> seen = new LinkedHashSet<>();
        while (matcher.find()) {
            String key = matcher.group(1) + "-" + matcher.group(2);
            if (seen.add(key)) {
                result.add(new String[]{matcher.group(1), matcher.group(2)});
            }
        }
        return result;
    }

    /** 取某个问答的全部回答，接口异常时返回空数组。 */
    private JSONArray answers(String qid) {
        String url = task.supportBaseUrl() + "/service/answer/AjaxApi/New_Answer/NewAnswerDeatil.ashx"
                + "?action=getQuestionAnswer&qid=" + qid;
        SiemensCheckInTask.HttpResult result = task.post(url, "", task.supportHeaders(url));
        if (StrUtil.isBlank(result.body) || !JSONUtil.isTypeJSONArray(result.body)) {
            return new JSONArray();
        }
        JSONArray array = JSONUtil.parseArray(result.body);
        return array == null ? new JSONArray() : array;
    }

    /** 我最近提的问题（「待解决」列表里自己的、今天提的），用来在提问成功后取回 ID。 */
    private JSONObject newestOwnQuestion(String myUid) {
        String url = task.supportBaseUrl() + "/service/answer/AjaxApi/New_Answer/NewAnswerList.ashx"
                + "?action=getAnswerListData&cid=&qType=3&qCategory=0&orderType=1&pageIndex=1&t=";
        SiemensCheckInTask.HttpResult result = task.post(url, "", task.supportHeaders(url));
        JSONObject body = SiemensCheckInTask.parseObject(result.body);
        JSONArray list = body == null ? null : body.getJSONArray("QuestionListForNewList");
        if (list == null) {
            return null;
        }
        String today = LocalDate.now(SHANGHAI).toString();
        for (int i = 0; i < list.size(); i++) {
            JSONObject item = list.getJSONObject(i);
            if (item != null && myUid.equals(item.getStr("U_ID")) && today.equals(dateOf(item.getStr("Q_InputTime")))) {
                return item;
            }
        }
        return null;
    }

    /** 我刚提交的回答：同一条问题下自己 A_ID 最大的那条（A_ID 单调递增）。 */
    private JSONObject newestOwnAnswer(String qid, String myUid) {
        JSONArray answers = answers(qid);
        JSONObject newest = null;
        for (int i = 0; i < answers.size(); i++) {
            JSONObject item = answers.getJSONObject(i);
            if (item == null || !myUid.equals(item.getStr("U_ID"))) {
                continue;
            }
            // 不按「今天」过滤：任务可能跨过零点（AI 生成慢、间隔等待），
            // 按日期比会把刚提交的回答丢掉，导致提问方永远等不到可采纳的答案。
            if (newest == null || parseInt(item.getStr("A_ID")) > parseInt(newest.getStr("A_ID"))) {
                newest = item;
            }
        }
        return newest;
    }

    // ==================== 存取 ====================

    private void load() {
        if (store != null) {
            return;
        }
        store = JSONUtil.createObj();
        store.set("accounts", new JSONArray());
        store.set("rounds", new JSONArray());
        store.set("siblingComments", JSONUtil.createObj());
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JSONObject saved = JSONUtil.parseObj(text);
            if (saved != null) {
                if (saved.getJSONArray("accounts") == null) {
                    saved.set("accounts", new JSONArray());
                }
                if (saved.getJSONArray("rounds") == null) {
                    saved.set("rounds", new JSONArray());
                }
                if (saved.getJSONObject("siblingComments") == null) {
                    saved.set("siblingComments", JSONUtil.createObj());
                }
                store = saved;
            }
        } catch (Exception e) {
            // 文件损坏时按空文件处理，下一轮重新发现即可。
            System.out.println("[西门子] 轮次文件解析失败，按空处理：" + e.getMessage());
        }
    }

    private void save() {
        if (file == null || store == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            // 三个容器共写同一个文件，先写临时文件再改名，避免读到写了一半的 JSON。
            Path temp = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
            Files.write(temp, store.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.out.println("[西门子] 轮次文件写入失败：" + e.getMessage());
        }
    }

    private static JSONObject findRound(JSONArray rounds, String qid) {
        for (int i = 0; i < rounds.size(); i++) {
            JSONObject round = rounds.getJSONObject(i);
            if (round != null && qid.equals(round.getStr("qid"))) {
                return round;
            }
        }
        return null;
    }

    /** 只保留最近的若干条，旧的已经采纳并评论完，没必要一直留着。 */
    private static void trim(JSONArray rounds) {
        while (rounds.size() > MAX_ROUNDS) {
            rounds.remove(0);
        }
    }

    /**
     * 发现历史问答时最多处理几条。
     * 「我的提问」里每一条都要额外请求一次回答列表，条目多了会连着打几十个请求，
     * 容易触发站点风控。这里只看最近的几条，其余交给轮次文件逐轮积累。
     */
    private static final int DISCOVER_LIMIT = 5;

    /** 把 /Date(毫秒)/ 转成北京时间的日期，解析不出返回空串。 */
    static String dateOf(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        Matcher matcher = DATE_MS.matcher(raw);
        if (!matcher.find()) {
            return "";
        }
        try {
            long millis = Long.parseLong(matcher.group(1));
            return Instant.ofEpochMilli(millis).atZone(SHANGHAI).toLocalDate().toString();
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }
}
