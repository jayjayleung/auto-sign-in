package org.jayjay.autosignin.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 兼容 OpenAI 对话接口。未指定平台时读取 AI_BASE_URL、AI_API_KEY、AI_MODEL。
 * 指定平台时读取带前缀的同名变量，例如 OTHER_AI_BASE_URL。
 */
public class AiClient {

    /** 路由到推理模型时，200 字文案也可能超过 90 秒；定时任务无人等待，放宽到 180 秒。 */
    private static final int CHAT_TIMEOUT = 180000;
    private static final int SEARCH_TIMEOUT = 90000;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String fallbackModel;
    private final boolean nativeSearch;
    private final WebSearchClient webSearch;

    public AiClient(Map<String, String> environment, String platform) {
        this(environment, platform, null);
    }

    AiClient(Map<String, String> environment, String platform, WebSearchClient webSearch) {
        String prefix = StrUtil.isBlank(platform) ? "" : platform.trim().toUpperCase() + "_";
        this.baseUrl = value(environment, prefix + "AI_BASE_URL");
        this.apiKey = value(environment, prefix + "AI_API_KEY");
        this.model = value(environment, prefix + "AI_MODEL");
        this.fallbackModel = value(environment, prefix + "AI_FALLBACK_MODEL");
        this.nativeSearch = "true".equalsIgnoreCase(value(environment, prefix + "AI_NATIVE_SEARCH"));
        this.webSearch = webSearch != null ? webSearch
                : new WebSearchClient(value(environment, prefix + "AI_SEARCH_URL"));
    }

    public boolean isReady() {
        return StrUtil.isAllNotBlank(baseUrl, apiKey, model);
    }

    public boolean isPartial() {
        boolean any = StrUtil.isNotBlank(baseUrl) || StrUtil.isNotBlank(apiKey) || StrUtil.isNotBlank(model);
        return any && !isReady();
    }

    public String complete(String system, String user) {
        return complete(system, user, false);
    }

    /** 未配置 AI_FALLBACK_MODEL 时，即使要求备用模型也使用主模型。 */
    public String modelName(boolean fallback) {
        return fallback && StrUtil.isNotBlank(fallbackModel) ? fallbackModel : model;
    }

