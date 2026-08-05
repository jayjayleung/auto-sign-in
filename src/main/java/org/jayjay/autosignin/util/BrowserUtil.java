package org.jayjay.autosignin.util;

import com.ruiyun.jvppeteer.api.core.ElementHandle;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.exception.TimeoutException;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class BrowserUtil {

    private static final long ELEMENT_WAIT_TIMEOUT_MILLIS = 30000L;
    private static final long ELEMENT_POLL_INTERVAL_MILLIS = 200L;

    private BrowserUtil() {
    }

    public static ElementHandle waitForElement(Page page, String selector) throws Exception {
        Objects.requireNonNull(page, "page");
        return waitForValue(() -> page.$(selector), selector,
                ELEMENT_WAIT_TIMEOUT_MILLIS, ELEMENT_POLL_INTERVAL_MILLIS);
    }

    static <T> T waitForValue(CheckedSupplier<T> supplier, String description,
                              long timeoutMillis, long pollIntervalMillis) throws Exception {
        Objects.requireNonNull(supplier, "supplier");
        if (timeoutMillis <= 0 || pollIntervalMillis <= 0) {
            throw new IllegalArgumentException("等待超时和轮询间隔必须大于 0");
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }

            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("等待元素超时：" + description);
            }

            long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            Thread.sleep(Math.min(pollIntervalMillis, remainingMillis));
        }
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
