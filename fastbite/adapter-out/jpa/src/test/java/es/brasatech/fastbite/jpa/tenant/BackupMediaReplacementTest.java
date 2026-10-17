package es.brasatech.fastbite.jpa.tenant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BackupMediaReplacementTest {
    @TempDir Path root;

    @Test void databaseCommitFailureRestoresTheOriginalDirectory() throws Exception {
        Path target = Files.createDirectory(root.resolve("tenant"));
        Files.writeString(target.resolve("image.txt"), "original");
        Path sub = Files.createDirectory(target.resolve("sub"));
        Files.writeString(sub.resolve("nested.txt"), "nested-original");
        try (var replacement = new BackupMediaReplacement(target)) {
            Files.writeString(replacement.stagedMedia().resolve("image.txt"), "new");
            Path newSub = Files.createDirectory(replacement.stagedMedia().resolve("sub"));
            Files.writeString(newSub.resolve("nested.txt"), "nested-new");
            replacement.install();
            assertEquals("new", Files.readString(target.resolve("image.txt")));
            assertEquals("nested-new", Files.readString(target.resolve("sub/nested.txt")));
            // No committed() call: equivalent to transaction commit throwing after media installation.
        }
        assertTrue(Files.exists(target));
        assertEquals("original", Files.readString(target.resolve("image.txt")));
        assertEquals("nested-original", Files.readString(target.resolve("sub/nested.txt")));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void databaseCommitSuccessKeepsNewMediaAndRetainsTargetDirectory() throws Exception {
        Path target = Files.createDirectory(root.resolve("tenant"));
        Files.writeString(target.resolve("image.txt"), "original");
        try (var replacement = new BackupMediaReplacement(target)) {
            Files.writeString(replacement.stagedMedia().resolve("image.txt"), "new");
            replacement.install();
            replacement.committed();
        }
        assertTrue(Files.exists(target));
        assertEquals("new", Files.readString(target.resolve("image.txt")));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void restoreIntoNonExistentDestinationInstallsAndCleansUpOnFailure() throws Exception {
        Path target = root.resolve("nonexistent");
        assertFalse(Files.exists(target));
        try (var replacement = new BackupMediaReplacement(target)) {
            Files.writeString(replacement.stagedMedia().resolve("image.txt"), "new");
            replacement.install();
            assertTrue(Files.exists(target));
            assertEquals("new", Files.readString(target.resolve("image.txt")));
        }
        // Failure without committed(): target contents cleaned up
        assertTrue(Files.exists(target));
        try (var files = Files.list(target)) { assertEquals(0, files.count()); }
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void failureWhileMovingOriginalsAsideRestoresAllOriginalsOnClose() throws Exception {
        Path target = Files.createDirectory(root.resolve("tenant"));
        Files.writeString(target.resolve("1.txt"), "orig-1");
        Files.writeString(target.resolve("2.txt"), "orig-2");
        Files.writeString(target.resolve("3.txt"), "orig-3");

        var moveCount = new java.util.concurrent.atomic.AtomicInteger();
        BackupMediaReplacement.Mover failingMover = (src, dest) -> {
            if (moveCount.incrementAndGet() == 2) {
                throw new java.io.IOException("Simulated I/O failure moving original file 2");
            }
            Files.move(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        };

        try (var replacement = new BackupMediaReplacement(target, failingMover)) {
            Files.writeString(replacement.stagedMedia().resolve("new.txt"), "new-content");
            assertThrows(java.io.UncheckedIOException.class, replacement::install);
        }

        // Close after install threw during move of originals: every original file is restored
        assertTrue(Files.exists(target));
        assertEquals("orig-1", Files.readString(target.resolve("1.txt")));
        assertEquals("orig-2", Files.readString(target.resolve("2.txt")));
        assertEquals("orig-3", Files.readString(target.resolve("3.txt")));
        assertFalse(Files.exists(target.resolve("new.txt")));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void failureWhileInstallingNewMediaRestoresOriginalsWithNoNewFilesLeft() throws Exception {
        Path target = Files.createDirectory(root.resolve("tenant"));
        Files.writeString(target.resolve("orig.txt"), "orig-content");

        var moveCount = new java.util.concurrent.atomic.AtomicInteger();
        BackupMediaReplacement.Mover failingMover = (src, dest) -> {
            // First move is orig.txt moving to previous.
            // Second move is new1.txt moving to target.
            // Third move simulates failure on new2.txt.
            if (moveCount.incrementAndGet() == 3) {
                throw new java.io.IOException("Simulated I/O failure installing new2.txt");
            }
            Files.move(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        };

        try (var replacement = new BackupMediaReplacement(target, failingMover)) {
            Files.writeString(replacement.stagedMedia().resolve("new1.txt"), "new-1");
            Files.writeString(replacement.stagedMedia().resolve("new2.txt"), "new-2");
            assertThrows(java.io.UncheckedIOException.class, replacement::install);
        }

        // Close after install threw during installation of new media:
        // Destination holds exactly the original files, with no new ones left over
        assertTrue(Files.exists(target));
        assertEquals("orig-content", Files.readString(target.resolve("orig.txt")));
        assertFalse(Files.exists(target.resolve("new1.txt")));
        assertFalse(Files.exists(target.resolve("new2.txt")));
        try (var files = Files.list(target)) { assertEquals(1, files.count()); }
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }
}
