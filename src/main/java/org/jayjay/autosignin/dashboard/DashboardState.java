package org.jayjay.autosignin.dashboard;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.jayjay.autosignin.history.RunHistory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 看板的数据入口：把多个实例的只读聚合拼成各页面需要的形状。
 * <p>
 * 每个页面只要自己那一份数据，所以按用途分成多个方法，
 * 而不是一次返回一个大 JSON——实例变多以后全量下发会成为瓶颈。
 */
final class DashboardState {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final Path stateRoot;
    private final List<String> instances;
    private final Path configDir;
    private final ConfigMatrix config;

    DashboardState(Path stateRoot, List<String> instances, Path configDir) {
        this.stateRoot = stateRoot;
        this.instances = instances == null ? new ArrayList<String>() : instances;
        this.configDir = configDir;
        this.config = new ConfigMatrix(configDir);
    }

    /** 实例名列表，顺序即页面上的顺序。 */
    List<String> instanceNames() {
        return Collections.unmodifiableList(instances);
    }

    /** 按名字取实例；名字不在白名单里返回 null，避免用路径穿出 state 目录。 */
    InstanceState instance(String name) {
        if (name == null || !instances.contains(name)) {
            return null;
        }
        Path dir = stateRoot == null ? null : stateRoot.resolve(name);
        return new InstanceState(name, dir, configDir);
    }

    /**
     * 概览页：实例摘要列表 + 汇总计数 + 共享的互评闭环。
     * 不含历史明细和日志，首屏只需要这些。
     */
    JSONObject overview() {
        JSONObject root = new JSONObject();
        root.set("generatedAt", RunHistory.displayTime(Instant.now().toString()));
        root.set("today", LocalDate.now(SHANGHAI).toString());
        JSONArray list = new JSONArray();
        int ranToday = 0;
        int withTodayRecord = 0;
        int exceptions = 0;
        int paused = 0;
        for (String name : instances) {
            JSONObject summary = instance(name).summary();
            list.add(summary);
            if (summary.getBool("ranToday", false)) {
                ranToday++;
            }
            JSONObject lastRun = summary.getJSONObject("lastRun");
            if (lastRun != null && lastRun.getBool("isToday", false)) {
                withTodayRecord++;
                exceptions += lastRun.getInt("exceptionCount", 0);
            }
            if (summary.getBool("paused", false)) {
                paused++;
            }
        }
        root.set("instances", list);
        root.set("totals", new JSONObject()
                .set("instances", instances.size())
                .set("ranToday", ranToday)
                .set("withTodayRecord", withTodayRecord)
                .set("exceptions", exceptions)
                .set("paused", paused));
        root.set("pipeline", pipeline());
        return root;
    }

    /** 实例列表页：只有一行摘要，不含详情页的重字段。 */
    JSONObject instanceList() {
        JSONArray list = new JSONArray();
        for (String name : instances) {
            list.add(instance(name).summary());
        }
        return new JSONObject().set("instances", list).set("total", list.size());
    }

    /** 实例详情页；实例名不合法时返回 null。 */
    JSONObject instanceDetail(String name, int historyLimit, int logLimit, int logTailLines) {
        InstanceState state = instance(name);
        return state == null ? null : state.detail(historyLimit, logLimit, logTailLines);
    }

    /**
     * 运行历史列表页：跨实例汇总，按时间倒序，支持「只看失败」和按实例筛选。
     * <p>
     * 先只看文件名（便宜）算出顺序，命中当前页范围才解析文件内容，
     * 否则翻到第 3 页也要把所有记录读一遍。
     */
    JSONObject runs(int offset, int limit, boolean onlyFailed, String instanceFilter) {
        List<String> targets = StrUtil.isBlank(instanceFilter) || "all".equals(instanceFilter)
                ? instances : Collections.singletonList(instanceFilter);

        List<Row> all = new ArrayList<Row>();
        for (String name : targets) {
            InstanceState state = instance(name);
            if (state == null) {
                continue;
            }
            for (String file : RunHistory.list(state.dir())) {
                all.add(new Row(name, file));
            }
        }
        // 文件名是 yyyy-MM-ddTHH-mm-ss，倒序即时间倒序；同一秒的多实例按名字稳定排序。
        Collections.sort(all, (left, right) -> {
            int byFile = right.file.compareTo(left.file);
            return byFile != 0 ? byFile : left.instance.compareTo(right.instance);
        });

        int safeLimit = Math.max(1, limit);
        JSONArray rows = new JSONArray();
        int skipped = 0;
        for (Row row : all) {
            if (rows.size() >= safeLimit) {
                break;
            }
            InstanceState state = instance(row.instance);
            JSONObject record = RunHistory.readOne(state.dir(), row.file);
            if (record == null) {
                continue;
            }
            JSONObject summary = state.runSummary(record);
            if (onlyFailed && summary.getInt("exceptionCount", 0) == 0) {
                continue;
            }
            if (skipped < Math.max(0, offset)) {
                skipped++;
                continue;
            }
            rows.add(summary);
        }
        return new JSONObject()
                .set("runs", rows)
                .set("offset", Math.max(0, offset))
                .set("limit", safeLimit)
                .set("onlyFailed", onlyFailed)
                .set("instance", StrUtil.blankToDefault(instanceFilter, "all"))
                .set("returned", rows.size());
    }

