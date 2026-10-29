package es.brasatech.fastbite.application.mail;

public interface OwnerSetupMailPort {
    void sendSetupLink(String email, String username, String link);
    default void sendSetupLink(String email, String username, String link, String language) {
        sendSetupLink(email, username, link);
    }
}
