package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.SandboxEntryService;
import es.brasatech.fastbite.application.platform.SandboxSignInService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.tenant.TenantSignupService;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.domain.user.Role;
import es.brasatech.fastbite.domain.user.UserDto;
import es.brasatech.fastbite.security.LoginController;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** "Enter sandbox": production hands the owner a single-use link, the sandbox signs them in with it. */
class SandboxEntryControllerTest {

    static final String SECRET = "a-secret-of-at-least-thirty-two-characters";
    static final UserDto ANA = new UserDto("1", "ana", "{bcrypt}unknown", "Ana García", Set.of(Role.OWNER), true, null);

    @WebMvcTest(controllers = {SandboxEntryController.class, LoginController.class, SignupController.class})
    @ContextConfiguration(classes = {TestConfig.class, SandboxEntryController.class, LoginController.class,
            SignupController.class, EnvironmentAdvice.class, PlatformEnvironment.class, PlatformLink.class,
            SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
    abstract static class Endpoint {
        @Autowired MockMvc mockMvc;
        @MockitoBean SandboxEntryService entry;
        @MockitoBean SandboxSignInService signIn;
        @MockitoBean UserService users;
        @MockitoBean TenantSignupService signup;
        @MockitoBean TenantLocationService tenantLocationService;

        @BeforeEach
        void setUp() {
            when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        }

        static MockHttpSession owner(String username) {
            var authorities = AuthorityUtils.createAuthorityList("ROLE_OWNER");
            var user = new TenantUser(username, "hash", true, authorities, null);
            var session = new MockHttpSession();
            session.setAttribute("SPRING_SECURITY_CONTEXT",
                    new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));
            return session;
        }
    }

    @Nested
    @TestPropertySource(properties = {"fastbite.environment=production", "fastbite.link.url=https://sandbox.example",
            "fastbite.link.secret=" + SECRET})
    class LinkedProduction extends Endpoint {

        @BeforeEach
        void anaMayEnter() {
            when(entry.available()).thenReturn(true);
        }

        @Test
        void theOwnerIsSentIntoTheSandboxWithASingleUseLink() throws Exception {
            when(entry.enter("ana", "anacafe")).thenReturn(new SandboxEntryService.Entry(SandboxEntryService.State.READY,
                    "https://sandbox.example/link/enter?token=abc.def"));

            mockMvc.perform(get("/owner/sandbox").param("location", "anacafe").session(owner("ana")))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("https://sandbox.example/link/enter?token=abc.def"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"));
        }

        @Test
        void withoutALocationTheOwnerLandsOnTheirFirst() throws Exception {
            when(tenantLocationService.getLocationsByOwner("ana")).thenReturn(java.util.List.of(
                    new TenantLocation("2", "ana", "zcafe", "RESTAURANT", null),
                    new TenantLocation("1", "ana", "anacafe", "RESTAURANT", null)));
            when(entry.enter("ana", "anacafe")).thenReturn(new SandboxEntryService.Entry(SandboxEntryService.State.READY,
                    "https://sandbox.example/link/enter?token=abc.def"));

            mockMvc.perform(get("/owner/sandbox").session(owner("ana")))
                    .andExpect(redirectedUrl("https://sandbox.example/link/enter?token=abc.def"));
        }

        @Test
        void aSandboxStillBeingPreparedSendsTheOwnerBackToTheConsole() throws Exception {
            when(entry.enter("ana", "anacafe")).thenReturn(new SandboxEntryService.Entry(SandboxEntryService.State.PREPARING, null));

            mockMvc.perform(get("/owner/sandbox").param("location", "anacafe").session(owner("ana")))
                    .andExpect(redirectedUrl("/owner/console?location=anacafe"))
                    .andExpect(flash().attribute("notice", org.hamcrest.Matchers.containsString("being prepared")));
        }

        @Test
        void anEndedSubscriptionSaysSo() throws Exception {
            when(entry.enter("ana", "anacafe")).thenReturn(new SandboxEntryService.Entry(SandboxEntryService.State.SUBSCRIPTION_ENDED, null));

            mockMvc.perform(get("/owner/sandbox").param("location", "anacafe").session(owner("ana")))
                    .andExpect(redirectedUrl("/owner/console?location=anacafe"))
                    .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("Renew it in Billing")));
        }

        @Test
        void anotherOwnersLocationIsNotFound() throws Exception {
            when(entry.enter("eve", "anacafe")).thenThrow(new IllegalArgumentException("Location anacafe is not yours"));

            mockMvc.perform(get("/owner/sandbox").param("location", "anacafe").session(owner("eve")))
                    .andExpect(status().isNotFound());
        }

        @Test
        void onlySignedInOwnersGetALink() throws Exception {
            mockMvc.perform(get("/owner/sandbox").param("location", "anacafe"))
                    .andExpect(redirectedUrl("/login"));
            verify(entry, never()).enter(anyString(), anyString());
        }

        @Test
        void productionTakesNoSignInLinks() throws Exception {
            mockMvc.perform(get("/link/enter").param("token", "abc.def")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @TestPropertySource(properties = {"fastbite.environment=sandbox", "fastbite.link.url=https://fastbite.example",
            "fastbite.link.secret=" + SECRET})
    class LinkedSandbox extends Endpoint {

        @BeforeEach
        void linked() {
            when(signIn.active()).thenReturn(true);
        }

        @Test
        void productionsLinkSignsTheOwnerInAndOpensTheLocation() throws Exception {
            when(signIn.accept("abc.def")).thenReturn(new SandboxSignInService.Outcome("ana", "anacafe", null));
            when(users.findByUsername("ana")).thenReturn(Optional.of(ANA));
            var previous = owner("someone-else");

            var result = mockMvc.perform(get("/link/enter").param("token", "abc.def").session(previous))
                    .andExpect(redirectedUrl("/owner/console?location=anacafe"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andReturn();

            assertThat(previous.isInvalid()).as("whoever was signed in before is signed out").isTrue();
            var context = (SecurityContext) result.getRequest().getSession().getAttribute("SPRING_SECURITY_CONTEXT");
            assertThat(context.getAuthentication().getName()).isEqualTo("ana");
            assertThat(context.getAuthentication().getAuthorities()).extracting("authority").containsExactly("ROLE_OWNER");
            assertThat(((TenantUser) context.getAuthentication().getPrincipal()).isPlatformAccount()).isTrue();
        }

        @Test
        void anExpiredOrUsedLinkSaysHowToGetANewOne() throws Exception {
            when(signIn.accept("used")).thenReturn(new SandboxSignInService.Outcome(null, null, SandboxSignInService.Refusal.INVALID));

            mockMvc.perform(get("/link/enter").param("token", "used"))
                    .andExpect(status().isBadRequest())
                    .andExpect(view().name("fastfood/sandboxEntry"))
                    .andExpect(model().attribute("enterUrl", "https://fastbite.example/owner/sandbox"));
        }

        @Test
        void duringAReleaseTheOwnerIsAskedToTryAgain() throws Exception {
            when(signIn.accept("abc.def")).thenReturn(new SandboxSignInService.Outcome(null, null, SandboxSignInService.Refusal.OTHER_VERSION));

            mockMvc.perform(get("/link/enter").param("token", "abc.def"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(model().attribute("refusal", "OTHER_VERSION"));
        }

        @Test
        void anOwnerTheSandboxDoesNotKnowIsNotSignedIn() throws Exception {
            when(signIn.accept("abc.def")).thenReturn(new SandboxSignInService.Outcome("ana", "anacafe", null));
            when(users.findByUsername("ana")).thenReturn(Optional.empty());

            var result = mockMvc.perform(get("/link/enter").param("token", "abc.def"))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn();
            assertThat(result.getRequest().getSession(false) == null
                    || result.getRequest().getSession(false).getAttribute("SPRING_SECURITY_CONTEXT") == null).isTrue();
        }

        @Test
        void ownersSignInThroughProduction() throws Exception {
            mockMvc.perform(get("/login")).andExpect(redirectedUrl("https://fastbite.example/owner/sandbox"));
            mockMvc.perform(get("/owner/console")).andExpect(redirectedUrl("/login"));
        }

        @Test
        void staffStillSignInAtTheirRestaurant() throws Exception {
            when(tenantLocationService.getLocation("anacafe")).thenReturn(Optional.of(
                    new TenantLocation("1", "ana", "anacafe", "RESTAURANT", null)));

            mockMvc.perform(get("/anacafe/login")).andExpect(status().isOk()).andExpect(view().name("fastfood/login"));
        }

        @Test
        void signupHappensInProduction() throws Exception {
            mockMvc.perform(get("/signup")).andExpect(redirectedUrl("https://fastbite.example/signup"));
            mockMvc.perform(post("/signup").with(csrf()).param("tenantId", "anacafe").param("username", "ana")
                            .param("password", "a-long-password").param("fullName", "Ana"))
                    .andExpect(redirectedUrl("https://fastbite.example/signup"));
            verify(signup, never()).registerTenant(anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void signingOutGoesBackToProduction() throws Exception {
            mockMvc.perform(post("/logout").with(csrf()).session(owner("ana")))
                    .andExpect(redirectedUrl("https://fastbite.example"));
        }
    }

    @Nested
    @TestPropertySource(properties = {"fastbite.environment=sandbox"})
    class StandaloneSandbox extends Endpoint {

        @Test
        void keepsItsOwnSignInAndSignup() throws Exception {
            mockMvc.perform(get("/login")).andExpect(status().isOk()).andExpect(view().name("fastfood/login"));
            mockMvc.perform(get("/signup")).andExpect(status().isOk()).andExpect(view().name("fastfood/signup"));
            mockMvc.perform(get("/link/enter").param("token", "abc.def")).andExpect(status().isNotFound());
        }
    }
}
