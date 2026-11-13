package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 技术论坛相关动作：收藏帖子、回帖、评论帖子、发探讨帖、发故事帖，
 * 以及定位可操作的帖子（最新帖、作者 ID）。
 */
final class SiemensForumActions {

    /** 每日任务默认发在 S7-1500 版区。 */
    static final String BOARD_ID = "66";
    /** 帖子类型：探讨帖。 */
    static final String TYPE_DISCUSS = "4";
    /** 帖子类型：故事帖。 */
    static final String TYPE_STORY = "3";
    /** 故事帖走「谈天说地」版区和对应子分类。 */
    private static final String STORY_BOARD_ID = "25";
    private static final String STORY_SORT_ID = "123";
    private static final String GROUP = "forum";

    private final SiemensCheckInTask task;

    SiemensForumActions(SiemensCheckInTask task) {
        this.task = task;
    }

    /** 收藏论坛帖子。 */
    void collectPost() {
        String postId = latestForumPostId();
        if (StrUtil.isBlank(postId)) {
            System.out.println("[西门子] 没有可收藏的帖子");
            return;
        }
        int split = postId.indexOf('-');
        String articleId = split > 0 ? postId.substring(0, split) : postId;
        String boardId = split > 0 ? postId.substring(split + 1) : BOARD_ID;
        String page = task.supportBaseUrl() + "/club/bbs/addfavour.aspx?a_id=" + articleId
                + "&b_id=" + boardId + "&s_id=0&isNew=1";
        SiemensCheckInTask.Page form = task.follow(page);
        if (form == null) {
            return;
        }
        String body = "&__VIEWSTATE=" + SiemensCheckInTask.encode(
                SiemensCheckInTask.inputValue(form.body, "__VIEWSTATE"))
                + "&__VIEWSTATEGENERATOR=" + SiemensCheckInTask.encode(
                SiemensCheckInTask.inputValue(form.body, "__VIEWSTATEGENERATOR"))
                + "&presetProperties=&tagSave=&tagSaveType=&tagName=";
        SiemensCheckInTask.HttpResult result = task.post(page, body, task.supportHeaders(page));
        System.out.println("[西门子] 收藏帖子 HTTP " + result.status);
    }

    /** 在最新帖子里回帖。 */
    void replyPost() {
        String postId = latestForumPostId();
        if (StrUtil.isBlank(postId)) {
            return;
        }
        int split = postId.indexOf('-');
        String articleId = split > 0 ? postId.substring(0, split) : postId;
        String boardId = split > 0 ? postId.substring(split + 1) : BOARD_ID;
        String content = task.generateUnique("请用中文写一段西门子技术论坛的回帖，"
                + "针对 S7-300 升级到 S7-1500 的选型讨论补充一条实用建议，150字以内，口语，不要分点。", "reply");
        if (StrUtil.isBlank(content)) {
            return;
        }
        submitPost(articleId, boardId, TYPE_DISCUSS, "回复：升级选型讨论", content);
    }

    /** 评论最新帖子。 */
    void commentPost() {
        String postId = latestForumPostId();
        if (StrUtil.isBlank(postId)) {
            return;
        }
        int split = postId.indexOf('-');
        String articleId = split > 0 ? postId.substring(0, split) : postId;
        String boardId = split > 0 ? postId.substring(split + 1) : BOARD_ID;
        String content = task.generateUnique(
                "请用中文写一条西门子技术论坛的短评论，60字以内，一句到两句，口语，不要分点。", "postcomment");
        if (StrUtil.isBlank(content)) {
            return;
        }
        String url = task.supportBaseUrl() + "/club/bbs/bbsGeneric.ashx?action=AddEvaluate"
                + "&aid=" + articleId + "&bid=" + boardId
                + "&author=" + authorOf(articleId) + "&content=" + SiemensCheckInTask.encode(content)
                + "&uid=" + task.currentUserId();
        task.waitBeforeSubmit(GROUP);
        SiemensCheckInTask.HttpResult result = task.post(url, "", task.supportHeaders(url));
        task.markSubmitted(GROUP);
        System.out.println("[西门子] 评论帖子：" + SiemensCheckInTask.abbreviate(result.body));
    }

