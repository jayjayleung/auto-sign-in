package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.jsoup.Jsoup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 找答案相关动作：收藏问答、回答、评论最佳答案、提问，
 * 以及定位可操作的问答（待解决、带最佳答案、取标题）。
 */
final class SiemensAnswerActions {

    /** 自动化系统 / S7-1500 系列 / S7-1500 的版区 ID。 */
    static final String BOARD_ID = "1260";
    private static final String GROUP = "answer";
    /** 收藏夹接口，tabType 1 是问答、2 是帖子；ContentId 就是问答 ID。 */
    private static final String FAVORITES_URL = "/MySupport/AjaxAPI/CutomTags/CustomTagsCollectData.ashx";
    /** 收藏问答最多往前找几条，避免第一条已收藏时无解。 */
    private static final int FAVORITE_CANDIDATES = 6;

    private final SiemensCheckInTask task;

    SiemensAnswerActions(SiemensCheckInTask task) {
        this.task = task;
    }

    /**
     * 收藏一条问答。列表页的收藏入口已下线，这里直接提交弹窗表单。
     * <p>
     * 站点只允许收藏「已解决」的问答：对待解决的问题提交 Favorite.aspx 会返回「参数不正确」
     * 或者静默不生效，所以候选从「新解决的问题」里取，而不是待解决问题。
     */
    String collectQuestion(String questionId) {
        Set<String> favorites = favoriteQuestionIds();
        List<String> candidates = new ArrayList<>();
        for (String candidate : solvedQuestions(FAVORITE_CANDIDATES)) {
            String id = candidate.substring(0, candidate.indexOf('-'));
            if (favorites == null || !favorites.contains(id)) {
                candidates.add(id);
            }
        }
        if (candidates.isEmpty()) {
            String fallback = StrUtil.blankToDefault(questionId, latestQuestionId());
            if (StrUtil.isNotBlank(fallback)) {
                candidates.add(fallback);
            }
        }
        if (candidates.isEmpty()) {
            System.out.println("[西门子] 没有可收藏的问答");
            return questionId;
        }
        String target = null;
        for (String candidate : candidates) {
            if (postFavoriteForm(candidate)) {
                target = candidate;
                break;
            }
            System.out.println("[西门子] 收藏问答 " + candidate + " 未生效，换下一条");
        }
        if (target == null) {
            target = candidates.get(0);
            System.out.println("[西门子] 收藏问答全部未生效：" + candidates);
            task.markTaskFailed(true);
            return target;
        }
        if (collectionTaskDone()) {
            System.out.println("[西门子] 收藏问答成功：" + target);
        } else {
            // 收藏进去了但任务状态没翻，站点统计可能有延迟，这里只记录不判失败。
            System.out.println("[西门子] 收藏问答已提交，任务状态未更新：" + target);
        }
        return target;
    }

    /** 收藏问答是每日任务，任务状态变 2 才算真的成功。 */
    private boolean collectionTaskDone() {
        return task.dailyTaskCompleted(SiemensCheckInTask.TASK_COLLECT_QUESTION);
    }

    /**
     * 提交收藏弹窗表单。只提交 __VIEWSTATE 和 __VIEWSTATEGENERATOR，
     * 带上 tagSave/tagSaveType 会被判「参数不正确」。
     * 返回 false 有两种情况：站点明确报错，或者原样返回表单（静默不生效）。
     */
    private boolean postFavoriteForm(String questionId) {
        String page = task.supportBaseUrl() + "/service/answer/Favorite.aspx?QID=" + questionId;
        SiemensCheckInTask.Page form = task.follow(page);
        if (form == null) {
            return false;
        }
        String viewState = SiemensCheckInTask.inputValue(form.body, "__VIEWSTATE");
        String viewStateGenerator = SiemensCheckInTask.inputValue(form.body, "__VIEWSTATEGENERATOR");
        if (StrUtil.isBlank(viewState) || StrUtil.isBlank(viewStateGenerator)) {
            System.out.println("[西门子] 收藏问答页面没有表单");
            return false;
        }
        String body = "&__VIEWSTATE=" + SiemensCheckInTask.encode(viewState)
                + "&__VIEWSTATEGENERATOR=" + SiemensCheckInTask.encode(viewStateGenerator);
        SiemensCheckInTask.HttpResult result = task.post(page, body, task.supportHeaders(page));
        System.out.println("[西门子] 收藏问答 HTTP " + result.status);
        if (result.status != 200 || result.body == null) {
            return false;
        }
        if (result.body.contains("参数不正确")) {
            return false;
        }
        // 原样返回同一张表单说明站点根本没处理，属于静默失败。
        return !result.body.equals(form.body);
    }

