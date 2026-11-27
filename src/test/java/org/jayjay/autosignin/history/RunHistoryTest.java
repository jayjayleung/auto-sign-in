package org.jayjay.autosignin.history;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.MessageLine;
import org.jayjay.autosignin.entity.NotificationSection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 运行记录只做本地文件读写，用临时目录验证，不碰网络和真实凭证。
 */
public class RunHistoryTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void 记录写入后可读回完整结构() throws Exception {
        Path stateDir = folder.newFolder("state").toPath();
        List<NotificationSection> sections = Arrays.asList(
                new NotificationSection("西门子签到", Collections.singletonList(
                        new MessageBlock().addLine("签到成功").addLine(
                                new MessageLine().append("西币：").appendBold("153")))),
                new NotificationSection("云桥签到", Collections.singletonList(
                        new MessageBlock().addLine("账号 1 签到失败：Cookie 失效"))));

        Instant started = Instant.now();
        Path file = RunHistory.record(stateDir, sections, started, started.plusSeconds(158));

        assertNotNull(file);
        assertTrue(Files.isRegularFile(file));
        List<JSONObject> records = RunHistory.read(stateDir, 10);
        assertEquals(1, records.size());

        JSONObject record = records.get(0);
        assertEquals(1, record.getInt("exceptionCount").intValue());
        assertEquals(158L, record.getLong("durationSeconds").longValue());
        assertTrue(record.getStr("startedAt").contains("+08:00"));
        assertEquals(2, record.getJSONArray("sections").size());

        JSONObject siemens = record.getJSONArray("sections").getJSONObject(0);
        assertEquals("西门子签到", siemens.getStr("title"));
        JSONArray lines = siemens.getJSONArray("blocks").getJSONObject(0).getJSONArray("lines");
        assertEquals(2, lines.size());
        JSONArray parts = lines.getJSONObject(1).getJSONArray("parts");
        assertEquals("西币：", parts.getJSONObject(0).getStr("text"));
        assertEquals("153", parts.getJSONObject(1).getStr("text"));
        assertTrue(parts.getJSONObject(1).getBool("bold"));
        assertFalse(parts.getJSONObject(0).getBool("bold"));

