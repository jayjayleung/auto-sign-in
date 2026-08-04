package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.ElementHandle;
import com.ruiyun.jvppeteer.api.core.Frame;
import com.ruiyun.jvppeteer.api.core.JSHandle;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.api.core.Response;
import com.ruiyun.jvppeteer.cdp.core.Puppeteer;
import com.ruiyun.jvppeteer.cdp.entities.Cookie;
import com.ruiyun.jvppeteer.cdp.entities.LaunchOptions;
import com.ruiyun.jvppeteer.cdp.entities.Protocol;
import com.ruiyun.jvppeteer.cdp.entities.RevisionInfo;
import com.ruiyun.jvppeteer.cdp.entities.Viewport;
import com.ruiyun.jvppeteer.cdp.entities.WaitForSelectorOptions;
import com.ruiyun.jvppeteer.common.Product;
import com.ruiyun.jvppeteer.exception.TimeoutException;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jayjay.autosignin.entity.MessageList;
import org.jayjay.autosignin.util.MessageUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
@EqualsAndHashCode(callSuper = true)
public class YongHoneCheckInTask extends CheckInTask {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int SELECTOR_TIMEOUT_MS = 15000;
    private static final int OPTIONAL_SELECTOR_TIMEOUT_MS = 3000;
    private static final int NAVIGATION_TIMEOUT_MS = 30000;
    private static final int COOKIE_WAIT_ATTEMPTS = 60;
    private static final long COOKIE_WAIT_INTERVAL_MS = 500L;
    private static final int COMMENT_RESULT_WAIT_ATTEMPTS = 30;
    private static final long COMMENT_RESULT_WAIT_INTERVAL_MS = 500L;
    private static final int MAX_RETRY_DELAY_MS = 60000;
    private static final Pattern POST_ID_PATTERN = Pattern.compile("post_\\d+");
    private static final Pattern USER_ID_QUERY_PATTERN = Pattern.compile(
            "[?&](?:authorid|uid)=([^&#]+)", Pattern.CASE_INSENSITIVE);

    // 重试五次，首次执行不计入重试次数。
    int maxRetries = 5;
    int retryCount = 0;
    boolean success = false;
    int delay = 3000;
    String loginUrl = "https://club.yonghongtech.com/member.php?mod=logging&action=login&phonelogin=no";
    String checkInUrl = "https://club.yonghongtech.com/home.php?mod=space&uid=${user_id}&do=signlog&from=space";
    String cjUrl = "https://club.yonghongtech.com/plugin.php?id=hux_zp3:hux_zp3";
    String publishUrl = "https://club.yonghongtech.com/forum.php?mod=post&action=newthread&fid=80";

    @Override
    public MessageList messageList() {
        return new MessageList("YongHong 签到", listMessage);
    }

    @Override
    public CheckInTask run() {
        success = false;
        retryCount = 0;
        listMessage.clear();
        isRun = true;

        String yhUsername = System.getenv("YH_USERNAME");
        String yhPassword = System.getenv("YH_PASSWORD");
        if (StrUtil.isBlank(yhUsername) || StrUtil.isBlank(yhPassword)) {
            System.out.println("yonghong 账号密码未配置，跳过签到");
            isRun = false;
            return this;
        }

        boolean doubleDay = isDoubleDay(LocalDate.now(BUSINESS_ZONE).getDayOfWeek());
        RunProgress progress = new RunProgress(doubleDay ? 3 : 0, !doubleDay ? 3 : 0);
        // maxRetries 表示首次执行失败后允许的重试次数，因此总尝试次数为 maxRetries + 1。
        int maxAttempts = Math.max(1, maxRetries + 1);
        long retryDelay = Math.min(MAX_RETRY_DELAY_MS, Math.max(0L, (long) delay));
        for (int attempt = 1; attempt <= maxAttempts && !success; attempt++) {
            retryCount = retryCountForAttempt(attempt);
            if (attempt > 1) {
                System.out.println("出现异常，正在重试第" + (attempt - 1) + "次...");
                listMessage.clear();
            }
            System.out.println("yonghong 签到任务开始（第" + attempt + "次尝试）");
            try {
                runAttempt(yhUsername, yhPassword, progress);
                if (!progress.isComplete()) {
                    throw new IllegalStateException("任务阶段未全部完成");
                }
                appendProgressMessages(progress);
                success = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                listMessage.clear();
                addMessage(interruptedMessage(progress));
                break;
            } catch (NonRetryableTaskException e) {
                e.printStackTrace();
                listMessage.clear();
                addMessage("永洪签到失败：" + e.getMessage());
                break;
            } catch (Exception e) {
                e.printStackTrace();
                // 所有副作用已经确认完成时，清理阶段的异常不应触发重复操作。
                if (progress.isComplete()) {
                    appendProgressMessages(progress);
                    success = true;
                    break;
                }
                String uncertain = progress.uncertainOperation();
                listMessage.clear();
                if (StrUtil.isNotBlank(uncertain)) {
                    addMessage("永洪签到状态未确认：" + uncertain + "，为避免重复操作已停止重试");
                    break;
                }
                if (attempt >= maxAttempts) {
                    addMessage("永洪签到失败，请查看日志");
                    break;
                }
                if (!sleepBeforeRetry(retryDelay)) {
                    listMessage.clear();
                    addMessage(interruptedMessage(progress));
                    break;
                }
                retryDelay = nextRetryDelay(retryDelay);
            }
        }
        return this;
    }

