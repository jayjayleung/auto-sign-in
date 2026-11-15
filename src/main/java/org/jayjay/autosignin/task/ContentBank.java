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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预制内容库：把「标题+正文」成对存档，供提问、探讨帖、故事帖取用。
 * <p>
 * 建库的动机是站点对标题做全局查重，一旦撞车会直接拒收。库里区分三种状态：
 * ready 可用、used 已发过、rejected 被站点拒过（标题已存在），后两种永不复用。
 * 文件放在 state 目录，跨天累积，重建容器只要挂载了 state 就不会丢。
 */
final class ContentBank {

    /** 内容类型：找答案提问、论坛探讨帖、论坛故事帖。 */
    static final String ASK = "ask";
    static final String POST = "post";
    static final String STORY = "story";

    private static final String READY = "ready";
    private static final String USED = "used";
    private static final String REJECTED = "rejected";
    /** 库存低于这个数就在运行开头补货。 */
    static final int LOW_WATERMARK = 10;
    /** 补货目标条数。每账号每天用 1 条，20 条约够 20 天，补一次约 2 分钟。 */
    static final int TARGET_SIZE = 20;
    /** 每批生成条数：批次太大推理模型容易超时，5 条实测约 25-40 秒。 */
    private static final int BATCH_SIZE = 5;
    /** 补货最多尝试几批，避免 AI 异常时卡住整个任务。 */
    private static final int MAX_BATCHES = 14;
    /** 单批生成超时。推理模型较慢，给足余量但要能在 cron 任务里跑完。 */
    private static final int GENERATE_TIMEOUT_MILLIS = 240_000;

    private final SiemensCheckInTask task;
    private final Path file;
    private JSONArray items;

    ContentBank(SiemensCheckInTask task, Path file) {
        this.task = task;
        this.file = file;
    }

    /**
     * 取一条可用的内容并标记为已用。库里没有时现场生成一条应急。
     * 返回 {title, body}，生成失败返回 null。
     */
    String[] take(String kind) {
        load();
        JSONObject ready = firstReady(kind);
        if (ready != null) {
            ready.set("status", USED);
            ready.set("usedAt", java.time.LocalDateTime.now().toString());
            save();
            return new String[]{ready.getStr("title"), ready.getStr("body")};
        }
        System.out.println("[西门子] " + kind + " 内容库已空，现场生成一条");
        List<String[]> generated = generate(kind, 1);
        if (generated.isEmpty()) {
            return null;
        }
        String[] content = generated.get(0);
        append(kind, content[0], content[1], USED);
        save();
        return content;
    }

