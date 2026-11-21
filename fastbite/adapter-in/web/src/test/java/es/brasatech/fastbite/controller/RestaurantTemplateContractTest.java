package es.brasatech.fastbite.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
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
    private static final Pattern INLINE_STYLE = Pattern.compile("(?<![\\w-])(?:th:)?style\\s*=");
    /** Colour literals: six- or eight-digit hex, short hex in a CSS value, or an rgb()/hsl() function. */
    private static final Pattern COLOUR = Pattern.compile(
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
    private static final Pattern BOOTSTRAP_OR_FONT_AWESOME = Pattern.compile(
            "/webjars/|bootstrap|\\bfa[srb]? fa-|data-bs-toggle|data-bs-target|class=\"[^\"]*(?<![\\w-])(?:row|col-[a-z0-9-]+|d-flex|d-none|text-(?:primary|success|danger|warning|info|muted|dark)|bg-(?:primary|light|white|success)|btn|modal|alert)(?![\\w-])");
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
        // The legacy parts remain for dashboard, dayReport, tickets, tableQrCodes and backOffice until item 4.
        assertNativeCounter("counter navbar", fragment("fragments/navbar.html", "<header th:fragment=\"counter\"", "</header>"));
        assertNativeCounter("counter cash check", fragment("fragments/cashCheck.html", "<dialog th:fragment=\"counterModal(day)\"", "</dialog>"));
        assertNativeCounter("counter invoice fields", fragment("fragments/invoice-fields.html", "<div th:fragment=\"counterFields(prefix)\"", "</body>"));
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
