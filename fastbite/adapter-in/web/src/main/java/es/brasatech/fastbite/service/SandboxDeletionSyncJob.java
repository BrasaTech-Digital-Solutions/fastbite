package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.platform.SandboxDeletionSyncService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries pending sandbox deletions after production deleted or closed a location.
 */
@Component
public class SandboxDeletionSyncJob {

    private final SandboxDeletionSyncService syncService;
    private final boolean inProcess;

    public SandboxDeletionSyncJob(
            SandboxDeletionSyncService syncService,
            @Value("${fastbite.jobs.in-process:true}") boolean inProcess) {
        this.syncService = syncService;
        this.inProcess = inProcess;
    }

    @Scheduled(
            initialDelayString = "${fastbite.link.provision-delay:PT15S}",
            fixedDelayString = "${fastbite.link.provision-delay:PT15S}")
    public void scheduled() {
        if (inProcess) {
            run();
        }
    }

    public int run() {
        return syncService.sendDue();
    }
}
