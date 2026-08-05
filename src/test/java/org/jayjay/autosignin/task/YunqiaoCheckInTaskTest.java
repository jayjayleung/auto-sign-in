package org.jayjay.autosignin.task;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class YunqiaoCheckInTaskTest {

    private HttpServer server;
    private String baseUrl;
    private final Map<String, Boolean> checkedIn = new ConcurrentHashMap<>();
    private final AtomicInteger checkInRequests = new AtomicInteger();
    private final AtomicReference<String> secondSessionRequestCookie = new AtomicReference<>();
    private volatile boolean omitSecondSessionCookie;

    @Before
    public void setUp() throws IOException {
        checkedIn.put("1", true);
        checkedIn.put("2", false);
        checkInRequests.set(0);
        secondSessionRequestCookie.set(null);
        omitSecondSessionCookie = false;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/auth/login", this::handleLogin);
        server.createContext("/points", this::handlePointsSession);
        server.createContext("/api/user/points/bootstrap", this::handleBootstrap);
        server.createContext("/api/user/points/checkin", this::handleCheckIn);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void runsConfiguredAccountsIndependently() {
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_USERNAME_1", "one@example.com");
        environment.put("YUNQIAO_PASSWORD_1", "password-one");
        environment.put("YUNQIAO_USERNAME_2", "two@example.com");
        environment.put("YUNQIAO_PASSWORD_2", "password-two");
        environment.put("YUNQIAO_USERNAME_3", "incomplete@example.com");

        YunqiaoCheckInTask task = new YunqiaoCheckInTask(environment, baseUrl, baseUrl);
        task.run();

        assertEquals("云桥签到", task.messageList().getTitle());
        List<StringBuilder> messages = task.getListMessage();
        assertEquals(3, messages.size());
        assertEquals("<strong>账号 1</strong>（one@example.com）<br>当前积分：3<br>连续签到：1 天<br>签到结果：今日已签到", messages.get(0).toString());
        assertEquals("<strong>账号 2</strong>（two@example.com）<br>当前积分：3<br>连续签到：1 天<br>签到结果：签到成功<br>连续签到奖励：2 积分", messages.get(1).toString());
        assertEquals("<strong>账号 3</strong>（incomplete@example.com）<br>签到结果：已跳过<br>原因：用户名或密码配置不完整", messages.get(2).toString());
        assertFalse(messages.toString().contains("总积分"));
        assertFalse(messages.toString().contains("password-one"));
        assertFalse(messages.toString().contains("password-two"));
        assertEquals(1, checkInRequests.get());
        assertTrue(checkedIn.get("2"));
        assertTrue(secondSessionRequestCookie.get() == null || secondSessionRequestCookie.get().trim().isEmpty());
    }

    @Test
    public void hidesAccountIndexWhenOnlyOneAccountRuns() {
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_USERNAME", "one@example.com");
        environment.put("YUNQIAO_PASSWORD", "password-one");

        YunqiaoCheckInTask task = new YunqiaoCheckInTask(environment, baseUrl, baseUrl);
        task.run();

        assertEquals(1, task.getListMessage().size());
        assertEquals(
                "<strong>账号</strong>（one@example.com）<br>当前积分：3<br>连续签到：1 天<br>签到结果：今日已签到",
                task.getListMessage().get(0).toString()
        );
    }

    @Test
    public void hidesAccountIndexWhenOnlyOneNumberedAccountRuns() {
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_USERNAME_2", "one@example.com");
        environment.put("YUNQIAO_PASSWORD_2", "password-one");

        YunqiaoCheckInTask task = new YunqiaoCheckInTask(environment, baseUrl, baseUrl);
        task.run();

        assertEquals(1, task.getListMessage().size());
        assertEquals(
                "<strong>账号</strong>（one@example.com）<br>当前积分：3<br>连续签到：1 天<br>签到结果：今日已签到",
                task.getListMessage().get(0).toString()
        );
    }

    @Test
    public void failsClosedWhenAccountDoesNotReceiveANewSessionCookie() {
        omitSecondSessionCookie = true;
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_USERNAME_1", "one@example.com");
        environment.put("YUNQIAO_PASSWORD_1", "password-one");
        environment.put("YUNQIAO_USERNAME_2", "two@example.com");
        environment.put("YUNQIAO_PASSWORD_2", "password-two");

        YunqiaoCheckInTask task = new YunqiaoCheckInTask(environment, baseUrl, baseUrl);
        task.run();

        List<StringBuilder> messages = task.getListMessage();
        assertEquals(2, messages.size());
        assertEquals("<strong>账号 1</strong>（one@example.com）<br>当前积分：3<br>连续签到：1 天<br>签到结果：今日已签到", messages.get(0).toString());
        assertEquals("<strong>账号 2</strong>（two@example.com）<br>签到结果：签到失败<br>原因：建立积分会话失败，未获取会话 Cookie", messages.get(1).toString());
        assertTrue(secondSessionRequestCookie.get() == null || secondSessionRequestCookie.get().trim().isEmpty());
        assertEquals(0, checkInRequests.get());
        assertFalse(messages.toString().contains("password-one"));
        assertFalse(messages.toString().contains("password-two"));
    }

    @Test
    public void loadsAccountsInNumericOrder() {
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_PASSWORD_10", "ten-password");
        environment.put("YUNQIAO_USERNAME_2", "two@example.com");
        environment.put("YUNQIAO_PASSWORD_2", "two-password");
        environment.put("YUNQIAO_USERNAME_10", "ten@example.com");

        List<YunqiaoCheckInTask.Account> accounts = YunqiaoCheckInTask.loadAccounts(environment);

        assertEquals(2, accounts.size());
        assertEquals(2, accounts.get(0).index);
        assertEquals(10, accounts.get(1).index);
        assertTrue(accounts.get(0).isComplete());
        assertTrue(accounts.get(1).isComplete());
    }

    @Test
    public void loadsUnnumberedAccountAsFirstAccount() {
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_USERNAME", "single@example.com");
        environment.put("YUNQIAO_PASSWORD", "single-password");

        List<YunqiaoCheckInTask.Account> accounts = YunqiaoCheckInTask.loadAccounts(environment);

        assertEquals(1, accounts.size());
        assertEquals(1, accounts.get(0).index);
        assertEquals("single@example.com", accounts.get(0).username);
        assertEquals("single-password", accounts.get(0).password);
    }

    @Test
    public void numberedFirstAccountOverridesUnnumberedAccount() {
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_USERNAME", "single@example.com");
        environment.put("YUNQIAO_PASSWORD", "single-password");
        environment.put("YUNQIAO_USERNAME_1", "numbered@example.com");
        environment.put("YUNQIAO_PASSWORD_1", "numbered-password");

        List<YunqiaoCheckInTask.Account> accounts = YunqiaoCheckInTask.loadAccounts(environment);

        assertEquals(1, accounts.size());
        assertEquals("numbered@example.com", accounts.get(0).username);
        assertEquals("numbered-password", accounts.get(0).password);
    }

    @Test
    public void partialNumberedFirstAccountDoesNotMixWithUnnumberedAccount() {
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_USERNAME", "single@example.com");
        environment.put("YUNQIAO_PASSWORD", "single-password");
        environment.put("YUNQIAO_USERNAME_1", "numbered@example.com");
        environment.put("YUNQIAO_PASSWORD_1", "");

        List<YunqiaoCheckInTask.Account> accounts = YunqiaoCheckInTask.loadAccounts(environment);

        assertEquals(1, accounts.size());
        assertEquals("numbered@example.com", accounts.get(0).username);
        assertEquals("", accounts.get(0).password);
        assertFalse(accounts.get(0).isComplete());
    }

    @Test
    public void blankNumberedFirstAccountFallsBackToUnnumberedAccount() {
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_USERNAME", "single@example.com");
        environment.put("YUNQIAO_PASSWORD", "single-password");
        environment.put("YUNQIAO_USERNAME_1", "   ");
        environment.put("YUNQIAO_PASSWORD_1", "\t");

        List<YunqiaoCheckInTask.Account> accounts = YunqiaoCheckInTask.loadAccounts(environment);

        assertEquals(1, accounts.size());
        assertEquals("single@example.com", accounts.get(0).username);
        assertEquals("single-password", accounts.get(0).password);
    }

    @Test
    public void skipsUnnumberedAccountWhenVariablesHaveNoValues() {
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_USERNAME", " ");
        environment.put("YUNQIAO_PASSWORD", "\t");

        List<YunqiaoCheckInTask.Account> accounts = YunqiaoCheckInTask.loadAccounts(environment);

        assertTrue(accounts.isEmpty());
    }

    @Test
    public void skipsTaskWhenAccountVariablesHaveNoValues() {
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_USERNAME_1", "");
        environment.put("YUNQIAO_PASSWORD_1", "");
        environment.put("YUNQIAO_USERNAME_2", "   ");
        environment.put("YUNQIAO_PASSWORD_2", "\t");

        YunqiaoCheckInTask task = new YunqiaoCheckInTask(environment, baseUrl, baseUrl);
        task.run();

        assertFalse(task.isRun());
        assertTrue(task.getListMessage().isEmpty());
        assertEquals(0, checkInRequests.get());
    }

    @Test
    public void keepsPartiallyConfiguredAccountForValidationMessage() {
        Map<String, String> environment = new HashMap<>();
        environment.put("YUNQIAO_USERNAME_1", "incomplete@example.com");
        environment.put("YUNQIAO_PASSWORD_1", "");

        YunqiaoCheckInTask task = new YunqiaoCheckInTask(environment, baseUrl, baseUrl);
        task.run();

        assertTrue(task.isRun());
        assertEquals(1, task.getListMessage().size());
        assertEquals(
                "<strong>账号</strong>（incomplete@example.com）<br>签到结果：已跳过<br>原因：用户名或密码配置不完整",
                task.getListMessage().get(0).toString()
        );
        assertEquals(0, checkInRequests.get());
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        JSONObject request = JSONUtil.parseObj(readBody(exchange));
        String email = request.getStr("email");
        String userId = email.startsWith("one") ? "1" : "2";
        JSONObject response = success(JSONUtil.createObj()
                .set("access_token", "token-" + userId)
                .set("user", JSONUtil.createObj().set("id", Integer.parseInt(userId))));
        writeJson(exchange, response);
    }

    private void handlePointsSession(HttpExchange exchange) throws IOException {
        String userId = queryValue(exchange.getRequestURI(), "user_id");
        if ("2".equals(userId)) {
            secondSessionRequestCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            if (!omitSecondSessionCookie) {
                exchange.getResponseHeaders().add("Set-Cookie", "points_session=" + userId + "; Path=/; HttpOnly");
            }
        } else {
            exchange.getResponseHeaders().add("Set-Cookie", "points_session=" + userId + "; Path=/; HttpOnly");
        }
        exchange.getResponseHeaders().add("Location", "/points?theme=light&lang=zh");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void handleBootstrap(HttpExchange exchange) throws IOException {
        String userId = cookieValue(exchange, "points_session");
        boolean isCheckedIn = Boolean.TRUE.equals(checkedIn.get(userId));
        JSONObject data = JSONUtil.createObj()
                .set("enabled", true)
                .set("checked_in_today", isCheckedIn)
                .set("current_streak", isCheckedIn ? 1 : 0)
                .set("account", JSONUtil.createObj().set("points_balance", isCheckedIn ? 3 : 2))
                .set("config", JSONUtil.createObj().set("daily_checkin_points", 1));
        writeJson(exchange, success(data));
    }

    private void handleCheckIn(HttpExchange exchange) throws IOException {
        String userId = cookieValue(exchange, "points_session");
        checkInRequests.incrementAndGet();
        if (Boolean.TRUE.equals(checkedIn.get(userId))) {
            writeJson(exchange, JSONUtil.createObj().set("code", 400).set("message", "今天已经签到"));
            return;
        }
        checkedIn.put(userId, true);
        writeJson(exchange, success(JSONUtil.createObj().set("streak_bonus", 2)));
    }

    private static JSONObject success(JSONObject data) {
        return JSONUtil.createObj().set("code", 0).set("message", "success").set("data", data);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String queryValue(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            return "";
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) {
                return pair[1];
            }
        }
        return "";
    }

    private static String cookieValue(HttpExchange exchange, String name) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) {
            return "";
        }
        for (String part : cookie.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) {
                return pair[1];
            }
        }
        return "";
    }

    private static void writeJson(HttpExchange exchange, JSONObject body) throws IOException {
        writeText(exchange, body.toString(), "application/json");
    }

    private static void writeText(HttpExchange exchange, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
