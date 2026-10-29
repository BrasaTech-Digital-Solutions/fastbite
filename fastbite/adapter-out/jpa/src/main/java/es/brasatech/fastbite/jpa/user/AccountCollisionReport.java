package es.brasatech.fastbite.jpa.user;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Repository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** A read-only operator check before enabling owner email sign-in. */
@Repository
public class AccountCollisionReport {
    public record Report(List<String> emailLikeUsernames, List<String> collidingUsernames) { }
    public record SchemaReport(String schema, Report report) { }
    public record Audit(List<SchemaReport> schemas) { }
    private record Account(String username, String email) { }
    private final JdbcTemplate jdbc;
    public AccountCollisionReport(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Report inspect() {
        var accounts = jdbc.query("SELECT username, email FROM public.users", (rs, row) ->
                new Account(rs.getString(1), rs.getString(2)));
        Set<String> emails = ownerEmails(accounts);
        return report(accounts.stream().map(Account::username).toList(), emails);
    }

    /** Read every tenant schema; works before and after V29. */
    public Audit inspectAll() {
        return jdbc.execute((ConnectionCallback<Audit>) connection -> {
            List<Account> owners = new ArrayList<>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM public.users")) {
                boolean hasEmail = false;
                var columns = rows.getMetaData();
                for (int i = 1; i <= columns.getColumnCount(); i++) {
                    if (columns.getColumnLabel(i).equalsIgnoreCase("email")) hasEmail = true;
                }
                while (rows.next()) owners.add(new Account(rows.getString("username"), hasEmail ? rows.getString("email") : null));
            }
            Set<String> emails = ownerEmails(owners);
            List<SchemaReport> reports = new ArrayList<>();
            reports.add(new SchemaReport("public", report(owners.stream().map(Account::username).toList(), emails)));
            List<String> schemas = new ArrayList<>();
            try (ResultSet rows = connection.getMetaData().getSchemas()) {
                while (rows.next()) {
                    String schema = rows.getString("TABLE_SCHEM");
                    if (schema.toLowerCase(Locale.ROOT).matches("tenant_[a-z0-9]+")) schemas.add(schema);
                }
            }
            schemas.sort(String.CASE_INSENSITIVE_ORDER);
            for (String schema : schemas) {
                List<String> usernames = usernames(connection, schema);
                reports.add(new SchemaReport(schema, report(usernames, emails)));
            }
            return new Audit(List.copyOf(reports));
        });
    }

    private static List<String> usernames(Connection connection, String schema) throws SQLException {
        List<String> usernames = new ArrayList<>();
        // Only metadata-discovered, validated schema names are interpolated.
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT username FROM \"" + schema + "\".users")) {
            while (rows.next()) usernames.add(rows.getString(1));
        }
        return usernames;
    }

    private static Set<String> ownerEmails(List<Account> accounts) {
        return accounts.stream().map(Account::email).filter(e -> e != null)
                .map(e -> e.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
    private static Report report(List<String> names, Set<String> emails) {
        var usernames = names.stream().filter(u -> u.contains("@")).sorted().toList();
        var collisions = usernames.stream().filter(u -> emails.contains(u.toLowerCase(Locale.ROOT))).toList();
        return new Report(usernames, collisions);
    }

    /** Set FASTBITE_AUDIT_JDBC_URL, FASTBITE_AUDIT_USER and FASTBITE_AUDIT_PASSWORD; prints counts only. */
    public static void main(String[] args) {
        String url = System.getenv("FASTBITE_AUDIT_JDBC_URL");
        String username = System.getenv("FASTBITE_AUDIT_USER");
        String password = System.getenv("FASTBITE_AUDIT_PASSWORD");
        if (url == null || username == null || password == null) {
            System.err.println("Set the three FASTBITE_AUDIT connection settings first.");
            System.exit(2);
        }
        try {
            var source = new DriverManagerDataSource(url, username, password);
            Audit audit = new AccountCollisionReport(new JdbcTemplate(source)).inspectAll();
            int names = audit.schemas().stream().mapToInt(s -> s.report().emailLikeUsernames().size()).sum();
            int collisions = audit.schemas().stream().mapToInt(s -> s.report().collidingUsernames().size()).sum();
            System.out.printf("Schemas checked: %d; email-like usernames: %d; collisions: %d%n",
                    audit.schemas().size(), names, collisions);
            if (args.length == 1 && "--details".equals(args[0])) {
                audit.schemas().forEach(schema -> {
                    schema.report().emailLikeUsernames().forEach(name -> System.out.println(schema.schema() + " username: " + name));
                    schema.report().collidingUsernames().forEach(name -> System.out.println(schema.schema() + " collision: " + name));
                });
            }
        } catch (RuntimeException error) {
            System.err.println("The read-only account audit failed; check database connectivity and schema permissions.");
            System.exit(1);
        }
    }
}
