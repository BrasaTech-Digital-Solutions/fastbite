package es.brasatech.fastbite;

import org.springframework.boot.SpringBootConfiguration;
import es.brasatech.fastbite.config.PriceDialect;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
// The guest templates format prices with #prices
@Import(PriceDialect.class)
public class TestConfig {
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }
}
