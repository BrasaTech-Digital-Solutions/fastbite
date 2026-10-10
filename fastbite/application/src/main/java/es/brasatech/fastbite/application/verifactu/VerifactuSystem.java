package es.brasatech.fastbite.application.verifactu;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The billing software as VERI*FACTU records name it (SistemaInformatico): who makes it, its name,
 * identifier and version. The producer also signs the declaración responsable for this version.
 */
@Component
public class VerifactuSystem {

    private final String producerName;
    private final String producerNif;
    private final String systemName;
    private final String systemId;
    private final String version;
    private final String operationDescription;

    public VerifactuSystem(
            @Value("${fastbite.verifactu.producer-name:Brasatech}") String producerName,
            @Value("${fastbite.verifactu.producer-nif:}") String producerNif,
            @Value("${fastbite.verifactu.system-name:FastBite}") String systemName,
            @Value("${fastbite.verifactu.system-id:FB}") String systemId,
            @Value("${fastbite.verifactu.version:1.0}") String version,
            @Value("${fastbite.verifactu.operation-description:Servicios de restauración}") String operationDescription) {
        this.producerName = producerName;
        this.producerNif = producerNif;
        this.systemName = systemName;
        this.systemId = systemId;
        this.version = version;
        this.operationDescription = operationDescription;
    }

    public String producerName() { return producerName; }

    public String producerNif() { return producerNif; }

    public String systemName() { return systemName; }

    public String systemId() { return systemId; }

    public String version() { return version; }

    /** The DescripcionOperacion of every invoice. */
    public String operationDescription() { return operationDescription; }
}
