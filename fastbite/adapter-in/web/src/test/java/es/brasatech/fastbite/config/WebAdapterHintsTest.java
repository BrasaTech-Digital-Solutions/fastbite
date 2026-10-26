package es.brasatech.fastbite.config;

import es.brasatech.fastbite.domain.fiscal.DayReport;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** What templates call through reflection must be registered, or the native image fails where the JVM works. */
class WebAdapterHintsTest {

    /** A method called on a value in an expression, such as {@code lines().isEmpty()} or {@code errors.containsKey('x')}. */
    private static final Pattern COLLECTION_CALL = Pattern.compile(
            "([#\\w.()\\[\\]']*)\\.(isEmpty|size|contains|containsKey|containsValue|containsAll|indexOf)\\(");
    /** Where expressions are: th:* attributes and inlined [[...]] / [(...)] text. */
    private static final Pattern EXPRESSION = Pattern.compile("th:[\\w-]+=\"([^\"]*)\"|\\[\\[(.*?)]]|\\[\\((.*?)\\)]");

    final RuntimeHints hints = new RuntimeHints();

    WebAdapterHintsTest() {
        new WebAdapterHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void everyLanguageOfTheMessagesIsInTheNativeImage() {
        for (String file : new String[]{"i18n/messages.properties", "i18n/messages_es.properties", "i18n/messages_pt.properties"}) {
            org.assertj.core.api.Assertions.assertThat(
                    org.springframework.aot.hint.predicate.RuntimeHintsPredicates.resource().forResource(file))
                    .as(file).accepts(hints);
        }
    }

    /**
     * Lists, sets, maps and strings are often JDK classes the native image cannot call through
     * reflection (List.of, toList(), EnumSet, reversed views). Registering those classes is no way out:
     * it also exposes their constructors to Jackson, which then fails on other types. Templates ask
     * them through Thymeleaf's utilities instead (#lists, #sets, #maps, #strings), which call them directly.
     */
    @Test
    void templatesAskCollectionsAndStringsThroughThymeleafsUtilities() throws Exception {
        List<String> found = new ArrayList<>();
        for (Resource template : new PathMatchingResourcePatternResolver().getResources("classpath*:templates/**/*.html")) {
            String[] lines = template.getContentAsString(StandardCharsets.UTF_8).split("\n");
            for (int i = 0; i < lines.length; i++) {
                var expressions = EXPRESSION.matcher(lines[i]);
                while (expressions.find()) {
                    String expression = expressions.group(1) != null ? expressions.group(1)
                            : expressions.group(2) != null ? expressions.group(2) : expressions.group(3);
                    var call = COLLECTION_CALL.matcher(expression);
                    while (call.find()) {
                        if (!call.group(1).startsWith("#")) {
                            found.add(template.getFilename() + ":" + (i + 1) + "  " + call.group());
                        }
                    }
                }
            }
        }
        assertThat(found).as("use #lists.isEmpty(x), #sets.contains(x, y), #maps.containsKey(x, k), #strings.isEmpty(x)")
                .isEmpty();
    }

    @Test
    void theEndOfDayReportCanBeRead() {
        for (Class<?> type : new Class<?>[]{DayReport.class, DayReport.MethodTotal.class, DayReport.SeriesRange.class,
                DayReport.Cancelled.class, es.brasatech.fastbite.controller.InternalJobsController.JobRunSummary.class}) {
            for (var method : type.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                    assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(method))
                            .as(type.getSimpleName() + "." + method.getName()).accepts(hints);
                }
            }
        }
    }
}
