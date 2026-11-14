package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.jayjay.autosignin.entity.NotificationSection;

import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 西门子工业支持中心签到。
 * <p>
 * 除了每日签到，还会读取每日任务列表并把未完成项逐项做掉：收藏、回帖、评论、发帖、提问、回答。
 * 文案由商城「西游 AI 助手」生成，走同一套 ONEID 账号。
 * <p>
 * 具体动作拆分在 {@link SiemensForumActions} 和 {@link SiemensAnswerActions}，本类只负责编排、
 * 登录签到、抽奖、AI 生成以及风控（同日去重、同类间隔、连续失败熔断）。
 */
public class SiemensCheckInTask extends CheckInTask {

    static final String USERNAME_KEY = "SIEMENS_USERNAME";
    static final String PASSWORD_KEY = "SIEMENS_PASSWORD";
    /** 设为 false 可以只签到、不执行发帖类每日任务。 */
    static final String DAILY_TASKS_KEY = "SIEMENS_DAILY_TASKS";

    private static final String DEFAULT_SUPPORT_BASE_URL = "https://www.ad.siemens.com.cn";
    private static final String LOGIN_API_PATH = "/api/bff/v1.2/developer/ciam/login/pwd";
    private static final String TASKS_URL = "/MySupport/AjaxAPI/EventTasksAjax.ashx";
    /** 商城西游 AI 助手，用于生成每日任务里的帖子、回答和评论内容。 */
    private static final String AI_CHAT_URL = "https://dida-server.siemens.com.cn/v1/external_chat/qa";
    private static final String MALL_CLIENT_ID = "455ae4c460b858b72ec04825e8296d6180ZM6Q5JnIM";
    private static final String ONEID_ORIGIN = "https://api.oneid.siemens.com.cn";
    private static final String MALL_REFERER = "https://mall.siemens.com.cn/pcweb/xiyouAi?platform=3";

    private static final int TIMEOUT_MILLIS = 30000;
    /** AI 生成比普通接口慢，单独放宽到 60 秒。 */
    private static final int AI_TIMEOUT_MILLIS = 60000;
    private static final int MAX_REDIRECTS = 10;
    /** 同类提交之间的最小间隔，站点规则要求提问/回答/评论间隔至少 2 分钟。 */
    private static final long MIN_INTERVAL_MILLIS = 120_000L;
    private static final String GROUP_FORUM = "forum";
    private static final String GROUP_ANSWER = "answer";
    /** 连续失败达到上限就停止后续任务，避免风控期间继续请求。 */
    private static final int MAX_CONSECUTIVE_FAILURES = 2;
    private static final double DUPLICATE_THRESHOLD = 0.8;

