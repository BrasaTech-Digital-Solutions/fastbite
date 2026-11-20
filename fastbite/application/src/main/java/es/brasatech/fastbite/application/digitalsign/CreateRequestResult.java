package es.brasatech.fastbite.application.digitalsign;

/** Result of {@code POST /requests}, preserving whether Digital Sign served an idempotent replay. */
public record CreateRequestResult(
        DigitalSignRequest request,
        boolean idempotentReplayed) {
}
