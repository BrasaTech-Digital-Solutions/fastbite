package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.OwnerEmailService;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.security.OwnerVerificationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OwnerEmailControllerTest {
    @Test void changingAddressClearsTheVerifiedSessionCache() {
        var owners = mock(OwnerEmailService.class);
        var controller = new OwnerEmailController(owners, mock(PlatformLink.class));
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute(OwnerVerificationFilter.VERIFIED_SESSION_ATTRIBUTE, "alice");
        assertThat(controller.request(new TestingAuthenticationToken("alice", ""), "new@example.test", request)).contains("sent");
        assertThat(request.getSession().getAttribute(OwnerVerificationFilter.VERIFIED_SESSION_ATTRIBUTE)).isNull();
        verify(owners).request(eq("alice"), eq("new@example.test"), anyString());
    }
}
