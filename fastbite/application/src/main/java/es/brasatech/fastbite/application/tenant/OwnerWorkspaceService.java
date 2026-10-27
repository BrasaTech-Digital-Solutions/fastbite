package es.brasatech.fastbite.application.tenant;

import es.brasatech.fastbite.application.fiscal.FiscalIssuerPort;
import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.fiscal.FiscalTicketRepository;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.fiscal.SpanishTaxId;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.tenant.SetupProgress;
import es.brasatech.fastbite.domain.tenant.TenantContext;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class OwnerWorkspaceService {
    private final TenantLocationService locations;
    private final OwnerWorkspacePort progress;
    private final ProductService products;
    private final RestaurantSettingsService settings;
    private final FiscalProfileService fiscalProfiles;
    private final FiscalTicketRepository tickets;
    private final VerifactuService verifactu;
    private final FiscalIssuerPort fiscalIssuers;
    private final PlatformEnvironment environment;
    private final Clock clock;

    public OwnerWorkspaceService(TenantLocationService locations, OwnerWorkspacePort progress, ProductService products, RestaurantSettingsService settings,
            FiscalProfileService fiscalProfiles, FiscalTicketRepository tickets, VerifactuService verifactu,
            FiscalIssuerPort fiscalIssuers, PlatformEnvironment environment, Clock clock) {
        this.locations = locations;
        this.progress = progress;
        this.products = products;
        this.settings = settings;
        this.fiscalProfiles = fiscalProfiles;
        this.tickets = tickets;
        this.verifactu = verifactu;
        this.fiscalIssuers = fiscalIssuers;
        this.environment = environment;
        this.clock = clock;
    }

    public void requireOwner(String owner, String tenant) {
        if (owner == null || !locations.isOwnerOf(owner,tenant)) throw new IllegalArgumentException("You do not own this location.");
    }
    public Summary summary(String owner,String tenant) {
        requireOwner(owner,tenant);
        return inTenant(tenant,()->new Summary(progress.read(tenant), products.findAll().size(), settings.get(), fiscalProfiles.get()));
    }
    public void action(String owner,String tenant,String action) {
        requireOwner(owner,tenant);
        progress.update(tenant,p->switch(action) {
            case "start" -> new SetupProgress(true,p.serviceReviewed(),p.previewReviewed(),false);
            case "preview" -> new SetupProgress(true,p.serviceReviewed(),true,p.dismissed());
            case "dismiss" -> new SetupProgress(p.started(),p.serviceReviewed(),p.previewReviewed(),true);
            case "resume" -> new SetupProgress(p.started(),p.serviceReviewed(),p.previewReviewed(),false);
            default -> throw new IllegalArgumentException("Unknown setup action.");
        });
    }
    public void saveService(String owner,String tenant,boolean dineIn,boolean takeaway,int yellow,int red,boolean tableQrTrusted) {
        requireOwner(owner,tenant);
        if (!dineIn && !takeaway) throw new IllegalArgumentException("Choose at least one way to serve your customers.");
        if (yellow<0 || red<0 || (yellow>0 && red>0 && red<yellow)) throw new IllegalArgumentException("Kitchen warning times must be positive and in increasing order, or zero to disable.");
        inTenant(tenant,()->settings.save(new RestaurantSettings(dineIn,takeaway,yellow,red,tableQrTrusted)));
        progress.update(tenant,p->new SetupProgress(true,true,p.previewReviewed(),p.dismissed()));
    }
    public void saveService(String owner,String tenant,boolean dineIn,boolean takeaway,int yellow,int red) {
        requireOwner(owner,tenant);
        if (!dineIn && !takeaway) throw new IllegalArgumentException("Choose at least one way to serve your customers.");
        if (yellow<0 || red<0 || (yellow>0 && red>0 && red<yellow)) throw new IllegalArgumentException("Kitchen warning times must be positive and in increasing order, or zero to disable.");
        var current = inTenant(tenant,settings::get);
        boolean trusted = current != null && current.tableQrTrusted();
        saveService(owner,tenant,dineIn,takeaway,yellow,red,trusted);
    }
    /** The cash the location's drawer starts each day with, offered when the cash is checked. */
    public java.math.BigDecimal cashFloat(String owner, String tenant) {
        requireOwner(owner, tenant);
        return inTenant(tenant, settings::cashFloat);
    }
    public void saveCashFloat(String owner, String tenant, java.math.BigDecimal cashFloat) {
        requireOwner(owner, tenant);
        if (cashFloat == null || cashFloat.signum() < 0 || cashFloat.compareTo(java.math.BigDecimal.valueOf(100_000)) > 0 || cashFloat.scale() > 2) {
            throw new IllegalArgumentException("Enter the starting cash in euros, from 0 to 100000, with up to two decimals.");
        }
        inTenant(tenant, () -> { settings.saveCashFloat(cashFloat); return null; });
    }
    public FiscalProfile fiscalProfile(String owner, String tenant) {
        requireOwner(owner, tenant);
        return inTenant(tenant, fiscalProfiles::get);
    }

    /**
     * Saves the location's business details. Fields may still be missing, so owners can save as
     * they go, but nothing that is filled in may be wrong.
     *
     * @return each wrong field with the key of the message explaining it; empty when it was saved
     */
    public Map<String, String> saveFiscalProfile(String owner, String tenant, FiscalProfile profile) {
        requireOwner(owner, tenant);
        return inTenant(tenant, () -> {
            Map<String, String> wrong = new LinkedHashMap<>(profile.problems());
            if (tickets.anyRealTicket()) {
                // Issued tickets carry this NIF and series: they stay, and the details stay complete
                FiscalProfile saved = fiscalProfiles.get();
                if (!Objects.equals(saved.taxId(), profile.taxId())) wrong.put("taxId", "fiscal.error.taxId.locked");
                if (!Objects.equals(saved.seriesPrefix(), profile.seriesPrefix())) wrong.put("seriesPrefix", "fiscal.error.seriesPrefix.locked");
            } else {
                wrong.values().removeIf("fiscal.error.required"::equals);
            }
            if (wrong.isEmpty()) {
                fiscalProfiles.save(profile);
                if (environment.production()
                        && profile.taxId() != null && SpanishTaxId.isValid(profile.taxId())) {
                    SpanishTaxId.parse(profile.taxId()).ifPresent(taxId ->
                            fiscalIssuers.recordIfAbsent(taxId.value(), tenant, clock.instant()));
                }
            }
            return wrong;
        });
    }

    /** Where the location's billing records stand with the tax agency. */
    public VerifactuService.Summary verifactuSummary(String owner, String tenant, boolean training) {
        requireOwner(owner, tenant);
        return inTenant(tenant, () -> verifactu.summary(training));
    }

    /** Every billing record of the location, in chain order, for the owner to keep. */
    public List<VerifactuRecord> verifactuRecords(String owner, String tenant, boolean training) {
        requireOwner(owner, tenant);
        return inTenant(tenant, () -> verifactu.chain(training));
    }

    /** Every ticket ever issued by the location, oldest first, for the owner to inspect and download. */
    public List<es.brasatech.fastbite.domain.fiscal.FiscalTicket> fiscalTickets(String owner, String tenant) {
        requireOwner(owner, tenant);
        return inTenant(tenant, tickets::findAll);
    }

    /** A single issued ticket of the location, for the owner to inspect or print. */
    public Optional<es.brasatech.fastbite.domain.fiscal.FiscalTicket> fiscalTicket(String owner, String tenant, String ticketId) {
        requireOwner(owner, tenant);
        return inTenant(tenant, () -> tickets.findById(ticketId));
    }

    /** Cancellation of a ticket, if it was cancelled. */
    public Optional<es.brasatech.fastbite.domain.fiscal.TicketCancellation> ticketCancellation(String owner, String tenant, String ticketId) {
        requireOwner(owner, tenant);
        return inTenant(tenant, () -> tickets.findCancellation(ticketId));
    }

    /** VERI*FACTU record of a ticket, if any. */
    public Optional<VerifactuRecord> verifactuRecordForTicket(String owner, String tenant, String ticketId) {
        requireOwner(owner, tenant);
        return inTenant(tenant, () -> verifactu.findForTicket(ticketId));
    }

    /**
     * Sends a refused billing record again as a subsanación, with the location's legal name as it
     * is now, so a name corrected in the business details is the one sent.
     */
    public VerifactuRecord amendVerifactuRecord(String owner, String tenant, String recordId) {
        requireOwner(owner, tenant);
        return inTenant(tenant, () -> verifactu.amend(recordId, fiscalProfiles.get().legalName()));
    }

    /** True once the location has issued a real ticket, which fixes its NIF and series code. */
    public boolean fiscalLocked(String owner, String tenant) {
        requireOwner(owner, tenant);
        return inTenant(tenant, tickets::anyRealTicket);
    }

    /**
     * Checks if this same owner has previously used this NIF on another location in FastBite,
     * returning the earliest first_seen_at date. Never leaks anything about other owners.
     */
    public Optional<Instant> priorNifUsage(String owner, String taxId, String tenant) {
        requireOwner(owner, tenant);
        if (taxId == null || !SpanishTaxId.isValid(taxId)) {
            return Optional.empty();
        }
        return SpanishTaxId.parse(taxId).flatMap(parsed ->
                fiscalIssuers.findPriorUsageByOwner(owner, parsed.value(), tenant));
    }

    private <T> T inTenant(String tenant,Supplier<T> work) {
        String before=TenantContext.getCurrentTenant();
        try { TenantContext.setCurrentTenant(tenant); return work.get(); }
        finally { if(before==null) TenantContext.clear(); else TenantContext.setCurrentTenant(before); }
    }
    public record Summary(SetupProgress progress,int products,RestaurantSettings settings,FiscalProfile fiscal) {
        public static final int STEPS = 4;
        public int completed() { return (products>0?1:0)+(progress.serviceReviewed()?1:0)+(progress.previewReviewed()?1:0)+(fiscal.complete()?1:0); }
        public boolean ready() { return completed()==STEPS; }
        public int steps() { return STEPS; }
        public boolean needsStart() { return products==0 && !progress.started(); }
    }
}
