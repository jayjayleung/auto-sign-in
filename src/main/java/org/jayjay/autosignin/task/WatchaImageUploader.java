package org.jayjay.autosignin.task;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把图片直接传进观猹自己的对象存储，不依赖任何外部图床。
 * <p>
 * 站点前端的图片上传就是两步：
 * <ol>
 *   <li>{@code GET /api/v2/infra/request-upload-image?postfix=png} 拿上传凭证（bucket、key、policy、签名）；</li>
 *   <li>把这些字段连同文件一起 multipart POST 到凭证里的 {@code upload_url}（TOS），
 *       返回 204 后最终地址是 {@code upload_url + key}。</li>
 * </ol>
 * 传上去的地址和真实用户截图同域同路径（{@code prod/user/uploads/...}），比任何图床都自然。
 */
final class WatchaImageUploader {

    private static final String REQUEST_PATH = "/api/v2/infra/request-upload-image";
    private static final int TIMEOUT = 60000;

    private WatchaImageUploader() {
    }

    /**
     * @return 站内图片地址；失败返回 null
     */
    static String upload(File image, String baseUrl, String token) {
        if (image == null || !image.isFile() || image.length() == 0) {
            return null;
        }
        try {
            String postfix = extensionOf(image.getName());
            String credentialUrl = baseUrl + REQUEST_PATH + "?postfix=" + postfix
                    + "&safaricanbiteme=" + Math.random();
            JSONObject payload = requestData(credentialUrl, token);
            if (payload == null) {
                return null;
            }
            String uploadUrl = payload.getStr("upload_url");
            JSONObject context = payload.getJSONObject("upload_context");
            String uploadType = payload.getStr("upload_type");
            if (StrUtil.isBlank(uploadUrl) || context == null || !"tos".equals(uploadType)) {
                System.out.println("[观猹] 上传凭证不可用：" + StrUtil.maxLength(String.valueOf(payload), 120));
                return null;
            }
            Map<String, Object> form = new LinkedHashMap<String, Object>();
            for (String key : context.keySet()) {
                form.put(key, context.getStr(key));
            }
            form.put("acl", "public-read");
            form.put("Content-Type", contentTypeOf(postfix));
            form.put("file", image);
            try (HttpResponse response = HttpRequest.post(uploadUrl)
                    .form(form)
                    .timeout(TIMEOUT)
                    .execute()) {
                if (response.getStatus() != 204 && response.getStatus() != 200) {
                    System.out.println("[观猹] 图片上传失败：HTTP " + response.getStatus()
                            + " " + StrUtil.maxLength(response.body(), 120));
                    return null;
                }
            }
            return uploadUrl.endsWith("/") ? uploadUrl + context.getStr("key") : uploadUrl + "/" + context.getStr("key");
        } catch (Exception e) {
            System.out.println("[观猹] 图片上传异常：" + StrUtil.maxLength(e.getMessage(), 120));
            return null;
        }
    }

    private static JSONObject requestData(String url, String token) {
        try (HttpResponse response = HttpRequest.get(url)
                .headerMap(CheckInTask.commonHeaders(), true)
                .header("Authorization", "Bearer " + token)
                .disableCookie()
                .timeout(TIMEOUT)
                .execute()) {
            JSONObject json = JSONUtil.parseObj(response.body());
            if (json.getInt("statusCode", 0) != 200) {
                return null;
            }
            return json.getJSONObject("data");
        } catch (Exception e) {
            System.out.println("[观猹] 获取上传凭证失败：" + StrUtil.maxLength(e.getMessage(), 120));
            return null;
        }
    }

    static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase() : "png";
    }

    static String contentTypeOf(String extension) {
        if ("jpg".equals(extension) || "jpeg".equals(extension)) {
            return "image/jpeg";
        }
        if ("webp".equals(extension)) {
            return "image/webp";
        }
        if ("gif".equals(extension)) {
            return "image/gif";
        }
        return "image/png";
    }
}
