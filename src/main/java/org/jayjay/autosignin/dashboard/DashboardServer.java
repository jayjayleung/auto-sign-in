package org.jayjay.autosignin.dashboard;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 看板 HTTP 服务：REST 接口 + 静态资源 + 账号口令鉴权。
 * <p>
 * 全部接口只读；用 JDK 自带的 {@link HttpServer}，不引入 Web 框架。
 * 口令只从环境变量读取，未配置时拒绝启动，不会裸奔。
 */
public final class DashboardServer {

    static final String USER_KEY = "DASHBOARD_USER";
    static final String PASSWORD_KEY = "DASHBOARD_PASSWORD";
    static final String PORT_KEY = "DASHBOARD_PORT";
    static final String BIND_KEY = "DASHBOARD_BIND";

    /** 会话有效期，过期要重新登录。 */
    private static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final String COOKIE_NAME = "asidash";
    /** 登录失败次数上限与锁定时长，防止口令被在线爆破。 */
    private static final int LOGIN_FAIL_LIMIT = 5;
    private static final Duration LOGIN_LOCK = Duration.ofMinutes(10);
    /** 详情页默认取多少条历史 / 几份日志 / 日志尾部多少行。 */
    private static final int DEFAULT_HISTORY = 20;
    private static final int DEFAULT_LOGS = 3;
    private static final int DEFAULT_LOG_TAIL = 200;

    private final DashboardState state;
    private final Routes routes = new Routes();
    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    private final Map<String, Failure> failures = new ConcurrentHashMap<String, Failure>();
    private final byte[] pageBytes;
    private final String user;
    private final String password;

    DashboardServer(DashboardState state, String user, String password) {
        this.state = state;
        this.user = StrUtil.trimToEmpty(user);
        this.password = StrUtil.nullToEmpty(password);
        this.pageBytes = loadResource("/dashboard/index.html");
        registerApi();
    }

    public static void main(String[] args) throws IOException {
        String user = System.getenv(USER_KEY);
        String password = System.getenv(PASSWORD_KEY);
        int port = intValue(System.getenv(PORT_KEY), 8090);
        String bind = StrUtil.blankToDefault(StrUtil.trimToEmpty(System.getenv(BIND_KEY)), "0.0.0.0");
        if (StrUtil.isBlank(user) || StrUtil.isBlank(password)) {
            // 没配口令时不能裸奔，也不能直接退出——容器配了 restart: always 会变成无限重启，
            // 反复重启反而比停着更难排查。这里起一个只给提示的服务，所有接口一律 503。
            System.out.println("[看板] 未配置 " + USER_KEY + " / " + PASSWORD_KEY
                    + "，仅提供配置提示页，接口一律拒绝");
            new DashboardServer(new DashboardState(null, new ArrayList<String>(), null), "", "")
                    .startUnconfigured(bind, port);
            return;
        }
        Path stateRoot = Paths.get(StrUtil.blankToDefault(System.getenv("DASHBOARD_STATE_DIR"), "/app/dashboard-state"));
        Path configDir = Paths.get(StrUtil.blankToDefault(System.getenv("DASHBOARD_CONFIG_DIR"), "/app/dashboard-config"));
        List<String> instances = new ArrayList<String>();
        for (String name : StrUtil.blankToDefault(System.getenv("DASHBOARD_INSTANCES"),
                "auto-sign-in,auto-sign-in-fanfan,auto-sign-in-no1").split(",")) {
            if (StrUtil.isNotBlank(name)) {
                instances.add(name.trim());
            }
        }
        new DashboardServer(new DashboardState(stateRoot, instances, configDir), user, password)
                .start(bind, port);
    }

    /** 注册所有 API 路由。 */    private void registerApi() {
        routes.get("/api/overview", request -> Routes.Reply.json(state.overview()));
        routes.get("/api/instances", request -> Routes.Reply.json(state.instanceList()));
        routes.get("/api/instances/{name}", request -> {
            String name = Routes.decodeSegment(request.path("name"));
            JSONObject detail = state.instanceDetail(name,
                    request.queryInt("history", DEFAULT_HISTORY, 1, 100),
                    request.queryInt("logs", DEFAULT_LOGS, 0, 10),
                    request.queryInt("tail", DEFAULT_LOG_TAIL, 1, 2000));
            return detail == null ? Routes.Reply.error(404, "实例不存在") : Routes.Reply.json(detail);
        });
        routes.get("/api/runs", request -> Routes.Reply.json(state.runs(
                request.queryInt("offset", 0, 0, 100000),
                request.queryInt("limit", 20, 1, 200),
                request.queryBool("onlyFailed"),
                request.query("instance", "all"))));
        routes.get("/api/runs/{instance}/{file}", request -> {
            String instance = Routes.decodeSegment(request.path("instance"));
            String file = Routes.decodeSegment(request.path("file"));
            JSONObject run = state.run(instance, file);
            return run == null ? Routes.Reply.error(404, "运行记录不存在") : Routes.Reply.json(run);
        });
        routes.get("/api/config", request -> Routes.Reply.json(state.configComparison()));
        routes.get("/api/pipeline", request -> Routes.Reply.json(state.pipeline()));
    }

