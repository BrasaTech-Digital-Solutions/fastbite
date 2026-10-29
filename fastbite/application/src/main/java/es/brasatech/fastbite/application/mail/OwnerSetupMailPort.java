package es.brasatech.fastbite.application.mail;

public interface OwnerSetupMailPort {
    void sendSetupLink(String email, String username, String link);
    default void sendSetupLink(String email, String username, String link, String language) {
        sendSetupLink(email, username, link);
    }
    void sendSignupVerification(String email, String username, String link, String language);
    void sendOwnerVerification(String email, String username, String link, String language);
    void sendOwnerEmailNotice(String email, String language);
    void sendSignupNotice(String email, String language);
}
