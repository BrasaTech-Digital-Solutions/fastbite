package es.brasatech.fastbite.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes = es.brasatech.fastbite.application.tenant.OwnerWorkspaceService.class), basePackages = {
    "es.brasatech.fastbite.jpa.tenant",
    "es.brasatech.fastbite.jpa.user",
    "es.brasatech.fastbite.jpa.fiscal",
    "es.brasatech.fastbite.jpa.legal",
    "es.brasatech.fastbite.jpa.verifactu",
    "es.brasatech.fastbite.application.tenant"
})
public class TestConfig {

    @Bean
    public es.brasatech.fastbite.application.mail.OwnerSetupMailPort ownerSetupMailPort() {
        return org.mockito.Mockito.mock(es.brasatech.fastbite.application.mail.OwnerSetupMailPort.class);
    }

    @Bean
    public es.brasatech.fastbite.application.tenant.SubscriptionGateway subscriptionGateway() {
        return org.mockito.Mockito.mock(es.brasatech.fastbite.application.tenant.SubscriptionGateway.class);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public es.brasatech.fastbite.application.platform.PlatformEnvironment platformEnvironment() {
        return new es.brasatech.fastbite.application.platform.PlatformEnvironment("sandbox");
    }

    @Bean
    public es.brasatech.fastbite.application.platform.PlatformAdmins platformAdmins() {
        return new es.brasatech.fastbite.application.platform.PlatformAdmins("");
    }
}