    /** 读收藏夹里的问答 ID；接口取不到时返回 null，调用方按「无法判断」处理。 */
    private Set<String> favoriteQuestionIds() {
        String url = task.supportBaseUrl() + FAVORITES_URL
                + "?tabType=1&isCheck=&searchContent=&sysCustomTag=&perCustomTag=&pageIndex=1&pageSize=100";
        String referer = task.supportBaseUrl() + "/MySupport/MyCollection.aspx";
        SiemensCheckInTask.HttpResult result = task.post(url, "", task.supportHeaders(referer));
        JSONObject body = SiemensCheckInTask.parseObject(result.body);
        if (body == null) {
            return null;
        }
        Set<String> ids = new HashSet<>();
        JSONArray data = body.getJSONArray("Data");
        if (data != null) {
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = data.getJSONObject(i);
                if (item != null && item.get("ContentId") != null) {
                    ids.add(item.getStr("ContentId"));
                }
            }
        }
        return ids;
    }

    /** 回答一条待解决问题。跳过自己提的问题，站点禁止自问自答。 */
    void answerQuestion() {
        String target = null;
        for (String candidate : unsolvedQuestions(FAVORITE_CANDIDATES)) {
            String asker = candidate.substring(candidate.lastIndexOf('-') + 1);
            if (StrUtil.isBlank(task.currentUserId()) || !task.currentUserId().equals(asker)) {
                target = candidate.substring(0, candidate.lastIndexOf('-'));
                break;
            }
        }
        if (StrUtil.isBlank(target)) {
            System.out.println("[西门子] 没有待回答的问题");
            return;
        }
        String questionId = target.substring(0, target.indexOf('-'));
        String categoryId = target.substring(target.indexOf('-') + 1);
        String prompt = "请用中文回答西门子找答案里的一个技术问题，问题标题是："
                + questionTitle(questionId, categoryId)
                + "。回答要具体、可执行，200字以内，不要标题，不要分点。";
        String answer = task.generateUnique(prompt, "answer");
        if (StrUtil.isBlank(answer)) {
            return;
        }
        String body = "A_Content=" + SiemensCheckInTask.encode(answer);
        body += "&A_Reference=&Q_ID=" + questionId + "&cid=" + categoryId
                + "&PType=answer&ImageIDs=";
        String url = task.supportBaseUrl() + "/service/answer/solution_P.aspx";
        task.waitBeforeSubmit(GROUP);
        SiemensCheckInTask.HttpResult result = task.post(url, body, task.supportHeaders(url));
        task.markSubmitted(GROUP);
        System.out.println("[西门子] 回答 HTTP " + result.status);
    }

    /** 评论一条已采纳的最佳答案。 */
    void commentBestAnswer() {
        String solved = firstSolvedQuestionWithBestAnswer();
        if (StrUtil.isBlank(solved)) {
            System.out.println("[西门子] 没有找到带最佳答案的问题");
            return;
        }
        String[] parts = solved.split("-");
        String questionId = parts[0];
        String categoryId = parts[1];
        String answerId = parts[2];
        String comment = task.generateUnique("请用中文写一条对西门子找答案里最佳答案的评论，"
                + "评论要结合答案内容补充一个实际经验点，100字以内，口语，不要分点。", "bestcomment");
        if (StrUtil.isBlank(comment)) {
            return;
        }
        String url = task.supportBaseUrl() + "/service/answer/answerGeneric.ashx?action=AddEvaluate"
                + "&aid=" + answerId + "&qid=" + questionId + "&cid=" + categoryId
                + "&content=" + SiemensCheckInTask.encode(comment) + "&uid=" + task.currentUserId();
        task.waitBeforeSubmit(GROUP);
        SiemensCheckInTask.HttpResult result = task.post(url, "", task.supportHeaders(url));
        task.markSubmitted(GROUP);
        System.out.println("[西门子] 评论最佳答案：" + SiemensCheckInTask.abbreviate(result.body));
    }

    /** 在找答案提一个问题。表单字段要和页面脚本提交的完全一致，少一个就报「数据错误」。 */
    void askQuestion() {
        String title = task.generateUnique("请用中文给西门子找答案想一个 S7-1500 相关的技术提问标题，30字以内，只输出标题。",
                "asktitle");
        String content = task.generateUnique("请用中文写一段西门子找答案的问题描述，围绕 S7-1500 组态或通讯的实际疑问，"
                + "200字以内，具体到场景，不要分点。", "askbody");
        if (StrUtil.isBlank(title) || StrUtil.isBlank(content)) {
            return;
        }
        boolean ok = submitQuestion(SiemensCheckInTask.cleanTitle(title), content);
        if (!ok) {
            // 站点偶发拒收时换一个标题再试一次，仍然失败就如实标记。
            System.out.println("[西门子] 提问被拒，重试一次");
            String retry = task.generateUnique("请用中文给西门子找答案想一个 S7-1500 相关的技术提问标题，"
                    + "换个角度，30字以内，只输出标题。", "asktitle");
            if (StrUtil.isNotBlank(retry)) {
                ok = submitQuestion(SiemensCheckInTask.cleanTitle(retry), content);
            }
        }
        if (!ok) {
            task.markTaskFailed(true);
        }
    }

    private boolean submitQuestion(String title, String content) {
        String page = task.supportBaseUrl() + "/service/answer/questionIn.aspx";
        SiemensCheckInTask.Page form = task.follow(page);
        if (form == null) {
            return false;
        }
        String body = "__VIEWSTATE=" + SiemensCheckInTask.encode(SiemensCheckInTask.inputValue(form.body, "__VIEWSTATE"))
                + "&__VIEWSTATEGENERATOR="
                + SiemensCheckInTask.encode(SiemensCheckInTask.inputValue(form.body, "__VIEWSTATEGENERATOR"))
                // 页面脚本会把下面这些字段全部带上，缺字段会被判「数据错误，请重新提交」。
                + "&toAskType=ask&Platform=4&AskSource=0&toUserId=0"
                + "&q_title=" + SiemensCheckInTask.encode(title)
                + "&q_content=" + SiemensCheckInTask.encode(content)
                + "&siosDocType=1&language=1&Q_Reward=1"
                + "&q_type=4&cid=" + BOARD_ID + "&cid1=0&cid2=0&qtype=4"
                + "&isMail=1&isAward=0&isSMS=1&ImageIDs="
                + "&ArticleIdOfCNC=0&ArticleIdOfGMC=0&WebinarId=0&WebinarIdOfGMC=0";
        task.waitBeforeSubmit(GROUP);
        SiemensCheckInTask.HttpResult result = task.post(task.supportBaseUrl() + "/service/answer/question_commit.aspx",
                body, task.supportHeaders(form.url));
        task.markSubmitted(GROUP);
        String response = StrUtil.blankToDefault(result.body, "");
        boolean rejected = response.contains("数据错误") || response.contains("参数不正确")
                || response.contains("请选择问题类别");
        System.out.println("[西门子] 提问：" + SiemensCheckInTask.abbreviate(response));
        return !rejected;
    }

    /**
     * 取「新解决的问题」列表（已解决），返回 questionId-categoryId-askerId，最多 limit 条。
     * t=1 才是已解决列表；t 为空是待解决问题，两者返回完全不同的数据。
     */
    private List<String> solvedQuestions(int limit) {
        List<String> result = new ArrayList<>();
        String url = task.supportBaseUrl() + "/service/answer/AjaxApi/New_Answer/NewAnswerList.ashx?"
                + "action=getAnswerListData&cid=&qType=all&qCategory=0&orderType=1&pageIndex=1&t=1";
        SiemensCheckInTask.HttpResult response = task.post(url, "", task.supportHeaders(url));
        JSONArray list = questionList(SiemensCheckInTask.parseObject(response.body));
        if (list == null) {
            return result;
        }
        for (int i = 0; i < list.size() && result.size() < limit; i++) {
            JSONObject item = list.getJSONObject(i);
            if (item == null || StrUtil.isBlank(item.getStr("Q_ID"))) {
                continue;
            }
            result.add(item.getStr("Q_ID") + "-" + item.getStr("C_ID") + "-" + item.getStr("U_ID"));
        }
        return result;
    }

    /** 取未解决问题列表，返回 questionId-categoryId-askerId，最多 limit 条。 */
    private List<String> unsolvedQuestions(int limit) {
        List<String> result = new ArrayList<>();
        // t 为空（或 0）才是「待解决问题」；t=1 起是「新解决的问题」，两者不能混用。
        String body = "action=getAnswerListData&cid=&qType=3&qCategory=0&orderType=1&pageIndex=1&t=";
        String url = task.supportBaseUrl() + "/service/answer/AjaxApi/New_Answer/NewAnswerList.ashx?" + body;
        SiemensCheckInTask.HttpResult response = task.post(url, "", task.supportHeaders(url));
        JSONArray list = questionList(SiemensCheckInTask.parseObject(response.body));
        if (list == null) {
            return result;
        }
        for (int i = 0; i < list.size() && result.size() < limit; i++) {
            JSONObject item = list.getJSONObject(i);
            if (item == null || StrUtil.isBlank(item.getStr("Q_ID"))) {
                continue;
            }
            result.add(item.getStr("Q_ID") + "-" + item.getStr("C_ID") + "-" + item.getStr("U_ID"));
        }
        return result;
    }

    /**
     * 找一条有最佳答案的已解决问题。
     * 必须带 t=1（新解决的问题），否则接口按默认的待解决列表返回，永远找不到最佳答案。
     */
    private String firstSolvedQuestionWithBestAnswer() {
        for (int page = 1; page <= 2; page++) {
            String url = task.supportBaseUrl() + "/service/answer/AjaxApi/New_Answer/NewAnswerList.ashx?"
                    + "action=getAnswerListData&cid=0&qType=all&qCategory=0&orderType=1&pageIndex=" + page + "&t=1";
            SiemensCheckInTask.HttpResult result = task.post(url, "", task.supportHeaders(url));
            JSONArray list = questionList(SiemensCheckInTask.parseObject(result.body));
            if (list == null) {
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                JSONObject item = list.getJSONObject(i);
                String questionId = item.getStr("Q_ID");
                String categoryId = item.getStr("C_ID");
                String detailUrl = task.supportBaseUrl() + "/service/answer/solved_"
                        + questionId + "_" + categoryId + ".html";
                SiemensCheckInTask.Page detail = task.follow(detailUrl);
                String answerId = detail == null ? null
                        : SiemensCheckInTask.attributeValue(detail.body, "data-bestanswerid");
                if (StrUtil.isNotBlank(answerId) && !"0".equals(answerId)) {
                    return questionId + "-" + categoryId + "-" + answerId;
                }
            }
        }
        return null;
    }

    private String questionTitle(String questionId, String categoryId) {
        SiemensCheckInTask.Page detail = task.follow(
                task.supportBaseUrl() + "/service/answer/solve_" + questionId + "_" + categoryId + ".html");
        return detail == null ? "西门子产品使用问题"
                : SiemensCheckInTask.readableText(
                        Jsoup.parse(detail.body).selectFirst("h4.singleLine") == null
                                ? null
                                : Jsoup.parse(detail.body).selectFirst("h4.singleLine").text());
    }

    private String latestQuestionId() {
        List<String> candidates = unsolvedQuestions(1);
        return candidates.isEmpty() ? null : candidates.get(0).substring(0, candidates.get(0).indexOf('-'));
    }

    private static JSONArray questionList(JSONObject body) {
        return body == null ? null : body.getJSONArray("QuestionListForNewList");
    }
}
