package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.config.PriceDialect;
import es.brasatech.fastbite.domain.discount.DiscountRule;
import es.brasatech.fastbite.domain.discount.DiscountScope;
import es.brasatech.fastbite.domain.discount.DiscountType;
import es.brasatech.fastbite.domain.group.Group;
import es.brasatech.fastbite.domain.payment.MoneyDenomination;
import es.brasatech.fastbite.domain.payment.MoneyType;
import es.brasatech.fastbite.domain.payment.PaymentConfig;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.table.TableStatus;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Render the actual fragments so syntax and model changes cannot silently break counter icons or cash check. */
class CounterFragmentsRenderTest {

    private final SpringTemplateEngine engine = engine();

    @Test
    void categoryIconsRenderKnownUnknownAndEmojiValues() {
        Context context = new Context(Locale.ENGLISH);
        context.setVariable("categories", List.of(
                new Group("coffee", "Coffee", "", "fas fa-mug-hot", List.of()),
                new Group("other", "Other", "", "fas fa-unknown", List.of()),
                new Group("dessert", "Dessert", "", "🍨", List.of()),
                new Group("empty", "Empty", "", "", List.of())));
        context.setVariable("selectedGroup", "coffee");

        String html = engine.process("templates/fastfood/fragments/counter.html", Set.of("categories"), context);

        assertThat(html).contains("href=\"#i-grid\"", "href=\"#i-mug-hot\"", "href=\"#i-list\"", "href=\"#i-tag\"",
                "🍨", "data-group-id=\"coffee\"", "data-group-id=\"other\"", "data-group-id=\"dessert\"")
                .doesNotContain("fa-mug-hot", "fa-unknown");
    }

    @Test
    void denominationsRenderWithoutBootstrapClasses() {
        String html = engine.process("<div data-test-wrapper th:replace=\"~{templates/fastfood/fragments/cashCheck.html :: denominations}\"></div>",
                new Context(Locale.ENGLISH));

        assertThat(html).contains("class=\"cash-check-denominations\"", "data-denomination=\"0.01\"",
                "data-denomination=\"100\"").doesNotContain("mb-3");
        assertThat(html.split("data-denomination=", -1)).hasSize(14);
    }

    @Test
    void reassignTablesRenderTranslatedStatusWhileKeepingStatusClasses() {
        Context context = new Context(Locale.forLanguageTag("es"));
        context.setVariable("tables", oneTablePerStatus());
        context.setVariable("currentTableId", "t-OCCUPIED");

        String html = engine.process("templates/fastfood/fragments/counter.html", Set.of("reassign-tables"), context);
        String visibleText = html.replaceAll("<[^>]+>", " ");

        assertThat(html).contains("status-available", "status-occupied", "status-billing");
        assertThat(visibleText).contains("DISPONIBLE", "OCUPADA", "FACTURACIÓN")
                .doesNotContain("AVAILABLE", "OCCUPIED", "BILLING");
    }

    @Test
    void tablesFragmentRendersTranslatedStatusWhileKeepingStatusClasses() {
        Context context = new Context(Locale.forLanguageTag("es"));
        context.setVariable("tables", oneTablePerStatus());
        context.setVariable("selectedTableId", "t-OCCUPIED");
        context.setVariable("settings", RestaurantSettings.DEFAULTS);

        String html = engine.process("templates/fastfood/fragments/counter.html", Set.of("tables"), context);
        String visibleText = html.replaceAll("<[^>]+>", " ");

        assertThat(html).contains("status-available", "status-occupied", "status-billing");
        assertThat(visibleText).contains("DISPONIBLE", "OCUPADA", "FACTURACIÓN")
                .doesNotContain("AVAILABLE", "OCCUPIED", "BILLING");
    }

    @Test
    void backOfficeTablesListAndFormRenderTranslatedStatusWithRealTableRecords() {
        Context listCtx = new Context(Locale.forLanguageTag("es"));
        listCtx.setVariable("tables", oneTablePerStatus());
        String listHtml = engine.process("templates/fastfood/fragments/backOffice.html", Set.of("tables-list"), listCtx);
        String listText = listHtml.replaceAll("<[^>]+>", " ");
        assertThat(listHtml).contains("bo-item-card", "bo-icon-btn", "openTableQr(this.dataset.id)",
                        "data-table-status=\"available\"", "data-table-status=\"occupied\"", "data-table-status=\"billing\"")
                .doesNotContain("cart-item", "btn-group", "fa-");
        assertThat(listText).contains("DISPONIBLE", "OCUPADA", "FACTURACIÓN", "Activo")
                .doesNotContain("AVAILABLE", "OCCUPIED", "BILLING", "Active");

        Context formCtx = new Context(Locale.forLanguageTag("es"));
        formCtx.setVariable("statuses", List.of(TableStatus.values()));
        String formHtml = engine.process("templates/fastfood/fragments/backOffice.html", Set.of("table-form"), formCtx);
        assertThat(formHtml).contains("bo-card", "value=\"AVAILABLE\"", "value=\"OCCUPIED\"", "value=\"BILLING\"")
                .contains(">DISPONIBLE<", ">OCUPADA<", ">FACTURACIÓN<")
                .doesNotContain("form-select", "form-control", "fa-");
    }

