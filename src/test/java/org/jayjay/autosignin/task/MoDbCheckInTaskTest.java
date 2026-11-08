package org.jayjay.autosignin.task;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MoDbCheckInTaskTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger loginRequests = new AtomicInteger();
    private volatile int failUntil;

    @Before
    public void setUp() throws IOException {
        loginRequests.set(0);
        failUntil = 0;
        HttpRequest.getCookieManager().getCookieStore().removeAll();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            int attempt = loginRequests.incrementAndGet();
            // 前一次不回包并断开连接，模拟网络抖动
            if (attempt <= failUntil) {
                exchange.close();
                return;
            }
            byte[] bytes = "{\"operateMessage\":\"登录成功\",\"success\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    /**
     * HttpURLConnection 对连接失败的 POST 会自动重发一次（sun.net.http.retryPost 默认开启），
     * 所以这里让前两次请求都失败，确保是我们自己的重试把第三次请求跑成功。
     */
    @Test
    public void retriesLoginWhenConnectionFailsRepeatedly() {
        failUntil = 2;
        MoDbCheckInTask task = task();

        HttpResponse response = task.loginWithRetry(JSONUtil.createObj().set("phoneNum", "1"));

        assertEquals(200, response.getStatus());
        assertEquals(3, loginRequests.get());
    }

    @Test
    public void givesUpAfterConfiguredAttempts() {
        failUntil = Integer.MAX_VALUE;
        MoDbCheckInTask task = task();

        try {
            task.loginWithRetry(JSONUtil.createObj().set("phoneNum", "1"));
            fail("登录请求连续失败时应抛出异常");
        } catch (RuntimeException expected) {
            assertTrue(expected.getCause() != null || expected.getMessage() != null);
        }
        // 3 次重试，每次请求可能再被 HttpURLConnection 内部重发一次
        assertTrue("请求次数应被限制在重试上限内，实际为 " + loginRequests.get(),
                loginRequests.get() >= task.getLoginMaxAttempts() && loginRequests.get() <= task.getLoginMaxAttempts() * 2);
    }

    private MoDbCheckInTask task() {
        MoDbCheckInTask task = new MoDbCheckInTask();
        task.setLoginUrl(baseUrl + "/login");
        task.setLoginRetryDelayMs(1L);
        return task;
    }
}
