package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.discount.DiscountService;
import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.fiscal.FiscalTicketService;
import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.order.OrderNumberService;
import es.brasatech.fastbite.application.order.OrderPricingService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.payment.PaymentService;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.table.TableSignatureUtil;
import es.brasatech.fastbite.application.tenant.AppearancePort;
import es.brasatech.fastbite.application.tenant.OwnerWorkspaceService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.config.PublicOrigin;
import es.brasatech.fastbite.config.RegisteredTenants;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.ThemeCatalog;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.allergen.Allergen;
import es.brasatech.fastbite.domain.fiscal.DayReport;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.TicketLine;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import es.brasatech.fastbite.domain.payment.PaymentConfig;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.table.TableStatus;
import es.brasatech.fastbite.dto.menu.MenuData;
import es.brasatech.fastbite.dto.office.MenuDataService;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.security.TenantUser;
import es.brasatech.fastbite.service.OrderCheckoutService;
import es.brasatech.fastbite.service.OrderLiveEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The theme contract (themes/README.md): the base stylesheet names no theme and defines every token once; a theme
 * sets only those tokens, under its own scope; and a theme that exists only in test resources styles the guest menu,
 * the counter and the kitchen with no template, JS or Java change.
 */
@WebMvcTest(controllers = {MenuController.class, CounterController.class, OrderController.class,
        TicketController.class, DayReportController.class, TableQrController.class, AppearanceController.class},
        properties = "spring.messages.basename=i18n/messages")
@ContextConfiguration(classes = {TestConfig.class, MenuController.class, CounterController.class, OrderController.class,
        TicketController.class, DayReportController.class, TableQrController.class,
        AppearanceController.class, AppearanceAdvice.class, ThemeCatalog.class,
        SecurityConfig.class, TenantRoutingResolver.class, WebConfig.class})
class ThemeContractTest {

    private static final String TEST_THEME = "test-contrast";
    private static final Pattern DECLARED_TOKEN = Pattern.compile("(--[a-z][a-z0-9-]*)\\s*:");
    private static final Pattern RULE = Pattern.compile("([^{}]+)\\{[^{}]*}");

    @Autowired private MockMvc mockMvc;
    @Autowired private ThemeCatalog themes;

    @MockitoBean private AppearancePort appearance;
    @MockitoBean private OwnerWorkspaceService workspace;
    @MockitoBean private TenantLocationService tenantLocationService;
    @MockitoBean private MenuDataService menuDataService;
    @MockitoBean private TableService tableService;
    @MockitoBean private TableSignatureUtil tableSignatureUtil;
    @MockitoBean private PublicOrigin publicOrigin;
    @MockitoBean private DiscountService discountService;
    @MockitoBean private OrderPricingService orderPricingService;
    @MockitoBean private OrderService orderService;
    @MockitoBean private OrderNumberService orderNumberService;
    @MockitoBean private RestaurantSettingsService settingsService;
    @MockitoBean private OrderCheckoutService orderCheckoutService;
    @MockitoBean private OrderLiveEventService orderLiveEventService;
    @MockitoBean private PaymentService paymentService;
    @MockitoBean private GroupService groupService;
    @MockitoBean private ProductService productService;
    @MockitoBean private CustomizationService customizationService;
    @MockitoBean private FiscalTicketService fiscalTicketService;
    @MockitoBean private FiscalProfileService fiscalProfileService;
    @MockitoBean private VerifactuService verifactuService;

    @BeforeEach
    void setUp() {
        RegisteredTenants.all(tenantLocationService);
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(appearance.effectiveTheme(any())).thenReturn(TEST_THEME);
        when(settingsService.get()).thenReturn(RestaurantSettings.DEFAULTS);
        Map<String, String> dictionary = new HashMap<>();
        for (String key : List.of("cartEmpty", "confirmAndSubmitBtn", "orderConfirmation", "orderItems", "orderTotal",
                "subtotal", "tax", "total")) dictionary.put(key, key);
        when(menuDataService.buildMenuData(any(Locale.class)))
                .thenReturn(new MenuData(new HashMap<>(), List.of(), "", dictionary, List.of(), ""));
        when(paymentService.getActiveConfig()).thenReturn(new PaymentConfig("cash", List.of("CASH"), List.of(), true));
        LocalDate day = LocalDate.of(2026, 11, 20);
        when(fiscalTicketService.today()).thenReturn(day);
        when(fiscalTicketService.now()).thenReturn(LocalTime.of(12, 0));
        when(fiscalTicketService.issuedOn(any())).thenReturn(List.of());
        when(fiscalTicketService.dayReport(any())).thenReturn(DayReport.of(day, List.of(), List.of()));
        when(fiscalProfileService.get()).thenReturn(FiscalProfile.EMPTY);
        when(publicOrigin.resolve(any())).thenReturn("https://example.test");
        when(publicOrigin.isLocal(any())).thenReturn(false);
    }

