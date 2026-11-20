package es.brasatech.fastbite.application.digitalsign;

import java.util.List;

/** Read-only view of a Digital Sign template returned by {@code GET /templates/{id}}. */
public record DigitalSignTemplate(
        String id,
        String name,
        List<TemplateSigner> signers,
        List<TemplateField> fields) {

    public DigitalSignTemplate {
        signers = signers == null ? List.of() : List.copyOf(signers);
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public record TemplateSigner(
            int index,
            String role,
            boolean certificate,
            List<TemplateRequestedFile> files) {

        public TemplateSigner {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    public record TemplateRequestedFile(
            String label,
            boolean required) {
    }

    public record TemplateField(
            String key,
            String label,
            String type,
            boolean required,
            String validation) {
    }
}
