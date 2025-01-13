package org.jayjay.autosignin.task;

import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.ElementHandle;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.cdp.core.Puppeteer;
import com.ruiyun.jvppeteer.cdp.entities.*;
import com.ruiyun.jvppeteer.common.Product;
import lombok.Data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Optional;

@Data
public class YongHoneCheckInTask implements CheckInTask{
    String loginUrl = "https://club.yonghongtech.com/member.php?mod=logging&action=login&phonelogin=no";
    String checkInUrl = "https://club.yonghongtech.com/home.php?mod=space&uid=${user_id}&do=signlog&from=space";
    String cjUrl = "https://club.yonghongtech.com/plugin.php?id=hux_zp3:hux_zp3";
    @Override
    public StringBuilder run(){
        message.append("yonghong 签到");
        System.out.println("yonghong 签到任务开始");
        String yhUsername = System.getenv("YH_USERNAME");
        String yhPassword = System.getenv("YH_PASSWORD");
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
            ElementHandle userName = page.$("input[name='username']");
            userName.type(yhUsername);
            page.type("input[name='password']", yhPassword);
            ElementHandle loginBtn = page.$("button[name='loginsubmit']");
            System.out.println("点击登录");
            loginBtn.click();
            loginBtn.dispose();
            Thread.sleep(3000);
            System.out.println(page.cookies());
            Optional<Cookie> any = page.cookies().stream().filter(cookie -> "user_id".equalsIgnoreCase(cookie.getName())).findAny();
            if (any.isPresent()) {
                String uid = any.get().getValue();
                System.out.println("获取cookie成功:"+uid);
                message.append("UID:").append(uid).append(lineEnd);

                message.append("用户名:").append(page.evaluate("document.querySelector(\"a[title='个人设置']\").innerText.replaceAll('\\n','')")).append(lineEnd);
                message.append("洪豆:").append(page.evaluate("document.querySelector(\".hl_member_in_status\").innerText.replaceAll('\\n','')")).append(lineEnd);
//                Page card = browser.newPage();
//                card.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.82");
//                String url = "https://club.yonghongtech.com/home.php?mod=space&uid=" + any.get().getValue() + "&do=signlog&from=space";
//                System.out.println("card_url:"+url);
//                page.goTo(url);
//                System.out.println("进入打卡页面");
                System.out.println(page.url());
                Thread.sleep(3000);
            }else {
                System.out.println("获取cookie失败");
            }
            Page cj = browser.newPage();

            cj.setUserAgent(userAgent);
            cj.goTo(cjUrl);
            Thread.sleep(5000);
//            page.waitForNavigation();
            System.out.println("开始抽奖！");
            cj.click("#startbtn");
            cj.waitForSelector("#main_messaqge");
//            Thread.sleep(15000);
            System.out.println(cj.evaluate("document.querySelector('#main_messaqge div p').innerText"));
            message.append(cj.evaluate("document.querySelector('#main_messaqge div p').innerText"));
            System.out.println("永洪抽奖完成");
            System.out.println(cj.url());
        }catch (Exception e){
            e.printStackTrace();
        }
        return message;
    }
}