    /** 执行一次流程；已确认完成的副作用由 progress 负责跳过。 */
    private void runAttempt(String yhUsername, String yhPassword, RunProgress progress) throws Exception {
        // 浏览器下载也属于本次尝试的一部分，不能绕过重试边界直接中断主流程。
        RevisionInfo revisionInfo = Puppeteer.downloadBrowser();
        System.out.println("revisionInfo: " + revisionInfo);

        ArrayList<String> argList = new ArrayList<>();
        // withHeadless 是否开启无头模式，无头模式不会显示浏览器
        argList.add("--no-sandbox");
        argList.add("--disable-setuid-sandbox");
        LaunchOptions options = LaunchOptions.builder()
                .args(argList)
                .defaultViewport(new Viewport(1900, 1080))
                .headless(true)
                .protocol(Protocol.CDP)
                .product(Product.Chrome)
                .build();

        try (Browser browser = Puppeteer.launch(options)) {
            Page page = null;
            String userId;
            try {
                page = browser.newPage();
                configurePage(page);
                navigate(page, loginUrl);
                page.waitForSelector("input[name='username']", selectorOptions(SELECTOR_TIMEOUT_MS));
                page.type("input[name='username']", yhUsername);
                page.waitForSelector("input[name='password']", selectorOptions(SELECTOR_TIMEOUT_MS));
                page.type("input[name='password']", yhPassword);

                ElementHandle loginBtn = page.waitForSelector("button[name='loginsubmit']",
                        selectorOptions(SELECTOR_TIMEOUT_MS));
                try {
                    System.out.println("点击登录");
                    loginBtn.click();
                } finally {
                    dispose(loginBtn);
                }

                Cookie userCookie = waitForLoginCookie(page);
                if (userCookie == null || StrUtil.isBlank(userCookie.getValue())) {
                    throw new IOException("登录确认超时：约30秒内未获取 user_id Cookie");
                }
                userId = userCookie.getValue();
                addMessage("UID：", userId);

                String profileUrl = checkInUrl.replace("${user_id}", userId);
                navigate(page, profileUrl);
                addProfileMessage(page);
            } finally {
                closePage(page);
            }

            ensureLottery(browser, progress);
            ensurePost(browser, "签到", "签到", "签到", userId, progress.checkInPost());
            ensurePost(browser, "上班卡", "滴~", "滴，上班卡~", userId, progress.workPost());
        }
    }

    private void ensureLottery(Browser browser, RunProgress progress) throws Exception {
        if (progress.lotteryCompleted()) {
            return;
        }

        Page page = null;
        try {
            page = browser.newPage();
            configurePage(page);
            navigate(page, cjUrl);
            ElementHandle startButton = page.waitForSelector("#startbtn", selectorOptions(SELECTOR_TIMEOUT_MS));
            String pointMessage = readPointMessage(page);
            installLotteryResultObserver(page);
            progress.beginLottery();
            try {
                startButton.click();
            } finally {
                dispose(startButton);
            }

            JSHandle resultHandle = page.waitForFunction(
                    "function() {"
                            + "var element = document.querySelector('#main_messaqge div p');"
                            + "var value = element && (element.innerText || '').trim();"
                            + "return Boolean(window.__autoSignInLotteryChanged && value);"
                            + "}",
                    selectorOptions(SELECTOR_TIMEOUT_MS));
            dispose(resultHandle);
            String lotteryMessage = normalizeText(page.$eval("#main_messaqge div p", "ele=>ele.innerText"));
            if (StrUtil.isBlank(lotteryMessage)) {
                throw new IllegalStateException("抽奖结果为空");
            }
            progress.completeLottery(pointMessage, lotteryMessage);
        } finally {
            closePage(page);
        }
    }

