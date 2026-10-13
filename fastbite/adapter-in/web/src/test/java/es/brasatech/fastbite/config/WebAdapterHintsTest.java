package es.brasatech.fastbite.config;

import es.brasatech.fastbite.domain.allergen.Allergen;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** What templates call through reflection must be registered, or the native image fails where the JVM works. */
class WebAdapterHintsTest {

    final RuntimeHints hints = new RuntimeHints();

    WebAdapterHintsTest() {
        new WebAdapterHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void templatesCanAskAProductsAllergens() throws Exception {
        Set<Allergen> few = EnumSet.of(Allergen.GLUTEN);
        for (String method : new String[]{"isEmpty", "size", "iterator"}) {
            assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(few.getClass().getMethod(method)))
                    .as(few.getClass().getName() + "." + method).accepts(hints);
        }
        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(few.getClass().getMethod("contains", Object.class)))
                .accepts(hints);
    }
}
