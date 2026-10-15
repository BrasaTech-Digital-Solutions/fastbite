package es.brasatech.fastbite;

import es.brasatech.fastbite.application.tenant.SubscriptionGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:subscription-flow;DB_CLOSE_DELAY=-1",
    "spring.jpa.properties.jakarta.persistence.jdbc.url=jdbc:h2:mem:subscription-flow;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","spring.mail.password=",
    "fastbite.migrations.mode=migrate","spring.jpa.hibernate.ddl-auto=none","spring.session.jdbc.initialize-schema=never",
    "stripe.secret.key=","stripe.webhook.secret=","stripe.platform.webhook.secret=","stripe.restaurant-price-id=","stripe.connected-account="})
class SubscriptionFlowTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SubscriptionGateway gateway;
    private HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();}
    private HttpResponse<String> get(HttpClient c,String path)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> post(HttpClient c,String path,String body)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    private String csrf(String html){var m=Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);assertTrue(m.find());return URLEncoder.encode(m.group(1),StandardCharsets.UTF_8);}
    @Test void funnelTrialExpiryBillingOwnershipAndExportWorkTogether()throws Exception {
        var owner=client();
        var home=get(owner,"/");assertEquals(200,home.statusCode());assertTrue(home.body().contains("hospitality."));assertTrue(home.body().contains(">49</span>"));
        var signup=get(owner,"/signup");
        var rejected=post(owner,"/signup","tenantId=kebab&username=billingflowowner&fullName=Test+Owner&password=local-test-password&_csrf="+csrf(signup.body()));
        assertEquals(200,rejected.statusCode());
        assertTrue(rejected.body().contains("is reserved"));
        assertTrue(rejected.body().contains("value=\"kebab\""));
        assertTrue(rejected.body().contains("value=\"billingflowowner\""));
        assertTrue(rejected.body().contains("value=\"Test Owner\""));
        assertFalse(rejected.body().contains("local-test-password"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id='kebab'",Integer.class));
        var created=post(owner,"/signup","tenantId=BillingCafe&username=billingflowowner&fullName=Test+Owner&password=local-test-password&_csrf="+csrf(signup.body()));
        assertEquals(200,created.statusCode());assertTrue(created.body().contains("successfully registered"));assertTrue(created.body().contains("/billingcafe/login"));
        var started=jdbc.queryForObject("SELECT trial_started_at FROM public.tenant_billing WHERE tenant_id='billingcafe'",java.sql.Timestamp.class);
        post(owner,"/signup","tenantId=billingcafe&username=billingflowowner&fullName=Test+Owner&password=local-test-password&_csrf="+csrf(get(owner,"/signup").body()));
        assertEquals(started,jdbc.queryForObject("SELECT trial_started_at FROM public.tenant_billing WHERE tenant_id='billingcafe'",java.sql.Timestamp.class));
        var login=get(owner,"/login");assertEquals(302,post(owner,"/login","username=billingflowowner&password=local-test-password&_csrf="+csrf(login.body())).statusCode());
        String token=csrf(get(owner,"/owner/console").body());
        var billing=get(owner,"/owner/billing/billingcafe");assertEquals(200,billing.statusCode());assertTrue(billing.body().contains("Free trial"));
        assertEquals(404,get(owner,"/owner/billing/notmyrestaurant").statusCode());
        post(owner,"/owner/add-location","tenantId=billingextra&plan=Enterprise&_csrf="+token);
        assertEquals("RESTAURANT",jdbc.queryForObject("SELECT plan FROM public.tenant_locations WHERE tenant_id='billingextra'",String.class));
        assertEquals("LOCAL_TRIAL",jdbc.queryForObject("SELECT status FROM public.tenant_billing WHERE tenant_id='billingextra'",String.class));
        jdbc.update("UPDATE public.tenant_billing SET trial_started_at=? WHERE tenant_id='billingcafe'",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(31L*86400)));
        for(String path:new String[]{"/billingcafe/api/create-order","/counter/api/order?tenantId=billingcafe"}) {
            String separator=path.contains("?")?"&":"?";
            var response=owner.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path+separator+"_csrf="+token)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(402,response.statusCode(),response.body());assertTrue(response.body().contains("New orders are paused"));
        }
        assertEquals(200,get(owner,"/owner/billing/billingcafe").statusCode());
        var backup=get(owner,"/billingcafe/api/backoffice/maintenance/backup");assertEquals(200,backup.statusCode());assertTrue(backup.body().startsWith("PK"));
        var quote=post(owner,"/owner/group-quote","email=group%40example.test&locations=3&_csrf="+token);assertEquals(302,quote.statusCode());
        assertEquals(3,jdbc.queryForObject("SELECT locations FROM public.group_quote_requests WHERE owner_username='billingflowowner'",Integer.class));
        post(owner,"/owner/delete-location","tenantId=billingcafe&_csrf="+token);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM public.tenant_locations WHERE tenant_id='billingcafe'",Integer.class));
    }
}
