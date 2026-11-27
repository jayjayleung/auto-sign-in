package org.jayjay.autosignin.dashboard;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.jayjay.autosignin.history.RunHistory;
import org.jayjay.autosignin.task.SiemensLockProbe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单个实例的状态聚合：西门子标记、内容库、观猹会话、运行情况。
 * <p>
 * 只读，不写任何文件；也刻意不回显配置值与会话令牌。
 */
final class InstanceState {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    /** 探测历史给页面留最近多少条，一天一条，够看两周。 */
    private static final int LOCK_HISTORY_LIMIT = 14;

    private final String name;
    private final Path dir;
    private final Path configDir;

    InstanceState(String name, Path dir, Path configDir) {
        this.name = name;
        this.dir = dir;
        this.configDir = configDir;
    }

    String name() {
        return name;
    }

    Path dir() {
        return dir;
    }

    /**
     * 概要：列表页和概览卡只需要的字段，不含日志内容和历史明细。
     * 分开返回是必要的，详情页要读的东西比一行摘要重得多。
     */
    JSONObject summary() {
        JSONObject json = new JSONObject();
        json.set("name", name);
        if (dir == null || !Files.isDirectory(dir)) {
            json.set("available", false);
            json.set("reason", "状态目录不存在");
            return json;
        }
        json.set("available", true);
        JSONObject siemens = siemens();
        JSONObject watcha = watcha();
        JSONObject lastRun = lastRun();
        json.set("siemens", siemens);
        json.set("watcha", watcha);
        json.set("lastRun", lastRun);
        json.set("contentBankReady", contentBank().getInt("ready", 0));
        json.set("exceptionCount", lastRun.getInt("exceptionCount", 0));
        json.set("ranToday", siemens.getBool("ranToday", false));
        json.set("paused", watcha.getBool("paused", false));
        json.set("siemensLocked", siemens.getBool("locked", false));
        json.set("health", health(siemens, watcha, lastRun));
        return json;
    }

    /**
     * 详情：在概要基础上补内容库明细、配置矩阵、最近若干条历史与日志尾部。
     */
    JSONObject detail(int historyLimit, int logLimit, int logTailLines) {
        JSONObject json = summary();
        if (!json.getBool("available", false)) {
            return json;
        }
        json.set("contentBank", contentBank());
        json.set("config", new ConfigMatrix(configDir).of(name));
        json.set("history", history(0, historyLimit));
        json.set("logs", logs(logLimit, logTailLines));
        return json;
    }

    /**
     * 健康度判定，给列表页一个可直接排序的等级。
     * 规则只覆盖「能确定是问题」的情况，剩下的都算正常，避免误报让人失去信任。
     */
    private JSONObject health(JSONObject siemens, JSONObject watcha, JSONObject lastRun) {
        JSONObject json = new JSONObject();
        List<String> problems = new ArrayList<String>();
        if (watcha.getBool("paused", false)) {
            problems.add("猹评已停写");
        }
        long streak = siemens.getLong("storyStreak", 0L);
        if (streak >= 2) {
            problems.add("故事帖连败 " + streak);
        }
        if (siemens.getBool("locked", false)) {
            // 站点锁定时签到还能过，但提问题、发帖、兑换这些动作全会被拦，
            // 每日任务一天撞十几次锁只会加重风险，必须在概览页就能看见。
            problems.add("西门子账号被站点锁定");
        }
        if (lastRun.getBool("hasRecord", false) && lastRun.getInt("exceptionCount", 0) > 0) {
            problems.add(lastRun.getInt("exceptionCount", 0) + " 个站点异常");
        }
        json.set("problems", problems);
        json.set("level", problems.isEmpty() ? "ok" : "warn");
        return json;
    }

    // ==================== 西门子 ====================

    JSONObject siemens() {
        JSONObject json = new JSONObject();
        String ranDate = StrUtil.trimToEmpty(StateFiles.latestContent(dir, "siemens-ran-"));
        json.set("lastRunDate", ranDate);
        json.set("challengeDate", StrUtil.trimToEmpty(StateFiles.latestContent(dir, "siemens-challenge-")));
        json.set("storyStreak", numberOrNull(StateFiles.latestContent(dir, "siemens-story-streak-")));
        json.set("ranToday", LocalDate.now(SHANGHAI).toString().equals(ranDate));
        int notices = StateFiles.countNonBlankLines(StateFiles.latest(dir, "siemens-coin-notices-"));
        if (notices >= 0) {
            json.set("coinNotices", notices);
        }
        Path session = StateFiles.latest(dir, "siemens-session-");
        json.set("hasSession", session != null);
        if (session != null) {
            json.set("sessionUpdatedAt", StateFiles.modifiedText(session));
        }
        JSONObject lock = lock();
        json.set("lock", lock);
        // 提一层到西门子节点上，前端判断「要不要显示红字」不用再往里钻。
        json.set("locked", "locked".equals(lock.getStr("status")));
        return json;
    }

