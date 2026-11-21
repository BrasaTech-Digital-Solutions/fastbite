package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.platform.PlatformAdmins;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate.HoldReason;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationService;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import es.brasatech.fastbite.domain.verifactu.VerifactuAuthorization;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Brasatech platform-admin view of production locations currently on VERI*FACTU hold and any online
 * orders marked {@code NEEDS_REFUND}.
 */
@Controller
public class PlatformVerifactuAdminController {

    public record HeldLocationRow(
            String tenantId,
            String ownerUsername,
            String nif,
            String legalName,
            String authorizationStatus,
            String holdReason,
            String reasonMessageKey) {
    }

    public record NeedsRefundOrderRow(
            String tenantId,
            String ownerUsername,
            String orderId,
            int orderNumber,
            String customerName,
            BigDecimal total,
            String cancelReason,
            LocalDateTime createdAt) {
    }

    private final TenantLocationService locations;
    private final FiscalProfileService fiscalProfiles;
    private final VerifactuAuthorizationGate gate;
    private final VerifactuAuthorizationService authorizations;
    private final OrderService orders;
    private final PlatformEnvironment environment;
    private final PlatformAdmins admins;

    public PlatformVerifactuAdminController(
            TenantLocationService locations,
            FiscalProfileService fiscalProfiles,
            VerifactuAuthorizationGate gate,
            VerifactuAuthorizationService authorizations,
            OrderService orders,
            PlatformEnvironment environment,
            PlatformAdmins admins) {
        this.locations = Objects.requireNonNull(locations, "locations");
        this.fiscalProfiles = Objects.requireNonNull(fiscalProfiles, "fiscalProfiles");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.authorizations = Objects.requireNonNull(authorizations, "authorizations");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.admins = Objects.requireNonNull(admins, "admins");
    }

    @GetMapping("/owner/platform/verifactu-holds")
    public String verifactuHolds(Authentication auth, Model model) {
        String admin = requirePlatformAdmin(auth);
        List<TenantLocation> ownerLocations = locations.getLocationsByOwner(admin);
        model.addAttribute("ownerUsername", admin);
        model.addAttribute("locations", ownerLocations);
        model.addAttribute("selected", ownerLocations.isEmpty() ? null : ownerLocations.getFirst());
        model.addAttribute("view", "settings");
        model.addAttribute("collaborationActive", authorizations.isCollaborationActive());

        List<HeldLocationRow> heldLocations = new ArrayList<>();
        List<NeedsRefundOrderRow> needsRefundOrders = new ArrayList<>();

        for (TenantLocation loc : locations.getAllLocations()) {
            if (loc.isClosed()) {
                continue;
            }
            Optional<HoldReason> reasonOpt = gate.holdReason(loc.tenantId());
            if (reasonOpt.isPresent()) {
                FiscalProfile profile = inTenant(loc.tenantId(), fiscalProfiles::get);
                String nif = (profile != null && profile.taxId() != null && !profile.taxId().isBlank())
                        ? profile.taxId().strip()
                        : null;
                String legalName = (profile != null && profile.legalName() != null && !profile.legalName().isBlank())
                        ? profile.legalName().strip()
                        : null;
                String authStatus = nif == null
                        ? null
                        : authorizations.findCurrentForNif(nif)
                                .map(VerifactuAuthorization::status)
                                .map(Enum::name)
                                .orElse(null);
                heldLocations.add(new HeldLocationRow(
                        loc.tenantId(),
                        loc.ownerUsername(),
                        nif,
                        legalName,
                        authStatus,
                        reasonOpt.get().name(),
                        EnvironmentAdvice.holdReasonMessageKey(reasonOpt.get())));
            }

            List<Order> pendingRefunds = inTenant(loc.tenantId(), orders::findOrdersNeedingRefund);
            for (Order order : pendingRefunds) {
                needsRefundOrders.add(new NeedsRefundOrderRow(
                        loc.tenantId(),
                        loc.ownerUsername(),
                        order.id(),
                        order.orderNumber(),
                        order.customerName(),
                        order.total(),
                        order.cancelReason(),
                        order.createdAt()));
            }
        }

        model.addAttribute("heldLocations", heldLocations);
        model.addAttribute("needsRefundOrders", needsRefundOrders);
        return "fastfood/owner/platform-verifactu-holds";
    }

    private String requirePlatformAdmin(Authentication auth) {
        if (!environment.production() || auth == null || !auth.isAuthenticated()
                || auth.getAuthorities().stream().noneMatch(a -> "ROLE_OWNER".equals(a.getAuthority()))
                || !admins.isAdmin(auth.getName())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return auth.getName();
    }

    private <T> T inTenant(String tenantId, Supplier<T> work) {
        String previous = TenantContext.getCurrentTenant();
        try {
            TenantContext.setCurrentTenant(tenantId);
            return work.get();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentTenant(previous);
            }
        }
    }
}
