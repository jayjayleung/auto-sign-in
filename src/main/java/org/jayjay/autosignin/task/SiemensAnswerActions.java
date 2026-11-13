package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.jsoup.Jsoup;

import java.util.Map;
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

    private final SiemensCheckInTask task;

    SiemensAnswerActions(SiemensCheckInTask task) {
        this.task = task;
    }

    /** 收藏一条问答。列表页的收藏入口已下线，这里直接提交弹窗表单。 */
    String collectQuestion(String questionId) {
        String qid = StrUtil.blankToDefault(questionId, latestQuestionId());
        if (StrUtil.isBlank(qid)) {
            System.out.println("[西门子] 没有可收藏的问答");
            return questionId;
        }
        String page = task.supportBaseUrl() + "/service/answer/Favorite.aspx?QID=" + qid;
        SiemensCheckInTask.Page form = task.follow(page);
        if (form == null) {
            return questionId;
        }
        String viewState = SiemensCheckInTask.inputValue(form.body, "__VIEWSTATE");
        String viewStateGenerator = SiemensCheckInTask.inputValue(form.body, "__VIEWSTATEGENERATOR");
        if (StrUtil.isBlank(viewState) || StrUtil.isBlank(viewStateGenerator)) {
            System.out.println("[西门子] 收藏问答页面没有表单");
            return questionId;
        }
        // tagSave 必须完全不提交，提交空串也会被判「参数不正确」。
        String body = "&__VIEWSTATE=" + SiemensCheckInTask.encode(viewState)
                + "&__VIEWSTATEGENERATOR=" + SiemensCheckInTask.encode(viewStateGenerator);
        SiemensCheckInTask.HttpResult result = task.post(page, body, task.supportHeaders(page));
        System.out.println("[西门子] 收藏问答 HTTP " + result.status);
        return questionId;
    }

    /** 回答一条待解决问题。 */
    void answerQuestion() {
        String target = firstUnsolvedQuestion();
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

    /** 在找答案提一个问题。 */
    void askQuestion() {
        String title = task.generateUnique("请用中文给西门子找答案想一个 S7-1500 相关的技术提问标题，30字以内，只输出标题。",
                "asktitle");
        String content = task.generateUnique("请用中文写一段西门子找答案的问题描述，围绕 S7-1500 组态或通讯的实际疑问，"
                + "200字以内，具体到场景，不要分点。", "askbody");
        if (StrUtil.isBlank(title) || StrUtil.isBlank(content)) {
            return;
        }
        String page = task.supportBaseUrl() + "/service/answer/questionIn.aspx";
        SiemensCheckInTask.Page form = task.follow(page);
        if (form == null) {
            return;
        }
        String body = "__VIEWSTATE=" + SiemensCheckInTask.encode(SiemensCheckInTask.inputValue(form.body, "__VIEWSTATE"))
                + "&__VIEWSTATEGENERATOR="
                + SiemensCheckInTask.encode(SiemensCheckInTask.inputValue(form.body, "__VIEWSTATEGENERATOR"))
                + "&toAskType=ask&Platform=4&AskSource=0&toUserId=0"
                + "&q_title=" + SiemensCheckInTask.encode(SiemensCheckInTask.cleanTitle(title))
                + "&q_content=" + SiemensCheckInTask.encode(content)
                + "&q_type=4&cid=" + BOARD_ID + "&cid1=&cid2=&qtype=4&language=zh";
        task.waitBeforeSubmit(GROUP);
        SiemensCheckInTask.HttpResult result = task.post(task.supportBaseUrl() + "/service/answer/question_commit.aspx",
                body, task.supportHeaders(page));
        task.markSubmitted(GROUP);
        System.out.println("[西门子] 提问：" + SiemensCheckInTask.abbreviate(result.body));
    }

    /** 找一条还没解决的问答，返回 questionId-categoryId。 */
    private String firstUnsolvedQuestion() {
        String body = "action=getAnswerListData&cid=&qType=3&qCategory=0&orderType=1&pageIndex=1&t=";
        String url = task.supportBaseUrl() + "/service/answer/AjaxApi/New_Answer/NewAnswerList.ashx?" + body;
        SiemensCheckInTask.HttpResult result = task.post(url, "", task.supportHeaders(url));
        JSONArray list = questionList(SiemensCheckInTask.parseObject(result.body));
        if (list == null || list.isEmpty()) {
            return null;
        }
        JSONObject first = list.getJSONObject(0);
        return first.getStr("Q_ID") + "-" + first.getStr("C_ID");
    }

    /** 找一条有最佳答案的已解决问题，返回 questionId-categoryId-answerId。 */
    private String firstSolvedQuestionWithBestAnswer() {
        for (int page = 1; page <= 3; page++) {
            String url = task.supportBaseUrl() + "/service/answer/AjaxApi/New_Answer/NewAnswerList.ashx?"
                    + "action=getAnswerListData&cid=&qType=4&qCategory=0&orderType=1&pageIndex=" + page + "&t=";
            SiemensCheckInTask.HttpResult result = task.post(url, "", task.supportHeaders(url));
            JSONArray list = questionList(SiemensCheckInTask.parseObject(result.body));
            if (list == null) {
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                JSONObject item = list.getJSONObject(i);
                String questionId = item.getStr("Q_ID");
                String categoryId = item.getStr("C_ID");
                String detailUrl = task.supportBaseUrl() + "/service/answer/solved_" + questionId + "_" + categoryId + ".html";
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
        String target = firstUnsolvedQuestion();
        return target == null ? null : target.substring(0, target.indexOf('-'));
    }

    private static JSONArray questionList(JSONObject body) {
        return body == null ? null : body.getJSONArray("QuestionListForNewList");
    }
}