    @Test
    void backOfficePaymentAndDiscountsRenderNativeSpanishWithRealRecords() {
        PaymentConfig cfg = new PaymentConfig("default", List.of("CASH", "CARD"),
                List.of(new MoneyDenomination(new BigDecimal("20.00"), "/images/money/20.png", MoneyType.BANKNOTE)), true);
        Context payCtx = new Context(Locale.forLanguageTag("es"));
        payCtx.setVariable("paymentConfig", cfg);
        payCtx.setVariable("allConfigs", List.of(cfg));
        String payHtml = engine.process("templates/fastfood/fragments/backOffice.html", Set.of("payment-section"), payCtx);
        assertThat(payHtml).contains("bo-payment-grid", "bo-denom-grid", "openDenomImageSelector()",
                        "Efectivo", "Tarjeta", "Billete", "Activo")
                .doesNotContain("style=", "form-select", "fa-", "Design ID/Name");

        DiscountRule rule = new DiscountRule("d-1", "Promo Verano", DiscountScope.ORDER, DiscountType.PERCENTAGE,
                new BigDecimal("10"), new BigDecimal("5.00"), "VERANO10", true, false, true);
        Context discCtx = new Context(Locale.forLanguageTag("es"));
        discCtx.setVariable("discounts", List.of(rule));
        String discHtml = engine.process("templates/fastfood/fragments/backOffice.html", Set.of("discounts-list"), discCtx);
        assertThat(discHtml).contains("bo-item-card", "Promo Verano", "VERANO10", "Activo", "Activo en caja", "No acumulable")
                .doesNotContain("cart-item", "btn-group", "fa-", "Counter POS Active", "Not Accumulative");
    }

    @Test
    void everyTableStatusHasATranslationKeyInEveryBundle() throws IOException {
        for (String file : List.of("i18n/messages.properties", "i18n/messages_es.properties", "i18n/messages_pt.properties")) {
            Properties props = new Properties();
            try (var reader = new InputStreamReader(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (TableStatus status : TableStatus.values()) {
                String key = "table.status." + status.name().toLowerCase(Locale.ROOT);
                assertThat(props.getProperty(key)).as("%s in %s", key, file).isNotBlank();
            }
        }
    }

    private static List<Table> oneTablePerStatus() {
        return Arrays.stream(TableStatus.values())
                .map(status -> new Table("t-" + status.name(), "Mesa " + (status.ordinal() + 1), 4, status, true, List.of()))
                .toList();
    }

    @Test
    void orderCardPayButtonIsLastInActionsForTableAndTakeawayOrders() {
        assertPayButtonIsLast(false, "UNPAID", "counter-button-success");
        assertPayButtonIsLast(false, "PAID", "counter-button-success-filled");
        assertPayButtonIsLast(true, "UNPAID", "counter-button-success");
    }

    private void assertPayButtonIsLast(boolean takeaway, String paymentStatus, String expectedPayClass) {
        Context context = new Context(Locale.forLanguageTag("es"));
        Map<String, Object> order = Map.ofEntries(
                Map.entry("id", "ord-1"),
                Map.entry("orderNumber", 42),
                Map.entry("status", "CREATED"),
                Map.entry("paymentStatus", paymentStatus),
                Map.entry("subtotal", new BigDecimal("2.80")),
                Map.entry("discount", BigDecimal.ZERO),
                Map.entry("total", new BigDecimal("2.80")),
                Map.entry("expanded", true),
                Map.entry("onlineTicket", ""),
                Map.entry("customerName", "Ana"),
                Map.entry("items", List.of(Map.of("quantity", 1, "name", "Café", "totalPrice", new BigDecimal("2.80"), "customizations", List.of()))));
        context.setVariable("orders", List.of(order));
        context.setVariable("takeaway", takeaway);

        String html = engine.process("templates/fastfood/fragments/counter.html", Set.of("table-session-cart"), context);

        Matcher actionsBlock = Pattern.compile("(?s)<div class=\"counter-order-actions\">(.*?)</div>").matcher(html);
        assertThat(actionsBlock.find()).as("actions block for takeaway=%s paymentStatus=%s", takeaway, paymentStatus).isTrue();
        List<String> buttons = Pattern.compile("(?s)<button\\b[^>]*>.*?</button>")
                .matcher(actionsBlock.group(1)).results().map(r -> r.group()).toList();
        assertThat(buttons).isNotEmpty();
        assertThat(buttons.getLast()).as("last button for takeaway=%s paymentStatus=%s", takeaway, paymentStatus)
                .contains("counter-order-pay", expectedPayClass);
        if (takeaway) {
            assertThat(actionsBlock.group(1)).doesNotContain("openReassignModal");
        } else {
            assertThat(actionsBlock.group(1)).contains("openReassignModal");
        }
    }

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setOrder(1);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setTemplateMode("HTML");
        StringTemplateResolver wrapper = new StringTemplateResolver();
        wrapper.setOrder(0);
        wrapper.setResolvablePatterns(Set.of("*data-test-wrapper*"));
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolvers(Set.of(wrapper, resolver));
        engine.setTemplateEngineMessageSource(messages);
        engine.addDialect(new PriceDialect());
        return engine;
    }
}
