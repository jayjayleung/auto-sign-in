package org.jayjay.autosignin.dashboard;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 看板聚合的测试。全部用临时目录造状态，不碰真实 state、网络和凭证。
 */
public class DashboardStateTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path stateRoot;
    private Path configDir;
    private DashboardState state;

    private void prepare() throws Exception {
        stateRoot = folder.newFolder("state").toPath();
        configDir = folder.newFolder("config").toPath();
        Files.createDirectories(stateRoot.resolve("auto-sign-in"));
        Files.createDirectories(stateRoot.resolve("shared"));
        state = new DashboardState(stateRoot, Arrays.asList("auto-sign-in"), configDir);
    }

    private JSONObject instanceSummary() {
        return state.instance("auto-sign-in").summary();
    }

    private JSONObject instanceDetail() {
        return state.instanceDetail("auto-sign-in", 10, 3, 200);
    }

    private static String today() {
        return LocalDate.now(SHANGHAI).toString();
    }

    // ==================== 西门子 ====================

    @Test
    public void 西门子标记判断今天是否已跑() throws Exception {
        prepare();
        Path dir = stateRoot.resolve("auto-sign-in");
        Files.write(dir.resolve("siemens-ran-abc123.txt"), today().getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("siemens-challenge-abc123.txt"), "2026-11-25\n".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("siemens-story-streak-abc123.txt"), "2".getBytes(StandardCharsets.UTF_8));

        JSONObject siemens = instanceSummary().getJSONObject("siemens");
        assertEquals(today(), siemens.getStr("lastRunDate"));
        assertTrue(siemens.getBool("ranToday"));
        assertEquals("2026-11-25", siemens.getStr("challengeDate"));
        assertEquals(2L, siemens.getLong("storyStreak").longValue());
    }

    @Test
    public void 昨天跑过则今天视为未跑() throws Exception {
        prepare();
        Files.write(stateRoot.resolve("auto-sign-in/siemens-ran-abc123.txt"),
                "2026-01-01".getBytes(StandardCharsets.UTF_8));

        JSONObject siemens = instanceSummary().getJSONObject("siemens");
        assertEquals("2026-01-01", siemens.getStr("lastRunDate"));
        assertFalse(siemens.getBool("ranToday"));
    }

    @Test
    public void 没探测记录时锁定状态为未探测() throws Exception {
        prepare();
        JSONObject lock = instanceSummary().getJSONObject("siemens").getJSONObject("lock");
        assertEquals("unknown", lock.getStr("status"));
        assertFalse(lock.getBool("checked"));
        // 没探测过不能算「已锁定」，否则概览页会在全新部署时误报
        assertFalse(instanceSummary().getBool("siemensLocked"));
    }

    @Test
    public void 探测到锁定时体现在概要和健康度里() throws Exception {
        prepare();
        Files.write(stateRoot.resolve("auto-sign-in/siemens-lock-abc123.json"), (
                "{\"status\":\"locked\","
                        + "\"checkedAt\":\"2026-11-27T12:00:00+08:00\","
                        + "\"firstSeenLocked\":\"2026-11-25T12:00:00+08:00\","
                        + "\"lastLockedAt\":\"2026-11-27T12:00:00+08:00\","
                        + "\"unlockedAt\":\"\","
                        + "\"history\":[{\"at\":\"2026-11-26T12:00:00+08:00\",\"status\":\"locked\"},"
                        + "{\"at\":\"2026-11-27T12:00:00+08:00\",\"status\":\"locked\"}]}")
                .getBytes(StandardCharsets.UTF_8));

        JSONObject summary = instanceSummary();
        assertTrue("概览页要能直接看到锁定", summary.getBool("siemensLocked"));
        JSONObject lock = summary.getJSONObject("siemens").getJSONObject("lock");
        assertTrue(lock.getBool("checked"));
        assertEquals("locked", lock.getStr("status"));
        assertTrue(lock.getStr("checkedAt").startsWith("2026-11-27"));
        assertTrue(lock.getStr("firstSeenLocked").startsWith("2026-11-25"));
        assertNotNull(lock.getLong("lockedDays"));
        assertEquals(2, lock.getJSONArray("history").size());
        assertTrue(summary.getJSONObject("health").getJSONArray("problems").contains("西门子账号被站点锁定"));
    }

    @Test
    public void 解锁后锁定天数按恢复日计算() throws Exception {
        prepare();
        Files.write(stateRoot.resolve("auto-sign-in/siemens-lock-abc123.json"), (
                "{\"status\":\"unlocked\","
                        + "\"checkedAt\":\"2026-12-04T12:00:00+08:00\","
                        + "\"firstSeenLocked\":\"2026-11-27T12:00:00+08:00\","
                        + "\"lastLockedAt\":\"2026-12-03T12:00:00+08:00\","
                        + "\"unlockedAt\":\"2026-12-04T12:00:00+08:00\"}")
                .getBytes(StandardCharsets.UTF_8));

        JSONObject summary = instanceSummary();
        assertFalse(summary.getBool("siemensLocked"));
        JSONObject lock = summary.getJSONObject("siemens").getJSONObject("lock");
        assertEquals("unlocked", lock.getStr("status"));
        assertEquals(7L, lock.getLong("lockedDays").longValue());
        assertFalse(summary.getJSONObject("health").getJSONArray("problems").contains("西门子账号被站点锁定"));
    }

    @Test
    public void 探测记录损坏时不显示成未锁定() throws Exception {
        prepare();
        Files.write(stateRoot.resolve("auto-sign-in/siemens-lock-abc123.json"),
                "{\"status\":\"locked\",".getBytes(StandardCharsets.UTF_8));

        JSONObject lock = instanceSummary().getJSONObject("siemens").getJSONObject("lock");
        // 解析失败不能给出「正常」的判断，只能标成不确定
        assertEquals("unknown", lock.getStr("status"));
        assertFalse(instanceSummary().getBool("siemensLocked"));
    }

    @Test
    public void 概览汇总锁定实例数() throws Exception {
        prepare();
        Files.createDirectories(stateRoot.resolve("auto-sign-in-fanfan"));
        DashboardState multi = new DashboardState(stateRoot,
                Arrays.asList("auto-sign-in", "auto-sign-in-fanfan"), configDir);
        Files.write(stateRoot.resolve("auto-sign-in/siemens-lock-a.json"),
                "{\"status\":\"locked\",\"checkedAt\":\"2026-11-27T12:00:00+08:00\"}"
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(stateRoot.resolve("auto-sign-in-fanfan/siemens-lock-b.json"),
                "{\"status\":\"unlocked\",\"checkedAt\":\"2026-11-27T12:00:00+08:00\"}"
                        .getBytes(StandardCharsets.UTF_8));

        JSONObject totals = multi.overview().getJSONObject("totals");
        assertEquals(1, totals.getInt("siemensLocked").intValue());
        assertEquals(2, totals.getInt("instances").intValue());
    }

    // ==================== 内容库 ====================

    @Test
    public void 内容库按状态汇总() throws Exception {
        prepare();
        String bank = "["
                + "{\"kind\":\"ask\",\"status\":\"ready\"},"
                + "{\"kind\":\"ask\",\"status\":\"ready\"},"
                + "{\"kind\":\"ask\",\"status\":\"used\"},"
                + "{\"kind\":\"story\",\"status\":\"rejected\"},"
                + "{\"kind\":\"post\",\"status\":\"ready\"}"
                + "]";
        Files.write(stateRoot.resolve("auto-sign-in/siemens-content-bank.json"),
                bank.getBytes(StandardCharsets.UTF_8));

        JSONObject content = instanceDetail().getJSONObject("contentBank");
        assertTrue(content.getBool("available"));
        assertEquals(3, content.getInt("ready").intValue());
        assertEquals(1, content.getInt("used").intValue());
        assertEquals(1, content.getInt("rejected").intValue());
        assertEquals(5, content.getInt("total").intValue());

        JSONArray rows = content.getJSONArray("rows");
        JSONObject ask = null;
        for (int index = 0; index < rows.size(); index++) {
            if ("ask".equals(rows.getJSONObject(index).getStr("kind"))) {
                ask = rows.getJSONObject(index);
            }
        }
        assertNotNull(ask);
        assertEquals(2, ask.getInt("ready").intValue());
        assertEquals(1, ask.getInt("used").intValue());
    }

    @Test
    public void 内容库损坏时不影响其它字段() throws Exception {
        prepare();
        Files.write(stateRoot.resolve("auto-sign-in/siemens-content-bank.json"),
                "not json".getBytes(StandardCharsets.UTF_8));
        Files.write(stateRoot.resolve("auto-sign-in/siemens-ran-abc.txt"),
                today().getBytes(StandardCharsets.UTF_8));

        JSONObject detail = instanceDetail();
        assertFalse(detail.getJSONObject("contentBank").getBool("available"));
        assertTrue(detail.getJSONObject("siemens").getBool("ranToday"));
    }

    // ==================== 观猹 ====================

    @Test
    public void 没有台账时视为零违规() throws Exception {
        prepare();
        JSONObject watcha = instanceSummary().getJSONObject("watcha");
        assertFalse(watcha.getBool("hasSession"));
        assertEquals(0, watcha.getInt("strikes").intValue());
        assertFalse(watcha.getBool("paused"));
    }

    @Test
    public void 台账两条违规即停写() throws Exception {
        prepare();
        Files.write(stateRoot.resolve("auto-sign-in/watcha-review-ledger.json"),
                ("{\"strikes\":2,\"published\":[{\"id\":1},{\"id\":2}]}").getBytes(StandardCharsets.UTF_8));

        JSONObject summary = instanceSummary();
        JSONObject watcha = summary.getJSONObject("watcha");
        assertEquals(2, watcha.getInt("strikes").intValue());
        assertEquals(2, watcha.getInt("publishedCount").intValue());
        assertTrue(watcha.getBool("paused"));
        // 停写必须体现在健康度里，否则概览页会漏报
        assertTrue(summary.getBool("paused"));
        assertTrue(summary.getJSONObject("health").getJSONArray("problems").contains("猹评已停写"));
    }

    @Test
    public void 台账读不出来时按停写显示() throws Exception {
        prepare();
        Files.write(stateRoot.resolve("auto-sign-in/watcha-review-ledger.json"),
                "{\"strikes\":0,\"published\":[".getBytes(StandardCharsets.UTF_8));

        JSONObject summary = instanceSummary();
        assertTrue(summary.getJSONObject("watcha").getBool("unreadable"));
        assertTrue("任务侧读不出台账会停写，看板不能显示成无违规", summary.getBool("paused"));
        assertTrue(summary.getJSONObject("health").getJSONArray("problems").contains("猹评已停写"));
    }

    @Test
    public void 会话文件只返回过期时间不回显令牌() throws Exception {
        prepare();
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"exp\":1795769734,\"uid\":123}".getBytes(StandardCharsets.UTF_8));
        String token = "header." + payload + ".signature";
        Files.write(stateRoot.resolve("auto-sign-in/watcha-session.json"),
                ("{\"access_token\":\"" + token + "\",\"refresh_token\":\"" + token
                        + "\",\"saved_at\":1795769734377}").getBytes(StandardCharsets.UTF_8));

        JSONObject watcha = instanceSummary().getJSONObject("watcha");
        assertTrue(watcha.getBool("hasSession"));
        assertFalse(watcha.toString().contains(token));
        assertFalse(watcha.toString().contains("signature"));
        assertTrue(watcha.getStr("accessExpiresAt").startsWith("2026-11-27"));
    }

    // ==================== 配置 ====================

    @Test
    public void 配置矩阵只输出有无不输出值() throws Exception {
        prepare();
        String secret = "super-secret-password";
        Files.write(configDir.resolve(".env"), Arrays.asList(
                        "MODB_USERNAME=demo-user",
                        "MODB_PASSWORD=" + secret,
                        "SIEMENS_ENABLED=false",
                        "# 注释行",
                        "",
                        "WATCHA_BROWSER=true").stream().collect(java.util.stream.Collectors.joining("\n"))
                .getBytes(StandardCharsets.UTF_8));

        JSONObject config = instanceDetail().getJSONObject("config");
        assertTrue(config.getBool("found"));
        assertFalse(config.toString().contains(secret));
        assertFalse(config.toString().contains("demo-user"));

        JSONArray groups = config.getJSONArray("groups");
        JSONObject modb = null;
        for (int index = 0; index < groups.size(); index++) {
            if ("墨天轮".equals(groups.getJSONObject(index).getStr("name"))) {
                modb = groups.getJSONObject(index);
            }
        }
        assertNotNull(modb);
        assertEquals(2, modb.getInt("present").intValue());
        assertEquals(2, modb.getInt("total").intValue());
    }

    @Test
    public void 开关空值显示与实际默认值一致() throws Exception {
        prepare();
        Files.write(configDir.resolve(".env"), (
                "SIEMENS_ENABLED=\n"
                        + "SIEMENS_DAILY_TASKS=\n"
                        + "WATCHA_PUBLISH_REVIEW=\n"
                        + "WATCHA_BROWSER=false\n")
                .getBytes(StandardCharsets.UTF_8));

        JSONArray switches = instanceDetail().getJSONObject("config").getJSONArray("switches");
        assertEquals("默认(开)", switchOf(switches, "SIEMENS_ENABLED"));
        // 真实站点上每日任务默认开启，不能显示成关闭。
        assertEquals("默认(开)", switchOf(switches, "SIEMENS_DAILY_TASKS"));
        assertEquals("默认(关)", switchOf(switches, "WATCHA_PUBLISH_REVIEW"));
        assertEquals("关", switchOf(switches, "WATCHA_BROWSER"));
    }

    @Test
    public void 配置对比多实例并排且不漏值() throws Exception {
        prepare();
        String secret = "another-secret";
        Files.createDirectories(stateRoot.resolve("auto-sign-in-fanfan"));
        Files.write(configDir.resolve(".env"), ("MODB_USERNAME=a\nMODB_PASSWORD=" + secret + "\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(configDir.resolve(".env.fanfan"), "MODB_USERNAME=b\n".getBytes(StandardCharsets.UTF_8));
        DashboardState multi = new DashboardState(stateRoot,
                Arrays.asList("auto-sign-in", "auto-sign-in-fanfan"), configDir);

        JSONObject compare = multi.configComparison();
        assertFalse(compare.toString().contains(secret));
        assertEquals(2, compare.getJSONArray("instances").size());

        JSONArray groupRows = compare.getJSONArray("groups");
        JSONObject modb = null;
        for (int index = 0; index < groupRows.size(); index++) {
            if ("墨天轮".equals(groupRows.getJSONObject(index).getStr("name"))) {
                modb = groupRows.getJSONObject(index);
            }
        }
        assertNotNull(modb);
        JSONArray cells = modb.getJSONArray("cells");
        assertEquals(2, cells.getJSONObject(0).getInt("present").intValue());
        // fanfan 只配了用户名，缺密码
        assertEquals(1, cells.getJSONObject(1).getInt("present").intValue());
    }

    private static String switchOf(JSONArray switches, String key) {
        for (int index = 0; index < switches.size(); index++) {
            JSONObject entry = switches.getJSONObject(index);
            if (key.equals(entry.getStr("key"))) {
                return entry.getStr("value");
            }
        }
        return null;
    }

    // ==================== 互评闭环 ====================

    @Test
    public void 互评闭环汇总账号与采纳() throws Exception {
        prepare();
        String pipeline = "{\"accounts\":[\"a\",\"b\",\"c\"],\"rounds\":["
                + "{\"askerUid\":\"a\",\"answer\":{\"uid\":\"b\"},\"adopted\":true,\"commentedBy\":[\"c\"]},"
                + "{\"askerUid\":\"b\",\"answer\":{\"uid\":\"c\"},\"adopted\":true},"
                + "{\"askerUid\":\"c\",\"answer\":{\"uid\":\"outsider\"}}"
                + "]}";
        Files.write(stateRoot.resolve("shared/siemens-pipeline.json"),
                pipeline.getBytes(StandardCharsets.UTF_8));

        JSONObject json = state.overview().getJSONObject("pipeline");
        assertTrue(json.getBool("available"));
        assertEquals(3, json.getInt("accounts").intValue());
        assertEquals(3, json.getInt("rounds").intValue());
        assertEquals(2, json.getInt("adopted").intValue());
        assertEquals(1, json.getInt("commented").intValue());
        assertEquals(1, json.getInt("externalAnswerers").intValue());

        JSONArray rows = json.getJSONArray("byAccount");
        JSONObject asker = rows.getJSONObject(0);
        assertEquals("a", asker.getStr("uid"));
        assertEquals(1, asker.getInt("asked").intValue());
        assertEquals(0, asker.getInt("answered").intValue());
        assertEquals(1, asker.getInt("gotAdopted").intValue());

        // 回答数记在回答者身上，不是提问者身上。
        JSONObject answerer = rows.getJSONObject(1);
        assertEquals("b", answerer.getStr("uid"));
        assertEquals(1, answerer.getInt("answered").intValue());
    }

    // ==================== 路由与实例校验 ====================

    @Test
    public void 状态目录不存在时实例标记为不可用() throws Exception {
        prepare();
        // 在白名单里但目录没建，属于「配置了但还没跑过」
        DashboardState withMissing = new DashboardState(stateRoot,
                Arrays.asList("auto-sign-in", "auto-sign-in-missing"), configDir);
        JSONObject instance = withMissing.instance("auto-sign-in-missing").summary();
        assertFalse(instance.getBool("available"));
        assertEquals("状态目录不存在", instance.getStr("reason"));
    }

    @Test
    public void 白名单外的实例名一律拒绝() throws Exception {
        prepare();
        // 不在白名单里，返回 null，避免拼出 state 目录外的路径
        assertNull(state.instance("../etc"));
        assertNull(state.instance("/etc/passwd"));
        assertNull(state.instance("unknown-instance"));
        assertNull(state.instanceDetail("../etc", 5, 1, 10));
        assertNull(state.run("../etc", "2026-11-27T09-11-02.json"));
    }

    // ==================== 概览 ====================

    @Test
    public void 概览汇总今日执行与异常数() throws Exception {
        prepare();
        Path dir = stateRoot.resolve("auto-sign-in");
        Files.createDirectories(dir.resolve("run-history"));
        Files.write(dir.resolve("siemens-ran-abc.txt"), today().getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("run-history/" + today() + "T09-11-02.json"),
                ("{\"startedAt\":\"" + today() + "T09:11:02+08:00\","
                        + "\"finishedAt\":\"" + today() + "T09:13:40+08:00\","
                        + "\"durationSeconds\":158,\"exceptionCount\":1,"
                        + "\"sections\":[{\"title\":\"西门子签到\",\"blocks\":[]}],\"console\":\"\"}")
                        .getBytes(StandardCharsets.UTF_8));

        JSONObject overview = state.overview();
        JSONObject totals = overview.getJSONObject("totals");
        assertEquals(1, totals.getInt("instances").intValue());
        assertEquals(1, totals.getInt("ranToday").intValue());
        assertEquals(1, totals.getInt("withTodayRecord").intValue());
        assertEquals(1, totals.getInt("exceptions").intValue());
        assertEquals(0, totals.getInt("paused").intValue());
        assertFalse(overview.getStr("today").isEmpty());
    }

    @Test
    public void 跨零点的记录按结束时间算今天() throws Exception {
        prepare();
        Path dir = stateRoot.resolve("auto-sign-in").resolve("run-history");
        Files.createDirectories(dir);
        LocalDate todayValue = LocalDate.now(SHANGHAI);
        String yesterday = todayValue.minusDays(1).toString();
        // 昨天 23:50 开始、今天 00:10 结束：刚跑完，必须算作今天的记录。
        Files.write(dir.resolve(todayValue + "T00-10-00.json"),
                ("{\"startedAt\":\"" + yesterday + "T23:50:00+08:00\","
                        + "\"finishedAt\":\"" + todayValue + "T00:10:00+08:00\","
                        + "\"durationSeconds\":1200,\"exceptionCount\":0,\"sections\":[],\"console\":\"\"}")
                        .getBytes(StandardCharsets.UTF_8));

        JSONObject lastRun = instanceSummary().getJSONObject("lastRun");
        assertTrue(lastRun.getBool("hasRecord"));
        assertTrue(lastRun.getBool("isToday"));
    }

    // ==================== 运行历史 ====================

    @Test
    public void 运行历史跨实例按时间倒序并支持只看失败() throws Exception {
        prepare();
        Files.createDirectories(stateRoot.resolve("auto-sign-in-fanfan"));
        DashboardState multi = new DashboardState(stateRoot,
                Arrays.asList("auto-sign-in", "auto-sign-in-fanfan"), configDir);

        writeRun(stateRoot.resolve("auto-sign-in"), "2026-11-25T09-00-00.json", 0, "西门子签到");
        writeRun(stateRoot.resolve("auto-sign-in"), "2026-11-27T09-00-00.json", 2, "西门子签到", "云桥签到");
        writeRun(stateRoot.resolve("auto-sign-in-fanfan"), "2026-11-26T09-00-00.json", 0, "西门子签到");

        JSONObject all = multi.runs(0, 10, false, "all");
        assertEquals(3, all.getInt("returned").intValue());
        JSONArray runs = all.getJSONArray("runs");
        // 最新在前
        assertTrue(runs.getJSONObject(0).getStr("finishedAt").startsWith("2026-11-27"));
        assertTrue(runs.getJSONObject(1).getStr("finishedAt").startsWith("2026-11-26"));
        assertEquals("auto-sign-in-fanfan", runs.getJSONObject(1).getStr("instance"));

        JSONObject failed = multi.runs(0, 10, true, "all");
        assertEquals(1, failed.getInt("returned").intValue());
        assertEquals(2, failed.getJSONArray("runs").getJSONObject(0).getInt("exceptionCount").intValue());

        JSONObject oneInstance = multi.runs(0, 10, false, "auto-sign-in-fanfan");
        assertEquals(1, oneInstance.getInt("returned").intValue());
    }

    @Test
    public void 运行历史分页不重复不遗漏() throws Exception {
        prepare();
        for (int day = 1; day <= 5; day++) {
            writeRun(stateRoot.resolve("auto-sign-in"),
                    String.format("2026-11-%02dT09-00-00.json", day), 0, "西门子签到");
        }
        JSONObject first = state.runs(0, 2, false, "all");
        JSONObject second = state.runs(2, 2, false, "all");
        assertEquals(2, first.getInt("returned").intValue());
        assertEquals(2, second.getInt("returned").intValue());
        // 第 1 页是 05、04，第 2 页是 03、02
        assertTrue(first.getJSONArray("runs").getJSONObject(0).getStr("finishedAt").startsWith("2026-11-05"));
        assertTrue(second.getJSONArray("runs").getJSONObject(0).getStr("finishedAt").startsWith("2026-11-03"));
    }

    @Test
    public void 单条运行详情包含分组结果() throws Exception {
        prepare();
        writeRun(stateRoot.resolve("auto-sign-in"), "2026-11-27T09-00-00.json", 1, "西门子签到", "云桥签到");

        JSONObject run = state.run("auto-sign-in", "2026-11-27T09-00-00.json");
        assertNotNull(run);
        assertEquals("auto-sign-in", run.getStr("instance"));
        assertEquals(2, run.getJSONArray("sections").size());
        assertEquals(1, run.getJSONObject("summary").getInt("exceptionCount").intValue());
        assertTrue(run.getStr("console").contains("签到成功"));
        // 目录穿越必须被挡住
        assertNull(state.run("auto-sign-in", "../../../etc/passwd"));
    }

    private static void writeRun(Path instanceDir, String file, int exceptions, String... titles) throws Exception {
        Path dir = instanceDir.resolve("run-history");
        Files.createDirectories(dir);
        StringBuilder sections = new StringBuilder();
        for (int index = 0; index < titles.length; index++) {
            if (index > 0) {
                sections.append(',');
            }
            sections.append("{\"title\":\"").append(titles[index])
                    .append("\",\"blocks\":[{\"lines\":[{\"parts\":[{\"text\":\"签到成功\",\"bold\":false}]}]}]}");
        }
        String iso = toIso(file);
        String json = "{\"startedAt\":\"" + iso + "\","
                + "\"finishedAt\":\"" + iso + "\","
                + "\"durationSeconds\":120,\"exceptionCount\":" + exceptions + ","
                + "\"console\":\"签到成功\",\"sections\":[" + sections + "]}";
        Files.write(dir.resolve(file), json.getBytes(StandardCharsets.UTF_8));
    }

    /** 把 yyyy-MM-ddTHH-mm-ss.json 转成 ISO 时间串。 */
    private static String toIso(String file) {
        String base = file.replace(".json", "");
        return base.substring(0, 10) + "T" + base.substring(11).replace("-", ":") + "+08:00";
    }

    // ==================== 日志 ====================

    @Test
    public void 日志尾部可在看板读取() throws Exception {
        prepare();
        Path logDir = stateRoot.resolve("auto-sign-in").resolve("logs");
        Files.createDirectories(logDir);
        StringBuilder content = new StringBuilder();
        for (int line = 0; line < 300; line++) {
            content.append("日志行 ").append(line).append('\n');
        }
        Files.write(logDir.resolve("2026-11-27.log"), content.toString().getBytes(StandardCharsets.UTF_8));

        JSONArray logs = instanceDetail().getJSONArray("logs");
        assertEquals(1, logs.size());
        JSONObject log = logs.getJSONObject(0);
        assertEquals("2026-11-27.log", log.getStr("file"));
        assertTrue(log.getLong("sizeBytes") > 0);
        assertTrue(log.getStr("tail").contains("日志行 299"));
        // 只取尾部，不能把整份日志塞进接口
        assertFalse(log.getStr("tail").contains("日志行 0\n"));
    }

    @Test
    public void 日志尾部滤掉浏览器解压噪声() throws Exception {
        prepare();
        Path logDir = stateRoot.resolve("auto-sign-in").resolve("logs");
        Files.createDirectories(logDir);
        Files.write(logDir.resolve("2026-11-27.log"), (
                "2026-11-27 23:47:49.787 [INFO]   inflating: chrome-linux64/locales/ta.pak\n"
                        + "2026-11-27 23:47:49.951 [INFO]    creating: chrome-linux64/resources/\n"
                        + "2026-11-27 23:48:00.000 [INFO] E: Unable to locate package libxcursor1\n"
                        + "2026-11-27 23:45:48.732 [INFO]  42  176M   42 75.2M    0     0   461k      0  0:06:32\n"
                        + "[永洪] 登录成功，已获取会话 Cookie\n"
                        + "[观猹] 签到结果：签到成功\n"
                        + "[通知] 邮件通知发送成功\n")
                .getBytes(StandardCharsets.UTF_8));

        String tail = instanceDetail().getJSONArray("logs").getJSONObject(0).getStr("tail");
        assertTrue(tail.contains("[观猹] 签到结果：签到成功"));
        assertTrue(tail.contains("[通知] 邮件通知发送成功"));
        assertFalse(tail.contains("inflating:"));
        assertFalse(tail.contains("Unable to locate package"));
        assertFalse(tail.contains("461k"));
    }

    // ==================== 环境文件解析 ====================

    @Test
    public void 环境文件解析处理引号与导出前缀() throws Exception {
        Path file = folder.newFile("sample.env").toPath();
        Files.write(file, Arrays.asList(
                        "PLAIN=value",
                        "QUOTED='single'",
                        "DOUBLE=\"double\"",
                        "export EXPORTED=exported-value",
                        "EMPTY=",
                        "# COMMENT=ignored",
                        "NOT_A_PAIR").stream().collect(java.util.stream.Collectors.joining("\n"))
                .getBytes(StandardCharsets.UTF_8));

        Map<String, String> values = ConfigMatrix.parseEnv(file);
        assertEquals("value", values.get("PLAIN"));
        assertEquals("single", values.get("QUOTED"));
        assertEquals("double", values.get("DOUBLE"));
        assertEquals("exported-value", values.get("EXPORTED"));
        assertEquals("", values.get("EMPTY"));
        assertFalse(values.containsKey("COMMENT"));
        assertFalse(values.containsKey("NOT_A_PAIR"));
    }

    @Test
    public void 空文件与不存在的文件都能安全解析() throws Exception {
        prepare();
        assertTrue(ConfigMatrix.parseEnv(null).isEmpty());
        assertTrue(ConfigMatrix.parseEnv(folder.getRoot().toPath().resolve("nope.env")).isEmpty());
    }
}
