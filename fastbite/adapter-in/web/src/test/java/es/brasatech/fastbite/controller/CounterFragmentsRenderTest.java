package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.domain.group.Group;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    void denominationsRenderForNativeAndLegacyCashCheck() {
        String nativeHtml = engine.process("<div data-test-wrapper th:replace=\"~{templates/fastfood/fragments/cashCheck.html :: denominations(counterNative=true)}\"></div>",
                new Context(Locale.ENGLISH));
        String legacyHtml = engine.process("<div data-test-wrapper th:replace=\"~{templates/fastfood/fragments/cashCheck.html :: denominations}\"></div>",
                new Context(Locale.ENGLISH));

        assertThat(nativeHtml).contains("class=\"cash-check-denominations\"", "data-denomination=\"0.01\"",
                "data-denomination=\"100\"").doesNotContain("mb-3");
        assertThat(legacyHtml).contains("class=\"cash-check-denominations mb-3\"", "data-denomination=\"0.01\"",
                "data-denomination=\"100\"");
        assertThat(nativeHtml.split("data-denomination=", -1)).hasSize(14);
        assertThat(legacyHtml.split("data-denomination=", -1)).hasSize(14);
    }

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setOrder(1);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setTemplateMode("HTML");
        StringTemplateResolver wrapper = new StringTemplateResolver();
        wrapper.setOrder(0);
        wrapper.setResolvablePatterns(Set.of("*data-test-wrapper*"));
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolvers(Set.of(wrapper, resolver));
        return engine;
    }
}
