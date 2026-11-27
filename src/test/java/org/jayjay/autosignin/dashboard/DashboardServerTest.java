package org.jayjay.autosignin.dashboard;

import cn.hutool.json.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 看板服务的鉴权、路由与静态资源测试。只连本机随机端口，不访问外部网络。
 */
public class DashboardServerTest {

    private static final String USER = "admin";
    private static final String PASSWORD = "s3cret-pass";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private HttpServer server;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        Path stateRoot = folder.newFolder("state").toPath();
        Path configDir = folder.newFolder("config").toPath();
        Files.createDirectories(stateRoot.resolve("auto-sign-in"));
        server = startServer(stateRoot, configDir, Collections.singletonList("auto-sign-in"));
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpServer startServer(Path stateRoot, Path configDir, List<String> instances) throws IOException {
        int port = freePort();
        DashboardState state = new DashboardState(stateRoot, instances, configDir);
        HttpServer started = new DashboardServer(state, USER, PASSWORD).start("127.0.0.1", port);
        baseUrl = "http://127.0.0.1:" + port;
        return started;
    }

    // ==================== 鉴权 ====================

    @Test
    public void 未登录访问接口返回401() throws Exception {
        Response response = request("GET", "/api/overview", null, null);
        assertEquals(401, response.status);
        JSONObject json = new JSONObject(response.body);
        assertTrue(json.getBool("loginRequired"));
    }

    @Test
    public void 未登录访问首页返回页面由前端渲染登录表单() throws Exception {
        Response response = request("GET", "/", null, null);
        assertEquals(200, response.status);
        // 页面外壳统一返回，登录判定在前端完成
        assertTrue(response.body.contains("/assets/app.js"));
    }

    @Test
    public void 口令错误返回401() throws Exception {
        Response response = request("POST", "/login",
                "{\"username\":\"admin\",\"password\":\"wrong\"}", null);
        assertEquals(401, response.status);
        assertTrue(response.body.contains("账号或口令不正确"));
    }

    @Test
    public void 口令正确后可用cookie访问接口() throws Exception {
        String cookie = login();
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("asidash="));

