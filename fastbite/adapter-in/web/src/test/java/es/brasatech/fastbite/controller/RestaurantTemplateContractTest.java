package es.brasatech.fastbite.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Style stays out of the restaurant's templates: no new inline {@code style=} and no new hard-coded colour.
 * The allow-list holds what is still there today, page by page; it only shrinks as each page moves to the
 * theme contract, and reaches zero everywhere at the end. {@code fragments/environment.html} (the sandbox banner)
 * is not guarded: it keeps its own look on every page.
 */
class RestaurantTemplateContractTest {

    /** Inline style attributes, plain or Thymeleaf. */
    static final Pattern INLINE_STYLE = Pattern.compile("(?<![\\w-])(?:th:)?style\\s*=");
    /** Colour literals: six- or eight-digit hex, short hex in a CSS value, or an rgb()/hsl() function. */
    static final Pattern COLOUR = Pattern.compile(
            "(?<![&\\w])#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6})\\b"
                    + "|(?:[:(,]\\s*|\\b(?:solid|dashed|dotted)\\s+)#[0-9a-fA-F]{3,4}\\b"
                    + "|\\b(?:rgba?|hsla?)\\(");

    /** Template → {inline styles, colour literals} still allowed. Every other guarded template allows none. */
    private static final Map<String, int[]> ALLOWED = Map.of();

    private static final String[] GUARDED = {
            "menu.html", "fragments/menu.html", "allergens.html", "paymentSelection.html", "confirmation.html",
            "orderCancelled.html", "counter.html", "fragments/counter.html", "dashboard.html", "tickets.html",
            "dayReport.html", "tableQrCodes.html", "backOffice.html", "fragments/backOffice.html",
            "fragments/navbar.html", "fragments/cashCheck.html", "fragments/invoice-fields.html", "fragments/guest.html"};

    /** The guest pages are off Bootstrap and Font Awesome: their own stylesheet and the inline icon sprite. */
    private static final String[] GUEST_PAGES = {
            "menu.html", "fragments/menu.html", "allergens.html", "paymentSelection.html", "confirmation.html",
            "orderCancelled.html", "fragments/guest.html"};
    /** Their scripts change state through classes and attributes only: no inline styles, no colours. */
    private static final String[] GUEST_SCRIPTS = {
            "menu.js", "allergen-filter.js", "guest-ui.js", "payment-selection.js", "order-progress.js",
            "guest-ticket.js", "live-updates.js"};
    /** The latter two are shared with Bootstrap pages, but neither path sets inline styles or names a theme. */
    private static final String[] COUNTER_SCRIPTS = {"counter.js", "cash-check.js", "kitchen-audio.js"};
    private static final String[] KITCHEN_SCRIPTS = {"dashboard.js", "kitchen-audio.js", "live-updates.js", "guest-ui.js"};
    private static final String[] TICKETS_SCRIPTS = {"guest-ui.js", "tickets.js", "cash-check.js"};
    private static final String[] BACKOFFICE_SCRIPTS = {"guest-ui.js", "backOffice.js"};
    static final Pattern BOOTSTRAP_OR_FONT_AWESOME = Pattern.compile(
            "/webjars/|bootstrap|\\bfa[srb]? fa-|data-bs-toggle|data-bs-target|class=\"[^\"]*(?<![\\w-])(?:row|col-[a-z0-9-]+|d-flex|d-none|text-(?:primary|success|danger|warning|info|muted|dark)|bg-(?:primary|light|white|success)|btn|modal|alert)(?![\\w-])");
    /**
     * Staff pages still on Bootstrap and the legacy navigation ({@code navbar :: navbar}), outside the native checks.
     * Reaches empty once backOffice.html completes item 4c-4.
     */
    static final List<String> STAFF_PAGES_WAITING = List.of();
    /** Every fragment the back office renders. All are native. */
    private static final Set<String> BACKOFFICE_FRAGMENTS = Set.of(
            "guest :: icons", "environment :: banner", "navbar :: staff", "guest-privacy-notice :: panel");
    /** Every fragment the counter renders, on its page or through its fragment endpoints. All are native. */
    private static final Set<String> COUNTER_FRAGMENTS = Set.of(
            "guest :: icons", "environment :: banner", "navbar :: staff", "counter :: tables",
            "invoice-fields :: counterFields", "cashCheck :: counterModal", "cashCheck :: denominations");
    /** Every fragment the kitchen renders. All are native. */
    private static final Set<String> KITCHEN_FRAGMENTS = Set.of(
            "guest :: icons", "environment :: banner", "navbar :: staff");
    /** Every fragment the tickets page renders. All are native. */
    private static final Set<String> TICKETS_FRAGMENTS = Set.of(
            "guest :: icons", "environment :: banner", "navbar :: staff",
            "invoice-fields :: counterFields", "cashCheck :: counterModal", "cashCheck :: denominations");
    private static final Pattern SCRIPT_LINK = Pattern.compile("@\\{/js/([\\w.-]+\\.js)}");
    private static final Pattern FRAGMENT_REFERENCE = Pattern.compile("~\\{fastfood/fragments/([\\w-]+) :: ([\\w-]+)");
    /**
     * Loaded by the counter but shared with the Bootstrap pages and the owner workspace: its fallback dialog (still
     * used to cancel a table order) builds inline styles. It joins COUNTER_SCRIPTS when that dialog moves.
     */
    static final List<String> COUNTER_SCRIPTS_WAITING = List.of("confirm-dialog.js");
    private static final Pattern SCRIPT_STYLE = Pattern.compile("\\.style\\.|\\.cssText|setProperty\\(");
    private static final Pattern SCRIPT_THEME = Pattern.compile(
            "(?i)data-theme|(?<![\\w-])(?:fastfood|cafe|modern|test-contrast)(?![\\w-])");

