package org.jayjay.autosignin.util;

import com.ruiyun.jvppeteer.exception.TimeoutException;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BrowserUtilTest {

    @Test
    public void returnsValueWhenPollingFindsIt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        String result = BrowserUtil.waitForValue(
                () -> attempts.incrementAndGet() == 3 ? "ready" : null,
                "test element", 1000L, 1L);

        assertEquals("ready", result);
        assertEquals(3, attempts.get());
    }

    @Test(timeout = 1000L)
    public void timesOutWhenValueNeverAppears() throws Exception {
        try {
            BrowserUtil.waitForValue(() -> null, "#missing", 20L, 1L);
            fail("Expected wait to time out");
        } catch (TimeoutException e) {
            assertTrue(e.getMessage().contains("#missing"));
        }
    }
}
