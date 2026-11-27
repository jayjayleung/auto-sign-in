package org.jayjay.autosignin;


import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.NotificationSection;
import org.jayjay.autosignin.history.RunHistory;
import org.jayjay.autosignin.task.CheckInTask;
import org.jayjay.autosignin.task.MoDbCheckInTask;
import org.jayjay.autosignin.task.SiemensCheckInTask;
import org.jayjay.autosignin.task.TiDbCheckInTask;
import org.jayjay.autosignin.task.WatchaCheckInTask;
import org.jayjay.autosignin.task.YunqiaoCheckInTask;
import org.jayjay.autosignin.task.YongHoneCheckInTask;
import org.jayjay.autosignin.util.MessageRenderer;
import org.jayjay.autosignin.util.NotificationSender;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class MainApplication {


    public static void main(String[] args) {
        Instant startedAt = Instant.now();
        List<NotificationSection> sections = new ArrayList<>();
        // 各站点独立执行：任一站点抛异常只影响它自己，后面的站点照常跑，结果也照常落盘和推送。
        for (Supplier<CheckInTask> site : sites()) {
            sections.add(runSite(site));
        }
        System.out.println("================================================================================================================");
        System.out.print(MessageRenderer.toPlainText(sections));
        // 容器日志在重建后会清空，这里额外落一份结构化结果，供看板回看当天各站点的实际产出。
        // 记录放在通知之前：通知通道不可用时，看板上仍然能看到今天的真实结果。
        RunHistory.record(CheckInTask.stateDir(), sections, startedAt, Instant.now());
        new NotificationSender().send(sections);
    }

    private static List<Supplier<CheckInTask>> sites() {
        return Arrays.<Supplier<CheckInTask>>asList(
                MoDbCheckInTask::new,
                TiDbCheckInTask::new,
                YongHoneCheckInTask::new,
                YunqiaoCheckInTask::new,
                WatchaCheckInTask::new,
                SiemensCheckInTask::new);
    }

    /**
     * 执行单个站点并兜住异常。
     * <p>
     * 任务内部大多已自行处理失败，但入口处抛出的异常（配置错、依赖缺失、站点改版）会一路冒泡，
     * 那样后面的站点全都不会执行，运行记录和通知也一起丢掉。这里统一转成一条失败消息，
     * 让「一个站点出事不影响其他站点」在入口层真正成立。
     */
    static NotificationSection runSite(Supplier<CheckInTask> site) {
        CheckInTask task = null;
        try {
            task = site.get();
            return task.run().getNotificationSection();
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String title = titleOf(task);
            System.out.println("[" + title + "] 任务异常：" + reason);
            e.printStackTrace();
            return new NotificationSection(title,
                    Collections.singletonList(MessageBlock.text("签到失败：" + reason)));
        }
    }

    /**
     * 取任务的通知标题；构造阶段就失败时退化为通用标题。
     * 只对已有实例取标题，避免为了拿标题再构造一次任务。
     */
    private static String titleOf(CheckInTask task) {
        if (task == null) {
            return "签到站点";
        }
        try {
            String title = task.buildNotificationSection().getTitle();
            return title == null || title.isEmpty() ? "签到站点" : title;
        } catch (Exception e) {
            return "签到站点";
        }
    }
}
