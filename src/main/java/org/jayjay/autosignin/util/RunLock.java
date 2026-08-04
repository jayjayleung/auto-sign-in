package org.jayjay.autosignin.util;

import java.io.IOException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class RunLock implements AutoCloseable {
    private static final String LOCK_FILE_PREFIX = "auto-sign-in-";

    private final FileChannel channel;
    private final FileLock lock;

    private RunLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static Path pathForApplication() {
        String identity = RunLock.class.getName();
        try {
            URL location = RunLock.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                identity = Paths.get(location.toURI()).toAbsolutePath().normalize().toString();
            } else {
                identity = System.getProperty("java.class.path", identity);
            }
        } catch (Exception ignored) {
            identity = System.getProperty("java.class.path", identity);
        }
        return pathForIdentity(identity);
    }

    static Path pathForIdentity(String identity) {
        UUID identityId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return Paths.get(System.getProperty("java.io.tmpdir"), LOCK_FILE_PREFIX + identityId + ".lock");
    }

    public static RunLock tryAcquire(Path path) throws IOException {
        Path lockPath = path.toAbsolutePath().normalize();
        Path parent = lockPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                channel.close();
                return null;
            }
            if (lock == null) {
                channel.close();
                return null;
            }
            return new RunLock(channel, lock);
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException closeException) {
                e.addSuppressed(closeException);
            }
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException e) {
            failure = e;
        }
        try {
            channel.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
