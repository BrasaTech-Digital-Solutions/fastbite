package es.brasatech.fastbite.email.service;

import es.brasatech.fastbite.application.mail.ContractLifecycleMailPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class ContractLifecycleMailAdapter implements ContractLifecycleMailPort {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            .withZone(ZoneId.of("Europe/Madrid"));

    private final JavaMailSender sender;
    private final String from;
    private final AccountMailRenderer renderer;

    public ContractLifecycleMailAdapter(JavaMailSender sender, @Value("${spring.mail.username}") String from, AccountMailRenderer renderer) {
        this.sender = sender;
        this.from = from;
        this.renderer = renderer;
    }

    @Override
    public void sendOrdersPausedNotice(String email, String tenantId, Instant pausedSince, Instant contractEndsAt) {
        String endDate = DATE_FORMAT.format(contractEndsAt);
        String subject = "FastBite — New orders paused for " + tenantId;
        String body = "New orders for your restaurant location '" + tenantId + "' are currently paused because its trial or subscription has ended.\n\n"
                + "Your owner sign-in, fiscal records and backup export remain available in your workspace.\n\n"
                + "If billing is not regularised within 30 days, the contract for '" + tenantId + "' will end on " + endDate + " (terms §5 & §13).\n\n"
                + "To resume taking orders, sign in to your FastBite owner console and activate or update your subscription in Billing.";
        renderer.send(sender, from, email, "contract-paused", "en", subject, null, body, body, null, null);
    }

    @Override
    public void sendContractEndingWarning(String email, String tenantId, Instant contractEndsAt) {
        String endDate = DATE_FORMAT.format(contractEndsAt);
        String subject = "FastBite — 7 days left before the contract for " + tenantId + " ends";
        String body = "This is a reminder that new orders for your restaurant location '" + tenantId + "' remain paused.\n\n"
                + "Unless billing is regularised, the contract for '" + tenantId + "' will end on " + endDate + " (terms §5 & §13).\n\n"
                + "Sign in to your FastBite owner console to activate or update your subscription, or to export a backup of your restaurant data.";
        renderer.send(sender, from, email, "contract-warning", "en", subject, null, body, body, null, null);
    }

    @Override
    public void sendContractEndedNotice(String email, String tenantId, Instant contractEndedAt, Instant deletionAt) {
        String deletionDate = DATE_FORMAT.format(deletionAt);
        String subject = "FastBite — The contract for " + tenantId + " has ended (30 days to export your data)";
        String body = "After 30 days without active billing, the contract for your restaurant location '" + tenantId + "' has ended (terms §5 & §13).\n\n"
                + "You have 30 days, until " + deletionDate + ", to sign in to your FastBite owner console and export your restaurant data (Backup & restore).\n\n"
                + "After " + deletionDate + ", the location's operational data (menu, tables, orders and staff accounts) will be permanently deleted, "
                + "except for issued invoices and VERI*FACTU billing records which are kept as required by law.";
        renderer.send(sender, from, email, "contract-ended", "en", subject, null, body, body, null, null);
    }
}
