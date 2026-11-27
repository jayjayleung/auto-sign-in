package org.jayjay.autosignin.task;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WatchaImageHostTest {

    @Test
    public void readsUrlFromJson() {
        String body = "{\"status\":\"success\",\"data\":{\"url\":\"https://img.example.com/1234567/shot.png\"}}";
        assertEquals("https://img.example.com/1234567/shot.png", WatchaImageHost.pickUrl(body));
    }

    @Test
    public void directUrlFromOtherHostStaysUntouched() {
        assertEquals("https://files.catbox.moe/abc.png", WatchaImageHost.pickUrl("https://files.catbox.moe/abc.png"));
        assertEquals("https://tmpfiles.org/dl/1/x.png", WatchaImageHost.pickUrl("https://tmpfiles.org/dl/1/x.png"));
    }

    @Test
    public void rejectsGarbage() {
        assertNull(WatchaImageHost.pickUrl(""));
        assertNull(WatchaImageHost.pickUrl("{\"error\":\"too large\"}"));
        assertNull(WatchaImageHost.pickUrl("not a url"));
    }

    @Test
    public void rejectsMissingFile() {
        assertNull(WatchaImageHost.upload(new java.io.File("/tmp/definitely-not-here.png"), null));
        assertNull("没配图床就不该上传", WatchaImageHost.upload(
                new java.io.File("/tmp/definitely-not-here.png"), ""));
    }
}