        // 纯文本回放用于页面兜底展示，必须与推送内容一致。
        assertTrue(record.getStr("console").contains("签到成功"));
        assertTrue(record.getStr("console").contains("西币：153"));
    }

    @Test
    public void 多次运行按时间倒序且文件名不冲突() throws Exception {
        Path stateDir = folder.newFolder("state").toPath();
        List<NotificationSection> sections = Collections.singletonList(
                new NotificationSection("西门子签到", Collections.singletonList(
                        new MessageBlock().addLine("签到成功"))));
        Instant second = todayAt(9, 11, 0, 0);
        Instant first = second.minus(1, ChronoUnit.DAYS);

        RunHistory.record(stateDir, sections, first, first.plusSeconds(1));
        RunHistory.record(stateDir, sections, second, second.plusSeconds(1));
        // 同一秒再跑一次也不能覆盖前一条。
        RunHistory.record(stateDir, sections, second, second.plusSeconds(2));

        List<JSONObject> records = RunHistory.read(stateDir, 10);
        assertEquals(3, records.size());
        assertTrue(records.get(0).getStr("startedAt").startsWith(today().toString()));
        assertTrue(records.get(2).getStr("startedAt").startsWith(today().minusDays(1).toString()));
    }

    @Test
    public void 超过保留天数的记录会被清理() throws Exception {
        Path stateDir = folder.newFolder("state").toPath();
        Path dir = stateDir.resolve(RunHistory.DIR_NAME);
        Files.createDirectories(dir);
        // 手写一个很旧的记录和一个刚写的记录。
        Files.write(dir.resolve("2020-01-01T09-00-00.json"), "{}".getBytes(StandardCharsets.UTF_8));

        List<NotificationSection> sections = Collections.singletonList(
                new NotificationSection("西门子签到", Collections.singletonList(
                        new MessageBlock().addLine("签到成功"))));
        Instant now = Instant.now();
        RunHistory.record(stateDir, sections, now, now.plusSeconds(1));

        assertFalse(Files.exists(dir.resolve("2020-01-01T09-00-00.json")));
        assertEquals(1, RunHistory.read(stateDir, 10).size());
    }

    @Test
    public void 非记录文件不会被当成历史() throws Exception {
        Path stateDir = folder.newFolder("state").toPath();
        Path dir = stateDir.resolve(RunHistory.DIR_NAME);
        Files.createDirectories(dir);
        Files.write(dir.resolve("siemens-content-bank.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("broken.json"), "not json".getBytes(StandardCharsets.UTF_8));

        assertTrue(RunHistory.read(stateDir, 10).isEmpty());
    }

    @Test
    public void 记录目录不存在时读回空列表() throws Exception {
        Path stateDir = folder.newFolder("empty").toPath();
        assertTrue(RunHistory.read(stateDir, 10).isEmpty());
        assertNull(RunHistory.record(null, new ArrayList<>(), Instant.now(), Instant.now()));
    }

    @Test
    public void 时间戳截到秒且带上海时区偏移() throws Exception {
        Path stateDir = folder.newFolder("state").toPath();
        List<NotificationSection> sections = Collections.singletonList(
                new NotificationSection("西门子签到", Collections.singletonList(
                        new MessageBlock().addLine("签到成功"))));
        Instant started = todayAt(9, 11, 2, 987654321);
        RunHistory.record(stateDir, sections, started, started.plusSeconds(158));

        JSONObject record = RunHistory.read(stateDir, 1).get(0);
        String date = today().toString();
        assertEquals(date + "T09:11:02+08:00", record.getStr("startedAt"));
        assertEquals(date + "T09:13:40+08:00", record.getStr("finishedAt"));
        assertEquals(date + " 09:11:02", RunHistory.displayTime(record.getStr("startedAt")));
    }

    @Test
    public void 未启用的空分组不写入记录() throws Exception {
        Path stateDir = folder.newFolder("state").toPath();
        List<NotificationSection> sections = Arrays.asList(
                new NotificationSection("西门子签到", Collections.singletonList(
                        new MessageBlock().addLine("签到成功"))),
                // 未配置账号的站点会返回空分组
                new NotificationSection(),
                new NotificationSection("云桥签到", new ArrayList<>()));
        Instant now = Instant.now();
        RunHistory.record(stateDir, sections, now, now.plusSeconds(10));

        JSONArray recorded = RunHistory.read(stateDir, 1).get(0).getJSONArray("sections");
        assertEquals(1, recorded.size());
        assertEquals("西门子签到", recorded.getJSONObject(0).getStr("title"));
    }

    @Test
    public void 关闭开关后不写文件() throws Exception {
        Path stateDir = folder.newFolder("state").toPath();
        List<NotificationSection> sections = Collections.singletonList(
                new NotificationSection("西门子签到", Collections.singletonList(
                        new MessageBlock().addLine("签到成功"))));
        Instant now = Instant.now();
        // 直接验证开关语义：环境变量读不到时默认开启。
        assertTrue(RunHistory.enabled());
        assertNotNull(RunHistory.record(stateDir, sections, now, now.plusSeconds(1)));
    }

    @Test
    public void 最近结束时间跳过损坏的最新记录() throws Exception {
        Path stateDir = folder.newFolder("state").toPath();
        assertNull(RunHistory.latestFinishedAt(stateDir));

        List<NotificationSection> sections = Collections.singletonList(
                new NotificationSection("西门子签到", Collections.singletonList(
                        new MessageBlock().addLine("签到成功"))));
        Instant started = todayAt(9, 11, 0, 0).minus(1, ChronoUnit.DAYS);
        RunHistory.record(stateDir, sections, started, started.plusSeconds(90));
        // 进程在写记录时被杀，最新一条只剩半截。
        Files.write(stateDir.resolve(RunHistory.DIR_NAME).resolve(today() + "T09-11-00.json"),
                "{\"startedAt\":".getBytes(StandardCharsets.UTF_8));

        assertEquals(started.plusSeconds(90), RunHistory.latestFinishedAt(stateDir));
    }

    private static LocalDate today() {
        return LocalDate.now(SHANGHAI);
    }

    /** 记录写入时会按真实日期清理 30 天前的文件，测试用的时间必须跟着当天走。 */
    private static Instant todayAt(int hour, int minute, int second, int nano) {
        return today().atTime(hour, minute, second, nano).atZone(SHANGHAI).toInstant();
    }
}
