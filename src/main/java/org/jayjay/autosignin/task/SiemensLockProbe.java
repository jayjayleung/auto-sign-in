package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 西门子账号锁定探测：每天只读访问一次兑换页，记录站点是否仍在拦这个账号。
 * <p>
 * 站点把我们三个账号按「作弊」锁定了兑换、提问、回答、发帖这些动作，但锁定提示里
 * 不写期限，只能靠反复试才知道什么时候恢复。手工试既费事又容易误判，所以由容器内的
 * 定时任务每天探一次：
 * <ul>
 *   <li>只发一个 GET，不提交、不签到、不改任何数据；</li>
 *   <li>用 state 里已保存的会话 Cookie，不重新登录，不触发密码接口风控；</li>
 *   <li>结果写进 {@code siemens-lock-<账号哈希>.json}，看板直接读这份文件。</li>
 * </ul>
 * 文件里只记账号哈希，不写账号明文；也不记录任何响应正文，避免把页面内容带进状态目录。
 * <p>
 * 记录里最有用的两个时间点是 {@code firstSeenLocked} 和 {@code unlockedAt}：
 * 两者的差就是站点这次实际给的封锁时长，可以反推出他们的处置尺度。
 */
public final class SiemensLockProbe {

    /** 兑换页：账号被锁时直接返回「您的账号被锁定，不能使用该功能」。 */
    static final String PROBE_URL = "https://www.ad.siemens.com.cn/MySupport/MyPrize.aspx";
    /** 状态文件名前缀，后面接账号哈希，和会话文件保持同一套命名。看板按这个前缀找最新一份。 */
    public static final String FILE_PREFIX = "siemens-lock-";

    /** 站点锁定提示里的关键片段，命中任意一个都算锁定。 */
    static final String LOCK_MARK = "账号被锁定";
    static final String LOCK_MARK_ALT = "锁定用户";
    /**
     * 站点错误页的标志。错误页本身有 4 万多字节，只看长度会被当成正常页面，
     * 从而把「页面挂了」误报成「已解锁」，那会让人立刻重开每日任务。
     */
    static final String ERROR_MARK = "您所访问的网页不存在";
    static final String ERROR_MARK_ALT = "网页错误描述";
    /** 正常兑换页至少有这么长；锁定提示只有一百多字节，用来区分「解锁」和「看不懂」。 */
    static final int PAGE_MIN_LENGTH = 2000;

    /** 检查记录最多保留多少条，一天一条够看两个月。 */
    static final int MAX_HISTORY = 60;

    static final String LOCKED = "locked";
    static final String UNLOCKED = "unlocked";
    static final String SESSION_EXPIRED = "session_expired";
    static final String NO_SESSION = "no_session";
    static final String UNKNOWN = "unknown";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int TIMEOUT_MILLIS = 20000;

    private SiemensLockProbe() {
    }

    public static void main(String[] args) {
        String username = System.getenv(SiemensCheckInTask.USERNAME_KEY);
        if (StrUtil.isBlank(username)) {
            System.out.println("[锁定探测] 未配置西门子账号，跳过");
            return;
        }
        Path stateDir = CheckInTask.stateDir();
        String key = CheckInTask.accountKey(username);
        Path file = stateDir.resolve(FILE_PREFIX + key + ".json");
        String checkedAt = OffsetDateTime.now(SHANGHAI).withNano(0).toString();
        Path sessionFile = stateDir.resolve("siemens-session-" + key + ".txt");
        String cookie = cookieHeader(sessionFile);
        if (StrUtil.isBlank(cookie)) {
            // 没有会话就没法判断，记一条 no_session 让看板提示去跑一次签到。
            System.out.println("[锁定探测] 没有可用的会话文件，无法判断，请先跑一次签到");
            write(file, merge(read(file), NO_SESSION, checkedAt, ""));
            return;
        }
        Response response = request(cookie);
        String status = classify(response.status, response.body);
        System.out.println("[锁定探测] " + status
                + "（HTTP " + response.status + "，" + response.body.length() + " 字节）");
        write(file, merge(read(file), status, checkedAt, response.detail));
    }

    /** 发一次只读请求；跟随重定向关掉，302 本身就是「会话失效」的判断依据。 */
    private static Response request(String cookie) {
        try {
            HttpResponse response = HttpRequest.get(PROBE_URL)
                    // cookie("") 关掉 Hutool 的全局 Cookie，避免带上别的站点会话
                    .cookie(cookie)
                    .header("User-Agent", CheckInTask.userAgent)
                    .setFollowRedirects(false)
                    .timeout(TIMEOUT_MILLIS)
                    .execute();
            return new Response(response.getStatus(), StrUtil.nullToEmpty(response.body()),
                    StrUtil.nullToEmpty(response.header("Location")));
        } catch (Exception e) {
            return new Response(0, "", "请求失败：" + StrUtil.maxLength(String.valueOf(e.getMessage()), 80));
        }
    }

