package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.discount.DiscountService;
import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.application.fiscal.GuestActionRateLimit;
import es.brasatech.fastbite.application.fiscal.GuestInvoiceRateLimit;
import es.brasatech.fastbite.application.mail.GuestTicketMailPort;
import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.order.OrderNumberService;
import es.brasatech.fastbite.application.order.OrderPricingService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.payment.PaymentService;
import es.brasatech.fastbite.application.platform.PlatformAdmins;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.platform.PromotionService;
import es.brasatech.fastbite.application.settings.GuestPrivacyNoticeService;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.tenant.AppearancePort;
import es.brasatech.fastbite.application.tenant.FreePlanLock;
import es.brasatech.fastbite.application.tenant.LegalAcceptancePort;
import es.brasatech.fastbite.application.tenant.OwnerStaffService;
import es.brasatech.fastbite.application.tenant.OwnerWorkspaceService;
import es.brasatech.fastbite.application.tenant.SubscriptionService;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate.HoldReason;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationRequiredException;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.config.PublicOrigin;
import es.brasatech.fastbite.config.SubscriptionAccessConfig;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.ThemeCatalog;
import es.brasatech.fastbite.config.VerifactuAccessConfig;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import es.brasatech.fastbite.service.GuestTicketSignature;
import es.brasatech.fastbite.service.OrderCheckoutService;
import es.brasatech.fastbite.service.OrderLiveEventService;
import es.brasatech.fastbite.service.StripeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = {
        OrderController.class,
        CounterController.class,
        StripeCheckoutController.class,
        TicketController.class,
        GuestTicketController.class,
        OwnerWorkspaceController.class
}, properties = "spring.messages.basename=i18n/messages")
@ContextConfiguration(classes = {
        TestConfig.class,
        OrderController.class,
        CounterController.class,
        StripeCheckoutController.class,
        TicketController.class,
        GuestTicketController.class,
        OwnerWorkspaceController.class,
        BadRequestAdvice.class,
        VerifactuAccessConfig.class,
        SubscriptionAccessConfig.class,
        EnvironmentAdvice.class,
        PlatformEnvironment.class,
        PlatformLink.class,
        PlatformAdmins.class,
        ThemeCatalog.class,
        SecurityConfig.class,
        TenantRoutingResolver.class,
        WebConfig.class
})
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "fastbite.environment=production",
        "fastbite.link.url=",
        "fastbite.link.standalone=true"
})
class VerifactuGateWebTest {

    private static final String TENANT = "kebab";

    @Autowired MockMvc mockMvc;
    @Autowired TenantRoutingResolver routingResolver;

    @MockitoBean VerifactuAuthorizationGate verifactuGate;
    @MockitoBean SubscriptionService subscriptions;
    @MockitoBean TenantLocationService tenantLocationService;

    @MockitoBean OrderNumberService orderNumberService;
    @MockitoBean OrderPricingService orderPricingService;
    @MockitoBean OrderService orderService;
    @MockitoBean TableService tableService;
    @MockitoBean RestaurantSettingsService settingsService;
    @MockitoBean OrderCheckoutService orderCheckoutService;
    @MockitoBean OrderLiveEventService orderLiveEventService;

    @MockitoBean PaymentService paymentService;
    @MockitoBean GroupService groupService;
    @MockitoBean ProductService productService;
    @MockitoBean CustomizationService customizationService;
    @MockitoBean DiscountService discountService;
    @MockitoBean FiscalTicketService fiscalTicketService;
    @MockitoBean FiscalProfileService fiscalProfileService;

    @MockitoBean StripeService stripeService;
    @MockitoBean PublicOrigin publicOrigin;

    @MockitoBean VerifactuService verifactuService;
    @MockitoBean GuestTicketSignature guestTicketSignature;
    @MockitoBean GuestInvoiceRateLimit guestInvoiceRateLimit;
    @MockitoBean GuestActionRateLimit guestActionRateLimit;
    @MockitoBean GuestTicketMailPort guestTicketMailPort;

