package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jayjay.autosignin.entity.MessageLine;
import org.jayjay.autosignin.entity.NotificationSection;

import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class TiDbCheckInTask extends CheckInTask {

    static final String LOGIN_MODE_PASSWORD = "password";
    static final String LOGIN_MODE_COOKIE = "cookie";

//    String loginUrl = "https://accounts.pingcap.cn/api/login/password";
    String loginUrl = "https://pingkai.cn/accounts/api/login/password";
    String checkInUrl = "https://pingkai.cn/accounts/api/points/daily-checkin";
    String userUrl = "https://pingkai.cn/accounts/api/me";
    String pointsUrl = "https://pingkai.cn/accounts/api/points/me";
    String redirectTo = "https://pingkai.cn/tidbcommunity/member";
    private Map<String, String> environment = System.getenv();

    public TiDbCheckInTask() {
    }

    TiDbCheckInTask(Map<String, String> environment) {
        this.environment = environment;
    }

    @Override
    public NotificationSection buildNotificationSection() {
        return new NotificationSection("TiDB 签到", messageBlocks);
    }

    @Override
    public CheckInTask run() {
        try {
            System.out.println("[TiDB] 签到任务开始");
            String mode = loginMode(environment);
            if (mode == null) {
                System.err.println("[TiDB] TIDB_LOGIN_MODE 仅支持 password 或 cookie");
                addMessage("TiDB 签到失败，请查看日志");
                return this;
            }
            if (LOGIN_MODE_COOKIE.equals(mode)) {
                runWithCookie();
            } else {
                runWithPassword();
            }
        } catch (Exception e) {
            e.printStackTrace();
            addMessage("TiDB 签到失败，请查看日志");
        }
        return this;
    }

    private void runWithPassword() {
        String tidbUsername = environment.get("TIDB_USERNAME");
        String tidbPassword = environment.get("TIDB_PASSWORD");
        if (StrUtil.isBlank(tidbUsername) || StrUtil.isBlank(tidbPassword)) {
            System.out.println("[TiDB] 用户名或密码未配置，跳过签到");
            enabled = false;
            return;
        }
        System.out.println("[TiDB] 开始登录");
        JSONObject bodyJson = JSONUtil.createObj();
        bodyJson.set("identifier", tidbUsername);
        bodyJson.set("password", tidbPassword);
        bodyJson.set("redirect_to", redirectTo);
        HttpResponse loginRes = HttpRequest.post(loginUrl).headerMap(headers(), true)
                .disableCookie()
                .body(bodyJson.toString())
                .execute();
        if (!loginRes.isOk()) {
            System.err.println("[TiDB] 登录失败，HTTP " + loginRes.getStatus());
            addMessage("TiDB 签到失败，请查看日志");
            return;
        }

        JSONObject loginBody = toJSON(loginRes.body());
        System.out.println("[TiDB] 登录结果：" + (loginBody == null ? "" : loginBody.getStr("detail")));
        completeCheckIn(loginRes.getCookies(), loginRes.getCookieValue("csrftoken"));
    }

    private void runWithCookie() {
        String cookieHeader = environment.get("TIDB_COOKIE");
        if (StrUtil.isBlank(cookieHeader)) {
            System.out.println("[TiDB] Cookie 未配置，跳过签到");
            enabled = false;
            return;
        }
        List<HttpCookie> cookies = parseCookies(cookieHeader);
        String csrfToken = cookieValue(cookies, "csrftoken");
        if (StrUtil.isBlank(csrfToken)) {
            System.err.println("[TiDB] Cookie 缺少 csrftoken");
            addMessage("TiDB 签到失败，请查看日志");
            return;
        }
        System.out.println("[TiDB] 使用 Cookie 继续签到");
        completeCheckIn(cookies, csrfToken);
    }

    private void completeCheckIn(List<HttpCookie> cookies, String csrfToken) {
        Map<String, String> checkInHeaders = checkInHeaders();
        checkInHeaders.put("x-csrftoken", csrfToken);
        HttpResponse meRes = HttpRequest.get(userUrl).disableCookie().cookie(cookies).headerMap(checkInHeaders(), true)
                .header("x-csrftoken", csrfToken).execute();
        if (meRes.isOk()) {
            JSONObject userBody = toJSON(meRes.body());
            JSONObject data = userBody == null ? null : userBody.getJSONObject("data");
            if (data != null) {
                addMessage("用户名：", data.getStr("username"));
            }
        }
        HttpResponse pointRes = HttpRequest.get(pointsUrl).disableCookie().cookie(cookies).headerMap(checkInHeaders(), true).execute();
        if (pointRes.isOk()) {
            JSONObject pointBody = toJSON(pointRes.body());
            JSONObject data = pointBody == null ? null : pointBody.getJSONObject("data");
            if (data != null) {
                addMessage("当前积分：", data.getStr("current_points"));
            }
        }
        System.out.println("[TiDB] 开始签到");
        HttpResponse checkInRes = HttpRequest.post(checkInUrl)
                .disableCookie()
                .cookie(cookies)
                .headerMap(checkInHeaders, true)
                .execute();
        JSONObject checkInBody = toJSON(checkInRes.body());
        String detail = checkInBody == null ? "" : checkInBody.getStr("detail");
        System.out.println("[TiDB] 签到结果：" + detail);
        addMessage("签到结果：", detail);
        JSONObject data = checkInBody == null ? null : checkInBody.getJSONObject("data");
        if (data != null && data.containsKey("points")) {
            MessageLine points = new MessageLine()
                    .append("本次获得 ", data.getStr("points"), " 积分");
            if (data.containsKey("tomorrow_points")) {
                points.append("，明日签到可获得 ", data.getStr("tomorrow_points"), " 积分");
            }
            addMessage(points);
        }
        System.out.println("[TiDB] 签到任务结束");
    }

    static String loginMode(Map<String, String> environment) {
        if (environment == null) {
            return LOGIN_MODE_PASSWORD;
        }
        String mode = environment.get("TIDB_LOGIN_MODE");
        if (StrUtil.isBlank(mode)) {
            return LOGIN_MODE_PASSWORD;
        }
        String normalized = mode.trim().toLowerCase();
        if (LOGIN_MODE_PASSWORD.equals(normalized) || LOGIN_MODE_COOKIE.equals(normalized)) {
            return normalized;
        }
        return null;
    }

    static List<HttpCookie> parseCookies(String cookieHeader) {
        List<HttpCookie> cookies = new ArrayList<>();
        if (StrUtil.isBlank(cookieHeader)) {
            return cookies;
        }
        for (String part : cookieHeader.split(";")) {
            String item = part.trim();
            int separator = item.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = item.substring(0, separator).trim();
            String value = item.substring(separator + 1).trim();
            if (StrUtil.isNotBlank(name)) {
                HttpCookie cookie = new HttpCookie(name, value);
                // 版本 0 按浏览器 Cookie 头原样发送，避免给值加上引号。
                cookie.setVersion(0);
                cookies.add(cookie);
            }
        }
        return cookies;
    }

    private static String cookieValue(List<HttpCookie> cookies, String name) {
        for (HttpCookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public Map<String, String> headers() {
        Map<String, String> headers = commonHeaders();
        headers.put("Origin", "https://pingkai.cn");
        return headers;
    }

    public Map<String, String> checkInHeaders() {
        Map<String, String> headers = commonHeaders();
        headers.put("Origin", "https://pingkai.cn");
        headers.put("Referer", "https://pingkai.cn/tidbcommunity/member");
        return headers;
    }

}
