package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.PendingSignupService;
import es.brasatech.fastbite.domain.tenant.TenantProvisioningInProgressException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EmailVerificationControllerTest {
    @Test void provisioningShowsWaitingPageInsteadOfInvalidLink() {
        var signup = mock(PendingSignupService.class);
        String token = "a".repeat(43);
        when(signup.verify(token)).thenThrow(new TenantProvisioningInProgressException("busy"));
        var model = new ExtendedModelMap();
        var response = new MockHttpServletResponse();
        assertThat(new EmailVerificationController(signup, org.mockito.Mockito.mock(es.brasatech.fastbite.application.office.OwnerEmailService.class), org.mockito.Mockito.mock(es.brasatech.fastbite.application.office.StaffAccountService.class)).verify(token, model, response, null)).isEqualTo("fastfood/verifyEmailWaiting");
        assertThat(model.get("token")).isEqualTo(token);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
