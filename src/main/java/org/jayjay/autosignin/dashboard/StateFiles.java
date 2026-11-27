package org.jayjay.autosignin.dashboard;

import cn.hutool.core.util.StrUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 读 state 目录的通用文件工具。
 * <p>
 * 抽出来的原因是好几个聚合类都要「按前缀找最新文件」「按行读第一个非空值」，
 * 散在各处容易写出不一致的容错行为。
 */
final class StateFiles {

    private StateFiles() {
    }

    /**
     * 按文件名前缀找最新的那个文件。多个实例状态目录里同一类文件可能带账号哈希后缀，
     * 取最新的是判断「当前生效的那份」最稳的办法。
     */
    static Path latest(Path dir, String prefix) {
        Path latest = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + "*")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                if (latest == null || modifiedMillis(file) > modifiedMillis(latest)) {
                    latest = file;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return latest;
    }

    /** 读同名前缀里最新的文件，取第一行非空文本。 */
    static String latestContent(Path dir, String prefix) {
        return firstNonBlankLine(latest(dir, prefix));
    }

    /** 读文件的第一个非空行，文件不可读时返回空串。 */
    static String firstNonBlankLine(Path file) {
        for (String line : lines(file)) {
            if (StrUtil.isNotBlank(line)) {
                return line.trim();
            }
        }
        return "";
    }

    /** 按行读文件，失败时返回空列表而不是抛异常。 */
    static List<String> lines(Path file) {
        if (file == null) {
            return new ArrayList<String>();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new ArrayList<String>();
        }
    }

    /** 非空行数，用于统计去重台账这类一行一条的文件。 */
    static int countNonBlankLines(Path file) {
        if (file == null) {
            return -1;
        }
        int count = 0;
        for (String line : lines(file)) {
            if (StrUtil.isNotBlank(line)) {
                count++;
            }
        }
        return count;
    }

    /** 读整个文件为字符串，失败返回空串。 */
    static String text(Path file) {
        if (file == null) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static boolean exists(Path file) {
        return file != null && Files.isRegularFile(file);
    }

    static long size(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return 0L;
        }
    }

    static long modifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 展示用的本地时间文本，文件不可读时返回空串。 */
    static String modifiedText(Path file) {
        long millis = modifiedMillis(file);
        return millis == 0L ? "" : org.jayjay.autosignin.history.RunHistory
                .displayTime(Instant.ofEpochMilli(millis).toString());
    }
}
