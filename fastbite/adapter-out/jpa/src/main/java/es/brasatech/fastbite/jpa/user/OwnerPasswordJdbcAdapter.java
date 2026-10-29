package es.brasatech.fastbite.jpa.user;

import es.brasatech.fastbite.application.office.OwnerPasswordPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class OwnerPasswordJdbcAdapter implements OwnerPasswordPort {
    private final JdbcTemplate jdbc;
    public OwnerPasswordJdbcAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<String> passwordHash(String username) {
        return jdbc.query("SELECT password FROM public.users WHERE username = ? AND active = TRUE",
                (rows, n) -> rows.getString(1), username).stream().findFirst();
    }
    @Override public boolean replacePassword(String username, String oldHash, String newHash) {
        return jdbc.update("UPDATE public.users SET password = ? WHERE username = ? AND password = ? AND active = TRUE",
                newHash, username, oldHash) == 1;
    }
}