    @Test
    void restaurantTemplatesGainNoInlineStyleOrHardCodedColour() throws IOException {
        Map<String, String> found = new TreeMap<>();
        Map<String, String> allowed = new TreeMap<>();
        for (String template : GUARDED) {
            String html = new ClassPathResource("templates/fastfood/" + template).getContentAsString(StandardCharsets.UTF_8);
            int[] limit = ALLOWED.getOrDefault(template, new int[]{0, 0});
            found.put(template, count(INLINE_STYLE, html) + " inline styles, " + count(COLOUR, html) + " colours");
            allowed.put(template, limit[0] + " inline styles, " + limit[1] + " colours");
        }
        // Exact on purpose: removing one means lowering its number in ALLOWED, so the list never hides a new one
        assertThat(found).isEqualTo(allowed);
    }

    @Test
    void guestPagesUseNeitherBootstrapNorFontAwesome() throws IOException {
        for (String template : GUEST_PAGES) {
            String html = new ClassPathResource("templates/fastfood/" + template).getContentAsString(StandardCharsets.UTF_8);
            // The toast is shared with the counter, which keeps Bootstrap until it moves: its close button may say so
            html = html.replace("data-bs-dismiss=\"toast\"", "");
            assertThat(BOOTSTRAP_OR_FONT_AWESOME.matcher(html).results().map(MatchResult::group).toList())
                    .as(template).isEmpty();
        }
    }

    @Test
    void counterAndItsNativeSharedFragmentsUseTheRestaurantContract() throws IOException {
        for (String template : new String[]{"counter.html", "fragments/counter.html"}) {
            assertNativeCounter(template, readTemplate(template));
        }
        // The legacy parts of these shared files remain for STAFF_PAGES_WAITING.
        assertNativeCounter("staff navbar", fragment("fragments/navbar.html", "<header th:fragment=\"staff(activeTab)\"", "</header>"));
        assertNativeCounter("counter cash check", fragment("fragments/cashCheck.html", "<dialog th:fragment=\"counterModal(day)\"", "</dialog>"));
        assertNativeCounter("cash check notes and coins", fragment("fragments/cashCheck.html", "<details th:fragment=\"denominations\"", "</details>"));
        assertNativeCounter("counter invoice fields", fragment("fragments/invoice-fields.html", "<div th:fragment=\"counterFields(prefix)\"", "</body>"));
    }

    @Test
    void theCounterRendersOnlyItsNativeFragments() throws IOException {
        Set<String> used = new TreeSet<>();
        for (String html : List.of(readTemplate("counter.html"), readTemplate("fragments/counter.html"),
                fragment("fragments/cashCheck.html", "<dialog th:fragment=\"counterModal(day)\"", "</dialog>"))) {
            FRAGMENT_REFERENCE.matcher(html).results().forEach(ref -> used.add(ref.group(1) + " :: " + ref.group(2)));
        }
        assertThat(used).as("fragments the counter renders").isSubsetOf(COUNTER_FRAGMENTS).contains("navbar :: staff");
        assertThat(readTemplate("counter.html")).as("the counter loads no Bootstrap page script and no kitchen.css")
                .doesNotContain("/webjars/").doesNotContain("navbar :: navbar").doesNotContain("kitchen.css");
        // Every script it loads is checked, as a counter or guest script, or is listed as waiting
        Set<String> checked = new TreeSet<>(List.of(COUNTER_SCRIPTS));
        checked.addAll(List.of(GUEST_SCRIPTS));
        checked.addAll(COUNTER_SCRIPTS_WAITING);
        assertThat(SCRIPT_LINK.matcher(readTemplate("counter.html")).results().map(link -> link.group(1)).toList())
                .as("scripts the counter loads").isNotEmpty().allMatch(checked::contains);
    }

