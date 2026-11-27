package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.MessageLine;
import org.jayjay.autosignin.entity.NotificationSection;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
public class MoDbCheckInTask extends CheckInTask {

    static final String USERNAME_KEY = "MODB_USERNAME";
    static final String PASSWORD_KEY = "MODB_PASSWORD";
    /** 状态目录覆盖，测试指向临时目录，避免污染真实容器里的凭证文件。 */
    static final String STATE_DIR_KEY = "MODB_STATE_DIR";

    String loginUrl = "https://www.modb.pro/api/login";
    //废弃
//    String checkInUrl = "https://www.modb.pro/api/user/checkIn";
    //新签到接口
    String checkInUrl = "https://www.modb.pro/api/user/dailyCheck";
    String clockUrl = "https://www.modb.pro/api/env/clock";
    String userUrl = "https://www.modb.pro/api/user/detail";
    // 硬编码密钥（与原JS一致）
    private static final String AES_KEY = "emcs-app-request";
    private static final String AES_IV = "xqgb1vda11s0e94g"; // 可能需要根据实际加密模式调整

    /** 登录偶发 Connection refused，按次数重试，避免整站点当天直接失败。 */
    int loginMaxAttempts = 3;
    long loginRetryDelayMs = 3000L;

    private Map<String, String> environment = System.getenv();
    /** 当前请求使用的凭证。登录或复用成功后写入，签到请求从这里取。 */
    private String currentToken = "";

    public MoDbCheckInTask() {
    }

    MoDbCheckInTask(Map<String, String> environment) {
        if (environment != null) {
            this.environment = environment;
        }
    }

    @Override
    public NotificationSection buildNotificationSection() {
        return new NotificationSection("墨天轮签到", messageBlocks);
    }

    @Override
    public CheckInTask run() {
        try {
            System.out.println("[墨天轮] 签到任务开始");
            String modbUsername = environment.get(USERNAME_KEY);
            String modbPassword = environment.get(PASSWORD_KEY);
            if (StrUtil.isBlank(modbUsername) || StrUtil.isBlank(modbPassword)) {
                System.out.println("[墨天轮] 用户名或密码未配置，跳过签到");
                enabled = false;
                return this;
            }
            modbUsername = modbUsername.trim();

            // 先用保存的凭证换用户信息：这一步既是探活也是后续要展示的数据源，不额外多发请求。
            JSONObject profile = openSession(modbUsername, modbPassword);
            if (profile == null) {
                addMessage("墨天轮签到失败，请查看日志");
                return this;
            }
            fillProfileMessages(profile);

            if (Boolean.TRUE.equals(profile.getBool("signed"))) {
                System.out.println("[墨天轮] 今日已签到");
                addMessage("签到结果：", "今日已签到");
                System.out.println("[墨天轮] 签到任务结束");
                return this;
            }
            signIn(profile);
            System.out.println("[墨天轮] 签到任务结束");
        } catch (Exception e) {
            e.printStackTrace();
            addMessage("墨天轮签到失败，请查看日志");
        }
        return this;
    }

    /**
     * 取得可用的登录凭证并读回用户信息。
     * <p>
     * 顺序是「先复用、再登录」：保存的 JWT 有 7 天有效期，本地能解出未过期就直接拿去请求；
     * 过期或被服务端拒绝时才重新走密码登录，并把新凭证覆盖到同一个文件。
     * 登录是唯一会把密码送到站点的动作，能省一次是一次。
     *
     * @return 用户信息；登录失败返回 null
     */
    private JSONObject openSession(String username, String password) {
        String saved = loadToken(username);
        if (StrUtil.isNotBlank(saved)) {
            if (tokenExpired(saved)) {
                System.out.println("[墨天轮] 保存的登录凭证已过期，重新登录");
            } else {
                JSONObject profile = readProfile(saved);
                if (profile != null) {
                    System.out.println("[墨天轮] 复用上次的登录凭证");
                    return profile;
                }
                System.out.println("[墨天轮] 保存的登录凭证已失效，重新登录");
                clearSession(username);
            }
        }

        System.out.println("[墨天轮] 开始登录");
        JSONObject bodyJson = JSONUtil.createObj();
        bodyJson.set("phoneNum", username);
        bodyJson.set("password", password);
        HttpResponse loginRes = loginWithRetry(bodyJson);
        if (!loginRes.isOk()) {
            System.err.println("[墨天轮] 登录失败，HTTP " + loginRes.getStatus());
            return null;
        }
        JSONObject loginBody = toJSON(loginRes.body());
        if (loginBody == null || !Boolean.TRUE.equals(loginBody.getBool("success"))) {
            System.err.println("[墨天轮] 登录失败：" + (loginBody == null ? "响应不是 JSON"
                    : loginBody.getStr("operateMessage")));
            return null;
        }
        System.out.println("[墨天轮] 登录结果：" + loginBody.getStr("operateMessage"));

        String token = loginRes.header("Authorization");
        if (StrUtil.isBlank(token)) {
            System.err.println("[墨天轮] 登录响应缺少 Authorization");
            return null;
        }
        saveSession(username, token);
        JSONObject profile = readProfile(token);
        if (profile == null) {
            System.err.println("[墨天轮] 登录后仍读不到用户信息");
        }
        return profile;
    }