    private void ensurePost(Browser browser, String title, String content, String comment,
                            String expectedUserId, PostProgress postProgress) throws Exception {
        if (!postProgress.isComplete()) {
            publishPostInternal(browser, title, content, comment, expectedUserId, postProgress);
        }
        if (!postProgress.isComplete()) {
            throw new IllegalStateException("论坛" + title + "发布未完成");
        }
    }

    private void configurePage(Page page) {
        page.setUserAgent(userAgent);
        page.setDefaultTimeout(SELECTOR_TIMEOUT_MS);
        page.setDefaultNavigationTimeout(NAVIGATION_TIMEOUT_MS);
    }

    private Cookie waitForLoginCookie(Page page) throws Exception {
        for (int attempt = 0; attempt < COOKIE_WAIT_ATTEMPTS; attempt++) {
            Cookie cookie = findCookie(page, "user_id");
            if (cookie != null) {
                return cookie;
            }
            String loginError = extractLoginError(page.content());
            if (StrUtil.isNotBlank(loginError)) {
                throw new NonRetryableTaskException("登录失败：" + loginError);
            }
            if (attempt + 1 < COOKIE_WAIT_ATTEMPTS) {
                Thread.sleep(COOKIE_WAIT_INTERVAL_MS);
            }
        }
        return null;
    }

    private static Cookie findCookie(Page page, String cookieName) throws Exception {
        List<Cookie> cookies = page.cookies();
        if (cookies == null) {
            return null;
        }
        Optional<Cookie> cookie = cookies.stream()
                .filter(item -> cookieName.equalsIgnoreCase(item.getName()))
                .filter(item -> StrUtil.isNotBlank(item.getValue()))
                .findFirst();
        return cookie.orElse(null);
    }

    private void addProfileMessage(Page page) throws Exception {
        ElementHandle profile = null;
        try {
            profile = page.waitForSelector(".nex_Home_intel h5",
                    selectorOptions(OPTIONAL_SELECTOR_TIMEOUT_MS));
            String user = normalizeText(page.$eval(".nex_Home_intel h5", "ele=>ele.innerText"));
            if (StrUtil.isNotBlank(user)) {
                System.out.println(user);
                addMessage(lineMsg("用户名：").append(user.replaceAll(MessageUtil.lineEnd, "")));
            }
        } catch (TimeoutException ignored) {
            // 用户名不是签到前置条件，页面未提供该节点时继续执行后续阶段。
        } finally {
            dispose(profile);
        }
    }

    private String readPointMessage(Page page) throws Exception {
        Document document = Jsoup.parse(page.content());
        Elements point = document.select("#ct div ul li:eq(2) > font:eq(3)");
        if (point.isEmpty()) {
            return null;
        }
        String pointStr = point.text();
        return StrUtil.isBlank(pointStr) ? null : pointStr;
    }

    private void installLotteryResultObserver(Page page) throws Exception {
        ElementHandle result = page.waitForSelector("#main_messaqge div p",
                selectorOptions(SELECTOR_TIMEOUT_MS));
        try {
            page.evaluate("function() {"
                    + "var element = document.querySelector('#main_messaqge div p');"
                    + "if (!element) { throw new Error('抽奖结果节点未加载'); }"
                    + "if (window.__autoSignInLotteryObserver) {"
                    + "window.__autoSignInLotteryObserver.disconnect();"
                    + "}"
                    + "window.__autoSignInLotteryChanged = false;"
                    + "window.__autoSignInLotteryObserver = new MutationObserver(function() {"
                    + "window.__autoSignInLotteryChanged = true;"
                    + "});"
                    + "window.__autoSignInLotteryObserver.observe(element, {"
                    + "childList: true, characterData: true, subtree: true"
                    + "});"
                    + "}");
        } finally {
            dispose(result);
        }
    }

