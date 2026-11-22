package es.brasatech.fastbite.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private static final Map<String, int[]> ALLOWED = Map.of(
            "dashboard.html", new int[]{8, 0},
            "tickets.html", new int[]{2, 0},
            "tableQrCodes.html", new int[]{0, 1});

    private static final String[] GUARDED = {
            "menu.html", "fragments/menu.html", "allergens.html", "paymentSelection.html", "confirmation.html",
            "orderCancelled.html", "counter.html", "fragments/counter.html", "dashboard.html", "tickets.html",
            "dayReport.html", "tableQrCodes.html", "fragments/navbar.html", "fragments/cashCheck.html",
            "fragments/invoice-fields.html", "fragments/guest.html"};

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
    static final Pattern BOOTSTRAP_OR_FONT_AWESOME = Pattern.compile(
            "/webjars/|bootstrap|\\bfa[srb]? fa-|data-bs-toggle|data-bs-target|class=\"[^\"]*(?<![\\w-])(?:row|col-[a-z0-9-]+|d-flex|d-none|text-(?:primary|success|danger|warning|info|muted|dark)|bg-(?:primary|light|white|success)|btn|modal|alert)(?![\\w-])");
    /**
     * Staff pages still on Bootstrap and the legacy navigation ({@code navbar :: navbar}), outside the native checks
     * until their own item: the kitchen, tickets, day report and table QR codes in item 4, the back office after it.
     */
    static final List<String> STAFF_PAGES_WAITING = List.of(
            "dashboard.html", "tickets.html", "dayReport.html", "tableQrCodes.html", "backOffice.html");
    /** Every fragment the counter renders, on its page or through its fragment endpoints. All are native. */
    private static final Set<String> COUNTER_FRAGMENTS = Set.of(
            "guest :: icons", "environment :: banner", "navbar :: counter", "counter :: tables",
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
        assertNativeCounter("counter navbar", fragment("fragments/navbar.html", "<header th:fragment=\"counter\"", "</header>"));
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
        assertThat(used).as("fragments the counter renders").isSubsetOf(COUNTER_FRAGMENTS).contains("navbar :: counter");
        assertThat(readTemplate("counter.html")).as("the counter loads no Bootstrap page script")
                .doesNotContain("/webjars/").doesNotContain("navbar :: navbar");
        // Every script it loads is checked, as a counter or guest script, or is listed as waiting
        Set<String> checked = new TreeSet<>(List.of(COUNTER_SCRIPTS));
        checked.addAll(List.of(GUEST_SCRIPTS));
        checked.addAll(COUNTER_SCRIPTS_WAITING);
        assertThat(SCRIPT_LINK.matcher(readTemplate("counter.html")).results().map(link -> link.group(1)).toList())
                .as("scripts the counter loads").isNotEmpty().allMatch(checked::contains);
    }

    @Test
    void onlyTheStaffPagesWaitingForALaterItemKeepBootstrapOrTheLegacyNavigation() throws IOException {
        assertThat(STAFF_PAGES_WAITING).doesNotContain("counter.html");
        for (String page : List.of("counter.html", "dashboard.html", "tickets.html", "dayReport.html", "tableQrCodes.html",
                "backOffice.html")) {
            String html = readTemplate(page);
            boolean legacy = html.contains("navbar :: navbar") || BOOTSTRAP_OR_FONT_AWESOME.matcher(html).find();
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
