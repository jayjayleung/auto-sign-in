package org.jayjay.autosignin.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.Map;

/**
 * 兼容 OpenAI 对话接口。未指定平台时读取 AI_BASE_URL、AI_API_KEY、AI_MODEL。
 * 指定平台时读取带前缀的同名变量，例如 OTHER_AI_BASE_URL。
 */
public class AiClient {

    private static final int TIMEOUT = 60000;

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public AiClient(Map<String, String> environment, String platform) {
        String prefix = StrUtil.isBlank(platform) ? "" : platform.trim().toUpperCase() + "_";
        this.baseUrl = value(environment, prefix + "AI_BASE_URL");
        this.apiKey = value(environment, prefix + "AI_API_KEY");
        this.model = value(environment, prefix + "AI_MODEL");
    }

    public boolean isReady() {
        return StrUtil.isAllNotBlank(baseUrl, apiKey, model);
    }

    public boolean isPartial() {
        boolean any = StrUtil.isNotBlank(baseUrl) || StrUtil.isNotBlank(apiKey) || StrUtil.isNotBlank(model);
        return any && !isReady();
    }

    public String complete(String system, String user) {
        JSONArray messages = new JSONArray();
        messages.add(JSONUtil.createObj().set("role", "system").set("content", system));
        messages.add(JSONUtil.createObj().set("role", "user").set("content", user));
        JSONObject payload = JSONUtil.createObj()
                .set("model", model)
                .set("temperature", 0.8)
                .set("max_tokens", 800)
                .set("messages", messages);
        HttpResponse response = HttpRequest.post(chatEndpoint(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .body(payload.toString())
                .timeout(TIMEOUT)
                .execute();
        String body = response.body();
        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(errorMessage(body));
        }
        JSONObject json = parseJson(body);
        if (json == null) {
            throw new IllegalStateException("文案生成失败：服务返回了无效数据");
        }
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("文案生成失败：服务没有返回文案");
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        if (message == null || StrUtil.isBlank(message.getStr("content"))) {
            throw new IllegalStateException("文案生成失败：服务没有返回文案");
        }
        return message.getStr("content");
    }

    public static String chatEndpoint(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith("/chat/completions")) {
            return value;
        }
        return value + "/chat/completions";
    }

    private static String errorMessage(String body) {
        JSONObject json = parseJson(body);
        if (json != null) {
            JSONObject error = json.getJSONObject("error");
            if (error != null && StrUtil.isNotBlank(error.getStr("message"))) {
                return trimMessage("文案生成失败：" + error.getStr("message"));
            }
            if (StrUtil.isNotBlank(json.getStr("message"))) {
                return trimMessage("文案生成失败：" + json.getStr("message"));
            }
        }
        return "文案生成失败";
    }

    private static JSONObject parseJson(String body) {
        if (StrUtil.isBlank(body) || !JSONUtil.isTypeJSON(body)) {
            return null;
        }
        return JSONUtil.parseObj(body);
    }

    private static String value(Map<String, String> environment, String key) {
        if (environment == null) {
            return "";
        }
        return StrUtil.nullToEmpty(environment.get(key)).trim();
    }

    private static String trimMessage(String message) {
        String text = message.replaceAll("[\\r\\n]+", " ").trim();
        return text.length() > 120 ? text.substring(0, 120) : text;
    }
}
