package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.application.digitalsign.CallOptions;
import es.brasatech.fastbite.application.digitalsign.CreateRequestCommand;
import es.brasatech.fastbite.application.digitalsign.CreateRequestResult;
import es.brasatech.fastbite.application.digitalsign.DigitalSignClient;
import es.brasatech.fastbite.application.digitalsign.DigitalSignException;
import es.brasatech.fastbite.application.digitalsign.DigitalSignProperties;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest.RequestDocuments;
import es.brasatech.fastbite.application.digitalsign.DigitalSignRequest.RequestSigner;
import es.brasatech.fastbite.application.digitalsign.DigitalSignTemplate;
import es.brasatech.fastbite.application.digitalsign.DigitalSignTemplate.TemplateField;
import es.brasatech.fastbite.application.digitalsign.DigitalSignTemplate.TemplateSigner;
import es.brasatech.fastbite.application.digitalsign.SigningUrlResponse;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationDocumentStorage.StoredDocument;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService.ReconcileSummary;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService.StartSigningResult;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService.WebhookOutcome;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationStore.UpdateResult;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorizationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerifactuAuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final byte[] SIGNED_PDF = "%PDF-1.4\nsigned\n%%EOF".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVIDENCE_PDF = "%PDF-1.4\nevidence\n%%EOF".getBytes(StandardCharsets.UTF_8);

    private VerifactuAuthorizationStore store;
    private VerifactuAuthorizationDocumentStorage storage;
    private DigitalSignClient client;
    private DigitalSignProperties properties;
    private VerifactuAuthorizationService service;

    @BeforeEach
    void setUp() {
        store = mock(VerifactuAuthorizationStore.class);
        storage = mock(VerifactuAuthorizationDocumentStorage.class);
        client = mock(DigitalSignClient.class);
        properties = new DigitalSignProperties();
        properties.getVerifactu().setSignerRole("Representante");
        when(client.isConfigured()).thenReturn(true);
        when(store.update(any())).thenAnswer(inv -> {
            VerifactuAuthorization arg = inv.getArgument(0);
            return arg.withVersion(arg.version() + 1);
        });
        when(store.compareAndSet(any())).thenAnswer(inv -> {
            VerifactuAuthorization arg = inv.getArgument(0);
            return new UpdateResult(arg.withVersion(arg.version() + 1), true);
        });
        when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new VerifactuAuthorizationService(
                store, storage, client, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void completedWithMatchingCertificateNifDownloadsBothPdfsAndTransitionsToValid() {
        VerifactuAuthorization auth = inProgressAuth("B09767898", "req-1");
        when(store.findByDigitalSignRequestId("req-1")).thenReturn(Optional.of(auth));

        // Second signer (Brasatech) has a different NIF; only the configured signer-role must be checked.
        RequestSigner brasatechSigner = new RequestSigner(
                "Colaborador", "Brasatech", null, "es", "SIGNED", NOW, null, List.of(),
                "CERTIFICATE", null, "READY", Map.of("organizationIdentifier", "VATES-A12345674"));
        RequestSigner restaurantSigner = new RequestSigner(
                "Representante", "Ana Lopez", null, "es", "SIGNED", NOW, null, List.of(),
                "CERTIFICATE", "B09767898", "READY",
                Map.of("subject", "CN=ANA LOPEZ, O=RESTAURANTE SL, OID.2.5.4.97=VATES-B09767898",
                        "issuer", Map.of("organizationIdentifier", "VATES-Q2826004J")));

        when(client.getRequest(eq("req-1"), any())).thenReturn(new DigitalSignRequest(
                "req-1", "COMPLETED", auth.id().toString(), "Anexo I", NOW.minusSeconds(60), null, NOW,
                List.of(brasatechSigner, restaurantSigner), new RequestDocuments(true, true)));
        when(client.downloadSignedDocument(eq("req-1"), any())).thenReturn(SIGNED_PDF);
        when(client.downloadEvidenceReport(eq("req-1"), any())).thenReturn(EVIDENCE_PDF);
        when(storage.storeSignedPdf("B09767898", auth.id(), SIGNED_PDF))
                .thenReturn(new StoredDocument("B09767898/" + auth.id() + "/signed.pdf", "a".repeat(64)));
        when(storage.storeEvidencePdf("B09767898", auth.id(), EVIDENCE_PDF))
                .thenReturn(new StoredDocument("B09767898/" + auth.id() + "/evidence.pdf", "b".repeat(64)));

        VerifactuAuthorization result = service.syncRequestForWebhook("req-1").orElseThrow();

        assertThat(result.status()).isEqualTo(VerifactuAuthorizationStatus.VALID);
        assertThat(result.validFrom()).isEqualTo(NOW);
        assertThat(result.certificateNif()).isEqualTo("B09767898");
        assertThat(result.signerName()).isEqualTo("Ana Lopez");
        assertThat(result.hasStoredDocuments()).isTrue();
        assertThat(result.failureReason()).isNull();
    }

    @Test
    void completedWithMismatchedCertificateNifMarksNifMismatchWithoutDownloadingPdfs() {
        VerifactuAuthorization auth = inProgressAuth("B09767898", "req-mismatch");
        when(store.findByDigitalSignRequestId("req-mismatch")).thenReturn(Optional.of(auth));

        RequestSigner signer = new RequestSigner(
                "Representante", "Luis Perez", null, "es", "SIGNED", NOW, null, List.of(),
                "CERTIFICATE", "12345678Z", "READY",
                Map.of("serialNumber", "IDCES-12345678Z"));
        when(client.getRequest(eq("req-mismatch"), any())).thenReturn(new DigitalSignRequest(
                "req-mismatch", "COMPLETED", auth.id().toString(), "Anexo I", NOW, null, NOW,
                List.of(signer), new RequestDocuments(true, true)));

        VerifactuAuthorization result = service.syncRequestForWebhook("req-mismatch").orElseThrow();

        assertThat(result.status()).isEqualTo(VerifactuAuthorizationStatus.NIF_MISMATCH);
        assertThat(result.certificateNif()).isEqualTo("12345678Z");
        assertThat(result.validFrom()).isNull();
        assertThat(VerifactuAuthorizationService.maskNif("B09767898")).isEqualTo("B0****898");
        verify(client, never()).downloadSignedDocument(any(), any());
        verify(client, never()).downloadEvidenceReport(any(), any());
    }

    @Test
    void completedWithUnparseableOrMissingCertificateFailsClosedAsNifUnverified() {
        VerifactuAuthorization auth = inProgressAuth("B09767898", "req-unverified");
        when(store.findByDigitalSignRequestId("req-unverified")).thenReturn(Optional.of(auth));

        RequestSigner signer = new RequestSigner(
                "Representante", "Luis Perez", null, "es", "SIGNED", NOW, null, List.of(),
                "CERTIFICATE", null, "READY",
                Map.of("subject", "CN=UNKNOWN WITHOUT NIF"));
        when(client.getRequest(eq("req-unverified"), any())).thenReturn(new DigitalSignRequest(
                "req-unverified", "COMPLETED", auth.id().toString(), "Anexo I", NOW, null, NOW,
                List.of(signer), new RequestDocuments(true, true)));

        VerifactuAuthorization result = service.syncRequestForWebhook("req-unverified").orElseThrow();

        assertThat(result.status()).isEqualTo(VerifactuAuthorizationStatus.NIF_UNVERIFIED);
        assertThat(result.validFrom()).isNull();
        verify(client, never()).downloadSignedDocument(any(), any());
    }

    @Test
    void downloadFailureMarksDownloadFailedAndReconciliationRetriesToValid() {
        VerifactuAuthorization auth = inProgressAuth("B09767898", "req-dl");
        when(store.findByDigitalSignRequestId("req-dl")).thenReturn(Optional.of(auth));

        RequestSigner signer = new RequestSigner(
                "Representante", "Ana Lopez", null, "es", "SIGNED", NOW, null, List.of(),
                "CERTIFICATE", "B09767898", "READY",
                Map.of("organizationIdentifier", "VATES-B09767898"));
        DigitalSignRequest completed = new DigitalSignRequest(
                "req-dl", "COMPLETED", auth.id().toString(), "Anexo I", NOW, null, NOW,
                List.of(signer), new RequestDocuments(true, true));
        when(client.getRequest(eq("req-dl"), any())).thenReturn(completed);
        when(client.downloadSignedDocument(eq("req-dl"), any())).thenReturn(SIGNED_PDF);
        when(storage.storeSignedPdf("B09767898", auth.id(), SIGNED_PDF))
                .thenReturn(new StoredDocument("B09767898/" + auth.id() + "/signed.pdf", "a".repeat(64)));
        when(client.downloadEvidenceReport(eq("req-dl"), any()))
                .thenThrow(new DigitalSignException(
                        DigitalSignException.Kind.NETWORK_ERROR, 0, "deadline_exceeded", null, "timeout"));

        VerifactuAuthorization failed = service.syncRequestForWebhook("req-dl").orElseThrow();
        assertThat(failed.status()).isEqualTo(VerifactuAuthorizationStatus.DOWNLOAD_FAILED);
        assertThat(failed.failureReason()).isEqualTo("DOWNLOAD_TIMEOUT");
        assertThat(failed.signedPdfPath()).isNotNull();
        assertThat(failed.evidencePdfPath()).isNull();

        // Now reconciliation retries and downloads only the missing evidence PDF -> VALID.
        when(store.purgeWebhookEventsOlderThan(NOW.minus(Duration.ofDays(30)))).thenReturn(4);
        when(store.findDueForReconciliation(eq(NOW.minus(Duration.ofHours(1))), eq(50)))
                .thenReturn(List.of(failed));
        when(store.findByDigitalSignRequestId("req-dl")).thenReturn(Optional.of(failed));
        when(storage.exists(failed.signedPdfPath())).thenReturn(true);
        when(client.downloadEvidenceReport(eq("req-dl"), any())).thenReturn(EVIDENCE_PDF);
        when(storage.storeEvidencePdf("B09767898", auth.id(), EVIDENCE_PDF))
                .thenReturn(new StoredDocument("B09767898/" + auth.id() + "/evidence.pdf", "b".repeat(64)));

        ReconcileSummary summary = service.reconcile(NOW.plus(Duration.ofMinutes(2)));
        assertThat(summary).isEqualTo(new ReconcileSummary(1, 1, 4));
    }

    @Test
    void http410GoneTransitionsToTerminalDocumentsGone() {
        VerifactuAuthorization auth = inProgressAuth("B09767898", "req-410");
        when(store.findByDigitalSignRequestId("req-410")).thenReturn(Optional.of(auth));

        RequestSigner signer = new RequestSigner(
                "Representante", "Ana Lopez", null, "es", "SIGNED", NOW, null, List.of(),
                "CERTIFICATE", "B09767898", "READY",
                Map.of("organizationIdentifier", "VATES-B09767898"));
        when(client.getRequest(eq("req-410"), any())).thenReturn(new DigitalSignRequest(
                "req-410", "COMPLETED", auth.id().toString(), "Anexo I", NOW, null, NOW,
                List.of(signer), new RequestDocuments(true, true)));
        when(client.downloadSignedDocument(eq("req-410"), any()))
                .thenThrow(new DigitalSignException(DigitalSignException.Kind.GONE, 410, "gone", null, "Purged"));

        VerifactuAuthorization result = service.syncRequestForWebhook("req-410").orElseThrow();
        assertThat(result.status()).isEqualTo(VerifactuAuthorizationStatus.DOCUMENTS_GONE);
        assertThat(result.status().isActive()).isFalse();
        assertThat(result.status().isTerminal()).isTrue();
        assertThat(result.failureReason()).isEqualTo("DOCUMENTS_GONE");
    }

    @Test
    void failedWebhookDeletesDedupEventSoRedeliverySucceeds() {
        VerifactuAuthorization auth = inProgressAuth("B09767898", "req-crash");
        when(store.recordWebhookEventIfNew("evt_retry", "request.completed", "req-crash", NOW))
                .thenReturn(true)
                .thenReturn(true);
        when(store.findByDigitalSignRequestId("req-crash"))
                .thenThrow(new IllegalStateException("Transient DB outage"))
                .thenReturn(Optional.of(auth));
        when(client.getRequest(eq("req-crash"), any())).thenReturn(new DigitalSignRequest(
                "req-crash", "DECLINED", auth.id().toString(), "Anexo I", NOW, null, null,
                List.of(), new RequestDocuments(false, false)));

        assertThatThrownBy(() -> service.handleWebhook("evt_retry", "request.completed", "req-crash"))
                .isInstanceOf(IllegalStateException.class);
        verify(store).deleteWebhookEvent("evt_retry");
        verify(store, never()).markWebhookEventProcessed(eq("evt_retry"), any());

        // Redelivery now succeeds
        WebhookOutcome outcome = service.handleWebhook("evt_retry", "request.completed", "req-crash");
        assertThat(outcome).isEqualTo(WebhookOutcome.PROCESSED);
        verify(store).markWebhookEventProcessed("evt_retry", NOW);
    }

    @Test
    void terminalRowsAreNotResynced() {
        VerifactuAuthorization declined = inProgressAuth("B09767898", "req-term").withSync(
                VerifactuAuthorizationStatus.DECLINED, null, null, null, null, null, null, "DECLINED", null, NOW);
        when(store.findByDigitalSignRequestId("req-term")).thenReturn(Optional.of(declined));

        VerifactuAuthorization result = service.syncRequestForWebhook("req-term").orElseThrow();
        assertThat(result.status()).isEqualTo(VerifactuAuthorizationStatus.DECLINED);
        verify(client, never()).getRequest(any(), any(CallOptions.class));
    }

    @Test
    void duplicateWebhookEventSkipsApiCall() {
        when(store.recordWebhookEventIfNew("evt_dup", "request.completed", "req-1", NOW)).thenReturn(false);

        WebhookOutcome outcome = service.handleWebhook("evt_dup", "request.completed", "req-1");

        assertThat(outcome).isEqualTo(WebhookOutcome.DUPLICATE);
        verify(client, never()).getRequest(any(), any(CallOptions.class));
        verify(store, never()).markWebhookEventProcessed(any(), any());
    }

    @Test
    void mapsDeclinedExpiredAndCancelledDirectly() {
        for (String status : List.of("DECLINED", "EXPIRED", "CANCELLED")) {
            VerifactuAuthorization auth = inProgressAuth("B09767898", "req-" + status);
            when(store.findByDigitalSignRequestId("req-" + status)).thenReturn(Optional.of(auth));
            when(client.getRequest(eq("req-" + status), any())).thenReturn(new DigitalSignRequest(
                    "req-" + status, status, auth.id().toString(), "Anexo I", NOW, null, null,
                    List.of(), new RequestDocuments(false, false)));

            VerifactuAuthorization updated = service.syncRequestForWebhook("req-" + status).orElseThrow();
            assertThat(updated.status().name()).isEqualTo(status);
            assertThat(updated.failureReason()).isEqualTo(status);
        }
    }

    @Test
    void lostCompareAndSetWhileStillActiveRetriesOnceAndPersistsValid() {
        VerifactuAuthorization v0 = inProgressAuth("B09767898", "req-cas");
        VerifactuAuthorization v1InProgress = v0.withSync(
                VerifactuAuthorizationStatus.IN_PROGRESS, null, null, null, null, null, null, null, null, NOW, 0)
                .withVersion(1);
        when(store.findByDigitalSignRequestId("req-cas"))
                .thenReturn(Optional.of(v0))
                .thenReturn(Optional.of(v1InProgress));
        when(store.findById(v0.id())).thenReturn(Optional.of(v1InProgress));

        RequestSigner signer = new RequestSigner(
                "Representante", "Ana Lopez", null, "es", "SIGNED", NOW, null, List.of(),
                "CERTIFICATE", "B09767898", "READY",
                Map.of("organizationIdentifier", "VATES-B09767898"));
        when(client.getRequest(eq("req-cas"), any())).thenReturn(new DigitalSignRequest(
                "req-cas", "COMPLETED", v0.id().toString(), "Anexo I", NOW, null, NOW,
                List.of(signer), new RequestDocuments(true, true)));
        when(client.downloadSignedDocument(eq("req-cas"), any())).thenReturn(SIGNED_PDF);
        when(client.downloadEvidenceReport(eq("req-cas"), any())).thenReturn(EVIDENCE_PDF);
        when(storage.storeSignedPdf("B09767898", v0.id(), SIGNED_PDF))
                .thenReturn(new StoredDocument("B09767898/" + v0.id() + "/signed.pdf", "a".repeat(64)));
        when(storage.storeEvidencePdf("B09767898", v0.id(), EVIDENCE_PDF))
                .thenReturn(new StoredDocument("B09767898/" + v0.id() + "/evidence.pdf", "b".repeat(64)));

        // First CAS loses to a concurrent IN_PROGRESS refresh (version 0 -> 1); retry with version 1 wins!
        doReturn(new UpdateResult(v1InProgress, false))
                .doAnswer(inv -> {
                    VerifactuAuthorization arg = inv.getArgument(0);
                    return new UpdateResult(arg.withVersion(arg.version() + 1), true);
                })
                .when(store).compareAndSet(any());

        VerifactuAuthorization result = service.syncRequestForWebhook("req-cas").orElseThrow();
        assertThat(result.status()).isEqualTo(VerifactuAuthorizationStatus.VALID);
        assertThat(result.version()).isEqualTo(2);
        verify(store, times(2)).compareAndSet(any());
    }

    @Test
    void get404Or410MovesToTerminalOnHoldAfterThreeAttempts() {
        VerifactuAuthorization current = inProgressAuth("B09767898", "req-404");
        when(client.getRequest(eq("req-404"), any()))
                .thenThrow(new DigitalSignException(DigitalSignException.Kind.NOT_FOUND, 404, "not_found", null, "Missing"));

        for (int attempt = 1; attempt <= 2; attempt++) {
            when(store.findByDigitalSignRequestId("req-404")).thenReturn(Optional.of(current));
            current = service.syncRequestForWebhook("req-404").orElseThrow();
            assertThat(current.status()).isEqualTo(VerifactuAuthorizationStatus.IN_PROGRESS);
            assertThat(current.syncFailureCount()).isEqualTo(attempt);
            assertThat(current.failureReason()).isEqualTo("REQUEST_NOT_FOUND");
        }

        when(store.findByDigitalSignRequestId("req-404")).thenReturn(Optional.of(current));
        VerifactuAuthorization terminal = service.syncRequestForWebhook("req-404").orElseThrow();
        assertThat(terminal.status()).isEqualTo(VerifactuAuthorizationStatus.DOCUMENTS_GONE);
        assertThat(terminal.status().isTerminal()).isTrue();
        assertThat(terminal.syncFailureCount()).isEqualTo(3);
        assertThat(terminal.failureReason()).isEqualTo("REQUEST_NOT_FOUND");
    }

    @Test
    void completedWithDocumentsFalseMovesToTerminalOnHoldAfterThreeAttemptsOrSevenDays() {
        VerifactuAuthorization current = inProgressAuth("B09767898", "req-docs-false");
        RequestSigner signer = new RequestSigner(
                "Representante", "Ana Lopez", null, "es", "SIGNED", NOW, null, List.of(),
                "CERTIFICATE", "B09767898", "READY",
                Map.of("organizationIdentifier", "VATES-B09767898"));
        when(client.getRequest(eq("req-docs-false"), any())).thenReturn(new DigitalSignRequest(
                "req-docs-false", "COMPLETED", current.id().toString(), "Anexo I", NOW, null, NOW,
                List.of(signer), new RequestDocuments(false, false)));

        for (int attempt = 1; attempt <= 2; attempt++) {
            when(store.findByDigitalSignRequestId("req-docs-false")).thenReturn(Optional.of(current));
            current = service.syncRequestForWebhook("req-docs-false").orElseThrow();
            assertThat(current.status()).isEqualTo(VerifactuAuthorizationStatus.DOWNLOAD_FAILED);
            assertThat(current.syncFailureCount()).isEqualTo(attempt);
            assertThat(current.failureReason()).isEqualTo("DOCUMENTS_NOT_READY");
        }

        when(store.findByDigitalSignRequestId("req-docs-false")).thenReturn(Optional.of(current));
        VerifactuAuthorization terminal = service.syncRequestForWebhook("req-docs-false").orElseThrow();
        assertThat(terminal.status()).isEqualTo(VerifactuAuthorizationStatus.DOCUMENTS_GONE);
        assertThat(terminal.status().isTerminal()).isTrue();
        assertThat(terminal.failureReason()).isEqualTo("DOCUMENTS_NOT_READY");

        // Request created 10 days ago does NOT age out on its first failure; aging counts 7 days from first failure.
        VerifactuAuthorization old = new VerifactuAuthorization(
                UUID.randomUUID(), "B09767898", "Restaurante Prueba SL",
                VerifactuAuthorizationStatus.IN_PROGRESS, "req-old", "verifactu-B09767898-2", 2,
                "tpl-anexo-1", null, null, null, null, null, null, null, "owner1",
                NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(10)), null, null, null, 0, 0, null);
        when(store.findByDigitalSignRequestId("req-old")).thenReturn(Optional.of(old));
        when(client.getRequest(eq("req-old"), any())).thenReturn(new DigitalSignRequest(
                "req-old", "COMPLETED", old.id().toString(), "Anexo I",
                NOW.minus(Duration.ofDays(10)), null, NOW,
                List.of(signer), new RequestDocuments(true, false)));

        VerifactuAuthorization firstFailure = service.syncRequestForWebhook("req-old").orElseThrow();
        assertThat(firstFailure.status()).isEqualTo(VerifactuAuthorizationStatus.DOWNLOAD_FAILED);
        assertThat(firstFailure.syncFailureCount()).isEqualTo(1);
        assertThat(firstFailure.syncFailureSince()).isEqualTo(NOW);

        // Once syncFailureSince is >= 7 days in the past, the next counted failure moves it to terminal.
        VerifactuAuthorization aged = new VerifactuAuthorization(
                UUID.randomUUID(), "B09767898", "Restaurante Prueba SL",
                VerifactuAuthorizationStatus.DOWNLOAD_FAILED, "req-aged", "verifactu-B09767898-3", 3,
                "tpl-anexo-1", null, null, null, null, null, null, "DOCUMENTS_NOT_READY", "owner1",
                NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(7)), null, null, null, 0,
                1, NOW.minus(Duration.ofDays(7)));
        when(store.findByDigitalSignRequestId("req-aged")).thenReturn(Optional.of(aged));
        when(client.getRequest(eq("req-aged"), any())).thenReturn(new DigitalSignRequest(
                "req-aged", "COMPLETED", aged.id().toString(), "Anexo I",
                NOW.minus(Duration.ofDays(10)), null, NOW.minus(Duration.ofDays(7)),
                List.of(signer), new RequestDocuments(true, false)));

        VerifactuAuthorization agedOut = service.syncRequestForWebhook("req-aged").orElseThrow();
        assertThat(agedOut.status()).isEqualTo(VerifactuAuthorizationStatus.DOCUMENTS_GONE);
        assertThat(agedOut.failureReason()).isEqualTo("DOCUMENTS_NOT_READY");
    }

    @Test
    void ownerDrivenRefreshDoesNotConsumeBoundedFailureAttempts() {
        VerifactuAuthorization active = inProgressAuth("B09767898", "req-refresh");
        when(store.findActiveByNif("B09767898")).thenReturn(Optional.of(active));
        when(store.findByDigitalSignRequestId("req-refresh")).thenReturn(Optional.of(active));
        when(client.getRequest(eq("req-refresh"), any()))
                .thenThrow(new DigitalSignException(DigitalSignException.Kind.NOT_FOUND, 404, "not_found", null, "Missing"));

        for (int i = 0; i < 5; i++) {
            VerifactuAuthorization after = service.refreshForNifOnPageLoad("B09767898").orElseThrow();
            assertThat(after.status()).isEqualTo(VerifactuAuthorizationStatus.IN_PROGRESS);
            assertThat(after.syncFailureCount()).isZero();
            assertThat(after.syncFailureSince()).isNull();
        }
        VerifactuAuthorization manual = service.refreshForNif("B09767898").orElseThrow();
        assertThat(manual.status()).isEqualTo(VerifactuAuthorizationStatus.IN_PROGRESS);
        assertThat(manual.syncFailureCount()).isZero();
        assertThat(manual.syncFailureSince()).isNull();
    }

    @Test
    void startSigningFailsGracefullyWhenNotConfiguredOrRequiredTemplateFieldMissing() {
        FiscalProfile complete = completeProfile("B09767898");

        // No templateId configured
        StartSigningResult unconfigured = service.startSigning(complete, "owner1", "Ana Lopez", "ana@example.es", "es");
        assertThat(unconfigured.outcome()).isEqualTo(StartSigningResult.Outcome.SIGNING_UNAVAILABLE);

        // Configured, but template requires a TEXT field we have no configured key for
        properties.setApiToken("secret-token");
        properties.getVerifactu().setTemplateId("tpl-anexo-1");
        properties.getVerifactu().getFields().setLegalName("fld_name");
        when(client.getTemplate("tpl-anexo-1")).thenReturn(new DigitalSignTemplate(
                "tpl-anexo-1", "Anexo I",
                List.of(new TemplateSigner(0, "Representante", true, List.of())),
                List.of(
                        new TemplateField("fld_name", "Razón social", "TEXT", true, "NONE"),
                        new TemplateField("fld_unknown", "Campo extra", "TEXT", true, "NONE"))));

        StartSigningResult missingField = service.startSigning(complete, "owner1", "Ana Lopez", "ana@example.es", "es");
        assertThat(missingField.outcome()).isEqualTo(StartSigningResult.Outcome.SIGNING_UNAVAILABLE);
        verify(client, never()).createRequest(any(), anyString());
    }

    @Test
    void startSigningCreatesRequestToleratingExtraRoleAndResumesExistingInProgress() {
        FiscalProfile complete = completeProfile("B09767898");
        properties.setApiToken("secret-token");
        properties.getVerifactu().setTemplateId("tpl-anexo-1");
        properties.getVerifactu().getFields().setLegalName("fld_name");
        properties.getVerifactu().getFields().setNif("fld_nif");
        properties.getVerifactu().getFields().setBrasatechNif("fld_bt_nif");

        when(store.findValidByNif("B09767898")).thenReturn(Optional.empty());
        when(store.findActiveByNif("B09767898")).thenReturn(Optional.empty());
        when(store.nextAttemptForNif("B09767898")).thenReturn(2);
        when(client.getTemplate("tpl-anexo-1")).thenReturn(new DigitalSignTemplate(
                "tpl-anexo-1", "Anexo I",
                List.of(
                        new TemplateSigner(0, "Brasatech", true, List.of()),
                        new TemplateSigner(1, "Representante", true, List.of())),
                List.of(
                        new TemplateField("fld_name", "Razón social", "TEXT", true, "NONE"),
                        new TemplateField("fld_nif", "NIF", "TEXT", true, "DNI_NIE"),
                        new TemplateField("fld_bt_nif", "NIF Brasatech", "TEXT", true, "DNI_NIE"))));
        when(client.createRequest(any(), eq("verifactu-B09767898-2"))).thenReturn(new CreateRequestResult(
                new DigitalSignRequest("req-new", "IN_PROGRESS", "ext", "Anexo I", NOW, null, null,
                        List.of(new RequestSigner("Representante", "Ana Lopez", "ana@example.es", "es",
                                "PENDING", null, "https://sign.example/secret-link", List.of(),
                                "CERTIFICATE", "B09767898", "WAITING_FOR_SIGNER", null)),
                        new RequestDocuments(false, false)),
                false));

        StartSigningResult created = service.startSigning(complete, "owner1", "Ana Lopez", "ana@example.es", "es");
        assertThat(created.outcome()).isEqualTo(StartSigningResult.Outcome.SIGNING_URL);
        assertThat(created.signingUrl()).isEqualTo("https://sign.example/secret-link");
        assertThat(created.toString()).doesNotContain("secret-link").contains("[REDACTED]");

        ArgumentCaptor<CreateRequestCommand> cmdCaptor = ArgumentCaptor.forClass(CreateRequestCommand.class);
        verify(client).createRequest(cmdCaptor.capture(), eq("verifactu-B09767898-2"));
        CreateRequestCommand cmd = cmdCaptor.getValue();
        assertThat(cmd.sendEmail()).isFalse();
        assertThat(cmd.signers()).hasSize(1);
        assertThat(cmd.signers().getFirst().signatureMethod()).isEqualTo("CERTIFICATE");
        assertThat(cmd.signers().getFirst().nif()).isEqualTo("B09767898");
        assertThat(cmd.values()).containsEntry("fld_nif", "B09767898").containsEntry("fld_bt_nif", "B09767898");

        // Resuming an active IN_PROGRESS request fetches a fresh signing URL without creating a duplicate.
        VerifactuAuthorization active = inProgressAuth("B09767898", "req-new");
        when(store.findActiveByNif("B09767898")).thenReturn(Optional.of(active));
        when(client.createSigningUrl("req-new", "Representante"))
                .thenReturn(new SigningUrlResponse("req-new", "Representante", "https://sign.example/fresh-link"));

        StartSigningResult resumed = service.startSigning(complete, "owner1", "Ana Lopez", "ana@example.es", "es");
        assertThat(resumed.outcome()).isEqualTo(StartSigningResult.Outcome.SIGNING_URL);
        assertThat(resumed.signingUrl()).isEqualTo("https://sign.example/fresh-link");
        verify(client, times(1)).createRequest(any(), anyString());
    }

    @Test
    void startSigningReturnsUnavailableWhenVerifiedOwnerEmailIsMissingEvenIfFiscalProfileHasEmail() {
        when(client.isConfigured()).thenReturn(true);
        properties.setApiToken("secret-token");
        properties.getVerifactu().setTemplateId("tpl-anexo-1");
        properties.getVerifactu().setSignerRole("Representante");
        properties.getVerifactu().getFields().setLegalName("fld_name");
        properties.getVerifactu().getFields().setNif("fld_nif");
        properties.getVerifactu().getFields().setBrasatechNif("fld_bt_nif");
        DigitalSignTemplate template = new DigitalSignTemplate(
                "tpl-anexo-1", "Anexo I",
                List.of(new TemplateSigner(1, "Representante", true, List.of())),
                List.of(
                        new TemplateField("fld_name", "Razón social", "TEXT", true, "NONE"),
                        new TemplateField("fld_nif", "NIF", "TEXT", true, "DNI_NIE"),
                        new TemplateField("fld_bt_nif", "NIF Brasatech", "TEXT", true, "DNI_NIE")));
        when(store.findValidByNif("B09767898")).thenReturn(Optional.empty());
        when(store.findActiveByNif("B09767898")).thenReturn(Optional.empty());
        when(client.getTemplate("tpl-anexo-1")).thenReturn(template);

        FiscalProfile completeWithEmail = completeProfile("B09767898");
        assertThat(completeWithEmail.email()).isEqualTo("ana@example.es");

        StartSigningResult result = service.startSigning(completeWithEmail, "owner1", "Ana Lopez", null, "es");

        assertThat(result.outcome()).isEqualTo(StartSigningResult.Outcome.SIGNING_UNAVAILABLE);
        verify(client, never()).createRequest(any(), anyString());
    }

    @Test
    void completedTestRequestInProductionIsRefusedAndNeverValid() {
        VerifactuAuthorization auth = inProgressAuth("B09767898", "req-test-completed");
        when(store.findByDigitalSignRequestId("req-test-completed")).thenReturn(Optional.of(auth));

        RequestSigner restaurantSigner = new RequestSigner(
                "Representante", "Ana Lopez", null, "es", "SIGNED", NOW,
                "https://digital-sign.brasatech.es/sign/secret-token-should-never-log", List.of(),
                "CERTIFICATE", "B09767898", "READY",
                Map.of("subject", "CN=ANA LOPEZ, OID.2.5.4.97=VATES-B09767898"));

        when(client.getRequest(eq("req-test-completed"), any())).thenReturn(new DigitalSignRequest(
                "req-test-completed", "COMPLETED", auth.id().toString(), "Anexo I", NOW.minusSeconds(60), null, NOW,
                List.of(restaurantSigner), new RequestDocuments(true, true), true));

        VerifactuAuthorization result = service.syncRequestForWebhook("req-test-completed").orElseThrow();

        assertThat(result.status()).isEqualTo(VerifactuAuthorizationStatus.TEST_ENVIRONMENT);
        assertThat(result.status().isTerminal()).isTrue();
        assertThat(result.isCurrentlyValid()).isFalse();
        assertThat(result.validFrom()).isNull();
        assertThat(result.failureReason()).isEqualTo("TEST_ENVIRONMENT");
        verify(client, never()).downloadSignedDocument(any(), any());
        verify(client, never()).downloadEvidenceReport(any(), any());
    }

    @Test
    void inProgressTestRequestInProductionIsRefusedOnSync() {
        VerifactuAuthorization auth = inProgressAuth("B09767898", "req-test-progress");
        when(store.findByDigitalSignRequestId("req-test-progress")).thenReturn(Optional.of(auth));

        RequestSigner restaurantSigner = new RequestSigner(
                "Representante", "Ana Lopez", null, "es", "PENDING", null,
                "https://digital-sign.brasatech.es/sign/secret-token-xyz", List.of(),
                "CERTIFICATE", "B09767898", "PENDING", null);

        when(client.getRequest(eq("req-test-progress"), any())).thenReturn(new DigitalSignRequest(
                "req-test-progress", "IN_PROGRESS", auth.id().toString(), "Anexo I", NOW.minusSeconds(60), null, null,
                List.of(restaurantSigner), new RequestDocuments(false, false), true));

        VerifactuAuthorization result = service.syncRequestForWebhook("req-test-progress").orElseThrow();

        assertThat(result.status()).isEqualTo(VerifactuAuthorizationStatus.TEST_ENVIRONMENT);
        assertThat(result.failureReason()).isEqualTo("TEST_ENVIRONMENT");
        assertThat(result.validFrom()).isNull();
    }

    @Test
    void startSigningRefusesTestRequestInProductionWithoutReturningSigningUrl() {
        properties.setApiToken("secret-token");
        properties.getVerifactu().setTemplateId("tpl-anexo-1");
        properties.getVerifactu().getFields().setLegalName("fld_legal");
        properties.getVerifactu().getFields().setNif("fld_nif");
        when(store.findValidByNif("B09767898")).thenReturn(Optional.empty());
        when(store.findActiveByNif("B09767898")).thenReturn(Optional.empty());
        when(store.nextAttemptForNif("B09767898")).thenReturn(1);
        when(client.getTemplate("tpl-anexo-1")).thenReturn(new DigitalSignTemplate(
                "tpl-anexo-1", "Anexo I",
                List.of(new TemplateSigner(0, "Representante", true, List.of())),
                List.of(
                        new TemplateField("fld_legal", "Razón social", "TEXT", true, "NONE"),
                        new TemplateField("fld_nif", "NIF", "TEXT", true, "NONE"))));
        RequestSigner signer = new RequestSigner(
                "Representante", "Ana Lopez", "ana@example.es", "es", "PENDING", null,
                "https://digital-sign.brasatech.es/sign/test-secret-url", List.of(),
                "CERTIFICATE", "B09767898", "PENDING", null);
        when(client.createRequest(any(CreateRequestCommand.class), eq("verifactu-B09767898-1")))
                .thenReturn(new CreateRequestResult(
                        new DigitalSignRequest("req-test-create", "IN_PROGRESS", "ext", "Anexo I", NOW, null, null,
                                List.of(signer), new RequestDocuments(false, false), true),
                        false));

        StartSigningResult result = service.startSigning(
                completeProfile("B09767898"), "owner1", "Ana Lopez", "ana@example.es", "es");

        assertThat(result.outcome()).isEqualTo(StartSigningResult.Outcome.SIGNING_UNAVAILABLE);
        assertThat(result.reason()).isEqualTo("TEST_ENVIRONMENT");
        assertThat(result.signingUrl()).isNull();
        ArgumentCaptor<VerifactuAuthorization> inserted = ArgumentCaptor.forClass(VerifactuAuthorization.class);
        verify(store).insert(inserted.capture());
        assertThat(inserted.getValue().status()).isEqualTo(VerifactuAuthorizationStatus.TEST_ENVIRONMENT);
        assertThat(inserted.getValue().failureReason()).isEqualTo("TEST_ENVIRONMENT");
        assertThat(inserted.getValue().validFrom()).isNull();
        verify(client, never()).createSigningUrl(anyString(), anyString());
    }

    @Test
    void sandboxAcceptsTestRequestOnCreateAndSyncToValid() {
        VerifactuAuthorizationService sandboxService = new VerifactuAuthorizationService(
                store, storage, client, properties,
                new PlatformEnvironment("sandbox"), Clock.fixed(NOW, ZoneOffset.UTC), false,
                "BRASATECH DIGITAL SOLUTIONS, S.L.U.", "B09767898",
                "Calle Ganaderos, 24, bajo, 03130 Santa Pola (Alicante), España");

        properties.setApiToken("secret-token");
        properties.getVerifactu().setTemplateId("tpl-anexo-1");
        properties.getVerifactu().getFields().setLegalName("fld_legal");
        properties.getVerifactu().getFields().setNif("fld_nif");
        when(store.findValidByNif("B09767898")).thenReturn(Optional.empty());
        when(store.findActiveByNif("B09767898")).thenReturn(Optional.empty());
        when(store.nextAttemptForNif("B09767898")).thenReturn(1);
        when(client.getTemplate("tpl-anexo-1")).thenReturn(new DigitalSignTemplate(
                "tpl-anexo-1", "Anexo I",
                List.of(new TemplateSigner(0, "Representante", true, List.of())),
                List.of(
                        new TemplateField("fld_legal", "Razón social", "TEXT", true, "NONE"),
                        new TemplateField("fld_nif", "NIF", "TEXT", true, "NONE"))));
        RequestSigner pendingSigner = new RequestSigner(
                "Representante", "Ana Lopez", "ana@example.es", "es", "PENDING", null,
                "https://digital-sign.brasatech.es/sign/sandbox-test-url", List.of(),
                "CERTIFICATE", "B09767898", "PENDING", null);
        when(client.createRequest(any(CreateRequestCommand.class), eq("verifactu-B09767898-1")))
                .thenReturn(new CreateRequestResult(
                        new DigitalSignRequest("req-sbx-test", "IN_PROGRESS", "ext", "Anexo I", NOW, null, null,
                                List.of(pendingSigner), new RequestDocuments(false, false), true),
                        false));

        StartSigningResult started = sandboxService.startSigning(
                completeProfile("B09767898"), "owner1", "Ana Lopez", "ana@example.es", "es");

        assertThat(started.outcome()).isEqualTo(StartSigningResult.Outcome.SIGNING_URL);
        assertThat(started.signingUrl()).isEqualTo("https://digital-sign.brasatech.es/sign/sandbox-test-url");
        assertThat(started.authorization().status()).isEqualTo(VerifactuAuthorizationStatus.IN_PROGRESS);

        VerifactuAuthorization inProgress = started.authorization();
        when(store.findByDigitalSignRequestId("req-sbx-test")).thenReturn(Optional.of(inProgress));
        RequestSigner signedSigner = new RequestSigner(
                "Representante", "Ana Lopez", null, "es", "SIGNED", NOW,
                null, List.of(), "CERTIFICATE", "B09767898", "READY",
                Map.of("subject", "CN=ANA LOPEZ, OID.2.5.4.97=VATES-B09767898"));
        when(client.getRequest(eq("req-sbx-test"), any())).thenReturn(new DigitalSignRequest(
                "req-sbx-test", "COMPLETED", inProgress.id().toString(), "Anexo I", NOW.minusSeconds(60), null, NOW,
                List.of(signedSigner), new RequestDocuments(true, true), true));
        byte[] pdfBytes = "%PDF-1.4\n%test\n".getBytes();
        when(client.downloadSignedDocument(eq("req-sbx-test"), any())).thenReturn(pdfBytes);
        when(client.downloadEvidenceReport(eq("req-sbx-test"), any())).thenReturn(pdfBytes);
        when(storage.storeSignedPdf(eq("B09767898"), eq(inProgress.id()), any()))
                .thenReturn(new StoredDocument("B09767898/signed.pdf", "sha-s"));
        when(storage.storeEvidencePdf(eq("B09767898"), eq(inProgress.id()), any()))
                .thenReturn(new StoredDocument("B09767898/evidence.pdf", "sha-e"));

        VerifactuAuthorization synced = sandboxService.syncRequestForWebhook("req-sbx-test").orElseThrow();

        assertThat(synced.status()).isEqualTo(VerifactuAuthorizationStatus.VALID);
        assertThat(synced.isCurrentlyValid()).isTrue();
        assertThat(synced.validFrom()).isNotNull();
    }

    @Test
    void sandboxRefusesLiveRequestOnCreateWithoutReturningSigningUrl() {
        VerifactuAuthorizationService sandboxService = new VerifactuAuthorizationService(
                store, storage, client, properties,
                new PlatformEnvironment("sandbox"), Clock.fixed(NOW, ZoneOffset.UTC), false,
                "BRASATECH DIGITAL SOLUTIONS, S.L.U.", "B09767898",
                "Calle Ganaderos, 24, bajo, 03130 Santa Pola (Alicante), España");

        properties.setApiToken("secret-token");
        properties.getVerifactu().setTemplateId("tpl-anexo-1");
        properties.getVerifactu().getFields().setLegalName("fld_legal");
        properties.getVerifactu().getFields().setNif("fld_nif");
        when(store.findValidByNif("B09767898")).thenReturn(Optional.empty());
        when(store.findActiveByNif("B09767898")).thenReturn(Optional.empty());
        when(store.nextAttemptForNif("B09767898")).thenReturn(1);
        when(client.getTemplate("tpl-anexo-1")).thenReturn(new DigitalSignTemplate(
                "tpl-anexo-1", "Anexo I",
                List.of(new TemplateSigner(0, "Representante", true, List.of())),
                List.of(
                        new TemplateField("fld_legal", "Razón social", "TEXT", true, "NONE"),
                        new TemplateField("fld_nif", "NIF", "TEXT", true, "NONE"))));
        RequestSigner signer = new RequestSigner(
                "Representante", "Ana Lopez", "ana@example.es", "es", "PENDING", null,
                "https://digital-sign.brasatech.es/sign/live-secret-url", List.of(),
                "CERTIFICATE", "B09767898", "PENDING", null);
        when(client.createRequest(any(CreateRequestCommand.class), eq("verifactu-B09767898-1")))
                .thenReturn(new CreateRequestResult(
                        new DigitalSignRequest("req-live-create", "IN_PROGRESS", "ext", "Anexo I", NOW, null, null,
                                List.of(signer), new RequestDocuments(false, false), false),
                        false));

        StartSigningResult result = sandboxService.startSigning(
                completeProfile("B09767898"), "owner1", "Ana Lopez", "ana@example.es", "es");

        assertThat(result.outcome()).isEqualTo(StartSigningResult.Outcome.SIGNING_UNAVAILABLE);
        assertThat(result.reason()).isEqualTo("LIVE_ENVIRONMENT");
        assertThat(result.signingUrl()).isNull();
        ArgumentCaptor<VerifactuAuthorization> inserted = ArgumentCaptor.forClass(VerifactuAuthorization.class);
        verify(store).insert(inserted.capture());
        assertThat(inserted.getValue().status()).isEqualTo(VerifactuAuthorizationStatus.LIVE_ENVIRONMENT);
        assertThat(inserted.getValue().failureReason()).isEqualTo("LIVE_ENVIRONMENT");
        assertThat(inserted.getValue().validFrom()).isNull();
        verify(client, never()).createSigningUrl(anyString(), anyString());
    }

    @Test
    void sandboxRefusesLiveRequestOnSyncEvenWhenCompleted() {
        VerifactuAuthorizationService sandboxService = new VerifactuAuthorizationService(
                store, storage, client, properties,
                new PlatformEnvironment("sandbox"), Clock.fixed(NOW, ZoneOffset.UTC), false,
                "BRASATECH DIGITAL SOLUTIONS, S.L.U.", "B09767898",
                "Calle Ganaderos, 24, bajo, 03130 Santa Pola (Alicante), España");

        VerifactuAuthorization auth = inProgressAuth("B09767898", "req-live-completed");
        when(store.findByDigitalSignRequestId("req-live-completed")).thenReturn(Optional.of(auth));

        RequestSigner restaurantSigner = new RequestSigner(
                "Representante", "Ana Lopez", null, "es", "SIGNED", NOW,
                "https://digital-sign.brasatech.es/sign/live-secret-token", List.of(),
                "CERTIFICATE", "B09767898", "READY",
                Map.of("subject", "CN=ANA LOPEZ, OID.2.5.4.97=VATES-B09767898"));

        when(client.getRequest(eq("req-live-completed"), any())).thenReturn(new DigitalSignRequest(
                "req-live-completed", "COMPLETED", auth.id().toString(), "Anexo I", NOW.minusSeconds(60), null, NOW,
                List.of(restaurantSigner), new RequestDocuments(true, true), false));

        VerifactuAuthorization result = sandboxService.syncRequestForWebhook("req-live-completed").orElseThrow();

        assertThat(result.status()).isEqualTo(VerifactuAuthorizationStatus.LIVE_ENVIRONMENT);
        assertThat(result.status().isTerminal()).isTrue();
        assertThat(result.isCurrentlyValid()).isFalse();
        assertThat(result.validFrom()).isNull();
        assertThat(result.failureReason()).isEqualTo("LIVE_ENVIRONMENT");
        verify(client, never()).downloadSignedDocument(any(), any());
        verify(client, never()).downloadEvidenceReport(any(), any());
    }

    @Test
    void webhookStateChangeEnqueuesNotificationWithoutSmtpAndSwallowsNotifierErrors() {
        VerifactuAuthorization inProgress = inProgressAuth("B09767898", "req-notify-1");
        when(store.recordWebhookEventIfNew(eq("evt-notify-1"), eq("request.declined"), eq("req-notify-1"), any())).thenReturn(true);
        when(store.findByDigitalSignRequestId("req-notify-1")).thenReturn(Optional.of(inProgress));
        when(client.getRequest(eq("req-notify-1"), any())).thenReturn(new DigitalSignRequest(
                "req-notify-1", "DECLINED", inProgress.id().toString(), "Anexo I",
                NOW, NOW.plusSeconds(3600), null,
                List.of(new RequestSigner("Representante", "Ana", null, "es", "DECLINED", null, null, List.of(), "CERTIFICATE", "B09767898", null, null)),
                new RequestDocuments(false, false)));

        VerifactuNotificationService notifier = mock(VerifactuNotificationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<VerifactuNotificationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(notifier);
        when(notifier.evaluateForNifEnqueueOnly("B09767898")).thenThrow(new RuntimeException("mail db hiccup"));
        service.setNotificationServiceProvider(provider);

        WebhookOutcome outcome = service.handleWebhook("evt-notify-1", "request.declined", "req-notify-1");

        assertThat(outcome).isEqualTo(WebhookOutcome.PROCESSED);
        verify(notifier).evaluateForNifEnqueueOnly("B09767898");
        verify(notifier, never()).evaluateForNif(any());
        verify(store).markWebhookEventProcessed(eq("evt-notify-1"), any());
    }

    @Test
    void revokeMarksValidRowRevokedPreservesPdfsNotifiesAndBlocksLaterWebhookOrRefresh() {
        Instant validFrom = NOW.minusSeconds(3600);
        VerifactuAuthorization valid = inProgressAuth("B09767898", "req-rev-1").withSync(
                VerifactuAuthorizationStatus.VALID, "Ana Lopez", "B09767898",
                "B09767898/signed.pdf", "a".repeat(64),
                "B09767898/evidence.pdf", "b".repeat(64),
                null, validFrom, validFrom);
        when(store.findValidByNif("B09767898")).thenReturn(Optional.of(valid));

        VerifactuNotificationService notifier = mock(VerifactuNotificationService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<VerifactuNotificationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(notifier);
        service.setNotificationServiceProvider(provider);

        var revokeResult = service.revoke("B09767898", "ana");

        assertThat(revokeResult.outcome()).isEqualTo(VerifactuAuthorizationService.RevokeResult.Outcome.REVOKED);
        VerifactuAuthorization revoked = revokeResult.authorization();
        assertThat(revoked.status()).isEqualTo(VerifactuAuthorizationStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(NOW);
        assertThat(revoked.revokedBy()).isEqualTo("ana");
        assertThat(revoked.validFrom()).isEqualTo(validFrom);
        assertThat(revoked.hasStoredDocuments()).isTrue();
        assertThat(revoked.isCurrentlyValid()).isFalse();
        assertThat(revoked.wasValidAt(NOW.minusSeconds(1))).isTrue();
        assertThat(revoked.wasValidAt(NOW)).isFalse();
        verify(notifier).evaluateForNifEnqueueOnly("B09767898");

        // A later webhook or sync for the revoked request MUST NOT touch Digital Sign or restore VALID
        when(store.recordWebhookEventIfNew(eq("evt-late-1"), eq("request.completed"), eq("req-rev-1"), any())).thenReturn(true);
        when(store.findByDigitalSignRequestId("req-rev-1")).thenReturn(Optional.of(revoked));
        when(store.findActiveByNif("B09767898")).thenReturn(Optional.empty());
        when(store.findValidByNif("B09767898")).thenReturn(Optional.empty());
        when(store.findLatestByNif("B09767898")).thenReturn(Optional.of(revoked));

        WebhookOutcome webhookOutcome = service.handleWebhook("evt-late-1", "request.completed", "req-rev-1");
        assertThat(webhookOutcome).isEqualTo(WebhookOutcome.PROCESSED);
        Optional<VerifactuAuthorization> refreshed = service.refreshForNif("B09767898");
        assertThat(refreshed).contains(revoked);
        assertThat(refreshed.get().status()).isEqualTo(VerifactuAuthorizationStatus.REVOKED);
        verify(client, never()).getRequest(eq("req-rev-1"), any());

        // Revoking again when no VALID row exists returns NOT_VALID
        assertThat(service.revoke("B09767898", "ana").outcome())
                .isEqualTo(VerifactuAuthorizationService.RevokeResult.Outcome.NOT_VALID);
    }

    @Test
    void startSigningAfterRevokeCreatesIncrementedAttemptAndNewIdempotencyKey() {
        properties.setApiToken("secret-token");
        properties.getVerifactu().setTemplateId("tpl-anexo-1");
        properties.getVerifactu().getFields().setNif("fld_nif");
        when(store.findValidByNif("B09767898")).thenReturn(Optional.empty());
        when(store.findActiveByNif("B09767898")).thenReturn(Optional.empty());
        when(store.nextAttemptForNif("B09767898")).thenReturn(2);
        when(client.getTemplate("tpl-anexo-1")).thenReturn(new DigitalSignTemplate(
                "tpl-anexo-1", "Anexo I",
                List.of(new TemplateSigner(0, "Representante", true, List.of())),
                List.of(new TemplateField("fld_nif", "NIF", "TEXT", true, "NONE"))));
        when(client.createRequest(any(), eq("verifactu-B09767898-2"))).thenReturn(new CreateRequestResult(
                new DigitalSignRequest("req-attempt-2", "IN_PROGRESS", "ext-2", "Anexo I", NOW, null, null,
                        List.of(new RequestSigner("Representante", "Ana", "ana@example.es", "es", "PENDING", null,
                                "https://digital-sign.brasatech.es/sign/attempt-2", List.of(), "CERTIFICATE", "B09767898", null, null)),
                        new RequestDocuments(false, false), false),
                false));

        StartSigningResult result = service.startSigning(
                completeProfile("B09767898"), "ana", "Ana Lopez", "ana@example.es", "es");

        assertThat(result.outcome()).isEqualTo(StartSigningResult.Outcome.SIGNING_URL);
        assertThat(result.authorization().attempt()).isEqualTo(2);
        assertThat(result.authorization().idempotencyKey()).isEqualTo("verifactu-B09767898-2");
    }

    private static FiscalProfile completeProfile(String nif) {
        return new FiscalProfile("Restaurante Prueba SL", "Prueba", nif,
                "Calle Mayor 1", "28013", "Madrid", "Madrid", "600123456", "ana@example.es", "RP", new BigDecimal("10"));
    }

    private static VerifactuAuthorization inProgressAuth(String nif, String requestId) {
        return new VerifactuAuthorization(
                UUID.randomUUID(),
                nif,
                "Restaurante Prueba SL",
                VerifactuAuthorizationStatus.IN_PROGRESS,
                requestId,
                "verifactu-" + nif + "-1",
                1,
                "tpl-anexo-1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "owner1",
                NOW.minusSeconds(120),
                NOW.minusSeconds(120),
                null,
                null,
                null,
                0);
    }
}
