package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.ElementHandle;
import com.ruiyun.jvppeteer.api.core.Frame;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.cdp.core.Puppeteer;
import com.ruiyun.jvppeteer.cdp.entities.*;
import com.ruiyun.jvppeteer.common.Product;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jayjay.autosignin.entity.MessageList;
import org.jayjay.autosignin.util.MessageUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.junit.Test;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

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

    @Override
    public MessageList messageList() {
        return new MessageList("YongHong 签到", listMessage);
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
                System.out.println("出现异常，正在重试第" + retryCount + "...");
                listMessage.clear();
            }
            System.out.println("yonghong 签到任务开始");
            String yhUsername = System.getenv("YH_USERNAME");
            String yhPassword = System.getenv("YH_PASSWORD");
            if (StrUtil.isBlank(yhUsername) || StrUtil.isBlank(yhPassword)) {
                System.out.println("yonghong 账号密码未配置，跳过签到");
                isRun = !isRun;
                return this;
            }
            System.out.println("yhUsername: " + yhUsername);
            System.out.println("yhPassword: " + yhPassword);

            //自动下载，第一次下载后不会再下载
            RevisionInfo revisionInfo = null;
            try {
                revisionInfo = Puppeteer.downloadBrowser();
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("revisionInfo: " + revisionInfo);
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
                System.out.println(browser.userAgent());
                Page page = browser.newPage();
                page.setUserAgent(userAgent);
                page.goTo(loginUrl);
                Thread.sleep(10000);
                System.out.println(page.url());
                System.out.println(page.content());
//                ElementHandle userName = page.$("input[name='username']");
//                userName.type(yhUsername);
//                ElementHandle userName = page.$("input[name='username']");

                page.type("input[name='username']", yhUsername);
                page.type("input[name='password']", yhPassword);
                ElementHandle loginBtn = page.$("button[name='loginsubmit']");
                System.out.println("点击登录");
                loginBtn.click();
                loginBtn.dispose();
                Thread.sleep(5000);
                System.out.println(page.cookies());
                Optional<Cookie> any = page.cookies().stream().filter(cookie -> "user_id".equalsIgnoreCase(cookie.getName())).findAny();
                if (any.isPresent()) {
                    String uid = any.get().getValue();
                    System.out.println("获取cookie成功:" + uid);
                    System.out.println(page.url());
                    //检查是否到了主页
                    for (int i = 0; i < 10; i++) {
                        if (page.url().equals(loginUrl)) {
                            Thread.sleep(2000);
                            System.out.println(page.url());
                            System.out.println("还在登录页面。。。继续等待2s");
                        } else {
                            Thread.sleep(5000);
                            System.out.println("进入首页成功");
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
                        String user = me.text().replaceAll(MessageUtil.lineEnd, "");
                        System.out.println(user);
                        addMessage(lineMsg("用户名：").append(user));
                    }

                    // document.querySelector("a[title='个人设置'].innerText")
                    //有bug,老是找不到节点
//                    page.hover(".hl_member_avator");
//                    page.waitForSelector("a[title='个人设置']");
//                    Object evaluate = page.$eval("a[title='个人设置']", "ele=>ele.innerText.replaceAll('\\n','')");
//                    addMessage(lineMsg("用户名:").append(evaluate));
//                    addMessage(lineMsg("洪豆:").append(page.$eval(".hl_member_in_status", "ele=>ele.innerText.replaceAll('\\n','')")));
//                Page card = browser.newPage();
//                card.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.82");
//                String url = "https://club.yonghongtech.com/home.php?mod=space&uid=" + any.get().getValue() + "&do=signlog&from=space";
//                System.out.println("card_url:"+url);
//                page.goTo(url);
//                System.out.println("进入打卡页面");
                    Thread.sleep(1000);
                } else {
                    System.out.println("获取cookie失败");
                }
                Page cj = browser.newPage();

                cj.setUserAgent(userAgent);
                cj.goTo(cjUrl);
                Thread.sleep(5000);

                Document document = Jsoup.parse(cj.content());
                Elements point = document.select("#ct div ul li:eq(2) > font:eq(3)");
                if (point != null) {
                    String pointStr = point.text();
                    if (StrUtil.isNotBlank(pointStr)) {
                        System.out.println(pointStr);
                        addMessage(lineMsg("洪豆：").append(pointStr));
                    }
                }
//            page.waitForNavigation();
                System.out.println("开始抽奖！");
                cj.click("#startbtn");
                cj.waitForSelector("#main_messaqge");
//            Thread.sleep(15000);
                String cjMsg = cj.$eval("#main_messaqge div p", "ele=>ele.innerText").toString();
                System.out.println(cjMsg);
                addMessage(cjMsg);
                System.out.println("永洪抽奖完成");
                System.out.println(cj.url());
                DayOfWeek dayOfWeek = LocalDateTime.now().getDayOfWeek();
                boolean doubleDay = dayOfWeek.getValue() % 2 == 0;
                //开始发布签到帖子
//                publishCheckIn(browser);
                publishPost(browser, "签到", "签到", "签到", doubleDay ? 3 : 0);
                //开始发布上班卡帖子
                publishPost(browser, "上班卡", "滴~", "滴，上班卡~", !doubleDay ? 3 : 0);
                success = true;
            } catch (Exception e) {
                e.printStackTrace();
                // 处理异常
                retryCount++;
                sleep(delay);
                delay *= 2; // 延迟指数增加
                listMessage.clear();
                if (retryCount >= maxRetries) {
                    addMessage("永洪签到失败，请查看日志");
                }
            }
        }

        return this;
    }



    public void publishPost(Browser browser, String title, String content, String comment, int commentNum) {
        try {
            System.out.println("开始论坛发布:" + title);
            Page page = browser.newPage();
            page.setUserAgent(userAgent);
            page.goTo(publishUrl);
            Thread.sleep(5000);
            System.out.println(page.url());
            page.evaluate("document.querySelectorAll('#typeid_ctrl_menu li')[1].click()");

            page.type("#subject", title);
            // 等待 iframe 加载并定位
            ElementHandle iframeHandle = page.waitForSelector("iframe#e_iframe");
            Frame frame = iframeHandle.contentFrame();

            // 在 iframe 内操作
            frame.$("body").type(content);

            page.$("#postsubmit").click();
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
                if(commentNum > 0) {
                    System.out.println("开始评论：" + comment);
                    for (int i = 0; i < 3; i++) {
                        Thread.sleep(2000);
                        page.$("#fastpostmessage").type(comment);
                        Thread.sleep(2000);
                        page.evaluate("document.getElementById('fastpostsubmit').click();");
                        Thread.sleep(2000);
                    }
                }
                addMessage("论坛" + title + "发布完成");
            }
            System.out.println("论坛" + title + "发布完成");
        } catch (Exception e) {
            e.printStackTrace();
            addMessage("论坛" + title + "发布失败");
        }
    }

    /**
     * 论坛发布
     *
     * @param browser
     */
    public void publishCheckIn(Browser browser) {
        try {
            System.out.println("开始论坛发布");
            Page page = browser.newPage();
            page.setUserAgent(userAgent);
            page.goTo(publishUrl);
            Thread.sleep(5000);
            System.out.println(page.url());
            page.evaluate("document.querySelectorAll('#typeid_ctrl_menu li')[1].click()");

            page.type("#subject", "签到");
            // 等待 iframe 加载并定位
            ElementHandle iframeHandle = page.waitForSelector("iframe#e_iframe");
            Frame frame = iframeHandle.contentFrame();

            // 在 iframe 内操作
            frame.$("body").type("签到");

            page.$("#postsubmit").click();
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
                System.out.println("开始评论：");
                for (int i = 0; i < 3; i++) {
                    Thread.sleep(2000);
                    page.$("#fastpostmessage").type("签到");
//                Thread.sleep(2000);
//                page.$("#fastpostsubmit").click();
//                page.evaluate("document.getElementById('fastpostmessage').innerText='签到'");
                    Thread.sleep(2000);
                    page.evaluate("document.getElementById('fastpostsubmit').click();");
                    Thread.sleep(2000);
                }
                addMessage("论坛发布完成，洪豆25");
            }
            System.out.println("论坛发布完成");
        } catch (Exception e) {
            e.printStackTrace();
            addMessage("论坛发布失败");
        }
    }
}
