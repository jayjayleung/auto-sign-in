package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** 收藏帖子时最多往前看几条，避免最新帖已收藏时无解。 */
    private static final int FAVORITE_CANDIDATES = 6;
    /**
     * 探讨帖的子分类（帖子类型）。S7-1500 版区要求必须选一个，不传会被拒「请选择帖子类别」。
     * 85 是「通信」。
     */
    private static final String DISCUSS_SORT_ID = "85";
    private static final String GROUP = "forum";
    /** 站点返回的「帖子类型」下拉项，value 是类别 ID。 */
    private static final Pattern SORT_OPTION = Pattern.compile("<option\\s+value=\"(\\d+)\"\\s*>([^<]*)</option>");

    private final SiemensCheckInTask task;

    SiemensForumActions(SiemensCheckInTask task) {
        this.task = task;
    }

    /** 收藏论坛帖子。跳过已经收藏过的帖子，标签必须带上（站点不认空标签）。 */
    void collectPost() {
        Set<String> favorites = favoritedArticleIds();
        String postId = null;
        for (String candidate : latestForumPostIds(FAVORITE_CANDIDATES)) {
            String articleId = candidate.substring(0, candidate.indexOf('-'));
            if (favorites == null || !favorites.contains(articleId)) {
                postId = candidate;
                break;
            }
        }
        if (StrUtil.isBlank(postId)) {
            // 全部已收藏时退回最新一条，站点会提示「已经收藏过」。
            postId = latestForumPostId();
        }
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
        if (form.body.contains("已经收藏")) {
            System.out.println("[西门子] 帖子 " + articleId + " 之前已收藏");
            return;
        }
        String tag = SiemensAnswerActions.favoriteTag();
        String body = "&__VIEWSTATE=" + SiemensCheckInTask.encode(
                SiemensCheckInTask.inputValue(form.body, "__VIEWSTATE"))
                + "&__VIEWSTATEGENERATOR=" + SiemensCheckInTask.encode(
                SiemensCheckInTask.inputValue(form.body, "__VIEWSTATEGENERATOR"))
                + "&presetProperties=&tagSave=" + SiemensCheckInTask.encode(tag)
                + "&tagSaveType=0&tagName=" + SiemensCheckInTask.encode(tag);
        SiemensCheckInTask.HttpResult result = task.post(page, body, task.supportHeaders(page));
        System.out.println("[西门子] 收藏帖子 HTTP " + result.status);
        boolean done = Boolean.TRUE.equals(task.taskStatus(SiemensCheckInTask.TASK_COLLECT_POST));
        if (!done) {
            System.out.println("[西门子] 收藏帖子未生效：" + articleId);
            task.markTaskFailed(true);
        }
    }

    /** 读收藏夹里的帖子 ID；接口取不到时返回 null，调用方按「无法判断」处理。 */
    private Set<String> favoritedArticleIds() {
        String url = task.supportBaseUrl() + "/MySupport/AjaxAPI/CutomTags/CustomTagsCollectData.ashx"
                + "?tabType=2&isCheck=&searchContent=&sysCustomTag=&perCustomTag=&pageIndex=1&pageSize=100";
        SiemensCheckInTask.HttpResult result = task.post(url, "",
                task.supportHeaders(task.supportBaseUrl() + "/MySupport/MyCollection.aspx"));
        cn.hutool.json.JSONObject body = SiemensCheckInTask.parseObject(result.body);
        if (body == null) {
            return null;
        }
        Set<String> ids = new HashSet<>();
        cn.hutool.json.JSONArray data = body.getJSONArray("Data");
        if (data != null) {
            for (int i = 0; i < data.size(); i++) {
                cn.hutool.json.JSONObject item = data.getJSONObject(i);
                if (item != null && item.get("ContentId") != null) {
                    ids.add(item.getStr("ContentId"));
                }
            }
        }
        return ids;
    }

    /** 取最新帖子列表里的前 limit 条，返回 articleId-boardId。 */
    private List<String> latestForumPostIds(int limit) {
        List<String> result = new ArrayList<>();
        SiemensCheckInTask.Page page = task.follow(task.supportBaseUrl() + "/club/bbs/bbs_latest.aspx");
        if (page == null) {
            return result;
        }
        Matcher matcher = Pattern.compile("post_(\\d+)_(\\d+)_\\d+_\\d+\\.html").matcher(page.body);
        Set<String> seen = new HashSet<>();
        while (matcher.find() && result.size() < limit) {
            String id = matcher.group(1) + "-" + matcher.group(2);
            if (seen.add(id)) {
                result.add(id);
            }
        }
        return result;
    }

    /** 在最新帖子里回帖。跳过自己的帖子，站点禁止自问自答。 */
    void replyPost() {
        String uid = task.currentUserId();
        String postId = null;
        for (String candidate : latestForumPostIds(FAVORITE_CANDIDATES)) {
            if (!candidate.startsWith(uid + "-")) {
                postId = candidate;
                break;
            }
        }
        if (StrUtil.isBlank(postId)) {
            postId = latestForumPostId();
        }
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
        submitPost(articleId, boardId, TYPE_DISCUSS, "回复：升级选型讨论", content, "0");
    }

    /** 评论帖子。跳过自己和已经评论过的帖子，否则站点直接回 false。 */
    void commentPost() {
        String uid = task.currentUserId();
        String postId = null;
        for (String candidate : latestForumPostIds(FAVORITE_CANDIDATES)) {
            if (candidate.startsWith(uid + "-")) {
                continue;
            }
            int split = candidate.indexOf('-');
            String articleId = candidate.substring(0, split);
            String boardId = candidate.substring(split + 1);
            SiemensCheckInTask.Page post = task.follow(task.supportBaseUrl()
                    + "/club/bbs/post_" + articleId + "_" + boardId + "_0_1.html");
            if (post == null) {
                continue;
            }
            Matcher author = Pattern.compile("user_disp\\.aspx\\?u_id=(\\d+)").matcher(post.body);
            String authorId = author.find() ? author.group(1) : "";
            long mine = Pattern.compile("u_id=\"" + uid + "\"").matcher(post.body).results().count();
            // 别人的帖子、且我在页面上只出现 1 次（说明还没评论过）。
            if (!authorId.equals(uid) && mine <= 1) {
                postId = candidate;
                break;
            }
        }
        if (StrUtil.isBlank(postId)) {
            System.out.println("[西门子] 没有可评论的帖子");
            return;
        }
        int split = postId.indexOf('-');
        String articleId = postId.substring(0, split);
        String boardId = postId.substring(split + 1);
        String content = task.generateUnique(
                "请用中文写一条西门子技术论坛的短评论，60字以内，一句到两句，口语，不要分点。", "postcomment");
        if (StrUtil.isBlank(content)) {
            return;
        }
        String url = task.supportBaseUrl() + "/club/bbs/bbsGeneric.ashx?action=AddEvaluate"
                + "&aid=" + articleId + "&bid=" + boardId
                + "&author=" + authorOf(articleId, boardId) + "&content=" + SiemensCheckInTask.encode(content)
                + "&uid=" + task.currentUserId();
        task.waitBeforeSubmit(GROUP);
        SiemensCheckInTask.HttpResult result = task.post(url, "", task.supportHeaders(url));
        task.markSubmitted(GROUP);
        String response = StrUtil.blankToDefault(result.body, "").trim();
        System.out.println("[西门子] 评论帖子：" + SiemensCheckInTask.abbreviate(response));
        if (!"true".equals(response)) {
            // false 表示站点认为已经评论过，这种也不算成功。
            task.markTaskFailed(true);
        }
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
        submitPost("0", BOARD_ID, TYPE_DISCUSS, SiemensCheckInTask.cleanTitle(title), content, discussSortId());
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
        if (ok) {
            System.out.println("[西门子] 发故事帖成功，等待审核");
        } else if (!task.wasTaskSkipped()) {
            System.out.println("[西门子] 发故事帖失败");
        }
    }

    private void submitPost(String articleId, String boardId, String type, String title,
                            String content, String sort) {
        task.waitBeforeSubmit(GROUP);
        JSONObject request = buildRequest(articleId, boardId, type, title, content, sort);
        boolean ok = commit(request);
        if (ok) {
            System.out.println("[西门子] 发帖成功");
        } else if (!task.wasTaskSkipped()) {
            // 额度受限已在 commit 里打过日志，这里不再重复报「失败」。
            System.out.println("[西门子] 发帖失败");
        }
    }

    /**
     * 取探讨帖要用的子分类 ID。站点按版区下发可选项，取第一个避免硬编码失效；
     * 拿不到时退回「通信」。
     */
    private String discussSortId() {
        String url = task.supportBaseUrl() + "/club/bbs/bbsGeneric.ashx?action=SendSort&bid=" + BOARD_ID;
        SiemensCheckInTask.HttpResult result = task.get(url);
        String sortId = sortIdFrom(result.body);
        return StrUtil.blankToDefault(sortId, DISCUSS_SORT_ID);
    }

    /** 从「帖子类型」下拉里取第一个类别 ID，取不到返回 null。 */
    static String sortIdFrom(String html) {
        if (StrUtil.isBlank(html)) {
            return null;
        }
        Matcher matcher = SORT_OPTION.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
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
            String message = head == null
                    ? SiemensCheckInTask.abbreviate(result.body) : head.getStr("Message");
            System.out.println("[西门子] 发帖被拒绝：" + message);
            // 新注册账号每天有主贴数上限，这不是程序问题，也不该触发风控熔断。
            if (isQuotaLimited(message)) {
                task.markTaskSkipped();
                return false;
            }
        }
        task.markTaskFailed(!ok);
        return ok;
    }

    /** 站点对当日主贴数量超限的提示，属于账号限制而不是程序失败。 */
    static boolean isQuotaLimited(String message) {
        return message != null && message.contains("主贴数量");
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

    private String authorOf(String articleId, String boardId) {
        SiemensCheckInTask.Page page = task.follow(
                task.supportBaseUrl() + "/club/bbs/post_" + articleId + "_" + boardId + "_0_1.html");
        if (page == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("user_disp\\.aspx\\?u_id=(\\d+)").matcher(page.body);
        return matcher.find() ? matcher.group(1) : "";
    }
}
