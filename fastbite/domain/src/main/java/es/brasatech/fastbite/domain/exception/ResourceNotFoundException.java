package es.brasatech.fastbite.domain.exception;

/** A resource is absent from the current tenant's accessible scope. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException() {
        super("Resource not found");
    }
}
