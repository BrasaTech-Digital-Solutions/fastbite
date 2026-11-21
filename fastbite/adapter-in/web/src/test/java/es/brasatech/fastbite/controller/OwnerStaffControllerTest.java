package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.OwnerStaffService;
import es.brasatech.fastbite.domain.user.Role;
import es.brasatech.fastbite.domain.user.UserDto;
import es.brasatech.fastbite.security.AccountSessionInvalidator;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerStaffControllerTest {

    @Test
    void deletingStaffMemberEndsAllOfTheirSessionsInThatTenant() {
        var staff = mock(OwnerStaffService.class);
        var sessions = mock(AccountSessionInvalidator.class);
        var messages = new StaticMessageSource();
        messages.addMessage("workspace.flash.staff.deleted", Locale.getDefault(), "Staff deleted.");
        var bob = new UserDto("u-bob", "bob", "hash", "Bob", Set.of(Role.COOK), true, "pizza");
        when(staff.listStaff("alice", "pizza")).thenReturn(List.of(bob));
        when(staff.deleteStaff("alice", "pizza", "u-bob")).thenReturn(List.of());

        var controller = new OwnerStaffController(staff, mock(PasswordEncoder.class), messages, sessions);
        controller.deleteUser("pizza", "u-bob", () -> "alice", new ExtendedModelMap());

        verify(staff).deleteStaff("alice", "pizza", "u-bob");
        verify(sessions).invalidate("bob", "pizza", null);
    }

    @Test
    void deletingUnknownStaffIdDoesNotInvalidateAnySessions() {
        var staff = mock(OwnerStaffService.class);
        var sessions = mock(AccountSessionInvalidator.class);
        var messages = new StaticMessageSource();
        messages.addMessage("workspace.flash.staff.deleted", Locale.getDefault(), "Staff deleted.");
        when(staff.listStaff("alice", "pizza")).thenReturn(List.of());
        when(staff.deleteStaff("alice", "pizza", "missing")).thenReturn(List.of());

        var controller = new OwnerStaffController(staff, mock(PasswordEncoder.class), messages, sessions);
        controller.deleteUser("pizza", "missing", () -> "alice", new ExtendedModelMap());

        verify(sessions, never()).invalidate(any(), any(), any());
    }

    @Test
    void doesNotInvalidateSessionsWhenStaffMemberWasNotRemoved() {
        var staff = mock(OwnerStaffService.class);
        var sessions = mock(AccountSessionInvalidator.class);
        var messages = new StaticMessageSource();
        messages.addMessage("workspace.flash.staff.deleted", Locale.getDefault(), "Staff deleted.");
        var bob = new UserDto("u-bob", "bob", "hash", "Bob", Set.of(Role.COOK), true, "pizza");
        when(staff.listStaff("alice", "pizza")).thenReturn(List.of(bob));
        when(staff.deleteStaff("alice", "pizza", "u-bob")).thenReturn(List.of(bob));

        var controller = new OwnerStaffController(staff, mock(PasswordEncoder.class), messages, sessions);
        controller.deleteUser("pizza", "u-bob", () -> "alice", new ExtendedModelMap());

        verify(sessions, never()).invalidate(any(), any(), any());
    }
}
