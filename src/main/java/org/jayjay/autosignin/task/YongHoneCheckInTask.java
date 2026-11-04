package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.ElementHandle;
import com.ruiyun.jvppeteer.api.core.Frame;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.cdp.core.Puppeteer;
import com.ruiyun.jvppeteer.cdp.entities.*;
import com.ruiyun.jvppeteer.common.Product;
import com.ruiyun.jvppeteer.common.PuppeteerLifeCycle;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.MessageLine;
import org.jayjay.autosignin.entity.NotificationSection;
import org.jayjay.autosignin.util.BrowserUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.Test;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
@EqualsAndHashCode(callSuper = true)
public class YongHoneCheckInTask extends CheckInTask {
    //重试五次
    int maxRetries = 5;
    int retryCount = 0;
    boolean success = false;
    int delay = 3000; // 初始延迟为3秒 6s 12s 24s 48s 96s
    String loginUrl = "https://club.yonghongtech.com/member.php?mod=logging&action=login&phonelogin=no";
    String checkInUrl = "https://club.yonghongtech.com/home.php?mod=space&uid=${user_id}&do=signlog&from=space";
    String cjUrl = "https://club.yonghongtech.com/plugin.php?id=hux_zp3:hux_zp3";
    String publishUrl = "https://club.yonghongtech.com/forum.php?mod=post&action=newthread&fid=80";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Pattern POST_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    /** 发帖页等全部资源时经常接近 30 秒，这里给到 60 秒，并改为只等 DOM 就绪。 */
    private static final Integer PUBLISH_PAGE_TIMEOUT_MILLIS = 60000;

    @Override
    public NotificationSection buildNotificationSection() {
        return new NotificationSection("永洪签到", messageBlocks);
    }

    @Test
    public void testRun() {
        run();
    }

