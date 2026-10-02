package com.springairag.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;

/**
 * The helper that replaced ten hardcoded migration versions.
 *
 * <p>It reads the classpath, so it cannot be wrong about which version this
 * build ships — but it can be wrong in a way that makes every caller pass
 * vacuously, and that is the failure mode worth a test: an empty resource scan
 * or a mis-parsed filename would turn "Flyway applied what we shipped" into
 * "the string equals itself".
 *
 * <p>No case here states a version number. Doing so would reintroduce the very
 * pattern this helper exists to remove, and would fail on the next migration
 * for the same reason the ten call sites did.
 */
class MigrationVersionsTest {

    @Test
    @DisplayName("shipped versions are unique, strictly ascending, and latest() is their maximum")
    void shippedVersionsAreOrderedAndLatestIsTheMaximum() {
        List<String> shipped = MigrationVersions.shipped();

        assertTrue(shipped.size() > 1, "the migration set should not be a single file");
        assertEquals(shipped.size(), shipped.stream().distinct().count(),
                "a version number is declared by two files");
        assertEquals(shipped.get(shipped.size() - 1), MigrationVersions.latest(),
                "latest() must be the last entry of the ascending list");

        // Ordering is numeric, not lexicographic: a string sort would rank "9"
        // above "59" and the helper would report a version that does not exist.
        for (int i = 1; i < shipped.size(); i++) {
            final int at = i;
            assertTrue(Long.parseLong(shipped.get(at)) > Long.parseLong(shipped.get(at - 1)),
                    () -> "shipped versions are not strictly ascending at index " + at + ": " + shipped);
        }
    }

    @Test
    @DisplayName("an empty scan fails loudly instead of making every caller vacuous")
    void refusesToAnswerBlindly() {
        // If this returned an empty list, `latest()` would be null and each
        // caller's assertEquals(null, installed) could only pass by accident.
        assertThrows(IllegalStateException.class, () -> MigrationVersions.versionsOf(new Resource[0]));
    }

    @Test
    @DisplayName("a filename Flyway would reject is refused rather than skipped")
    void refusesToSkipAnUnparseableFilename() {
        // Skipping it would shrink the set and make latest() quietly stale —
        // the same class of failure the helper was introduced to remove.
        Resource misplaced = new org.springframework.core.io.ByteArrayResource(new byte[0]) {
            @Override
            public String getFilename() {
                return "add_llm_usage_event.sql";
            }
        };
        assertThrows(IllegalStateException.class, () -> MigrationVersions.versionsOf(new Resource[] { misplaced }));
    }

    @Test
    @DisplayName("it reads the multi-part names Flyway actually produces")
    void parsesRealWorldNames() {
        Resource[] resources = {
                named("V1__init.sql"),
                named("V9__add_ab_testing.sql"),
                named("V59__add_fs_import_batches.sql"),
        };
        assertEquals(List.of("1", "9", "59"), MigrationVersions.versionsOf(resources));
    }

    private static Resource named(String filename) {
        return new org.springframework.core.io.ByteArrayResource(new byte[0]) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }
}