    @Test
    void theBaseStylesheetsNameNoTheme() throws IOException {
        for (String path : List.of("static/css/style.css", "static/css/restaurant.css", "static/css/counter.css",
                "static/css/kitchen.css", "static/css/tickets.css", "static/css/table-qr.css")) {
            String base = read(path);
            assertThat(base).as(path).doesNotContain("data-theme");
            for (ThemeCatalog.Theme theme : themes.all()) {
                assertThat(base).as("theme id %s in %s", theme.id(), path)
                        .doesNotContainPattern("(?i)(?<![\\w-])" + Pattern.quote(theme.id()) + "(?![\\w-])");
            }
        }
        // The guest and staff pages' stylesheets read the tokens and never set one
        assertThat(declaredTokens(withoutComments(read("static/css/restaurant.css")))).isEmpty();
        assertThat(declaredTokens(withoutComments(read("static/css/counter.css")))).isEmpty();
        assertThat(declaredTokens(withoutComments(read("static/css/kitchen.css")))).isEmpty();
        assertThat(declaredTokens(withoutComments(read("static/css/tickets.css")))).isEmpty();
        assertThat(declaredTokens(withoutComments(read("static/css/table-qr.css")))).isEmpty();
    }

    @Test
    void everyThemeSetsOnlyContractTokensUnderItsOwnScope() throws IOException {
        Set<String> contract = baseTokens();
        assertThat(themes.all()).extracting(ThemeCatalog.Theme::id).contains("fastfood", "cafe", "modern", TEST_THEME);
        for (ThemeCatalog.Theme theme : themes.all()) {
            String css = withoutComments(themes.asset(theme.id(), "theme.css").getContentAsString(StandardCharsets.UTF_8));
            assertThat(declaredTokens(css)).as("tokens set by %s", theme.id()).isSubsetOf(contract);
            Matcher rule = RULE.matcher(css);
            while (rule.find()) {
                for (String selector : rule.group(1).split(",")) {
                    assertThat(selector.strip()).as("selector in %s", theme.id())
                            .startsWith("[data-theme=\"" + theme.id() + "\"]");
                }
            }
        }
        String testTheme = themes.asset(TEST_THEME, "theme.css").getContentAsString(StandardCharsets.UTF_8);
        assertThat(declaredTokens(withoutComments(testTheme))).as("the test theme sets every token").isEqualTo(contract);
    }

    @Test
    void theReadmeListsEveryTokenTheBaseDefines() throws IOException {
        Set<String> listed = new LinkedHashSet<>();
        Matcher row = Pattern.compile("(?m)^\\| `(--[a-z0-9-]+)` \\|").matcher(read("themes/README.md"));
        while (row.find()) listed.add(row.group(1));
        assertThat(listed).isEqualTo(baseTokens());
    }

