package es.brasatech.fastbite.jpa.user;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class AccountCollisionReportTest {
    @Test void reportsEmailLikeUsernamesAndCaseInsensitiveEmailCollisions() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:collision_report;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE public.users (username VARCHAR(255), email VARCHAR(254))");
        jdbc.update("INSERT INTO public.users VALUES (?, ?)", "legacy@example.com", null);
        jdbc.update("INSERT INTO public.users VALUES (?, ?)", "owner", "LEGACY@example.com");
        jdbc.update("INSERT INTO public.users VALUES (?, ?)", "other@example.com", null);
        var report = new AccountCollisionReport(jdbc).inspect();
        assertThat(report.emailLikeUsernames()).containsExactly("legacy@example.com", "other@example.com");
        assertThat(report.collidingUsernames()).containsExactly("legacy@example.com");
    }
    @Test void scansStaffIncludingCake() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:collision_all;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE public.users (username VARCHAR(255), email VARCHAR(254))");
        jdbc.update("INSERT INTO public.users VALUES (?, ?)", "owner", "staff@example.com");
        jdbc.execute("CREATE SCHEMA tenant_cafe");
        jdbc.execute("CREATE TABLE tenant_cafe.users (username VARCHAR(255))");
        jdbc.update("INSERT INTO tenant_cafe.users VALUES (?)", "STAFF@example.com");
        jdbc.execute("CREATE SCHEMA tenant_cake");
        jdbc.execute("CREATE TABLE tenant_cake.users (username VARCHAR(255))");
        jdbc.update("INSERT INTO tenant_cake.users VALUES (?)", "cake@example.com");
        var audit = new AccountCollisionReport(jdbc).inspectAll();
        assertThat(audit.schemas()).hasSize(3);
        assertThat(audit.schemas().get(1).report().collidingUsernames()).containsExactly("STAFF@example.com");
    }
    @Test void scansPreMigrationAccountsWithoutAnEmailColumn() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:collision_legacy;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE public.users (username VARCHAR(255))");
        jdbc.update("INSERT INTO public.users VALUES (?)", "old@example.com");
        var audit = new AccountCollisionReport(jdbc).inspectAll();
        assertThat(audit.schemas().getFirst().report().emailLikeUsernames()).containsExactly("old@example.com");
        assertThat(audit.schemas().getFirst().report().collidingUsernames()).isEmpty();
    }
}
