package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.File;

/**
 * 把试用截图传到临时图床，拿到一个公网地址。
 * <p>
 * 为什么不直接传字节给观猹：它的配图接口只收 URL（{@code GET /api/v2/infra/external-resources/image?url=}），
 * 服务端自己去抓。所以要先有一个能被站外访问的地址，再让它镜像成站内图。
 * <p>
 * 图床必须自己配（{@code WATCHA_SHOT_HOST}），且要能给出**直链**：
 * tmpfiles 的 {@code /dl/} 现在会 302 回网页，catbox/litterbox 拒绝匿名上传，
 * 所以没有默认值——没配就跳过截图，退回官网首图。
 */
final class WatchaImageHost {

    private static final int TIMEOUT = 60000;

    private WatchaImageHost() {
    }

    /**
     * @return 公网可访问的图片地址；失败返回 null
     */
    static String upload(File file, String apiUrl) {
        if (file == null || !file.isFile() || file.length() == 0) {
            return null;
        }
        String endpoint = StrUtil.trimToEmpty(apiUrl);
        if (endpoint.isEmpty()) {
            System.out.println("[观猹] 没配图床地址，跳过截图上传");
            return null;
        }
        try (HttpResponse response = HttpRequest.post(endpoint)
                .form("file", file)
                .timeout(TIMEOUT)
                .execute()) {
            String body = response.body();
            if (StrUtil.isBlank(body)) {
                System.out.println("[观猹] 图床上传没有返回内容");
                return null;
            }
            String url = pickUrl(body);
            if (StrUtil.isBlank(url)) {
                System.out.println("[观猹] 图床返回里没有地址：" + StrUtil.maxLength(body, 120));
                return null;
            }
            return url;
        } catch (Exception e) {
            System.out.println("[观猹] 图床上传失败：" + StrUtil.maxLength(e.getMessage(), 120));
            return null;
        }
    }

    /**
     * tmpfiles 返回的是网页地址 {@code https://tmpfiles.org/1234/x.png}，直链要加 {@code /dl}；
     * 其它图床（catbox 之类）直接返回文件地址，原样使用。
     */
    static String pickUrl(String body) {
        String raw = null;
        try {
            JSONObject json = JSONUtil.parseObj(body);
            JSONObject data = json.getJSONObject("data");
            raw = data == null ? json.getStr("url") : data.getStr("url");
        } catch (Exception ignored) {
            // 有的图床直接返回纯文本地址
            raw = body.trim();
        }
        if (StrUtil.isBlank(raw) || !raw.startsWith("http")) {
            return null;
        }
        return raw;
    }
}