    /** 发一条探讨帖。 */
    void publishPost() {
        String title = task.generateUnique(
                "请用中文给西门子技术论坛想一个关于 S7-1500 与 PROFINET 应用的技术探讨帖标题，15字以内，只输出标题。",
                "posttitle");
        String content = task.generateUnique(
                "请用中文写一段西门子技术论坛探讨帖正文，主题围绕 S7-1500 和 PROFINET 的实际应用经验，"
                        + "300字以内，口语，不要分点，不要引用来源。", "postbody");
        if (StrUtil.isBlank(title) || StrUtil.isBlank(content)) {
            return;
        }
        submitPost("0", BOARD_ID, TYPE_DISCUSS, SiemensCheckInTask.cleanTitle(title), content);
    }

    /** 发一条故事帖。故事帖要站点审核，提交后不会立即计入任务。 */
    void publishStory() {
        String title = task.generateUnique(
                "请用中文给西门子技术论坛想一个现场调试故事的标题，15字以内，只输出标题。", "storytitle");
        String content = task.generateUnique(
                "请用中文写一篇西门子技术论坛的故事帖，讲一个工程师现场调试的真实感故事，"
                        + "350字以内，第一人称口语，不要分点，不要引用来源。", "storybody");
        if (StrUtil.isBlank(title) || StrUtil.isBlank(content)) {
            return;
        }
        submitStory(SiemensCheckInTask.cleanTitle(title), content);
    }

    private void submitStory(String title, String content) {
        task.waitBeforeSubmit(GROUP);
        JSONObject request = buildRequest("0", STORY_BOARD_ID, TYPE_STORY, title, content, STORY_SORT_ID);
        boolean ok = commit(request);
        System.out.println("[西门子] 发故事帖" + (ok ? "成功，等待审核" : "失败"));
    }

    private void submitPost(String articleId, String boardId, String type, String title, String content) {
        task.waitBeforeSubmit(GROUP);
        JSONObject request = buildRequest(articleId, boardId, type, title, content, "0");
        boolean ok = commit(request);
        System.out.println("[西门子] 发帖" + (ok ? "成功" : "失败"));
    }

    private JSONObject buildRequest(String articleId, String boardId, String type,
                                    String title, String content, String sort) {
        JSONObject request = JSONUtil.createObj();
        request.set("ArticleId", Integer.parseInt(articleId));
        request.set("BoardId", boardId);
        request.set("AType", type);
        request.set("IsOnLineRead", 0);
        request.set("Title", title);
        request.set("IsJump", 0);
        request.set("JumpUrl", "");
        request.set("FaceId", "1");
        request.set("Content", "<p>" + content + "</p>");
        request.set("Sort", sort);
        request.set("Category", "");
        request.set("Code", "");
        request.set("AtUsers", "");
        request.set("Quote", "");
        request.set("QuoteAId", "");
        return request;
    }

    /** 提交帖子，返回站点是否接受。 */
    private boolean commit(JSONObject request) {
        String url = task.supportBaseUrl() + "/club/bbs/AjaxApi/PostCommitAjax.ashx";
        SiemensCheckInTask.HttpResult result = task.post(url, "request=" + SiemensCheckInTask.encode(request.toString()),
                task.supportHeaders(url));
        task.markSubmitted(GROUP);
        JSONObject body = SiemensCheckInTask.parseObject(result.body);
        JSONObject head = body == null ? null : body.getJSONObject("Head");
        boolean ok = head != null && Boolean.TRUE.equals(head.getBool("Ret"));
        if (!ok) {
            System.out.println("[西门子] 发帖被拒绝：" + (head == null
                    ? SiemensCheckInTask.abbreviate(result.body) : head.getStr("Message")));
        }
        task.markTaskFailed(!ok);
        return ok;
    }

    /** 从「最新发帖」列表取一条帖子，返回 articleId-boardId。 */
    private String latestForumPostId() {
        SiemensCheckInTask.Page page = task.follow(task.supportBaseUrl() + "/club/bbs/bbs_latest.aspx");
        if (page == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("post_(\\d+)_(\\d+)_\\d+_\\d+\\.html").matcher(page.body);
        return matcher.find() ? matcher.group(1) + "-" + matcher.group(2) : null;
    }

    private String authorOf(String articleId) {
        SiemensCheckInTask.Page page = task.follow(
                task.supportBaseUrl() + "/club/bbs/post_" + articleId + "_" + BOARD_ID + "_0_1.html");
        if (page == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("user_disp\\.aspx\\?u_id=(\\d+)").matcher(page.body);
        return matcher.find() ? matcher.group(1) : "";
    }
}
