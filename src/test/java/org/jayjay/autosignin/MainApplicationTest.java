package org.jayjay.autosignin;

import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.NotificationSection;
import org.jayjay.autosignin.task.CheckInTask;
import org.jayjay.autosignin.task.MoDbCheckInTask;
import org.jayjay.autosignin.task.SiemensCheckInTask;
import org.junit.Test;

import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 入口层的站点隔离：一个站点抛异常不能影响后面的站点。
 */
public class MainApplicationTest {

    /** 只会抛异常的假任务，模拟配置错或站点改版。 */
    private static final class BoomTask extends MoDbCheckInTask {
        @Override
        public CheckInTask run() {
            throw new IllegalStateException("模拟站点炸了");
        }
    }

    /** 构造阶段就失败的任务，标题也得能兜底。 */
    private static final class BoomOnCreate extends SiemensCheckInTask {
        private BoomOnCreate() {
            throw new IllegalStateException("模拟构造失败");
        }
    }

    @Test
    public void 任务抛异常时转成失败消息而不是冒泡() {
        NotificationSection section = MainApplication.runSite(BoomTask::new);

        assertTrue(section.hasContent());
        assertEquals("墨天轮签到", section.getTitle());
        assertTrue(section.getBlocks().get(0).toPlainText().contains("签到失败"));
        assertTrue(section.getBlocks().get(0).toPlainText().contains("模拟站点炸了"));
    }

    @Test
    public void 构造失败时用通用标题兜底() {
        Supplier<CheckInTask> broken = () -> {
            throw new IllegalStateException("模拟构造失败");
        };
        NotificationSection section = MainApplication.runSite(broken);

        assertTrue(section.hasContent());
        assertEquals("签到站点", section.getTitle());
    }

    @Test
    public void 正常任务原样返回自己的结果() {
        NotificationSection section = MainApplication.runSite(MoDbCheckInTask::new);

        // 没配置账号时任务自身会跳过并返回空分组，不该被包装成失败消息。
        assertFalse(section.hasContent());
        assertTrue(section.getBlocks() == null || section.getBlocks().isEmpty());
    }
}
