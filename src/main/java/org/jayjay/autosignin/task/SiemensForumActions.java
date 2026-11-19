package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    /**
     * 单次最多读取几条审核通知。
     * 记录被清空或首次运行时站上会积压一批历史通知，全读一遍会拉出一长串无关结果，
     * 超出的直接标记已处理。
     */
    private static final int REVIEW_RECENT_LIMIT = 3;
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
     * 回帖、评论时最多往前看几条帖子。
     * 最新列表里常混着谈天说地、活动报名这类非技术帖，要往后找主题明确的技术帖。
     */
    private static final int TOPIC_CANDIDATES = 6;
    /** 最多检查几篇帖子的主题。每篇都要抓一次详情页，不宜无限往后翻。 */
    private static final int TOPIC_CHECK_LIMIT = 4;
    /** 型号、报警码这类「字母+数字」组合，例如 S7-1500、ET200SP、F07452、6ES7。 */
    private static final Pattern TECHNICAL_CODE = Pattern.compile("[A-Za-z]{1,6}[A-Za-z0-9\\-]*\\d[A-Za-z0-9\\-]*");
    /**
     * 技术主题线索词。只用来判断一条帖子值不值得回，命中任意一个就算技术帖。
     * <p>
     * 词表刻意收窄：像「模块」「参数」「故障」「电机」这种在活动帖、二手交易帖、
     * 闲聊帖里也会出现的泛词一律不放，否则「国内直流电机厂商销售情况」这种商业帖
     * 也会被当成技术帖。宁可漏判（跳过当天任务），也不要判错。
     * <p>
     * 需要收紧或放宽时只改这里，{@link #isTechnicalText(String)} 不含其它硬编码判断。
     */
    private static final String[] TECHNICAL_KEYWORDS = {
            // 产品与型号系列
            "plc", "profinet", "profibus", "ethernet", "et200", "simatic", "wincc", "博途", "tia portal",
            "s7-1200", "s7-1500", "s7-300", "s7-400", "s7-200", "1500plc", "1200plc",
            "s120", "g120", "v90", "v20", "g130", "g150", "6es7", "6gk7", "6se7",
            // 组态与编程
            "组态", "梯形图", "功能块", "程序块", "扫描周期", "数据块", "db块", "db 块", "ob1",
            "中断ob", "中断 ob", "符号表", "在线监控", "下载程序", "固件升级",
            // 编程语言与数据区（这些是纯技术术语，不会出现在活动帖里）
            "l 区", "l区", "m 区", "m区", "背景db", "临时变量", "全局变量", "数据类型",
            "fb块", "fc块", "ob块", "背景db", "背景 db", "sfc", "stl", "scl", "lad", "fbd",
            // 通讯与网络
            "通讯", "通信", "网段", "交换机", "网关", "报文", "opc ua", "modbus",
            "设备名", "从站", "主站", "掉站", "看门狗", "更新时间", "冗余", "环网", "mrp",
            // 诊断与故障（必须带具体技术对象，单用「故障」「报警」太泛）
            "诊断缓冲区", "报警代码", "故障代码", "报错代码", "停机", "过流", "过载", "超时",
            // 驱动与运动控制
            "变频器", "伺服", "编码器", "运动控制", "回零", "定位控制", "直流母线", "预充电",
            // 模拟量与信号
            "模拟量", "数字量", "热电阻", "pt100", "热电偶", "4-20ma", "4~20ma", "io 模块",
            // 上位与显示
            "hmi", "触摸屏", "pid 控制", "pid调节", "pid 调节",
    };
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

    /**
     * 在最新帖子里回帖。跳过自己的帖子，站点禁止自问自答。
     * <p>
     * 候选按**帖子主题**挑：先取到详情页，只有主题明确的技术帖才参与，
     * 谈天说地、活动报名这类没有技术主题的帖子直接排除——
     * 对着这类帖子写出来的回帖必然答非所问。
     */
    void replyPost() {
        String uid = task.currentUserId();
        Topic post = pickTechnicalTopic(uid);
        if (post == null) {
            // 没有可回的技术帖不是失败：计入失败会触发熔断，把后面能做的任务一起中止。
            System.out.println("[西门子] 没有找到主题明确的技术帖，跳过回帖");
            task.markTaskNoTarget();
            return;
        }
        String source = post.title + " " + post.body;
        String content = task.generateRelated("请用中文写一段西门子技术论坛的回帖，作为同行接着这个帖子往下聊。"
                + "帖子标题：" + post.title + "。帖子正文：" + post.body + "。"
                + "要求：紧扣这个帖子讨论的具体对象和问题，先复述你针对的是哪一点（例如某个型号、某个报警、"
                + "某一步操作），再给出自己的经验、判断或补充做法；"
                + "150字以内，口语，不要分点，不要复述标题，不要写和这个帖子无关的通用建议。",
                "reply", source);
        if (StrUtil.isBlank(content)) {
            System.out.println("[西门子] 回帖内容生成失败，跳过：" + post.articleId);
            return;
        }
        String title = StrUtil.isBlank(post.title) ? "回复：技术讨论" : "回复：" + post.title;
        submitPost(post.articleId, post.boardId, TYPE_DISCUSS,
                SiemensCheckInTask.cleanTitle(title), content, "0");
    }

    /**
     * 评论帖子。跳过自己和已经评论过的帖子，否则站点直接回 false。
     * 和回帖一样，只评论主题明确的技术帖：详情页已经在抓，顺手把标题和正文取出来判主题，
     * 不额外增加请求。
     */
    void commentPost() {
        String uid = task.currentUserId();
        List<Topic> open = new ArrayList<>();
        for (String candidate : latestForumPostIds(TOPIC_CANDIDATES)) {
            if (candidate.startsWith(uid + "-")) {
                continue;
            }
            int split = candidate.indexOf('-');
            String articleId = candidate.substring(0, split);
            String boardId = candidate.substring(split + 1);
            Topic topic = topicOf(articleId, boardId);
            if (topic == null || !isTechnicalTopic(topic)) {
                continue;
            }
            Matcher author = Pattern.compile("user_disp\\.aspx\\?u_id=(\\d+)").matcher(topic.html);
            String authorId = author.find() ? author.group(1) : "";
            long mine = Pattern.compile("u_id=\"" + uid + "\"").matcher(topic.html).results().count();
            // 别人的帖子、且我在页面上只出现 1 次（说明还没评论过）。
            if (!authorId.equals(uid) && mine <= 1) {
                open.add(topic);
            }
        }
        if (open.isEmpty()) {
            // 同样不是失败：没有可评论的技术帖就跳过，不影响其它任务。
            System.out.println("[西门子] 没有可评论的技术帖");
            task.markTaskNoTarget();
            return;
        }
        // 按账号序号错开，多个容器不会都评论同一篇。
        Topic post = open.get(task.accountIndex() % open.size());
        String source = post.title + " " + post.body;
        String content = task.generateRelated("请用中文写一条西门子技术论坛的短评论。"
                + "帖子标题：" + post.title + "。帖子正文：" + post.body + "。"
                + "要求：针对这个帖子里提到的具体内容说一句自己的看法或补充，"
                + "60字以内，一句到两句，口语，不要分点，不要写成和主题无关的客套话。",
                "postcomment", source);
        if (StrUtil.isBlank(content)) {
            System.out.println("[西门子] 评论内容生成失败，跳过：" + post.articleId);
            return;
        }
        String url = task.supportBaseUrl() + "/club/bbs/bbsGeneric.ashx?action=AddEvaluate"
                + "&aid=" + post.articleId + "&bid=" + post.boardId
                + "&author=" + post.authorId + "&content=" + SiemensCheckInTask.encode(content)
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
                    "请用中文给西门子技术论坛想一个技术分享帖标题，格式必须是「【分享】」加具体技术点，"
                            + "例如「【分享】V90 回零偏移排查」，20字以内，只输出标题。", "storytitle");
            content = task.generateUnique(
                    "请用中文写一篇西门子技术论坛的技术复盘帖，按「现象 → 排查顺序 → 定位依据 → 结论」写，"
                            + "带上报警代码、诊断缓冲区原文或测量数值，写清每一步的判断依据，"
                            + "型号和参数名要符合西门子真实产品体系，不要编造订货号。"
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
    private String latestForumPostId() {
        SiemensCheckInTask.Page page = task.follow(task.supportBaseUrl() + "/club/bbs/bbs_latest.aspx");
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
     * 首次运行或清过记录时站上会积压大量历史通知，这里只取最近的
     * {@link #REVIEW_RECENT_LIMIT} 条，否则一次推送会列出十几条无关结果。
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
        // 消息列表按时间倒序，超出的历史通知不再逐条抓详情，但标题仍要记为作废，
        // 否则那些被驳回的内容还能从内容库里被取出来重投。
        List<String> recent = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (i < REVIEW_RECENT_LIMIT) {
                recent.add(ids.get(i));
            } else {
                String title = reviewTitle(ids.get(i));
                if (StrUtil.isNotBlank(title)) {
                    task.contentBank().markRejected(ContentBank.STORY, title);
                }
                task.markReviewHandled(ids.get(i));
            }
        }
        for (String id : recent) {
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
     * 只取审核通知里的故事标题，用于把超限的历史通知也标记作废。
     * 拿不到返回空串。
     */
    private String reviewTitle(String noticeId) {
        SiemensCheckInTask.Page detail = task.follow(
                task.supportBaseUrl() + "/MySupport/MyMsgDetail.aspx?noticeId=" + noticeId);
        if (detail == null || detail.body == null) {
            return "";
        }
        org.jsoup.nodes.Element body = org.jsoup.Jsoup.parse(detail.body).selectFirst(".msg-detail-btm");
        String text = body == null ? "" : body.text().replaceAll("\\s+", " ");
        Matcher matcher = STORY_REVIEW.matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    /**
     * 记录本次审核结果，返回累计连续被拒的天数。
     * <p>
     * 故事帖天天被拒是常态，每天都往通知里写一行「N 篇未过审」只会变成噪音，
     * 所以平时不报，只有连续多天全被拒、该考虑关掉故事帖时才提醒一次。
     * 计数按账号存在 state 目录，任一故事被通过或降级为普通帖就清零。
     */
    int recordReviewOutcome(boolean allRejected) {
        Path file = task.storyRejectStreakPath();
        int streak = 0;
        if (file != null && Files.exists(file)) {
            try {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
                streak = StrUtil.isBlank(text) ? 0 : Integer.parseInt(text);
            } catch (Exception e) {
                streak = 0;
            }
        }
        streak = allRejected ? streak + 1 : 0;
        if (file != null) {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, String.valueOf(streak).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                // 写不进去只会导致下次提醒晚一天。
            }
        }
        return streak;
    }

    /** 连续被拒到第几次时提醒关掉故事帖。 */
    static final int REJECT_STREAK_ALERT = 3;

    /**
     * 连续被拒达到阈值时的提醒文案；没到阈值返回空串。
     * 只在正好达到阈值那天提醒一次，之后不再重复，避免天天刷同一条。
     */
    static String rejectStreakAlert(int streak) {
        if (streak != REJECT_STREAK_ALERT) {
            return "";
        }
        return "连续 " + streak + " 天故事帖全被拒，"
                + "可考虑设 SIEMENS_STORY_POST=false 关掉这个故事帖任务";
    }

    /**
     * 本次审核里是否全部被驳回（没有任何一篇被放行或降级后仍算数）。
     * 没有结果时按「不算被拒」处理，避免把没提交成功的一天也计入连续失败。
     */
    static boolean allRejected(List<String[]> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return false;
        }
        for (String[] review : reviews) {
            if (review[1] == null || !review[1].startsWith("未通过审核")) {
                return false;
            }
        }
        return true;
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

    /**
     * 抓一篇帖子的详情，取标题、正文摘要、作者和原始 HTML。
     * 一次请求同时满足「判主题」和「生成文案」两个用途，避免重复抓同一个页面。
     */
    private Topic topicOf(String articleId, String boardId) {
        SiemensCheckInTask.Page page = task.follow(
                task.supportBaseUrl() + "/club/bbs/post_" + articleId + "_" + boardId + "_0_1.html");
        if (page == null) {
            return null;
        }
        String title = SiemensCheckInTask.readableText(
                SiemensAnswerActions.firstElementText(page.body, "title"));
        if (StrUtil.isNotBlank(title)) {
            // 页面标题带站名后缀，只留真正的帖子标题。
            int cut = title.indexOf("-技术论坛");
            if (cut > 0) {
                title = title.substring(0, cut).trim();
            }
        }
        String body = SiemensAnswerActions.firstElementText(page.body, "div.articlecontent");
        String summary = StrUtil.isBlank(body) ? "" : SiemensAnswerActions.plainText(body, 600);
        Matcher author = Pattern.compile("user_disp\\.aspx\\?u_id=(\\d+)").matcher(page.body);
        String authorId = author.find() ? author.group(1) : "";
        if (StrUtil.isBlank(title) && StrUtil.isBlank(summary)) {
            return null;
        }
        return new Topic(articleId, boardId, StrUtil.blankToDefault(title, ""), summary,
                authorId, page.body);
    }

    /**
     * 按帖子主题挑一条可回帖的技术帖：跳过自己的帖子和没有技术主题的帖子，
     * 按账号序号错开，避免多个容器操作同一篇。
     */
    private Topic pickTechnicalTopic(String uid) {
        List<Topic> technical = new ArrayList<>();
        int checked = 0;
        for (String candidate : latestForumPostIds(TOPIC_CANDIDATES)) {
            // 限制的是「检查了几篇」而不是「找到了几篇」：每篇都要抓详情页，
            // 用已找到数当条件会让全是非技术帖时翻完整个候选列表。
            if (checked >= TOPIC_CHECK_LIMIT) {
                break;
            }
            int split = candidate.indexOf('-');
            String articleId = candidate.substring(0, split);
            String boardId = candidate.substring(split + 1);
            checked++;
            Topic topic = topicOf(articleId, boardId);
            if (topic == null || uid.equals(topic.authorId) || !isTechnicalTopic(topic)) {
                continue;
            }
            technical.add(topic);
        }
        if (technical.isEmpty()) {
            return null;
        }
        return technical.get(task.accountIndex() % technical.size());
    }

    /**
     * 帖子是否是一个有明确技术主题的帖。
     * <p>
     * 站点「最新发帖」混着谈天说地、活动报名、版务公告，这类帖没有可讨论的技术内容，
     * 对着它们生成的回帖必然是套话。判据是标题或正文里出现西门子产品型号、
     * 技术缩写或常见故障/组态用语——技术帖几乎必然命中，生活帖基本不会。
     */
    static boolean isTechnicalTopic(Topic topic) {
        if (topic == null) {
            return false;
        }
        // 活动/公告类标记优先：这类帖正文常提到产品名，但主题不是技术问题。
        if (isNonTechnicalPost(topic)) {
            return false;
        }
        return isTechnicalText(topic.title) || isTechnicalText(topic.body);
    }

    /**
     * 是不是活动、公告、交易类帖子。
     * 只看标题：正文里出现「报名」「领取」等词很常见，用正文判会误杀正常技术帖。
     */
    static boolean isNonTechnicalPost(Topic topic) {
        if (topic == null || StrUtil.isBlank(topic.title)) {
            return false;
        }
        String title = topic.title;
        for (String marker : NON_TECHNICAL_MARKERS) {
            if (title.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 文本里是否含技术线索。标题权重更高，但两者用同一套词表和规则。 */
    static boolean isTechnicalText(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String keyword : TECHNICAL_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        // 型号、报警码这类拉丁字母+数字的组合（S7-1500、ET200SP、F07452）也算技术线索。
        Matcher matcher = TECHNICAL_CODE.matcher(text);
        while (matcher.find()) {
            if (matcher.group().length() >= 3) {
                return true;
            }
        }
        return false;
    }

    /**
     * 活动、公告类标题标记。这类帖正文里常提到产品名，会被关键词命中，
     * 但主题是活动/公告而不是技术问题，回帖只会变成凑数。
     * 命中这里直接判成非技术帖，优先级高于关键词。
     */
    private static final String[] NON_TECHNICAL_MARKERS = {
            "一路上有你", "限量纪念", "周年专场", "报名", "活动", "领取", "抽奖",
            "免费领", "促销", "优惠", "团购", "二手", "转让", "招聘", "求职", "版规", "公告",
            "签到实物", "兑换", "积分",
    };

    /** 一篇帖子在生成文案时需要的信息，避免多处重复抓取同一个详情页。 */
    static final class Topic {
        final String articleId;
        final String boardId;
        final String title;
        final String body;
        final String authorId;
        final String html;

        Topic(String articleId, String boardId, String title, String body, String authorId, String html) {
            this.articleId = articleId;
            this.boardId = boardId;
            this.title = title;
            this.body = body;
            this.authorId = authorId;
            this.html = html;
        }
    }
}
