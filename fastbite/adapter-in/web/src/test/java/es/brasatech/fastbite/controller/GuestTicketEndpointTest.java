package es.brasatech.fastbite.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.application.fiscal.GuestActionRateLimit;
import es.brasatech.fastbite.application.fiscal.GuestInvoiceRateLimit;
import es.brasatech.fastbite.application.mail.GuestTicketMailPort;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.config.PublicOrigin;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.fiscal.*;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import es.brasatech.fastbite.domain.verifactu.Verifactu;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.service.GuestTicketSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {GuestTicketController.class, TicketController.class}, properties = "spring.messages.basename=i18n/messages")
@ContextConfiguration(classes = {TestConfig.class, GuestTicketController.class, TicketController.class,
        GuestTicketSignature.class, SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
class GuestTicketEndpointTest {
    @Autowired MockMvc mvc;
    @Autowired GuestTicketSignature signatures;
    @MockitoBean FiscalTicketService tickets;
    @MockitoBean GuestInvoiceRateLimit invoiceLimit;
    @MockitoBean GuestActionRateLimit actionLimit;
    @MockitoBean GuestTicketMailPort mail;
    @MockitoBean PublicOrigin publicOrigin;
    @MockitoBean OrderService orders;
    @MockitoBean VerifactuService verifactu;
    @MockitoBean TenantLocationService locations;

    @BeforeEach void setup() {
        when(publicOrigin.configuredPublicUrl()).thenReturn("https://example.test");
        es.brasatech.fastbite.config.RegisteredTenants.all(locations);
        when(locations.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(locations.getLocationByCustomDomain("restaurant.example"))
                .thenReturn(Optional.of(new es.brasatech.fastbite.domain.tenant.TenantLocation(null, "owner", "kebab", "FREE", "restaurant.example")));
        FiscalTicket.Issuer issuer = new FiscalTicket.Issuer("Cafe", null, "12345678Z", "Street", "28001", "Madrid", null, null);
        FiscalTicket ticket = new FiscalTicket("abc", TicketKind.SIMPLIFIED, "TEST-T", 1, "TEST-T-000001", true,
                OffsetDateTime.parse("2026-10-31T10:00:00Z"), "online", issuer, null, List.of(), BigDecimal.ZERO,
                BigDecimal.TEN, VatBreakdown.EMPTY, BigDecimal.ZERO, PaymentMethod.ONLINE, null, null, null,
                List.of("order"), "1", null, "reference");
        when(tickets.findById("abc")).thenReturn(Optional.of(ticket));
        when(tickets.corrections("abc")).thenReturn(List.of());
        when(tickets.cancellation("abc")).thenReturn(Optional.empty());
        when(verifactu.findForTicket("abc")).thenReturn(Optional.empty());
    }

    @Test void prefixedGuestPageRendersAndRejectsBadSignatures() throws Exception {
        String sig = signatures.sign("kebab", "abc");
        mvc.perform(get("/kebab/ticket/abc").param("s", sig))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(allOf(containsString("<title>TEST-T-000001</title>"), containsString("Cafe"),
                        containsString("TRAINING"), not(containsString("Served by")))));
        mvc.perform(get("/kebab/ticket/abc").param("s", sig + "0")).andExpect(status().isNotFound());
        mvc.perform(get("/kebab/ticket/abc")).andExpect(status().isNotFound());
    }

    @Test void tenantHostUsesTheSameSignedPage() throws Exception {
        mvc.perform(get("/ticket/abc").with(request -> { request.setServerName("restaurant.example"); return request; })
                .param("s", signatures.sign("kebab", "abc")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("TEST-T-000001")));
    }

    @Test void statusEndpointOnlyReturnsTheCurrentSessionsTicket() throws Exception {
        mvc.perform(get("/kebab/api/guest-ticket")).andExpect(status().isNotFound());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("orderId", "order");
        session.setAttribute("orderTenant", "kebab");
        when(orders.findById("order")).thenReturn(Optional.of(mock(Order.class)));
        mvc.perform(get("/kebab/api/guest-ticket").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("pending"))
                .andExpect(jsonPath("$.ticketUrl").doesNotExist());
        FiscalTicket sale = tickets.findById("abc").orElseThrow();
        when(tickets.findForOrder("order")).thenReturn(Optional.of(sale));
        mvc.perform(get("/kebab/api/guest-ticket").session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.state").value("ready"))
                .andExpect(jsonPath("$.number").value("TEST-T-000001"))
                .andExpect(jsonPath("$.ticketUrl").value("/kebab/ticket/abc?s=" + signatures.sign("kebab", "abc")));
        mvc.perform(get("/other/api/guest-ticket").session(session)).andExpect(status().isNotFound());
    }

    @Test void guestTicketShowsTaxQrAndRejectsSignaturesFromAnotherTenantOrTicket() throws Exception {
        FiscalTicket ticket = tickets.findById("abc").orElseThrow();
        VerifactuRecord record = new VerifactuRecord("rec", "abc", "TEST", 1, "F2", "12345678Z", "Cafe",
                ticket.fullNumber(), ticket.issuedOn(), ticket.issuedAt(), BigDecimal.ZERO, BigDecimal.TEN, null, "ABC",
                Verifactu.qrUrl(true, "12345678Z", ticket.fullNumber(), ticket.issuedOn(), BigDecimal.TEN),
                null, null, true, VerifactuRecord.Submission.PENDING);
        when(verifactu.findForTicket("abc")).thenReturn(Optional.of(record));
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("kebab", "abc")))
                .andExpect(status().isOk()).andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
                .andExpect(content().string(allOf(containsString("<svg"), containsString("VERI*FACTU"), containsString("TRAINING"))));
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("other", "abc"))).andExpect(status().isNotFound());
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("kebab", "different"))).andExpect(status().isNotFound());
        mvc.perform(get("/kebab/ticket/missing").param("s", signatures.sign("kebab", "missing"))).andExpect(status().isNotFound());
    }

    @Test void replacedAndRefundedTicketsShowOnlyTheAppropriateSignedLinks() throws Exception {
        FiscalTicket replacement = mock(FiscalTicket.class);
        when(replacement.kind()).thenReturn(TicketKind.REPLACEMENT);
        when(replacement.id()).thenReturn("replacement");
        when(replacement.fullNumber()).thenReturn("FULL-001");
        when(tickets.corrections("abc")).thenReturn(List.of(replacement));
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("kebab", "abc")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("FULL-001")))
                .andExpect(content().string(not(containsString("/kebab/ticket/replacement?s="))))
                .andExpect(content().string(not(containsString("name=\"taxId\""))));
        FiscalTicket refund = mock(FiscalTicket.class);
        when(refund.kind()).thenReturn(TicketKind.RECTIFYING);
        when(refund.fullNumber()).thenReturn("REFUND-001");
        when(refund.id()).thenReturn("refund");
        when(tickets.corrections("abc")).thenReturn(List.of(refund));
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("kebab", "abc")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("REFUND-001")))
                .andExpect(content().string(containsString("/kebab/ticket/refund?s=" + signatures.sign("kebab", "refund"))))
                .andExpect(content().string(not(containsString("name=\"taxId\""))));
    }

    @Test void cancelledGuestTicketDoesNotRevealStaffOrReason() throws Exception {
        when(tickets.cancellation("abc")).thenReturn(Optional.of(new TicketCancellation("abc", OffsetDateTime.now(), "staff-secret", "private-reason")));
        mvc.perform(get("/kebab/ticket/abc").param("s", signatures.sign("kebab", "abc")))
                .andExpect(status().isOk()).andExpect(content().string(allOf(containsString("cancelled"),
                        not(containsString("staff-secret")), not(containsString("private-reason")), not(containsString("name=\"taxId\"")))));
    }

    @Test void guestInvoiceRequiresSignatureAndCsrfAndReturnsItsOwnLink() throws Exception {
        String sig = signatures.sign("kebab", "abc");
        when(invoiceLimit.tryAcquire(anyString(), anyString())).thenReturn(true);
        FiscalTicket replacement = mock(FiscalTicket.class);
        when(replacement.id()).thenReturn("full-invoice");
        when(tickets.replace(eq("abc"), any(), eq("cliente online"))).thenReturn(replacement);
        mvc.perform(get("/kebab/ticket/abc").param("s", sig))
                .andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("Need an invoice for your business?")));
        mvc.perform(post("/kebab/ticket/abc/invoice").param("s", sig)).andExpect(status().isForbidden());
        mvc.perform(post("/kebab/ticket/abc/invoice").with(csrf()).param("s", "wrong"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/kebab/ticket/abc/invoice").with(csrf()).param("s", sig)
                        .param("name", "Acme").param("taxId", "12345678Z").param("country", "ES").param("address", "Madrid"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/kebab/ticket/full-invoice?s=" + signatures.sign("kebab", "full-invoice")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        verify(tickets).replace(eq("abc"), any(), eq("cliente online"));
        var hashedTicket = ArgumentCaptor.forClass(String.class);
        var hashedClient = ArgumentCaptor.forClass(String.class);
        verify(invoiceLimit).tryAcquire(hashedTicket.capture(), hashedClient.capture());
        assertThat(hashedTicket.getValue()).startsWith("v1.").doesNotContain("abc");
        assertThat(hashedClient.getValue()).startsWith("v1.").doesNotContain("127.0.0.1");
    }

    @Test void guestInvoiceShowsLocalizedFieldErrorsAndRateLimit() throws Exception {
        String sig = signatures.sign("kebab", "abc");
        when(invoiceLimit.tryAcquire(anyString(), anyString())).thenReturn(true);
        when(tickets.replace(eq("abc"), any(), eq("cliente online"))).thenThrow(
                new LocalizedRejection("recipient.error.taxIdInvalid", "Invalid tax number"));
        mvc.perform(post("/kebab/ticket/abc/invoice").with(csrf()).param("s", sig)
                        .param("name", "Acme").param("taxId", "bad").locale(java.util.Locale.forLanguageTag("es")))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("invoice-error")))
                .andExpect(content().string(containsString("value=\"bad\"")));
        when(invoiceLimit.tryAcquire(anyString(), anyString())).thenReturn(false);
        mvc.perform(post("/kebab/ticket/abc/invoice").with(csrf()).param("s", sig))
                .andExpect(status().isTooManyRequests()).andExpect(content().string(containsString("Too many attempts")));
    }


    @Test void customDomainInvoicePostIssuesOnlyItsOwnTenantLink() throws Exception {
        String sig = signatures.sign("kebab", "abc");
        when(invoiceLimit.tryAcquire(anyString(), anyString())).thenReturn(true);
        FiscalTicket replacement = mock(FiscalTicket.class);
        when(replacement.id()).thenReturn("full-invoice");
        when(tickets.replace(eq("abc"), any(), eq("cliente online"))).thenReturn(replacement);
        mvc.perform(post("/ticket/abc/invoice").with(request -> { request.setServerName("restaurant.example"); return request; })
                        .with(csrf()).param("s", sig).param("name", "Acme").param("taxId", "12345678Z"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ticket/full-invoice?s=" + signatures.sign("kebab", "full-invoice")));
        mvc.perform(post("/other/ticket/abc/invoice").with(csrf()).param("s", sig))
                .andExpect(status().isNotFound());
    }

    @Test void signedEmailRequiresCsrfAndNeverUsesRequestHostForTheTicketLink() throws Exception {
        when(tickets.training()).thenReturn(true);
        String sig = signatures.sign("kebab", "abc");
        when(actionLimit.tryAcquire(eq(GuestActionRateLimit.Action.EMAIL), anyString(), anyString())).thenReturn(true);
        mvc.perform(post("/kebab/ticket/abc/email").param("s", sig).param("email", "guest@example.test"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/kebab/ticket/abc/email").with(csrf()).param("s", "wrong").param("email", "guest@example.test"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/other/ticket/abc/email").with(csrf()).param("s", sig).param("email", "guest@example.test"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/ticket/abc/email").with(request -> { request.setServerName("restaurant.example"); return request; })
                        .header("X-Forwarded-Host", "attacker.example").with(csrf())
                        .param("s", sig).param("email", "guest@example.test"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(not(containsString("guest@example.test"))));
        verify(mail).sendTicket(eq("guest@example.test"), eq("Cafe"), eq("TEST-T-000001"), eq(BigDecimal.TEN),
                eq("https://example.test/kebab/ticket/abc?s=" + sig), eq(true), anyString());
        verify(actionLimit).tryAcquire(eq(GuestActionRateLimit.Action.EMAIL), anyString(), anyString());
    }

    @Test void standaloneDeploymentDeterminesPracticeWordingEvenForTrainingTicket() throws Exception {
        String sig = signatures.sign("kebab", "abc");
        when(actionLimit.tryAcquire(eq(GuestActionRateLimit.Action.EMAIL), anyString(), anyString())).thenReturn(true);
        when(tickets.training()).thenReturn(false, true);
        mvc.perform(post("/kebab/ticket/abc/email").with(csrf()).param("s", sig).param("email", "prod@example.test"))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("promote to production"))));
        mvc.perform(post("/kebab/ticket/abc/email").with(csrf()).param("s", sig).param("email", "practice@example.test"))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("promote to production"))));
        verify(mail).sendTicket(eq("prod@example.test"), eq("Cafe"), eq("TEST-T-000001"), eq(BigDecimal.TEN),
                eq("https://example.test/kebab/ticket/abc?s=" + sig), eq(false), anyString());
        verify(mail).sendTicket(eq("practice@example.test"), eq("Cafe"), eq("TEST-T-000001"), eq(BigDecimal.TEN),
                eq("https://example.test/kebab/ticket/abc?s=" + sig), eq(true), anyString());
    }

    @Test void emailRefusalsNeverSendAndProviderFailuresDoNotRevealAddress() throws Exception {
        String sig = signatures.sign("kebab", "abc");
        mvc.perform(post("/kebab/ticket/abc/email").with(csrf()).param("s", sig).param("email", "not-an-address"))
                .andExpect(status().isBadRequest());
        when(actionLimit.tryAcquire(eq(GuestActionRateLimit.Action.EMAIL), anyString(), anyString())).thenReturn(false);
        mvc.perform(post("/kebab/ticket/abc/email").with(csrf()).param("s", sig).param("email", "guest@example.test"))
                .andExpect(status().isTooManyRequests());
        verify(mail, never()).sendTicket(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyString());
        when(actionLimit.tryAcquire(eq(GuestActionRateLimit.Action.EMAIL), anyString(), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("provider leaked guest@example.test")).when(mail)
                .sendTicket(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyString());
        mvc.perform(post("/kebab/ticket/abc/email").with(csrf()).param("s", sig).param("email", "guest@example.test"))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("guest@example.test"))))
                .andExpect(content().string(not(containsString("provider leaked"))));
    }

    @Test void providerFailureDoesNotLogRecipientOrExceptionDetails() throws Exception {
        String recipient = "private-guest@case.example";
        String sig = signatures.sign("kebab", "abc");
        when(actionLimit.tryAcquire(eq(GuestActionRateLimit.Action.EMAIL), anyString(), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("provider-leak " + recipient)).when(mail)
                .sendTicket(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(), anyString());
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        root.addAppender(capture);
        try {
            mvc.perform(post("/kebab/ticket/abc/email").with(csrf()).param("s", sig).param("email", recipient))
                    .andExpect(status().isOk()).andExpect(content().string(not(containsString(recipient))));
            assertThat(capture.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("Guest ticket email not sent (IllegalStateException)");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(capture.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(recipient, "provider-leak");
                if (event.getThrowableProxy() != null) {
                    assertThat(event.getThrowableProxy().getMessage()).doesNotContain(recipient, "provider-leak");
                }
            });
        } finally { root.detachAppender(capture); capture.stop(); }
    }

    @Test void sandboxWithoutBusinessDetailsUsesLocationNameInEmail() throws Exception {
        FiscalTicket.Issuer blankIssuer = new FiscalTicket.Issuer(null, null, null, null, null, null, null, null);
        FiscalTicket blank = new FiscalTicket("blank", TicketKind.SIMPLIFIED, "TEST", 1, "TEST-1", true,
                OffsetDateTime.parse("2026-10-31T10:00:00Z"), "online", blankIssuer, null, List.of(), BigDecimal.ZERO,
                BigDecimal.TEN, VatBreakdown.EMPTY, BigDecimal.ZERO, PaymentMethod.ONLINE, null, null, null,
                List.of(), "1", null, null);
        when(tickets.findById("blank")).thenReturn(Optional.of(blank));
        when(tickets.corrections("blank")).thenReturn(List.of());
        when(tickets.cancellation("blank")).thenReturn(Optional.empty());
        when(tickets.training()).thenReturn(true);
        when(actionLimit.tryAcquire(eq(GuestActionRateLimit.Action.EMAIL), anyString(), anyString())).thenReturn(true);
        String sig = signatures.sign("kebab", "blank");
        mvc.perform(post("/kebab/ticket/blank/email").with(csrf()).param("s", sig).param("email", "guest@example.test"))
                .andExpect(status().isOk());
        verify(mail).sendTicket(eq("guest@example.test"), eq("kebab"), eq("TEST-1"), eq(BigDecimal.TEN),
                eq("https://example.test/kebab/ticket/blank?s=" + sig), eq(true), anyString());
    }

    @Test void guestEmailPortugueseUsesFormalRegister() throws Exception {
        String sig = signatures.sign("kebab", "abc");
        var pt = java.util.Locale.forLanguageTag("pt");
        mvc.perform(get("/kebab/ticket/abc").param("s", sig).locale(pt)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Usamos o seu email apenas para lhe enviar este talão; não o guardamos.")));
        mvc.perform(post("/kebab/ticket/abc/email").with(csrf()).param("s", sig).param("email", "invalid").locale(pt))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Introduza um endereço de email válido.")));
        when(actionLimit.tryAcquire(eq(GuestActionRateLimit.Action.EMAIL), anyString(), anyString())).thenReturn(false);
        mvc.perform(post("/kebab/ticket/abc/email").with(csrf()).param("s", sig).param("email", "guest@example.test").locale(pt))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().string(containsString("Demasiados pedidos. Tente novamente mais tarde.")));
    }

    @Test void fullInvoiceEmailUsesItsOwnSignedLinkAndRealTicketHasNoPracticeFlag() throws Exception {
        FiscalTicket.Issuer issuer = new FiscalTicket.Issuer("Cafe", null, "12345678Z", "Street", "28001", "Madrid", null, null);
        FiscalTicket full = new FiscalTicket("full", TicketKind.FULL, "F", 2, "F-000002", false,
                OffsetDateTime.parse("2026-10-31T10:00:00Z"), "online", issuer, null, List.of(), BigDecimal.ZERO,
                BigDecimal.TEN, VatBreakdown.EMPTY, BigDecimal.ZERO, PaymentMethod.ONLINE, null, null, null,
                List.of("order"), "1", null, "reference");
        when(tickets.findById("full")).thenReturn(Optional.of(full));
        when(tickets.corrections("full")).thenReturn(List.of());
        when(tickets.cancellation("full")).thenReturn(Optional.empty());
        when(actionLimit.tryAcquire(eq(GuestActionRateLimit.Action.EMAIL), anyString(), anyString())).thenReturn(true);
        String sig = signatures.sign("kebab", "full");
        mvc.perform(get("/kebab/ticket/full").param("s", sig)).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(post("/kebab/ticket/full/email").with(csrf()).param("s", sig).param("email", "guest@example.test"))
                .andExpect(status().isOk());
        verify(mail).sendTicket(eq("guest@example.test"), eq("Cafe"), eq("F-000002"), eq(BigDecimal.TEN),
                eq("https://example.test/kebab/ticket/full?s=" + sig), eq(false), anyString());
    }

    @Test void guestInvoiceShowsOverlongAddressErrorBesideAddress() throws Exception {
        String sig = signatures.sign("kebab", "abc");
        when(invoiceLimit.tryAcquire(anyString(), anyString())).thenReturn(true);
        when(tickets.replace(eq("abc"), any(), eq("cliente online"))).thenThrow(
                new LocalizedRejection("recipient.error.length", "The customer's details are too long"));
        mvc.perform(post("/kebab/ticket/abc/invoice").with(csrf()).param("s", sig)
                        .param("name", "Acme").param("taxId", "12345678Z").param("address", "x".repeat(201)))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("invoice-address")))
                .andExpect(content().string(containsString("too long for the invoice")));
    }

}
