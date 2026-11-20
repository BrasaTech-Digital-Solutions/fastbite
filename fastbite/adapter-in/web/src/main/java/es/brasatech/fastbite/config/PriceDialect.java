package es.brasatech.fastbite.config;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

import java.util.Set;

/** Gives templates {@code #prices}: {@code ${#prices.format(product.price)}} prints "1,80 €" on a Spanish page. */
@Component
public class PriceDialect extends AbstractDialect implements IExpressionObjectDialect {

    static final String NAME = "prices";

    public PriceDialect() {
        super("FastBite prices");
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new IExpressionObjectFactory() {
            @Override
            public Set<String> getAllExpressionObjectNames() {
                return Set.of(NAME);
            }

            @Override
            public Object buildObject(IExpressionContext context, String expressionObjectName) {
                return NAME.equals(expressionObjectName) ? new Prices(context.getLocale()) : null;
            }

            @Override
            public boolean isCacheable(String expressionObjectName) {
                return true;
            }
        };
    }
}
