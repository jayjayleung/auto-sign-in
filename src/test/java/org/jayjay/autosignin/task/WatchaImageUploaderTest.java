package org.jayjay.autosignin.task;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WatchaImageUploaderTest {

    @Test
    public void derivesPostfixAndContentType() {
        assertEquals("png", WatchaImageUploader.extensionOf("shot-4137-1.png"));
        assertEquals("jpg", WatchaImageUploader.extensionOf("shot.JPG"));
        assertEquals("png", WatchaImageUploader.extensionOf("noext"));
        assertEquals("image/jpeg", WatchaImageUploader.contentTypeOf("jpeg"));
        assertEquals("image/webp", WatchaImageUploader.contentTypeOf("webp"));
        assertEquals("image/png", WatchaImageUploader.contentTypeOf("png"));
    }

    @Test
    public void refusesMissingFile() {
        assertNull(WatchaImageUploader.upload(new File("/tmp/definitely-missing.png"), "https://watcha.cn", "t"));
    }
}
