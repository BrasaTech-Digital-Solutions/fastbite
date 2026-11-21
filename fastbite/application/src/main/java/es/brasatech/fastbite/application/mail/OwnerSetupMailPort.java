package es.brasatech.fastbite.application.mail;

public interface OwnerSetupMailPort {
    void sendSetupLink(String email, String username, String link);
    default void sendSetupLink(String email, String username, String link, String language) {
        sendSetupLink(email, username, link);
    }
    void sendSignupVerification(String email, String username, String link, String language);
    void sendOwnerVerification(String email, String username, String link, String language);
    void sendOwnerEmailNotice(String email, String language);
    void sendPasswordReset(String email, String username, String link, String language);
    void sendSignupNotice(String email, String language);
    default void sendVerifactuOnHold(String email, String tenantId, String link, String language) {
    }
    default void sendVerifactuOnHold(String email, String tenantId, String link, String language, String holdReason) {
        sendVerifactuOnHold(email, tenantId, link, language);
    }
    default void sendVerifactuOpened(String email, String tenantId, String link, String language) {
        sendVerifactuOpenedWithTrial(email, tenantId, link, language);
    }
    default void sendVerifactuOpenedWithTrial(String email, String tenantId, String link, String language) {
    }
    default void sendVerifactuOpenedWithoutTrial(String email, String tenantId, String link, String language) {
    }
    default void sendVerifactuReopened(String email, String tenantId, String link, String language) {
    }
}