    @MockitoBean OwnerWorkspaceService workspace;
    @MockitoBean OwnerStaffService staff;
    @MockitoBean TenantBackupRestorePort backups;
    @MockitoBean AppearancePort appearance;
    @MockitoBean PromotionService promotionService;
    @MockitoBean FreePlanLock freePlanLock;
    @MockitoBean LegalAcceptancePort acceptances;
    @MockitoBean GuestPrivacyNoticeService privacyNotices;
    @MockitoBean VerifactuAuthorizationService verifactuAuthorizations;

    private MockHttpServletRequestBuilder asStaff(MockHttpServletRequestBuilder request) {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_MANAGER");
        var user = new TenantUser("manager", "secret", true, authorities, TENANT);
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));
        return request.session(session).with(csrf()).header("Referer", "http://localhost/" + TENANT + "/counter");
    }

    private TenantUser ownerUser() {
        return new TenantUser("ana", "secret", true,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER")), null);
    }

    @BeforeEach
    void setUp() {
        routingResolver.evictTenant(TENANT);
        TenantLocation loc = new TenantLocation("1", "ana", TENANT, "RESTAURANT", null, null, false, false, null);
        when(tenantLocationService.getLocation(TENANT)).thenReturn(Optional.of(loc));
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(tenantLocationService.isClosed(TENANT)).thenReturn(false);
        when(subscriptions.isClosed(TENANT)).thenReturn(false);
        when(subscriptions.canTakeOrders(TENANT)).thenReturn(true);
        // Gate closed by default — even for kebab under local profile!
        when(verifactuGate.mayIssueTickets(any())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void everyGatedEndpointReturns403JsonWhenOnHoldWithNoBypassForKebabOrLocalProfile() throws Exception {
        // 1. POST /{tenantId}/api/create-order
        mockMvc.perform(post("/" + TENANT + "/api/create-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[],\"customerName\":\"Ana\"}")
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VERIFACTU_AUTHORIZATION_REQUIRED"));

        // 2. POST /counter/api/order
        mockMvc.perform(asStaff(post("/counter/api/order"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VERIFACTU_AUTHORIZATION_REQUIRED"));

        // 3. POST /{tenantId}/api/stripe/create-checkout-session
        mockMvc.perform(post("/" + TENANT + "/api/stripe/create-checkout-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tipPercent\":0}")
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VERIFACTU_AUTHORIZATION_REQUIRED"));

        // 4. POST /counter/api/settle
        mockMvc.perform(asStaff(post("/counter/api/settle"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderIds\":[\"o1\"],\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VERIFACTU_AUTHORIZATION_REQUIRED"));

        // 5. POST /counter/api/orders/{orderId}/pay
        mockMvc.perform(asStaff(post("/counter/api/orders/o1/pay")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VERIFACTU_AUTHORIZATION_REQUIRED"));

        // 6. POST /counter/api/tickets/{ticketId}/invoice
        mockMvc.perform(asStaff(post("/counter/api/tickets/t1/invoice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Client\",\"taxId\":\"B09767898\",\"country\":\"ES\",\"address\":\"Street 1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VERIFACTU_AUTHORIZATION_REQUIRED"));

        // 7. POST /counter/api/tickets/{ticketId}/refund
        mockMvc.perform(asStaff(post("/counter/api/tickets/t1/refund"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Wrong item\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VERIFACTU_AUTHORIZATION_REQUIRED"));

        // 8. POST /counter/api/tickets/{ticketId}/cancel
        mockMvc.perform(asStaff(post("/counter/api/tickets/t1/cancel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Wrong table\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VERIFACTU_AUTHORIZATION_REQUIRED"));

        verifyNoInteractions(fiscalTicketService, orderService, orderCheckoutService);
    }

    @Test
    void guestInvoiceFormReturns403HtmlViewWithMessageAndNeverCallsReplaceWhenOnHold() throws Exception {
        FiscalTicket ticket = new FiscalTicket(
                "t1", TicketKind.SIMPLIFIED, "KH-T26", 1, "KH-T26-000001", false,
                OffsetDateTime.now(), "manager",
                new FiscalTicket.Issuer("Kebab SL", "Kebab", "B09767898", "Calle 1", "28013", "Madrid", "Madrid", null),
                null, List.of(), BigDecimal.ZERO, new BigDecimal("10.00"),
                new VatBreakdown(List.of(), new BigDecimal("9.09"), new BigDecimal("0.91"), new BigDecimal("10.00")),
                BigDecimal.ZERO, PaymentMethod.CASH, new BigDecimal("10.00"), BigDecimal.ZERO,
                null, List.of("o1"), "1", null, null);
        when(guestTicketSignature.valid(TENANT, "t1", "sig")).thenReturn(true);
        when(guestTicketSignature.sign(anyString(), anyString())).thenReturn("sig");
        when(fiscalTicketService.findById("t1")).thenReturn(Optional.of(ticket));
        when(fiscalTicketService.corrections("t1")).thenReturn(List.of());
        when(fiscalTicketService.cancellation("t1")).thenReturn(Optional.empty());
        when(verifactuService.findForTicket("t1")).thenReturn(Optional.empty());

        mockMvc.perform(post("/" + TENANT + "/ticket/t1/invoice")
                        .param("s", "sig")
                        .param("name", "Cliente SL")
                        .param("taxId", "B09767898")
                        .param("country", "ES")
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(view().name("fastfood/ticket"))
                .andExpect(model().attributeExists("invoiceError"));

        verify(fiscalTicketService, never()).replace(any(), any(), any());
    }

    @Test
    void kitchenTransitionsAndSubsanacionAmendStillSucceedWhenOnHold() throws Exception {
        // Kitchen order status transition stays allowed
        mockMvc.perform(asStaff(post("/" + TENANT + "/api/order/o1/next")))
                .andExpect(status().isOk());
        verify(orderService).moveToNextStatus("o1");

        // VERI*FACTU amend (subsanación) stays allowed
        mockMvc.perform(post("/owner/workspace/" + TENANT + "/verifactu/records/rec-1/amend")
                        .with(user(ownerUser()))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(workspace).amendVerifactuRecord("ana", TENANT, "rec-1");
    }

    @Test
    void serviceThrownVerifactuAuthorizationRequiredExceptionMapsTo403Json() throws Exception {
        // Interceptor allows through, but defense-in-depth inside FiscalTicketService rejects
        when(verifactuGate.mayIssueTickets(any())).thenReturn(true);
        when(fiscalTicketService.settle(any(), any(), any(), anyString()))
                .thenThrow(new VerifactuAuthorizationRequiredException(HoldReason.REVOKED));

        mockMvc.perform(asStaff(post("/counter/api/settle"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderIds\":[\"o1\"],\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VERIFACTU_AUTHORIZATION_REQUIRED"));
    }

    @Test
    void gatedEndpointProceedsWhenAuthorised() throws Exception {
        when(verifactuGate.mayIssueTickets(any())).thenReturn(true);
        FiscalTicket ticket = new FiscalTicket(
                "t1", TicketKind.SIMPLIFIED, "KH-T26", 1, "KH-T26-000001", false,
                OffsetDateTime.now(), "manager",
                new FiscalTicket.Issuer("Kebab SL", "Kebab", "B09767898", "Calle 1", "28013", "Madrid", "Madrid", null),
                null, List.of(), BigDecimal.ZERO, new BigDecimal("10.00"),
                new VatBreakdown(List.of(), new BigDecimal("9.09"), new BigDecimal("0.91"), new BigDecimal("10.00")),
                BigDecimal.ZERO, PaymentMethod.CASH, new BigDecimal("10.00"), BigDecimal.ZERO,
                null, List.of("o1"), "1", null, null);
        when(fiscalTicketService.settle(any(), any(), any(), anyString())).thenReturn(ticket);

        mockMvc.perform(asStaff(post("/counter/api/settle"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderIds\":[\"o1\"],\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullNumber").value("KH-T26-000001"));
    }
}