    private static final Pattern FID_PATTERN = Pattern.compile("fId\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern APP_ID_PATTERN = Pattern.compile("idaasAppId\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern USER_ID_PATTERN = Pattern.compile("userId\\s*=\\s*(\\d+)");
    private static final Pattern COIN_IN_TEXT = Pattern.compile("(\\d+)\\s*西币");

    /** AI 助手不可用时的兜底文案，按天轮换。 */
    private static final String[] POST_TITLES = {
            "S7-1500 与 PROFINET 组网经验交流",
            "1500 做 PN 通讯的几个坑",
            "S7-1500 从站掉站处理分享",
            "1500 与变频器 PN 通讯调试小结",
    };
    private static final String[] POST_BODIES = {
            "最近在做一条线的 PN 组网，主站用 1516，带了十几个 ET200 和几个 G120。"
                    + "分享几点：设备名一定要在上线前统一规划，别现场临时起名；"
                    + "拓扑视图能对上实物会省很多排查时间；换端口或者换交换机后记得重新校验。"
                    + "有类似场景的可以一起交流下经验。",
            "S7-1500 走 PROFINET 的时候，比较容易被忽略的是更新时间设置。"
                    + "站多的时候把更新时间调得过短，反而容易出现丢站或者报警。"
                    + "我们做法是按实际工艺节拍给，别一味追求快。另外交换机上最好把 PN 口和办公网分开。",
            "遇到过 1500 从站偶发掉站，最后查到是网线屏蔽没做好，和柜内变频器走线太近。"
                    + "换屏蔽网线、分开桥架之后就稳定了。提醒大家布线阶段就把 PN 线和动力线分开。",
            "1516 带 PN 从站，现场调试时习惯先在拓扑视图里对一遍实际接线，"
                    + "能提前发现端口接错。设备名用 TIA 里的功能批量分配，比一个个点快很多。"
                    + "改完组态记得重新下载硬件，不然诊断信息会对不上。",
    };
    private static final String[] STORY_TITLES = {
            "记一次夜班抢修",
            "第一次独立调试",
            "现场那根接错的网线",
            "通宵调变频的那晚",
    };
    private static final String[] STORY_BODIES = {
            "刚入行那年跟着师傅做一个包装线的改造。晚上十点多产线突然全停，上位机全是通讯报警。"
                    + "师傅让我先看交换机的灯，果然有一个口在闪黄。顺着线查过去，是白天施工碰松的一根 PN 线。"
                    + "重新压好水晶头，重启从站就恢复了。那晚我记住了：先看物理层，别急着翻程序。",
            "第一次独立负责一条小线的调试，压力挺大。开机后有两个从站一直起不来，"
                    + "我把设备名、IP、拓扑挨个对了一遍，发现是其中一个模块的固件版本和组态不一致。"
                    + "升级固件重新下载之后全部正常。这件事让我养成了先核对版本再排查逻辑的习惯。",
            "有条老线换控制器，图纸上标的是 A 柜到 B 柜一根网线，实际现场接成了两根中间过交换机。"
                    + "结果拓扑死活对不上，报警一直消不掉。后来顺着桥架一根根摸，才发现多了个交换机。"
                    + "补进拓扑之后一次就过了。现场永远比图纸复杂，动手前先走一遍线。",
            "调试一条新线到凌晨三点，变频器一直报通讯超时。改了更新时间、换了网线都没用。"
                    + "最后发现是有一台设备的设备名和另一台重名，两台轮流上线。"
                    + "改完名字整个世界都安静了。回去路上天都亮了，但那次的教训一直记着。",
    };
    private static final String[] ASK_TITLES = {
            "S7-1500 与第三方 PN 设备通讯超时",
            "1516 带 PN 从站偶发掉站",
            "S7-1500 组态里设备名如何批量分配",
            "1500 通过 PN 读取变频器参数异常",
    };
    private static final String[] ASK_BODIES = {
            "项目里用 1516 主站带一台第三方 PROFINET 设备，组态里能识别到设备，"
                    + "但在线诊断提示通讯超时。更新时间和看门狗都按手册设过，网线也换过。"
                    + "想请教一下这类情况一般先查哪些点？",
            "一条线上 1516 带了十几个 ET200 从站，运行几小时后偶尔有一两个掉站，重新上电就恢复。"
                    + "交换机是管理型的，暂时没看到明显错误包。这种情况一般是布线问题还是更新时间设置问题？",
            "S7-1500 的组态里要分配几十个从站的设备名，目前是一个个点开改，效率很低。"
                    + "想确认一下有没有批量分配或者在线的批量改法，用 TIA 哪个版本比较稳？",
            "用 1500 通过 PROFINET 读变频器参数，个别参数读回来是 0 或者异常值，"
                    + "但同一台变频器用面板看是正常的。报文是标准报文，组态也核对过。"
                    + "想请教下一般是什么原因？",
    };
    private static final String[] REPLIES = {
            "我们厂去年也做过类似的改造，经验是先把 DP 从站保留，用 CM 1542-5 过渡，"
                    + "按工位分批换，别整线一次停。程序和画面分两次下装，出问题好回退。",
            "补充一条：换型时把原来的符号表和注释一起整理好，后面接手的人能省很多时间。"
                    + "备件也要提前确认交期，老模块现在越来越难买。",
            "选型上建议先算一下 IO 点数的增长余量，别只按现在的需求配。"
                    + "我们当时就是点数不够又补了一块模块，柜内空间差点放不下。",
    };
    private static final String[] POST_COMMENTS = {
            "这个思路挺实用的，现场调试时确实能少走弯路。",
            "同意，先看物理层再查程序，能省一半时间。",
            "感谢分享，正好最近在做类似的项目，参考价值很大。",
    };
    private static final String[] BEST_COMMENTS = {
            "补充一点现场经验：同样的配置最好在样机上先跑一遍，把报警码记下来，现场再遇到能省不少时间。",
            "按这个思路处理过一次，确实有效。另外建议把诊断缓冲区一起看，能更快定位到第一现场。",
            "这个方法我们也用过，补充一条：改完组态记得重新下载硬件，否则诊断信息会对不上。",
    };
    private static final String[] ANSWERS = {
            "建议先确认硬件组态和实际模块型号、固件版本是否一致，再检查 PROFINET 设备名是否分配正确。"
                    + "如果在线诊断能看到设备但通讯不上，先看是否在同一个网段、是否有 IP 冲突，"
                    + "再逐台核对拓扑和端口设置，一般就能定位到问题点。",
            "这种情况一般先看三层：物理层（网线、屏蔽、走线是否和动力线分开）、"
                    + "设备名和 IP 分配（有没有重名或残留）、以及更新时间设置是否过短。"
                    + "顺序建议从物理层开始，排查起来最快。",
            "可以在 TIA 的在线访问里用「分配设备名」的功能批量处理，比逐个点开快很多。"
                    + "前提是拓扑视图和实物接线一致，组态下载后再统一分配一次，基本能一次过。",
    };

    /** 每日任务的稳定代码，站点改中文名或增删任务时这里才需要跟着改。 */
    static final String TASK_SIGN_IN = "NewMySupport_SignIn";
    static final String TASK_ASK = "NewMySupport_FAQuestion";
    static final String TASK_ANSWER = "NewMySupport_FAAnswer";
    static final String TASK_COLLECT_QUESTION = "NewMySupport_CollectionQA";
    static final String TASK_COMMENT_BEST_ANSWER = "NewMySupport_CommentBestAnswer";
    static final String TASK_PUBLISH_POST = "NewMySupport_BBSPostArticle";
    static final String TASK_PUBLISH_STORY = "NewMySupport_BBSPostStoryArticle";
    static final String TASK_REPLY_POST = "NewMySupport_BBSReplyArticle";
    static final String TASK_COLLECT_POST = "NewMySupport_CollectionArticle";
    static final String TASK_COMMENT_POST = "NewMySupport_CommentArticle";

    private final Map<String, String> environment;
    private final String supportBaseUrl;
    private final String entryUrl;
    private final String signUrl;
    private final CookieJar cookies = new CookieJar();
    private final Map<String, Long> lastSubmitAt = new HashMap<>();
    private final boolean dailyTasksByDefault;

    private String currentUserId = "";
    private String cachedAiToken;
    /**
     * AI 助手不可用时置位（要验证码、超时等）。置位后本次运行不再重试登录，
     * 改用内置文案兜底，避免一次 AI 故障把整轮每日任务带崩。
     */
    private boolean aiUnavailable;
    /** 兜底文案每种类型已经用过几次，用来在同一次运行内换着取，避免重复。 */
    private final Map<String, Integer> fallbackUses = new HashMap<>();
    private int consecutiveFailures;
    private boolean lastTaskFailed;
    /** 本次任务被站点额度挡住，既不算成功也不算失败。 */
    private boolean lastTaskSkipped;
    /** 被额度挡下的任务，通知里单独说明，避免误报成「已提交待审核」。 */
    private final Set<String> quotaLimited = new HashSet<>();
    /** 本次运行已经处理过的任务代码。 */
    private final Set<String> handled = new HashSet<>();
    /** 最近一次使用的账号，用于定位会话文件。 */
    private String lastUsername;
    /** 指向真实站点时才复用并保存会话，测试用的假站点不落盘。 */
    private final boolean sessionReuseEnabled;

    public SiemensCheckInTask() {
        this(System.getenv(), DEFAULT_SUPPORT_BASE_URL, true);
    }

    SiemensCheckInTask(Map<String, String> environment, String supportBaseUrl) {
        this(environment, supportBaseUrl, false);
    }

    SiemensCheckInTask(Map<String, String> environment, String supportBaseUrl, boolean dailyTasksByDefault) {
        this.environment = environment;
        this.supportBaseUrl = trimTrailingSlash(StrUtil.blankToDefault(supportBaseUrl, DEFAULT_SUPPORT_BASE_URL));
        this.entryUrl = this.supportBaseUrl + "/MySupport/index.aspx";
        this.signUrl = this.supportBaseUrl + "/MySupport/AjaxAPI/SignInAjax.ashx";
        this.dailyTasksByDefault = dailyTasksByDefault;
        // 只有指向真实站点的实例才复用会话；测试用的本地假站点不落盘，避免相互串会话。
        this.sessionReuseEnabled = DEFAULT_SUPPORT_BASE_URL.equals(this.supportBaseUrl);
    }

    /** 用账号密码走一遍 ONEID 登录流程；成功返回 null，失败返回原因。 */
    private String loginWithPassword(String username, String password) {
        System.out.println("[西门子] 打开登录页");
        Page loginPage = follow(entryUrl);
        if (loginPage == null || !isOneIdLoginPage(loginPage.body)) {
            return "没有打开登录页";
        }
        String flowId = group(FID_PATTERN, loginPage.body);
        String appId = group(APP_ID_PATTERN, loginPage.body);
        if (StrUtil.isBlank(flowId) || StrUtil.isBlank(appId)) {
            return "登录页缺少会话信息";
        }

        System.out.println("[西门子] 开始登录");
        JSONObject loginBody = login(loginPage.url, appId, flowId, username, password);
        if (loginBody == null) {
            return "登录没有返回结果";
        }
        if (!isLoginSuccess(loginBody)) {
            String reason = describeLoginFailure(loginBody);
            System.out.println("[西门子] 登录失败：" + reason);
            return reason;
        }
        JSONObject data = loginBody.getJSONObject("data");
        String flowType = data == null ? null : data.getStr("flowType");
        if (StrUtil.isNotBlank(flowType) && flowType.startsWith("NEED_")) {
            String reason = describeLoginCode(flowType);
            System.out.println("[西门子] 登录未完成：" + reason);
            return reason;
        }
        String redirectUrl = data == null ? null : data.getStr("redirectUrl");
        if (StrUtil.isBlank(redirectUrl)) {
            return "登录成功但没有返回跳转地址";
        }

        System.out.println("[西门子] 跟随登录跳转");
        Page landed = follow(resolveUrl(loginPage.url, redirectUrl));
        if (landed == null || isOneIdLoginPage(landed.body)) {
            return "登录后仍停在登录页";
        }
        return null;
    }

    // ==================== 会话复用 ====================

    /**
     * 尝试用上次保存的 cookie 恢复会话。
     * 会话 cookie 本身带过期时间，所以这里只关心站点是否还认这个登录态。
     */
    private boolean tryRestoreSession(String username) {
        if (!sessionReuseEnabled) {
            return false;
        }
        Path file = sessionFile(username);
        if (file == null || !Files.exists(file)) {
            return false;
        }
        cookies.clear();
        boolean loaded;
        try {
            loaded = cookies.load(file);
        } catch (Exception e) {
            return false;
        }
        if (!loaded) {
            return false;
        }
        Page page = follow(entryUrl);
        return page != null && !isOneIdLoginPage(page.body) && USER_ID_PATTERN.matcher(page.body).find();
    }

    private void saveSession(String username) {
        if (!sessionReuseEnabled) {
            return;
        }
        Path file = sessionFile(username);
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            cookies.save(file);
        } catch (Exception e) {
            // 保存失败只影响下次复用，不影响本次执行。
        }
    }

