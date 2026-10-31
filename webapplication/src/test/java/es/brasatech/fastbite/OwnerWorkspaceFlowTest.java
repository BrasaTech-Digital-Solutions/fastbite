package es.brasatech.fastbite;

import es.brasatech.fastbite.application.tenant.SubscriptionGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:subscription-flow;DB_CLOSE_DELAY=-1",
    "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:subscription-flow;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.mail.password=",
    "fastbite.migrations.mode=migrate","spring.jpa.hibernate.ddl-auto=none","spring.session.jdbc.initialize-schema=never",
    "stripe.secret.key=","stripe.webhook.secret=","stripe.platform.webhook.secret=","stripe.restaurant-price-id=","stripe.connected-account="})
class OwnerWorkspaceFlowTest {
    @Autowired es.brasatech.fastbite.application.tenant.PendingSignupPort pendingSignup;
    @Autowired es.brasatech.fastbite.application.tenant.PendingSignupService signupService;
    @Autowired es.brasatech.fastbite.application.mail.AccountMailOutboxPort accountOutbox;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder fixturePasswords;

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SubscriptionGateway gateway;
    private HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();}
    private HttpResponse<String> get(HttpClient c,String path)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> post(HttpClient c,String path,String body)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    private String csrf(String html){var m=Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);assertTrue(m.find());return URLEncoder.encode(m.group(1),StandardCharsets.UTF_8);}
    private HttpResponse<String> upload(HttpClient c,String path,byte[] bytes,String token)throws Exception {
        String boundary="FastBiteWorkspaceBoundary";
        var body=new java.io.ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"backup.zip\"\r\nContent-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));body.write(bytes);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("X-CSRF-TOKEN",URLDecoder.decode(token,StandardCharsets.UTF_8)).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void workspacePersistsScopedProgressAndPreviewsBackupsWithoutChangingData()throws Exception {
        var owner=client();
        VerifiedSignupFixture.create(pendingSignup, signupService, accountOutbox, fixturePasswords, "workspacecafe", "workspaceowner", "Test Owner", "local-test-password");
        post(owner,"/login","username=workspaceowner&password=local-test-password&_csrf="+csrf(get(owner,"/login").body()));
        var overview=get(owner,"/owner/console");assertEquals(200,overview.statusCode());assertTrue(overview.body().contains("What kind of restaurant are you running?"));
        assertTrue(overview.body().contains("workspace-location-card"), "one open location enters its workspace");
        assertTrue(overview.body().contains("workspace-mobile-tabs"));
        assertFalse(overview.body().contains("name=\"stripeAccountId\""));assertFalse(overview.body().contains("Request volume pricing"));
        String token=csrf(overview.body());
        assertEquals(302,post(owner,"/owner/appearance","theme=cafe&_csrf="+token).statusCode());
        var guest=client();
        assertTrue(get(guest,"/workspacecafe/menu").body().contains("data-theme=\"cafe\""));
        assertEquals(403,post(owner,"/owner/workspace/workspacecafe/appearance","theme=modern").statusCode());
        assertEquals(404,post(owner,"/owner/workspace/notowned/appearance","theme=modern&_csrf="+token).statusCode());
        post(owner,"/owner/workspace/workspacecafe/appearance","theme=modern&_csrf="+token);
        assertTrue(get(guest,"/workspacecafe/menu").body().contains("data-theme=\"modern\""));
        post(owner,"/owner/appearance","theme=fastfood&_csrf="+token);
        assertTrue(get(guest,"/workspacecafe/menu").body().contains("data-theme=\"modern\""));
        post(owner,"/owner/workspace/workspacecafe/appearance","theme=unknown&_csrf="+token);
        assertEquals("modern",jdbc.queryForObject("SELECT theme_id FROM public.location_appearance WHERE tenant_id='workspacecafe'",String.class));
        post(owner,"/owner/workspace/workspacecafe/appearance","theme=inherit&_csrf="+token);
        var guestMenu=get(guest,"/workspacecafe/menu");
        assertTrue(guestMenu.body().contains("data-theme=\"fastfood\""));
        assertFalse(guestMenu.body().contains("navbarThemeSelector"));
        assertEquals(200,get(guest,"/images/favicon.svg").statusCode());
        assertEquals(200,get(guest,"/images/themes/cafe/menu.svg").statusCode());
        assertEquals(404,get(guest,"/images/themes/cafe/theme.properties").statusCode());
        assertTrue(get(owner,"/owner/console?view=location-settings&section=appearance").body().contains("Save location theme"));
        assertTrue(get(owner,"/owner/console").body().contains("guest-preview-frame"));
        assertEquals(404,get(owner,"/owner/console?location=notowned").statusCode());
        assertEquals(404,post(owner,"/owner/workspace/notowned/setup","action=dismiss&_csrf="+token).statusCode());
        assertEquals(403,post(owner,"/owner/workspace/workspacecafe/setup","action=dismiss").statusCode());
        for(String path:new String[]{"?view=locations","?view=team","?view=settings","?view=location-settings","?view=location-settings&section=payments","?view=location-settings&section=domain","?view=location-settings&section=backup","?view=location-settings&section=advanced"})assertEquals(200,get(owner,"/owner/console"+path).statusCode(),path);
        assertEquals(200,get(owner,"/owner/billing/workspacecafe").statusCode());
        post(owner,"/owner/workspace/workspacecafe/setup","action=start&_csrf="+token);
        post(owner,"/owner/workspace/workspacecafe/setup","action=dismiss&_csrf="+token);
        assertTrue(get(owner,"/owner/console").body().contains("Show opening checklist"));
        var secondSession=client();post(secondSession,"/login","username=workspaceowner&password=local-test-password&_csrf="+csrf(get(secondSession,"/login").body()));
        assertTrue(get(secondSession,"/owner/console").body().contains("Show opening checklist"));
        post(owner,"/owner/workspace/workspacecafe/setup","action=resume&_csrf="+token);
        post(owner,"/owner/workspace/workspacecafe/service","dineIn=true&yellow=5&red=10&_csrf="+token);
        assertTrue(jdbc.queryForObject("SELECT service_reviewed FROM public.owner_onboarding WHERE tenant_id='workspacecafe'",Boolean.class));
        assertEquals(false,jdbc.queryForObject("SELECT takeaway FROM tenant_workspacecafe.restaurant_settings",Boolean.class));
        post(owner,"/owner/add-location","tenantId=workspacesecond&_csrf="+token);
        String global = get(owner,"/owner/console").body();
        assertTrue(global.contains("id=\"add-location\""), "two locations show the global list");
        assertFalse(global.contains("class=\"workspace-location-card\""));
        assertTrue(global.contains("view=billing"));
        assertFalse(global.contains("class=\"workspace-mobile-back\""));
        assertEquals(2, Pattern.compile("aria-current=\"page\"").matcher(global).results().count());
        for (String area : new String[]{"today", "menu", "team", "tickets", "location-config", "data"}) {
            String page = get(owner, "/owner/console?view=" + area + "&location=workspacecafe").body();
            assertTrue(page.contains("class=\"workspace-mobile-back\""), area);
            int sidebarStart = page.indexOf("<nav aria-label=\"Location\">");
            int mobileStart = page.indexOf("<nav class=\"workspace-mobile-tabs\"");
            assertTrue(sidebarStart >= 0 && mobileStart >= 0, area);
            String navigation = page.substring(sidebarStart, page.indexOf("</nav>", sidebarStart))
                    + page.substring(mobileStart, page.indexOf("</nav>", mobileStart));
            assertEquals(area.equals("tickets") || area.equals("location-config") || area.equals("data") ? 3 : 2,
                    Pattern.compile("aria-current=\"page\"").matcher(navigation).results().count(), area);
            if (area.equals("tickets") || area.equals("location-config") || area.equals("data")) {
                assertTrue(Pattern.compile("<summary[^>]*aria-current=\"page\"").matcher(page).find(), area);
            }
        }
        for (String area : new String[]{"billing", "account"}) {
            String page = get(owner, "/owner/console?view=" + area).body();
            assertFalse(page.contains("class=\"workspace-mobile-back\""), area);
            assertEquals(2, Pattern.compile("aria-current=\"page\"").matcher(page).results().count(), area);
        }
        String account = get(owner,"/owner/console?view=settings").body();
        assertTrue(account.contains("/owner/account/password"));
        assertTrue(account.contains("view=billing"));
        assertTrue(get(owner,"/owner/console?view=billing").body().contains("/owner/group-quote"));
        assertTrue(get(owner,"/owner/console?view=menu&location=workspacecafe").body().contains("/workspacecafe/backoffice"));
        assertTrue(get(owner,"/owner/console?view=team&location=workspacecafe").body().contains("id=\"add-staff\""));
        assertTrue(get(owner,"/owner/console?view=tickets&location=workspacecafe").body().contains("seriesPrefix"));
        assertTrue(get(owner,"/owner/console?view=location-config&location=workspacecafe").body().contains("cashFloat"));
        assertTrue(get(owner,"/owner/console?view=data&location=workspacecafe").body().contains("backup-preview"));
        assertTrue(get(guest,"/workspacesecond/menu").body().contains("data-theme=\"fastfood\""));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM public.owner_onboarding WHERE tenant_id='workspacesecond'",Integer.class));
        var staff=post(owner,"/owner/locations/workspacecafe/users","username=testcashier&fullName=Test+Cashier&password=local-staff-password&role=CASHIER&_csrf="+token);assertEquals(200,staff.statusCode());assertTrue(staff.body().contains("testcashier"));
        var backup=owner.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/workspacecafe/api/backoffice/maintenance/backup")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,backup.statusCode());
        var preview=upload(owner,"/owner/workspace/workspacesecond/backup-preview",backup.body(),token);assertEquals(200,preview.statusCode());assertEquals(2,new com.fasterxml.jackson.databind.ObjectMapper().readTree(preview.body()).get("accounts").asInt(),preview.body());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_workspacesecond.users",Integer.class));
        assertEquals(404,upload(owner,"/owner/workspace/notowned/backup-preview",backup.body(),token).statusCode());
        assertEquals(400,upload(owner,"/owner/workspace/workspacesecond/backup-preview","invalid".getBytes(),token).statusCode());
        var restored=upload(owner,"/workspacesecond/api/backoffice/maintenance/restore",backup.body(),token);assertEquals(200,restored.statusCode());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_workspacesecond.users",Integer.class));
        assertTrue(get(owner,"/owner/console?location=workspacesecond&view=team").body().contains("testcashier"));
        assertEquals("workspacesecond",jdbc.queryForObject("SELECT tenant_id FROM tenant_workspacesecond.users WHERE username='testcashier'",String.class));
        var restoredStaff=client();
        post(restoredStaff,"/login?tenantId=workspacesecond","username=testcashier&password=local-staff-password&_csrf="+csrf(get(restoredStaff,"/workspacesecond/login").body()));
        assertEquals(200,get(restoredStaff,"/workspacesecond/counter").statusCode());
        var deniedAppearance=post(restoredStaff,"/owner/appearance","theme=cafe&_csrf="+csrf(get(restoredStaff,"/workspacesecond/counter").body()));
        assertEquals(302,deniedAppearance.statusCode());
        assertTrue(deniedAppearance.headers().firstValue("location").orElse("").contains("/login"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM public.owner_appearance WHERE owner_username='testcashier'",Integer.class));
        assertEquals(302,get(restoredStaff,"/owner/console").statusCode());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_workspacecafe.users",Integer.class));
        assertEquals(200,post(owner,"/workspacesecond/api/backoffice/maintenance/restore-demo","_csrf="+token).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_workspacesecond.users",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_workspacesecond.orders",Integer.class));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_workspacesecond.products",Integer.class)>0);
        assertEquals(200,get(owner,"/owner/console?location=workspacesecond").statusCode());

        // Test item 2: invalid legal form submission flashes errors into session and survives 302 redirect
        String legalToken = csrf(get(owner, "/owner/console?view=location-settings&section=legal&location=workspacecafe").body());
        var invalidPost = post(owner, "/owner/workspace/workspacecafe/legal", "legalName=Test&taxId=INVALID_NIF&_csrf=" + legalToken);
        assertEquals(302, invalidPost.statusCode());
        String redirectTarget = invalidPost.headers().firstValue("location").orElse("");
        assertTrue(redirectTarget.contains("/owner/console?view=location-settings&section=legal&location=workspacecafe"));
        String redirectPath = URI.create(redirectTarget).getRawPath() + "?" + URI.create(redirectTarget).getRawQuery();
        var afterRedirect = get(owner, redirectPath);
        assertEquals(200, afterRedirect.statusCode());
        assertTrue(afterRedirect.body().contains("INVALID_NIF"));
        assertTrue(afterRedirect.body().contains("Please check the highlighted fields."));

        // Closed-only accounts stay on the global list, with fiscal archives available.
        jdbc.update("UPDATE public.tenant_billing SET closed_at=CURRENT_TIMESTAMP WHERE tenant_id IN ('workspacecafe','workspacesecond')");
        var closedList = get(owner,"/owner/console");
        assertEquals(200,closedList.statusCode());
        assertTrue(closedList.body().contains("Closed locations"));
        assertTrue(closedList.body().contains("workspacecafe"));
        var archive = get(owner,"/owner/console?location=workspacecafe");
        assertEquals(200,archive.statusCode());
        assertTrue(archive.body().contains("tickets.json"));
        // A zero-location owner still reaches global account navigation.
        jdbc.update("DELETE FROM public.tenant_locations WHERE owner_username='workspaceowner'");
        var empty = get(owner,"/owner/console");
        assertEquals(200,empty.statusCode());
        assertTrue(empty.body().contains("id=\"add-location\""));
        assertFalse(empty.body().contains("class=\"workspace-location-card\""));
    }
}
