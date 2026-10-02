package com.springairag.core.integration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The migration version this build actually ships, and the one the database
 * actually applied.
 *
 * <p>Batch 801 ran the twenty-two gated PostgreSQL suites for the first time.
 * Nine of the ten failures were the same line of reasoning: a test asserted that
 * {@code flyway_schema_history}'s newest row equalled a literal — {@code "55"},
 * {@code "57"}, {@code "58"} — written when that migration was current. Adding
 * V56, then V57, then V58, then V59 broke one such assertion each time, and
 * because those suites only run behind a switch, nothing reported it until
 * somebody finally switched them on.
 *
 * <p>Asserting a literal is the wrong shape. The invariant worth stating is
 * that Flyway applied exactly the migrations that ship in this jar, so the
 * comparison is made between the two moving parts instead of against a number
 * that is stale the moment the next migration lands.
 */
public final class MigrationVersions {

    /** Flyway's own versioned-file naming: {@code V57__add_llm_usage_event.sql}. */
    private static final Pattern VERSIONED_FILE = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private MigrationVersions() {
    }

    /**
     * The highest migration version present on the classpath, as Flyway would
     * report it.
     *
     * <p>Read from the jar rather than from a checked-in constant for the same
     * reason the assertion does: a constant in the test tree is a second place
     * to forget.
     */
    public static String latest() {
        return highestOf(shippedVersions());
    }

    /** Every shipped version, ascending. */
    public static List<String> shipped() {
        return shippedVersions().stream().sorted(Comparator.comparingLong(Long::parseLong)).toList();
    }

    private static List<String> shippedVersions() {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:db/migration/V*__*.sql");
        } catch (IOException e) {
            throw new IllegalStateException("cannot list db/migration on the classpath", e);
        }
        return versionsOf(resources);
    }

    /**
     * Package-private so the behaviour on a bad or empty resource set is
     * testable without arranging a broken classpath.
     */
    static List<String> versionsOf(Resource[] resources) {
        List<String> versions = new ArrayList<>();
        for (Resource resource : resources) {
            Matcher matcher = VERSIONED_FILE.matcher(resource.getFilename() == null ? "" : resource.getFilename());
            if (!matcher.matches()) {
                throw new IllegalStateException(
                        "migration file does not follow Flyway's naming convention: " + resource.getFilename());
            }
            versions.add(matcher.group(1));
        }
        if (versions.isEmpty()) {
            throw new IllegalStateException("no migrations found under db/migration on the classpath");
        }
        return versions;
    }

    private static String highestOf(List<String> versions) {
        return versions.stream()
                .max(Comparator.comparingLong(Long::parseLong))
                .orElseThrow();
    }

    /**
     * The newest successfully applied version recorded in the database.
     *
     * <p>Ordering is by {@code installed_rank}, not by the version string: a
     * string sort would put {@code "9"} after {@code "59"} and quietly turn a
     * schema assertion into a false pass.
     */
    public static String installedLatest(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT version
                FROM flyway_schema_history
                WHERE success = TRUE
                ORDER BY installed_rank DESC
                LIMIT 1
                """, String.class);
    }

    /**
     * The assertion the nine call sites wanted, in the form that survives the
     * next migration.
     */
    public static void assertLatestApplied(JdbcTemplate jdbc) {
        org.junit.jupiter.api.Assertions.assertEquals(
                latest(),
                installedLatest(jdbc),
                () -> "Flyway applied " + installedLatest(jdbc)
                        + " but this build ships up to " + latest()
                        + "; if that is intended, no test change is needed — if not,"
                        + " a migration is missing from the migration path.");
    }
}