    private void publishPostInternal(Browser browser, String title, String content,
                                     String comment, String expectedUserId,
                                     PostProgress progress) throws Exception {
        if (!title.equals(progress.title())) {
            throw new IllegalArgumentException("帖子状态与标题不匹配");
        }
        if (progress.uncertainOperation() != null) {
            throw new NonRetryableTaskException(progress.uncertainOperation());
        }
        if (progress.requiredComments() > 0 && StrUtil.isBlank(comment)) {
            throw new NonRetryableTaskException("论坛" + title + "评论内容为空");
        }
        if (progress.requiredComments() > 0 && StrUtil.isBlank(expectedUserId)) {
            throw new NonRetryableTaskException("论坛" + title + "无法确认当前登录用户");
        }

        Page page = null;
        try {
            page = browser.newPage();
            configurePage(page);
            if (progress.threadCreated()) {
                navigate(page, progress.threadUrl());
                verifyThreadPage(page, title);
            } else {
                navigate(page, publishUrl);
                page.waitForSelector("#typeid_ctrl_menu li:nth-child(2)",
                        selectorOptions(SELECTOR_TIMEOUT_MS));
                page.evaluate("var items = document.querySelectorAll('#typeid_ctrl_menu li');"
                        + "if (items.length < 2) { throw new Error('帖子分类选项未加载'); }"
                        + "items[1].click();");
                page.waitForSelector("#subject", selectorOptions(SELECTOR_TIMEOUT_MS));
                page.type("#subject", title);

                ElementHandle iframeHandle = page.waitForSelector("iframe#e_iframe",
                        selectorOptions(SELECTOR_TIMEOUT_MS));
                try {
                    Frame frame = iframeHandle.contentFrame();
                    if (frame == null) {
                        throw new IllegalStateException("发帖编辑器 iframe 不可用");
                    }
                    ElementHandle body = frame.waitForSelector("body", selectorOptions(SELECTOR_TIMEOUT_MS));
                    try {
                        body.type(content);
                    } finally {
                        dispose(body);
                    }
                } finally {
                    dispose(iframeHandle);
                }

                ElementHandle submit = page.waitForSelector("#postsubmit", selectorOptions(SELECTOR_TIMEOUT_MS));
                progress.beginCreation();
                try {
                    submit.click();
                } finally {
                    dispose(submit);
                }
                verifyThreadPage(page, title);
                progress.completeCreation(page.url());
            }

            while (!progress.isComplete()) {
                waitForCommentFormReady(page);
                ElementHandle commentBox = page.waitForSelector("#fastpostmessage",
                        selectorOptions(SELECTOR_TIMEOUT_MS));
                ElementHandle commentSubmit = page.waitForSelector("#fastpostsubmit",
                        selectorOptions(SELECTOR_TIMEOUT_MS));
                try {
                    commentBox.type(comment);
                    List<String> existingPostIds = readPostIds(page);
                    progress.beginComment();
                    commentSubmit.click();
                    waitForCommentResult(page, existingPostIds, comment, expectedUserId);
                    progress.completeComment();
                } finally {
                    dispose(commentBox);
                    dispose(commentSubmit);
                }
            }
        } finally {
            closePage(page);
        }
    }

    private void verifyThreadPage(Page page, String expectedTitle) throws Exception {
        ElementHandle subject = page.waitForSelector("#thread_subject",
                selectorOptions(NAVIGATION_TIMEOUT_MS));
        try {
            String actualTitle = normalizeText(page.$eval("#thread_subject", "ele=>ele.innerText"));
            if (!titlesMatch(expectedTitle, actualTitle)) {
                throw new NonRetryableTaskException("帖子详情标题不匹配");
            }
        } finally {
            dispose(subject);
        }
    }

    private void waitForCommentFormReady(Page page) throws Exception {
        JSHandle ready = page.waitForFunction(
                "function() {"
                        + "var input = document.querySelector('#fastpostmessage');"
                        + "var submit = document.querySelector('#fastpostsubmit');"
                        + "return input && submit && input.value.trim() === '' && !submit.disabled;"
                        + "}",
                selectorOptions(SELECTOR_TIMEOUT_MS));
        dispose(ready);
    }

    private List<String> readPostIds(Page page) throws Exception {
        Document document = Jsoup.parse(page.content());
        List<String> postIds = new ArrayList<>();
        for (Element post : document.select("div[id^=post_]")) {
            if (POST_ID_PATTERN.matcher(post.id()).matches()) {
                postIds.add(post.id());
            }
        }
        return postIds;
    }

