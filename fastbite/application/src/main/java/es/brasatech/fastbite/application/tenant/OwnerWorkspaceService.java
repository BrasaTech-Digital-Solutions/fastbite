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
import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.domain.order.Order;
import es.brasatech.fastbite.domain.order.OrderChannel;
import es.brasatech.fastbite.domain.order.OrderPaymentStatus;
import es.brasatech.fastbite.domain.order.OrderStatus;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.table.TableSignatureUtil;
import es.brasatech.fastbite.domain.table.Table;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final OrderService orders;
    private final UserService users;
    private final TableService tables;
    private final TableSignatureUtil tableSignatures;

    @Autowired
    public OwnerWorkspaceService(TenantLocationService locations, OwnerWorkspacePort progress, ProductService products, RestaurantSettingsService settings,
            FiscalProfileService fiscalProfiles, FiscalTicketRepository tickets, VerifactuService verifactu,
            FiscalIssuerPort fiscalIssuers, PlatformEnvironment environment, Clock clock,
            OrderService orders, UserService users,
            TableService tables, TableSignatureUtil tableSignatures) {
        this.locations = Objects.requireNonNull(locations, "locations");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.products = Objects.requireNonNull(products, "products");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.fiscalProfiles = Objects.requireNonNull(fiscalProfiles, "fiscalProfiles");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.verifactu = Objects.requireNonNull(verifactu, "verifactu");
        this.fiscalIssuers = Objects.requireNonNull(fiscalIssuers, "fiscalIssuers");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.users = Objects.requireNonNull(users, "users");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.tableSignatures = Objects.requireNonNull(tableSignatures, "tableSignatures");
    }

    public void requireOwner(String owner, String tenant) {
        if (owner == null || !locations.isOwnerOf(owner,tenant)) throw new IllegalArgumentException("You do not own this location.");
    }
    public Summary summary(String owner,String tenant) {
        requireOwner(owner,tenant);
        return inTenant(tenant,() -> {
            SetupProgress current = progress.read(tenant);
            SetupProgress updated = checkAndLatchMilestones(tenant, current);
            PracticeTable firstTable = findFirstTable(tenant);
            int productCount = products.findAll().size();
            RestaurantSettings serviceSettings = settings.get();
            FiscalProfile fiscal = fiscalProfiles.get();
            boolean hasStaff = users.findAll().stream().anyMatch(u -> !u.isOwner() && u.active());
            boolean chargesEnabled = locations.getLocation(tenant).map(l -> l.isStripeVerified()).orElse(false);
            Summary summary = new Summary(updated, productCount, serviceSettings, fiscal, firstTable,
                    hasStaff, chargesEnabled, environment.sandbox());
            if (!environment.sandbox() && !updated.opened() && (summary.ready() || tickets.anyRealTicket())) {
                progress.update(tenant, p -> p.withOpening(p.workAlone(), true));
                updated = progress.read(tenant);
                summary = new Summary(updated, productCount, serviceSettings, fiscal, firstTable,
                        hasStaff, chargesEnabled, false);
            }
            return summary;
        });
    }

    private PracticeTable findFirstTable(String tenant) {
        List<Table> allTables = tables.findAll();
        if (allTables.isEmpty()) {
            return null;
        }
        Table first = allTables.stream().filter(Table::active).findFirst().orElse(allTables.getFirst());
        String token = tableSignatures.generateSignature(tenant, first.id());
        String url = "/" + tenant + "/menu?table=" + first.id() + "&token=" + token;
        return new PracticeTable(first.id(), first.name(), url);
    }

    public void ensureSampleTable(String owner, String tenant) {
        requireOwner(owner, tenant);
        inTenant(tenant, () -> {
            if (tables.findAll().isEmpty()) {
                tables.create(new Table("Mesa 1", 4));
            }
            return null;
        });
    }

    private SetupProgress checkAndLatchMilestones(String tenant, SetupProgress current) {
        if (!environment.sandbox()) {
            return current;
        }
        boolean tableOrdered = current.practiceTableOrdered();
        boolean kitchenPrepared = current.practiceKitchenPrepared();
        boolean counterCharged = current.practiceCounterCharged();
        boolean teamTested = current.practiceTeamTested();

        if (tableOrdered && kitchenPrepared && counterCharged && teamTested) {
            return current;
        }

        boolean needUpdate = false;

        if (!tableOrdered || !kitchenPrepared || !counterCharged) {
            List<Order> orderList = orders.findAll();
            for (Order o : orderList) {
                if (!tableOrdered && o.orderChannel() == OrderChannel.TABLE) {
                    tableOrdered = true;
                    needUpdate = true;
                }
                if (!kitchenPrepared && (o.status() == OrderStatus.DONE
                        || o.status() == OrderStatus.DELIVERED
                        || o.status() == OrderStatus.COMPLETE)) {
                    kitchenPrepared = true;
                    needUpdate = true;
                }
                if (!counterCharged && o.paymentStatus() == OrderPaymentStatus.PAID) {
                    counterCharged = true;
                    needUpdate = true;
                }
            }
        }

        if (!counterCharged && !tickets.findAll().isEmpty()) {
            counterCharged = true;
            needUpdate = true;
        }

        if (!teamTested) {
            boolean hasStaff = users.findAll().stream()
                    .anyMatch(u -> !u.isOwner() && u.active());
            if (hasStaff) {
                teamTested = true;
                needUpdate = true;
            }
        }

        if (needUpdate) {
            final boolean fTableOrdered = tableOrdered;
            final boolean fKitchenPrepared = kitchenPrepared;
            final boolean fCounterCharged = counterCharged;
            final boolean fTeamTested = teamTested;
            progress.update(tenant, p -> new SetupProgress(
                    p.started(), p.serviceReviewed(), p.previewReviewed(), p.dismissed(),
                    p.practiceGuestViewed(),
                    fTableOrdered || p.practiceTableOrdered(),
                    fKitchenPrepared || p.practiceKitchenPrepared(),
                    fCounterCharged || p.practiceCounterCharged(),
                    p.practiceMenuPersonalized(),
                    fTeamTested || p.practiceTeamTested(), p.workAlone(), p.opened()
            ));
            return progress.read(tenant);
        }
        return current;
    }
    public void action(String owner,String tenant,String action) {
        requireOwner(owner,tenant);
        progress.update(tenant,p->switch(action) {
            case "start" -> new SetupProgress(true,p.serviceReviewed(),p.previewReviewed(),false,
                    p.practiceGuestViewed(),p.practiceTableOrdered(),p.practiceKitchenPrepared(),
                    p.practiceCounterCharged(),p.practiceMenuPersonalized(),p.practiceTeamTested(),p.workAlone(),p.opened());
            case "preview" -> new SetupProgress(true,p.serviceReviewed(),true,p.dismissed(),
                    p.practiceGuestViewed(),p.practiceTableOrdered(),p.practiceKitchenPrepared(),
                    p.practiceCounterCharged(),p.practiceMenuPersonalized(),p.practiceTeamTested(),p.workAlone(),p.opened());
            case "dismiss" -> new SetupProgress(p.started(),p.serviceReviewed(),p.previewReviewed(),true,
                    p.practiceGuestViewed(),p.practiceTableOrdered(),p.practiceKitchenPrepared(),
                    p.practiceCounterCharged(),p.practiceMenuPersonalized(),p.practiceTeamTested(),p.workAlone(),p.opened());
            case "resume" -> new SetupProgress(p.started(),p.serviceReviewed(),p.previewReviewed(),false,
                    p.practiceGuestViewed(),p.practiceTableOrdered(),p.practiceKitchenPrepared(),
                    p.practiceCounterCharged(),p.practiceMenuPersonalized(),p.practiceTeamTested(),p.workAlone(),p.opened());
            case "practice_guest" -> new SetupProgress(true,p.serviceReviewed(),p.previewReviewed(),p.dismissed(),
                    true,p.practiceTableOrdered(),p.practiceKitchenPrepared(),
                    p.practiceCounterCharged(),p.practiceMenuPersonalized(),p.practiceTeamTested(),p.workAlone(),p.opened());
            case "practice_menu" -> new SetupProgress(true,p.serviceReviewed(),p.previewReviewed(),p.dismissed(),
                    p.practiceGuestViewed(),p.practiceTableOrdered(),p.practiceKitchenPrepared(),
                    p.practiceCounterCharged(),true,p.practiceTeamTested(),p.workAlone(),p.opened());
            case "work_alone" -> p.withOpening(true, p.opened());
            case "work_with_team" -> p.withOpening(false, p.opened());
            default -> throw new IllegalArgumentException("Unknown setup action.");
        });
    }
    public void saveService(String owner,String tenant,boolean dineIn,boolean takeaway,int yellow,int red,boolean tableQrTrusted) {
        requireOwner(owner,tenant);
        if (!dineIn && !takeaway) throw new IllegalArgumentException("Choose at least one way to serve your customers.");
        if (yellow<0 || red<0 || (yellow>0 && red>0 && red<yellow)) throw new IllegalArgumentException("Kitchen warning times must be positive and in increasing order, or zero to disable.");
        inTenant(tenant,()->settings.save(new RestaurantSettings(dineIn,takeaway,yellow,red,tableQrTrusted)));
        progress.update(tenant,p->new SetupProgress(true,true,p.previewReviewed(),p.dismissed(),
                p.practiceGuestViewed(),p.practiceTableOrdered(),p.practiceKitchenPrepared(),
                p.practiceCounterCharged(),p.practiceMenuPersonalized(),p.practiceTeamTested(),p.workAlone(),p.opened()));
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
    public record PracticeTable(String id, String name, String url) {}
    public record Summary(SetupProgress progress, int products, RestaurantSettings settings, FiscalProfile fiscal,
            PracticeTable firstTable, boolean hasStaff, boolean chargesEnabled, boolean sandbox) {
        public Summary(SetupProgress progress, int products, RestaurantSettings settings, FiscalProfile fiscal) {
            this(progress, products, settings, fiscal, null, false, false, false);
        }
        public boolean menuDone() { return products > 0; }
        public boolean fiscalDone() { return fiscal.complete(); }
        public boolean serviceDone() { return progress.serviceReviewed(); }
        public boolean paymentsRequired() { return settings.takeaway(); }
        public boolean paymentsDone() { return !paymentsRequired() || chargesEnabled; }
        public boolean teamDone() { return hasStaff || progress.workAlone(); }
        public boolean previewDone() { return progress.previewReviewed(); }
        public int completed() { return (menuDone()?1:0)+(fiscalDone()?1:0)+(serviceDone()?1:0)
                +(paymentsRequired() && chargesEnabled?1:0)+(teamDone()?1:0)+(previewDone()?1:0); }
        public int steps() { return paymentsRequired()?6:5; }
        public boolean ready() { return completed()==steps(); }
        public String nextStep() {
            if (!menuDone()) return "menu";
            if (!fiscalDone()) return "fiscal";
            if (!serviceDone()) return "service";
            if (!paymentsDone()) return "payments";
            if (!teamDone()) return "team";
            if (!previewDone()) return "preview";
            return "";
        }
        public boolean opened() { return progress.opened(); }
        public boolean needsStart() { return products==0 && !progress.started() && !progress.opened(); }
    }
}
