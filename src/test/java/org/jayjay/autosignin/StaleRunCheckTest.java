package org.jayjay.autosignin;

import org.jayjay.autosignin.entity.MessageBlock;
import org.jayjay.autosignin.entity.NotificationSection;
import org.jayjay.autosignin.history.RunHistory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 只读本地运行记录做判断，不发通知、不碰网络。
 */
public class StaleRunCheckTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void 还没有运行记录时不提醒() throws Exception {
        Path stateDir = folder.newFolder("state").toPath();
        assertNull("新部署还没跑过，没有基准", StaleRunCheck.check(stateDir, Instant.now(), 26));
    }

    @Test
    public void 最近跑过就不提醒() throws Exception {
        Path stateDir = folder.newFolder("state").toPath();
        Instant finished = Instant.now().minus(10, ChronoUnit.HOURS);
        record(stateDir, finished);

        assertNull(StaleRunCheck.check(stateDir, Instant.now(), 26));
    }

    @Test
    public void 超过阈值没有完成的运行就提醒() throws Exception {
        Path stateDir = folder.newFolder("state").toPath();
        Instant now = Instant.now();
        record(stateDir, now.minus(35, ChronoUnit.HOURS));

        NotificationSection alert = StaleRunCheck.check(stateDir, now, 26);

        assertNotNull(alert);
        assertEquals("运行检查", alert.getTitle());
        assertTrue(alert.getBlocks().get(0).toPlainText().contains("35 小时前"));
    }

    @Test
    public void 阈值配置非法时用默认值() {
        assertEquals(StaleRunCheck.DEFAULT_HOURS, StaleRunCheck.hours(null));
        assertEquals(StaleRunCheck.DEFAULT_HOURS, StaleRunCheck.hours(""));
        assertEquals(StaleRunCheck.DEFAULT_HOURS, StaleRunCheck.hours("abc"));
        assertEquals(StaleRunCheck.DEFAULT_HOURS, StaleRunCheck.hours("0"));
        assertEquals(48L, StaleRunCheck.hours(" 48 "));
    }

    private static void record(Path stateDir, Instant finishedAt) {
        RunHistory.record(stateDir, Collections.singletonList(
                        new NotificationSection("西门子签到", Collections.singletonList(MessageBlock.text("签到成功")))),
                finishedAt.minusSeconds(60), finishedAt);
    }
}
