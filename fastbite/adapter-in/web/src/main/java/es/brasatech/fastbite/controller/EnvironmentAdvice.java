package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate.HoldReason;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Tells every page which environment it belongs to, so the sandbox can never be mistaken for
 * production: its pages carry a banner, a favicon of their own and "[Sandbox]" in the tab title.
 * In production, also supplies the VERI*FACTU hold banner when the active location cannot issue
 * tickets yet.
 */
@ControllerAdvice
public class EnvironmentAdvice {

    private static final Set<String> NON_LOCATION_OWNER_VIEWS = Set.of("locations", "settings", "account", "billing");

    /**
     * @param sandbox       pages of the sandbox
     * @param productionUrl where a linked sandbox sends people to production; null when standalone
     * @param practice      production with a sandbox its owners can enter
     */
    public record PlatformView(boolean sandbox, String productionUrl, boolean practice) {
    }

    /**
     * Production hold banner rendered through {@code fragments/environment :: banner}.
     *
     * @param audience       {@code "owner"}, {@code "staff"}, or {@code "guest"}
     * @param tenantId       the location currently on hold
     * @param messageKey     primary i18n message key for the audience
     * @param reasonKey      optional human-readable hold reason key (owner workspace only)
     * @param actionUrl      optional link to the VERI*FACTU opening step (owner workspace only)
     * @param actionLabelKey optional label key for {@code actionUrl}
     */
    public record VerifactuBannerView(
            String audience,
            String tenantId,
            String messageKey,
            String reasonKey,
            String actionUrl,
            String actionLabelKey) {
    }

    private final PlatformView view;
    private final ObjectProvider<VerifactuAuthorizationGate> gateProvider;
    private final ObjectProvider<TenantLocationService> locationsProvider;

    public EnvironmentAdvice(Environment configuration) {
        this(configuration, null, null);
    }

    @Autowired
    public EnvironmentAdvice(
            Environment configuration,
            ObjectProvider<VerifactuAuthorizationGate> gateProvider,
            ObjectProvider<TenantLocationService> locationsProvider) {
        boolean sandbox = !"production".equalsIgnoreCase(configuration.getProperty("fastbite.environment", "sandbox").strip());
        String link = configuration.getProperty("fastbite.link.url", "").strip();
        this.view = new PlatformView(sandbox, sandbox && !link.isEmpty() ? link : null, !sandbox && !link.isEmpty());
        this.gateProvider = gateProvider;
        this.locationsProvider = locationsProvider;
    }

    @ModelAttribute("platform")
    public PlatformView platform() {
        return view;
    }

