package es.brasatech.fastbite.application.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Brasatech platform administrators allowed to extend restaurant trials for pilots or deals.
 *
 * <p>Configured via {@code fastbite.platform.admins} ({@code FASTBITE_PLATFORM_ADMINS}) as a
 * comma-separated list of owner usernames. Empty means nobody is a platform admin.
 */
@Component
public class PlatformAdmins {

    private final Set<String> admins;

    public PlatformAdmins(@Value("${fastbite.platform.admins:}") String configured) {
        if (configured == null || configured.isBlank()) {
            this.admins = Set.of();
        } else {
            this.admins = Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    public boolean isAdmin(String username) {
        if (username == null) {
            return false;
        }
        String trimmed = username.trim();
        return !trimmed.isEmpty() && admins.contains(trimmed);
    }
}