    @Test
    void aThemeOnlyInTestResourcesStylesTheMenuCounterAndKitchen() throws Exception {
        String stylesheet = themes.resolve(TEST_THEME).stylesheet();
        for (MockHttpServletRequestBuilder page : List.of(get("/kebab/menu"),
                as("MANAGER", get("/kebab/counter")), as("COOK", get("/kebab/dashboard")),
                as("MANAGER", get("/kebab/counter/tickets")))) {
            String html = mockMvc.perform(page).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("data-theme=\"" + TEST_THEME + "\"", "href=\"" + stylesheet + "\"");
            // The theme reaches the page through those two places only: nothing in the markup is theme-specific
            assertThat(html.split(Pattern.quote(TEST_THEME), -1)).hasSize(3);
        }
        mockMvc.perform(get(stylesheet))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(containsString("--color-primary: #000000;")));
    }

    @Test
    void theCounterAndTheFragmentsItLoadsCarryNoStyleAndShowAmountsInThePageLanguage() throws Exception {
        when(productService.findAll()).thenReturn(List.of(new ProductDto("latte", "Latte", new BigDecimal("1.80"), "", null,
                Set.of(), true, null, Set.of(Allergen.MILK))));
        when(orderPricingService.price(any(), any())).thenAnswer(call -> call.getArgument(0));
        when(discountService.calculateDiscount(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        Locale spanish = Locale.forLanguageTag("es");
        String page = render(as("MANAGER", get("/kebab/counter")).locale(spanish));
        String products = render(as("MANAGER", get("/counter/fragments/products")).locale(spanish)
                .header("Referer", "http://localhost/kebab/counter"));
        String cart = render(as("MANAGER", post("/counter/fragments/order-cart")).locale(spanish).with(csrf())
                .header("Referer", "http://localhost/kebab/counter").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"id\":\"l1\",\"productId\":\"latte\",\"itemId\":\"latte\",\"name\":\"Latte\",\"price\":1.80,\"quantity\":2,\"customizations\":[]}]"));
        for (Map.Entry<String, String> rendered : Map.of("counter", page, "products", products, "cart", cart).entrySet()) {
            String html = rendered.getValue();
            assertThat(RestaurantTemplateContractTest.INLINE_STYLE.matcher(html).find()).as("inline style in %s", rendered.getKey()).isFalse();
            assertThat(RestaurantTemplateContractTest.COLOUR.matcher(html).find()).as("colour in %s", rendered.getKey()).isFalse();
            assertThat(RestaurantTemplateContractTest.BOOTSTRAP_OR_FONT_AWESOME.matcher(html).find())
                    .as("Bootstrap or Font Awesome in %s", rendered.getKey()).isFalse();
        }
        // The page's language reaches the scripts, which format with Intl.NumberFormat like the server
        assertThat(page).contains("lang=\"es\"", "id=\"clearOrderModal\"", ">0,00\u00a0€<");
        // A price with its currency, and the allergen named next to its icon in Spanish
        assertThat(products).contains(">1,80\u00a0€<", "role=\"list\"")
                .containsPattern("<span aria-hidden=\"true\">\\x{1F95B}</span> <span>Lácteos</span>");
        assertThat(cart).contains(">3,60\u00a0€<");
    }

    @Test
    void counterAndDashboardRenderTranslatedMenuAndChimeLabelsInSpanish() throws Exception {
        Locale spanish = Locale.forLanguageTag("es");
        String counter = render(as("MANAGER", get("/kebab/counter")).locale(spanish));
        assertThat(counter)
                .doesNotContain("Toggle Menu", "Chime Off")
                .contains("Mostrar/ocultar menú", "Sin sonido",
                        "data-text-on=\"Con sonido\"", "data-text-off=\"Sin sonido\"");

        String dashboard = render(as("COOK", get("/kebab/dashboard")).locale(spanish));
        assertThat(dashboard)
                .doesNotContain("Chime Off")
                .contains("Sin sonido", "data-text-on=\"Con sonido\"", "data-text-off=\"Sin sonido\"");
    }

    @Test
    void theKitchenRendersNativeSpanishMarkupWithTabsAndDialogs() throws Exception {
        Locale spanish = Locale.forLanguageTag("es");
        String managerHtml = render(as("MANAGER", get("/kebab/dashboard")).locale(spanish));
        assertThat(RestaurantTemplateContractTest.INLINE_STYLE.matcher(managerHtml).find()).as("inline style in dashboard").isFalse();
        assertThat(RestaurantTemplateContractTest.COLOUR.matcher(managerHtml).find()).as("colour in dashboard").isFalse();
        assertThat(RestaurantTemplateContractTest.BOOTSTRAP_OR_FONT_AWESOME.matcher(managerHtml).find())
                .as("Bootstrap or Font Awesome in dashboard").isFalse();
        assertThat(managerHtml)
                .contains("lang=\"es\"", "/css/kitchen.css", "role=\"tablist\"", "aria-selected=\"true\"",
                        "<dialog", "id=\"cancelModal\"", "id=\"statusChangeModal\"",
                        "id=\"toastContainer\"", "role=\"status\"", "Duración")
                .doesNotContain("Change status for order #", "Chime Off");

        String cookHtml = render(as("COOK", get("/kebab/dashboard")).locale(spanish));
        assertThat(cookHtml)
                .contains("id=\"cookDashboard\"", "id=\"queueDashboard\"")
                .doesNotContain("id=\"cashierDashboard\"", "id=\"waiterDashboard\"", "id=\"managerTabs\"");
    }

    @Test
    void ticketsTableQrAndDayReportRenderNativeSpanishMarkupWithRealRecords() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 20);
        OffsetDateTime issuedAt = day.atTime(12, 30).atOffset(ZoneOffset.UTC);
        FiscalTicket.Issuer issuer = new FiscalTicket.Issuer("Kebab SL", "Kebab House", "B12345674",
                "Calle Mayor 1", "28001", "Madrid", "Madrid", "600000000");
        VatBreakdown vat = new VatBreakdown(List.of(new VatBreakdown.Line(
                new BigDecimal("10.00"), new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("11.00"))),
                new BigDecimal("10.00"), new BigDecimal("1.00"), new BigDecimal("11.00"));
        List<TicketLine> lines = List.of(new TicketLine("Kebab", 1, new BigDecimal("11.00"), new BigDecimal("11.00"),
                new BigDecimal("10.00"), List.of()));
        FiscalTicket cashTicket = new FiscalTicket("t-cash", TicketKind.SIMPLIFIED, "KH-T26", 1, "KH-T26-000001",
                false, issuedAt, "carla", issuer, null, lines, BigDecimal.ZERO, new BigDecimal("11.00"), vat,
                BigDecimal.ZERO, PaymentMethod.CASH, new BigDecimal("20.00"), new BigDecimal("9.00"), null,
                List.of("o-1"), "12", "Mesa 1", null);
        FiscalTicket onlineTicket = new FiscalTicket("t-online", TicketKind.SIMPLIFIED, "KH-T26", 2, "KH-T26-000002",
                false, issuedAt.plusMinutes(5), "online", issuer, null, lines, BigDecimal.ZERO, new BigDecimal("11.00"), vat,
                BigDecimal.ZERO, PaymentMethod.ONLINE, null, null, null,
                List.of("o-2"), "13", null, "pi_123");
        List<FiscalTicket> issued = List.of(cashTicket, onlineTicket);
        when(fiscalTicketService.issuedOn(day)).thenReturn(issued);
        when(fiscalTicketService.dayReport(day)).thenReturn(DayReport.of(day, issued, List.of()));
        when(fiscalTicketService.corrections(anyString())).thenReturn(List.of());
        when(fiscalTicketService.cancellation(anyString())).thenReturn(Optional.empty());
        when(fiscalProfileService.get()).thenReturn(new FiscalProfile("Kebab SL", "Kebab House", "B12345674",
                "Calle Mayor 1", "28001", "Madrid", "Madrid", "600000000", null, "KH", new BigDecimal("10.00")));
        when(tableService.findAll()).thenReturn(List.of(new Table("t-1", "Mesa 1", 4, TableStatus.AVAILABLE, true, List.of())));
        when(tableSignatureUtil.generateSignature("kebab", "t-1")).thenReturn("sig-1");

        Locale spanish = Locale.forLanguageTag("es");
        String managerTickets = render(as("MANAGER", get("/kebab/counter/tickets")).locale(spanish));
        assertThat(RestaurantTemplateContractTest.INLINE_STYLE.matcher(managerTickets).find()).as("inline style in tickets").isFalse();
        assertThat(RestaurantTemplateContractTest.COLOUR.matcher(managerTickets).find()).as("colour in tickets").isFalse();
        assertThat(RestaurantTemplateContractTest.BOOTSTRAP_OR_FONT_AWESOME.matcher(managerTickets).find())
                .as("Bootstrap or Font Awesome in tickets").isFalse();
        assertThat(managerTickets)
                .contains("lang=\"es\"", "/css/tickets.css", "Kebab House", "<dialog", "id=\"invoiceModal\"", "id=\"refundModal\"",
                        "id=\"cancelModal\"", "id=\"cashCheckModal\"", "id=\"inv-taxId\"", "id=\"toastContainer\"",
                        "role=\"status\"", "KH-T26-000001", "KH-T26-000002",
                        "data-ticket=\"t-cash\" data-number=\"KH-T26-000001\" data-action=\"cancel\"")
                .doesNotContain("data-ticket=\"t-online\" data-number=\"KH-T26-000002\" data-action=\"cancel\"");

        String waiterTickets = render(as("WAITER", get("/kebab/counter/tickets")).locale(spanish));
        assertThat(waiterTickets)
                .contains("data-action=\"print\"", "data-action=\"invoice\"")
                .doesNotContain("data-action=\"refund\"", "data-action=\"cancel\"", "id=\"cashCheckModal\"", "/counter/day-report");

        String cashierTickets = render(as("CASHIER", get("/kebab/counter/tickets")).locale(spanish));
        assertThat(cashierTickets)
                .contains("data-action=\"print\"", "data-action=\"invoice\"", "id=\"cashCheckModal\"", "/counter/day-report")
                .doesNotContain("data-action=\"refund\"", "data-action=\"cancel\"");

        String qrHtml = render(as("MANAGER", get("/kebab/backoffice/tables/qr-codes")).locale(spanish));
        assertThat(RestaurantTemplateContractTest.INLINE_STYLE.matcher(qrHtml).find()).as("inline style in tableQrCodes").isFalse();
        assertThat(RestaurantTemplateContractTest.COLOUR.matcher(qrHtml).find()).as("colour in tableQrCodes").isFalse();
        assertThat(RestaurantTemplateContractTest.BOOTSTRAP_OR_FONT_AWESOME.matcher(qrHtml).find())
                .as("Bootstrap or Font Awesome in tableQrCodes").isFalse();
        assertThat(qrHtml)
                .contains("lang=\"es\"", "/css/table-qr.css", "Mesa 1", "sig-1")
                .doesNotContain("Table QR codes", "Put each code", "There are no active tables");

        String reportHtml = render(as("MANAGER", get("/kebab/counter/day-report")).locale(spanish));
        assertThat(reportHtml)
                .contains("lang=\"es\"", "/css/ticket.css", "KH-T26-000001")
                .doesNotContain("/webjars/", "bootstrap", "fa-");
    }

    @Test
    void cafeAndModernDoNotSetColorOnReassignDialogHeader() throws IOException {
        for (String themeId : List.of("cafe", "modern")) {
            String css = withoutComments(themes.asset(themeId, "theme.css").getContentAsString(StandardCharsets.UTF_8));
            Matcher rule = RULE.matcher(css);
            while (rule.find()) {
                if (rule.group(1).contains(".counter-dialog-header-reassign")) {
                    assertThat(rule.group(0)).as("%s should not set color on .counter-dialog-header-reassign", themeId)
                            .doesNotContainPattern("(?<![\\w-])color\\s*:");
                }
            }
        }
    }

    private String render(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static MockHttpServletRequestBuilder as(String role, MockHttpServletRequestBuilder request) {
        var authorities = AuthorityUtils.createAuthorityList("ROLE_" + role);
        var user = new TenantUser("carla", "hash", true, authorities, "kebab");
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT",
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities)));
        return request.session(session);
    }

    /** The tokens of the contract: every custom property the base stylesheet sets on its first rule, {@code :root}. */
    private static Set<String> baseTokens() throws IOException {
        String base = withoutComments(read("static/css/style.css"));
        Matcher root = Pattern.compile("^\\s*:root\\s*\\{([^}]*)}").matcher(base);
        assertThat(root.find()).as("the base stylesheet starts with :root").isTrue();
        Set<String> tokens = declaredTokens(root.group(1));
        assertThat(declaredTokens(base.substring(root.end()))).as("tokens set outside :root").isEmpty();
        return tokens;
    }

    private static Set<String> declaredTokens(String css) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher token = DECLARED_TOKEN.matcher(css);
        while (token.find()) tokens.add(token.group(1));
        return tokens;
    }

    private static String withoutComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