    /** 启动并打印访问地址；只看日志就能知道服务在哪。 */
    public HttpServer start(String bind, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("[看板] 已启动：http://" + bind + ":" + server.getAddress().getPort()
                + "/ （账号 " + user + "）");
        return server;
    }

    /**
     * 未配置口令时的降级模式：只响应配置提示页，`/health` 返回未就绪，其他一律 503。
     * 这样容器能稳定停在这里等人配置，而不是被 restart 策略反复拉起。
     */
    HttpServer startUnconfigured(String bind, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if ("/health".equals(path)) {
                    Routes.Reply.text(503, "text/plain;charset=utf-8", "not-configured").writeTo(exchange);
                    return;
                }
                // 除提示页外一律拒绝：POST /login 也不能放过，否则空口令有被绕过的风险
                if (path.startsWith("/api/") || "POST".equalsIgnoreCase(method)) {
                    Routes.Reply.error(503, "看板尚未配置账号口令").writeTo(exchange);
                    return;
                }
                String hint = "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                        + "<title>看板未配置</title><style>body{font:14px/1.7 -apple-system,sans-serif;"
                        + "background:#0e1116;color:#e6edf3;padding:48px;max-width:620px;margin:0 auto}"
                        + "code{background:#1c232d;padding:2px 6px;border-radius:4px;"
                        + "font-family:ui-monospace,monospace}h1{font-size:18px}"
                        + "pre{background:#0a0d12;border:1px solid #26303c;border-radius:8px;padding:14px;"
                        + "overflow-x:auto;font-family:ui-monospace,monospace;font-size:12px}"
                        + ".muted{color:#8b98a5}</style></head><body>"
                        + "<h1>看板还没有配置账号口令</h1>"
                        + "<p class=\"muted\">为了避免无鉴权暴露签到状态，看板在配置完成前不提供任何数据。</p>"
                        + "<p>在工作区创建 <code>.env.dashboard</code> 并填入账号和口令：</p>"
                        + "<pre>cp .env.dashboard.example .env.dashboard\n"
                        + "# 编辑 .env.dashboard，设置 DASHBOARD_USER 和 DASHBOARD_PASSWORD\n"
                        + "docker compose --profile dashboard up -d auto-sign-in-dashboard</pre>"
                        + "<p class=\"muted\">口令请用 16 位以上随机串，不要与签到账号相同。</p>"
                        + "</body></html>";
                Routes.Reply.text(200, "text/html;charset=utf-8", hint).writeTo(exchange);
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        System.out.println("[看板] 配置提示页已启动：http://" + bind + ":" + server.getAddress().getPort() + "/");
        return server;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/health".equals(path)) {
                Routes.Reply.text(200, "text/plain;charset=utf-8", "ok").writeTo(exchange);
                return;
            }
            if ("/login".equals(path)) {
                if ("POST".equalsIgnoreCase(method)) {
                    login(exchange);
                } else {
                    write(exchange, 200, "text/html;charset=utf-8", pageBytes);
                }
                return;
            }
            if ("/logout".equals(path)) {
                sessions.remove(cookieValue(exchange));
                exchange.getResponseHeaders().add("Set-Cookie",
                        COOKIE_NAME + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
                redirect(exchange, "/login");
                return;
            }
            if (path.startsWith("/assets/")) {
                // 静态资源不需要鉴权，里面没有任何业务数据；页面本身会先要求登录。
                Routes.Reply asset = staticAsset(path);
                if (asset == null) {
                    Routes.Reply.error(404, "资源不存在").writeTo(exchange);
                } else {
                    asset.writeTo(exchange);
                }
                return;
            }
            if (path.startsWith("/api/")) {
                if (!authorized(exchange)) {
                    Routes.Reply.json(401, new JSONObject().set("error", "未登录").set("loginRequired", true))
                            .writeTo(exchange);
                    return;
                }
                dispatch(exchange, method, path);
                return;
            }
            // 其余路径全部回页面，由前端路由渲染对应视图。
            write(exchange, 200, "text/html;charset=utf-8", pageBytes);
        } catch (Exception e) {
            Routes.Reply.error(500, "服务内部错误").writeTo(exchange);
        } finally {
            exchange.close();
        }
    }

