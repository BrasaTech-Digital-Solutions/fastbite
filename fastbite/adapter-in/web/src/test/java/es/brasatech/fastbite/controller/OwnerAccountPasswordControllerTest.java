package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.OwnerPasswordPort;
import es.brasatech.fastbite.security.AccountSessionInvalidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OwnerAccountPasswordControllerTest {
    private final OwnerPasswordPort passwords = mock(OwnerPasswordPort.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AccountSessionInvalidator sessions = mock(AccountSessionInvalidator.class);
    private final OwnerAccountPasswordController controller = new OwnerAccountPasswordController(passwords, encoder, sessions, mock(es.brasatech.fastbite.application.platform.PlatformLink.class));
    private final TestingAuthenticationToken auth = new TestingAuthenticationToken("alice", "");
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test void changesPasswordAndEndsOtherSessions() {
        when(passwords.passwordHash("alice")).thenReturn(Optional.of("old-hash"));
        when(encoder.matches("old password", "old-hash")).thenReturn(true);
        when(encoder.encode("new password")).thenReturn("new-hash");
        when(passwords.replacePassword("alice", "old-hash", "new-hash")).thenReturn(true);
        assertThat(controller.change(auth, request, "old password", "new password", "new password")).contains("passwordChanged");
        verify(sessions).invalidate("alice", null, request.getSession().getId());
    }
    @Test void wrongCurrentPasswordDoesNotChangeAnything() {
        when(passwords.passwordHash("alice")).thenReturn(Optional.of("old-hash"));
        assertThat(controller.change(auth, request, "wrong", "new password", "new password")).contains("passwordError");
        verify(passwords, never()).replacePassword(anyString(), anyString(), anyString());
        verifyNoInteractions(sessions);
    }
}
