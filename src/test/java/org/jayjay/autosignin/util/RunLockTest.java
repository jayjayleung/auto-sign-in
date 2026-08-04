package org.jayjay.autosignin.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

public class RunLockTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void applicationLockPathIsStableAndApplicationIdentitiesRemainDistinct() {
        assertEquals(RunLock.pathForApplication(), RunLock.pathForApplication());
        assertNotEquals(
                RunLock.pathForIdentity("/app/auto-sign-in-a.jar"),
                RunLock.pathForIdentity("/app/auto-sign-in-b.jar"));
    }

    @Test
    public void lockPreventsOverlapAndCanBeReacquiredAfterRelease() throws Exception {
        Path lockPath = temporaryFolder.newFolder("locks").toPath().resolve("run.lock");
        RunLock first = RunLock.tryAcquire(lockPath);
        assertNotNull(first);
        try {
            assertNull(RunLock.tryAcquire(lockPath));
        } finally {
            first.close();
        }

        RunLock reacquired = RunLock.tryAcquire(lockPath);
        assertNotNull(reacquired);
        reacquired.close();
    }
}