    private void dispatch(HttpExchange exchange, String method, String path) throws Exception {
        Map<String, String> params = new HashMap<String, String>();
        Map<String, String> query = Routes.parseQuery(exchange.getRequestURI().getRawQuery());
        Routes.Route route = routes.find(method, path, params, query);
        if (route == null) {
            Routes.Reply.error(404, "接口不存在").writeTo(exchange);
            return;
        }
        route.handle(Routes.request(params, query)).writeTo(exchange);
    }

    // ==================== 静态资源 ====================

    /**
     * 从 jar 里读前端文件。
     * 只允许白名单后缀，且拒绝 {@code ..}，避免被拼出任意资源读取。
     */
    private static Routes.Reply staticAsset(String path) {
        if (path.contains("..") || path.contains("\\")) {
            return null;
        }
        String lower = path.toLowerCase();
        String contentType;
        if (lower.endsWith(".js")) {
            contentType = "text/javascript;charset=utf-8";
        } else if (lower.endsWith(".css")) {
            contentType = "text/css;charset=utf-8";
        } else if (lower.endsWith(".html")) {
            contentType = "text/html;charset=utf-8";
        } else if (lower.endsWith(".svg")) {
            contentType = "image/svg+xml";
        } else {
            return null;
        }
        try (InputStream input = DashboardServer.class.getResourceAsStream("/dashboard" + path)) {
            if (input == null) {
                return null;
            }
            return Routes.Reply.bytes(contentType, readAll(input).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 登录 ====================

    private void login(HttpExchange exchange) throws IOException {
        String remote = String.valueOf(exchange.getRemoteAddress().getAddress().getHostAddress());
        Failure failure = failures.get(remote);
        if (failure != null && failure.locked()) {
            Routes.Reply.json(429, new JSONObject()
                    .set("error", "尝试次数过多，请 " + failure.remainingMinutes() + " 分钟后再试"))
                    .writeTo(exchange);
            return;
        }
        JSONObject body = parseBody(exchange);
        String inputUser = StrUtil.trimToEmpty(body.getStr("username"));
        String inputPassword = StrUtil.nullToEmpty(body.getStr("password"));
        // 口令未配置时任何输入都不放行：空串和空串比较会相等，不能只靠 constantEquals。
        boolean configured = StrUtil.isNotBlank(user) && StrUtil.isNotBlank(password);
        if (!configured || !constantEquals(user, inputUser) || !constantEquals(password, inputPassword)) {
            int count = failure == null ? 1 : failure.count + 1;
            failures.put(remote, new Failure(count, Instant.now()));
            Routes.Reply.json(401, new JSONObject().set("error", "账号或口令不正确")).writeTo(exchange);
            return;
        }
        failures.remove(remote);
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, new Session(Instant.now().plus(SESSION_TTL)));
        exchange.getResponseHeaders().add("Set-Cookie",
                COOKIE_NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + SESSION_TTL.getSeconds());
        redirect(exchange, "/");
    }

    /** 定长比较，避免按字符逐位比对时泄漏前缀长度。 */
    private static boolean constantEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private boolean authorized(HttpExchange exchange) {
        String token = cookieValue(exchange);
        if (StrUtil.isBlank(token)) {
            return false;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return false;
        }
        if (session.expiresAt.isBefore(Instant.now())) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    private static String cookieValue(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return "";
        }
        for (String header : headers) {
            for (String part : header.split(";")) {
                String text = part.trim();
                if (text.startsWith(COOKIE_NAME + "=")) {
                    return text.substring(COOKIE_NAME.length() + 1).trim();
                }
            }
        }
        return "";
    }

    // ==================== 响应 ====================

    private static JSONObject parseBody(HttpExchange exchange) {
        try {
            return JSONUtil.parseObj(readAll(exchange.getRequestBody()));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void write(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        Routes.Reply.bytes(contentType, body).writeTo(exchange, status);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    /** 页面作为资源放在 jar 里，容器里不需要额外挂载文件。 */
    private static byte[] loadResource(String resource) {
        try (InputStream input = DashboardServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                return "<h1>看板页面缺失</h1>".getBytes(StandardCharsets.UTF_8);
            }
            return readAll(input).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<h1>看板页面读取失败</h1>".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static int intValue(String text, int fallback) {
        try {
            return StrUtil.isBlank(text) ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 登录成功后的一次会话。 */
    private static final class Session {
        private final Instant expiresAt;

        private Session(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    /** 单个来源的登录失败计数。 */
    private static final class Failure {
        private final int count;
        private final Instant at;

        private Failure(int count, Instant at) {
            this.count = count;
            this.at = at;
        }

        private boolean locked() {
            return count >= LOGIN_FAIL_LIMIT && at.plus(LOGIN_LOCK).isAfter(Instant.now());
        }

        private long remainingMinutes() {
            long seconds = Duration.between(Instant.now(), at.plus(LOGIN_LOCK)).getSeconds();
            return Math.max(1L, (seconds + 59L) / 60L);
        }
    }
}
