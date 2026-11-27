package org.jayjay.autosignin.task;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WatchaSessionTest {

    @Test
    public void roundTripKeepsBothTokens() throws Exception {
        File file = File.createTempFile("watcha-session", ".json");
        file.deleteOnExit();
        WatchaSession.of("access-1", "refresh-1").save(file);

        WatchaSession loaded = WatchaSession.load(file);
        assertEquals("access-1", loaded.accessToken());
        assertEquals("refresh-1", loaded.refreshToken());
        assertTrue(loaded.hasAccess());
        assertTrue(loaded.hasRefresh());
        assertTrue("刚存的会话年龄应该是 0 小时", loaded.ageHours() >= 0 && loaded.ageHours() < 1);
    }

    @Test
    public void missingFileIsEmptySession() {
        WatchaSession session = WatchaSession.load(new File("/tmp/no-such-watcha-session.json"));
        assertFalse(session.hasAccess());
        assertFalse(session.hasRefresh());
        assertEquals(-1, session.ageHours());
    }

    @Test
    public void readsLegacyFileWithoutRefreshToken() throws Exception {
        File file = File.createTempFile("watcha-session-legacy", ".json");
        file.deleteOnExit();
        Files.write(file.toPath(),
                "{\"access_token\":\"old\",\"saved_at\":0}".getBytes(StandardCharsets.UTF_8));
        WatchaSession loaded = WatchaSession.load(file);
        assertEquals("old", loaded.accessToken());
        assertFalse("老文件没有 refresh_token 也要能读", loaded.hasRefresh());
    }

    @Test
    public void emptyTokensAreNotSaved() throws Exception {
        File file = new File("/tmp/watcha-session-empty-" + System.nanoTime() + ".json");
        WatchaSession.empty().save(file);
        assertFalse("空会话不该落盘", file.exists());
    }
}
