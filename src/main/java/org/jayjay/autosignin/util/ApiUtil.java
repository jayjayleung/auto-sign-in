package org.jayjay.autosignin.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.ElementHandle;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.api.core.Response;
import com.ruiyun.jvppeteer.cdp.core.Puppeteer;
import com.ruiyun.jvppeteer.cdp.entities.*;
import com.ruiyun.jvppeteer.common.Product;
import com.ruiyun.jvppeteer.common.PuppeteerLifeCycle;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

/**
 * @Author: jayjay
 * @Date: 2024/4/4
 * @ClassName: org.springblade.autosignin.util.ApiUtil
 * @Description: Api请求工具类
 */
@Slf4j
public class ApiUtil {

    public static String contentStr = "<p><span>${text}</span></p><div class=\"media-wrap image-wrap\"><img src=\"/person/file/fileUrl?ossId=${ossId}\"></div><p></p>";
    public static String contentTextStr = "<p><span>${text}</span></p>";

    public static void main(String[] args) {

    }

    @Test
    public void test1() {
//        System.out.println(checkInModb());
        System.out.println(checkInTidb());
    }

    public static Map<String, String> commonHeaders = new HashMap<>();

    static {
        // 填充 HTTP 头信息
        commonHeaders.put("Accept", "application/json, text/plain, */*");
        commonHeaders.put("Accept-Encoding", "gzip, deflate");
        commonHeaders.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        commonHeaders.put("Connection", "keep-alive");
        commonHeaders.put("Content-Length", "2");
        commonHeaders.put("Content-Type", "application/json;charset=UTF-8");
        commonHeaders.put("Sec-Fetch-Dest", "empty");
        commonHeaders.put("Sec-Fetch-Mode", "cors");
        commonHeaders.put("Sec-Fetch-Site", "same-origin");
        commonHeaders.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.82");
        commonHeaders.put("sec-ch-ua-platform", "Windows");

        // 打印 Map 以验证
//        commonHeaders.forEach((key, value) -> System.out.println(key + ": " + value));
    }


    public static Map<String, String> getModbHeader() {
        Map<String, String> headers = new HashMap<>(commonHeaders);
        headers.put("Host", "www.modb.pro");
        headers.put("Origin", "https://www.modb.pro");
        headers.put("Referer", "https://www.modb.pro/login?redirect=%2ForderList");
        return headers;
    }

    public static Map<String, String> getTidbLoginHeader() {
        Map<String, String> headers = new HashMap<>(commonHeaders);
        headers.put("Origin", "https://accounts.pingcap.cn");
        return headers;
    }

    public static Map<String, String> getTidbCheckInHeader() {
        Map<String, String> headers = new HashMap<>(commonHeaders);
        headers.put("Origin", "https://tidb.net");
        headers.put("Referer", "https://tidb.net/member");
        return headers;
    }

    /**
     * 墨道db签到
     *
     * @return 列表list JSONArray
     */
    public static String checkInModb() {
        String url = "https://www.modb.pro/api/login";
        JSONObject bodyJson = JSONUtil.createObj();
        bodyJson.set("phoneNum", "15913213996");
        bodyJson.set("password", "sjie19950901.");
        HttpResponse res = HttpRequest.post(url).headerMap(getModbHeader(), true)
                .body(bodyJson.toString())
                .execute();
        if (res.getStatus() != 200) {
            return null;
        }
        String body = res.body();
        res.getCookies().forEach(System.out::println);
        System.out.println("------------");
        System.out.println(res.getCookieStr());
        System.out.println(res.header("Authorization"));
        JSONObject result = toJSON(body);
        assert result != null;
        result.getStr("operateMessage");
        Map<String, String> modbHeader = getModbHeader();
        modbHeader.put("Authorization", res.header("Authorization"));
        String body1 = HttpRequest.post("https://www.modb.pro/api/user/checkIn")
                .cookie(res.getCookies())
                .headerMap(modbHeader, true).execute().body();
//        System.out.println(body1);
        return toJSON(body1).getStr("operateMessage");
    }

    public static String checkInTidb() {
        String url = "https://accounts.pingcap.cn/api/login/password";
        JSONObject bodyJson = JSONUtil.createObj();
        bodyJson.set("identifier", "15913213996");
        bodyJson.set("password", "sjie19950901.");
        bodyJson.set("redirect_to", "https://tidb.net/member");
        HttpResponse res = HttpRequest.post(url).headerMap(getTidbLoginHeader(), true)
                .body(bodyJson.toString())
                .execute();

        res.getCookies().forEach(System.out::println);
        System.out.println("------------");
        System.out.println(res.getCookieStr());
        System.out.println(res.header("Authorization"));
        System.out.println(res.body());
        String body1 = HttpRequest.post("https://tidb.net/api/points/daily-checkin")
                .cookie(res.getCookies())
                .headerMap(getTidbCheckInHeader(), true).header("x-csrftoken", res.getCookieValue("csrftoken")).execute().body();
        System.out.println(toJSON(body1).getStr("detail"));
        return body1;
    }

    @Test
    public void testYh() throws Exception {
        checkInYongHong();
    }

    public void checkInYongHong() throws Exception {
        //自动下载，第一次下载后不会再下载
        RevisionInfo revisionInfo = Puppeteer.downloadBrowser();
        System.out.println("revisionInfo: " + revisionInfo);
        ArrayList<String> argList = new ArrayList<>();
        // withHeadless 是否开启无头模式，无头模式不会显示浏览器
//        LaunchOptions options = new LaunchOptionsBuilder().withArgs(argList).withHeadless(false).build();
        argList.add("--no-sandbox");
        argList.add("--disable-setuid-sandbox");
        LaunchOptions options = LaunchOptions.builder().args(argList).defaultViewport(null)
                .headless(false)
                .protocol(Protocol.CDP)
                .product(Product.Chrome).build();
//        options.setProduct(Product.Chrome);
        try(Browser browser = Puppeteer.launch(options)) {
            Page page = browser.newPage();
//            page.goTo("https://club.yonghongtech.com/member.php?mod=logging&action=login");
            page.goTo("https://club.yonghongtech.com/member.php?mod=logging&action=login&phonelogin=no");
            ElementHandle userName = page.$("input[name='username']");
            userName.type("15913213996");
            page.type("input[name='password']","sjie19950901.");
//            page.click("input[name='loginsubmit']");
            ElementHandle loginBtn = page.$("button[name='loginsubmit']");
            System.out.println(loginBtn);
//            loginBtn.click();
            if(Objects.nonNull(loginBtn)) {
                log.info("点击了");
                loginBtn.click();
            }
//            page.waitForNavigation();
            Thread.sleep(10000);
        }

    }


    /**
     * 转换json
     *
     * @param str json字符串
     * @return Json对象
     */
    public static JSONObject toJSON(String str) {
        if (StrUtil.isBlank(str)) {
            return null;
        }
        if (!JSONUtil.isTypeJSON(str)) {
            return null;
        }
        return JSONUtil.parseObj(str);
    }


}
