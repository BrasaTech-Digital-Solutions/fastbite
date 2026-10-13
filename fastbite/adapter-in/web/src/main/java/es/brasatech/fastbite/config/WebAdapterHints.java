package es.brasatech.fastbite.config;

import es.brasatech.fastbite.domain.I18nField;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.customization.CustomizationOptionDto;
import es.brasatech.fastbite.domain.group.Group;
import es.brasatech.fastbite.domain.order.CartItem;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.payment.PaymentConfig;
import es.brasatech.fastbite.domain.product.ProductCustomizer;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.domain.table.TableStatus;
import es.brasatech.fastbite.domain.user.Customer;
import es.brasatech.fastbite.domain.user.UserDto;
import es.brasatech.fastbite.dto.counter.CounterOrderRequest;
import es.brasatech.fastbite.dto.menu.*;
import es.brasatech.fastbite.dto.office.BackOfficeDto;
import es.brasatech.fastbite.dto.order.OrderCancelReason;
import es.brasatech.fastbite.dto.order.OrderStatusChange;
import org.springframework.aot.hint.*;

public class WebAdapterHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.reflection().registerType(ThemeCatalog.Theme.class, MemberCategory.values());
        // Allergens are EnumSets, whose JDK classes are private: the menu, counter and allergen chart
        // call isEmpty() and contains() on them through reflection
        for (String enumSet : new String[]{"java.util.RegularEnumSet", "java.util.JumboEnumSet"}) {
            hints.reflection().registerType(TypeReference.of(enumSet), MemberCategory.INVOKE_PUBLIC_METHODS);
        }
        hints.resources().registerPattern("themes/**");
        hints.reflection().registerType(es.brasatech.fastbite.domain.tenant.SetupProgress.class, org.springframework.aot.hint.MemberCategory.values());
        // Read by the business details page and the VAT lines of guest carts
        hints.reflection().registerType(es.brasatech.fastbite.domain.fiscal.FiscalProfile.class, MemberCategory.values());
        hints.reflection().registerType(es.brasatech.fastbite.domain.fiscal.SpanishTaxId.Kind.class, MemberCategory.values());
        hints.reflection().registerType(es.brasatech.fastbite.domain.fiscal.VatBreakdown.class, MemberCategory.values());
        hints.reflection().registerType(es.brasatech.fastbite.domain.fiscal.VatBreakdown.Line.class, MemberCategory.values());
        // Tickets: read by the ticket templates, and stored as JSON lines by the JPA adapter
        for (Class<?> type : new Class<?>[]{es.brasatech.fastbite.domain.fiscal.FiscalTicket.class,
                es.brasatech.fastbite.domain.fiscal.FiscalTicket.Issuer.class,
                es.brasatech.fastbite.domain.fiscal.FiscalTicket.Correction.class,
                es.brasatech.fastbite.domain.fiscal.TicketLine.class,
                es.brasatech.fastbite.domain.fiscal.TicketLine.Option.class,
                es.brasatech.fastbite.domain.fiscal.TicketRecipient.class,
                es.brasatech.fastbite.domain.fiscal.TicketKind.class,
                es.brasatech.fastbite.domain.fiscal.PaymentMethod.class,
                es.brasatech.fastbite.domain.allergen.Allergen.class,
                es.brasatech.fastbite.controller.AllergenChartController.Section.class,
                es.brasatech.fastbite.controller.AllergenChartController.OptionRow.class,
                es.brasatech.fastbite.controller.TicketController.SettleRequest.class,
                es.brasatech.fastbite.controller.TicketController.RefundRequest.class,
                es.brasatech.fastbite.controller.TicketController.CancelRequest.class,
                es.brasatech.fastbite.controller.EnvironmentAdvice.PlatformView.class,
                es.brasatech.fastbite.domain.fiscal.TicketCancellation.class,
                es.brasatech.fastbite.dto.counter.InvoiceDetails.class,
                es.brasatech.fastbite.domain.verifactu.VerifactuRecord.class,
                es.brasatech.fastbite.domain.verifactu.VerifactuRecord.Link.class,
                es.brasatech.fastbite.domain.verifactu.VerifactuRecord.Amendment.class,
                es.brasatech.fastbite.domain.verifactu.VerifactuRecord.Cancellation.class,
                es.brasatech.fastbite.domain.verifactu.VerifactuRecord.Submission.class,
                es.brasatech.fastbite.domain.verifactu.VerifactuStatus.class,
                es.brasatech.fastbite.application.verifactu.VerifactuService.Summary.class,
                es.brasatech.fastbite.application.verifactu.VerifactuSystem.class,
                es.brasatech.fastbite.application.verifactu.VerifactuDeclaration.class}) {
            hints.reflection().registerType(type, MemberCategory.values());
        }
        hints.reflection().registerType(es.brasatech.fastbite.application.tenant.OwnerWorkspaceService.Summary.class, org.springframework.aot.hint.MemberCategory.values());
        hints.reflection().registerType(es.brasatech.fastbite.application.tenant.TenantBackupRestorePort.BackupPreview.class, org.springframework.aot.hint.MemberCategory.values());
        hints.reflection().registerType(es.brasatech.fastbite.application.tenant.TenantBackupRestorePort.DemoTemplateDescriptor.class, MemberCategory.values());
        hints.reflection().registerType(es.brasatech.fastbite.domain.tenant.BillingAccount.class, MemberCategory.values());
        hints.reflection().registerType(es.brasatech.fastbite.domain.tenant.SubscriptionPlan.class, MemberCategory.values());
        hints.reflection().registerType(es.brasatech.fastbite.domain.tenant.PlatformBillingSettings.class, MemberCategory.values());
        // Include directory entries too, so native resource discovery can traverse each location.
        hints.resources().registerPattern("db/migration/restaurant/**");
        hints.resources().registerPattern("db/migration/platform/**");
        hints.resources().registerPattern("static/**");
        hints.resources().registerPattern("templates/**");
        hints.resources().registerPattern("schema.sql");
        hints.resources().registerPattern("*_demo.zip");
        hints.resources().registerPattern("demo-templates.list");
        hints.reflection().registerType(TypeReference.of("es.brasatech.fastbite.jpa.tenant.DynamicDemoTemplateRegistry$TemplateManifest"), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of("es.brasatech.fastbite.jpa.tenant.DynamicDemoTemplateRegistry$TemplateManifest$LocalizedText"), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of("es.brasatech.fastbite.storage.CloudFileStorageService"), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of("es.brasatech.fastbite.storage.DiskFileStorageService"), MemberCategory.values());
        hints.resources().registerPattern("*.zip");
        hints.resources().registerResourceBundle("i18n/messages");

        // Serialization hints for session-stored objects
        hints.serialization().registerType(java.math.BigDecimal.class);
        hints.serialization().registerType(java.math.BigInteger.class);
        hints.serialization().registerType(java.util.ArrayList.class);
        hints.serialization().registerType(java.util.HashMap.class);
        hints.serialization().registerType(java.util.HashSet.class);
        hints.serialization().registerType(TypeReference.of("java.util.Collections$UnmodifiableSet"));
        hints.serialization().registerType(TypeReference.of("java.util.Collections$UnmodifiableRandomAccessList"));
        hints.serialization().registerType(TypeReference.of("java.util.Collections$UnmodifiableList"));
        hints.serialization().registerType(TypeReference.of(java.util.Collections.emptyList().getClass()));
        hints.serialization().registerType(CartItem.class);
        hints.serialization().registerType(ProductCustomizer.class);
        
        // Spring Security serialization
        hints.serialization().registerType(TypeReference.of("org.springframework.security.core.context.SecurityContextImpl"));
        hints.serialization().registerType(TypeReference.of("org.springframework.security.authentication.UsernamePasswordAuthenticationToken"));
        hints.serialization().registerType(TypeReference.of("org.springframework.security.core.userdetails.User"));
        hints.serialization().registerType(es.brasatech.fastbite.security.TenantUser.class);
        hints.serialization().registerType(TypeReference.of("org.springframework.security.core.authority.SimpleGrantedAuthority"));
        hints.serialization().registerType(TypeReference.of("org.springframework.security.core.authority.FactorGrantedAuthority"));
        // Everything else a signed-in session holds (see SessionSerializationHintsTest)
        for (String type : new String[]{
                "org.springframework.security.authentication.AbstractAuthenticationToken",
                "org.springframework.security.core.userdetails.User$AuthorityComparator",
                "org.springframework.security.web.authentication.WebAuthenticationDetails",
                "org.springframework.security.web.csrf.DefaultCsrfToken",
                "org.springframework.security.web.savedrequest.DefaultSavedRequest",
                "org.springframework.security.web.savedrequest.SavedCookie",
                "java.lang.String$CaseInsensitiveComparator",
                "java.time.Ser",
                "java.util.Collections$UnmodifiableCollection",
                "java.util.Locale",
                "java.util.TreeMap",
                "java.util.TreeSet",
                "java.lang.String",
                // Notices shown after a redirect (flash attributes)
                "org.springframework.web.servlet.FlashMap",
                "org.springframework.util.LinkedMultiValueMap",
                "org.springframework.util.MultiValueMapAdapter",
                "java.util.concurrent.CopyOnWriteArrayList",
                "java.util.LinkedHashMap"}) {
            hints.serialization().registerType(TypeReference.of(type));
        }
        hints.serialization().registerType(java.time.Instant.class);
        hints.serialization().registerType(es.brasatech.fastbite.domain.user.Role.class);
        hints.serialization().registerType(UserDto.class);

        // Reflection hints for DTOs and Domain objects used in Thymeleaf/SpEL
        hints.reflection().registerType(TypeReference.of(java.math.BigDecimal.class), MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(TypeReference.of(java.math.BigInteger.class), MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.INVOKE_PUBLIC_METHODS);
        
        // Explicitly register Thymeleaf utility classes for SpEL
        hints.reflection().registerType(org.thymeleaf.expression.Lists.class, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(org.thymeleaf.expression.Numbers.class, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(org.thymeleaf.expression.Maps.class, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(org.thymeleaf.expression.Strings.class, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(org.thymeleaf.expression.Messages.class, MemberCategory.INVOKE_PUBLIC_METHODS);
        
        // Jackson 3 reflection support for native image
        hints.reflection().registerType(TypeReference.of("tools.jackson.databind.json.JsonMapper"), hint -> {
            hint.withMembers(MemberCategory.INVOKE_PUBLIC_METHODS);
            hint.withMethod("builder", java.util.Collections.emptyList(), ExecutableMode.INVOKE);
        });
        hints.reflection().registerType(TypeReference.of("tools.jackson.databind.ObjectMapper"), MemberCategory.INVOKE_PUBLIC_METHODS);

        hints.reflection().registerType(TypeReference.of(CounterOrderRequest.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(MenuData.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(Product.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(Customization.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(CustomizationType.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(CustomizationOption.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(CustomizationInputType.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(Tab.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(Cart.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(CartItem.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(BackOfficeDto.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(OrderCancelReason.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(OrderStatusChange.class), MemberCategory.values());

        // Domain objects used in templates or JSON serialization
        hints.reflection().registerType(TypeReference.of(ProductCustomizer.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(Order.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(OrderChannel.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(OrderPaymentStatus.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(TableStatus.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(Customer.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(Table.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(PaymentConfig.class), MemberCategory.values());

        hints.reflection().registerType(TypeReference.of(es.brasatech.fastbite.domain.order.ServiceType.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(es.brasatech.fastbite.domain.settings.RestaurantSettings.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(es.brasatech.fastbite.controller.TableQrController.TableQr.class), MemberCategory.values());

        // Translation editor
        hints.reflection().registerType(TypeReference.of(I18nField.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(es.brasatech.fastbite.domain.TranslatableText.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(es.brasatech.fastbite.domain.TranslatableType.class), MemberCategory.values());
        
        // Missing Domain DTOs
        hints.reflection().registerType(TypeReference.of(CustomizationDto.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(CustomizationOptionDto.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(Group.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(ProductDto.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of("es.brasatech.fastbite.jpa.tenant.TenantBackupRestoreAdapter$TenantBackupData"), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of("es.brasatech.fastbite.jpa.tenant.TenantBackupRestoreAdapter$OrderCounterBackup"), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of("es.brasatech.fastbite.jpa.settings.RestaurantSettingsEntity"), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(UserDto.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(es.brasatech.fastbite.domain.user.Role.class), MemberCategory.values());

        // Tenant Location reflection hints
        hints.reflection().registerType(TypeReference.of(es.brasatech.fastbite.domain.tenant.TenantLocation.class), MemberCategory.values());

        // Discount classes reflection hints
        hints.reflection().registerType(TypeReference.of(es.brasatech.fastbite.domain.discount.DiscountRule.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(es.brasatech.fastbite.domain.discount.DiscountScope.class), MemberCategory.values());
        hints.reflection().registerType(TypeReference.of(es.brasatech.fastbite.domain.discount.DiscountType.class), MemberCategory.values());
    }
}
