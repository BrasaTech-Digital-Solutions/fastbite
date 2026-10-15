package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.platform.SandboxProvisioningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends production's new locations to the sandbox, and again those it could not take yet. */
@Component
@Slf4j
public class SandboxProvisioningJob {

    private final SandboxProvisioningService provisioning;

    public SandboxProvisioningJob(SandboxProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Scheduled(initialDelayString = "${fastbite.link.provision-delay:PT15S}",
            fixedDelayString = "${fastbite.link.provision-delay:PT15S}")
    public void sendDue() {
        try {
            provisioning.sendDue();
        } catch (RuntimeException e) {
            log.warn("Locations could not be sent to the sandbox: {}", e.getMessage());
        }
    }
}
