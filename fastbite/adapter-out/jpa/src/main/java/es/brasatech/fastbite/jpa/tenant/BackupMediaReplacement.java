package es.brasatech.fastbite.jpa.tenant;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.logging.Logger;

/** Stage beside the destination, retain the original directory until the database commits. */
final class BackupMediaReplacement implements AutoCloseable {
    @FunctionalInterface
    interface Mover {
        void move(Path source, Path target) throws IOException;
    }

    private static final Logger LOG = Logger.getLogger(BackupMediaReplacement.class.getName());
    private final Path destination;
    private final Path staging;
    private final Path media;
    private final Path previous;
    private final Mover mover;
    private boolean movedOriginal;
    private boolean installing;
    private boolean committed;

    BackupMediaReplacement(Path destination) throws IOException {
        this(destination, (src, dest) -> Files.move(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING));
    }

    BackupMediaReplacement(Path destination, Mover mover) throws IOException {
        this.destination = destination;
        this.mover = mover;
        Files.createDirectories(destination.getParent());
        staging = Files.createTempDirectory(destination.getParent(), ".restore-");
        media = Files.createDirectory(staging.resolve("media"));
        previous = staging.resolve("previous");
    }

    Path stagedMedia() { return media; }

    void install() {
        try {
            if (Files.exists(destination)) {
                // Mark movedOriginal before moving: if moveContents fails halfway, rollback moves back whatever reached previous.
                movedOriginal = true;
                moveContents(destination, previous);
            } else {
                Files.createDirectories(destination);
            }
            // Mark installing before moving: if install fails halfway, rollback empties destination so new files don't leak.
            installing = true;
            moveContents(media, destination);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot replace tenant media", e);
        }
    }

    void committed() { committed = true; }

    @Override
    public void close() throws IOException {
        if (!committed) {
            // If rollback fails or throws, exit without deleting staging to retain staging/previous for recovery; never delete the only copy.
            if (installing) cleanDirectoryContents(destination);
            if (movedOriginal) moveContents(previous, destination);
        }
        try {
            deleteTree(staging);
        } catch (IOException e) {
            LOG.warning("Backup staging cleanup failed; retained at " + staging);
        }
    }

    private void moveContents(Path source, Path target) throws IOException {
        if (!Files.exists(source)) return;
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (path.equals(source)) continue;
                Path relative = source.relativize(path);
                Path dest = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.delete(path);
                } else {
                    Files.createDirectories(dest.getParent());
                    mover.move(path, dest);
                }
            }
        }
    }

    private static void cleanDirectoryContents(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(dir)) Files.delete(path);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
