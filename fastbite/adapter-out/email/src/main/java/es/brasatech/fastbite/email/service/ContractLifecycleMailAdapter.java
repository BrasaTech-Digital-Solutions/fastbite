package es.brasatech.fastbite.email.service;

import es.brasatech.fastbite.application.mail.ContractLifecycleMailPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
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

    public ContractLifecycleMailAdapter(JavaMailSender sender, @Value("${spring.mail.username}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void sendOrdersPausedNotice(String email, String tenantId, Instant pausedSince, Instant contractEndsAt) {
        String endDate = DATE_FORMAT.format(contractEndsAt);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("FastBite — New orders paused for " + tenantId);
        message.setText("New orders for your restaurant location '" + tenantId + "' are currently paused because its trial or subscription has ended.\n\n"
                + "Your owner sign-in, fiscal records and backup export remain available in your workspace.\n\n"
                + "If billing is not regularised within 30 days, the contract for '" + tenantId + "' will end on " + endDate + " (terms §5 & §13).\n\n"
                + "To resume taking orders, sign in to your FastBite owner console and activate or update your subscription in Billing.");
        sender.send(message);
    }

    @Override
    public void sendContractEndingWarning(String email, String tenantId, Instant contractEndsAt) {
        String endDate = DATE_FORMAT.format(contractEndsAt);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("FastBite — 7 days left before the contract for " + tenantId + " ends");
        message.setText("This is a reminder that new orders for your restaurant location '" + tenantId + "' remain paused.\n\n"
                + "Unless billing is regularised, the contract for '" + tenantId + "' will end on " + endDate + " (terms §5 & §13).\n\n"
                + "Sign in to your FastBite owner console to activate or update your subscription, or to export a backup of your restaurant data.");
        sender.send(message);
    }

    @Override
    public void sendContractEndedNotice(String email, String tenantId, Instant contractEndedAt, Instant deletionAt) {
        String deletionDate = DATE_FORMAT.format(deletionAt);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("FastBite — The contract for " + tenantId + " has ended (30 days to export your data)");
        message.setText("After 30 days without active billing, the contract for your restaurant location '" + tenantId + "' has ended (terms §5 & §13).\n\n"
                + "You have 30 days, until " + deletionDate + ", to sign in to your FastBite owner console and export your restaurant data (Backup & restore).\n\n"
                + "After " + deletionDate + ", the location's operational data (menu, tables, orders and staff accounts) will be permanently deleted, "
                + "except for issued invoices and VERI*FACTU billing records which are kept as required by law.");
        sender.send(message);
    }
}
