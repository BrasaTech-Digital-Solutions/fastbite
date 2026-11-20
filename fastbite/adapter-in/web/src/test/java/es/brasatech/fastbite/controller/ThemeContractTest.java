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
import es.brasatech.fastbite.application.tenant.AppearancePort;
import es.brasatech.fastbite.application.tenant.OwnerWorkspaceService;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.config.RegisteredTenants;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.ThemeCatalog;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.domain.payment.PaymentConfig;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The theme contract (themes/README.md): the base stylesheet names no theme and defines every token once; a theme
 * sets only those tokens, under its own scope; and a theme that exists only in test resources styles the guest menu,
 * the counter and the kitchen with no template, JS or Java change.
 */
@WebMvcTest(controllers = {MenuController.class, CounterController.class, OrderController.class, AppearanceController.class})
@ContextConfiguration(classes = {TestConfig.class, MenuController.class, CounterController.class, OrderController.class,
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
        when(fiscalTicketService.today()).thenReturn(LocalDate.of(2026, 11, 20));
    }

    @Test
    void theBaseStylesheetsNameNoTheme() throws IOException {
        for (String path : List.of("static/css/style.css", "static/css/restaurant.css")) {
            String base = read(path);
            assertThat(base).as(path).doesNotContain("data-theme");
            for (ThemeCatalog.Theme theme : themes.all()) {
                assertThat(base).as("theme id %s in %s", theme.id(), path)
                        .doesNotContainPattern("(?i)(?<![\\w-])" + Pattern.quote(theme.id()) + "(?![\\w-])");
            }
        }
        // The guest pages' stylesheet reads the tokens and never sets one
        assertThat(declaredTokens(withoutComments(read("static/css/restaurant.css")))).isEmpty();
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
                as("MANAGER", get("/kebab/counter")), as("COOK", get("/kebab/dashboard")))) {
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
