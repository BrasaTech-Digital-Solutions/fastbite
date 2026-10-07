package es.brasatech.fastbite.controller;
import es.brasatech.fastbite.application.tenant.SubscriptionService;
import es.brasatech.fastbite.domain.tenant.SubscriptionPlan;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(assignableTypes = {HomeController.class, SignupController.class, BillingController.class, OwnerWorkspaceController.class})
public class PlatformOfferAdvice {
    private final SubscriptionService subscriptions;

    public PlatformOfferAdvice(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @ModelAttribute("monthlyPrice")
    public long monthlyPrice() {
        try {
            return subscriptions.platformSettings().monthlyPriceCents() / 100;
        } catch (Exception e) {
            return SubscriptionPlan.MONTHLY_CENTS / 100;
        }
    }

    @ModelAttribute("trialDays")
    public int trialDays() {
        try {
            return subscriptions.platformSettings().trialDays();
        } catch (Exception e) {
            return SubscriptionPlan.TRIAL_DAYS;
        }
    }
}
