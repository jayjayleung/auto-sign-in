package org.jayjay.autosignin.dashboard;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置矩阵：把各实例的 env 文件解析成「哪些项配了、开关是开还是关」。
 * <p>
 * 硬约束：任何配置值都不进入返回值。{@code .env} 里是账号密码、
 * 会话令牌等价于 Cookie，看板只回答「配没配」这个问题。
 */
final class ConfigMatrix {

    /** 站点或渠道 → 需要的键。只用来算「配了几项」，键名本身不是秘密。 */
    private static final Map<String, String[]> GROUPS = new LinkedHashMap<String, String[]>();

    static {
        GROUPS.put("墨天轮", new String[]{"MODB_USERNAME", "MODB_PASSWORD"});
        GROUPS.put("TiDB", new String[]{"TIDB_USERNAME", "TIDB_PASSWORD", "TIDB_COOKIE"});
        GROUPS.put("永洪", new String[]{"YH_USERNAME", "YH_PASSWORD"});
        GROUPS.put("云桥", new String[]{"YUNQIAO_USERNAME", "YUNQIAO_PASSWORD",
                "YUNQIAO_USERNAME_1", "YUNQIAO_PASSWORD_1", "YUNQIAO_USERNAME_2", "YUNQIAO_PASSWORD_2"});
        GROUPS.put("观猹", new String[]{"WATCHA_USERNAME", "WATCHA_PASSWORD"});
        GROUPS.put("西门子", new String[]{"SIEMENS_USERNAME", "SIEMENS_PASSWORD"});
        GROUPS.put("文案接口", new String[]{"AI_BASE_URL", "AI_API_KEY", "AI_MODEL"});
        GROUPS.put("邮件", new String[]{"EMAIL_USERNAME", "EMAIL_PASSWORD", "EMAIL_TO"});
        GROUPS.put("PushPlus", new String[]{"PUSH_PLUS_TOKEN"});
        GROUPS.put("Server酱", new String[]{"SERVER_CHAN_TOKEN"});
    }

    /** 影响行为的重要开关，页面单独展示开/关。 */
    private static final String[] SWITCH_KEYS = {"SIEMENS_ENABLED", "SIEMENS_DAILY_TASKS", "SIEMENS_STORY_POST",
            "WATCHA_PUBLISH_REVIEW", "WATCHA_PUBLISH_POST", "WATCHA_BROWSER", "WATCHA_AUTO_REVIEW"};

    /**
     * 各开关留空时的实际默认值，必须和任务里的判断一致，
     * 否则页面会把「默认开启」误报成「关闭」。
     */
    private static final Map<String, Boolean> SWITCH_DEFAULTS = new LinkedHashMap<String, Boolean>();

    static {
        SWITCH_DEFAULTS.put("SIEMENS_ENABLED", Boolean.TRUE);
        // 真实站点上 dailyTasksByDefault=true，只有本地/测试入口才默认关闭。
        SWITCH_DEFAULTS.put("SIEMENS_DAILY_TASKS", Boolean.TRUE);
        SWITCH_DEFAULTS.put("SIEMENS_STORY_POST", Boolean.TRUE);
        SWITCH_DEFAULTS.put("WATCHA_PUBLISH_REVIEW", Boolean.FALSE);
        SWITCH_DEFAULTS.put("WATCHA_PUBLISH_POST", Boolean.FALSE);
        SWITCH_DEFAULTS.put("WATCHA_BROWSER", Boolean.FALSE);
        SWITCH_DEFAULTS.put("WATCHA_AUTO_REVIEW", Boolean.TRUE);
    }

    private final Path configDir;

    ConfigMatrix(Path configDir) {
        this.configDir = configDir;
    }

    /** 单个实例的配置概况。 */
    JSONObject of(String instance) {
        JSONObject json = new JSONObject();
        Path envFile = envFile(instance);
        Map<String, String> values = envFile == null
                ? Collections.<String, String>emptyMap() : parseEnv(envFile);
        json.set("found", envFile != null && Files.isRegularFile(envFile));
        JSONArray groups = new JSONArray();
        for (Map.Entry<String, String[]> entry : GROUPS.entrySet()) {
            int present = 0;
            for (String key : entry.getValue()) {
                if (StrUtil.isNotBlank(values.get(key))) {
                    present++;
                }
            }
            groups.add(new JSONObject().set("name", entry.getKey())
                    .set("present", present).set("total", entry.getValue().length));
        }
        json.set("groups", groups);
        JSONArray switches = new JSONArray();
        for (String key : SWITCH_KEYS) {
            switches.add(new JSONObject().set("key", key)
                    .set("value", switchText(key, StrUtil.trimToEmpty(values.get(key)))));
        }
        json.set("switches", switches);
        return json;
    }