    /** 标记某条标题被站点拒绝（标题已存在），后续不再复用。 */
    void markRejected(String kind, String title) {
        load();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item != null && kind.equals(item.getStr("kind"))
                    && title != null && title.equals(item.getStr("title"))) {
                item.set("status", REJECTED);
                save();
                return;
            }
        }
    }

    /**
     * 补货：库存不足时批量生成直到达标。
     * 在运行开头调用，失败不影响签到和不需要文案的任务。
     */
    void refillIfLow(String kind) {
        load();
        int ready = countReady(kind);
        if (ready >= LOW_WATERMARK) {
            System.out.println("[西门子] " + kind + " 内容库剩余 " + ready + " 条，无需补充");
            return;
        }
        int needed = TARGET_SIZE - ready;
        System.out.println("[西门子] " + kind + " 内容库剩余 " + ready + " 条，开始补充 " + needed + " 条");
        int added = 0;
        int failures = 0;
        for (int batch = 0; batch < MAX_BATCHES && added < needed; batch++) {
            int size = Math.min(BATCH_SIZE, needed - added);
            List<String[]> generated = generate(kind, size);
            if (generated.isEmpty()) {
                failures++;
                System.out.println("[西门子] " + kind + " 第 " + (batch + 1) + " 批生成失败");
                if (failures >= 2) {
                    System.out.println("[西门子] " + kind + " 连续两批失败，停止补充");
                    break;
                }
                continue;
            }
            failures = 0;
            for (String[] content : generated) {
                append(kind, content[0], content[1], READY);
                added++;
            }
            save();
            System.out.println("[西门子] " + kind + " 已补充 " + added + " 条");
        }
    }

    private int countReady(String kind) {
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item != null && kind.equals(item.getStr("kind")) && READY.equals(item.getStr("status"))) {
                count++;
            }
        }
        return count;
    }

    private JSONObject firstReady(String kind) {
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item != null && kind.equals(item.getStr("kind")) && READY.equals(item.getStr("status"))) {
                return item;
            }
        }
        return null;
    }

    private void append(String kind, String title, String body, String status) {
        if (StrUtil.isBlank(title) || StrUtil.isBlank(body)) {
            return;
        }
        // 标题重复的直接丢弃，站点认的是标题。
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item != null && title.equals(item.getStr("title"))) {
                return;
            }
        }
        JSONObject item = JSONUtil.createObj();
        item.set("kind", kind);
        item.set("title", title);
        item.set("body", body);
        item.set("status", status);
        item.set("addedAt", java.time.LocalDate.now().toString());
        item.set("usedAt", null);
        items.put(item);
    }

    // ==================== 生成 ====================

    /** 调外部 AI 生成 count 条内容，返回 {title, body} 列表；失败返回空列表。 */
    private List<String[]> generate(String kind, int count) {
        List<String[]> result = new ArrayList<>();
        String baseUrl = env("AI_BASE_URL");
        String apiKey = env("AI_API_KEY");
        String model = env("AI_MODEL");
        if (StrUtil.isBlank(baseUrl) || StrUtil.isBlank(apiKey) || StrUtil.isBlank(model)) {
            System.out.println("[西门子] 未配置 AI_BASE_URL/AI_API_KEY/AI_MODEL，无法生成内容");
            return result;
        }
        String prompt = buildPrompt(kind, count);
        JSONObject payload = JSONUtil.createObj();
        payload.set("model", model);
        payload.set("messages", new JSONArray().put(JSONUtil.createObj()
                .set("role", "user").set("content", prompt)));
        // 推理模型先消耗 token 再出正文，预算给足否则 content 会是 null。
        payload.set("max_tokens", 8000);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json;charset=UTF-8");
        String url = StrUtil.removeSuffix(baseUrl, "/") + "/chat/completions";
        try {
            HttpResponse response = HttpRequest.post(url).body(payload.toString())
                    .headerMap(headers, true).timeout(GENERATE_TIMEOUT_MILLIS).execute();
            if (response.getStatus() != 200) {
                System.out.println("[西门子] 生成内容 HTTP " + response.getStatus());
                return result;
            }
            JSONObject body = JSONUtil.parseObj(response.body());
            JSONArray choices = body.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return result;
            }
            String content = choices.getJSONObject(0).getJSONObject("message") == null ? null
                    : choices.getJSONObject(0).getJSONObject("message").getStr("content");
            if (StrUtil.isBlank(content)) {
                return result;
            }
            return parseItems(content);
        } catch (Exception e) {
            System.out.println("[西门子] 生成内容异常：" + e.getMessage());
            return result;
        }
    }

    /** 解析 AI 返回的 JSON 数组，容忍 ```json 包裹和少量杂字。 */
    static List<String[]> parseItems(String raw) {
        List<String[]> result = new ArrayList<>();
        String json = raw == null ? "" : raw.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return result;
        }
        JSONArray array;
        try {
            array = JSONUtil.parseArray(json.substring(start, end + 1));
        } catch (Exception e) {
            return result;
        }
        Set<String> titles = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String title = StrUtil.trim(item.getStr("title"));
            String body = StrUtil.trim(item.getStr("body"));
            if (StrUtil.isBlank(title) || StrUtil.isBlank(body) || !titles.add(title)) {
                continue;
            }
            result.add(new String[]{title, body});
        }
        return result;
    }

    private String buildPrompt(String kind, int count) {
        if (ASK.equals(kind)) {
            return "你是西门子 S7-1500 PLC 现场工程师。请生成 " + count + " 条「找答案」提问，"
                    + "每条包含标题和描述两部分，要求：\n"
                    + "1. 标题 15-25 个汉字，写清具体现象和对象，像真实工程师随手的提问口吻，不要带序号和引号\n"
                    + "2. 标题开头要多样化：不要都用「现场」「S7-1500」起头，有的可以直接从故障现象、"
                    + "模块型号或疑问点切入，允许口语化\n"
                    + "3. 描述 60-120 字，写清现场背景、现象和已尝试的排查，口语，不要分点\n"
                    + "4. 各条之间主题必须互不相同，覆盖：组态下载、PROFINET 通讯、OPC UA、"
                    + "运动控制、诊断报警、固件升级、程序监控、与第三方设备互联、冗余与环网、数据记录等场景\n"
                    + "5. 不要出现具体人名、公司名、项目名\n"
                    + "6. 严格输出 JSON 数组，每项形如 {\"title\":\"...\",\"body\":\"...\"}，不要任何解释文字";
        }
        if (POST.equals(kind)) {
            return "你是西门子 S7-1500 PLC 现场工程师。请生成 " + count + " 条技术论坛的「探讨帖」，"
                    + "每条包含标题和正文，要求：\n"
                    + "1. 标题 12-20 个汉字，像真实工程师发起讨论的口吻，不要带序号和引号\n"
                    + "2. 正文 150-260 字，围绕 S7-1500 与 PROFINET、组态、诊断、运动控制等实际应用经验，"
                    + "写自己的做法和体会，口语，不要分点，不要引用来源\n"
                    + "3. 各条主题互不相同，标题开头多样化\n"
                    + "4. 不要出现具体人名、公司名、项目名\n"
                    + "5. 严格输出 JSON 数组，每项形如 {\"title\":\"...\",\"body\":\"...\"}，不要任何解释文字";
        }
        return "你是西门子 S7-1500 PLC 现场工程师。请生成 " + count + " 个「现场调试故事」的素材，"
                + "每条包含标题和故事正文，要求：\n"
                + "1. 标题 10-18 个汉字，像真实故事的题目，不要带序号和引号\n"
                + "2. 故事正文 200-300 字，第一人称，讲一次现场调试或抢修的经历：遇到什么问题、"
                + "怎么排查、最后怎么解决、收获什么，口语，不要分点\n"
                + "3. 各条故事互不相同，覆盖不同场景（组网、伺服、通讯、程序、上电、改造等）\n"
                + "4. 不要出现具体人名、公司名、项目名\n"
                + "5. 严格输出 JSON 数组，每项形如 {\"title\":\"...\",\"body\":\"...\"}，不要任何解释文字";
    }

    private String env(String key) {
        return task == null || task.environment() == null ? null : task.environment().get(key);
    }

    // ==================== 存取 ====================

    private void load() {
        if (items != null) {
            return;
        }
        items = new JSONArray();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JSONArray saved = JSONUtil.parseArray(text);
            if (saved != null) {
                items = saved;
            }
        } catch (Exception e) {
            // 文件损坏时按空库处理，重新生成即可。
            items = new JSONArray();
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, items.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 写不进去只影响下次复用，不影响本次。
        }
    }
}
