package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.tenant.OwnerSetupService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = OwnerPasswordSetupController.class, properties = "spring.messages.basename=i18n/messages")
@ContextConfiguration(classes = {TestConfig.class, OwnerPasswordSetupController.class, SecurityConfig.class})
class OwnerPasswordSetupControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean OwnerSetupService setup;
    @MockitoBean TenantLocationService locations;
    @MockitoBean PasswordEncoder encoder;
    private static final String TOKEN = "a".repeat(43);

    @Test void anonymousFormIsUncachedAndGetDoesNotConsumeTheLink() throws Exception {
        when(setup.isValid(TOKEN)).thenReturn(true);
        mvc.perform(get("/set-password").param("token", TOKEN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("Confirm password")));
        verify(setup, never()).complete(anyString(), anyString());
    }

    @Test void postRequiresCsrfAndRejectsShortMismatchedAndOversizedPasswords() throws Exception {
        when(setup.isValid(TOKEN)).thenReturn(true);
        mvc.perform(post("/set-password").param("token", TOKEN)).andExpect(status().isForbidden());
        for (String password : new String[]{"short", "a".repeat(73), "é".repeat(37)}) {
            mvc.perform(post("/set-password").with(csrf()).param("token", TOKEN)
                    .param("password", password).param("confirmation", password)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/set-password").with(csrf()).param("token", TOKEN)
                .param("password", "a-strong-password").param("confirmation", "different"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(encoder);
    }

    @Test void successfulSubmissionEncodesPasswordAndDoesNotEchoTheToken() throws Exception {
        when(setup.isValid(TOKEN)).thenReturn(true);
        when(encoder.encode("a-strong-password")).thenReturn("encoded");
        when(setup.complete(TOKEN, "encoded")).thenReturn(true);
        mvc.perform(post("/set-password").with(csrf()).param("token", TOKEN)
                .param("password", "a-strong-password").param("confirmation", "a-strong-password"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Your password is set")))
                .andExpect(content().string(not(containsString(TOKEN))))
                .andExpect(content().string(not(containsString("a-strong-password"))));
    }

    @Test void invalidAndReusedLinksFailWithoutEncoding() throws Exception {
        mvc.perform(get("/set-password").param("token", TOKEN)).andExpect(status().isBadRequest());
        mvc.perform(post("/set-password").with(csrf()).param("token", TOKEN)
                .param("password", "a-strong-password").param("confirmation", "a-strong-password"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(encoder);
    }
    @Test void setupPageUsesConfiguredTranslations() throws Exception {
        when(setup.isValid(TOKEN)).thenReturn(true);
        for (String[] locale : new String[][]{{"en", "Set your FastBite password"}, {"es", "Establece tu contraseña de FastBite"}, {"pt", "Defina a sua palavra-passe FastBite"}}) {
            mvc.perform(get("/set-password").param("token", TOKEN).locale(java.util.Locale.forLanguageTag(locale[0])))
                    .andExpect(status().isOk()).andExpect(content().string(containsString(locale[1])));
        }
    }
}