    private void waitForCommentResult(Page page, List<String> existingPostIds,
                                      String expectedComment, String expectedUserId) throws Exception {
        for (int attempt = 0; attempt < COMMENT_RESULT_WAIT_ATTEMPTS; attempt++) {
            if (commentResultMatches(page.content(), existingPostIds, expectedComment, expectedUserId)) {
                return;
            }
            if (attempt + 1 < COMMENT_RESULT_WAIT_ATTEMPTS) {
                Thread.sleep(COMMENT_RESULT_WAIT_INTERVAL_MS);
            }
        }
        throw new IOException("评论结果确认超时");
    }

    static boolean commentResultMatches(String html, List<String> existingPostIds,
                                        String expectedComment, String expectedUserId) {
        if (StrUtil.isBlank(html) || StrUtil.isBlank(expectedComment) || StrUtil.isBlank(expectedUserId)) {
            return false;
        }
        Set<String> existing = existingPostIds == null
                ? new HashSet<>()
                : new HashSet<>(existingPostIds);
        String expectedText = normalizeText(expectedComment);
        Document document = Jsoup.parse(html);
        for (Element post : document.select("div[id^=post_]")) {
            String postId = post.id();
            if (!POST_ID_PATTERN.matcher(postId).matches() || existing.contains(postId)) {
                continue;
            }
            if (!postBelongsToUser(post, expectedUserId)) {
                continue;
            }
            String numericId = postId.substring("post_".length());
            Element body = post.select("#postmessage_" + numericId).first();
            if (body != null && normalizeText(body.text()).contains(expectedText)) {
                return true;
            }
        }
        return false;
    }