    @ModelAttribute("verifactuBanner")
    public VerifactuBannerView verifactuBanner(HttpServletRequest request, Principal principal) {
        if (view.sandbox() || request == null || !"GET".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        VerifactuAuthorizationGate gate = gateProvider == null ? null : gateProvider.getIfAvailable();
        if (gate == null) {
            return null;
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank() || isExcludedPath(uri) || isNonHtmlAccept(request)) {
            return null;
        }

        String audience;
        String tenantId;
        if (uri.startsWith("/owner/")) {
            if (uri.startsWith("/owner/platform/") || uri.startsWith("/owner/account/")) {
                return null;
            }
            audience = "owner";
            tenantId = resolveOwnerTenant(uri, request, principal);
        } else if (isStaffPath(uri)) {
            audience = "staff";
            tenantId = TenantContext.getCurrentTenant();
        } else if (isGuestPath(uri)) {
            audience = "guest";
            tenantId = TenantContext.getCurrentTenant();
        } else {
            return null;
        }

        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        TenantLocationService locations = locationsProvider == null ? null : locationsProvider.getIfAvailable();
        if (locations != null) {
            Optional<TenantLocation> location = locations.getLocation(tenantId);
            if (location.isEmpty() || location.get().isClosed()) {
                return null;
            }
        }

        Optional<HoldReason> hold = gate.holdReason(tenantId);
        if (hold.isEmpty()) {
            return null;
        }

        return switch (audience) {
            case "staff" -> new VerifactuBannerView("staff", tenantId, "verifactu.banner.staff", null, null, null);
            case "guest" -> new VerifactuBannerView("guest", tenantId, "verifactu.banner.guest", null, null, null);
            default -> new VerifactuBannerView(
                    "owner",
                    tenantId,
                    "verifactu.banner.owner",
                    holdReasonMessageKey(hold.get()),
                    "/owner/console?view=today&location=" + tenantId + "&opening=1#opening-step-verifactu",
                    "verifactu.banner.owner.action");
        };
    }

    public static String holdReasonMessageKey(HoldReason reason) {
        if (reason == null) {
            return "verifactu.hold.reason.not_signed";
        }
        return switch (reason) {
            case COLLABORATION_INACTIVE -> "verifactu.hold.reason.collaboration_inactive";
            case FISCAL_INCOMPLETE -> "verifactu.hold.reason.fiscal_incomplete";
            case NOT_SIGNED -> "verifactu.hold.reason.not_signed";
            case IN_PROGRESS -> "verifactu.hold.reason.in_progress";
            case DOWNLOAD_FAILED -> "verifactu.hold.reason.download_failed";
            case DECLINED -> "verifactu.hold.reason.declined";
            case EXPIRED -> "verifactu.hold.reason.expired";
            case CANCELLED -> "verifactu.hold.reason.cancelled";
            case NIF_MISMATCH -> "verifactu.hold.reason.nif_mismatch";
            case NIF_UNVERIFIED -> "verifactu.hold.reason.nif_unverified";
            case TEST_ENVIRONMENT -> "verifactu.hold.reason.test_environment";
            case DOCUMENTS_GONE -> "verifactu.hold.reason.documents_gone";
            case REVOKED -> "verifactu.hold.reason.revoked";
            case SIGNING_UNAVAILABLE -> "verifactu.hold.reason.signing_unavailable";
        };
    }

    private String resolveOwnerTenant(String uri, HttpServletRequest request, Principal principal) {
        String fromPrefix = extractPathSegmentAfter(uri, "/owner/billing/");
        if (fromPrefix == null) {
            fromPrefix = extractPathSegmentAfter(uri, "/owner/locations/");
        }
        if (fromPrefix == null) {
            fromPrefix = extractPathSegmentAfter(uri, "/owner/workspace/");
        }
        if (fromPrefix != null) {
            return fromPrefix;
        }
        String locationParam = request.getParameter("location");
        if (locationParam != null && !locationParam.isBlank()) {
            return locationParam.strip();
        }
        if ("/owner/console".equals(uri) && principal != null && principal.getName() != null) {
            String viewParam = request.getParameter("view");
            if (viewParam != null && NON_LOCATION_OWNER_VIEWS.contains(viewParam.strip())) {
                return null;
            }
            TenantLocationService locations = locationsProvider == null ? null : locationsProvider.getIfAvailable();
            if (locations != null) {
                List<TenantLocation> list = locations.getLocationsByOwner(principal.getName());
                return list.stream()
                        .filter(l -> !l.isClosed())
                        .map(TenantLocation::tenantId)
                        .findFirst()
                        .orElse(null);
            }
        }
        return null;
    }

    private static String extractPathSegmentAfter(String uri, String prefix) {
        if (!uri.startsWith(prefix)) {
            return null;
        }
        String rest = uri.substring(prefix.length());
        int slash = rest.indexOf('/');
        String segment = slash >= 0 ? rest.substring(0, slash) : rest;
        return segment.isBlank() ? null : segment.strip();
    }

    private static boolean isExcludedPath(String uri) {
        return "/".equals(uri)
                || uri.startsWith("/api/")
                || uri.contains("/api/")
                || uri.contains("/fragments/")
                || uri.endsWith(".json")
                || uri.endsWith(".pdf")
                || uri.startsWith("/internal/")
                || uri.startsWith("/link/")
                || uri.startsWith("/legal/")
                || uri.startsWith("/verifactu/")
                || uri.startsWith("/css/")
                || uri.startsWith("/js/")
                || uri.startsWith("/images/")
                || uri.startsWith("/user-images/")
                || uri.startsWith("/webjars/")
                || uri.startsWith("/signup")
                || uri.startsWith("/login")
                || uri.startsWith("/error")
                || uri.startsWith("/verify-email")
                || uri.startsWith("/set-password")
                || uri.startsWith("/forgot-password")
                || uri.startsWith("/reset-password");
    }

    private static boolean isNonHtmlAccept(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank()) {
            return false;
        }
        String lower = accept.toLowerCase(Locale.ROOT);
        return lower.contains("application/json") && !lower.contains("text/html") && !lower.contains("*/*");
    }

    private static boolean isStaffPath(String uri) {
        String normalized = stripTenantPrefix(uri);
        return normalized.equals("/counter") || normalized.startsWith("/counter/")
                || normalized.equals("/dashboard") || normalized.startsWith("/dashboard/")
                || normalized.equals("/backoffice") || normalized.startsWith("/backoffice/");
    }

    private static boolean isGuestPath(String uri) {
        String normalized = stripTenantPrefix(uri);
        return normalized.equals("/menu") || normalized.startsWith("/menu/")
                || normalized.equals("/select-payment") || normalized.startsWith("/select-payment/")
                || normalized.equals("/order-confirmation") || normalized.startsWith("/order-confirmation/")
                || normalized.equals("/allergens") || normalized.startsWith("/allergens/")
                || normalized.equals("/ticket") || normalized.startsWith("/ticket/")
                || normalized.equals("/privacy") || normalized.startsWith("/privacy/");
    }

    private static String stripTenantPrefix(String uri) {
        String tenant = TenantContext.getCurrentTenant();
        if (tenant != null && !tenant.isBlank()) {
            String prefix = "/" + tenant;
            if (uri.equals(prefix)) {
                return "/";
            }
            if (uri.startsWith(prefix + "/")) {
                return uri.substring(prefix.length());
            }
        }
        return uri;
    }
}
