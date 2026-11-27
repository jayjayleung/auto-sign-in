package org.jayjay.autosignin.task;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MoDbCheckInTaskTest {

    private static final String VALID_TOKEN = jwt(7200);

    private HttpServer server;
    private String baseUrl;
    private Path stateDir;
    private final AtomicInteger loginRequests = new AtomicInteger();
    private final AtomicInteger detailRequests = new AtomicInteger();
    private final AtomicReference<String> signedCookie = new AtomicReference<>();
    private final AtomicReference<String> detailAuth = new AtomicReference<>();
    private volatile int failUntil;
    /** 服务端认可的凭证；测试改它来模拟 token 被提前作废。 */
    private volatile String acceptedToken = VALID_TOKEN;
    private volatile boolean alreadySigned;
    private volatile String checkInMessage = "签到成功，墨值 +5";
    /** 模拟站点在未认证时返回 200 + 空 body。 */
    private volatile boolean emptyDetailBody;

    @Before
    public void setUp() throws IOException {
        loginRequests.set(0);
        detailRequests.set(0);
        failUntil = 0;
        acceptedToken = VALID_TOKEN;
        alreadySigned = false;
        emptyDetailBody = false;
        signedCookie.set(null);
        detailAuth.set(null);
        stateDir = Files.createTempDirectory("modb-test-state");
        // hutool 的 Cookie 仓库按主机共享且忽略端口，不清空会让相邻测试的 Cookie 串进来
        HttpRequest.getCookieManager().getCookieStore().removeAll();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", this::handleLogin);
        server.createContext("/detail", this::handleDetail);
        server.createContext("/clock", exchange ->
                json(exchange, 200, "{\"success\":true,\"operateCallBackObj\":1795774541966}"));
        server.createContext("/checkin", this::handleCheckIn);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
        deleteRecursively(stateDir);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录残留不影响测试结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    @Test
    public void logsInAndSavesTokenWhenNoSessionExists() {
        MoDbCheckInTask task = task(environment());

        task.run();

        assertEquals(1, loginRequests.get());
        assertEquals("签到结果：签到成功，墨值 +5", task.getMessageBlocks().get(2).toString());
        assertTrue("登录后应把凭证写进状态文件", Files.isRegularFile(sessionFile("user@example.com")));
        assertFalse("凭证不能出现在通知里",
                task.buildNotificationSection().toString().contains(VALID_TOKEN));
        assertFalse("密码不能出现在通知里",
                task.buildNotificationSection().toString().contains("secret-password"));
    }

    @Test
    public void reusesSavedTokenWithoutLoggingIn() {
        writeSession("user@example.com", VALID_TOKEN);
        MoDbCheckInTask task = task(environment());

        task.run();

        assertEquals("保存的凭证有效时不应再登录", 0, loginRequests.get());
        assertEquals("Bearer " + VALID_TOKEN, detailAuth.get());
        assertEquals("签到结果：签到成功，墨值 +5", task.getMessageBlocks().get(2).toString());
    }

    @Test
    public void relogsWhenSavedTokenIsRejected() {
        // 本地看没过期，但服务端已经不认了
        writeSession("user@example.com", jwt(7200, "revoked"));
        MoDbCheckInTask task = task(environment());

        task.run();

        assertEquals("凭证被拒后应回退登录", 1, loginRequests.get());
        assertEquals("签到结果：签到成功，墨值 +5", task.getMessageBlocks().get(2).toString());
        assertTrue("应换成新凭证覆盖旧文件",
                new String(readSession("user@example.com"), StandardCharsets.UTF_8).contains(VALID_TOKEN));
    }

    @Test
    public void relogsWhenSavedTokenIsLocallyExpired() {
        writeSession("user@example.com", jwt(-60));
        MoDbCheckInTask task = task(environment());

        task.run();

        assertEquals("本地已过期时不先发注定失败的探活请求", 1, loginRequests.get());
        assertEquals("唯一的 detail 请求应来自登录后的用户信息读取", 1, detailRequests.get());
        assertEquals("签到结果：签到成功，墨值 +5", task.getMessageBlocks().get(2).toString());
    }

    @Test
    public void skipsCheckInWhenAlreadySignedToday() {
        alreadySigned = true;
        MoDbCheckInTask task = task(environment());

        task.run();

        assertEquals("已签到时不再调用签到接口", 0, checkInCalls.get());
        assertEquals("签到结果：今日已签到", task.getMessageBlocks().get(2).toString());
    }

    @Test
    public void treatsEmptyDetailBodyAsInvalidSession() {
        // 未认证时站点返回 200 + 空 body：只看状态码会把匿名响应当成登录成功
        emptyDetailBody = true;
        writeSession("user@example.com", jwt(7200, "stale"));
        MoDbCheckInTask task = task(environment());

        task.run();

        assertEquals("空 body 应触发重新登录", 1, loginRequests.get());
        String text = task.buildNotificationSection().toString();
        assertTrue("登录后仍读不到用户信息时不能报成功", text.contains("墨天轮签到失败"));
    }

    @Test
    public void reportsLoginFailureWithoutLeakingCredentials() {
        acceptedToken = "nobody";
        Map<String, String> environment = environment();
        environment.put("MODB_PASSWORD", "wrong-password");
        MoDbCheckInTask task = task(environment);

        task.run();

        String text = task.buildNotificationSection().toString();
        assertTrue(text.contains("墨天轮签到失败"));
        assertFalse("失败提示不应泄露密码", text.contains("wrong-password"));
    }

    @Test
    public void skipsWhenCredentialsAreMissing() {
        MoDbCheckInTask task = task(new HashMap<String, String>());

        task.run();

        assertFalse(task.isEnabled());
        assertTrue(task.getMessageBlocks().isEmpty());
        assertEquals(0, loginRequests.get());
    }

    @Test
    public void parsesJwtExpiry() {
        long expiresAt = MoDbCheckInTask.tokenExpiresAt(VALID_TOKEN);
        assertTrue("应能解出 exp", expiresAt > 0);
        assertFalse("两小时后到期的凭证不算过期", MoDbCheckInTask.tokenExpired(VALID_TOKEN));
        assertTrue(MoDbCheckInTask.tokenExpired(jwt(-60)));
        assertEquals("不是 JWT 时返回 -1", -1, MoDbCheckInTask.tokenExpiresAt("opaque-token"));
        assertEquals("Bearer 前缀不影响解析", expiresAt, MoDbCheckInTask.tokenExpiresAt("Bearer " + VALID_TOKEN));
        assertFalse("解不出 exp 时交给服务端裁定，不判过期",
                MoDbCheckInTask.tokenExpired("opaque-token"));
    }

    @Test
    public void normalizesBearerPrefix() {
        assertEquals("Bearer abc", MoDbCheckInTask.bearer("abc"));
        assertEquals("Bearer abc", MoDbCheckInTask.bearer("Bearer abc"));
        assertEquals("Bearer abc", MoDbCheckInTask.bearer("bearer abc"));
        assertEquals("", MoDbCheckInTask.bearer(null));
        assertEquals("", MoDbCheckInTask.bearer("   "));
    }

    @Test
    public void scopesSessionFilePerAccount() {
        Path first = sessionFileFor("a@example.com");
        Path second = sessionFileFor("b@example.com");

        assertFalse("不同账号不能共用同一个凭证文件", first.equals(second));
        assertFalse("文件名不应包含账号明文", first.getFileName().toString().contains("a@example.com"));
    }

    /**
     * HttpURLConnection 对连接失败的 POST 会自动重发一次（sun.net.http.retryPost 默认开启），
     * 所以这里让前两次请求都失败，确保是我们自己的重试把第三次请求跑成功。
     */
    @Test
    public void retriesLoginWhenConnectionFailsRepeatedly() {
        failUntil = 2;
        MoDbCheckInTask task = task(environment());

        HttpResponse response = task.loginWithRetry(JSONUtil.createObj().set("phoneNum", "1"));

        assertEquals(200, response.getStatus());
        assertEquals(3, loginRequests.get());
    }

    @Test
    public void givesUpAfterConfiguredAttempts() {
        failUntil = Integer.MAX_VALUE;
        MoDbCheckInTask task = task(environment());

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

    private final AtomicInteger checkInCalls = new AtomicInteger();

    private Map<String, String> environment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("MODB_USERNAME", "user@example.com");
        environment.put("MODB_PASSWORD", "secret-password");
        environment.put("MODB_STATE_DIR", stateDir.toString());
        return environment;
    }

    private MoDbCheckInTask task(Map<String, String> environment) {
        MoDbCheckInTask task = new MoDbCheckInTask(environment);
        task.setLoginUrl(baseUrl + "/login");
        task.setUserUrl(baseUrl + "/detail");
        task.setClockUrl(baseUrl + "/clock");
        task.setCheckInUrl(baseUrl + "/checkin");
        task.setLoginRetryDelayMs(1L);
        return task;
    }

    private Path sessionFileFor(String account) {
        MoDbCheckInTask task = task(environment());
        return sessionDirOf(task).resolve("modb-session-" + CheckInTask.accountKey(account) + ".json");
    }

    private Path sessionDirOf(MoDbCheckInTask task) {
        return java.nio.file.Paths.get(task.getEnvironment().get("MODB_STATE_DIR"));
    }

    private Path sessionFile(String account) {
        return stateDir.resolve("modb-session-" + CheckInTask.accountKey(account) + ".json");
    }

    private void writeSession(String account, String token) {
        try {
            Files.write(sessionFile(account), JSONUtil.createObj()
                    .set("token", token)
                    .set("savedAt", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] readSession(String account) {
        try {
            return Files.readAllBytes(sessionFile(account));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        int attempt = loginRequests.incrementAndGet();
        // 前一次不回包并断开连接，模拟网络抖动
        if (attempt <= failUntil) {
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Authorization", "Bearer " + VALID_TOKEN);
        json(exchange, 200, "{\"operateMessage\":\"登录成功\",\"success\":true}");
    }

    private void handleDetail(HttpExchange exchange) throws IOException {
        detailRequests.incrementAndGet();
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        detailAuth.set(auth);
        if (!("Bearer " + acceptedToken).equals(auth)) {
            // 站点对无效凭证返回 403 的 nginx 页面
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }
        if (emptyDetailBody) {
            // 未认证时站点返回 200 + 空 body：判据不能只看状态码
            json(exchange, 200, "");
            return;
        }
        json(exchange, 200, "{\"userId\":558852,\"account\":\"呱太\",\"point\":1510,"
                + "\"todayDelta\":5,\"signed\":" + alreadySigned + "}");
    }

    private void handleCheckIn(HttpExchange exchange) throws IOException {
        checkInCalls.incrementAndGet();
        signedCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
        if (!("Bearer " + acceptedToken).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            json(exchange, 401, "{\"operateMessage\":\"请登录后操作\",\"success\":false}");
            return;
        }
        json(exchange, 200, "{\"operateMessage\":\"" + checkInMessage + "\",\"success\":true}");
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    /** 生成一个带 exp 的 HS512 风格 JWT；签名部分随便填，本地只读 payload。 */
    private static String jwt(long expiresInSeconds) {
        return jwt(expiresInSeconds, "valid");
    }

    private static String jwt(long expiresInSeconds, String subject) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"HS512\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(("{\"sub\":\"" + subject + "\",\"exp\":"
                + (System.currentTimeMillis() / 1000 + expiresInSeconds) + "}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".signature";
    }
}