    /** 通知里把账号和墨值放在签到结果之前，和其余站点保持一致。 */
    private void fillProfileMessages(JSONObject profile) {
        MessageLine account = new MessageLine();
        account.append("用户名：", StrUtil.nullToEmpty(profile.getStr("account")));
        addMessage(account);

        Integer point = profile.getInt("point");
        Integer todayDelta = profile.getInt("todayDelta");
        MessageLine points = new MessageLine();
        points.append("墨值：", point == null ? "-" : String.valueOf(point));
        // 今天的增量只有大于 0 才有信息量，0 或读不到时不必在括号里写个零。
        if (todayDelta != null && todayDelta > 0) {
            points.append("（今日 +", String.valueOf(todayDelta), "）");
        }
        addMessage(points);
    }

    /** 换加密时间戳后签到。 */
    private void signIn(JSONObject profile) {
        String reqKey = requestKey();
        if (StrUtil.isBlank(reqKey)) {
            addMessage("签到结果：", "获取加密时间戳失败");
            return;
        }
        System.out.println("[墨天轮] 开始签到");
        HttpResponse checkInRes = HttpRequest.post(checkInUrl)
                .disableCookie()
                .headerMap(userHeaders(currentToken), true)
                .body("{\"reqKey\":\"" + reqKey + "\"}")
                .execute();
        String body = checkInRes.body();
        JSONObject checkInBody = toJSON(body);
        if (checkInRes.getStatus() == 401) {
            addMessage(expiredBlock());
            return;
        }
        if (!checkInRes.isOk()) {
            System.err.println("[墨天轮] 签到失败，HTTP " + checkInRes.getStatus());
            addMessage("签到结果：", "签到失败，HTTP " + checkInRes.getStatus());
            return;
        }
        String str = checkInBody == null ? null : checkInBody.getStr("operateMessage");
        if (StrUtil.isBlank(str)) {
            throw new IllegalStateException("签到响应缺少 operateMessage");
        }
        System.out.println("[墨天轮] 签到结果：" + str);
        addMessage("签到结果：", str);
    }

    /**
     * 凭证失效时的提示。走到这里说明登录已经成功过，token 却在签到接口被拒，
     * 属于保存的会话在两次请求之间过期，下次运行会自动重新登录，不需要人工干预。
     */
    private MessageBlock expiredBlock() {
        return new MessageBlock()
                .addLine(new MessageLine().appendBold("签到失败：登录凭证已失效"))
                .addLine("原因：签到接口返回未登录，保存的会话已过期。")
                .addLine("处理：下次运行会重新用账号密码登录，无需手动处理");
    }

    // ==================== 登录凭证 ====================

    /**
     * 读取用户信息，同时用作登录凭证的探活。
     * <p>
     * 未认证时这个接口返回 200 + 空 body，带无效 token 时返回 403，
     * 所以判据必须是「有 userId」，只看状态码会把匿名响应误判成登录成功。
     *
     * @return 用户信息；凭证无效返回 null
     */
    private JSONObject readProfile(String token) {
        if (StrUtil.isBlank(token)) {
            return null;
        }
        HttpResponse res = HttpRequest.get(userUrl)
                .disableCookie()
                .headerMap(userHeaders(token), true)
                .execute();
        if (!res.isOk()) {
            return null;
        }
        JSONObject body = toJSON(res.body());
        Integer userId = body == null ? null : body.getInt("userId");
        if (userId == null || userId <= 0) {
            return null;
        }
        currentToken = token;
        return body;
    }

    private Path sessionFile(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        return resolveStateDir().resolve("modb-session-" + accountKey(username) + ".json");
    }

    /** 状态目录允许用 MODB_STATE_DIR 覆盖，测试靠它指向临时目录，不碰真实凭证。 */
    private Path resolveStateDir() {
        String configured = environment.get(STATE_DIR_KEY);
        if (StrUtil.isNotBlank(configured)) {
            return Paths.get(configured.trim());
        }
        return CheckInTask.stateDir();
    }

