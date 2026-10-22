package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.tenant.TenantProvisionerPort;
import es.brasatech.fastbite.config.DemoDataInitializer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DemoInitializerProfileTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityDataInitializer.class, DemoDataInitializer.class)
            .withPropertyValues("image.upload.directory=unused")
            .withBean(UserService.class, () -> mock(UserService.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
            .withBean(TenantLocationService.class, () -> mock(TenantLocationService.class))
            .withBean(TenantBackupRestorePort.class, () -> mock(TenantBackupRestorePort.class))
            .withBean(TenantProvisionerPort.class, () -> mock(TenantProvisionerPort.class))
            .withBean(EntityManager.class, () -> mock(EntityManager.class));

    @Test void defaultAndProductionProfilesNeverRegisterDemoInitializers() {
        for (String profiles : new String[]{"jpa", "jpa,production"}) {
            runner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
                assertThat(context).doesNotHaveBean(SecurityDataInitializer.class);
                assertThat(context).doesNotHaveBean(DemoDataInitializer.class);
            });
        }
    }

    @Test void localAndDemoProfilesOptInToBothInitializers() {
        for (String profiles : new String[]{"jpa,local", "jpa,demo"}) {
            runner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
                assertThat(context).hasSingleBean(SecurityDataInitializer.class);
                assertThat(context).hasSingleBean(DemoDataInitializer.class);
            });
        }
    }
}
