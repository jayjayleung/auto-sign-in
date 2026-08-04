package org.jayjay.autosignin.task;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.jayjay.autosignin.entity.MessageList;

import java.net.HttpCookie;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YunqiaoCheckInTask extends CheckInTask {

    // 通过 quya.org 域名提供登录和积分接口。
    private static final String DEFAULT_API_BASE_URL = "https://api.quya.org";
    private static final String DEFAULT_POINTS_BASE_URL = "https://www.quya.org";
    private static final String CUSTOM_PAGE_URL = DEFAULT_API_BASE_URL + "/custom/93bbf0afef76f203";
    // 账号配置按编号成对读取，例如 YUNQIAO_USERNAME_1 和 YUNQIAO_PASSWORD_1。
    private static final Pattern USERNAME_KEY = Pattern.compile("^YUNQIAO_USERNAME_(\\d+)$");
    private static final Pattern PASSWORD_KEY = Pattern.compile("^YUNQIAO_PASSWORD_(\\d+)$");
    private static final int REQUEST_TIMEOUT = 30000;

    private final Map<String, String> environment;
    private final String loginUrl;
    private final String pointsPageUrl;
    private final String bootstrapUrl;
    private final String checkInUrl;

    public YunqiaoCheckInTask() {
        this(System.getenv(), DEFAULT_API_BASE_URL, DEFAULT_POINTS_BASE_URL);
    }

    YunqiaoCheckInTask(Map<String, String> environment, String apiBaseUrl, String pointsBaseUrl) {
        this.environment = environment;
        String normalizedApiBaseUrl = trimTrailingSlash(apiBaseUrl);
        String normalizedPointsBaseUrl = trimTrailingSlash(pointsBaseUrl);
        this.loginUrl = normalizedApiBaseUrl + "/api/v1/auth/login";
        this.pointsPageUrl = normalizedPointsBaseUrl + "/points";
        this.bootstrapUrl = normalizedPointsBaseUrl + "/api/user/points/bootstrap";
        this.checkInUrl = normalizedPointsBaseUrl + "/api/user/points/checkin";
    }

    @Override
    public MessageList messageList() {
        return new MessageList("云桥积分签到", listMessage);
    }

    @Override
    public CheckInTask run() {
        List<Account> accounts = loadAccounts(environment);
        if (CollUtil.isEmpty(accounts)) {
            System.out.println("云桥账号未配置，跳过签到");
            isRun = false;
            return this;
        }

        System.out.println("云桥积分签到任务开始，账号数：" + accounts.size());
        for (Account account : accounts) {
            if (!account.isComplete()) {
                addMessage(accountLabel(account), "：用户名或密码配置不完整，已跳过");
                continue;
            }
            try {
                checkIn(account);
            } catch (Exception e) {
                String error = safeMessage(e.getMessage());
                System.err.println("云桥账号 " + account.index + " 签到失败：" + error);
                addMessage(accountLabel(account), "：签到失败 - ", error);
            }
        }
        System.out.println("云桥积分签到任务结束");
        return this;
    }

    private void checkIn(Account account) throws Exception {
        // 登录令牌需先换取积分站点会话 Cookie，后续状态与签到请求共用该会话。
        LoginSession loginSession = login(account);
        List<HttpCookie> cookies = createPointsSession(loginSession);
        PointsStatus status = loadStatus(cookies);

        if (status.checkedInToday) {
            addMessage(statusMessage(account, "今日已签到", status, 0));
            return;
        }
        if (!status.enabled) {
            addMessage(accountLabel(account), "：积分功能未开启");
            return;
        }

        int streakBonus;
        try {
            JSONObject checkInData = requestData(HttpRequest.post(checkInUrl)
                    .headerMap(pointsApiHeaders(), true)
                    .cookie(cookies)
                    .body("{}")
                    .timeout(REQUEST_TIMEOUT)
                    .execute(), "签到");
            streakBonus = numberValue(checkInData, "streak_bonus");
        } catch (IllegalStateException e) {
            if (safeMessage(e.getMessage()).contains("已经签到")) {
                PointsStatus currentStatus = loadStatus(cookies);
                addMessage(statusMessage(account, "今日已签到", currentStatus, 0));
                return;
            }
            throw e;
        }

        PointsStatus updatedStatus = loadStatus(cookies);
        addMessage(statusMessage(account, "签到成功", updatedStatus, streakBonus));
    }

    private LoginSession login(Account account) {
        JSONObject loginBody = JSONUtil.createObj()
                .set("email", account.username)
                .set("password", account.password);
        JSONObject data = requestData(HttpRequest.post(loginUrl)
                .headerMap(loginHeaders(), true)
                .disableCookie()
                .body(loginBody.toString())
                .timeout(REQUEST_TIMEOUT)
                .execute(), "登录");

        if (StrUtil.isNotBlank(data.getStr("temp_token"))) {
            throw new IllegalStateException("登录需要二次验证码，暂不支持自动签到");
        }
        JSONObject user = data.getJSONObject("user");
        String accessToken = data.getStr("access_token");
        String userId = user == null ? null : String.valueOf(user.get("id"));
        if (StrUtil.isBlank(accessToken) || StrUtil.isBlank(userId) || "null".equals(userId)) {
            throw new IllegalStateException("登录响应缺少用户 ID 或访问令牌");
        }
        return new LoginSession(userId, accessToken);
    }

    private List<HttpCookie> createPointsSession(LoginSession loginSession) throws Exception {
        String sessionUrl = pointsPageUrl
                + "?user_id=" + URLEncoder.encode(loginSession.userId, "UTF-8")
                + "&token=" + URLEncoder.encode(loginSession.accessToken, "UTF-8");
        HttpResponse response = HttpRequest.get(sessionUrl)
                .headerMap(pointsPageHeaders(), true)
                .disableCookie()
                .timeout(REQUEST_TIMEOUT)
                .execute();
        if (response.getStatus() < 200 || response.getStatus() >= 400) {
            throw new IllegalStateException("建立积分会话失败，HTTP " + response.getStatus());
        }
        List<HttpCookie> cookies = responseCookies(response);
        if (CollUtil.isEmpty(cookies)) {
            throw new IllegalStateException("建立积分会话失败，未获取会话 Cookie");
        }
        return cookies;
    }

    /**
     * 只读取本次响应下发的 Cookie，避免复用 Hutool 全局 Cookie 存储中的其他账号会话。
     */
    private static List<HttpCookie> responseCookies(HttpResponse response) {
        List<HttpCookie> cookies = new ArrayList<>();
        for (Map.Entry<String, List<String>> header : response.headers().entrySet()) {
            if (header.getKey() == null || !"Set-Cookie".equalsIgnoreCase(header.getKey())
                    || header.getValue() == null) {
                continue;
            }
            for (String value : header.getValue()) {
                if (StrUtil.isNotBlank(value)) {
                    cookies.addAll(HttpCookie.parse(value));
                }
            }
        }
        return cookies;
    }

    private PointsStatus loadStatus(List<HttpCookie> cookies) {
        JSONObject data = requestData(HttpRequest.get(bootstrapUrl)
                .headerMap(pointsApiHeaders(), true)
                .cookie(cookies)
                .timeout(REQUEST_TIMEOUT)
                .execute(), "获取积分状态");
        JSONObject account = data.getJSONObject("account");
        return new PointsStatus(
                Boolean.TRUE.equals(data.getBool("enabled")),
                Boolean.TRUE.equals(data.getBool("checked_in_today")),
                numberValue(data, "current_streak"),
                numberValue(account, "points_balance")
        );
    }

    private JSONObject requestData(HttpResponse response, String action) {
        JSONObject payload = toJSON(response.body());
        if (payload == null) {
            throw new IllegalStateException(action + "失败：服务返回了无效数据");
        }
        Integer code = payload.getInt("code");
        if (!response.isOk() || code == null || code != 0) {
            throw new IllegalStateException(action + "失败：" + safeMessage(payload.getStr("message")));
        }
        JSONObject data = payload.getJSONObject("data");
        return data == null ? JSONUtil.createObj() : data;
    }

    private Map<String, String> loginHeaders() {
        Map<String, String> headers = commonHeaders();
        headers.put("Origin", DEFAULT_API_BASE_URL);
        headers.put("Referer", DEFAULT_API_BASE_URL + "/login");
        return headers;
    }

    private Map<String, String> pointsPageHeaders() {
        Map<String, String> headers = commonHeaders();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        headers.put("Referer", CUSTOM_PAGE_URL);
        return headers;
    }

    private Map<String, String> pointsApiHeaders() {
        Map<String, String> headers = commonHeaders();
        headers.put("Origin", DEFAULT_POINTS_BASE_URL);
        headers.put("Referer", pointsPageUrl);
        return headers;
    }

    static List<Account> loadAccounts(Map<String, String> environment) {
        TreeMap<Integer, AccountValues> valuesByIndex = new TreeMap<>();
        // TreeMap 保证多账号始终按编号执行，不受环境变量遍历顺序影响。
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            Matcher usernameMatcher = USERNAME_KEY.matcher(entry.getKey());
            Matcher passwordMatcher = PASSWORD_KEY.matcher(entry.getKey());
            if (usernameMatcher.matches()) {
                int index = Integer.parseInt(usernameMatcher.group(1));
                valuesByIndex.computeIfAbsent(index, ignored -> new AccountValues()).username = entry.getValue();
            } else if (passwordMatcher.matches()) {
                int index = Integer.parseInt(passwordMatcher.group(1));
                valuesByIndex.computeIfAbsent(index, ignored -> new AccountValues()).password = entry.getValue();
            }
        }

        List<Account> accounts = new ArrayList<>();
        for (Map.Entry<Integer, AccountValues> entry : valuesByIndex.entrySet()) {
            AccountValues values = entry.getValue();
            accounts.add(new Account(entry.getKey(), values.username, values.password));
        }
        return accounts;
    }

    private static StringBuilder statusMessage(Account account, String result, PointsStatus status, int streakBonus) {
        StringBuilder message = new StringBuilder(accountLabel(account))
                .append("：")
                .append(result)
                .append("，当前积分 ")
                .append(status.pointsBalance)
                .append("，连续签到 ")
                .append(status.currentStreak)
                .append(" 天");
        if (streakBonus > 0) {
            message.append("，连续签到奖励 ").append(streakBonus).append(" 积分");
        }
        return message;
    }

    private static String accountLabel(Account account) {
        String label = "账号 " + account.index;
        if (StrUtil.isBlank(account.username)) {
            return label;
        }
        String username = account.username.replaceAll("[\\r\\n<>]+", "");
        if (username.length() > 100) {
            username = username.substring(0, 100) + "...";
        }
        return label + "（" + username + "）";
    }

    private static int numberValue(JSONObject object, String key) {
        if (object == null) {
            return 0;
        }
        Object value = object.get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String safeMessage(String message) {
        String value = StrUtil.blankToDefault(message, "未知错误").replaceAll("[\\r\\n]+", " ");
        return value.length() > 160 ? value.substring(0, 160) + "..." : value;
    }

    static class Account {
        final int index;
        final String username;
        final String password;

        Account(int index, String username, String password) {
            this.index = index;
            this.username = username;
            this.password = password;
        }

        boolean isComplete() {
            return StrUtil.isNotBlank(username) && StrUtil.isNotBlank(password);
        }
    }

    private static class AccountValues {
        private String username;
        private String password;
    }

    private static class LoginSession {
        private final String userId;
        private final String accessToken;

        private LoginSession(String userId, String accessToken) {
            this.userId = userId;
            this.accessToken = accessToken;
        }
    }

    private static class PointsStatus {
        private final boolean enabled;
        private final boolean checkedInToday;
        private final int currentStreak;
        private final int pointsBalance;

        private PointsStatus(boolean enabled, boolean checkedInToday, int currentStreak, int pointsBalance) {
            this.enabled = enabled;
            this.checkedInToday = checkedInToday;
            this.currentStreak = currentStreak;
            this.pointsBalance = pointsBalance;
        }
    }
}
