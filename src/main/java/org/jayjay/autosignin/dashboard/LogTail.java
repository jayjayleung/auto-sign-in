package org.jayjay.autosignin.dashboard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志尾部读取与噪声过滤。
 * <p>
 * 首次运行会解压 Chrome，日志里大半是 {@code inflating: xxx.pak}、依赖安装报错和下载进度条，
 * 不过滤的话看板上只能看到这些过程输出，真正的签到结果反而被淹没。
 */
final class LogTail {

    private LogTail() {
    }

    /**
     * 读文件末尾若干行（过滤噪声后）。
     */
    static String read(Path file, int lines) {
        List<String> kept = new ArrayList<String>();
        for (String line : StateFiles.lines(file)) {
            if (!isNoise(line)) {
                kept.add(line);
            }
        }
        int from = Math.max(0, kept.size() - Math.max(1, lines));
        return String.join("\n", kept.subList(from, kept.size()));
    }

    /** 浏览器解压、依赖安装、下载进度条之类的过程输出，对看板没有价值。 */
    static boolean isNoise(String line) {
        String text = line.trim();
        if (text.contains("[INFO]   inflating:")
                || text.contains("[INFO]  extracting:")
                || text.contains("[INFO]    creating:")
                || text.contains("[INFO] + ")
                || text.contains("has no installation candidate")
                || text.contains("Unable to locate package")
                || text.contains("is only available from another source")
                || text.contains("This may mean that the package is missing")) {
            return true;
        }
        // 进度条形如「 42  176M   42 75.2M    0     0   461k      0  0:06:32」
        return text.matches("^\\d+\\s+\\d+(\\.\\d+)?[KMG]?\\s+\\d+\\s+\\d+(\\.\\d+)?[KMG]?\\s+\\d+\\s+\\d+.*")
                || text.matches("^\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+ \\[INFO\\]\\s+\\d+\\s+\\d+[KMG]?.*");
    }
}
