package org.jayjay.autosignin.task;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MessageCopyTest {

    @Test
    public void usesConsistentTaskTitles() {
        assertEquals("墨天轮签到", new MoDbCheckInTask().buildNotificationSection().getTitle());
        assertEquals("TiDB 签到", new TiDbCheckInTask().buildNotificationSection().getTitle());
        assertEquals("云桥签到", new YunqiaoCheckInTask().buildNotificationSection().getTitle());
        assertEquals("永洪签到", new YongHoneCheckInTask().buildNotificationSection().getTitle());
    }
}