        Response overview = request("GET", "/api/overview", null, cookie);
        assertEquals(200, overview.status);
        JSONObject json = new JSONObject(overview.body);
        assertEquals(1, json.getJSONArray("instances").size());
        assertEquals(1, json.getJSONObject("totals").getInt("instances").intValue());
    }

    @Test
    public void 退出后cookie失效() throws Exception {
        String cookie = login();
        assertEquals(200, request("GET", "/api/overview", null, cookie).status);

        Response logout = request("GET", "/logout", null, cookie);
        assertEquals(302, logout.status);
        assertEquals(401, request("GET", "/api/overview", null, cookie).status);
    }

    @Test
    public void 连续失败达到上限后锁定() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(401, request("POST", "/login",
                    "{\"username\":\"admin\",\"password\":\"bad\"}", null).status);
        }
        Response locked = request("POST", "/login",
                "{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}", null);
        assertEquals(429, locked.status);
        assertTrue(locked.body.contains("尝试次数过多"));
    }

    @Test
    public void 健康探针无需登录() throws Exception {
        Response response = request("GET", "/health", null, null);
        assertEquals(200, response.status);
        assertEquals("ok", response.body);
    }

    @Test
    public void 未配置口令时只给提示页且接口一律拒绝() throws Exception {
        int port = freePort();
        DashboardState empty = new DashboardState(null, Collections.<String>emptyList(), null);
        HttpServer unconfigured = new DashboardServer(empty, "", "").startUnconfigured("127.0.0.1", port);
        try {
            String url = "http://127.0.0.1:" + port;
            // 提示页可访问，告诉人怎么配
            Response page = request(url, "GET", "/", null, null);
            assertEquals(200, page.status);
            assertTrue(page.body.contains("DASHBOARD_PASSWORD"));
            assertTrue(page.body.contains(".env.dashboard"));

            // 探针返回未就绪，便于外部发现
            Response health = request(url, "GET", "/health", null, null);
            assertEquals(503, health.status);

            // 任何接口都不能拿到数据，也不能因为口令为空而被绕过
            for (String path : Arrays.asList("/api/overview", "/api/instances", "/api/runs", "/api/config")) {
                assertEquals("接口 " + path + " 必须拒绝", 503, request(url, "GET", path, null, null).status);
            }
            // 即便提交空账号口令也不行
            assertEquals(503, request(url, "POST", "/login",
                    "{\"username\":\"\",\"password\":\"\"}", null).status);
        } finally {
            unconfigured.stop(0);
        }
    }

    // ==================== 路由 ====================

    @Test
    public void 各接口在登录后都能访问() throws Exception {
        String cookie = login();
        for (String path : Arrays.asList("/api/overview", "/api/instances", "/api/runs",
                "/api/config", "/api/pipeline",
                "/api/instances/auto-sign-in", "/api/instances/auto-sign-in?history=5&logs=1&tail=20")) {
            Response response = request("GET", path, null, cookie);
            assertEquals("接口 " + path + " 应返回 200，实际 " + response.status, 200, response.status);
        }
    }

    @Test
    public void 不存在的接口返回404() throws Exception {
        String cookie = login();
        assertEquals(404, request("GET", "/api/nope", null, cookie).status);
    }

    @Test
    public void 不存在的实例返回404() throws Exception {
        String cookie = login();
        assertEquals(404, request("GET", "/api/instances/unknown", null, cookie).status);
    }

    @Test
    public void 不存在的运行记录返回404() throws Exception {
        String cookie = login();
        assertEquals(404, request("GET",
                "/api/runs/auto-sign-in/2026-01-01T00-00-00.json", null, cookie).status);
    }

    @Test
    public void 白名单外的实例名被拒绝() throws Exception {
        String cookie = login();
        // 编码后的 ../ 不能穿出 state 目录
        assertEquals(404, request("GET", "/api/instances/..%2F..%2Fetc", null, cookie).status);
    }

    @Test
    public void 运行列表支持只看失败与分页参数() throws Exception {
        Path runDir = folder.getRoot().toPath().resolve("state/auto-sign-in/run-history");
        Files.createDirectories(runDir);
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        Files.write(runDir.resolve("2026-11-26T09-00-00.json"),
                ("{\"startedAt\":\"2026-11-26T09:00:00+08:00\",\"finishedAt\":\"2026-11-26T09:02:00+08:00\","
                        + "\"durationSeconds\":120,\"exceptionCount\":0,\"sections\":[],\"console\":\"\"}")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(runDir.resolve("2026-11-27T09-00-00.json"),
                ("{\"startedAt\":\"2026-11-27T09:00:00+08:00\",\"finishedAt\":\"2026-11-27T09:02:00+08:00\","
                        + "\"durationSeconds\":120,\"exceptionCount\":2,\"sections\":[],\"console\":\"\"}")
                        .getBytes(StandardCharsets.UTF_8));

        String cookie = login();
        JSONObject all = new JSONObject(request("GET", "/api/runs", null, cookie).body);
        assertEquals(2, all.getInt("returned").intValue());

        JSONObject failed = new JSONObject(request("GET", "/api/runs?onlyFailed=true", null, cookie).body);
        assertEquals(1, failed.getInt("returned").intValue());
        assertEquals(2, failed.getJSONArray("runs").getJSONObject(0).getInt("exceptionCount").intValue());

        JSONObject paged = new JSONObject(request("GET", "/api/runs?limit=1&offset=1", null, cookie).body);
        assertEquals(1, paged.getInt("returned").intValue());
        assertTrue(paged.getJSONArray("runs").getJSONObject(0).getStr("finishedAt").startsWith("2026-11-26"));
        assertFalse(today.isEmpty());
    }

    @Test
    public void 单条运行详情返回分组结果() throws Exception {
        Path runDir = folder.getRoot().toPath().resolve("state/auto-sign-in/run-history");
        Files.createDirectories(runDir);
        Files.write(runDir.resolve("2026-11-27T09-00-00.json"),
                ("{\"startedAt\":\"2026-11-27T09:00:00+08:00\",\"finishedAt\":\"2026-11-27T09:02:00+08:00\","
                        + "\"durationSeconds\":120,\"exceptionCount\":0,\"console\":\"签到成功\","
                        + "\"sections\":[{\"title\":\"西门子签到\",\"blocks\":[{\"lines\":[{\"parts\":"
                        + "[{\"text\":\"签到成功\",\"bold\":true}]}]}]}]}")
                        .getBytes(StandardCharsets.UTF_8));

        String cookie = login();
        JSONObject run = new JSONObject(request("GET",
                "/api/runs/auto-sign-in/2026-11-27T09-00-00.json", null, cookie).body);
        assertEquals("auto-sign-in", run.getStr("instance"));
        assertEquals(1, run.getJSONArray("sections").size());
        assertEquals("西门子签到", run.getJSONArray("sections").getJSONObject(0).getStr("title"));
    }

    @Test
    public void 深链接路径都返回页面外壳() throws Exception {
        String cookie = login();
        for (String path : Arrays.asList("/", "/instances", "/instances/auto-sign-in", "/runs", "/config")) {
            Response response = request("GET", path, null, cookie);
            assertEquals("路径 " + path + " 应返回 200", 200, response.status);
            assertTrue("路径 " + path + " 应是页面外壳", response.body.contains("/assets/app.js"));
        }
    }

    // ==================== 静态资源 ====================

    @Test
    public void 静态资源可从jar读取且不需要登录() throws Exception {
        for (String path : Arrays.asList("/assets/app.js", "/assets/app.css", "/assets/util.js")) {
            Response response = request("GET", path, null, null);
            assertEquals("资源 " + path + " 应返回 200", 200, response.status);
            assertTrue(response.body.length() > 0);
        }
        assertTrue(request("GET", "/assets/util.js", null, null).contentType.contains("javascript"));
    }

    @Test
    public void 静态资源拒绝目录穿越与未知类型() throws Exception {
        assertEquals(404, request("GET", "/assets/../../../etc/passwd", null, null).status);
        assertEquals(404, request("GET", "/assets/nope.js", null, null).status);
    }

    // ==================== 安全 ====================

    @Test
    public void 接口不会漏出配置值() throws Exception {
        Path configDir = folder.newFolder("config-secret").toPath();
        String secret = "do-not-leak-me";
        Files.write(configDir.resolve(".env"),
                ("MODB_PASSWORD=" + secret + "\n").getBytes(StandardCharsets.UTF_8));
        Path stateRoot = folder.newFolder("state-secret").toPath();
        Files.createDirectories(stateRoot.resolve("auto-sign-in"));

        HttpServer other = startServer(stateRoot, configDir, Collections.singletonList("auto-sign-in"));
        try {
            String cookie = login();
            for (String path : Arrays.asList("/api/config", "/api/instances/auto-sign-in", "/api/overview")) {
                Response response = request("GET", path, null, cookie);
                assertEquals(200, response.status);
                assertFalse("接口 " + path + " 漏出了配置值", response.body.contains(secret));
            }
        } finally {
            other.stop(0);
        }
    }

    // ==================== 工具 ====================

    private String login() throws Exception {
        return request("POST", "/login",
                "{\"username\":\"" + USER + "\",\"password\":\"" + PASSWORD + "\"}", null).cookie;
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Response request(String method, String path, String body, String cookie) throws Exception {
        return request(baseUrl, method, path, body, cookie);
    }

    /** 指定 baseUrl 的重载，用于同一测试里起第二个服务。 */
    private static Response request(String baseUrl, String method, String path, String body, String cookie)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = input == null ? "" : readAll(input);
        String setCookie = connection.getHeaderField("Set-Cookie");
        String contentType = connection.getHeaderField("Content-Type");
        return new Response(status, text, setCookie == null ? null : setCookie.split(";")[0],
                contentType == null ? "" : contentType);
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        input.close();
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class Response {
        private final int status;
        private final String body;
        private final String cookie;
        private final String contentType;

        private Response(int status, String body, String cookie, String contentType) {
            this.status = status;
            this.body = body;
            this.cookie = cookie;
            this.contentType = contentType;
        }
    }
}