    private static boolean postBelongsToUser(Element post, String expectedUserId) {
        Elements identityNodes = post.select(
                "a[href*=authorid=], img[src*=avatar.php][src*=uid=]");
        for (Element identityNode : identityNodes) {
            String attribute = identityNode.hasAttr("href")
                    ? identityNode.attr("href")
                    : identityNode.attr("src");
            Matcher matcher = USER_ID_QUERY_PATTERN.matcher(attribute);
            while (matcher.find()) {
                if (expectedUserId.equals(matcher.group(1))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void appendProgressMessages(RunProgress progress) {
        if (StrUtil.isNotBlank(progress.pointMessage())) {
            addMessage(lineMsg("洪豆：").append(progress.pointMessage()));
        }
        if (StrUtil.isNotBlank(progress.lotteryMessage())) {
            addMessage(progress.lotteryMessage());
        }
        if (progress.checkInPost().isComplete()) {
            addMessage("论坛签到发布完成");
        }
        if (progress.workPost().isComplete()) {
            addMessage("论坛上班卡发布完成");
        }
    }

    static boolean isDoubleDay(DayOfWeek dayOfWeek) {
        return dayOfWeek != null && dayOfWeek.getValue() % 2 == 0;
    }

    static int retryCountForAttempt(int attempt) {
        return Math.max(0, attempt - 1);
    }

    static String extractLoginError(String html) {
        if (StrUtil.isBlank(html)) {
            return null;
        }
        Document document = Jsoup.parse(html);
        Elements candidates = document.select("#messagetext.alert_error, .alert_error");
        for (Element candidate : candidates) {
            String message = normalizeText(candidate.text());
            if (StrUtil.isNotBlank(message)) {
                return message;
            }
        }
        return null;
    }

    static boolean titlesMatch(String expected, String actual) {
        return !StrUtil.isBlank(expected)
                && normalizeText(expected).equals(normalizeText(actual));
    }

    private static String normalizeText(Object value) {
        return value == null ? "" : value.toString().replaceAll("\\s+", " ").trim();
    }

    private static Response navigate(Page page, String url) throws Exception {
        Response response = page.goTo(url);
        if (response != null && !response.ok()) {
            throw new IOException("页面请求失败，HTTP 状态码：" + response.status());
        }
        return response;
    }

    private static WaitForSelectorOptions selectorOptions(int timeoutMs) {
        WaitForSelectorOptions options = new WaitForSelectorOptions();
        options.setTimeout(timeoutMs);
        return options;
    }

    private static long nextRetryDelay(long currentDelay) {
        if (currentDelay <= 0) {
            return 0;
        }
        return Math.min(MAX_RETRY_DELAY_MS, currentDelay * 2);
    }

    private static boolean sleepBeforeRetry(long milliseconds) {
        if (milliseconds <= 0) {
            return true;
        }
        try {
            Thread.sleep(milliseconds);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String interruptedMessage(RunProgress progress) {
        String uncertain = progress.uncertainOperation();
        if (StrUtil.isNotBlank(uncertain)) {
            return "永洪签到状态未确认：" + uncertain + "，任务线程被中断";
        }
        return "永洪签到失败，任务线程被中断";
    }

    private static void dispose(JSHandle handle) {
        if (handle != null) {
            try {
                handle.dispose();
            } catch (Exception ignored) {
                // 页面关闭时句柄可能已经失效，不影响任务结果。
            }
        }
    }

    private static void closePage(Page page) {
        if (page != null) {
            try {
                if (page.isClosed()) {
                    return;
                }
                page.close();
            } catch (Exception ignored) {
                // 浏览器关闭会一并回收页面，避免清理异常覆盖原始失败原因。
            }
        }
    }

    private enum OperationState {
        NOT_STARTED,
        IN_FLIGHT,
        COMPLETED
    }

    static final class PostProgress {
        private final String title;
        private final int requiredComments;
        private OperationState creationState = OperationState.NOT_STARTED;
        private String threadUrl;
        private int confirmedComments;
        private boolean commentInFlight;

        PostProgress(String title, int requiredComments) {
            this.title = title;
            this.requiredComments = Math.max(0, requiredComments);
        }

        String title() {
            return title;
        }

        int requiredComments() {
            return requiredComments;
        }

        boolean threadCreated() {
            return creationState == OperationState.COMPLETED;
        }

        boolean isComplete() {
            return threadCreated() && confirmedComments >= requiredComments;
        }

        String threadUrl() {
            return threadUrl;
        }

        void beginCreation() {
            if (creationState != OperationState.NOT_STARTED) {
                throw new IllegalStateException("帖子创建状态不允许重复开始");
            }
            creationState = OperationState.IN_FLIGHT;
        }

        void completeCreation(String url) {
            if (creationState != OperationState.IN_FLIGHT || StrUtil.isBlank(url)) {
                throw new IllegalStateException("帖子创建结果无效");
            }
            threadUrl = url;
            creationState = OperationState.COMPLETED;
        }

        void beginComment() {
            if (!threadCreated() || commentInFlight || confirmedComments >= requiredComments) {
                throw new IllegalStateException("评论状态不允许开始");
            }
            commentInFlight = true;
        }

        void completeComment() {
            if (!commentInFlight) {
                throw new IllegalStateException("评论尚未开始");
            }
            confirmedComments++;
            commentInFlight = false;
        }

        String uncertainOperation() {
            if (creationState == OperationState.IN_FLIGHT) {
                return "论坛" + title + "帖子创建结果未确认";
            }
            if (commentInFlight) {
                return "论坛" + title + "第" + (confirmedComments + 1) + "次评论结果未确认";
            }
            return null;
        }
    }

    static final class RunProgress {
        private OperationState lotteryState = OperationState.NOT_STARTED;
        private String pointMessage;
        private String lotteryMessage;
        private final PostProgress checkInPost;
        private final PostProgress workPost;

        RunProgress(int checkInComments, int workComments) {
            checkInPost = new PostProgress("签到", checkInComments);
            workPost = new PostProgress("上班卡", workComments);
        }

        PostProgress checkInPost() {
            return checkInPost;
        }

        PostProgress workPost() {
            return workPost;
        }

        boolean lotteryCompleted() {
            return lotteryState == OperationState.COMPLETED;
        }

        String pointMessage() {
            return pointMessage;
        }

        String lotteryMessage() {
            return lotteryMessage;
        }

        boolean isComplete() {
            return lotteryCompleted() && checkInPost.isComplete() && workPost.isComplete();
        }

        void beginLottery() {
            if (lotteryState != OperationState.NOT_STARTED) {
                throw new IllegalStateException("抽奖状态不允许重复开始");
            }
            lotteryState = OperationState.IN_FLIGHT;
        }

        void completeLottery(String point, String result) {
            if (lotteryState != OperationState.IN_FLIGHT || StrUtil.isBlank(result)) {
                throw new IllegalStateException("抽奖结果无效");
            }
            pointMessage = point;
            lotteryMessage = result;
            lotteryState = OperationState.COMPLETED;
        }

        String uncertainOperation() {
            if (lotteryState == OperationState.IN_FLIGHT) {
                return "抽奖结果未确认";
            }
            String postUncertainty = checkInPost.uncertainOperation();
            if (StrUtil.isNotBlank(postUncertainty)) {
                return postUncertainty;
            }
            return workPost.uncertainOperation();
        }
    }

    private static final class NonRetryableTaskException extends Exception {
        private NonRetryableTaskException(String message) {
            super(message);
        }
    }
}
