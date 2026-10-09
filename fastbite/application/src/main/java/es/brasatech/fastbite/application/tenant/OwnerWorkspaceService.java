package es.brasatech.fastbite.application.tenant;
import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import es.brasatech.fastbite.domain.tenant.*;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
@Service
public class OwnerWorkspaceService {
    private final TenantLocationService locations;
    private final OwnerWorkspacePort progress;
    private final ProductService products;
    private final RestaurantSettingsService settings;
    private final FiscalProfileService fiscalProfiles;
    public OwnerWorkspaceService(TenantLocationService locations, OwnerWorkspacePort progress, ProductService products, RestaurantSettingsService settings,
            FiscalProfileService fiscalProfiles) {
        this.locations=locations; this.progress=progress; this.products=products; this.settings=settings; this.fiscalProfiles=fiscalProfiles;
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
    public void saveService(String owner,String tenant,boolean dineIn,boolean takeaway,int yellow,int red) {
        requireOwner(owner,tenant);
        if (!dineIn && !takeaway) throw new IllegalArgumentException("Choose at least one way to serve your customers.");
        if (yellow<0 || red<0 || (yellow>0 && red>0 && red<yellow)) throw new IllegalArgumentException("Kitchen warning times must be positive and in increasing order, or zero to disable.");
        inTenant(tenant,()->settings.save(new RestaurantSettings(dineIn,takeaway,yellow,red)));
        progress.update(tenant,p->new SetupProgress(true,true,p.previewReviewed(),p.dismissed()));
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
        Map<String, String> wrong = new LinkedHashMap<>(profile.problems());
        wrong.values().removeIf("fiscal.error.required"::equals);
        if (wrong.isEmpty()) {
            inTenant(tenant, () -> fiscalProfiles.save(profile));
        }
        return wrong;
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