    private String loadToken(String username) {
        Path file = sessionFile(username);
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            JSONObject json = JSONUtil.parseObj(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            return StrUtil.blankToDefault(json.getStr("token"), "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 凭证文件是等价于密码的东西，尽量只让当前用户可读。 */
    private void saveSession(String username, String token) {
        Path file = sessionFile(username);
        if (file == null || StrUtil.isBlank(token)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JSONObject json = JSONUtil.createObj()
                    .set("token", stripBearer(token))
                    .set("savedAt", System.currentTimeMillis());
            Files.write(file, json.toString().getBytes(StandardCharsets.UTF_8));
            restrictPermissions(file);
        } catch (Exception e) {
            // 存不下来只影响下次复用，本次流程照常。
            System.out.println("[墨天轮] 登录凭证保存失败：" + StrUtil.maxLength(e.getMessage(), 100));
        }
    }

    private void clearSession(String username) {
        Path file = sessionFile(username);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            // 删不掉就等下次覆盖。
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Set<java.nio.file.attribute.PosixFilePermission> ownerOnly =
                    java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, ownerOnly);
        } catch (Exception e) {
            // 非 POSIX 文件系统（比如 Windows 本地调试）不支持就算了。
        }
    }

    /**
     * 本地判断凭证是否已过期，省掉一次注定失败的请求。
     * 解不出 exp 时按「未过期」处理，交给服务端裁定，避免把格式变化误判成过期。
     */
    static boolean tokenExpired(String token) {
        long expiresAt = tokenExpiresAt(token);
        return expiresAt > 0 && expiresAt * 1000L <= System.currentTimeMillis();
    }

    /** 解 JWT 的 exp（秒）；不是三段式 JWT 或解不出来返回 -1。 */
    static long tokenExpiresAt(String token) {
        String bare = stripBearer(token);
        String[] segments = bare.split("\\.");
        if (segments.length < 2) {
            return -1;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(padBase64(segments[1]));
            Integer exp = JSONUtil.parseObj(new String(payload, StandardCharsets.UTF_8)).getInt("exp");
            return exp == null ? -1 : exp;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String padBase64(String value) {
        StringBuilder padded = new StringBuilder(value);
        while (padded.length() % 4 != 0) {
            padded.append('=');
        }
        return padded.toString();
    }

    private static String stripBearer(String token) {
        String trimmed = StrUtil.trimToEmpty(token);
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed.substring(7).trim() : trimmed;
    }

    // ==================== 请求 ====================

    private String requestKey() {
        HttpResponse clockRes = HttpRequest.get(clockUrl)
                .disableCookie()
                .headerMap(userHeaders(currentToken), true)
                .execute();
        if (!clockRes.isOk()) {
            System.out.println("[墨天轮] 读取加密时间戳失败，HTTP " + clockRes.getStatus());
            return "";
        }
        JSONObject body = toJSON(clockRes.body());
        Object callback = body == null ? null : body.get("operateCallBackObj");
        if (callback == null) {
            return "";
        }
        try {
            return encryptRequestKey(String.valueOf(callback));
        } catch (Exception e) {
            System.out.println("[墨天轮] 加密时间戳失败：" + StrUtil.maxLength(e.getMessage(), 100));
            return "";
        }
    }

    /**
     * 登录请求带重试：网络抖动（如 Connection refused）时重试，最后一次仍失败则抛出最后一次异常。
     */
    HttpResponse loginWithRetry(JSONObject bodyJson) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= loginMaxAttempts; attempt++) {
            try {
                return HttpRequest.post(loginUrl).headerMap(headers(), true)
                        .body(bodyJson.toString())
                        .execute();
            } catch (RuntimeException e) {
                lastError = e;
                System.err.println("[墨天轮] 登录请求异常（第 " + attempt + "/" + loginMaxAttempts + " 次）：" + e.getMessage());
                if (attempt < loginMaxAttempts) {
                    sleep(loginRetryDelayMs);
                }
            }
        }
        throw lastError == null ? new IllegalStateException("墨天轮登录重试次数非法") : lastError;
    }

    public Map<String, String> headers() {
        Map<String, String> headers = commonHeaders();
        headers.put("Host", "www.modb.pro");
        headers.put("Origin", "https://www.modb.pro");
        headers.put("Referer", "https://www.modb.pro/login?redirect=%2ForderList");
        return headers;
    }

    public Map<String, String> userHeaders(String token) {
        Map<String, String> headers = commonHeaders();
        headers.put("Host", "www.modb.pro");
        headers.put("Origin", "https://www.modb.pro");
        headers.put("Authorization", bearer(token));
//        headers.put("Referer", "https://www.modb.pro/login?redirect=%2ForderList");
        return headers;
    }

    /** 登录响应里已经带好了 Bearer 前缀，复用文件里存的是裸 JWT，两种入参都要能用。 */
    static String bearer(String token) {
        String trimmed = StrUtil.trimToEmpty(token);
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            trimmed = trimmed.substring(7).trim();
        }
        return trimmed.isEmpty() ? "" : "Bearer " + trimmed;
    }

    // 生成类似原JS的UUID
    private static String generateCustomUUID() {
        SecureRandom random = new SecureRandom();
        char[] chars = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
        char[] uuid = new char[36];

        for (int i = 0; i < 36; i++) {
            uuid[i] = chars[random.nextInt(16)];
        }

        // 强制设置特定位置的值
        uuid[14] = '4';
        uuid[19] = Character.forDigit((uuid[19] & 0x3) | 0x8, 16); // 保持第三位为特定值

        // 插入分隔符
        uuid[8] = uuid[13] = uuid[18] = uuid[23] = '-';
        return new String(uuid);
    }

    // AES加密实现
    public static String encryptRequestKey(String callbackData) throws Exception {
        String uuid = generateCustomUUID();
        String plainText = uuid + ":" + callbackData;

        // 密钥处理（注意：这里可能需要根据实际算法调整）
        SecretKeySpec keySpec = new SecretKeySpec(AES_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(AES_IV.getBytes(StandardCharsets.UTF_8));

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

}
