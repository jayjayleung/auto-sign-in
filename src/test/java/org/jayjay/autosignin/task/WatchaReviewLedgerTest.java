package org.jayjay.autosignin.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WatchaReviewLedgerTest {

    @Test
    public void countsLostReviewsAsStrikes() {
        WatchaReviewLedger ledger = new WatchaReviewLedger();
        String recent = java.time.Instant.now().minusSeconds(3L * 86400).toString();
        ledger.record(1L, 100, recent);
        ledger.record(2L, 101, recent);
        Set<Long> alive = new HashSet<Long>();
        alive.add(1L);
        assertEquals("只少了一条就只记一次", 1, ledger.reconcile(alive));
        assertEquals(1, ledger.strikes());
    }

    @Test
    public void ignoresReviewsStillInsideGraceWindow() {
        WatchaReviewLedger ledger = new WatchaReviewLedger();
        String now = java.time.Instant.now().toString();
        ledger.record(3L, 102, now);
        assertEquals("刚发的还在审核期，不算消失", 0, ledger.reconcile(new HashSet<Long>()));
        assertEquals(0, ledger.strikes());
    }

    @Test
    public void ignoresEntriesOlderThanTheLookupWindow() {
        WatchaReviewLedger ledger = new WatchaReviewLedger();
        String old = java.time.Instant.now().minusSeconds(40L * 86400).toString();
        ledger.record(11L, 300, old);
        assertEquals("40 天前的猹评早就不在回查窗口里，不能算被删", 0, ledger.reconcile(new HashSet<Long>()));
        assertEquals(0, ledger.strikes());
    }

    @Test
    public void pausesAfterTwoStrikes() {
        WatchaReviewLedger ledger = new WatchaReviewLedger();
        String recent = java.time.Instant.now().minusSeconds(3L * 86400).toString();
        ledger.record(1L, 100, recent);
        ledger.record(2L, 101, recent);
        assertFalse(ledger.paused(LocalDate.of(2026, 11, 27)));
        ledger.reconcile(new HashSet<Long>());
        ledger.reconcile(new HashSet<Long>());
        assertTrue("累计两次违规应当停写", ledger.paused(LocalDate.of(2026, 11, 27)));
    }

    @Test
    public void survivesRoundTripThroughFile() throws Exception {
        File file = File.createTempFile("watcha-ledger", ".json");
        file.deleteOnExit();
        WatchaReviewLedger ledger = new WatchaReviewLedger();
        ledger.record(9L, 200, java.time.Instant.now().minusSeconds(2L * 86400).toString());
        ledger.reconcile(new HashSet<Long>());
        ledger.save(file);

        WatchaReviewLedger reloaded = WatchaReviewLedger.load(file);
        assertEquals(1, reloaded.strikes());
        assertEquals(1, reloaded.publishedCount());
        assertEquals("已经记过的条目不该重复计数", 0, reloaded.reconcile(new HashSet<Long>()));
    }

    @Test
    public void missingFileLoadsEmpty() {
        WatchaReviewLedger ledger = WatchaReviewLedger.load(new File("/tmp/no-such-ledger-xyz.json"));
        assertEquals(0, ledger.publishedCount());
        assertEquals(0, ledger.strikes());
        assertFalse(ledger.unreadable());
    }

    @Test
    public void saveReplacesFileWithoutLeavingTempFile() throws Exception {
        Path dir = Files.createTempDirectory("watcha-ledger");
        File file = dir.resolve("watcha-review-ledger.json").toFile();
        WatchaReviewLedger ledger = new WatchaReviewLedger();
        ledger.record(1L, 100, java.time.Instant.now().toString());
        ledger.save(file);
        ledger.record(2L, 101, java.time.Instant.now().toString());
        ledger.save(file);

        assertEquals(2, WatchaReviewLedger.load(file).publishedCount());
        assertFalse("改名完成后不应残留临时文件", Files.exists(dir.resolve("watcha-review-ledger.json.tmp")));
    }

    @Test
    public void truncatedFileFailsClosed() throws Exception {
        File file = File.createTempFile("watcha-ledger", ".json");
        file.deleteOnExit();
        Files.write(file.toPath(), "{\"strikes\":0,\"published\":[{\"id\":".getBytes(StandardCharsets.UTF_8));

        WatchaReviewLedger ledger = WatchaReviewLedger.load(file);

        assertTrue(ledger.unreadable());
        assertTrue("读不出来的台账不能当成零违规继续发", ledger.paused(LocalDate.of(2026, 11, 27)));
    }

    @Test
    public void unreadableFileIsKeptForInspection() throws Exception {
        File file = File.createTempFile("watcha-ledger", ".json");
        file.deleteOnExit();
        byte[] broken = "not json".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), broken);

        WatchaReviewLedger ledger = WatchaReviewLedger.load(file);
        ledger.record(5L, 105, java.time.Instant.now().toString());
        ledger.save(file);

        assertArrayEquals("不能用空台账覆盖掉坏文件，否则下次读到的就是正常的零违规", broken,
                Files.readAllBytes(file.toPath()));
    }
}