    public String complete(String system, String user, boolean fallback) {
        JSONArray messages = new JSONArray();
        messages.add(JSONUtil.createObj().set("role", "system").set("content", system));
        messages.add(JSONUtil.createObj().set("role", "user").set("content", user));
        JSONObject payload = JSONUtil.createObj()
                .set("model", modelName(fallback))
                .set("temperature", 0.8)
                .set("max_tokens", 3000)
                .set("messages", messages);
        JSONObject json = request(chatEndpoint(baseUrl), payload, CHAT_TIMEOUT);
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("文案生成失败：服务没有返回文案");
        }
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.getJSONObject("message");
        if (message == null || StrUtil.isBlank(message.getStr("content"))) {
            String finish = choice.getStr("finish_reason");
            throw new IllegalStateException("length".equals(finish)
                    ? "文案生成失败：输出被长度限制截断"
                    : "文案生成失败：服务没有返回文案");
        }
        return message.getStr("content");
    }

    /**
     * 检索产品资料。默认由程序直接执行公开搜索；AI_NATIVE_SEARCH=true 时改由文案服务的 Responses 联网搜索，
     * 兼容服务不执行原生搜索、只回查询词，会多一次等待模型的往返。
     */
    public String research(String system, String user, Set<String> queries) {
        return nativeSearch ? search(system, user) : webSearch.search(queries);
    }

    /** 原生搜索直接读取结果；兼容服务返回的 websearch 函数由程序执行，必须取得实际资料和来源。 */
    public String search(String system, String user) {
        JSONObject payload = JSONUtil.createObj()
                .set("model", model)
                .set("instructions", system)
                .set("input", user)
                .set("tools", JSONUtil.createArray().put(JSONUtil.createObj().set("type", "web_search")))
                .set("tool_choice", "required")
                .set("include", JSONUtil.createArray().put("web_search_call.action.sources"))
                .set("max_output_tokens", 3000);
        JSONObject json = request(responsesEndpoint(baseUrl), payload, SEARCH_TIMEOUT);
        if (!"completed".equals(json.getStr("status"))) {
            throw new IllegalStateException("文案生成失败：联网搜索未完成");
        }
        JSONArray output = json.getJSONArray("output");
        boolean searched = false;
        StringBuilder text = new StringBuilder();
        Set<String> sources = new LinkedHashSet<String>();
        Set<String> queries = new LinkedHashSet<String>();
        if (output != null) {
            for (int index = 0; index < output.size(); index++) {
                JSONObject item = output.getJSONObject(index);
                if (item == null) {
                    continue;
                }
                addSearchQuery(queries, item);
                if ("web_search_call".equals(item.getStr("type"))
                        && "completed".equals(item.getStr("status"))) {
                    searched = true;
                    JSONObject action = item.getJSONObject("action");
                    if (action != null) {
                        addSource(sources, action.getStr("url"));
                        JSONArray links = action.getJSONArray("sources");
                        if (links != null) {
                            for (int link = 0; link < links.size(); link++) {
                                JSONObject source = links.getJSONObject(link);
                                if (source != null) {
                                    addSource(sources, source.getStr("url"));
                                }
                            }
                        }
                    }
                }
                if (!"message".equals(item.getStr("type"))) {
                    continue;
                }
                JSONArray content = item.getJSONArray("content");
                if (content == null) {
                    continue;
                }
                for (int part = 0; part < content.size(); part++) {
                    JSONObject block = content.getJSONObject(part);
                    if (block == null || !"output_text".equals(block.getStr("type"))) {
                        continue;
                    }
                    String value = block.getStr("text");
                    if (StrUtil.isNotBlank(value)) {
                        text.append(value).append('\n');
                    }
                    JSONArray annotations = block.getJSONArray("annotations");
                    if (annotations != null) {
                        for (int annotation = 0; annotation < annotations.size(); annotation++) {
                            JSONObject citation = annotations.getJSONObject(annotation);
                            if (citation != null && "url_citation".equals(citation.getStr("type"))) {
                                addSource(sources, citation.getStr("url"));
                            }
                        }
                    }
                }
            }
        }
        if (!searched) {
            if (!queries.isEmpty()) {
                return webSearch.search(queries);
            }
            throw new IllegalStateException("文案生成失败：服务未执行联网搜索");
        }
        if (text.length() == 0 || sources.isEmpty()) {
            throw new IllegalStateException("文案生成失败：联网搜索没有返回产品资料或来源");
        }
        String summary = text.length() > 4000 ? text.substring(0, 4000) : text.toString();
        return summary.trim() + "\n检索来源：\n" + String.join("\n", sources);
    }

    public static String chatEndpoint(String baseUrl) {
        return apiBase(baseUrl) + "/chat/completions";
    }

    public static String responsesEndpoint(String baseUrl) {
        return apiBase(baseUrl) + "/responses";
    }

    private static String apiBase(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        for (String suffix : new String[]{"/chat/completions", "/responses"}) {
            if (value.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private JSONObject request(String endpoint, JSONObject payload, int timeout) {
        String body;
        int status;
        try (HttpResponse response = HttpRequest.post(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .body(payload.toString())
                .timeout(timeout)
                .execute()) {
            body = response.body();
            status = response.getStatus();
        } catch (RuntimeException e) {
            throw new IllegalStateException("文案生成失败：" + briefMessage(e), e);
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(errorMessage(body));
        }
        JSONObject json = parseJson(body);
        if (json == null) {
            throw new IllegalStateException("文案生成失败：服务返回了无效数据");
        }
        return json;
    }

    private static void addSource(Set<String> sources, String url) {
        if (sources.size() < 6 && StrUtil.isNotBlank(url) && url.length() <= 1000
                && (url.startsWith("https://") || url.startsWith("http://"))) {
            sources.add(url);
        }
    }

    private static void addSearchQuery(Set<String> queries, JSONObject item) {
        if (queries.size() >= 2 || !"function_call".equals(item.getStr("type"))
                || !"completed".equals(item.getStr("status"))
                || !("websearch".equals(item.getStr("name")) || "web_search".equals(item.getStr("name")))) {
            return;
        }
        try {
            JSONObject arguments = parseJson(item.getStr("arguments"));
            Object query = arguments == null ? null : arguments.get("query");
            if (query instanceof CharSequence) {
                String value = query.toString().trim();
                if (!value.isEmpty() && value.length() <= 300) {
                    queries.add(value);
                }
            }
        } catch (RuntimeException ignored) {
            // 无效的函数参数不能当作搜索成功，也不能用来请求外部网站。
        }
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

    private static String briefMessage(RuntimeException e) {
        String message = e.getMessage();
        if (StrUtil.isBlank(message)) {
            return e.getClass().getSimpleName();
        }
        return trimMessage(message);
    }
}