    /**
     * 站点锁定探测的结果，来自 {@code siemens-lock-<账号哈希>.json}。
     * <p>
     * 站点锁定不写期限，只能靠每天探一次攒出「第一次看到锁定」到「恢复」的间隔，
     * 这个间隔就是站点实际给的处置时长，所以两个时间点都要原样给到页面。
     */
    JSONObject lock() {
        JSONObject json = new JSONObject();
        Path file = dir == null ? null : StateFiles.latest(dir, SiemensLockProbe.FILE_PREFIX);
        if (!StateFiles.exists(file)) {
            json.set("status", "unknown");
            json.set("checked", false);
            return json;
        }
        json.set("checked", true);
        try {
            JSONObject data = JSONUtil.parseObj(StateFiles.text(file));
            String status = StrUtil.blankToDefault(data.getStr("status"), "unknown");
            json.set("status", status);
            json.set("checkedAt", RunHistory.displayTime(data.getStr("checkedAt")));
            json.set("firstSeenLocked", RunHistory.displayTime(data.getStr("firstSeenLocked")));
            json.set("lastLockedAt", RunHistory.displayTime(data.getStr("lastLockedAt")));
            json.set("unlockedAt", RunHistory.displayTime(data.getStr("unlockedAt")));
            Long days = SiemensLockProbe.lockedDays(data.getStr("firstSeenLocked"), data.getStr("unlockedAt"));
            if (days != null) {
                json.set("lockedDays", days);
            }
            json.set("history", historyOf(data.getJSONArray("history")));
        } catch (Exception e) {
            // 探测文件坏了不能显示成「未锁定」，那会让人以为可以放心跑每日任务。
            json.set("status", "unknown");
            json.set("error", "探测记录解析失败");
        }
        return json;
    }

    /** 探测历史只留最近若干条给页面，避免把整份记录塞进摘要接口。 */
    private static JSONArray historyOf(JSONArray saved) {
        JSONArray rows = new JSONArray();
        if (saved == null) {
            return rows;
        }
        int from = Math.max(0, saved.size() - LOCK_HISTORY_LIMIT);
        for (int index = from; index < saved.size(); index++) {
            JSONObject item = saved.getJSONObject(index);
            if (item == null) {
                continue;
            }
            rows.add(new JSONObject()
                    .set("at", RunHistory.displayTime(item.getStr("at")))
                    .set("status", StrUtil.blankToDefault(item.getStr("status"), "unknown")));
        }
        return rows;
    }

    // ==================== 内容库 ====================

    JSONObject contentBank() {
        JSONObject json = new JSONObject();
        Path file = dir == null ? null : dir.resolve("siemens-content-bank.json");
        if (!StateFiles.exists(file)) {
            json.set("available", false);
            return json;
        }
        json.set("available", true);
        JSONArray rows = new JSONArray();
        Map<String, int[]> byKind = new LinkedHashMap<String, int[]>();
        try {
            JSONArray items = JSONUtil.parseArray(StateFiles.text(file));
            for (int index = 0; index < items.size(); index++) {
                JSONObject item = items.getJSONObject(index);
                if (item == null) {
                    continue;
                }
                String kind = StrUtil.blankToDefault(item.getStr("kind"), "unknown");
                String status = StrUtil.blankToDefault(item.getStr("status"), "unknown");
                int[] counts = byKind.get(kind);
                if (counts == null) {
                    // 依次是 ready / used / rejected / 其它
                    counts = new int[4];
                    byKind.put(kind, counts);
                }
                counts[statusIndex(status)]++;
            }
        } catch (Exception e) {
            json.set("available", false);
            json.set("error", "内容库解析失败");
            return json;
        }
        int[] total = new int[4];
        for (Map.Entry<String, int[]> entry : byKind.entrySet()) {
            int[] counts = entry.getValue();
            for (int index = 0; index < total.length; index++) {
                total[index] += counts[index];
            }
            rows.add(new JSONObject().set("kind", entry.getKey())
                    .set("ready", counts[0]).set("used", counts[1]).set("rejected", counts[2]));
        }
        json.set("rows", rows);
        json.set("ready", total[0]);
        json.set("used", total[1]);
        json.set("rejected", total[2]);
        json.set("total", total[0] + total[1] + total[2] + total[3]);
        json.set("updatedAt", StateFiles.modifiedText(file));
        return json;
    }

    private static int statusIndex(String status) {
        if ("ready".equals(status)) {
            return 0;
        }
        if ("used".equals(status)) {
            return 1;
        }
        if ("rejected".equals(status)) {
            return 2;
        }
        return 3;
    }

    // ==================== 观猹 ====================