    /** 清掉保存的会话，返回之前是否存在。 */
    private boolean clearSession() {
        if (!sessionReuseEnabled) {
            return false;
        }
        Path file = sessionFile(lastUsername);
        if (file == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(file);
        } catch (Exception e) {
            return false;
        }
    }

    /** 会话文件按账号区分，多个账号在同一容器里不会互相覆盖。 */
    private Path sessionFile(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        lastUsername = username;
        String key = sha1Hex(username.trim()).substring(0, 12);
        return stateDir().resolve("siemens-session-" + key + ".txt");
    }

    @Override
    public NotificationSection buildNotificationSection() {
        return new NotificationSection("西门子签到", messageBlocks);
    }

    @Override
    public CheckInTask run() {
        try {
            System.out.println("[西门子] 签到任务开始");
            String username = environment == null ? null : environment.get(USERNAME_KEY);
            String password = environment == null ? null : environment.get(PASSWORD_KEY);
            if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
                System.out.println("[西门子] 用户名或密码未配置，跳过签到");
                enabled = false;
                return this;
            }
            String failure = execute(username.trim(), password);
            if (failure != null) {
                addMessage("签到失败：", failure);
            }
        } catch (Exception e) {
            e.printStackTrace();
            addMessage("西门子签到失败，请查看日志");
        }
        System.out.println("[西门子] 签到任务结束");
        return this;
    }

    private String execute(String username, String password) {
        // 优先复用上次保存的会话，避免每次都密码登录把账号打到要图形验证码。
        if (tryRestoreSession(username)) {
            System.out.println("[西门子] 复用上次的登录会话");
        } else {
            String failure = loginWithPassword(username, password);
            if (failure != null) {
                return failure;
            }
            saveSession(username);
        }

        System.out.println("[西门子] 查询签到状态");
        JSONObject detail = readSignDetail();
        if (!isTrustedSignDetail(detail)) {
            // 保存的会话可能刚好过期：清掉再走一次密码登录。
            if (clearSession()) {
                System.out.println("[西门子] 保存的会话已失效，改用密码登录");
                String failure = loginWithPassword(username, password);
                if (failure != null) {
                    return failure;
                }
                saveSession(username);
                detail = readSignDetail();
                if (!isTrustedSignDetail(detail)) {
                    return "登录后没有进入支持中心";
                }
            } else {
                return "登录后没有进入支持中心";
            }
        }
        String result = "今日已签到";
        if (!Boolean.TRUE.equals(detail.getBool("TodayHaveSign"))) {
            System.out.println("[西门子] 开始签到");
            String signBody = postSign("sign");
            System.out.println("[西门子] 签到响应：" + abbreviate(signBody));
            if (!signedSuccessfully(signBody)) {
                return "签到接口返回失败";
            }
            result = "签到成功";
            JSONObject refreshed = readSignDetail();
            if (isTrustedSignDetail(refreshed)) {
                detail = refreshed;
            }
        } else {
            System.out.println("[西门子] 今日已签到");
        }

        Profile profile = loadProfile();
        Integer coinBefore = parseIntOrNull(profile.coin);
        if (StrUtil.isNotBlank(profile.userId)) {
            currentUserId = profile.userId;
        }
        addMessage("签到结果：", result);
        if (detail.get("SignCount") != null) {
            addMessage("已签到：", detail.getStr("SignCount"), " 天");
        }
        playLottery(readSignDetail());

        if (dailyTasksEnabled()) {
            completeDailyTasks();
        }

        // 任务都做完后再读一次余额，用差额体现今天实际增加了多少西币。
        Profile after = loadProfile();
        String coinValue = StrUtil.blankToDefault(after.coin, profile.coin);
        if (StrUtil.isNotBlank(coinValue)) {
            Integer coinAfter = parseIntOrNull(coinValue);
            if (coinBefore != null && coinAfter != null && coinAfter >= coinBefore) {
                addMessage("西币：", coinValue, "（本次 +" + (coinAfter - coinBefore) + "）");
            } else {
                addMessage("西币：", coinValue);
            }
        }
        return null;
    }

    // ==================== 每日任务编排 ====================

    private boolean dailyTasksEnabled() {
        String flag = environment == null ? null : environment.get(DAILY_TASKS_KEY);
        if (StrUtil.isNotBlank(flag)) {
            return !"false".equalsIgnoreCase(flag.trim());
        }
        return dailyTasksByDefault;
    }

    /** 把剩下的每日任务做掉，逐项独立执行，某一项失败不影响其他项。 */
    private void completeDailyTasks() {
        if (alreadyRanToday()) {
            System.out.println("[西门子] 今天已经执行过每日任务，跳过，避免重复发帖");
            addMessage("每日任务：", "今天已执行过，跳过");
            return;
        }
        List<String> pending = pendingDailyTasks(readTasks());
        if (pending.isEmpty()) {
            System.out.println("[西门子] 每日任务已全部完成");
            markRanToday();
            return;
        }
        System.out.println("[西门子] 待完成每日任务：" + pending);
        SiemensForumActions forum = new SiemensForumActions(this);
        SiemensAnswerActions answer = new SiemensAnswerActions(this);
        String questionId = null;
        String aborted = null;
        for (String code : pending) {
            lastTaskFailed = false;
            lastTaskSkipped = false;
            try {
                // 按任务代码分发，中文名和排序随时会被站点调整。
                if (TASK_COLLECT_QUESTION.equals(code)) {
                    questionId = answer.collectQuestion(questionId);
                } else if (TASK_COLLECT_POST.equals(code)) {
                    forum.collectPost();
                } else if (TASK_ANSWER.equals(code)) {
                    answer.answerQuestion();
                } else if (TASK_COMMENT_BEST_ANSWER.equals(code)) {
                    answer.commentBestAnswer();
                } else if (TASK_REPLY_POST.equals(code)) {
                    forum.replyPost();
                } else if (TASK_COMMENT_POST.equals(code)) {
                    forum.commentPost();
                } else if (TASK_PUBLISH_POST.equals(code)) {
                    forum.publishPost();
                } else if (TASK_PUBLISH_STORY.equals(code)) {
                    forum.publishStory();
                } else if (TASK_ASK.equals(code)) {
                    answer.askQuestion();
                } else {
                    System.out.println("[西门子] 新增的未支持任务：" + code + "，跳过");
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("[西门子] 任务失败：" + code);
                lastTaskFailed = true;
            }
            if (lastTaskFailed) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    System.out.println("[西门子] 连续失败 " + consecutiveFailures + " 次，停止剩余任务，避免触发风控");
                    aborted = "连续失败 " + consecutiveFailures + " 次，已中止";
                    break;
                }
            } else if (lastTaskSkipped) {
                // 额度受限不是失败，但也不能算已完成：重置熔断计数并单独记录。
                consecutiveFailures = 0;
                quotaLimited.add(code);
            } else {
                consecutiveFailures = 0;
                handled.add(code);
            }
        }
        // 中止时不写「今天已执行」标记，留出后续重试的机会；已完成的项站点会标记，重跑不会重复发。
        if (aborted == null) {
            markRanToday();
        }
        reportRemaining(aborted);
    }

    /**
     * 重新读任务列表，把没做的项写进通知。
     * 故事帖提交后进入站点审核，任务状态当天不会变成已完成，只要本次提交成功就算已处理。
     */
    private void reportRemaining(String aborted) {
        String text = remainingText(pendingDailyTasks(readTasks()),
                handled.contains(TASK_PUBLISH_STORY), aborted);
        if (!quotaLimited.isEmpty()) {
            text = text + "（受站点每日主贴上限限制，明天会重试）";
        }
        addMessage("每日任务：", text);
    }

    /**
     * 把剩余任务拼成通知文案。
     * 只有本次确实提交成功的故事帖才算已处理，失败时仍要出现在未完成列表里。
     */
    static String remainingText(List<String> pending, boolean submittedStory, String aborted) {
        List<String> left = new ArrayList<>(pending == null ? new ArrayList<>() : pending);
        if (submittedStory) {
            left.remove(TASK_PUBLISH_STORY);
        }
        List<String> names = new ArrayList<>();
        for (String code : left) {
            names.add(taskDisplayName(code));
        }
        if (names.isEmpty() && aborted == null) {
            return submittedStory ? "已全部完成，故事帖待审核" : "已全部完成";
        }
        StringBuilder text = new StringBuilder();
        if (aborted != null) {
            // 中止时也要把剩余项列清楚，否则看不出到底卡在哪。
            text.append(aborted);
            if (!names.isEmpty()) {
                text.append("，未完成 ").append(names);
            }
        } else {
            text.append("未完成 ").append(names);
        }
        if (submittedStory) {
            text.append("（故事帖已提交，待审核）");
        }
        return text.toString();
    }

    /**
     * 判断今天是否已经跑过每日任务。同一账号被多个容器或 Actions 同时配置时，
     * 重复运行会重复发帖被判灌水，所以用账号维度记录当天是否已执行。
     */
    private boolean alreadyRanToday() {
        String username = environment == null ? null : environment.get(USERNAME_KEY);
        if (StrUtil.isBlank(username)) {
            return false;
        }
        Path file = runMarkerPath(username.trim());
        try {
            if (!Files.exists(file)) {
                return false;
            }
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            return LocalDate.now().toString().equals(content);
        } catch (Exception e) {
            return false;
        }
    }

    private void markRanToday() {
        String username = environment == null ? null : environment.get(USERNAME_KEY);
        if (StrUtil.isBlank(username)) {
            return;
        }
        try {
            Path file = runMarkerPath(username.trim());
            Files.createDirectories(file.getParent());
            Files.write(file, LocalDate.now().toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 写不进去就当没记录，下次继续执行。
        }
    }

    private Path runMarkerPath(String username) {
        String key = sha1Hex(username).substring(0, 12);
        return stateDir().resolve("siemens-ran-" + key + ".txt");
    }

    /**
     * 状态文件目录。容器里 /app 在重启后仍然保留，只有重建镜像或 docker compose down
     * 才会丢；本地运行时退回系统临时目录。
     */
    static Path stateDir() {
        Path app = Paths.get("/app");
        if (Files.isDirectory(app) && Files.isWritable(app)) {
            return app.resolve("state");
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "auto-sign-in");
    }

    // ==================== 任务列表 ====================

    /** 读取每日任务列表，TaskType 1 是每日任务，EventTasksStatus 2 表示已完成。 */
    private JSONObject readTasks() {
        HttpResult result = post(supportBaseUrl + TASKS_URL,
                "action=GetAllEventTasksByActiveType&activityType=10", signHeaders());
        return parseObject(result.body);
    }

    /** 找出还没完成的每日任务代码。 */
    static List<String> pendingDailyTasks(JSONObject tasks) {
        List<String> pending = new ArrayList<>();
        if (tasks == null) {
            return pending;
        }
        JSONArray list = tasks.getJSONArray("TaskList");
        if (list == null) {
            return pending;
        }
        for (int i = 0; i < list.size(); i++) {
            JSONObject task = list.getJSONObject(i);
            if (task == null || task.getInt("TaskType", 0) != 1) {
                continue;
            }
            if (task.getInt("EventTasksStatus", 0) != 2) {
                String code = task.getStr("TaskName");
                pending.add(StrUtil.blankToDefault(code, task.getStr("TaskNameCN", "unknown")));
            }
        }
        return pending;
    }

    /** 把任务代码翻译成中文名，写进通知。 */
    static String taskDisplayName(String code) {
        if (code == null) {
            return "";
        }
        switch (code) {
            case TASK_SIGN_IN:
                return "网站签到";
            case TASK_ASK:
                return "找答案提问";
            case TASK_ANSWER:
                return "找答案回答";
            case TASK_COLLECT_QUESTION:
                return "收藏问答";
            case TASK_COMMENT_BEST_ANSWER:
                return "评论最佳答案";
            case TASK_PUBLISH_POST:
                return "论坛发帖";
            case TASK_PUBLISH_STORY:
                return "论坛发故事帖";
            case TASK_REPLY_POST:
                return "论坛回帖";
            case TASK_COLLECT_POST:
                return "收藏帖子";
            case TASK_COMMENT_POST:
                return "评论帖子";
            default:
                return code;
        }
    }

    /** 判断某个每日任务是否已经被站点标记为完成（EventTasksStatus 2）。 */
    boolean dailyTaskCompleted(String taskCode) {
        JSONObject tasks = readTasks();
        JSONArray list = tasks == null ? null : tasks.getJSONArray("TaskList");
        if (list == null) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            JSONObject task = list.getJSONObject(i);
            if (task != null && taskCode.equals(task.getStr("TaskName"))) {
                return task.getInt("EventTasksStatus", 0) == 2;
            }
        }
        return false;
    }

    // ==================== 风控 ====================

    /** 同类提交间隔不足 2 分钟时先等待，避免被判「短时间内多次发帖」。 */
    void waitBeforeSubmit(String group) {
        Long last = lastSubmitAt.get(group);
        if (last == null) {
            return;
        }
        long wait = MIN_INTERVAL_MILLIS - (System.currentTimeMillis() - last);
        if (wait <= 0) {
            return;
        }
        System.out.println("[西门子] " + group + " 距上次提交不足 2 分钟，等待 "
                + Math.round(wait / 1000.0) + " 秒");
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void markSubmitted(String group) {
        lastSubmitAt.put(group, System.currentTimeMillis());
    }

    void markTaskFailed(boolean failed) {
        lastTaskFailed = failed;
    }

    /**
     * 标记本次任务被站点额度挡下：既不算成功也不能算失败。
     * 通知里要照实列为未完成，但不要触发连续失败熔断。
     */
    void markTaskSkipped() {
        lastTaskSkipped = true;
    }

    // ==================== 签到与抽奖 ====================

    private JSONObject readSignDetail() {
        return parseObject(postSign("getsignbydate"));
    }

    private String postSign(String action) {
        HttpResult result = post(signUrl, "action=" + action, signHeaders());
        return result.body;
    }

    private Map<String, String> signHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Origin", origin(supportBaseUrl));
        headers.put("Referer", entryUrl);
        return headers;
    }

    /**
     * 签到后把攒下的抽奖机会用掉。次数由签到发放，NextSignGiveCoinAwardCount 为 true
     * 表示下一次签到会再给一次，所以每天要先检查再抽，否则机会会一直攒着。
     */
    private void playLottery(JSONObject detail) {
        int coinChances = detail == null ? 0 : detail.getInt("GetCoinAwardCount", 0);
        int bigChances = detail == null ? 0 : detail.getInt("GetBigAwardCount", 0);
        if (coinChances <= 0 && bigChances <= 0) {
            System.out.println("[西门子] 今天没有抽奖机会");
            return;
        }
        int coinDraws = 0;
        int coinTotal = 0;
        for (int i = 0; i < Math.max(coinChances, 0); i++) {
            JSONObject prize = parseObject(postSign("awardcoin"));
            if (prize == null) {
                break;
            }
            if (prize.getInt("awardType", -1) == -1) {
                System.out.println("[西门子] 抽西币未成功：" + prize.getStr("msg"));
                break;
            }
            coinDraws++;
            coinTotal += coinAmount(prize);
            System.out.println("[西门子] 抽西币：" + prize.getStr("msg"));
            if (prize.getInt("awardCoinCount", 0) <= 0) {
                break;
            }
        }
        String bigResult = "";
        int bigDraws = 0;
        for (int i = 0; i < Math.max(bigChances, 0); i++) {
            JSONObject prize = parseObject(postSign("bigaward"));
            if (prize == null) {
                break;
            }
            bigDraws++;
            String message = prize.getStr("msg");
            System.out.println("[西门子] 抽实物奖：" + message);
            if (StrUtil.isNotBlank(message)) {
                bigResult = message;
            }
            if (prize.getInt("bigAwardCount", 0) <= 0) {
                break;
            }
        }
        List<String> summary = new ArrayList<>();
        if (coinDraws > 0) {
            summary.add("西币 " + coinDraws + " 次" + (coinTotal > 0 ? "共 " + coinTotal : ""));
        }
        if (bigDraws > 0) {
            summary.add("实物奖 " + bigDraws + " 次" + (StrUtil.isNotBlank(bigResult) ? "：" + bigResult : ""));
        }
        if (!summary.isEmpty()) {
            addMessage("抽奖：", String.join("，", summary));
        }
    }

    /** 从返回里尽量解析本次抽到的西币数，解析不出按 0 处理。 */
    private static int coinAmount(JSONObject prize) {
        for (String key : new String[]{"awardCoin", "coin", "awardValue", "awardNum"}) {
            Integer value = prize.getInt(key);
            if (value != null && value > 0) {
                return value;
            }
        }
        Matcher matcher = COIN_IN_TEXT.matcher(StrUtil.blankToDefault(prize.getStr("msg"), ""));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    // ==================== AI 生成 ====================

    /** 生成内容并和上次发过的内容比对，太像就重写一次。AI 不可用时用内置文案兜底。 */
    String generateUnique(String prompt, String kind) {
        String previous = loadLastContent(kind);
        String content = null;
        try {
            content = generateWithAi(prompt, previous);
        } catch (RuntimeException e) {
            // AI 侧网络抖动或超时不该让整条任务失败，标记后直接用兜底文案。
            System.out.println("[西门子] AI 生成异常：" + e.getMessage() + "，改用内置文案");
            aiUnavailable = true;
        }
        if (StrUtil.isBlank(content)) {
            content = fallbackContent(kind);
            if (StrUtil.isBlank(content)) {
                lastTaskFailed = true;
                return null;
            }
            System.out.println("[西门子] " + kind + " 使用内置文案");
            return content;
        }
        if (StrUtil.isNotBlank(previous) && similarity(content, previous) > DUPLICATE_THRESHOLD) {
            System.out.println("[西门子] " + kind + "内容与上次过于接近，重写一次");
            String retry = generateWithAi(prompt + "\n（务必换一个完全不同的开头和例子。）", previous);
            if (StrUtil.isNotBlank(retry)) {
                content = retry;
            }
        }
        rememberContent(kind, content);
        return content;
    }

    /**
     * 内置兜底文案，只在 AI 助手不可用时使用。
     * 用「当天序号 + 账号哈希 + 第几次取用」选条目，避免多个账号同一天发出同样的内容。
     */
    String fallbackContent(String kind) {
        if (kind == null) {
            return null;
        }
        switch (kind) {
            case "posttitle":
                return pick(POST_TITLES, kind);
            case "postbody":
                return pick(POST_BODIES, kind);
            case "storytitle":
                return pick(STORY_TITLES, kind);
            case "storybody":
                return pick(STORY_BODIES, kind);
            case "asktitle":
                return pick(ASK_TITLES, kind);
            case "askbody":
                return pick(ASK_BODIES, kind);
            case "reply":
                return pick(REPLIES, kind);
            case "postcomment":
                return pick(POST_COMMENTS, kind);
            case "bestcomment":
                return pick(BEST_COMMENTS, kind);
            case "answer":
                return pick(ANSWERS, kind);
            default:
                return null;
        }
    }

    private String pick(String[] pool, String kind) {
        if (pool == null || pool.length == 0) {
            return null;
        }
        Integer used = fallbackUses.get(kind);
        int next = used == null ? 0 : used;
        fallbackUses.put(kind, next + 1);
        String username = environment == null ? null : environment.get(USERNAME_KEY);
        int seed = LocalDate.now().getDayOfYear();
        if (StrUtil.isNotBlank(username)) {
            seed += Math.abs(username.trim().hashCode() % 97);
        }
        return pool[(seed + next) % pool.length];
    }

    /** 每次请求带上当天日期和随机角度，避免连续几天生成雷同内容。 */
    String generateWithAi(String prompt, String avoid) {
        if (aiUnavailable) {
            return null;
        }
        String token = mallAccessToken();
        if (StrUtil.isBlank(token)) {
            aiUnavailable = true;
            return null;
        }
        String varied = prompt + "\n（今天是 " + LocalDate.now() + "，请换一个与以往不同的角度和说法"
                + (StrUtil.isBlank(avoid) ? "。" : "，不要与下面这段雷同：" + avoid + "。");
        String answer = askAi(token, varied);
        if (StrUtil.isBlank(answer) && StrUtil.isNotBlank(avoid)) {
            answer = askAi(token, prompt + "\n（今天是 " + LocalDate.now() + "，换个说法重新写一遍。）");
        }
        return answer;
    }

    private String askAi(String token, String prompt) {
        JSONObject payload = JSONUtil.createObj();
        payload.set("input", prompt);
        payload.set("channel_is", true);
        payload.set("internal", true);
        // chatId 传空串时服务端会新建会话；传随机值会被判为会话失效。
        payload.set("chatId", "");
        payload.set("are", "");
        payload.set("location", "");
        payload.set("organization", "");
        payload.set("username", "");
        payload.set("category", "");
        payload.set("no_rag", false);
        payload.set("stream", false);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + token);
        headers.put("Origin", "https://mall.siemens.com.cn");
        headers.put("Referer", MALL_REFERER);
        HttpResult result = post(AI_CHAT_URL, payload.toString(), "application/json;charset=UTF-8",
                headers, AI_TIMEOUT_MILLIS);
        JSONObject body = parseObject(result.body);
        if (body == null) {
            // 超时或网关异常：本次运行不再依赖 AI，后面直接用兜底文案。
            System.out.println("[西门子] AI 助手 HTTP " + result.status + "，改用内置文案");
            aiUnavailable = true;
            return null;
        }
        String answer = body.getStr("Ans");
        if (StrUtil.isBlank(answer)) {
            System.out.println("[西门子] AI 助手没有返回内容：" + body.getStr("message"));
            return null;
        }
        return StrUtil.trim(answer);
    }

    /** 用商城 client_id 走 ONEID 密码登录，拿到调用 AI 用的 access_token。 */
    private String mallAccessToken() {
        if (StrUtil.isNotBlank(cachedAiToken)) {
            return cachedAiToken;
        }
        String authUrl = ONEID_ORIGIN + "/api/v1.2/sso/" + MALL_CLIENT_ID
                + "?prompt=login&baseUrl=" + encode(ONEID_ORIGIN)
                + "&showFrameLogo=0&showFrameName=0&source=IDaaSLoginSDK";
        Page page = follow(authUrl);
        if (page == null || !isOneIdLoginPage(page.body)) {
            return null;
        }
        String flowId = group(FID_PATTERN, page.body);
        String appId = group(APP_ID_PATTERN, page.body);
        if (StrUtil.isBlank(flowId) || StrUtil.isBlank(appId)) {
            return null;
        }
        String username = environment == null ? null : environment.get(USERNAME_KEY);
        String password = environment == null ? null : environment.get(PASSWORD_KEY);
        JSONObject body = login(page.url, appId, flowId, username.trim(), password);
        if (!isLoginSuccess(body)) {
            System.out.println("[西门子] AI 助手登录失败：" + describeLoginFailure(body));
            // 密码接口要图形验证码或触发频率限制时，继续重试只会加重风控，直接放弃 AI。
            aiUnavailable = true;
            return null;
        }
        JSONObject data = body.getJSONObject("data");
        cachedAiToken = data == null ? null : data.getStr("access_token");
        return cachedAiToken;
    }

    private void rememberContent(String kind, String content) {
        try {
            Path dir = stateDir();
            Files.createDirectories(dir);
            Files.write(dir.resolve("siemens-" + kind + ".txt"), content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 写不进去不影响本次执行。
        }
    }

    /** 取上次运行留下的内容，作为今天的对照。 */
    private String loadLastContent(String kind) {
        try {
            Path file = stateDir().resolve("siemens-" + kind + ".txt");
            if (Files.exists(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            // 读不到就当没有历史。
        }
        return null;
    }

    /** 粗略比较两段文字的相似度，只用来判断是否几乎一样。 */
    static double similarity(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return 0;
        }
        Set<String> a = shingles(left);
        Set<String> b = shingles(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    private static Set<String> shingles(String text) {
        String cleaned = text.replaceAll("\\s+", "");
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 3 <= cleaned.length(); i++) {
            set.add(cleaned.substring(i, i + 3));
        }
        return set;
    }

    // ==================== 登录 ====================

    private JSONObject login(String loginPageUrl, String appId, String flowId, String username, String password) {
        JSONObject payload = JSONUtil.createObj();
        payload.set("agreeConsent", true);
        payload.set("username", username);
        payload.set("password", password);
        payload.set("captchaCode", "");
        payload.set("captchaText", "");
        payload.set("fId", flowId);
        payload.set("userType", "default");
        payload.set("language", "zh");
        payload.set("readTerms", new JSONArray());
        String timestamp = String.valueOf(System.currentTimeMillis());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Origin", origin(loginPageUrl));
        headers.put("Referer", loginPageUrl);
        headers.put("idaasAppId", appId);
        headers.put("idaasTimeStamp", timestamp);
        headers.put("idaasSign", sha1Hex(appId + timestamp));
        HttpResult result = post(origin(loginPageUrl) + LOGIN_API_PATH, payload.toString(),
                "application/json;charset=UTF-8", headers, TIMEOUT_MILLIS);
        JSONObject loginJson = parseObject(result.body);
        System.out.println("[西门子] 登录 HTTP " + result.status
                + (loginJson == null ? "" : " " + loginJson.getStr("code")));
        return loginJson;
    }

    static boolean isCaptchaRequired(JSONObject body) {
        String code = body == null ? null : body.getStr("code");
        String message = body == null ? null : body.getStr("message");
        return "Params.Blank.CaptchaText".equals(code) || "Params.Blank.CaptchaCode".equals(code)
                || "Operation.Failure.Captcha.Invalid".equals(code)
                || (message != null && message.contains("Captcha"));
    }

    static boolean isLoginThrottled(JSONObject body) {
        String code = body == null ? null : body.getStr("code");
        return code != null && code.contains("Too.Frequency");
    }

    static boolean isLoginSuccess(JSONObject body) {
        return body != null && ("Operation.Success".equals(body.getStr("code"))
                || Boolean.TRUE.equals(body.getBool("success")));
    }

    static boolean isOneIdLoginPage(String html) {
        return html != null && html.contains("window.idaas") && html.contains("idaasAppId");
    }

    static String describeLoginFailure(JSONObject body) {
        String message = body == null ? null : body.getStr("message");
        String code = body == null ? null : body.getStr("code");
        String token = StrUtil.blankToDefault(message, code);
        if (StrUtil.isBlank(token)) {
            return "登录失败";
        }
        if (containsHan(token)) {
            return token;
        }
        return describeLoginCode(token);
    }

    static String describeLoginCode(String code) {
        if ("Operation.Failure.Username.Or.Password.Error".equals(code)) {
            return "用户名或密码错误";
        }
        if ("Operation.Failure.Auth.Too.Frequency".equals(code)
                || code != null && code.contains("Too.Frequency")) {
            return "登录太频繁已被限制，请稍后重试";
        }
        if ("Operation.Failure.User.Locked".equals(code) || code != null && code.contains("User.Locked")) {
            return "账号被临时锁定，请稍后重试";
        }
        if ("Params.Blank.CaptchaText".equals(code) || "Params.Blank.CaptchaCode".equals(code)
                || "Operation.Failure.Captcha.Invalid".equals(code)) {
            return "登录需要图形验证码，请稍后重试";
        }
        if ("Operation.Failure.FId.Not.Found".equals(code) || "Params.Blank.FId".equals(code)) {
            return "登录会话已失效";
        }
        if (code != null && code.contains("Captcha")) {
            return "登录需要验证码，请稍后重试";
        }
        if ("NEED_TWO_FACTOR".equals(code)) {
            return "账号开启了二次验证，无法自动登录";
        }
        if ("NEED_UPDATE_PASSWORD".equals(code)) {
            return "账号需要先修改密码";
        }
        if ("NEED_READ_TERMS".equals(code)) {
            return "账号需要先同意协议";
        }
        return code;
    }

    // ==================== 签到结果判定 ====================

    /**
     * 未登录时接口返回 TodayHaveSign=true，且 SignDetail.UserID 为 -1。
     * 已登录但今天还没签到时，SignDetail 为 null，TodayHaveSign 为 false。
     */
    static boolean isTrustedSignDetail(JSONObject detail) {
        if (detail == null || !detail.containsKey("TodayHaveSign")) {
            return false;
        }
        JSONObject signDetail = detail.getJSONObject("SignDetail");
        if (signDetail == null || signDetail.isEmpty()) {
            return !Boolean.TRUE.equals(detail.getBool("TodayHaveSign"));
        }
        Integer userId = signDetail.getInt("UserID");
        return userId != null && userId > 0;
    }

    static boolean signedSuccessfully(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.trim();
        if ("true".equalsIgnoreCase(trimmed) || "1".equals(trimmed)) {
            return true;
        }
        if (trimmed.isEmpty() || "false".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
            return false;
        }
        JSONObject json = parseObject(trimmed);
        if (json == null) {
            return trimmed.contains("成功");
        }
        if (json.containsKey("TodayHaveSign")) {
            return Boolean.TRUE.equals(json.getBool("TodayHaveSign"));
        }
        if (json.containsKey("success") && !Boolean.TRUE.equals(json.getBool("success"))) {
            return false;
        }
        if (json.containsKey("status")) {
            return json.getInt("status", 0) == 1;
        }
        return true;
    }

    // ==================== 请求与工具 ====================

    HttpResult get(String url) {
        return exchange("GET", url, null, null, null, TIMEOUT_MILLIS);
    }

    HttpResult post(String url, String body, Map<String, String> headers) {
        return exchange("POST", url, body, "application/x-www-form-urlencoded", headers, TIMEOUT_MILLIS);
    }

    HttpResult post(String url, String body, String contentType, Map<String, String> headers, int timeoutMillis) {
        return exchange("POST", url, body, contentType, headers, timeoutMillis);
    }

    Page follow(String url) {
        String current = url;
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpResult result = get(current);
            if (result.status >= 300 && result.status < 400 && StrUtil.isNotBlank(result.location)) {
                current = resolveUrl(current, result.location.trim());
                continue;
            }
            return new Page(current, result.body);
        }
        return null;
    }

    private HttpResult exchange(String method, String url, String body, String contentType,
                                Map<String, String> headers, int timeoutMillis) {
        HttpRequest request = "POST".equals(method) ? HttpRequest.post(url) : HttpRequest.get(url);
        // cookie("") 关闭 Hutool 全局 Cookie，避免把别的站点会话带进来；本任务自己维护 Cookie。
        request.cookie(StrUtil.blankToDefault(cookies.headerFor(url), ""))
                .setFollowRedirects(false)
                .timeout(timeoutMillis)
                .header("User-Agent", userAgent);
        if (headers != null) {
            request.headerMap(headers, true);
        }
        if (body != null) {
            request.body(body);
        }
        if (contentType != null) {
            request.contentType(contentType);
        }
        HttpResponse response = request.execute();
        cookies.store(url, response);
        return new HttpResult(response.getStatus(), response.body(), response.header("Location"));
    }

    private Profile loadProfile() {
        Page page = follow(entryUrl);
        if (page == null || isOneIdLoginPage(page.body)) {
            return Profile.empty();
        }
        Matcher userId = USER_ID_PATTERN.matcher(page.body);
        return new Profile(userId.find() ? userId.group(1) : "",
                readableText(elementText(page.body, "h6.singleLine")),
                readableText(elementText(page.body, "a[href*=MyPrize]")));
    }

    /** 取首页上的用户名和西币，用 Jsoup 解析，失败返回 null。 */
    private static String elementText(String html, String selector) {
        try {
            org.jsoup.nodes.Element element = org.jsoup.Jsoup.parse(html).selectFirst(selector);
            return element == null ? null : element.text();
        } catch (Exception e) {
            return null;
        }
    }

    String supportBaseUrl() {
        return supportBaseUrl;
    }

    String currentUserId() {
        return currentUserId;
    }

    Map<String, String> supportHeaders(String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Origin", origin(supportBaseUrl));
        headers.put("Referer", referer);
        return headers;
    }

    static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String resolveUrl(String current, String location) {
        return URI.create(current).resolve(location).toString();
    }

    static String origin(String url) {
        URI uri = URI.create(url);
        if (uri.getPort() < 0) {
            return uri.getScheme() + "://" + uri.getHost();
        }
        return uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
    }

    static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    static String cleanTitle(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("标题：", "").replace("标题:", "").trim();
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    static String inputValue(String html, String name) {
        if (html == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("name=\"" + name + "\"[^>]*value=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE)
                .matcher(html);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String attributeValue(String html, String name) {
        if (html == null) {
            return null;
        }
        Matcher matcher = Pattern.compile(name + "=\"([^\"]*)\"").matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    static String readableText(String text) {
        if (StrUtil.isBlank(text) || text.contains("@")) {
            return null;
        }
        return StrUtil.trim(text);
    }

    static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180);
    }

    static JSONObject parseObject(String body) {
        if (StrUtil.isBlank(body) || !JSONUtil.isTypeJSONObject(body)) {
            return null;
        }
        return JSONUtil.parseObj(body);
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean containsHan(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) >= 0x4E00) {
                return true;
            }
        }
        return false;
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return StrUtil.isBlank(value) ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static final class Page {
        final String url;
        final String body;

        Page(String url, String body) {
            this.url = url;
            this.body = body;
        }
    }

    static final class HttpResult {
        final int status;
        final String body;
        final String location;

        HttpResult(int status, String body, String location) {
            this.status = status;
            this.body = body;
            this.location = location;
        }
    }

    static final class Profile {
        final String userId;
        final String name;
        final String coin;

        Profile(String userId, String name, String coin) {
            this.userId = userId;
            this.name = name;
            this.coin = coin;
        }

        static Profile empty() {
            return new Profile("", null, null);
        }
    }

    static final class CookieJar {
        private final List<StoredCookie> cookies = new ArrayList<>();

        void store(String requestUrl, HttpResponse response) {
            if (response == null || response.headers() == null) {
                return;
            }
            for (Map.Entry<String, List<String>> entry : response.headers().entrySet()) {
                if (entry.getKey() != null && "set-cookie".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                    for (String header : entry.getValue()) {
                        store(requestUrl, header);
                    }
                }
            }
        }

        void store(String requestUrl, String setCookie) {
            if (StrUtil.isBlank(setCookie)) {
                return;
            }
            List<HttpCookie> parsed;
            try {
                parsed = HttpCookie.parse(setCookie);
            } catch (IllegalArgumentException e) {
                return;
            }
            String host = hostOf(requestUrl);
            for (HttpCookie cookie : parsed) {
                if (StrUtil.isBlank(cookie.getName())) {
                    continue;
                }
                if (cookie.getMaxAge() == 0) {
                    remove(cookie.getName(), host);
                    continue;
                }
                StoredCookie stored = new StoredCookie();
                stored.name = cookie.getName();
                stored.value = cookie.getValue() == null ? "" : cookie.getValue();
                if (StrUtil.isBlank(cookie.getDomain())) {
                    stored.domain = host;
                    stored.hostOnly = true;
                } else {
                    stored.domain = stripDot(cookie.getDomain().toLowerCase());
                    stored.hostOnly = false;
                }
                stored.path = StrUtil.blankToDefault(cookie.getPath(), "/");
                stored.secure = cookie.getSecure();
                replace(stored);
            }
        }

        String headerFor(String url) {
            StringBuilder header = new StringBuilder();
            for (StoredCookie cookie : cookies) {
                if (!matches(cookie, url)) {
                    continue;
                }
                if (header.length() > 0) {
                    header.append("; ");
                }
                header.append(cookie.name).append('=').append(cookie.value);
            }
            return header.toString();
        }

        private void replace(StoredCookie stored) {
            Iterator<StoredCookie> iterator = cookies.iterator();
            while (iterator.hasNext()) {
                StoredCookie current = iterator.next();
                if (current.name.equals(stored.name) && current.domain.equals(stored.domain)
                        && current.path.equals(stored.path)) {
                    iterator.remove();
                }
            }
            cookies.add(stored);
        }

        private void remove(String name, String host) {
            cookies.removeIf(cookie -> cookie.name.equals(name)
                    && (cookie.domain.equals(host) || host.endsWith("." + cookie.domain)));
        }

        private static boolean matches(StoredCookie cookie, String url) {
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            if (cookie.secure && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            if (cookie.hostOnly) {
                if (!host.equals(cookie.domain)) {
                    return false;
                }
            } else if (!host.equals(cookie.domain) && !host.endsWith("." + cookie.domain)) {
                return false;
            }
            String path = StrUtil.blankToDefault(uri.getPath(), "/");
            String cookiePath = cookie.path;
            if (cookiePath.endsWith("/")) {
                return path.startsWith(cookiePath);
            }
            return path.equals(cookiePath) || path.startsWith(cookiePath + "/");
        }

        private static String hostOf(String url) {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase();
        }

        private static String stripDot(String domain) {
            return domain.startsWith(".") ? domain.substring(1) : domain;
        }

        /** 把当前 cookie 写到文件，下次运行不必再密码登录。 */
        void save(Path file) throws java.io.IOException {
            StringBuilder text = new StringBuilder();
            for (StoredCookie cookie : cookies) {
                text.append(cookie.name).append('\t')
                        .append(cookie.value).append('\t')
                        .append(cookie.domain).append('\t')
                        .append(cookie.path).append('\t')
                        .append(cookie.hostOnly ? '1' : '0').append('\t')
                        .append(cookie.secure ? '1' : '0')
                        .append('\n');
            }
            Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
        }

        /** 从文件读回 cookie；文件不存在或格式不对时返回 false。 */
        boolean load(Path file) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return false;
            }
            List<StoredCookie> restored = new ArrayList<>();
            for (String line : lines) {
                if (StrUtil.isBlank(line)) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 6) {
                    continue;
                }
                StoredCookie cookie = new StoredCookie();
                cookie.name = parts[0];
                cookie.value = parts[1];
                cookie.domain = parts[2];
                cookie.path = parts[3];
                cookie.hostOnly = "1".equals(parts[4]);
                cookie.secure = "1".equals(parts[5]);
                if (StrUtil.isNotBlank(cookie.name)) {
                    restored.add(cookie);
                }
            }
            if (restored.isEmpty()) {
                return false;
            }
            cookies.clear();
            cookies.addAll(restored);
            return true;
        }

        void clear() {
            cookies.clear();
        }
    }

    private static final class StoredCookie {
        private String name;
        private String value;
        private String domain;
        private String path;
        private boolean hostOnly;
        private boolean secure;
    }
}
