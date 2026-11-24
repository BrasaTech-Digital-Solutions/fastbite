package es.brasatech.fastbite.jpa.verifactu;

import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorizationStatus;
import es.brasatech.fastbite.jpa.tenant.TenantMigrationService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifactuAuthorizationJdbcAdapterTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    private VerifactuAuthorizationJdbcAdapter store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:verifactu_auth_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        new TenantMigrationService(dataSource).migrateAll(false);
        store = new VerifactuAuthorizationJdbcAdapter(dataSource);
    }

    @Test
    void enforcesAtMostOneValidAndOneActivePerNifWhileAllowingTerminalHistory() {
        VerifactuAuthorization declined = auth("B09767898", VerifactuAuthorizationStatus.DECLINED, 1, "req-1", T0);
        store.insert(declined);

        VerifactuAuthorization active1 = auth("B09767898", VerifactuAuthorizationStatus.IN_PROGRESS, 2, "req-2", T0.plusSeconds(10));
        store.insert(active1);

        // Second active row for the same NIF must fail uniqueness constraint.
        VerifactuAuthorization active2 = auth("B09767898", VerifactuAuthorizationStatus.DOWNLOAD_FAILED, 3, "req-3", T0.plusSeconds(20));
        assertThatThrownBy(() -> store.insert(active2))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Transitioning active1 -> VALID releases active_nif and claims valid_nif.
        VerifactuAuthorization valid1 = store.update(makeValid(active1, T0.plusSeconds(30)));

        assertThat(store.findValidByNif("B09767898")).contains(valid1);
        assertThat(store.findActiveByNif("B09767898")).isEmpty();
        assertThat(store.nextAttemptForNif("B09767898")).isEqualTo(3);

        // Second VALID row for the same NIF must fail uniqueness constraint.
        VerifactuAuthorization valid2 = makeValid(
                auth("B09767898", VerifactuAuthorizationStatus.IN_PROGRESS, 3, "req-4", T0.plusSeconds(40)),
                T0.plusSeconds(40));
        assertThatThrownBy(() -> store.insert(valid2))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Another NIF can independently hold a VALID row.
        VerifactuAuthorization otherNif = makeValid(
                auth("12345678Z", VerifactuAuthorizationStatus.IN_PROGRESS, 1, "req-other", T0.plusSeconds(50)),
                T0.plusSeconds(50));
        store.insert(otherNif);
        assertThat(store.hasValidAuthorization("12345678Z")).isTrue();
    }

    @Test
    void staleUpdateDoesNotOverwriteValidAuthorization() {
        VerifactuAuthorization initial = auth("B09767898", VerifactuAuthorizationStatus.IN_PROGRESS, 1, "req-race", T0);
        store.insert(initial);

        // Both webhook and reconciliation read version 0 concurrently.
        VerifactuAuthorization webhookRead = store.findById(initial.id()).orElseThrow();
        VerifactuAuthorization reconcileRead = store.findById(initial.id()).orElseThrow();

        // Webhook wins and marks VALID (version -> 1).
        VerifactuAuthorization valid = store.update(makeValid(webhookRead, T0.plusSeconds(10)));
        assertThat(valid.status()).isEqualTo(VerifactuAuthorizationStatus.VALID);
        assertThat(valid.version()).isEqualTo(1);

        // Stale reconciliation tries to write IN_PROGRESS or DOWNLOAD_FAILED from version 0.
        VerifactuAuthorization staleAttempt = reconcileRead.withSync(
                VerifactuAuthorizationStatus.DOWNLOAD_FAILED,
                reconcileRead.signerName(),
                null, null, null, null, null,
                "DOWNLOAD_TIMEOUT", null, T0.plusSeconds(20));
        VerifactuAuthorization afterStale = store.update(staleAttempt);

        assertThat(afterStale).isEqualTo(valid);
        assertThat(store.findById(initial.id())).contains(valid);
        assertThat(store.hasValidAuthorization("B09767898")).isTrue();
    }

    @Test
    void wasAuthorizedAtRespectsValidFromAndRevokedAtRange() {
        VerifactuAuthorization valid = makeValid(
                auth("B09767898", VerifactuAuthorizationStatus.IN_PROGRESS, 1, "req-range", T0),
                T0.plusSeconds(60));
        store.insert(valid);

        assertThat(store.hasValidAuthorization("B09767898")).isTrue();
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(59))).isFalse();
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(60))).isTrue();
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(3600))).isTrue();

        // Revoke at T0 + 120s: no longer currently valid, but tickets issued in [60, 120) remain eligible.
        Instant revokedAt = T0.plusSeconds(120);
        VerifactuAuthorization revoked = new VerifactuAuthorization(
                valid.id(), valid.nif(), valid.legalName(), VerifactuAuthorizationStatus.REVOKED,
                valid.digitalSignRequestId(), valid.idempotencyKey(), valid.attempt(), valid.templateId(),
                valid.signerName(), valid.certificateNif(),
                valid.signedPdfPath(), valid.signedPdfSha256(), valid.evidencePdfPath(), valid.evidencePdfSha256(),
                "REVOKED", valid.createdBy(), valid.createdAt(), revokedAt, valid.validFrom(), revokedAt, revokedAt,
                valid.version());
        store.update(revoked);

        assertThat(store.hasValidAuthorization("B09767898")).isFalse();
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(59))).isFalse();
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(60))).isTrue();
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(119))).isTrue();
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(120))).isFalse();
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(200))).isFalse();
    }

    @Test
    void findDueForReconciliationReturnsDownloadFailedAndStaleInProgressOnly() {
        Instant now = T0.plus(Duration.ofHours(3));
        Instant staleBefore = now.minus(Duration.ofHours(1));

        // Stale IN_PROGRESS (created 3h ago, never synced) -> due
        VerifactuAuthorization stale = auth("B09767898", VerifactuAuthorizationStatus.IN_PROGRESS, 1, "req-stale", T0);
        store.insert(stale);

        // Fresh IN_PROGRESS (created 10m ago) -> NOT due
        VerifactuAuthorization fresh = auth("12345678Z", VerifactuAuthorizationStatus.IN_PROGRESS, 1, "req-fresh", now.minus(Duration.ofMinutes(10)));
        store.insert(fresh);

        // Fresh DOWNLOAD_FAILED (synced 1m ago) -> STILL due
        VerifactuAuthorization downloadFailed = new VerifactuAuthorization(
                UUID.randomUUID(), "A12345674", "Restaurante Dos SL", VerifactuAuthorizationStatus.DOWNLOAD_FAILED,
                "req-dlfail", "verifactu-A12345674-1", 1, "tpl-1", "Ana", "A12345674",
                null, null, null, null, "DOWNLOAD_FAILED", "owner",
                now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofMinutes(1)), null, null, now.minus(Duration.ofMinutes(1)), 0);
        store.insert(downloadFailed);

        // Terminal DOCUMENTS_GONE -> NOT due
        store.insert(auth("Y1234567X", VerifactuAuthorizationStatus.DOCUMENTS_GONE, 1, "req-gone", T0));

        // VALID -> NOT due
        store.insert(makeValid(auth("X1234567L", VerifactuAuthorizationStatus.IN_PROGRESS, 1, "req-valid", T0), T0));

        List<VerifactuAuthorization> due = store.findDueForReconciliation(staleBefore, 20);
        assertThat(due).extracting(VerifactuAuthorization::digitalSignRequestId)
                .containsExactly("req-stale", "req-dlfail");
    }

    @Test
    void deduplicatesWebhookEventsSupportsDeleteForRetryAndPurgesOlderThanThirtyDays() {
        Instant oldTime = T0.minus(Duration.ofDays(31));
        Instant recentTime = T0.minus(Duration.ofDays(5));

        assertThat(store.recordWebhookEventIfNew("evt_old", "request.completed", "req-1", oldTime)).isTrue();
        assertThat(store.recordWebhookEventIfNew("evt_old", "request.completed", "req-1", oldTime)).isFalse();
        assertThat(store.recordWebhookEventIfNew("evt_recent", "request.completed", "req-2", recentTime)).isTrue();
        store.markWebhookEventProcessed("evt_recent", recentTime.plusSeconds(1));

        // Delete allows immediate redelivery
        assertThat(store.recordWebhookEventIfNew("evt_retry", "request.completed", "req-3", T0)).isTrue();
        store.deleteWebhookEvent("evt_retry");
        assertThat(store.recordWebhookEventIfNew("evt_retry", "request.completed", "req-3", T0)).isTrue();

        int purged = store.purgeWebhookEventsOlderThan(T0.minus(Duration.ofDays(30)));
        assertThat(purged).isEqualTo(1);

        // Old event was purged, so re-inserting it succeeds; recent event still deduplicates.
        assertThat(store.recordWebhookEventIfNew("evt_old", "request.completed", "req-1", T0)).isTrue();
        assertThat(store.recordWebhookEventIfNew("evt_recent", "request.completed", "req-2", T0)).isFalse();
    }

    @Test
    void compareAndSetReportsAppliedFlagAndPersistsSyncFailureCount() {
        VerifactuAuthorization initial = store.insert(auth("B09767898", VerifactuAuthorizationStatus.IN_PROGRESS, 1, "req-cas", T0));
        assertThat(initial.syncFailureCount()).isZero();

        var first = store.compareAndSet(initial.withSync(
                VerifactuAuthorizationStatus.IN_PROGRESS, "Maria Garcia", null, null, null, null, null,
                "REQUEST_NOT_FOUND", null, T0.plusSeconds(10), 2, T0.plusSeconds(5)));
        assertThat(first.applied()).isTrue();
        assertThat(first.authorization().version()).isEqualTo(1);
        assertThat(first.authorization().syncFailureCount()).isEqualTo(2);
        assertThat(first.authorization().syncFailureSince()).isEqualTo(T0.plusSeconds(5));

        // Stale write using version 0 loses CAS and reports applied == false
        var stale = store.compareAndSet(initial.withSync(
                VerifactuAuthorizationStatus.DECLINED, "Maria Garcia", null, null, null, null, null,
                "DECLINED", null, T0.plusSeconds(20), 0, null));
        assertThat(stale.applied()).isFalse();
        assertThat(stale.authorization().version()).isEqualTo(1);
        assertThat(stale.authorization().syncFailureCount()).isEqualTo(2);
        assertThat(stale.authorization().syncFailureSince()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void revokePersistsRevokedByClearsValidNifPreservesPdfsAndAllowsNewSigningAttempt() {
        Instant validFrom = T0.plusSeconds(30);
        VerifactuAuthorization valid1 = store.insert(makeValid(
                auth("B09767898", VerifactuAuthorizationStatus.IN_PROGRESS, 1, "req-rev-1", T0), validFrom));
        assertThat(store.findValidByNif("B09767898")).contains(valid1);

        Instant revokedAt = T0.plusSeconds(120);
        var revokedRes = store.compareAndSet(valid1.withRevoked(revokedAt, "ana"));
        assertThat(revokedRes.applied()).isTrue();
        VerifactuAuthorization revoked = revokedRes.authorization();
        assertThat(revoked.status()).isEqualTo(VerifactuAuthorizationStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(revokedAt);
        assertThat(revoked.revokedBy()).isEqualTo("ana");
        assertThat(revoked.hasStoredDocuments()).isTrue();
        assertThat(revoked.signedPdfPath()).isEqualTo(valid1.signedPdfPath());
        assertThat(revoked.evidencePdfPath()).isEqualTo(valid1.evidencePdfPath());

        assertThat(store.findValidByNif("B09767898")).isEmpty();
        assertThat(store.hasValidAuthorization("B09767898")).isFalse();
        assertThat(store.findLatestByNif("B09767898")).contains(revoked);
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(60))).isTrue();
        assertThat(store.wasAuthorizedAt("B09767898", revokedAt)).isFalse();

        // New signing attempt + new VALID row for the same NIF succeeds cleanly
        assertThat(store.nextAttemptForNif("B09767898")).isEqualTo(2);
        VerifactuAuthorization attempt2 = store.insert(
                auth("B09767898", VerifactuAuthorizationStatus.IN_PROGRESS, 2, "req-rev-2", T0.plusSeconds(180)));
        assertThat(store.findActiveByNif("B09767898")).contains(attempt2);

        VerifactuAuthorization valid2 = store.compareAndSet(makeValid(attempt2, T0.plusSeconds(240))).authorization();
        assertThat(store.findValidByNif("B09767898")).contains(valid2);
        assertThat(store.hasValidAuthorization("B09767898")).isTrue();
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(60))).isTrue();
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(150))).isFalse();
        assertThat(store.wasAuthorizedAt("B09767898", T0.plusSeconds(240))).isTrue();
    }

    private static VerifactuAuthorization auth(
            String nif, VerifactuAuthorizationStatus status, int attempt, String requestId, Instant createdAt) {
        return new VerifactuAuthorization(
                UUID.randomUUID(),
                nif,
                "Restaurante Prueba SL",
                status,
                requestId,
                "verifactu-" + nif + "-" + attempt,
                attempt,
                "tpl-anexo-1",
                "Maria Garcia",
                null,
                null,
                null,
                null,
                null,
                null,
                "owner1",
                createdAt,
                createdAt,
                null,
                null,
                null,
                0);
    }

    private static VerifactuAuthorization makeValid(VerifactuAuthorization base, Instant validFrom) {
        return base.withSync(
                VerifactuAuthorizationStatus.VALID,
                "Maria Garcia",
                base.nif(),
                base.nif() + "/" + base.id() + "/signed.pdf",
                "a".repeat(64),
                base.nif() + "/" + base.id() + "/evidence.pdf",
                "b".repeat(64),
                null,
                validFrom,
                validFrom);
    }
}
