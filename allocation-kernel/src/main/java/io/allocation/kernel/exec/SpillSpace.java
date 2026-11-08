package io.allocation.kernel.exec;

import io.allocation.kernel.AllocationException;
import io.allocation.kernel.ErrorCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Owns the directory that stage buffers spill into, and removes it afterwards.
 *
 * <p>The directory is created on first use, so a run that fits in memory never touches the filesystem.
 * That matters more than it sounds: the common run is a few thousand rows, and creating and deleting a
 * temp directory for every one of them is a needless way to be slow.
 *
 * <p>Cleanup is best effort. A leftover file in the OS temp directory is untidy; failing a successful
 * allocation because a temp file could not be deleted would be worse.
 */
final class SpillSpace implements AutoCloseable {

    private Path directory;
    private int counter;

    /** Creates a new, uniquely named spool file inside the spill directory. */
    Path newFile() {
        try {
            if (directory == null) {
                directory = Files.createTempDirectory("allocation-spill-");
            }
            return directory.resolve("rows-" + (++counter) + ".bin");
        } catch (IOException e) {
            throw new AllocationException(ErrorCode.INTERNAL, "cannot create a spill directory", e);
        }
    }

    /** True once something has actually been spilled. */
    boolean used() {
        return directory != null;
    }

    @Override
    public void close() {
        if (directory == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
        directory = null;
    }
}