    /** 单条运行详情：完整的分组结果；实例或文件不合法时返回 null。 */
    JSONObject run(String instanceName, String file) {
        InstanceState state = instance(instanceName);
        if (state == null) {
            return null;
        }
        JSONObject record = RunHistory.readOne(state.dir(), file);
        if (record == null) {
            return null;
        }
        return new JSONObject()
                .set("instance", instanceName)
                .set("summary", state.runSummary(record))
                .set("sections", record.getJSONArray("sections"))
                .set("console", record.getStr("console"));
    }

    /** 配置页：多实例并排对比。 */
    JSONObject configComparison() {
        return config.compare(instances);
    }

    /** 互评闭环，三个实例共用同一份轮次文件。 */
    JSONObject pipeline() {
        JSONObject json = new JSONObject();
        Path file = stateRoot == null ? null : stateRoot.resolve("shared").resolve("siemens-pipeline.json");
        if (!StateFiles.exists(file)) {
            json.set("available", false);
            return json;
        }
        try {
            JSONObject data = JSONUtil.parseObj(StateFiles.text(file));
            JSONArray accounts = data.getJSONArray("accounts");
            JSONArray rounds = data.getJSONArray("rounds");
            json.set("available", true);
            json.set("accounts", accounts == null ? 0 : accounts.size());
            json.set("rounds", rounds == null ? 0 : rounds.size());
            int adopted = 0;
            int commented = 0;
            Set<String> externalAnswerers = new LinkedHashSet<String>();
            Map<String, int[]> perAccount = new LinkedHashMap<String, int[]>();
            if (accounts != null) {
                for (int index = 0; index < accounts.size(); index++) {
                    perAccount.put(accounts.getStr(index), new int[3]);
                }
            }
            if (rounds != null) {
                for (int index = 0; index < rounds.size(); index++) {
                    JSONObject round = rounds.getJSONObject(index);
                    if (round == null) {
                        continue;
                    }
                    boolean isAdopted = round.getBool("adopted", false);
                    if (isAdopted) {
                        adopted++;
                    }
                    JSONArray commenters = round.getJSONArray("commentedBy");
                    if (commenters != null && !commenters.isEmpty()) {
                        commented++;
                    }
                    count(perAccount, round.getStr("askerUid"), 0);
                    JSONObject answer = round.getJSONObject("answer");
                    if (answer != null) {
                        String uid = answer.getStr("uid");
                        if (perAccount.containsKey(uid)) {
                            count(perAccount, uid, 1);
                        } else if (StrUtil.isNotBlank(uid)) {
                            externalAnswerers.add(uid);
                        }
                    }
                    if (isAdopted) {
                        count(perAccount, round.getStr("askerUid"), 2);
                    }
                }
            }
            json.set("adopted", adopted);
            json.set("commented", commented);
            json.set("externalAnswerers", externalAnswerers.size());
            JSONArray rows = new JSONArray();
            for (Map.Entry<String, int[]> entry : perAccount.entrySet()) {
                rows.add(new JSONObject().set("uid", entry.getKey())
                        .set("asked", entry.getValue()[0])
                        .set("answered", entry.getValue()[1])
                        .set("gotAdopted", entry.getValue()[2]));
            }
            json.set("byAccount", rows);
            json.set("updatedAt", StateFiles.modifiedText(file));
        } catch (Exception e) {
            json.set("available", false);
            json.set("error", "轮次文件解析失败");
        }
        return json;
    }

    private static void count(Map<String, int[]> perAccount, String uid, int position) {
        if (StrUtil.isBlank(uid)) {
            return;
        }
        int[] counts = perAccount.get(uid);
        if (counts != null) {
            counts[position]++;
        }
    }

    /** 排序用的中间结构：实例名 + 记录文件名。 */
    private static final class Row {
        private final String instance;
        private final String file;

        private Row(String instance, String file) {
            this.instance = instance;
            this.file = file;
        }
    }
}