    @Override
    public CheckInTask run() {
        //开启重试，有时候网页打开失败
        while (!success && retryCount <= maxRetries) {
            if (retryCount > 0) {
                System.out.println("[永洪] 出现异常，正在进行第 " + retryCount + " 次重试");
                clearMessages();
            }
            System.out.println("[永洪] 签到任务开始");
            String yhUsername = System.getenv("YH_USERNAME");
            String yhPassword = System.getenv("YH_PASSWORD");
            if (StrUtil.isBlank(yhUsername) || StrUtil.isBlank(yhPassword)) {
                System.out.println("[永洪] 用户名或密码未配置，跳过签到");
                enabled = false;
                return this;
            }

            //自动下载，第一次下载后不会再下载
            RevisionInfo revisionInfo = null;
            try {
                revisionInfo = Puppeteer.downloadBrowser();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("[永洪] Chrome 版本：" + revisionInfo);
            ArrayList<String> argList = new ArrayList<>();
            // withHeadless 是否开启无头模式，无头模式不会显示浏览器
//        LaunchOptions options = new LaunchOptionsBuilder().withArgs(argList).withHeadless(false).build();
            argList.add("--no-sandbox");
            argList.add("--disable-setuid-sandbox");
//        argList.add("--user_agent=\"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.82\"");
            LaunchOptions options = LaunchOptions.builder()
                    .args(argList)
                    .defaultViewport(new Viewport(1900, 1080))
                    .headless(true)
                    .protocol(Protocol.CDP)
                    .product(Product.Chrome)
                    .build();
            try (Browser browser = Puppeteer.launch(options)) {
                System.out.println("[永洪] 浏览器 User-Agent：" + browser.userAgent());
                Page page = browser.newPage();
                page.setUserAgent(userAgent);
                page.goTo(loginUrl);
                Thread.sleep(10000);
                System.out.println(page.url());
//                ElementHandle userName = page.$("input[name='username']");
//                userName.type(yhUsername);
//                ElementHandle userName = page.$("input[name='username']");

                page.type("input[name='username']", yhUsername);
                page.type("input[name='password']", yhPassword);
                ElementHandle loginBtn = page.$("button[name='loginsubmit']");
                System.out.println("[永洪] 提交登录");
                loginBtn.click();
                loginBtn.dispose();
                Thread.sleep(5000);
                Optional<Cookie> any = page.cookies().stream().filter(cookie -> "user_id".equalsIgnoreCase(cookie.getName())).findAny();
                String uid = null;
                // 洪豆要等任务全部完成才能读到最终值，先占住它在通知里的位置：UID 和用户名之后
                int pointsSlot = -1;
                if (any.isPresent()) {
                    uid = any.get().getValue();
                    System.out.println("[永洪] 登录成功，已获取会话 Cookie");
                    System.out.println(page.url());
                    //检查是否到了主页
                    for (int i = 0; i < 10; i++) {
                        if (page.url().equals(loginUrl)) {
                            Thread.sleep(2000);
                            System.out.println(page.url());
                            System.out.println("[永洪] 仍在登录页面，2 秒后继续检查");
                        } else {
                            Thread.sleep(5000);
                            System.out.println("[永洪] 已进入首页");
                            System.out.println(page.url());
                            break;
                        }
                    }
                    addMessage("UID：", uid);
                    String url = "https://club.yonghongtech.com/home.php?mod=space&uid=" + any.get().getValue() + "&do=signlog&from=space";
                    page.goTo(url);
                    Thread.sleep(5000);
                    System.out.println(page.url());
                    Document document = Jsoup.parse(page.content());
                    Elements me = document.select(".nex_Home_intel h5");
                    if (me != null) {
                        String user = me.text().replaceAll("[\\r\\n]+", "");
                        System.out.println(user);
                        addMessage("用户名：", user);
                    }
                    pointsSlot = messageBlocks.size();
                    // document.querySelector("a[title='个人设置'].innerText")
                    //有bug,老是找不到节点
//                    page.hover(".hl_member_avator");
//                    Object evaluate = page.$eval("a[title='个人设置']", "ele=>ele.innerText.replaceAll('\\n','')");
//                Page card = browser.newPage();
//                card.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.82");
//                String url = "https://club.yonghongtech.com/home.php?mod=space&uid=" + any.get().getValue() + "&do=signlog&from=space";
//                System.out.println("card_url:"+url);
//                page.goTo(url);
//                System.out.println("进入打卡页面");
                    Thread.sleep(1000);
                } else {
                    System.out.println("[永洪] 登录失败，未获取会话 Cookie");
                }
                Page cj = browser.newPage();

                cj.setUserAgent(userAgent);
                cj.goTo(cjUrl);
                Thread.sleep(5000);

                // 抽奖和发帖都会增加洪豆，这里只记录任务前的值，用于任务后读取失败时兜底
                String pointsBefore = pointsOn(cj.content());
                if (StrUtil.isNotBlank(pointsBefore)) {
                    System.out.println("[永洪] 任务前洪豆：" + pointsBefore);
                }
//            page.waitForNavigation();
                System.out.println("[永洪] 开始抽奖");
                cj.click("#startbtn");
                ElementHandle lotteryResult = BrowserUtil.waitForElement(cj, "#main_messaqge");
                String cjMsg;
                try {
                    cjMsg = lotteryResult.$eval("div p", "ele=>ele.innerText").toString();
                } finally {
                    lotteryResult.dispose();
                }
                System.out.println("[永洪] 抽奖结果：" + cjMsg);
                addMessage("抽奖结果：", cjMsg);
                System.out.println("[永洪] 抽奖完成");
                System.out.println(cj.url());
                Set<String> postedToday = postedToday(browser, uid);
                // 论坛积分规则：发表主题和发表回复每天各只奖励一次（+5、+2），
                // 多发帖和多条评论不再增加洪豆，只增加风控风险，所以每天只发一篇签到帖和一条评论。
                publishUnlessPosted(browser, postedToday, "签到", "签到", "签到", 1);
                addPointsMessage(cj, pointsBefore, pointsSlot);
                success = true;
            } catch (Exception e) {
                e.printStackTrace();
                // 处理异常
                retryCount++;
                sleep(delay);
                delay *= 2; // 延迟指数增加
                clearMessages();
                if (retryCount >= maxRetries) {
                    addMessage("永洪签到失败，请查看日志");
                }
            }
        }

        return this;
    }



    /**
     * 从抽奖页面解析当前洪豆数量，页面上取不到时返回 null。
     */
    static String pointsOn(String html) {
        if (StrUtil.isBlank(html)) {
            return null;
        }
        Elements point = Jsoup.parse(html).select("#ct div ul li:eq(2) > font:eq(3)");
        if (point == null || point.isEmpty()) {
            return null;
        }
        String text = point.text().trim();
        return StrUtil.isBlank(text) ? null : text;
    }

    /**
     * 读取任务全部完成后的洪豆数量，插入到用户名之后；取不到时退回任务前的值并说明原因。
     *
     * @param slot 用户名之后的位置，任务全部完成后才知道最终值，所以先占位再回填
     */
    private void addPointsMessage(Page cj, String pointsBefore, int slot) {
        String pointsAfter = null;
        try {
            cj.reload();
            cj.waitForNetworkIdle(new WaitForNetworkIdleOptions());
            pointsAfter = pointsOn(cj.content());
        } catch (Exception e) {
            System.out.println("[永洪] 读取任务后洪豆失败：" + e.getMessage());
        }
        if (StrUtil.isBlank(pointsAfter)) {
            if (StrUtil.isBlank(pointsBefore)) {
                System.out.println("[永洪] 未能读取洪豆数量");
                return;
            }
            System.out.println("[永洪] 任务后洪豆读取失败，使用任务前的值：" + pointsBefore);
            insertMessage(slot, new MessageBlock().addLine(
                    new MessageLine().append("洪豆：", pointsBefore, "（任务前，任务后读取失败）")));
            return;
        }
        System.out.println("[永洪] 任务后洪豆：" + pointsAfter);
        insertMessage(slot, new MessageBlock().addLine("洪豆：", pointsAfter));
        Long increase = pointsIncrease(pointsBefore, pointsAfter);
        if (increase != null && increase > 0) {
            System.out.println("[永洪] 本次获得洪豆：" + increase);
            addMessage("本次获得洪豆：", String.valueOf(increase));
        }
    }

    /**
     * 把消息块插到指定位置；位置越界时追加到末尾。
     */
    private void insertMessage(int slot, MessageBlock block) {
        if (block == null || block.isBlank()) {
            return;
        }
        if (slot < 0 || slot > messageBlocks.size()) {
            messageBlocks.add(block);
            return;
        }
        messageBlocks.add(slot, block);
    }

    /**
     * 计算任务带来的洪豆增量。任一端缺失或无法解析为数字时返回 null，负数增量也按 null 处理。
     */
    static Long pointsIncrease(String before, String after) {
        Long beforeValue = parsePoints(before);
        Long afterValue = parsePoints(after);
        if (beforeValue == null || afterValue == null) {
            return null;
        }
        long increase = afterValue - beforeValue;
        return increase > 0 ? increase : null;
    }

    private static Long parsePoints(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Set<String> postedToday(Browser browser, String uid) {
        if (StrUtil.isBlank(uid)) {
            return Collections.emptySet();
        }
        try {
            Page mine = browser.newPage();
            mine.setUserAgent(userAgent);
            mine.goTo("https://club.yonghongtech.com/home.php?mod=space&uid=" + uid + "&do=thread&view=me&from=space");
            Thread.sleep(5000);
            String html = mine.content();
            if (!threadListReady(html)) {
                System.out.println("[永洪] 未能确认今日是否已发帖，继续发布");
                addMessage("未能确认今日是否已发帖，继续发布");
                return Collections.emptySet();
            }
            Set<String> posted = postedTitlesOn(html, LocalDate.now(SHANGHAI));
            System.out.println("[永洪] 今日已发帖：" + (posted.isEmpty() ? "无" : String.join("、", posted)));
            return posted;
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("[永洪] 未能确认今日是否已发帖，继续发布");
            addMessage("未能确认今日是否已发帖，继续发布");
            return Collections.emptySet();
        }
    }

    private void publishUnlessPosted(Browser browser, Set<String> postedToday, String title, String content, String comment, int commentNum) {
        if (postedToday.contains(title)) {
            System.out.println("[永洪] " + title + "帖今天已发布，跳过");
            addMessage(title, "帖今天已发布，跳过");
            return;
        }
        publishPost(browser, title, content, comment, commentNum);
    }

    static boolean threadListReady(String html) {
        return html != null && (html.contains("nex_forum_lists") || html.contains("nex_Mythreads_Tuiys"));
    }

    /**
     * 永洪论坛每天 23:50 到次日 6:00 关闭发帖，发帖页只会返回提示信息页。
     */
    static boolean blockedByQuietHours(String html) {
        return html != null
                && html.contains("不能进行此操作")
                && !html.contains("nex_forum_lists");
    }

    private void reportQuietHours(String title) {
        System.out.println("[永洪] " + title + "帖处于论坛禁发时段，跳过");
        addMessage(title, "帖未发布：论坛每日 23:50-6:00 禁发，请其他时间再试");
    }

    static Set<String> postedTitlesOn(String html, LocalDate day) {
        Set<String> titles = new LinkedHashSet<>();
        if (StrUtil.isBlank(html) || day == null) {
            return titles;
        }
        Document document = Jsoup.parse(html);
        for (Element card : document.select("div.nex_forum_lists")) {
            String title = card.select("a.xst").text().trim();
            if (!"签到".equals(title) && !"上班卡".equals(title)) {
                continue;
            }
            if (postedOn(card, day)) {
                titles.add(title);
            }
        }
        return titles;
    }

    private static boolean postedOn(Element card, LocalDate day) {
        Element dateNode = card.selectFirst("dd.nex_forumn_post_dateline");
        if (dateNode == null) {
            return false;
        }
        Element span = dateNode.selectFirst("span[title]");
        String raw = span != null && StrUtil.isNotBlank(span.attr("title")) ? span.attr("title") : dateNode.text();
        return sameDay(raw, day);
    }

    private static boolean sameDay(String raw, LocalDate day) {
        if (StrUtil.isBlank(raw)) {
            return false;
        }
        String text = raw.trim();
        Matcher matcher = POST_DATE.matcher(text);
        if (matcher.find()) {
            try {
                return LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                ).equals(day);
            } catch (DateTimeException e) {
                return false;
            }
        }
        return text.startsWith("今天");
    }

    /**
     * 打开发帖页。论坛页面外部资源较多，整页 load 常常接近 30 秒，
     * 这里放宽超时并只等 DOM 就绪，必要时再退回一次朴素跳转。
     */
    private void openPublishPage(Page page) throws Exception {
        GoToOptions options = new GoToOptions();
        options.setTimeout(PUBLISH_PAGE_TIMEOUT_MILLIS);
        options.setWaitUntil(Collections.singletonList(PuppeteerLifeCycle.domcontentloaded));
        try {
            page.goTo(publishUrl, options);
            return;
        } catch (Exception e) {
            System.out.println("[永洪] 打开发帖页超时，改为只等页面可访问：" + e.getMessage());
        }
        // 已经跳转过但资源迟迟不结束时，只要地址正确且能取到页面内容，就继续走后续流程
        if (!publishPageReady(page)) {
            page.goTo(publishUrl, options);
        }
    }

    /**
     * 判断发帖页是否已经可用：地址正确且能取到内容。
     */
    private boolean publishPageReady(Page page) {
        try {
            String url = page.url();
            if (url == null || !url.contains("mod=post")) {
                return false;
            }
            String content = page.content();
            return StrUtil.isNotBlank(content);
        } catch (Exception e) {
            return false;
        }
    }

    public void publishPost(Browser browser, String title, String content, String comment, int commentNum) {
        try {
            System.out.println("[永洪] 开始发布" + title + "帖");
            Page page = browser.newPage();
            page.setUserAgent(userAgent);
            openPublishPage(page);
            Thread.sleep(5000);
            System.out.println(page.url());
            if (blockedByQuietHours(page.content())) {
                reportQuietHours(title);
                return;
            }
            page.evaluate("document.querySelectorAll('#typeid_ctrl_menu li')[1].click()");

            page.type("#subject", title);
            typeIntoEditor(page, content);

            page.click("#postsubmit");
            boolean flag = false;
            for (int i = 0; i < 10; i++) {
                if (page.url().equals(publishUrl)) {
                    Thread.sleep(2000);
                } else {
                    flag = true;
                    System.out.println(page.url());
                    break;
                }
            }
            if (flag) {
                for (int i = 0; i < commentNum; i++) {
                    System.out.println("[永洪] 发布" + title + "帖评论 " + (i + 1) + "/" + commentNum);
                    Thread.sleep(2000);
                    page.type("#fastpostmessage", comment);
                    Thread.sleep(2000);
                    page.evaluate("document.getElementById('fastpostsubmit').click();");
                    Thread.sleep(2000);
                }
                addMessage(title, "帖发布成功");
                System.out.println("[永洪] " + title + "帖发布成功");
            } else {
                System.out.println("[永洪] " + title + "帖发布结果未确认");
                addMessage(title, "帖发布结果未确认，请查看日志");
            }
        } catch (Exception e) {
            e.printStackTrace();
            addMessage(title, "帖发布失败");
        }
    }

    /**
     * 论坛发布
     *
     * @param browser
     */
    public void publishCheckIn(Browser browser) {
        try {
            System.out.println("[永洪] 开始发布签到帖");
            Page page = browser.newPage();
            page.setUserAgent(userAgent);
            openPublishPage(page);
            Thread.sleep(5000);
            System.out.println(page.url());
            if (blockedByQuietHours(page.content())) {
                reportQuietHours("签到");
                return;
            }
            page.evaluate("document.querySelectorAll('#typeid_ctrl_menu li')[1].click()");

            page.type("#subject", "签到");
            typeIntoEditor(page, "签到");

            page.click("#postsubmit");
            boolean flag = false;
            for (int i = 0; i < 10; i++) {
                if (page.url().equals(publishUrl)) {
                    Thread.sleep(2000);
                } else {
                    flag = true;
                    System.out.println(page.url());
                    break;
                }
            }
            if (flag) {
                System.out.println("[永洪] 发布签到帖评论");
                Thread.sleep(2000);
                page.type("#fastpostmessage", "签到");
                Thread.sleep(2000);
                page.evaluate("document.getElementById('fastpostsubmit').click();");
                Thread.sleep(2000);
                addMessage("签到帖发布成功");
            }
            System.out.println("[永洪] 签到帖发布流程结束");
        } catch (Exception e) {
            e.printStackTrace();
            addMessage("签到帖发布失败");
        }
    }

    private void typeIntoEditor(Page page, String content) throws Exception {
        ElementHandle iframeHandle = BrowserUtil.waitForElement(page, "iframe#e_iframe");
        try {
            Frame frame = iframeHandle.contentFrame();
            if (frame == null) {
                throw new IllegalStateException("永洪发帖编辑器 iframe 尚未加载完成");
            }

            ElementHandle body = frame.$("body");
            if (body == null) {
                throw new IllegalStateException("未找到永洪发帖编辑器正文区域");
            }
            try {
                body.type(content);
            } finally {
                body.dispose();
            }
        } finally {
            iframeHandle.dispose();
        }
    }
}
