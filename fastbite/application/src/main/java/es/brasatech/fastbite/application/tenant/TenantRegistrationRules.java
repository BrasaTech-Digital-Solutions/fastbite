package es.brasatech.fastbite.application.tenant;

import java.util.Locale;
import java.util.Set;

public final class TenantRegistrationRules {
    private static final Set<String> RESERVED = Set.of("default", "admin", "kebab", "signup", "login",
            "logout", "owner", "menu", "dashboard", "counter", "backoffice", "api", "css", "js", "images",
            "webjars", "stripe", "error", "actuator", "appspecific", "allergens", "verifactu", "internal", "link");
    private TenantRegistrationRules() { }

    public static String tenantId(String input) {
        String tenant = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (!tenant.matches("[a-z0-9]{1,56}")) {
            throw new IllegalArgumentException("Use 1 to 56 letters or digits for your restaurant web address.");
        }
        if (RESERVED.contains(tenant)) {
            throw new IllegalArgumentException("The restaurant web address '" + tenant
                    + "' is reserved. Choose another address, such as your restaurant name followed by 'cafe'.");
        }
        return tenant;
    }

    public static void owner(String username) {
        if (username == null || !username.matches("[a-zA-Z0-9_.@-]{1,100}")) {
            throw new IllegalArgumentException("Invalid owner username.");
        }
    }

    public static void plan(String plan) {
        if (!java.util.Set.of("FREE", "RESTAURANT", "Free Demo").contains(plan == null ? "" : plan)) throw new IllegalArgumentException("Invalid restaurant plan.");
    }
}
