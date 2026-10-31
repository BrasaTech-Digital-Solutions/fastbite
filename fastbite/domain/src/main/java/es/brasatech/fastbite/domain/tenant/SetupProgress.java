package es.brasatech.fastbite.domain.tenant;

public record SetupProgress(
        boolean started,
        boolean serviceReviewed,
        boolean previewReviewed,
        boolean dismissed,
        boolean practiceGuestViewed,
        boolean practiceTableOrdered,
        boolean practiceKitchenPrepared,
        boolean practiceCounterCharged,
        boolean practiceMenuPersonalized,
        boolean practiceTeamTested) {

    public static final SetupProgress EMPTY = new SetupProgress(false, false, false, false, false, false, false, false, false, false);

    public SetupProgress(boolean started, boolean serviceReviewed, boolean previewReviewed, boolean dismissed) {
        this(started, serviceReviewed, previewReviewed, dismissed, false, false, false, false, false, false);
    }

    public int practiceCompletedCount() {
        int count = 0;
        if (practiceGuestViewed) count++;
        if (practiceTableOrdered) count++;
        if (practiceKitchenPrepared) count++;
        if (practiceCounterCharged) count++;
        if (practiceMenuPersonalized) count++;
        if (practiceTeamTested) count++;
        return count;
    }

    public boolean practiceReady() {
        return practiceCompletedCount() == 6;
    }
}