    JSONObject watcha() {
        JSONObject json = new JSONObject();
        Path session = dir == null ? null : dir.resolve("watcha-session.json");
        if (StateFiles.exists(session)) {
            json.set("hasSession", true);
            try {
                JSONObject saved = JSONUtil.parseObj(StateFiles.text(session));
                Long savedAt = saved.getLong("saved_at");
                if (savedAt != null) {
                    json.set("savedAt", RunHistory.displayTime(Instant.ofEpochMilli(savedAt).toString()));
                }
                json.set("accessExpiresAt", jwtExpiry(saved.getStr("access_token")));
                json.set("refreshExpiresAt", jwtExpiry(saved.getStr("refresh_token")));
            } catch (Exception e) {
                json.set("error", "会话文件解析失败");
            }
        } else {
            json.set("hasSession", false);
        }
        Path ledger = dir == null ? null : dir.resolve("watcha-review-ledger.json");
        if (StateFiles.exists(ledger)) {
            try {
                JSONObject data = JSONUtil.parseObj(StateFiles.text(ledger));
                JSONArray published = data.getJSONArray("published");
                int strikes = data.getInt("strikes", 0);
                json.set("strikes", strikes);
                json.set("publishedCount", published == null ? 0 : published.size());
                json.set("paused", strikes >= 2);
                json.set("ledgerUpdatedAt", StateFiles.modifiedText(ledger));
            } catch (Exception e) {
                // 与任务侧一致：台账读不出来时任务会按停写处理，看板不能显示成「无违规」。
                json.set("error", "台账解析失败");
                json.set("unreadable", true);
                json.set("paused", true);
            }
        } else {
            // 没有台账说明还没发过猹评或已经人工清空，等于 0 违规。
            json.set("strikes", 0);
            json.set("publishedCount", 0);
            json.set("paused", false);
        }
        return json;
    }

    /**
     * 只读 JWT 的 exp 声明，用来算会话还能用多久。
     * 这是本地解码，不发请求，也不需要签名校验。
     */
    static String jwtExpiry(String token) {
        if (StrUtil.isBlank(token)) {
            return "";
        }
        String[] segments = token.trim().split("\\.");
        if (segments.length < 2) {
            return "";
        }
        try {
            JSONObject json = JSONUtil.parseObj(Base64.decodeStr(segments[1], java.nio.charset.StandardCharsets.UTF_8));
            Long exp = json.getLong("exp");
            return exp == null ? "" : RunHistory.displayTime(Instant.ofEpochSecond(exp).toString());
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 运行记录 ====================

    /** 最近一次运行的摘要；没有记录时只给 hasRecord=false。 */
    JSONObject lastRun() {
        List<JSONObject> records = RunHistory.read(dir, 1);
        JSONObject json = new JSONObject();
        if (records.isEmpty()) {
            json.set("hasRecord", false);
            return json;
        }
        return runSummary(records.get(0));
    }

    /** 分页取历史，跳过 offset 条后取 limit 条。 */
    JSONArray history(int offset, int limit) {
        JSONArray array = new JSONArray();
        List<String> names = RunHistory.list(dir);
        for (int index = Math.max(0, offset); index < names.size() && array.size() < limit; index++) {
            JSONObject record = RunHistory.readOne(dir, names.get(index));
            if (record != null) {
                array.add(record);
            }
        }
        return array;
    }

    /** 历史条数，给列表页算分页。 */
    int historyCount() {
        return RunHistory.list(dir).size();
    }

    /** 把一条记录压成列表行需要的字段，避免列表页拖着整份 console 走。 */
    JSONObject runSummary(JSONObject record) {
        JSONObject json = new JSONObject();
        json.set("file", record.getStr("file"));
        json.set("instance", name);
        json.set("startedAt", RunHistory.displayTime(record.getStr("startedAt")));
        json.set("finishedAt", RunHistory.displayTime(record.getStr("finishedAt")));
        json.set("durationSeconds", record.getLong("durationSeconds", 0L));
        json.set("exceptionCount", record.getInt("exceptionCount", 0));
        JSONArray sections = record.getJSONArray("sections");
        json.set("sectionCount", sections == null ? 0 : sections.size());
        JSONArray titles = new JSONArray();
        if (sections != null) {
            for (int index = 0; index < sections.size(); index++) {
                titles.add(sections.getJSONObject(index).getStr("title"));
            }
        }
        json.set("sectionTitles", titles);
        json.set("hasRecord", true);
        String finished = record.getStr("finishedAt");
        // 按「结束时间」判断今天：定时任务可能跨零点，按开始时间算会让刚跑完的记录显示成昨天。
        json.set("isToday", finished != null && finished.startsWith(LocalDate.now(SHANGHAI).toString()));
        return json;
    }

    // ==================== 日志 ====================

    JSONArray logs(int limit, int tailLines) {
        JSONArray array = new JSONArray();
        Path logDir = dir == null ? null : dir.resolve(RunHistory.LOG_DIR_NAME);
        if (logDir == null || !Files.isDirectory(logDir)) {
            return array;
        }
        List<Path> files = new ArrayList<Path>();
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "*.log")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        } catch (Exception e) {
            return array;
        }
        java.util.Collections.sort(files, java.util.Comparator.comparing(Path::getFileName).reversed());
        for (Path file : files) {
            if (array.size() >= limit) {
                break;
            }
            array.add(new JSONObject()
                    .set("file", file.getFileName().toString())
                    .set("updatedAt", StateFiles.modifiedText(file))
                    .set("sizeBytes", StateFiles.size(file))
                    .set("tail", LogTail.read(file, tailLines)));
        }
        return array;
    }

    // ==================== 内部 ====================

    private static Long numberOrNull(String text) {
        try {
            return StrUtil.isBlank(text) ? null : Long.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
