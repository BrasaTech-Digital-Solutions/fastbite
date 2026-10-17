package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.platform.SandboxProvisioningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends production's new locations to the sandbox, and again those it could not take yet. */
@Component
@Slf4j
public class SandboxProvisioningJob {

    private final SandboxProvisioningService provisioning;
    private final boolean inProcess;

    public SandboxProvisioningJob(SandboxProvisioningService provisioning) {
        this(provisioning, true);
    }

    @Autowired
    public SandboxProvisioningJob(SandboxProvisioningService provisioning,
            @Value("${fastbite.jobs.in-process:true}") boolean inProcess) {
        this.provisioning = provisioning;
        this.inProcess = inProcess;
    }

    @Scheduled(initialDelayString = "${fastbite.link.provision-delay:PT15S}",
            fixedDelayString = "${fastbite.link.provision-delay:PT15S}")
    public void sendDue() {
        if (!inProcess) {
            return;
        }
        run();
    }

    public int run() {
        if (!provisioning.active()) {
            return 0;
        }
        try {
            return provisioning.sendDue();
        } catch (RuntimeException e) {
            log.warn("Locations could not be sent to the sandbox: {}", e.getMessage());
            return 0;
        }
    }
}

