package org.jayjay.autosignin.history;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.MessageLine;
import org.jayjay.autosignin.entity.NotificationSection;
import org.jayjay.autosignin.util.MessageRenderer;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 每次运行的落盘记录。
 * <p>
 * 容器日志在重建后会清空，而「今天到底签到成没成功、拿到多少积分」恰恰是最需要回看的东西，
 * 所以把与推送内容完全一致的通知结构额外写一份到 state 目录，供看板读取。
 * 写入失败只打日志，不影响签到和通知本身。
 */
public final class RunHistory {

    /** 运行记录目录名，按实例分开，落在各自的 state 目录下。 */
    public static final String DIR_NAME = "run-history";

    /** 关闭运行记录的环境变量，默认开启。 */
    public static final String ENABLED_KEY = "RUN_HISTORY_ENABLED";

    /** 保留天数，超过的在每次写入后清理。 */
    static final int KEEP_DAYS = 30;

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 每次运行写一份状态目录下的日志文件，容器重建后仍可从宿主机查看。 */
    public static final String LOG_DIR_NAME = "logs";
    private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");
    private static final DateTimeFormatter TIME_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private RunHistory() {
    }

    /**
     * 是否写入运行记录。留空表示开启，只有显式写成 false 才关闭。
     */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(StrUtil.trimToEmpty(System.getenv(ENABLED_KEY)));
    }

    /**
     * 把本次运行结果写入 {@code <stateDir>/run-history/}，返回写入的文件；失败返回 null。
     */
    public static Path record(Path stateDir, List<NotificationSection> sections,
                              Instant startedAt, Instant finishedAt) {
        if (stateDir == null || !enabled()) {
            return null;
        }
        try {
            Path dir = stateDir.resolve(DIR_NAME);
            Files.createDirectories(dir);
            Path target = uniqueFile(dir, startedAt.atZone(SHANGHAI));
            Files.write(target, toJson(sections, startedAt, finishedAt).toString().getBytes(StandardCharsets.UTF_8));
            prune(dir);
            return target;
        } catch (Exception e) {
            System.out.println("[运行记录] 写入失败：" + StrUtil.maxLength(String.valueOf(e.getMessage()), 120));
            return null;
        }
    }

    /**
     * 读取最近的运行记录，按时间倒序。文件损坏或不是记录文件时跳过，不抛异常。
     */
    public static List<JSONObject> read(Path stateDir, int limit) {
        List<JSONObject> records = new ArrayList<JSONObject>();
        for (String name : list(stateDir)) {
            if (records.size() >= limit) {
                break;
            }
            JSONObject record = readOne(stateDir, name);
            if (record != null) {
                records.add(record);
            }
        }
        return records;
    }

    /**
     * 所有运行记录的文件名，按时间倒序（最新在前）。
     * 列表页需要分页时才读每个文件，避免为了拿元数据把全部记录都解析一遍。
     */
    public static List<String> list(Path stateDir) {
        List<String> names = new ArrayList<String>();
        if (stateDir == null) {
            return names;
        }
        Path dir = stateDir.resolve(DIR_NAME);
        if (!Files.isDirectory(dir)) {
            return names;
        }
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file) && isRecordName(file)) {
                    files.add(file);
                }
            }
        } catch (Exception e) {
            return names;
        }
        // 文件名本身就是可排序的时间，比读 mtime 更稳（复制、恢复都不会打乱顺序）。
        Collections.sort(files, Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
        for (Path path : files) {
            names.add(path.getFileName().toString());
        }
        return names;
    }

    /**
     * 按文件名读单条记录；文件不存在或损坏时返回 null。
     * 文件名由 {@link #list(Path)} 给出，这里只做拼路径，不接受任意路径，避免目录穿越。
     */
    public static JSONObject readOne(Path stateDir, String fileName) {
        if (stateDir == null || StrUtil.isBlank(fileName) || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..")) {
            return null;
        }
        Path file = stateDir.resolve(DIR_NAME).resolve(fileName);
        if (!Files.isRegularFile(file) || !isRecordName(file)) {
            return null;
        }
        return readFile(file);
    }

    private static JSONObject readFile(Path file) {
        try {
            JSONObject record = JSONUtil.parseObj(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            record.set("file", file.getFileName().toString());
            return record;
        } catch (Exception e) {
            // 半条记录不值得让整个看板失败。
            return null;
        }
    }

    /**
     * 组装记录结构。手写映射而不是直接序列化实体，保证读回时不需要再反序列化成实体。
     */
    static JSONObject toJson(List<NotificationSection> sections, Instant startedAt, Instant finishedAt) {
        JSONObject record = new JSONObject();
        record.set("startedAt", iso(startedAt));
        record.set("finishedAt", iso(finishedAt));
        record.set("durationSeconds", Duration.between(startedAt, finishedAt).getSeconds());
        record.set("exceptionCount", exceptionCount(sections));
        record.set("console", MessageRenderer.toPlainText(sections == null ? new ArrayList<NotificationSection>() : sections));
        JSONArray sectionArray = new JSONArray();
        if (sections != null) {
            for (NotificationSection section : sections) {
                // 未启用的站点会返回空分组，写进记录只是噪声，看板也不该为它单开一块。
                if (section == null || !section.hasContent()) {
                    continue;
                }
                sectionArray.add(toJson(section));
            }
        }
        record.set("sections", sectionArray);
        return record;
    }

    private static JSONObject toJson(NotificationSection section) {
        JSONObject json = new JSONObject();
        if (section == null) {
            json.set("title", "");
            json.set("blocks", new JSONArray());
            return json;
        }
        json.set("title", StrUtil.nullToEmpty(section.getTitle()));
        JSONArray blocks = new JSONArray();
        if (section.getBlocks() != null) {
            for (MessageBlock block : section.getBlocks()) {
                if (block == null || block.isBlank()) {
                    continue;
                }
                JSONArray lines = new JSONArray();
                for (MessageLine line : block.getLines()) {
                    JSONArray parts = new JSONArray();
                    for (MessageLine.Part part : line.getParts()) {
                        parts.add(new JSONObject().set("text", part.getText()).set("bold", part.isBold()));
                    }
                    lines.add(new JSONObject().set("parts", parts));
                }
                blocks.add(new JSONObject().set("lines", lines));
            }
        }
        json.set("blocks", blocks);
        return json;
    }

    /** 与通知标题的判定保持一致：含「失败」的分组记一次异常。 */
    private static int exceptionCount(List<NotificationSection> sections) {
        if (sections == null) {
            return 0;
        }
        int failed = 0;
        for (NotificationSection section : sections) {
            if (section == null || !section.hasContent()) {
                continue;
            }
            for (MessageBlock block : section.getBlocks()) {
                String text = block == null ? "" : block.toPlainText();
                if (text.contains("失败")) {
                    failed++;
                    break;
                }
            }
        }
        return failed;
    }

    private static Path uniqueFile(Path dir, ZonedDateTime time) {
        String base = FILE_NAME.format(time);
        Path candidate = dir.resolve(base + ".json");
        int index = 2;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + "-" + index + ".json");
            index++;
        }
        return candidate;
    }

    private static void prune(Path dir) {
        LocalDate cutoff = LocalDate.now(SHANGHAI).minusDays(KEEP_DAYS);
        List<Path> stale = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                String date = datePart(file);
                if (date == null) {
                    continue;
                }
                try {
                    if (LocalDate.parse(date).isBefore(cutoff)) {
                        stale.add(file);
                    }
                } catch (DateTimeParseException ignored) {
                    // 不是本程序写的文件，不动它。
                }
            }
        } catch (Exception e) {
            return;
        }
        for (Path file : stale) {
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
                // 清理失败不影响本次记录。
            }
        }
    }

    /**
     * 判断是否为运行记录文件：文件名是 {@code yyyy-MM-ddTHH-mm-ss[-n].json}。
     * 只看前缀日期不够，{@code 2026-11-27T09-11-02.json} 之后还可能带 -2 去重后缀。
     */
    static boolean isRecordName(Path file) {
        return datePart(file) != null;
    }

    /** 取出文件名里的日期部分；不是记录文件时返回 null。 */
    private static String datePart(Path file) {
        String name = file.getFileName().toString();
        if (name.length() < 21 || !name.endsWith(".json") || name.charAt(10) != 'T') {
            return null;
        }
        String date = name.substring(0, 10);
        for (int index = 0; index < 10; index++) {
            char character = date.charAt(index);
            if (index == 4 || index == 7) {
                if (character != '-') {
                    return null;
                }
            } else if (!Character.isDigit(character)) {
                return null;
            }
        }
        return date;
    }

    /** 统一用上海时区的偏移量记时间，跨时区打开记录也不会错位；截到秒，避免纳秒噪声。 */
    static String iso(Instant instant) {
        return instant.atZone(SHANGHAI).toOffsetDateTime()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    /** 供看板展示的本地时间文本。 */
    public static String displayTime(String isoTimestamp) {
        if (StrUtil.isBlank(isoTimestamp)) {
            return "";
        }
        try {
            return TIME_TEXT.format(java.time.OffsetDateTime.parse(isoTimestamp.trim()).atZoneSameInstant(SHANGHAI));
        } catch (DateTimeParseException e) {
            return isoTimestamp;
        }
    }
}