    @Test
    void kitchenUsesTheRestaurantContract() throws IOException {
        String html = readTemplate("dashboard.html");
        assertNativeCounter("dashboard.html", html);
        assertThat(html).as("dashboard has no inline onclick handlers").doesNotContain("onclick=");
        assertThat(html).as("dashboard loads kitchen.css").contains("/css/kitchen.css");
        Set<String> used = new TreeSet<>();
        FRAGMENT_REFERENCE.matcher(html).results().forEach(ref -> used.add(ref.group(1) + " :: " + ref.group(2)));
        assertThat(used).as("fragments the kitchen renders").isSubsetOf(KITCHEN_FRAGMENTS).contains("navbar :: staff");
        Set<String> checked = new TreeSet<>(List.of(KITCHEN_SCRIPTS));
        assertThat(SCRIPT_LINK.matcher(html).results().map(link -> link.group(1)).toList())
                .as("scripts the kitchen loads").isNotEmpty().allMatch(checked::contains);
    }

    @Test
    void kitchenScriptsChangeOnlyStateAndNeverNameATheme() throws IOException {
        String js = new ClassPathResource("static/js/dashboard.js").getContentAsString(StandardCharsets.UTF_8);
        assertThat(count(SCRIPT_STYLE, js)).as("inline styles set by dashboard.js").isZero();
        assertThat(count(COLOUR, js)).as("colours in dashboard.js").isZero();
        assertThat(SCRIPT_THEME.matcher(js).results().map(MatchResult::group).toList())
                .as("theme-specific logic in dashboard.js").isEmpty();
        assertThat(js).as("Bootstrap modal usage in dashboard.js").doesNotContain("bootstrap.Modal");
    }

    @Test
    void dashboardJsEscapesInterpolatedOrderAndItemFields() throws IOException {
        String js = new ClassPathResource("static/js/dashboard.js").getContentAsString(StandardCharsets.UTF_8);
        Pattern unescapedTemplateField = Pattern.compile(
                "\\$\\{\\s*(?!escapeHtml\\()(?:item\\.name|cust\\.name|order\\.id|order\\.orderNumber|order\\.cancelReason|order\\.customerName|group\\.tableName)\\b");
        Pattern unescapedCustName = Pattern.compile("(?<!escapeHtml\\()\\bcust\\.name\\b");
        List<String> violations = new ArrayList<>();
        unescapedTemplateField.matcher(js).results().forEach(m -> violations.add(m.group()));
        unescapedCustName.matcher(js).results().forEach(m -> violations.add(m.group()));
        assertThat(violations).as("unescaped order/item/customization interpolations in dashboard.js").isEmpty();
    }

