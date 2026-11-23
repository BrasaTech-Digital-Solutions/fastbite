package es.brasatech.fastbite.email.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.Properties;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AccountMailTemplatesTest {
    @Test void everyHoldReasonRendersTheAppropriateEmail() throws Exception {
        String url = "https://fastbite.example/owner/console?view=today&location=cafe#opening-step-verifactu";
        for (var reason : es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate.HoldReason.values()) {
            var parts = render(sender -> new OwnerSetupMailAdapter(sender, "noreply@example.test",
                    new AccountMailRenderer("BRASATECH", "Test address", "https://example.test"))
                    .sendVerifactuOnHold("owner@example.test", "cafe", url, "en", reason.name()));
            switch (reason) {
                case FISCAL_INCOMPLETE -> {
                    assertThat(parts.subject()).contains("Business details needed");
                    assertThat(parts.text()).contains("business details are incomplete", url);
                    assertThat(parts.html()).contains("Complete business details");
                }
                case COLLABORATION_INACTIVE -> {
                    assertThat(parts.subject()).contains("Ticket issuing is on hold");
                    assertThat(parts.text()).contains("nothing you need to do", "We will let you know").doesNotContain(url, "null");
                    assertThat(parts.html()).doesNotContain("opening-step-verifactu", "word-break:break-all");
                }
                default -> {
                    assertThat(parts.subject()).contains("is on hold (VERI*FACTU authorisation)");
                    assertThat(parts.text()).contains("Reason:", "Open the VERI*FACTU step", url);
                    assertThat(parts.html()).contains("Open VERI*FACTU step");
                }
            }
        }
    }
    private record Parts(String subject, String text, String html) { }
    private Parts render(Consumer<JavaMailSender> call) throws Exception {
        var sender = mock(JavaMailSender.class);
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        call.accept(sender);
        verify(sender).send(message);
        message.saveChanges();
        var mixed = (jakarta.mail.Multipart) message.getContent();
        var related = (jakarta.mail.Multipart) mixed.getBodyPart(0).getContent();
        var alternative = (jakarta.mail.Multipart) related.getBodyPart(0).getContent();
        return new Parts(message.getSubject(), (String) alternative.getBodyPart(0).getContent(), (String) alternative.getBodyPart(1).getContent());
    }
    @Test void accountMessagesRenderEachLanguageAsTextAndSafeHtml() throws Exception {
        String url = "https://example.test/verify?token=safe";
        for (String language : new String[]{"en", "es", "pt"}) {
            for (int kind = 0; kind < 6; kind++) {
                int selected = kind;
                var parts = render(sender -> {
                    var adapter = new OwnerSetupMailAdapter(sender, "noreply@example.test", new AccountMailRenderer("BRASATECH", "Test address", "https://example.test"));
                    switch (selected) {
                        case 0 -> adapter.sendSetupLink("owner@example.test", "<b>x</b>", url, language);
                        case 1 -> adapter.sendSignupVerification("owner@example.test", "<b>x</b>", url, language);
                        case 2 -> adapter.sendSignupNotice("owner@example.test", language);
                        case 3 -> adapter.sendOwnerVerification("owner@example.test", "<b>x</b>", url, language);
                        case 4 -> adapter.sendOwnerEmailNotice("owner@example.test", language);
                        case 5 -> adapter.sendPasswordReset("owner@example.test", "<b>x</b>", url, language);
                        default -> throw new AssertionError();
                    }
                });
                assertThat(parts.text()).isNotBlank();
                assertThat(parts.html()).contains("fastbite", "BRASATECH", "/legal/privacy", "role=\"presentation\"");
                if (selected == 0 || selected == 1 || selected == 3 || selected == 5) {
                    assertThat(parts.text()).contains(url);
                    assertThat(parts.html()).contains(url);
                    if ("en".equals(language)) {
                        int fallbackLabel = parts.html().indexOf("If the button does not work, use this link:");
                        int fallbackBox = parts.html().indexOf("word-break:break-all");
                        assertThat(fallbackLabel).isGreaterThanOrEqualTo(0).isLessThan(fallbackBox);
                    }
                } else assertThat(parts.html()).doesNotContain("word-break:break-all");
                if (selected == 3 || selected == 4) {
                    String heading = switch (language) {
                        case "es" -> selected == 3 ? "Verifica tu correo" : "Tu correo ha cambiado";
                        case "pt" -> selected == 3 ? "Confirme o seu email" : "O seu email foi alterado";
                        default -> selected == 3 ? "Verify your email" : "Your email was changed";
                    };
                    assertThat(parts.subject()).isEqualTo("FastBite");
                    assertThat(parts.html()).contains(">" + heading + "</h1>");
                }
                if (selected == 0) assertThat(parts.html()).contains("&lt;b&gt;x&lt;/b&gt;").doesNotContain("<b>x</b>");
            }
        }
    }
    @Test void contractNoticesKeepTheirExactExistingText() throws Exception {
        String tenant = "<b>cafe</b>";
        Instant date = Instant.parse("2026-11-03T12:00:00Z");
        String[] expected = {
                "New orders for your restaurant location '" + tenant + "' are currently paused because its trial or subscription has ended.\n\nYour owner sign-in, fiscal records and backup export remain available in your workspace.\n\nIf billing is not regularised within 30 days, the contract for '" + tenant + "' will end on 03/11/2026 (terms §5 & §13).\n\nTo resume taking orders, sign in to your FastBite owner console and activate or update your subscription in Billing.",
                "This is a reminder that new orders for your restaurant location '" + tenant + "' remain paused.\n\nUnless billing is regularised, the contract for '" + tenant + "' will end on 03/11/2026 (terms §5 & §13).\n\nSign in to your FastBite owner console to activate or update your subscription, or to export a backup of your restaurant data.",
                "After 30 days without active billing, the contract for your restaurant location '" + tenant + "' has ended (terms §5 & §13).\n\nYou have 30 days, until 03/11/2026, to sign in to your FastBite owner console and export your restaurant data (Backup & restore).\n\nAfter 03/11/2026, the location's operational data (menu, tables, orders and staff accounts) will be permanently deleted, except for issued invoices and VERI*FACTU billing records which are kept as required by law."
        };
        String[] subjects = { "FastBite — New orders paused for " + tenant,
                "FastBite — 7 days left before the contract for " + tenant + " ends",
                "FastBite — The contract for " + tenant + " has ended (30 days to export your data)" };
        for (int kind = 0; kind < 3; kind++) {
            int selected = kind;
            var parts = render(sender -> {
                var adapter = new ContractLifecycleMailAdapter(sender, "noreply@example.test", new AccountMailRenderer("BRASATECH", "Test address", "https://example.test"));
                switch (selected) {
                    case 0 -> adapter.sendOrdersPausedNotice("owner@example.test", tenant, date, date);
                    case 1 -> adapter.sendContractEndingWarning("owner@example.test", tenant, date);
                    case 2 -> adapter.sendContractEndedNotice("owner@example.test", tenant, date, date);
                    default -> throw new AssertionError();
                }
            });
            assertThat(parts.subject()).isEqualTo(subjects[kind]);
            assertThat(parts.text()).isEqualTo(expected[kind]);
            assertThat(org.unbescape.html.HtmlEscape.unescapeHtml(parts.html())).contains(expected[kind]);
            assertThat(parts.html()).contains("&lt;b&gt;cafe&lt;/b&gt;").doesNotContain("<b>cafe</b>", "word-break:break-all");
        }
    }

    @Test void verifactuHoldAndOpenedEmailsRenderEachLanguageSafely() throws Exception {
        String tenant = "<b>lacasita</b>";
        String url = "https://fastbite.example/owner/console?view=today&location=lacasita#opening-step-verifactu";
        String htmlUrl = url.replace("&", "&amp;");
        for (String language : new String[]{"en", "es", "pt"}) {
            var holdParts = render(sender -> {
                var adapter = new OwnerSetupMailAdapter(sender, "noreply@example.test",
                        new AccountMailRenderer("BRASATECH", "Test address", "https://example.test"));
                adapter.sendVerifactuOnHold("owner@example.test", tenant, url, language, "COLLABORATION_INACTIVE");
            });
            assertThat(holdParts.subject()).contains(tenant);
            assertThat(holdParts.text()).doesNotContain(url, "null");
            assertThat(holdParts.text()).contains(switch (language) {
                case "es" -> "No tienes que hacer nada. Te avisaremos";
                case "pt" -> "Não precisa de fazer nada. Avisaremos";
                default -> "There is nothing you need to do. We will let you know";
            });
            assertThat(holdParts.html()).doesNotContain(htmlUrl, "word-break:break-all").contains("&lt;b&gt;lacasita&lt;/b&gt;").doesNotContain("<b>lacasita</b>");

            String legalUrl = "https://fastbite.example/owner/console?view=location-settings&section=legal&location=lacasita";
            var fiscalParts = render(sender -> {
                var adapter = new OwnerSetupMailAdapter(sender, "noreply@example.test",
                        new AccountMailRenderer("BRASATECH", "Test address", "https://example.test"));
                adapter.sendVerifactuOnHold("owner@example.test", tenant, legalUrl, language, "FISCAL_INCOMPLETE");
            });
            assertThat(fiscalParts.text()).contains(legalUrl, "NIF");
            assertThat(fiscalParts.html()).contains(switch (language) {
                case "es" -> "Completar datos del negocio";
                case "pt" -> "Completar dados do negócio";
                default -> "Complete business details";
            });
            assertThat(fiscalParts.html()).contains(legalUrl.replace("&", "&amp;"), "&lt;b&gt;lacasita&lt;/b&gt;")
                    .doesNotContain("<b>lacasita</b>");
            assertThat(fiscalParts.subject()).doesNotContain("authorisation", "autorización", "autorização");

            var mismatchParts = render(sender -> {
                var adapter = new OwnerSetupMailAdapter(sender, "noreply@example.test",
                        new AccountMailRenderer("BRASATECH", "Test address", "https://example.test"));
                adapter.sendVerifactuOnHold("owner@example.test", tenant, url, language, "NIF_MISMATCH");
            });
            assertThat(mismatchParts.text()).contains("NIF");

            var testEnvParts = render(sender -> {
                var adapter = new OwnerSetupMailAdapter(sender, "noreply@example.test",
                        new AccountMailRenderer("BRASATECH", "Test address", "https://example.test"));
                adapter.sendVerifactuOnHold("owner@example.test", tenant, url, language, "TEST_ENVIRONMENT");
            });
            assertThat(testEnvParts.text()).contains("Digital Sign");

            var trialParts = render(sender -> {
                var adapter = new OwnerSetupMailAdapter(sender, "noreply@example.test",
                        new AccountMailRenderer("BRASATECH", "Test address", "https://example.test"));
                adapter.sendVerifactuOpenedWithTrial("owner@example.test", tenant, url, language);
            });
            assertThat(trialParts.subject()).contains(tenant);
            assertThat(trialParts.text()).contains(url).contains("30");
            assertThat(trialParts.html()).contains(htmlUrl).contains("&lt;b&gt;lacasita&lt;/b&gt;").doesNotContain("<b>lacasita</b>");

            var freeParts = render(sender -> {
                var adapter = new OwnerSetupMailAdapter(sender, "noreply@example.test",
                        new AccountMailRenderer("BRASATECH", "Test address", "https://example.test"));
                adapter.sendVerifactuOpenedWithoutTrial("owner@example.test", tenant, url, language);
            });
            assertThat(freeParts.subject()).contains(tenant);
            assertThat(freeParts.text()).contains(url).doesNotContain("30");
            assertThat(freeParts.text().toLowerCase()).doesNotContain("trial").doesNotContain("prueba").doesNotContain("experimental");

            var reopenedParts = render(sender -> {
                var adapter = new OwnerSetupMailAdapter(sender, "noreply@example.test",
                        new AccountMailRenderer("BRASATECH", "Test address", "https://example.test"));
                adapter.sendVerifactuReopened("owner@example.test", tenant, url, language);
            });
            assertThat(reopenedParts.subject()).contains(tenant);
            assertThat(reopenedParts.text()).contains(url).doesNotContain("30");
            assertThat(reopenedParts.text().toLowerCase()).doesNotContain("trial").doesNotContain("prueba").doesNotContain("experimental");
        }
    }
}
