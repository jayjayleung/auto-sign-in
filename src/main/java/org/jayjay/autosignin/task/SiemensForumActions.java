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
    /** 消息中心里的故事审核通知标题，驳回和转普通帖都会发一条。 */
    private static final String REVIEW_NOTICE_TITLE = "故事审核通知";
    /** 审核通知的正文是「您提交的故事“标题”未通过审核,未通过原因：xxx」。 */
    private static final Pattern STORY_REVIEW = Pattern.compile(
            "您提交的故事[“\"]([^”\"]+)[”\"](?:未通过审核[,，]未通过原因[:：]([^。]+)|(被转换为普通帖))");
    /** 我的消息列表里每条通知的链接和标题。 */
    private static final Pattern NOTICE_LINK = Pattern.compile(
            "MyMsgDetail\\.aspx\\?noticeId=(\\d+)\"[^>]*>(.{0,600}?)</div>\\s*<div class=\"fr right\">",
            Pattern.DOTALL);
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
        List<String> open = new ArrayList<>();
        for (String candidate : latestForumPostIds(FAVORITE_CANDIDATES)) {
            String articleId = candidate.substring(0, candidate.indexOf('-'));
            if (favorites == null || !favorites.contains(articleId)) {
                open.add(candidate);
            }
        }
        String postId = null;
        if (!open.isEmpty()) {
            // 按账号序号错开，多个容器不会都收藏同一条。
            postId = open.get(task.accountIndex() % open.size());
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
        List<String> others = new ArrayList<>();
        for (String candidate : latestForumPostIds(FAVORITE_CANDIDATES)) {
            if (!candidate.startsWith(uid + "-")) {
                others.add(candidate);
            }
        }
        String postId = others.isEmpty() ? latestForumPostId()
                : others.get(task.accountIndex() % others.size());
        if (StrUtil.isBlank(postId)) {
            return;
        }
        int split = postId.indexOf('-');
        String articleId = split > 0 ? postId.substring(0, split) : postId;
        String boardId = split > 0 ? postId.substring(split + 1) : BOARD_ID;
        String[] topic = postTopic(articleId, boardId);
        String content;
        String title;
        if (topic == null) {
            System.out.println("[西门子] 拿不到原帖内容，回帖改用内置文案：" + articleId);
            content = task.fallbackContent("reply");
            title = "回复：升级选型讨论";
        } else {
            content = task.generateUnique("请用中文写一段西门子技术论坛的回帖，回复的原帖标题是："
                    + topic[0] + "。原帖内容：" + topic[1]
                    + "。回帖要贴着原帖的话题接话，150字以内，口语，不要分点。", "reply");
            title = StrUtil.isBlank(topic[0]) ? "回复：技术讨论" : "回复：" + topic[0];
        }
        if (StrUtil.isBlank(content)) {
            return;
        }
        submitPost(articleId, boardId, TYPE_DISCUSS, SiemensCheckInTask.cleanTitle(title), content, "0");
    }

    /** 评论帖子。跳过自己和已经评论过的帖子，否则站点直接回 false。 */
    void commentPost() {
        String uid = task.currentUserId();
        List<String> open = new ArrayList<>();
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
                open.add(candidate);
            }
        }
        if (open.isEmpty()) {
            System.out.println("[西门子] 没有可评论的帖子");
            return;
        }
        // 按账号序号错开，多个容器不会都评论同一篇。
        String postId = open.get(task.accountIndex() % open.size());
        int split = postId.indexOf('-');
        String articleId = postId.substring(0, split);
        String boardId = postId.substring(split + 1);
        String[] topic = postTopic(articleId, boardId);
        String content;
        if (topic == null) {
            System.out.println("[西门子] 拿不到原帖内容，评论改用内置文案：" + articleId);
            content = task.fallbackContent("postcomment");
        } else {
            content = task.generateUnique("请用中文写一条西门子技术论坛的短评论，针对的帖子标题是："
                    + topic[0] + "。帖子内容：" + topic[1]
                    + "。60字以内，一句到两句，回帖要贴合这个主题，口语，不要分点。", "postcomment");
        }
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
        String[] fromBank = task.contentBank().take(ContentBank.POST);
        String title;
        String content;
        if (fromBank != null) {
            title = fromBank[0];
            content = fromBank[1];
            System.out.println("[西门子] 发帖使用内容库：" + title);
        } else {
            title = task.generateUnique(
                    "请用中文给西门子技术论坛想一个关于 S7-1500 与 PROFINET 应用的技术探讨帖标题，15字以内，只输出标题。",
                    "posttitle");
            content = task.generateUnique(
                    "请用中文写一段西门子技术论坛探讨帖正文，主题围绕 S7-1500 和 PROFINET 的实际应用经验，"
                            + "300字以内，口语，不要分点，不要引用来源。", "postbody");
        }
        if (StrUtil.isBlank(title) || StrUtil.isBlank(content)) {
            return;
        }
        submitPost("0", BOARD_ID, TYPE_DISCUSS, SiemensCheckInTask.cleanTitle(title), content, discussSortId());
    }

    /** 发一条故事帖。故事帖要站点审核，提交后不会立即计入任务。 */
    void publishStory() {
        String[] fromBank = task.contentBank().take(ContentBank.STORY);
        String title;
        String content;
        if (fromBank != null) {
            title = fromBank[0];
            content = fromBank[1];
            System.out.println("[西门子] 故事帖使用内容库：" + title);
        } else {
            title = task.generateUnique(
                    "请用中文给西门子技术论坛想一个现场经历故事标题，要具体到这次经历的特征，"
                            + "不要用「一次…」「记一次…」这类泛泛开头，15字以内，只输出标题。", "storytitle");
            content = task.generateUnique(
                    "请用中文写一篇西门子技术论坛的故事帖，第一人称讲自己亲历的一次现场调试："
                            + "开头交代时间和现场氛围，必须写出具体的硬件型号、软件版本、报警代码或测量数值，"
                            + "写清楚先怀疑了什么、怎么发现判断错了、真正原因是什么，最后写这次之后改了什么习惯。"
                            + "细节要独一无二，不要写「网线松动」「设备名冲突」「接地不良」这类通用桥段，"
                            + "350字以内，不要分点，不要引用来源，不要出现真实人名和公司名。", "storybody");
        }
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
    private String latestForumPostId() {        SiemensCheckInTask.Page page = task.follow(task.supportBaseUrl() + "/club/bbs/bbs_latest.aspx");
        if (page == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("post_(\\d+)_(\\d+)_\\d+_\\d+\\.html").matcher(page.body);
        return matcher.find() ? matcher.group(1) + "-" + matcher.group(2) : null;
    }

    /**
     * 读取消息中心里还没处理过的故事审核结果。
     * <p>
     * 故事帖提交后要等审核，结果以站内消息下发，可能是「未通过审核」（附原因），
     * 也可能是「被转换为普通帖发布」，后者说明内容被判成普通帖，同样不计入故事帖任务。
     * 已处理过的通知记在 state 目录，避免每天重复上报同一批结果。
     * 返回 {标题, 结论} 列表，拿不到时返回空列表。
     */
    List<String[]> readStoryReviews() {
        List<String[]> reviews = new ArrayList<>();
        SiemensCheckInTask.Page page = task.follow(task.supportBaseUrl() + "/MySupport/MyMsgmain.aspx");
        if (page == null || page.body == null) {
            return reviews;
        }
        Set<String> handled = task.handledReviewIds();
        Matcher link = NOTICE_LINK.matcher(page.body);
        List<String> ids = new ArrayList<>();
        while (link.find()) {
            // 标题在 center 的 title 属性里，审核通知不会太多，全都看一眼。
            if (link.group(2).contains(REVIEW_NOTICE_TITLE) && !handled.contains(link.group(1))) {
                ids.add(link.group(1));
            }
        }
        for (String id : ids) {
            SiemensCheckInTask.Page detail = task.follow(
                    task.supportBaseUrl() + "/MySupport/MyMsgDetail.aspx?noticeId=" + id);
            if (detail == null || detail.body == null) {
                continue;
            }
            org.jsoup.nodes.Element body = org.jsoup.Jsoup.parse(detail.body).selectFirst(".msg-detail-btm");
            String text = body == null ? "" : body.text().replaceAll("\\s+", " ");
            Matcher matcher = STORY_REVIEW.matcher(text);
            // 详情页读不到或不是审核通知时也记下 ID，避免每次重试同一条。
            task.markReviewHandled(id);
            if (!matcher.find()) {
                continue;
            }
            String title = matcher.group(1);
            String result = matcher.group(3) != null
                    ? "被转成普通帖" : "未通过审核：" + StrUtil.trim(matcher.group(2));
            reviews.add(new String[]{title, result});
        }
        return reviews;
    }

    /**
     * 把审核没通过的故事标题在内容库里标记作废，以后不再取用。
     * 被驳回的标题站点已经认过，重发只会再被拒一次。
     */
    void markRejectedStories(List<String[]> reviews) {
        for (String[] review : reviews) {
            task.contentBank().markRejected(ContentBank.STORY, review[0]);
        }
    }

    private String authorOf(String articleId, String boardId) {        SiemensCheckInTask.Page page = task.follow(
                task.supportBaseUrl() + "/club/bbs/post_" + articleId + "_" + boardId + "_0_1.html");
        if (page == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("user_disp\\.aspx\\?u_id=(\\d+)").matcher(page.body);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * 取原帖的标题和正文摘要，供回帖和评论时贴合主题。
     * 详情页标题在 &lt;title&gt;（「标题-技术论坛-…」），正文在 div.articlecontent；
     * 取不到时返回 null，调用方改用内置文案，避免把空主题喂给模型。
     * 返回 {标题, 正文摘要}，任一段可能是空串。
     */
    private String[] postTopic(String articleId, String boardId) {
        SiemensCheckInTask.Page page = task.follow(
                task.supportBaseUrl() + "/club/bbs/post_" + articleId + "_" + boardId + "_0_1.html");
        if (page == null) {
            return null;
        }
        String title = SiemensCheckInTask.readableText(SiemensAnswerActions.firstElementText(page.body, "title"));
        if (StrUtil.isNotBlank(title)) {
            // 页面标题带站名后缀，只留真正的帖子标题。
            int cut = title.indexOf("-技术论坛");
            if (cut > 0) {
                title = title.substring(0, cut).trim();
            }
        }
        String body = SiemensAnswerActions.firstElementText(page.body, "div.articlecontent");
        String summary = StrUtil.isBlank(body) ? "" : SiemensAnswerActions.plainText(body, 200);
        if (StrUtil.isBlank(title) && StrUtil.isBlank(summary)) {
            return null;
        }
        return new String[]{StrUtil.blankToDefault(title, ""), summary};
    }
}