    /**
     * 把一次响应归类。
     * <p>
     * 先看锁定提示，再看错误页，最后才看长度：没登录时兑换页会 302 到 Login.aspx，
     * 那种情况既不能算锁定也不能算解锁，只能标成会话失效等人处理。站点错误页虽然很长，
     * 但那是「页面挂了」而不是「账号能用了」，同样不能算解锁。
     */
    static String classify(int status, String body) {
        String text = body == null ? "" : body;
        if (text.contains(LOCK_MARK) || text.contains(LOCK_MARK_ALT)) {
            return LOCKED;
        }
        if (status >= 300 && status < 400) {
            return SESSION_EXPIRED;
        }
        if (text.contains(ERROR_MARK) || text.contains(ERROR_MARK_ALT)) {
            return UNKNOWN;
        }
        if (status == 200 && text.length() >= PAGE_MIN_LENGTH) {
            return UNLOCKED;
        }
        return UNKNOWN;
    }

    /**
     * 合并本次结果到已有记录里。
     * <p>
     * {@code firstSeenLocked} 只在「新一轮锁定」时重置：之前是解锁状态又变回锁定，
     * 或者本来就没有基准。会话失效这类中间态一律不改基准，否则会把封锁时长算短。
     */
    static JSONObject merge(JSONObject previous, String status, String checkedAt, String detail) {
        JSONObject json = new JSONObject();
        if (previous != null) {
            json.putAll(previous);
        }
        String previousStatus = previous == null ? "" : StrUtil.trimToEmpty(previous.getStr("status"));
        json.set("checkedAt", checkedAt);
        json.set("status", status);
        json.set("detail", StrUtil.maxLength(StrUtil.nullToEmpty(detail), 120));
        if (LOCKED.equals(status)) {
            if (UNLOCKED.equals(previousStatus) || StrUtil.isBlank(json.getStr("firstSeenLocked"))) {
                json.set("firstSeenLocked", checkedAt);
                json.set("unlockedAt", "");
            }
            json.set("lastLockedAt", checkedAt);
        } else if (UNLOCKED.equals(status)) {
            if (StrUtil.isBlank(json.getStr("unlockedAt"))) {
                json.set("unlockedAt", checkedAt);
            }
        }
        JSONArray history = new JSONArray();
        JSONArray saved = previous == null ? null : previous.getJSONArray("history");
        if (saved != null) {
            for (int index = 0; index < saved.size(); index++) {
                history.add(saved.get(index));
            }
        }
        history.add(new JSONObject().set("at", checkedAt).set("status", status));
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        json.set("history", history);
        return json;
    }

    /**
     * 从会话文件拼出 Cookie 请求头。
     * <p>
     * 格式是任务侧写的制表符分隔行：名称、值、域、路径、是否仅主机、是否 secure。
     * 这里只挑能发给兑换页那个主机的，别的域（比如 oneid）带上去会被站点当成异常请求。
     */
    static String cookieHeader(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
        String host = "www.ad.siemens.com.cn";
        StringBuilder header = new StringBuilder();
        for (String line : lines) {
            if (StrUtil.isBlank(line)) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length < 4 || StrUtil.isBlank(parts[0])) {
                continue;
            }
            boolean hostOnly = parts.length > 4 && "1".equals(parts[4]);
            if (!matches(parts[2], hostOnly, host)) {
                continue;
            }
            if (header.length() > 0) {
                header.append("; ");
            }
            header.append(parts[0]).append('=').append(parts[1]);
        }
        return header.toString();
    }

    private static boolean matches(String domain, boolean hostOnly, String host) {
        String value = StrUtil.trimToEmpty(domain).toLowerCase();
        if (StrUtil.isBlank(value)) {
            return false;
        }
        if (hostOnly) {
            return host.equals(value);
        }
        return host.equals(value) || host.endsWith("." + value);
    }

    private static JSONObject read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JSONUtil.parseObj(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 文件损坏时按「没有基准」处理，下一次探测重新建立。
            return null;
        }
    }

    private static void write(Path file, JSONObject json) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, json.toStringPretty().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("[锁定探测] 记录写不进去：" + e.getMessage());
        }
    }

    /**
     * 首次探测到锁定到解锁（未解锁时算到今天）间隔的天数，没有基准时返回 null。
     * 看板也要用同一套算法，避免两边各算一遍得出不同结论。
     */
    public static Long lockedDays(String firstSeenLocked, String unlockedAt) {
        if (StrUtil.isBlank(firstSeenLocked)) {
            return null;
        }
        try {
            LocalDate start = LocalDate.parse(StrUtil.subPre(firstSeenLocked.trim(), 10));
            LocalDate end = StrUtil.isBlank(unlockedAt)
                    ? LocalDate.now(SHANGHAI)
                    : LocalDate.parse(StrUtil.subPre(unlockedAt.trim(), 10));
            return end.toEpochDay() - start.toEpochDay();
        } catch (Exception e) {
            return null;
        }
    }

    /** 一次探测的原始响应，只在内存里传递，不落盘。 */
    static final class Response {
        final int status;
        final String body;
        final String detail;

        Response(int status, String body, String detail) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.detail = detail == null ? "" : detail;
        }
    }
}
