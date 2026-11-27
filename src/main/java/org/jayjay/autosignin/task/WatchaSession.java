package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 站点会话的落地缓存。
 * <p>
 * 站点把令牌放在 localStorage 的 {@code watcha.accessToken} 和 {@code watcha.refreshToken}，
 * 前端平时就是拿 refresh_token 换新 access_token，而不是每天重新输密码。
 * 这里照做：先复用 access_token，过期了用 refresh_token 换，两条都不行才真的登录。
 * 登录是整套流程里唯一会把密码送到服务端、也最容易被 449 拦下来的动作，能不登就不登。
 */
final class WatchaSession {

    private final String accessToken;
    private final String refreshToken;
    private final long savedAt;

    private WatchaSession(String accessToken, String refreshToken, long savedAt) {
        this.accessToken = accessToken == null ? "" : accessToken;
        this.refreshToken = refreshToken == null ? "" : refreshToken;
        this.savedAt = savedAt;
    }

    static WatchaSession load(File file) {
        if (file == null || !file.isFile()) {
            return empty();
        }
        try {
            JSONObject json = JSONUtil.parseObj(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            return new WatchaSession(json.getStr("access_token"), json.getStr("refresh_token"),
                    json.getLong("saved_at", 0L));
        } catch (Exception e) {
            return empty();
        }
    }

    static WatchaSession empty() {
        return new WatchaSession("", "", 0L);
    }

    void save(File file) {
        if (StrUtil.isBlank(accessToken) && StrUtil.isBlank(refreshToken)) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            JSONObject json = JSONUtil.createObj()
                    .set("access_token", accessToken)
                    .set("refresh_token", refreshToken)
                    .set("saved_at", System.currentTimeMillis());
            Files.write(file.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.out.println("[观猹] 会话写入失败：" + StrUtil.maxLength(e.getMessage(), 100));
        }
    }

    String accessToken() {
        return accessToken;
    }

    String refreshToken() {
        return refreshToken;
    }

    boolean hasAccess() {
        return StrUtil.isNotBlank(accessToken);
    }

    boolean hasRefresh() {
        return StrUtil.isNotBlank(refreshToken);
    }

    /** 会话存了多久，日志里用。 */
    long ageHours() {
        return savedAt <= 0 ? -1 : (System.currentTimeMillis() - savedAt) / 3600000L;
    }

    static WatchaSession of(String accessToken, String refreshToken) {
        return new WatchaSession(accessToken, refreshToken, System.currentTimeMillis());
    }
}
