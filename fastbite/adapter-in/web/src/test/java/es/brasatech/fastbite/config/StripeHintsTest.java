package es.brasatech.fastbite.config;

import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Event;
import com.stripe.model.StripeError;
import com.stripe.model.checkout.Session;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeHint;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

class StripeHintsTest {

    private final RuntimeHints hints = new RuntimeHints();

    StripeHintsTest() {
        new StripeHints().registerHints(hints, getClass().getClassLoader());
    }

    @Test
    void coreStripeTypesAreRegisteredForReflection() {
        Class<?>[] types = new Class<?>[]{
                StripeError.class,
                SessionCreateParams.class,
                AccountLinkCreateParams.class,
                Account.class,
                Session.class,
                Event.class
        };

        for (Class<?> type : types) {
            assertThat(RuntimeHintsPredicates.reflection().onType(type)).accepts(hints);
            TypeHint typeHint = hints.reflection().getTypeHint(type);
            assertThat(typeHint).isNotNull();
            assertThat(typeHint.getMemberCategories()).contains(
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.ACCESS_DECLARED_FIELDS,
                    MemberCategory.UNSAFE_ALLOCATED
            );
        }
    }
}
