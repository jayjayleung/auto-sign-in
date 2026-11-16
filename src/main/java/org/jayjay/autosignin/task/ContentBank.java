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
    /** 库存低于这个数就在运行开头补货。设得比首次补货量低，才能逐步累积而不是每天补一次。 */
    static final int LOW_WATERMARK = 5;
    /**
     * 补货目标条数。每账号每天用 1 条：
     * 库空时只补到 {@link #FIRST_FILL} 条，避免首次运行因生成太多内容而拖太久；
     * 之后每次不足水位线就补齐到 {@link #TARGET_SIZE}，逐步累积。
     */
    static final int TARGET_SIZE = 20;
    /** 库完全为空时的首次补货目标，约 1 分钟，避免三个库一起补把运行拖到十分钟。 */
    static final int FIRST_FILL = 10;
    /** 每批生成条数：批次太大推理模型容易超时，5 条实测约 25-40 秒。 */
    private static final int BATCH_SIZE = 5;
    /** 补货最多尝试几批：补满 20 条只需 3 批，多留几批应对偶发失败。 */
    private static final int MAX_BATCHES = 6;
    /** 单批生成超时。推理模型较慢，给足余量但要能在 cron 任务里跑完。 */
    private static final int GENERATE_TIMEOUT_MILLIS = 240_000;
    /** 整轮运行里补库的总时长上限，三个库共享这一份预算。 */
    static final long REFILL_BUDGET_MILLIS = 10 * 60_000L;

    /** 业务时区固定北京时间：容器和 GitHub Actions 的 TZ 不同，日期口径必须显式指定。 */
    private static final java.time.ZoneId SHANGHAI = java.time.ZoneId.of("Asia/Shanghai");

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
            ready.set("usedAt", java.time.LocalDateTime.now(SHANGHAI).toString());
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
     * <p>
     * 库完全为空时只补到 {@link #FIRST_FILL} 条，避免首次运行一次性生成太多而拖长时间；
     * 已有库存但跌破水位线时补齐到 {@link #TARGET_SIZE}，逐步累积到满。
     * 总时长按整轮运行共享：三个库一起补时不能各算各的，否则会串成半小时。
     */
    void refillIfLow(String kind, long deadline) {
        load();
        int ready = countReady(kind);
        if (ready >= LOW_WATERMARK) {
            System.out.println("[西门子] " + kind + " 内容库剩余 " + ready + " 条，无需补充");
            return;
        }
        int target = ready == 0 ? FIRST_FILL : TARGET_SIZE;
        int needed = target - ready;
        System.out.println("[西门子] " + kind + " 内容库剩余 " + ready + " 条，补充到 "
                + target + " 条（本次生成 " + needed + " 条）");
        int added = 0;
        int failures = 0;
        for (int batch = 0; batch < MAX_BATCHES && added < needed; batch++) {
            if (System.currentTimeMillis() >= deadline) {
                System.out.println("[西门子] " + kind + " 补库总时长已用满，先用现有 "
                        + (ready + added) + " 条");
                break;
            }
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
            // 只统计真正入库的条数：标题撞车的会被 append 丢掉。
            int stored = 0;
            for (String[] content : generated) {
                if (append(kind, content[0], content[1], READY)) {
                    stored++;
                }
            }
            if (stored == 0) {
                System.out.println("[西门子] " + kind + " 第 " + (batch + 1)
                        + " 批内容全部与已有标题重复，跳过");
                continue;
            }
            added += stored;
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

    /**
     * 把一条内容入库。标题已存在或字段为空时丢弃，返回是否真的加进去了。
     * 调用方必须按返回值计数，否则会因为标题重复而虚报补充条数。
     */
    private boolean append(String kind, String title, String body, String status) {
        if (StrUtil.isBlank(title) || StrUtil.isBlank(body)) {
            return false;
        }
        // 标题重复的直接丢弃，站点认的是标题。
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (item != null && title.equals(item.getStr("title"))) {
                return false;
            }
        }
        JSONObject item = JSONUtil.createObj();
        item.set("kind", kind);
        item.set("title", title);
        item.set("body", body);
        item.set("status", status);
        item.set("addedAt", java.time.LocalDate.now(SHANGHAI).toString());
        item.set("usedAt", null);
        items.put(item);
        return true;
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
        // 把已用过的标题喂给模型，避免库空时反复生成同一条。
        String existing = existingTitles(kind);
        if (StrUtil.isNotBlank(existing)) {
            prompt = prompt + "\n\n以下标题已经用过，绝对不要再出现相同或近似的：\n" + existing;
        }
        JSONObject payload = JSONUtil.createObj();
        payload.set("model", model);
        payload.set("messages", new JSONArray().put(JSONUtil.createObj()
                .set("role", "user").set("content", prompt)));
        // 推理模型先消耗 token 再出正文，预算给足否则 content 会是 null。
        // 上限可按模型调整：部分模型（如 gpt-4o）上限较低，超出会直接报错。
        payload.set("max_tokens", maxTokens());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json;charset=UTF-8");
        String url = StrUtil.removeSuffix(baseUrl, "/") + "/chat/completions";
        try {
            HttpResponse response = HttpRequest.post(url).body(payload.toString())
                    .headerMap(headers, true).timeout(GENERATE_TIMEOUT_MILLIS).execute();
            if (response.getStatus() != 200) {
                System.out.println("[西门子] 生成内容 HTTP " + response.getStatus()
                        + "：" + StrUtil.maxLength(response.body(), 200));
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
        return "你是西门子 S7-1500 PLC 现场工程师，在技术论坛写自己的调试经历。"
                + "请生成 " + count + " 个「现场故事」，每条包含标题和正文，要求：\n"
                + "1. 标题 10-18 个汉字，具体到这次经历的特征，不要用「一次……」「记一次……」这类泛泛开头，"
                + "不要带序号和引号\n"
                + "2. 正文 200-300 字，第一人称，写一件自己亲历的具体事：\n"
                + "   - 开头交代清楚时间地点氛围（例如凌晨两点、车间刚停线、下着雨、甲方在旁边催）\n"
                + "   - 必须出现具体的硬件型号或订货号、软件版本、报警代码、参数名或测量数值\n"
                + "   - 写清楚当时自己先怀疑了什么、查了什么、怎么发现判断错了，再写真正的原因\n"
                + "   - 结尾写一句这次之后自己改了什么习惯或做法\n"
                + "3. 细节要独一无二：具体的柜号、端子号、插槽位置、屏幕上的原文提示、量到的电压电流值，"
                + "不要写「网线松动」「设备名冲突」「接地不良」这类谁都能编的通用桥段\n"
                + "4. 各条之间场景和故障类型必须完全不同，不要都是通讯掉站\n"
                + "5. 不要出现真实人名、公司名、项目名、具体城市\n"
                + "6. 严格输出 JSON 数组，每项形如 {\"title\":\"...\",\"body\":\"...\"}，不要任何解释文字";
    }

    /** 取该类型下已有（用过或被拒）的标题，供提示词去重；太多时只取最近的部分。 */
    private String existingTitles(String kind) {
        List<String> titles = new ArrayList<>();
        for (int i = items.size() - 1; i >= 0 && titles.size() < 20; i--) {
            JSONObject item = items.getJSONObject(i);
            if (item == null || !kind.equals(item.getStr("kind"))) {
                continue;
            }
            String title = item.getStr("title");
            if (StrUtil.isNotBlank(title)) {
                titles.add(title);
            }
        }
        return String.join("\n", titles);
    }

    private String env(String key) {
        return task == null || task.environment() == null ? null : task.environment().get(key);
    }

    /** 生成用的 token 上限，可用 AI_MAX_TOKENS 覆盖；默认 8000 适配推理模型。 */
    private int maxTokens() {
        String configured = env("AI_MAX_TOKENS");
        if (StrUtil.isNotBlank(configured)) {
            try {
                int value = Integer.parseInt(configured.trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 填错就用默认值。
            }
        }
        return 8000;
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
            // 先写临时文件再改名：cron 进程可能在写入途中被终止，
            // 直接覆盖会留下损坏的 JSON，下次启动按空库处理就丢了全部去重记录。
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, items.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            // 写不进去只影响下次复用，不影响本次。
        }
    }
}