    /**
     * 多实例并排对比：页面「配置」页只需要这一份数据就能看出哪台缺了哪项。
     * 每行是一个配置组或开关，每列是一个实例。
     */
    JSONObject compare(List<String> instances) {
        JSONObject json = new JSONObject();
        JSONArray columns = new JSONArray();
        Map<String, JSONObject> byInstance = new LinkedHashMap<String, JSONObject>();
        for (String instance : instances) {
            JSONObject config = of(instance);
            byInstance.put(instance, config);
            columns.add(new JSONObject().set("name", instance).set("found", config.getBool("found")));
        }
        json.set("instances", columns);

        JSONArray groupRows = new JSONArray();
        for (Map.Entry<String, String[]> entry : GROUPS.entrySet()) {
            JSONObject row = new JSONObject().set("name", entry.getKey());
            JSONArray cells = new JSONArray();
            for (String instance : instances) {
                JSONObject config = byInstance.get(instance);
                JSONObject match = findByName(config.getJSONArray("groups"), entry.getKey());
                cells.add(new JSONObject()
                        .set("present", match == null ? 0 : match.getInt("present", 0))
                        .set("total", entry.getValue().length));
            }
            row.set("cells", cells);
            groupRows.add(row);
        }
        json.set("groups", groupRows);

        JSONArray switchRows = new JSONArray();
        for (String key : SWITCH_KEYS) {
            JSONObject row = new JSONObject().set("key", key);
            JSONArray cells = new JSONArray();
            for (String instance : instances) {
                JSONObject match = findByKey(byInstance.get(instance).getJSONArray("switches"), key);
                cells.add(new JSONObject().set("value", match == null ? "" : match.getStr("value")));
            }
            row.set("cells", cells);
            switchRows.add(row);
        }
        json.set("switches", switchRows);
        return json;
    }

    private Path envFile(String instance) {
        if (configDir == null) {
            return null;
        }
        // 主实例用 .env，其余实例用 .env.<后缀>，和工作区的挂载一一对应。
        if ("auto-sign-in".equals(instance)) {
            return configDir.resolve(".env");
        }
        return configDir.resolve(".env." + instance.replace("auto-sign-in-", ""));
    }

    /** 空值代表用默认值，展示时写明实际是开还是关，避免误读。 */
    static String switchText(String key, String raw) {
        if (StrUtil.isBlank(raw)) {
            Boolean defaultValue = SWITCH_DEFAULTS.get(key);
            return "默认(" + (Boolean.TRUE.equals(defaultValue) ? "开" : "关") + ")";
        }
        String text = raw.trim();
        boolean on = !"false".equalsIgnoreCase(text) && !"0".equals(text);
        return on ? "开" : "关";
    }

    private static JSONObject findByName(JSONArray array, String name) {
        if (array == null) {
            return null;
        }
        for (int index = 0; index < array.size(); index++) {
            JSONObject item = array.getJSONObject(index);
            if (name.equals(item.getStr("name"))) {
                return item;
            }
        }
        return null;
    }

    private static JSONObject findByKey(JSONArray array, String key) {
        if (array == null) {
            return null;
        }
        for (int index = 0; index < array.size(); index++) {
            JSONObject item = array.getJSONObject(index);
            if (key.equals(item.getStr("key"))) {
                return item;
            }
        }
        return null;
    }

    /**
     * 只解析 KEY=VALUE，跳过注释和空行，剥掉成对引号。
     * 只需要判断是否为空，所以不做变量展开。
     */
    static Map<String, String> parseEnv(Path file) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (String line : StateFiles.lines(file)) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#") || !text.contains("=")) {
                continue;
            }
            if (text.startsWith("export ")) {
                text = text.substring("export ".length()).trim();
            }
            int index = text.indexOf('=');
            String key = text.substring(0, index).trim();
            String value = text.substring(index + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("'") && value.endsWith("'"))
                    || (value.startsWith("\"") && value.endsWith("\"")))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }
}
