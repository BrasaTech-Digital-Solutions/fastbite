package es.brasatech.fastbite;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:opening-flow;DB_CLOSE_DELAY=-1",
    "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:opening-flow;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.mail.password=",
    "management.health.mail.enabled=false", "fastbite.public-url=http://localhost:8080",
    "fastbite.environment=production", "fastbite.link.standalone=true", "fastbite.migrations.mode=migrate",
    "spring.jpa.hibernate.ddl-auto=none", "spring.session.jdbc.initialize-schema=never"})
class ProductionOpeningFlowTest {
    @Autowired es.brasatech.fastbite.application.tenant.PendingSignupPort pendingSignup;
    @Autowired es.brasatech.fastbite.application.tenant.PendingSignupService signupService;
    @Autowired es.brasatech.fastbite.application.mail.AccountMailOutboxPort accountOutbox;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder fixturePasswords;
    @Autowired JdbcTemplate jdbc;
    @Value("${local.server.port}") int port;
    private HttpResponse<String> get(HttpClient c,String path)throws Exception { return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
    private HttpResponse<String> post(HttpClient c,String path,String body)throws Exception { return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString()); }
    private String csrf(String html) { var m=Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);assertTrue(m.find());return URLEncoder.encode(m.group(1),StandardCharsets.UTF_8); }
    @Test void openingChecklistAndWorkAloneAreScopedAndProtected()throws Exception {
        var owner=HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        VerifiedSignupFixture.create(pendingSignup,signupService,accountOutbox,fixturePasswords,"openflow","openowner","Owner","local-test-password");
        post(owner,"/login","username=openowner&password=local-test-password&_csrf="+csrf(get(owner,"/login").body()));
        var page=get(owner,"/owner/console?location=openflow");
        assertEquals(200,page.statusCode());
        assertTrue(page.body().contains("Your opening checklist"),page.body());
        assertTrue(page.body().contains("I work alone"));
        assertTrue(page.body().contains("id=\"opening-next-action\""));
        assertTrue(page.body().contains("Bring your setup"));
        var spanish = owner.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/owner/console?location=openflow")).header("Accept-Language","es").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,spanish.statusCode());
        assertTrue(spanish.body().contains("Tu lista para abrir"),spanish.body());
        assertTrue(spanish.body().contains("Siguiente paso"));
        assertTrue(spanish.body().contains("Trabajo solo"));
        assertFalse(page.body().contains("concept-picker-section"));
        String token=csrf(page.body());
        assertEquals(403,post(owner,"/owner/workspace/openflow/setup","action=work_alone").statusCode());
        assertEquals(404,post(owner,"/owner/workspace/notowned/setup","action=work_alone&_csrf="+token).statusCode());
        assertEquals(302,post(owner,"/owner/workspace/openflow/setup","action=work_alone&_csrf="+token).statusCode());
        assertTrue(jdbc.queryForObject("SELECT work_alone FROM public.owner_onboarding WHERE tenant_id='openflow'",Boolean.class));
        assertEquals(302,post(owner,"/owner/workspace/openflow/setup","action=work_with_team&_csrf="+token).statusCode());
        assertFalse(jdbc.queryForObject("SELECT work_alone FROM public.owner_onboarding WHERE tenant_id='openflow'",Boolean.class));
        jdbc.update("UPDATE public.owner_onboarding SET opened = TRUE WHERE tenant_id='openflow'");
        var today = get(owner, "/owner/console?location=openflow");
        assertEquals(200, today.statusCode());
        assertTrue(today.body().contains("Needs your attention"), today.body());
        assertTrue(today.body().contains("Open counter"));
        assertTrue(today.body().contains("Your opening checklist"));
        assertEquals(302,post(owner,"/owner/workspace/openflow/setup","action=dismiss&_csrf="+token).statusCode());
        var hidden = get(owner,"/owner/console?location=openflow");
        assertTrue(hidden.body().contains("Show opening checklist"));
        assertEquals(302,post(owner,"/owner/workspace/openflow/setup","action=resume&_csrf="+token).statusCode());
        assertTrue(get(owner,"/owner/console?location=openflow").body().contains("Your opening checklist"));
        jdbc.update("INSERT INTO tenant_openflow.users (id, username, password, full_name, active) VALUES (?, ?, ?, ?, ?)",
                "staff-1", "openstaff", "hash", "Team Member", true);
        jdbc.update("INSERT INTO tenant_openflow.user_roles (user_id, role) VALUES (?, ?)", "staff-1", "WAITER");
        jdbc.update("UPDATE public.owner_onboarding SET work_alone = TRUE WHERE tenant_id='openflow'");
        var staffPage = get(owner,"/owner/console?location=openflow");
        assertEquals(200, staffPage.statusCode());
        assertFalse(staffPage.body().contains("value=\"work_alone\""));
        assertFalse(staffPage.body().contains("value=\"work_with_team\""));
        assertTrue(Pattern.compile("<li><span class=\"step-number done\"[^>]*>✓</span><div><h3><span>Your team</span>", Pattern.DOTALL).matcher(staffPage.body()).find(), staffPage.body());
        assertTrue(jdbc.queryForObject("SELECT work_alone FROM public.owner_onboarding WHERE tenant_id='openflow'",Boolean.class));
    }
}
