package es.brasatech.fastbite;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private HttpResponse<String> postEs(HttpClient c, String path, String body) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept-Language", "es").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> getEs(HttpClient c, String path) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept-Language", "es").GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private String redirectPath(HttpResponse<String> response) {
        URI location = URI.create(response.headers().firstValue("Location").orElseThrow());
        return location.getRawPath() + (location.getRawQuery() == null ? "" : "?" + location.getRawQuery());
    }
    private HttpResponse<String> uploadInvalidPdfEs(HttpClient c, String token) throws Exception {
        String boundary = "fastbite-test-boundary";
        String content = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n"
                + URLDecoder.decode(token, StandardCharsets.UTF_8)
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"bad.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\nnot a PDF\r\n--" + boundary + "--\r\n";
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/owner/workspace/openflow/privacy-notice"))
                .header("Accept-Language", "es").header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(content)).build(), HttpResponse.BodyHandlers.ofString());
    }
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
        var invalidPdf = uploadInvalidPdfEs(owner, token);
        assertEquals(302, invalidPdf.statusCode());
        assertTrue(getEs(owner, redirectPath(invalidPdf)).body()
                .contains("El archivo seleccionado no es un PDF."));
        var duplicate = postEs(owner, "/owner/add-location", "tenantId=openflow&_csrf=" + token);
        assertEquals(302, duplicate.statusCode());
        assertTrue(getEs(owner, redirectPath(duplicate)).body()
                .contains("La dirección del local ya está reservada."));
        assertEquals(302, postEs(owner, "/owner/add-location", "tenantId=openother&_csrf=" + token).statusCode());
        jdbc.update("UPDATE public.tenant_locations SET custom_domain='used.example.test' WHERE tenant_id='openother'");
        var domain = postEs(owner, "/owner/bind-domain", "tenantId=openflow&customDomain=used.example.test&_csrf=" + token);
        assertEquals(302, domain.statusCode());
        assertTrue(getEs(owner, redirectPath(domain)).body()
                .contains("El dominio used.example.test ya está asignado a otro local."));

        assertEquals(403,post(owner,"/owner/workspace/openflow/setup","action=work_alone").statusCode());
        assertEquals(404,post(owner,"/owner/workspace/notowned/setup","action=work_alone&_csrf="+token).statusCode());
        assertEquals(302,post(owner,"/owner/workspace/openflow/setup","action=work_alone&_csrf="+token).statusCode());
        assertTrue(jdbc.queryForObject("SELECT work_alone FROM public.owner_onboarding WHERE tenant_id='openflow'",Boolean.class));
        assertEquals(302,post(owner,"/owner/workspace/openflow/setup","action=work_with_team&_csrf="+token).statusCode());
        assertFalse(jdbc.queryForObject("SELECT work_alone FROM public.owner_onboarding WHERE tenant_id='openflow'",Boolean.class));
        jdbc.update("UPDATE public.owner_onboarding SET opened = TRUE WHERE tenant_id='openflow'");
        jdbc.update("UPDATE public.tenant_billing SET trial_started_at = ? WHERE tenant_id='openflow'",
                Timestamp.from(Instant.now().minus(25, ChronoUnit.DAYS)));
        var today = get(owner, "/owner/console?location=openflow");
        assertEquals(200, today.statusCode());
        assertTrue(today.body().contains("Needs your attention"), today.body());
        assertTrue(today.body().contains("Your trial ends within seven days"), today.body());
        assertTrue(today.body().contains("Free trial until"), today.body());
        assertTrue(today.body().contains("Open counter"));
        assertTrue(today.body().contains("Your opening checklist"));
        var todayEs = owner.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/owner/console?location=openflow")).header("Accept-Language","es").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200, todayEs.statusCode());
        assertTrue(todayEs.body().contains("Prueba gratis hasta el"), todayEs.body());
        assertTrue(todayEs.body().contains("Tu prueba termina en siete días o menos"), todayEs.body());
        assertTrue(todayEs.body().contains("Escribe {0} exactamente para confirmar"), todayEs.body());
        assertTrue(todayEs.body().indexOf("today-tools-grid") < todayEs.body().indexOf("setup-panel"));
        var billingEs = owner.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/owner/console?view=billing")).header("Accept-Language","es").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200, billingEs.statusCode());
        assertTrue(billingEs.body().contains("Gestiona las suscripciones y pruebas de tus locales."), billingEs.body());
        assertTrue(billingEs.body().contains("Prueba gratis hasta el"), billingEs.body());
        var ticketsEs = owner.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/owner/console?view=tickets&location=openflow")).header("Accept-Language","es").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ticketsEs.statusCode());
        assertTrue(ticketsEs.body().contains("Gestiona los datos fiscales, tickets y registros de este local."), ticketsEs.body());
        assertTrue(ticketsEs.body().contains("Tickets y facturas emitidos"), ticketsEs.body());
        assertTrue(ticketsEs.body().contains("Aviso de privacidad para tus clientes"), ticketsEs.body());
        assertTrue(ticketsEs.body().contains("Subir tu aviso de privacidad en PDF"), ticketsEs.body());
        assertFalse(ticketsEs.body().contains("style=\"max-width: 420px;\""));
        assertTrue(ticketsEs.body().contains("/owner/workspace/openflow/tickets.json"), ticketsEs.body());
        var dataEs = owner.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/owner/console?view=data&location=openflow")).header("Accept-Language","es").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200, dataEs.statusCode());
        assertTrue(dataEs.body().contains("Trae, guarda y restaura los datos de este local."), dataEs.body());
        var appearanceEs = owner.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/owner/console?view=location-config&section=appearance&location=openflow")).header("Accept-Language","es").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200, appearanceEs.statusCode(), appearanceEs.body());
        assertTrue(appearanceEs.body().contains("Guardar aspecto del local"), appearanceEs.body());



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
