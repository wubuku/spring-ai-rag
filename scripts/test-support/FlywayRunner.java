import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * 最小 Flyway 迁移入口，供 scripts/verify-flyway-schema-paths.sh 使用。
 *
 * 参数：<jdbc-url> <user> <password> <locations> [target-version|latest]
 *
 * 只做 migrate，不 clean（cleanDisabled），避免误删调用方数据库。
 */
public class FlywayRunner {

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("usage: FlywayRunner <url> <user> <password> <locations> [target|latest]");
            System.exit(2);
        }
        String url = args[0];
        String user = args[1];
        String password = args[2];
        String locations = args[3];
        String target = args.length > 4 ? args[4] : "latest";

        var configure = Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations)
                .baselineOnMigrate(true)
                .cleanDisabled(true);
        if (!"latest".equalsIgnoreCase(target)) {
            configure.target(target);
        }

        Flyway flyway = configure.load();
        MigrateResult result = flyway.migrate();
        System.out.printf("migrations=%d version=%s%n",
                result.migrationsExecuted, result.targetSchemaVersion);
    }
}
