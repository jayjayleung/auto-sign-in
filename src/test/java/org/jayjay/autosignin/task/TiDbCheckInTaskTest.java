package org.jayjay.autosignin.task;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.http.HttpRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TiDbCheckInTaskTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger loginRequests = new AtomicInteger();
    private final AtomicReference<String> checkInCookie = new AtomicReference<>();
    private final AtomicReference<String> checkInCsrf = new AtomicReference<>();

    @Before
    public void setUp() throws IOException {
        loginRequests.set(0);
        checkInCookie.set(null);
        checkInCsrf.set(null);
        // hutool 的 Cookie 仓库按主机共享且忽略端口，不清空会让相邻测试的 Cookie 串进来
        HttpRequest.getCookieManager().getCookieStore().removeAll();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", this::handleLogin);
        server.createContext("/me", exchange -> json(exchange, 200,
                "{\"data\":{\"username\":\"tester\"}}"));
        server.createContext("/points", exchange -> json(exchange, 200,
                "{\"data\":{\"current_points\":\"12\"}}"));
        server.createContext("/checkin", this::handleCheckIn);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void usesPasswordLoginByDefault() {
        Map<String, String> environment = new HashMap<>();
        environment.put("TIDB_USERNAME", "user@example.com");
        environment.put("TIDB_PASSWORD", "secret-password");
        environment.put("TIDB_COOKIE", "csrftoken=cookie-csrf; ssid=cookie-ssid");

        TiDbCheckInTask task = task(environment);
        task.run();

        assertEquals(1, loginRequests.get());
        assertCookieContains(checkInCookie.get(), "csrftoken=login-csrf");
        assertCookieContains(checkInCookie.get(), "ssid=login-ssid");
        assertEquals("login-csrf", checkInCsrf.get());
        assertEquals("用户名：tester", task.getMessageBlocks().get(0).toString());
        assertEquals("签到结果：签到成功", task.getMessageBlocks().get(2).toString());
        assertFalse(task.buildNotificationSection().toString().contains("secret-password"));
        assertFalse(task.buildNotificationSection().toString().contains("cookie-ssid"));
    }

    @Test
    public void usesConfiguredCookieWithoutLogin() {
        Map<String, String> environment = new HashMap<>();
        environment.put("TIDB_LOGIN_MODE", "cookie");
        environment.put("TIDB_USERNAME", "user@example.com");
        environment.put("TIDB_PASSWORD", "secret-password");
        environment.put("TIDB_COOKIE", "csrftoken=cookie-csrf; ssid=cookie-ssid");

        TiDbCheckInTask task = task(environment);
        task.run();

        assertEquals(0, loginRequests.get());
        assertCookieContains(checkInCookie.get(), "csrftoken=cookie-csrf");
        assertCookieContains(checkInCookie.get(), "ssid=cookie-ssid");
        assertEquals("cookie-csrf", checkInCsrf.get());
        assertEquals("当前积分：12", task.getMessageBlocks().get(1).toString());
        assertEquals("本次获得 1 积分，明日签到可获得 2 积分", task.getMessageBlocks().get(3).toString());
        assertFalse(task.buildNotificationSection().toString().contains("secret-password"));
    }

    @Test
    public void skipsPasswordModeWhenCredentialsAreMissing() {
        TiDbCheckInTask task = task(new HashMap<String, String>());
        task.run();

        assertFalse(task.isEnabled());
        assertTrue(task.getMessageBlocks().isEmpty());
        assertEquals(0, loginRequests.get());
        assertNull(checkInCookie.get());
    }

    @Test
    public void skipsCookieModeWhenCookieIsMissing() {
        Map<String, String> environment = new HashMap<>();
        environment.put("TIDB_LOGIN_MODE", "COOKIE");
        TiDbCheckInTask task = task(environment);
        task.run();

        assertFalse(task.isEnabled());
        assertTrue(task.getMessageBlocks().isEmpty());
        assertEquals(0, loginRequests.get());
    }

    @Test
    public void reportsInvalidLoginMode() {
        Map<String, String> environment = new HashMap<>();
        environment.put("TIDB_LOGIN_MODE", "sms");
        environment.put("TIDB_COOKIE", "csrftoken=cookie-csrf; ssid=cookie-ssid");

        TiDbCheckInTask task = task(environment);
        task.run();

        assertEquals("TiDB 签到失败，请查看日志", task.getMessageBlocks().get(0).toString());
        assertEquals(0, loginRequests.get());
        assertNull(checkInCookie.get());
    }

    @Test
    public void reportsCookieWithoutCsrfToken() {
        Map<String, String> environment = new HashMap<>();
        environment.put("TIDB_LOGIN_MODE", "cookie");
        environment.put("TIDB_COOKIE", "ssid=cookie-ssid");

        TiDbCheckInTask task = task(environment);
        task.run();

        assertEquals("TiDB 签到失败，请查看日志", task.getMessageBlocks().get(0).toString());
        assertNull(checkInCookie.get());
        assertFalse(task.buildNotificationSection().toString().contains("cookie-ssid"));
    }

    private TiDbCheckInTask task(Map<String, String> environment) {
        TiDbCheckInTask task = new TiDbCheckInTask(environment);
        task.setLoginUrl(baseUrl + "/login");
        task.setUserUrl(baseUrl + "/me");
        task.setPointsUrl(baseUrl + "/points");
        task.setCheckInUrl(baseUrl + "/checkin");
        return task;
    }

    /** HTTP 不保证多个 Set-Cookie 的先后顺序，只校验 Cookie 是否齐全。 */
    private static void assertCookieContains(String cookieHeader, String expected) {
        assertTrue("Cookie 头缺少 " + expected + "，实际为 " + cookieHeader,
                cookieHeader != null && ArrayUtil.contains(cookieHeader.split(";\\s*"), expected));
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        loginRequests.incrementAndGet();
        exchange.getResponseHeaders().add("Set-Cookie", "csrftoken=login-csrf; Path=/");
        exchange.getResponseHeaders().add("Set-Cookie", "ssid=login-ssid; Path=/");
        json(exchange, 200, "{\"detail\":\"成功\"}");
    }

    private void handleCheckIn(HttpExchange exchange) throws IOException {
        checkInCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
        checkInCsrf.set(exchange.getRequestHeaders().getFirst("x-csrftoken"));
        json(exchange, 200, "{\"detail\":\"签到成功\",\"data\":{\"points\":\"1\",\"tomorrow_points\":\"2\"}}");
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
