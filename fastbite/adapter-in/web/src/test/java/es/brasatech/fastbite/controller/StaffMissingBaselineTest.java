package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.StaffAccountService;
import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.application.tenant.OwnerStaffService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.exception.ResourceNotFoundException;
import es.brasatech.fastbite.security.AccountSessionInvalidator;
import es.brasatech.fastbite.security.TenantUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaffMissingBaselineTest {
    @Test void inaccessibleOwnerStaffLocationsReturnNotFound() throws Exception {
        var locations = mock(TenantLocationService.class);
        var service = new OwnerStaffService(mock(UserService.class), locations);
        var messages = new StaticMessageSource();
        var mvc = MockMvcBuilders.standaloneSetup(new OwnerStaffController(service, mock(PasswordEncoder.class), messages, mock(AccountSessionInvalidator.class)))
                .setControllerAdvice(new BadRequestAdvice(messages), new ResourceNotFoundAdvice()).build();
        var roles = AuthorityUtils.createAuthorityList("ROLE_OWNER");
        var auth = UsernamePasswordAuthenticationToken.authenticated(new TenantUser("owner", "hash", true, roles, null), null, roles);
        for (String tenant : new String[]{"absentrestaurant", "invalid-tenant", "otherrestaurant"}) {
            String url = "/owner/locations/" + tenant + "/users";
            mvc.perform(get(url).principal(auth)).andExpect(status().isNotFound());
            mvc.perform(post(url + "/delete").param("userId", "unknown").principal(auth)).andExpect(status().isNotFound());
        }
    }
    @Test void absentMalformedAndForeignStaffAccountResponses() throws Exception {
        StaffAccountService service = mock(StaffAccountService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("hash");
        when(service.find(anyString(), anyBoolean(), anyString(), anyString())).thenReturn(Optional.empty());
        when(service.password(anyString(), anyBoolean(), anyString(), anyString(), anyString())).thenThrow(new ResourceNotFoundException());
        doThrow(new ResourceNotFoundException()).when(service).email(anyString(), anyBoolean(), anyString(), anyString(), anyString(), anyString());
        doThrow(new ResourceNotFoundException()).when(service).reset(anyString(), anyBoolean(), anyString(), anyString());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StaffAccountController(service, encoder, mock(AccountSessionInvalidator.class)))
                .setControllerAdvice(new ResourceNotFoundAdvice()).build();
        var roles = AuthorityUtils.createAuthorityList("ROLE_OWNER");
        var principal = new TenantUser("owner", "hash", true, roles, null);
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, roles);
        for (String id : new String[]{UUID.randomUUID().toString(), "not-a-uuid", UUID.randomUUID().toString()}) {
            String url = "/owner/locations/cafe/users/" + id + "/account";
            mvc.perform(get(url).principal(auth)).andExpect(status().isNotFound());
            mvc.perform(post(url + "/password").param("password", "longpassword1").principal(auth)).andExpect(status().isNotFound());
            mvc.perform(post(url + "/email").param("email", "a@example.test").principal(auth)).andExpect(status().isNotFound());
            mvc.perform(post(url + "/reset").principal(auth)).andExpect(status().isNotFound());
        }
    }
}
