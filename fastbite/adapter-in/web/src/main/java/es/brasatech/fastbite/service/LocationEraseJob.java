package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.tenant.LocationLifecycleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Permanently erases locations whose undo window has expired and purges expired name holds.
 */
@Component
public class LocationEraseJob {

    private final LocationLifecycleService lifecycle;
    private final boolean inProcess;

    public LocationEraseJob(
            LocationLifecycleService lifecycle,
            @Value("${fastbite.jobs.in-process:true}") boolean inProcess) {
        this.lifecycle = lifecycle;
        this.inProcess = inProcess;
    }

    @Scheduled(
            initialDelayString = "${fastbite.locations.erase-delay:PT5M}",
            fixedDelayString = "${fastbite.locations.erase-delay:PT5M}")
    public void scheduled() {
        if (inProcess) {
            run();
        }
    }

    public int run() {
        return lifecycle.eraseDueLocations();
    }
}