    @Test
    void kitchenTextKeysExistInEveryMessageBundle() throws IOException {
        List<String> keys = List.of(
                "dashboard.modal.status.prompt",
                "dashboard.js.sec.ago",
                "dashboard.js.weeks.ago",
                "dashboard.js.months.ago",
                "dashboard.js.duration.min",
                "dashboard.js.na",
                "dashboard.js.table.prefix",
                "dashboard.js.toast.previous",
                "dashboard.js.toast.batch.next",
                "dashboard.js.toast.batch.previous",
                "dashboard.js.toast.cancelled",
                "dashboard.table.cancelled.at",
                "dashboard.table.cancelled.reason",
                "dashboard.table.completed.at",
                "dashboard.table.duration");
        for (String file : List.of("i18n/messages.properties", "i18n/messages_es.properties", "i18n/messages_pt.properties")) {
            Properties props = new Properties();
            try (var reader = new InputStreamReader(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (String key : keys) {
                assertThat(props.getProperty(key)).as("%s in %s", key, file).isNotBlank();
            }
        }
    }

    @Test
    void ticketsUsesTheRestaurantContract() throws IOException {
        String html = readTemplate("tickets.html");
        assertNativeCounter("tickets.html", html);
        assertThat(html).as("tickets has no inline event handlers and loads tickets.css only")
                .doesNotContain("onclick=", "onchange=", "/css/counter.css", "/css/kitchen.css")
                .contains("/css/tickets.css", "invoice-fields :: counterFields", "cashCheck :: counterModal");
        Set<String> used = new TreeSet<>();
        FRAGMENT_REFERENCE.matcher(html).results().forEach(ref -> used.add(ref.group(1) + " :: " + ref.group(2)));
        assertThat(used).as("fragments the tickets page renders").isSubsetOf(TICKETS_FRAGMENTS).contains("navbar :: staff");
        Set<String> checked = new TreeSet<>(List.of(TICKETS_SCRIPTS));
        assertThat(SCRIPT_LINK.matcher(html).results().map(link -> link.group(1)).toList())
                .as("scripts the tickets page loads").isNotEmpty().allMatch(checked::contains);
    }

    @Test
    void ticketsAndTableQrScriptsChangeOnlyStateAndNeverNameATheme() throws IOException {
        for (String script : List.of("tickets.js", "table-qr.js")) {
            String js = new ClassPathResource("static/js/" + script).getContentAsString(StandardCharsets.UTF_8);
            assertThat(count(SCRIPT_STYLE, js)).as("inline styles set by %s", script).isZero();
            assertThat(count(COLOUR, js)).as("colours in %s", script).isZero();
            assertThat(SCRIPT_THEME.matcher(js).results().map(MatchResult::group).toList())
                    .as("theme-specific logic in %s", script).isEmpty();
            assertThat(js).as("Bootstrap modal usage in %s", script).doesNotContain("bootstrap.Modal");
        }
    }

    @Test
    void tableQrCodesAndDayReportUseNoBootstrapOrFontAwesome() throws IOException {
        String qr = readTemplate("tableQrCodes.html");
        assertNativeCounter("tableQrCodes.html", qr);
        assertThat(qr).as("tableQrCodes uses table-qr.css, icon sprite, locale lang, no inline onclick")
                .doesNotContain("onclick=", "<style>")
                .contains("/css/table-qr.css", "guest :: icons", "th:lang=\"${#locale.language}\"", "/js/table-qr.js");

        String report = readTemplate("dayReport.html");
        assertThat(count(INLINE_STYLE, report)).as("inline styles in dayReport.html").isZero();
        assertThat(count(COLOUR, report)).as("colours in dayReport.html").isZero();
        assertThat(report).as("dayReport stays Bootstrap-free on ticket.css")
                .doesNotContain("/webjars/", "bootstrap", "fa-", "data-bs-", "navbar :: navbar")
                .contains("/css/ticket.css", "th:lang=\"${#locale.language}\"");
    }

    @Test
    void tableQrTextKeysExistInEveryMessageBundle() throws IOException {
        List<String> keys = List.of(
                "tableqr.title",
                "tableqr.heading",
                "tableqr.print",
                "tableqr.instructions",
                "tableqr.local.warning",
                "tableqr.empty");
        for (String file : List.of("i18n/messages.properties", "i18n/messages_es.properties", "i18n/messages_pt.properties")) {
            Properties props = new Properties();
            try (var reader = new InputStreamReader(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (String key : keys) {
                assertThat(props.getProperty(key)).as("%s in %s", key, file).isNotBlank();
            }
        }
    }

    @Test
    void backOfficeSliceOneUsesNativeTopbarAndSelectorDialogs() throws IOException {
        String html = readTemplate("backOffice.html");
        assertThat(html)
                .contains("navbar :: staff", "guest :: icons", "/css/backoffice.css", "/js/guest-ui.js",
                        "class=\"bo-layout\"", "id=\"toastContainer\"")
                .doesNotContain("navbar :: navbar", "list-group-item",
                        "class=\"modal fade\" id=\"productSelectorModal\"",
                        "class=\"modal fade\" id=\"customizationSelectorModal\"",
                        "class=\"modal fade\" id=\"quickCustomizationModal\"");
        for (String id : List.of("productSelectorModal", "customizationSelectorModal", "quickCustomizationModal")) {
            assertThat(html).as("native dialog for %s", id).containsPattern("<dialog[^>]*id=\"" + id + "\"");
        }
    }

    @Test
    void backOfficeSliceOneTextKeysExistInEveryMessageBundle() throws IOException {
        List<String> keys = List.of(
                "backoffice.tables.list.title",
                "backoffice.payment.list.title",
                "backoffice.privacy.list.title",
                "backoffice.maintenance.list.title",
                "backoffice.quick.view.groups",
                "backoffice.quick.view.customizations",
                "backoffice.quick.view.products",
                "backoffice.quick.view.empty",
                "backoffice.action.edit",
                "backoffice.action.translations",
                "backoffice.action.delete",
                "backoffice.action.remove");
        for (String file : List.of("i18n/messages.properties", "i18n/messages_es.properties", "i18n/messages_pt.properties")) {
            Properties props = new Properties();
            try (var reader = new InputStreamReader(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (String key : keys) {
                assertThat(props.getProperty(key)).as("%s in %s", key, file).isNotBlank();
            }
        }
    }

    @Test
    void backOfficeSliceTwoProductsAndImagePickerUseNativeMarkup() throws IOException {
        String productsSection = fragment("backOffice.html", "<section id=\"products-section\"", "</section>");
        assertNativeCounter("backOffice.html#products-section", productsSection);
        assertThat(productsSection)
                .contains("id=\"product-form\" class=\"bo-card\" hidden", "id=\"btn-new-product\"",
                        "id=\"btn-select-product-image\"", "id=\"allergens-missing-banner\"",
                        "class=\"bo-allergen-chip\"");

        String imageDialog = fragment("backOffice.html", "<dialog class=\"dialog dialog-centered dialog-xl\" id=\"imageSelectorModal\"", "</dialog>");
        assertNativeCounter("backOffice.html#imageSelectorModal", imageDialog);
        assertThat(imageDialog)
                .contains("role=\"tablist\"", "role=\"tab\"", "aria-selected=\"true\"",
                        "aria-controls=\"system-images\"", "aria-controls=\"user-images\"",
                        "aria-controls=\"upload-new\"", "role=\"tabpanel\"");

        String productsList = fragment("fragments/backOffice.html", "<!-- Products List -->", "<!-- Customization Option Card -->");
        assertNativeCounter("fragments/backOffice.html::products-list", productsList);
        assertThat(productsList).contains("bo-item-card", "bo-icon-btn", "aria-pressed=");

        String imageGallery = fragment("fragments/backOffice.html", "<!-- Image Gallery -->", "<!-- Quick View -->");
        assertNativeCounter("fragments/backOffice.html::image-gallery", imageGallery);
        assertThat(imageGallery).contains("<button type=\"button\" class=\"bo-image-card\"");
    }

    @Test
    void backOfficeSliceTwoTextKeysExistInEveryMessageBundle() throws IOException {
        List<String> keys = List.of(
                "backoffice.products.action.stock86",
                "backoffice.products.stock86.success",
                "backoffice.products.stock86.error",
                "backoffice.products.placeholder.image",
                "backoffice.modal.image.empty.system",
                "backoffice.modal.image.empty.user",
                "backoffice.modal.image.error.system",
                "backoffice.modal.image.error.user",
                "backoffice.modal.image.selected",
                "backoffice.modal.image.upload.success",
                "backoffice.modal.image.upload.error",
                "backoffice.modal.image.placeholder.folder",
                "backoffice.modal.image.tabs.label");
        for (String file : List.of("i18n/messages.properties", "i18n/messages_es.properties", "i18n/messages_pt.properties")) {
            Properties props = new Properties();
            try (var reader = new InputStreamReader(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (String key : keys) {
                assertThat(props.getProperty(key)).as("%s in %s", key, file).isNotBlank();
            }
        }
    }

    @Test
    void backOfficeSliceThreeTablesPaymentAndDiscountsUseNativeMarkup() throws IOException {
        String tablesSection = fragment("backOffice.html", "<section id=\"tables-section\"", "</section>");
        assertNativeCounter("backOffice.html#tables-section", tablesSection);
        assertThat(tablesSection).contains("id=\"btn-tables-qr\"", "id=\"btn-new-table\"", "openTableQr()");

        String paymentSection = fragment("backOffice.html", "<section id=\"payment-section\"", "</section>");
        assertNativeCounter("backOffice.html#payment-section", paymentSection);
        assertThat(paymentSection).contains("id=\"payment-config-container\"");

        String discountsSection = fragment("backOffice.html", "<section id=\"discounts-section\"", "</section>");
        assertNativeCounter("backOffice.html#discounts-section", discountsSection);
        assertThat(discountsSection).contains("id=\"btn-new-discount\"");

        String tablesList = fragment("fragments/backOffice.html", "<!-- Tables List -->", "<!-- Table Form Fragment -->");
        assertNativeCounter("fragments/backOffice.html::tables-list", tablesList);
        assertThat(tablesList).contains("bo-item-card", "bo-icon-btn", "table.status.", "#strings.toLowerCase(table.status)");

        String tableForm = fragment("fragments/backOffice.html", "<!-- Table Form Fragment -->", "<!-- Payment Section Fragment -->");
        assertNativeCounter("fragments/backOffice.html::table-form", tableForm);
        assertThat(tableForm).contains("bo-card", "table.status.", "#strings.toLowerCase(status)");

        String paymentFrag = fragment("fragments/backOffice.html", "<!-- Payment Section Fragment -->", "<!-- Discounts List -->");
        assertNativeCounter("fragments/backOffice.html::payment-section", paymentFrag);
        assertThat(paymentFrag).contains("bo-payment-grid", "bo-denom-grid", "openDenomImageSelector()", "id=\"paymentConfigForm\"");

        String discountsList = fragment("fragments/backOffice.html", "<!-- Discounts List -->", "<!-- Discount Form -->");
        assertNativeCounter("fragments/backOffice.html::discounts-list", discountsList);
        assertThat(discountsList).contains("bo-item-card", "bo-icon-btn");

        String discountForm = fragment("fragments/backOffice.html", "<!-- Discount Form -->", "</html>");
        assertNativeCounter("fragments/backOffice.html::discount-form", discountForm);
        assertThat(discountForm).contains("bo-card", "id=\"discountFormElement\"");
    }

    @Test
    void backOfficeSliceThreeTextKeysExistInEveryMessageBundle() throws IOException {
        List<String> keys = List.of(
                "backoffice.tables.qr.codes",
                "backoffice.tables.qr.all.title",
                "backoffice.tables.qr.single",
                "backoffice.tables.new",
                "backoffice.tables.edit.title",
                "backoffice.tables.save.success",
                "backoffice.tables.save.error",
                "backoffice.tables.delete.success",
                "backoffice.tables.delete.error",
                "backoffice.payment.action.set.default",
                "backoffice.payment.label.id",
                "backoffice.payment.placeholder.id",
                "backoffice.payment.hint.id",
                "backoffice.payment.denom.type.banknote",
                "backoffice.payment.denom.type.coin",
                "backoffice.payment.button.reset",
                "backoffice.payment.mode.required",
                "backoffice.payment.save.error",
                "backoffice.payment.default.success",
                "backoffice.payment.default.error",
                "backoffice.payment.delete.success",
                "backoffice.payment.delete.error",
                "backoffice.payment.denom.required",
                "backoffice.payment.reset.success",
                "backoffice.discounts.scope.order",
                "backoffice.discounts.scope.table",
                "backoffice.discounts.type.percentage",
                "backoffice.discounts.type.fixed_amount",
                "backoffice.discounts.placeholder.code",
                "backoffice.discounts.badge.accumulative",
                "backoffice.discounts.badge.not.accumulative",
                "backoffice.discounts.badge.counter.active",
                "backoffice.discounts.badge.counter.inactive",
                "backoffice.discounts.save.error",
                "backoffice.discounts.delete.error");
        for (String file : List.of("i18n/messages.properties", "i18n/messages_es.properties", "i18n/messages_pt.properties")) {
            Properties props = new Properties();
            try (var reader = new InputStreamReader(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (String key : keys) {
                assertThat(props.getProperty(key)).as("%s in %s", key, file).isNotBlank();
            }
        }
    }

    @Test
    void backOfficeUsesTheRestaurantContractWithoutBootstrapOrFontAwesome() throws IOException {
        String html = readTemplate("backOffice.html");
        String frag = readTemplate("fragments/backOffice.html");
        assertNativeCounter("backOffice.html", html);
        assertNativeCounter("fragments/backOffice.html", frag);
        assertThat(html).as("backOffice loads backoffice.css and no webjars")
                .contains("/css/backoffice.css")
                .doesNotContain("/webjars/", "bootstrap", "font-awesome");
        assertThat(html).as("maintenance stays ROLE_ADMIN only")
                .contains("id=\"nav-maintenance\"")
                .contains("id=\"maintenance-section\" class=\"section-content\" hidden sec:authorize=\"hasRole('ADMIN')\"");
        Set<String> used = new TreeSet<>();
        FRAGMENT_REFERENCE.matcher(html).results().forEach(ref -> used.add(ref.group(1) + " :: " + ref.group(2)));
        assertThat(used).as("fragments backOffice renders").isSubsetOf(BACKOFFICE_FRAGMENTS).contains("navbar :: staff");
        Set<String> checked = new TreeSet<>(List.of(BACKOFFICE_SCRIPTS));
        checked.addAll(COUNTER_SCRIPTS_WAITING);
        assertThat(SCRIPT_LINK.matcher(html).results().map(link -> link.group(1)).toList())
                .as("scripts backOffice loads").isNotEmpty().allMatch(checked::contains);

        String js = new ClassPathResource("static/js/backOffice.js").getContentAsString(StandardCharsets.UTF_8);
        assertThat(count(SCRIPT_STYLE, js)).as("inline styles set by backOffice.js").isZero();
        assertThat(count(COLOUR, js)).as("colours in backOffice.js").isZero();
        assertThat(SCRIPT_THEME.matcher(js).results().map(MatchResult::group).toList())
                .as("theme-specific logic in backOffice.js").isEmpty();
        assertThat(js).as("Bootstrap or Font Awesome in backOffice.js")
                .doesNotContain("bootstrap.", "fas fa-", "far fa-", "fa-spinner", "fa-spin");
    }

    @Test
    void backOfficeSliceFourTextKeysExistInEveryMessageBundle() throws IOException {
        List<String> keys = List.of(
                "backoffice.groups.placeholder.icon",
                "backoffice.maintenance.title",
                "backoffice.maintenance.ordering.title",
                "backoffice.maintenance.ordering.desc",
                "backoffice.maintenance.ordering.dinein",
                "backoffice.maintenance.ordering.dinein.hint",
                "backoffice.maintenance.ordering.takeaway",
                "backoffice.maintenance.ordering.takeaway.hint",
                "backoffice.maintenance.ordering.takeaway.locked",
                "backoffice.maintenance.kds.title",
                "backoffice.maintenance.kds.desc",
                "backoffice.maintenance.kds.desc.zero",
                "backoffice.maintenance.kds.yellow",
                "backoffice.maintenance.kds.yellow.hint",
                "backoffice.maintenance.kds.red",
                "backoffice.maintenance.kds.red.hint",
                "backoffice.maintenance.danger.title",
                "backoffice.maintenance.danger.desc",
                "backoffice.maintenance.danger.button",
                "backoffice.maintenance.demo.title",
                "backoffice.maintenance.demo.desc",
                "backoffice.maintenance.demo.select",
                "backoffice.maintenance.demo.loading",
                "backoffice.maintenance.demo.button",
                "backoffice.maintenance.demo.restoring",
                "backoffice.maintenance.export.title",
                "backoffice.maintenance.export.desc",
                "backoffice.maintenance.export.button",
                "backoffice.maintenance.restore.title",
                "backoffice.maintenance.restore.desc",
                "backoffice.maintenance.restore.caution",
                "backoffice.maintenance.restore.caution.detail",
                "backoffice.maintenance.restore.label",
                "backoffice.maintenance.restore.button",
                "backoffice.maintenance.restore.restoring",
                "backoffice.maintenance.restore.select.first",
                "backoffice.maintenance.settings.save.success",
                "backoffice.maintenance.settings.save.error",
                "backoffice.maintenance.reset.success",
                "backoffice.maintenance.reset.error",
                "backoffice.maintenance.restore.success",
                "backoffice.maintenance.restore.error",
                "backoffice.maintenance.demo.success",
                "backoffice.maintenance.demo.error");
        for (String file : List.of("i18n/messages.properties", "i18n/messages_es.properties", "i18n/messages_pt.properties")) {
            Properties props = new Properties();
            try (var reader = new InputStreamReader(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (String key : keys) {
                assertThat(props.getProperty(key)).as("%s in %s", key, file).isNotBlank();
            }
        }
    }

    @Test
    void onlyTheStaffPagesWaitingForALaterItemKeepBootstrapOrTheLegacyNavigation() throws IOException {
        assertThat(STAFF_PAGES_WAITING).isEmpty();
        for (String page : List.of("counter.html", "dashboard.html", "tickets.html", "dayReport.html", "tableQrCodes.html",
                "backOffice.html")) {
            String html = readTemplate(page);
            // dayReport.html uses ticket.css's thermal-receipt `.row` layout, not Bootstrap's grid
            String checkHtml = "dayReport.html".equals(page) ? html.replaceAll("class=\"row(?: big)?\"", "") : html;
            boolean legacy = checkHtml.contains("navbar :: navbar") || BOOTSTRAP_OR_FONT_AWESOME.matcher(checkHtml).find();
            if (legacy) assertThat(STAFF_PAGES_WAITING).as("%s still uses Bootstrap or the legacy navigation", page).contains(page);
        }
        // The two navigations name the staff pages alike
        String navbar = readTemplate("fragments/navbar.html");
        for (String key : List.of("staff.nav.menu", "staff.nav.kitchen", "staff.nav.counter", "staff.nav.tickets", "staff.nav.backoffice")) {
            assertThat(navbar.split(Pattern.quote("#{" + key + "}"), -1)).as(key).hasSize(3);
        }
    }

    @Test
    void counterTextKeysExistInEveryMessageBundle() throws IOException {
        List<String> keys = List.of(
                "counter.navbar.toggle",
                "counter.customizer.title",
                "kitchen.audio.on",
                "kitchen.audio.off",
                "kitchen.audio.toggle");
        for (String file : List.of("i18n/messages.properties", "i18n/messages_es.properties", "i18n/messages_pt.properties")) {
            Properties props = new Properties();
            try (var reader = new InputStreamReader(new ClassPathResource(file).getInputStream(), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (String key : keys) {
                assertThat(props.getProperty(key)).as("%s in %s", key, file).isNotBlank();
            }
        }
    }

    @Test
    void onlyColouredCounterDialogHeadersCarryCloseOnDark() throws IOException {
        String counter = readTemplate("counter.html");
        var headers = Pattern.compile("(?s)<div class=\"(counter-dialog-header[^\"]*)\">(.*?)</div>").matcher(counter);
        int found = 0;
        while (headers.find()) {
            found++;
            String classes = headers.group(1);
            String body = headers.group(2);
            if (body.contains("counter-close-on-dark")) {
                assertThat(classes).as("dialog header with counter-close-on-dark")
                        .matches(".*\\bcounter-dialog-header-(?:table|payment|orders)\\b.*");
            }
        }
        assertThat(found).isGreaterThanOrEqualTo(6);
    }

    @Test
    void everyCounterJsDictionaryKeyExistsInCounterHtml() throws IOException {
        String js = new ClassPathResource("static/js/counter.js").getContentAsString(StandardCharsets.UTF_8);
        String counter = readTemplate("counter.html");
        String dict = fragment("counter.html", "<div hidden id=\"dictionary\"", "</div>");
        Set<String> declared = new TreeSet<>();
        Pattern.compile("(?:th:)?data-([a-z0-9-]+)=").matcher(dict).results()
                .forEach(m -> declared.add(toCamelCase(m.group(1))));
        Set<String> missing = new TreeSet<>();
        Pattern.compile("\\bt\\(['\"]([A-Za-z0-9]+)['\"]\\)").matcher(js).results()
                .map(m -> m.group(1))
                .forEach(key -> {
                    if (!declared.contains(key)) missing.add(key);
                });
        assertThat(missing).as("t(...) keys used in counter.js missing from counter.html #dictionary").isEmpty();
        assertThat(counter).contains("id=\"tableModal\"", "id=\"reassignModal\"");
    }

    private static String toCamelCase(String kebab) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < kebab.length(); i++) {
            char c = kebab.charAt(i);
            if (c == '-') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    private static void assertNativeCounter(String label, String html) {
        assertThat(count(INLINE_STYLE, html)).as("inline styles in %s", label).isZero();
        assertThat(count(COLOUR, html)).as("colours in %s", label).isZero();
        assertThat(BOOTSTRAP_OR_FONT_AWESOME.matcher(html).results().map(MatchResult::group).toList())
                .as("Bootstrap or Font Awesome in %s", label).isEmpty();
    }

    private static String readTemplate(String name) throws IOException {
        return new ClassPathResource("templates/fastfood/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private static String fragment(String name, String begin, String end) throws IOException {
        String html = readTemplate(name);
        int start = html.indexOf(begin);
        assertThat(start).as("fragment in %s", name).isGreaterThanOrEqualTo(0);
        int finish = html.indexOf(end, start);
        assertThat(finish).as("fragment end in %s", name).isGreaterThan(start);
        return html.substring(start, finish + end.length());
    }

    @Test
    void guestScriptsChangeOnlyClassesAndAttributes() throws IOException {
        for (String script : GUEST_SCRIPTS) {
            String js = new ClassPathResource("static/js/" + script).getContentAsString(StandardCharsets.UTF_8);
            assertThat(count(SCRIPT_STYLE, js)).as("inline styles set by %s", script).isZero();
            assertThat(count(COLOUR, js)).as("colours in %s", script).isZero();
        }
    }

    @Test
    void counterScriptsChangeOnlyStateAndNeverNameATheme() throws IOException {
        for (String script : COUNTER_SCRIPTS) {
            String js = new ClassPathResource("static/js/" + script).getContentAsString(StandardCharsets.UTF_8);
            assertThat(count(SCRIPT_STYLE, js)).as("inline styles set by %s", script).isZero();
            assertThat(count(COLOUR, js)).as("colours in %s", script).isZero();
            assertThat(SCRIPT_THEME.matcher(js).results().map(MatchResult::group).toList())
                    .as("theme-specific logic in %s", script).isEmpty();
        }
    }

    @Test
    void theGuardSeesInlineStylesAndColoursButNotOrderNumbers() {
        assertThat(count(INLINE_STYLE, "<p style=\"x\"></p><p th:style=\"x\"></p><p data-style=\"x\"></p>")).isEqualTo(2);
        assertThat(count(COLOUR, "color:#fff; border: 1px solid #000; fill=\"#dc3545\" rgba(0,0,0,.1) hsl(0 0% 0%)"))
                .isEqualTo(5);
        assertThat(count(COLOUR, "<span>Order #123</span> &#160; href=\"#cartModal\"")).isZero();
    }

    private static long count(Pattern pattern, String text) {
        return pattern.matcher(text).results().count();
    }
}
