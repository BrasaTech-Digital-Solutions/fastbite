package es.brasatech.fastbite.email.service;

import es.brasatech.fastbite.application.mail.LocationLifecycleMailPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LocationLifecycleMailAdapterTest {

    @Test
    void deleteNoticeIncludesUndoDeadlineAndWhereToFindUndoInEsEnPt() {
        JavaMailSender sender = mock(JavaMailSender.class);
        AccountMailRenderer renderer = mock(AccountMailRenderer.class);
        var adapter = new LocationLifecycleMailAdapter(sender, "info@brasatech.es", renderer);

        Instant now = Instant.parse("2026-11-27T12:00:00Z");
        Instant undoUntil = now.plus(7, ChronoUnit.DAYS); // 04/12/2026 13:00 CET

        adapter.sendLocationLifecycleNotice("owner@example.com", "bistro",
                LocationLifecycleMailPort.Action.DELETED, LocationLifecycleMailPort.Scope.BOTH, now, undoUntil, "es");
        ArgumentCaptor<String> esBody = ArgumentCaptor.forClass(String.class);
        verify(renderer).send(eq(sender), eq("info@brasatech.es"), eq("owner@example.com"),
                eq("location-lifecycle"), eq("es"), any(), any(), esBody.capture(), any(), any(), any());
        assertThat(esBody.getValue())
                .contains("04/12/2026 13:00")
                .contains("Deshacer")
                .contains("Pendientes de eliminar");

        adapter.sendLocationLifecycleNotice("owner@example.com", "bistro",
                LocationLifecycleMailPort.Action.DELETED, LocationLifecycleMailPort.Scope.PRODUCTION, now, undoUntil, "en");
        ArgumentCaptor<String> enBody = ArgumentCaptor.forClass(String.class);
        verify(renderer).send(eq(sender), eq("info@brasatech.es"), eq("owner@example.com"),
                eq("location-lifecycle"), eq("en"), any(), any(), enBody.capture(), any(), any(), any());
        assertThat(enBody.getValue())
                .contains("04/12/2026 13:00")
                .contains("Undo")
                .contains("Pending deletion");

        adapter.sendLocationLifecycleNotice("owner@example.com", "bistro",
                LocationLifecycleMailPort.Action.DELETED, LocationLifecycleMailPort.Scope.SANDBOX, now, undoUntil, "pt");
        ArgumentCaptor<String> ptBody = ArgumentCaptor.forClass(String.class);
        verify(renderer).send(eq(sender), eq("info@brasatech.es"), eq("owner@example.com"),
                eq("location-lifecycle"), eq("pt"), any(), any(), ptBody.capture(), any(), any(), any());
        assertThat(ptBody.getValue())
                .contains("04/12/2026 13:00")
                .contains("Anular")
                .contains("Pendentes de eliminar");
    }
}
