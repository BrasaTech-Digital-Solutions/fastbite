package es.brasatech.fastbite.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.tenant.*;
import es.brasatech.fastbite.config.PublicOrigin;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.domain.user.Role;
import es.brasatech.fastbite.security.AccountSessionInvalidator;
import es.brasatech.fastbite.service.StripeService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserValidationLoggingTest {
    private static ListAppender<ILoggingEvent> capture(Class<?> type) {
        var appender = new ListAppender<ILoggingEvent>(); appender.start();
        ((Logger) LoggerFactory.getLogger(type)).addAppender(appender); return appender;
    }
    private static void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender); appender.stop();
    }
    @Test void duplicateDomainIsAWarningWithoutDisclosingTheOtherOwnerOrLocation() {
        var port = mock(TenantLocationPort.class);
        var first = new TenantLocation("1", "alice", "pizza", "RESTAURANT", null);
        var other = new TenantLocation("2", "secretowner", "secretlocation", "RESTAURANT", "menu.example.test");
        when(port.findByTenantId("pizza")).thenReturn(Optional.of(first));
        when(port.findByCustomDomain("menu.example.test")).thenReturn(Optional.of(other));
        var locations = new TenantLocationService(port);
        var controller = new OwnerConsoleController(locations, mock(TenantSignupService.class), mock(TenantRoutingResolver.class), mock(StripeService.class), mock(PublicOrigin.class));
        var flash = new RedirectAttributesModelMap(); Principal alice = () -> "alice";
        var appender = capture(OwnerConsoleController.class);
        try {
            assertThat(controller.bindDomain("pizza", "menu.example.test", alice, flash)).isEqualTo("redirect:/owner/console?view=locations");
            assertThat(flash.getFlashAttributes().get("error").toString()).contains("menu.example.test").doesNotContain("secretowner", "secretlocation");
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
            verify(port, never()).save(any());
        } finally { detach(OwnerConsoleController.class, appender); }
    }
    @Test void registeredAddressShowsItsSpanishReasonAndAddress() throws Exception {
        var port = mock(TenantLocationPort.class);
        when(port.findByTenantId("pizza")).thenReturn(Optional.of(new TenantLocation("1", "alice", "pizza", "RESTAURANT", null)));
        var locations = new TenantLocationService(port);
        var signup = mock(TenantSignupService.class);
        doAnswer(call -> { locations.registerLocation("alice", "pizza", "RESTAURANT"); return null; })
                .when(signup).registerAdditionalLocation("pizza", "alice", "RESTAURANT");
        var controller = new OwnerConsoleController(locations, signup, mock(TenantRoutingResolver.class),
                mock(StripeService.class), mock(PublicOrigin.class));
        var messages = new ResourceBundleMessageSource(); messages.setBasename("i18n/messages"); messages.setDefaultEncoding("UTF-8");
        controller.setMessages(messages);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/owner/add-location").principal(() -> "alice").locale(Locale.forLanguageTag("es"))
                        .param("tenantId", "pizza"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "La dirección del local pizza ya está registrada."));
    }
    @Test void plainRegistrationExceptionShowsOnlyGenericSpanishError() throws Exception {
        var signup = mock(TenantSignupService.class);
        doThrow(new IllegalArgumentException("secret internal detail"))
                .when(signup).registerAdditionalLocation("pizza", "alice", "RESTAURANT");
        var controller = new OwnerConsoleController(mock(TenantLocationService.class), signup,
                mock(TenantRoutingResolver.class), mock(StripeService.class), mock(PublicOrigin.class));
        var messages = new StaticMessageSource();
        messages.addMessage("workspace.flash.failed", Locale.forLanguageTag("es"), "No se pudo completar la acción.");
        controller.setMessages(messages);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/owner/add-location").principal(() -> "alice").locale(Locale.forLanguageTag("es"))
                        .param("tenantId", "pizza"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "No se pudo completar la acción."));
    }
    @Test void unexpectedDomainFailureStillLogsErrorWithCause() {
        var locations = mock(TenantLocationService.class);
        when(locations.isOwnerOf("alice", "pizza")).thenThrow(new IllegalStateException("database unavailable"));
        var controller = new OwnerConsoleController(locations, mock(TenantSignupService.class), mock(TenantRoutingResolver.class), mock(StripeService.class), mock(PublicOrigin.class));
        var appender = capture(OwnerConsoleController.class);
        try {
            controller.bindDomain("pizza", "menu.example.test", () -> "alice", new RedirectAttributesModelMap());
            assertThat(appender.list).hasSize(1); assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
            assertThat(appender.list.getFirst().getThrowableProxy()).isNotNull();
        } finally { detach(OwnerConsoleController.class, appender); }
    }
    @Test void invalidStaffPasswordIsWarningAndStillRendersItsMessage() {
        var staff = mock(OwnerStaffService.class); when(staff.listStaff("alice", "pizza")).thenReturn(List.of());
        var messages = new StaticMessageSource(); messages.addMessage("password.policy", Locale.ENGLISH, "Password is too short.");
        var controller = new OwnerStaffController(staff, mock(PasswordEncoder.class), messages, mock(AccountSessionInvalidator.class));
        var model = new ExtendedModelMap(); var appender = capture(OwnerStaffController.class);
        try {
            controller.createUser("pizza", "bob", "Bob", "short", Role.COOK, () -> "alice", model);
            assertThat(model.get("error")).isNotNull();
            assertThat(appender.list).hasSize(1); assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally { detach(OwnerStaffController.class, appender); }
    }
    @Test void invalidBundleIsBadRequestAndUnexpectedFailureRemainsAnError() throws Exception {
        var backups = mock(TenantBackupRestorePort.class);
        var controller = new TenantMaintenanceController(backups, mock(PlatformLink.class), new StaticMessageSource());
        var upload = new MockMultipartFile("file", "test.zip", "application/zip", new byte[]{1});
        doThrow(new IllegalArgumentException("Invalid bundle")).when(backups).importProductionBundle(eq("pizza"), any());
        var appender = capture(TenantMaintenanceController.class);
        try {
            assertThat(controller.uploadProductionBundle("pizza", upload, null).getStatusCode().value()).isEqualTo(400);
            assertThat(appender.list.getLast().getLevel()).isEqualTo(Level.WARN);
            assertThat(appender.list.getLast().getThrowableProxy()).isNull();
            doThrow(new IllegalStateException("storage failed")).when(backups).importProductionBundle(eq("pizza"), any());
            assertThat(controller.uploadProductionBundle("pizza", upload, null).getStatusCode().value()).isEqualTo(500);
            assertThat(appender.list.getLast().getLevel()).isEqualTo(Level.ERROR);
            assertThat(appender.list.getLast().getThrowableProxy()).isNotNull();
        } finally { detach(TenantMaintenanceController.class, appender); }
    }
}
