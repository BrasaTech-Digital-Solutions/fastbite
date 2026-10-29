package es.brasatech.fastbite.application.office;

import java.util.Optional;

/** Platform owner credentials, independent of the current restaurant schema. */
public interface OwnerPasswordPort {
    Optional<String> passwordHash(String username);
    boolean replacePassword(String username, String oldHash, String newHash);
}
