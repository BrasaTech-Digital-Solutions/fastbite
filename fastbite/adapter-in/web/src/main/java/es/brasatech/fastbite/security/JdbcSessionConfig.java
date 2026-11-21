package es.brasatech.fastbite.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

/**
 * Spring Session JDBC 4.0's default principal-name query joins {@code SPRING_SESSION} and
 * {@code SPRING_SESSION_ATTRIBUTES} without {@code ORDER BY}. When the database interleaves attribute
 * rows across sessions, {@code SessionResultSetExtractor} creates partial copies and drops earlier
 * attributes such as {@code SPRING_SECURITY_CONTEXT}. Ordering by {@code S.SESSION_ID} keeps each
 * session's rows contiguous.
 */
@Configuration
public class JdbcSessionConfig {

    static final String ORDERED_LIST_SESSIONS_BY_PRINCIPAL_NAME_QUERY = """
            SELECT S.PRIMARY_ID, S.SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME,
                   S.MAX_INACTIVE_INTERVAL, SA.ATTRIBUTE_NAME, SA.ATTRIBUTE_BYTES
            FROM %TABLE_NAME% S
            LEFT JOIN %TABLE_NAME%_ATTRIBUTES SA ON S.PRIMARY_ID = SA.SESSION_PRIMARY_ID
            WHERE S.PRINCIPAL_NAME = ?
            ORDER BY S.SESSION_ID
            """;

    @Bean
    public SessionRepositoryCustomizer<JdbcIndexedSessionRepository> orderedPrincipalSessionQueryCustomizer() {
        return repository -> repository.setListSessionsByPrincipalNameQuery(ORDERED_LIST_SESSIONS_BY_PRINCIPAL_NAME_QUERY);
    }
}
