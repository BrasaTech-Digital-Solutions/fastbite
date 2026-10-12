package es.brasatech.fastbite.jpa.tenant;

import es.brasatech.fastbite.application.platform.SandboxSignInPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;

import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.transaction;
import static es.brasatech.fastbite.jpa.tenant.RegistrationJdbc.update;

/** The sign-in links the sandbox has accepted, in the platform schema, shared by all its instances. */
@Component
@Profile("jpa")
public class SandboxSignInJdbcAdapter implements SandboxSignInPort {

    private final DataSource dataSource;

    public SandboxSignInJdbcAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean claim(String nonce, Instant expires, Instant now) {
        return transaction(dataSource, connection -> {
            update(connection, "DELETE FROM public.sandbox_sign_in WHERE expires_at <= ?", now.toEpochMilli());
            // Two requests with the same link at once: the database lets only one of them in
            if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                return update(connection, "INSERT INTO public.sandbox_sign_in (nonce, expires_at) VALUES (?, ?) "
                        + "ON CONFLICT (nonce) DO NOTHING", nonce, expires.toEpochMilli()) == 1;
            }
            return update(connection, "INSERT INTO public.sandbox_sign_in (nonce, expires_at) SELECT ?, ? "
                    + "WHERE NOT EXISTS (SELECT 1 FROM public.sandbox_sign_in WHERE nonce = ?)",
                    nonce, expires.toEpochMilli(), nonce) == 1;
        });
    }
}
