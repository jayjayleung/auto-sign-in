package org.jayjay.autosignin;

import cn.hutool.core.util.StrUtil;
import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.NotificationSection;
import org.jayjay.autosignin.history.RunHistory;
import org.jayjay.autosignin.task.CheckInTask;
import org.jayjay.autosignin.util.NotificationSender;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

/**
 * 「没跑」检查：最近一次完成的运行太久以前，就单独推送一条提醒。
 * <p>
 * 站点失败会随签到结果一起推送，但进程崩溃、卡住不结束、定时任务没触发时什么都不会发出来。
 * 由容器内的第二条 cron 每天调用一次，只读运行记录，不访问任何站点。
 * 容器整个停掉时它也跟着停，那种情况只能靠外部监控发现。
 */
public final class StaleRunCheck {

    /** 超过多少小时没有完成的运行就提醒。 */
    public static final String HOURS_KEY = "STALE_CHECK_HOURS";
    static final long DEFAULT_HOURS = 26;
    static final String TITLE = "签到可能没有运行";

    private static final DateTimeFormatter TIME_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private StaleRunCheck() {
    }

    public static void main(String[] args) {
        if (!RunHistory.enabled()) {
            System.out.println("[运行检查] 运行记录已关闭，无法判断，跳过");
            return;
        }
        NotificationSection alert = check(CheckInTask.stateDir(), Instant.now(), hours(System.getenv(HOURS_KEY)));
        if (alert != null) {
            new NotificationSender().send(Collections.singletonList(alert), TITLE);
        }
    }

    /**
     * 判断是否需要提醒；需要时返回提醒内容，否则返回 null。
     */
    static NotificationSection check(Path stateDir, Instant now, long hours) {
        Instant last = RunHistory.latestFinishedAt(stateDir);
        if (last == null) {
            // 新部署还没跑完过一次，没有基准可比。
            System.out.println("[运行检查] 暂无运行记录，跳过");
            return null;
        }
        long elapsed = Duration.between(last, now).toHours();
        if (elapsed < hours) {
            System.out.println("[运行检查] 最近一次运行在 " + elapsed + " 小时前，正常");
            return null;
        }
        System.out.println("[运行检查] 已 " + elapsed + " 小时没有完成的运行，发送提醒");
        MessageBlock block = new MessageBlock()
                .addLine("最近一次完成运行：", TIME_TEXT.format(last), "（", String.valueOf(elapsed), " 小时前）")
                .addLine("可能原因：任务崩溃、卡住未结束，或定时任务没有触发")
                .addLine("排查：docker compose logs，以及 state/logs 下当天的日志");
        return new NotificationSection("运行检查", Collections.singletonList(block));
    }

    static long hours(String value) {
        try {
            long hours = Long.parseLong(StrUtil.trimToEmpty(value));
            return hours > 0 ? hours : DEFAULT_HOURS;
        } catch (NumberFormatException e) {
            return DEFAULT_HOURS;
        }
    }
}
