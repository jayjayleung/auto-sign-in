package org.jayjay.autosignin.dashboard;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简路由：把请求路径匹配成「处理器 + 路径参数」。
 * <p>
 * 只用 JDK 就够了，不需要引入 Web 框架；路径模式只支持 {@code {name}} 形式的占位，
 * 匹配时按段比较，不依赖正则回溯。
 */
final class Routes {

    /** 一条路由：HTTP 方法 + 路径模式 + 处理器。 */
    static final class Route {
        private final String method;
        private final String[] pattern;
        private final Handler handler;

        private Route(String method, String path, Handler handler) {
            this.method = method;
            this.pattern = split(path);
            this.handler = handler;
        }

        /** 路径是否匹配；匹配时把占位段的实际值填进 params。 */
        private boolean matches(String requestMethod, String[] segments, java.util.Map<String, String> params) {
            if (!matchesMethod(requestMethod)) {
                return false;
            }
            if (pattern.length != segments.length) {
                return false;
            }
            params.clear();
            for (int index = 0; index < pattern.length; index++) {
                String expected = pattern[index];
                if (expected.length() > 2 && expected.startsWith("{") && expected.endsWith("}")) {
                    params.put(expected.substring(1, expected.length() - 1), segments[index]);
                } else if (!expected.equals(segments[index])) {
                    return false;
                }
            }
            return true;
        }

        private boolean matchesMethod(String requestMethod) {
            if ("*".equals(method)) {
                return true;
            }
            // HEAD 按 GET 处理，浏览器和探针都可能用。
            if ("GET".equals(method) && "HEAD".equalsIgnoreCase(requestMethod)) {
                return true;
            }
            return method.equalsIgnoreCase(requestMethod);
        }

        /** 执行处理器并返回响应。 */
        Reply handle(Request request) throws Exception {
            return handler.handle(request);
        }
    }

    /** 处理器：拿到路径参数和查询参数，返回响应。 */
    interface Handler {
        Reply handle(Request request) throws Exception;
    }

    /** 一次请求的上下文。 */
    static final class Request {
        private final java.util.Map<String, String> params;
        private final java.util.Map<String, String> query;

        private Request(java.util.Map<String, String> params, java.util.Map<String, String> query) {
            this.params = params;
            this.query = query;
        }

        String path(String name) {
            return params.get(name);
        }

        /** 取查询参数；缺省时返回 fallback。 */
        String query(String name, String fallback) {
            String value = query.get(name);
            return StrUtil.isBlank(value) ? fallback : value;
        }

        int queryInt(String name, int fallback, int min, int max) {
            try {
                String value = query.get(name);
                if (StrUtil.isBlank(value)) {
                    return fallback;
                }
                return Math.min(max, Math.max(min, Integer.parseInt(value.trim())));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        boolean queryBool(String name) {
            String value = query.get(name);
            return value != null && ("1".equals(value) || "true".equalsIgnoreCase(value.trim()));
        }
    }

    /** 响应体：JSON 或静态资源。 */
    static final class Reply {
        private final int status;
        private final String contentType;
        private final byte[] body;

        private Reply(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        static Reply json(cn.hutool.json.JSONObject json) {
            return json(200, json);
        }

        static Reply json(int status, cn.hutool.json.JSONObject json) {
            return new Reply(status, "application/json;charset=utf-8",
                    json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        static Reply text(int status, String contentType, String text) {
            return new Reply(status, contentType, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        static Reply bytes(String contentType, byte[] body) {
            return new Reply(200, contentType, body);
        }

        static Reply error(int status, String message) {
            return json(status, new cn.hutool.json.JSONObject().set("error", message));
        }

        int status() {
            return status;
        }

        String contentType() {
            return contentType;
        }

        byte[] body() {
            return body;
        }

        /** 按自身的状态码写回响应。 */
        void writeTo(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
            writeTo(exchange, status);
        }

        /** 用指定状态码写回，便于把静态资源挂在别的路径下仍返回 200。 */
        void writeTo(com.sun.net.httpserver.HttpExchange exchange, int overrideStatus) throws java.io.IOException {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().add("X-Frame-Options", "DENY");
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(overrideStatus, -1);
                return;
            }
            exchange.sendResponseHeaders(overrideStatus, body.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    private final List<Route> routes = new ArrayList<Route>();

    /** 注册一条路由。 */
    Routes get(String path, Handler handler) {
        routes.add(new Route("GET", path, handler));
        return this;
    }

    Routes post(String path, Handler handler) {
        routes.add(new Route("POST", path, handler));
        return this;
    }

    /** 匹配请求；找不到返回 null，由调用方决定 404 还是退回静态资源。 */
    Route find(String method, String path, java.util.Map<String, String> params,
               java.util.Map<String, String> query) {
        String[] segments = split(path);
        for (Route route : routes) {
            if (route.matches(method, segments, params)) {
                return route;
            }
        }
        return null;
    }

    static Request request(java.util.Map<String, String> params, java.util.Map<String, String> query) {
        return new Request(params, query);
    }

    /** 按 / 切段并去掉首尾空段，{@code /api/runs/} 和 {@code /api/runs} 等价。 */
    static String[] split(String path) {
        List<String> parts = new ArrayList<String>();
        for (String part : StrUtil.nullToEmpty(path).split("/")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts.toArray(new String[0]);
    }

    /** 解析 {@code a=1&b=2} 形式的查询串，值按 URL 解码。 */
    static java.util.Map<String, String> parseQuery(String raw) {
        java.util.Map<String, String> query = new java.util.LinkedHashMap<String, String>();
        if (StrUtil.isBlank(raw)) {
            return query;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int index = pair.indexOf('=');
            String key = index < 0 ? pair : pair.substring(0, index);
            String value = index < 0 ? "" : pair.substring(index + 1);
            query.put(decode(key), decode(value));
        }
        return query;
    }

    private static String decode(String text) {
        try {
            return java.net.URLDecoder.decode(text, java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return text;
        }
    }

    /** 路径段解码，用于实例名和文件名参数。 */
    static String decodeSegment(String text) {
        return decode(text);
    }
}
