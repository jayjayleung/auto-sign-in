package org.jayjay.autosignin.task;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 浏览器通道里能单测的部分：脚本拼装和响应解析。
 * 真正起 Chrome 的那段只能在带浏览器依赖的镜像里跑。
 */
public class WatchaBrowserTransportTest {

    @Test
    public void scriptCarriesMethodUrlAndToken() {
        String script = WatchaBrowserTransport.fetchScript("POST", "https://watcha.cn/api/v2/products/7/reviews",
                "abc123", "{\"vote_value\":1}");
        assertTrue(script.contains("method:\"POST\""));
        assertTrue(script.contains("https://watcha.cn/api/v2/products/7/reviews"));
        assertTrue(script.contains("Bearer abc123"));
        assertTrue(script.contains("credentials:'include'"));
        assertTrue(script.contains(",body:\""));
    }

    @Test
    public void scriptWithoutTokenStillSendsCookies() {
        String script = WatchaBrowserTransport.fetchScript("GET", "https://watcha.cn/api/v2/checkins/today", "",
                null);
        assertFalse("没有令牌时不该出现空的 Bearer", script.contains("Bearer"));
        assertTrue("cookie 还是要带", script.contains("credentials:'include'"));
        assertFalse("GET 不该带请求体", script.contains(",body:\""));
    }

    @Test
    public void scriptEscapesQuotesAndBackslashes() {
        String body = "{\"text\":\"含 \\\"引号\\\" 和 \\\\ 反斜杠\"}";
        String script = WatchaBrowserTransport.fetchScript("POST", "https://watcha.cn/api/v2/x", "t", body);
        assertTrue(script.contains("\\\\\\\"引号\\\\\\\""));
        assertTrue(script.contains("\\\\"));
    }

    @Test
    public void parsesFetchResult() {
        WatchaBrowserTransport.Result result = WatchaBrowserTransport.Result.parse(
                "{\"status\":200,\"body\":\"{\\\"statusCode\\\":200,\\\"data\\\":{\\\"id\\\":1}}\"}");
        assertEquals(200, result.status);
        assertTrue(result.ok());
        assertEquals(1, result.apiData().getInt("id").intValue());
    }

    @Test
    public void reportsFailureClearly() {
        WatchaBrowserTransport.Result result = WatchaBrowserTransport.Result.parse(
                "{\"status\":449,\"body\":\"{\\\"message\\\":\\\"需要完成验证码\\\"}\"}");
        assertEquals(449, result.status);
        assertFalse(result.ok());
        assertTrue(result.describe().contains("449"));
    }

    @Test
    public void normalizesHeadlessUserAgent() {
        String headless = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "HeadlessChrome/131.0.6778.204 Safari/537.36";
        String ua = WatchaBrowserTransport.normalizeUserAgent(headless);
        assertTrue("不能留 HeadlessChrome 标记", !ua.contains("HeadlessChrome"));
        assertEquals("131.0.6778.204", WatchaBrowserTransport.versionOf(ua));
    }

    @Test
    public void fallsBackWhenUserAgentIsUnreadable() {
        assertEquals(CheckInTask.userAgent, WatchaBrowserTransport.normalizeUserAgent(""));
        assertEquals("0.0.0.0", WatchaBrowserTransport.versionOf("不是 UA"));
    }

    @Test
    public void toleratesNonJsonResult() {
        WatchaBrowserTransport.Result result = WatchaBrowserTransport.Result.parse("网络错误");
        assertEquals(0, result.status);
        assertFalse(result.ok());
        assertEquals("网络错误", result.body);
    }
}
