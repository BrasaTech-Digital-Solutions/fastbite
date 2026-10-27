package es.brasatech.fastbite.tools.declaration;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.AbstractLinkBuilder;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the app's own declaration template (templates/fastfood/declaration.html) for a signed version, so the
 * signed PDF says exactly what the app shows.
 */
public final class DeclarationRenderer {

    static final String TEMPLATES = "fastbite/adapter-in/web/src/main/resources/templates/";
    static final String STYLES = "fastbite/adapter-in/web/src/main/resources/static/css/legal.css";

    /** The template reads these the way it reads the app's VerifactuSystem. */
    public record SystemView(String systemName, String systemId, String version, String producerName, String producerNif) {
    }

    /** The template reads this the way it reads the app's VerifactuDeclaration: complete and signed. */
    public record DeclarationView(String producerAddress, String place, String date, String signatory) {
        public boolean onlyVerifactu() { return true; }
        public boolean multipleTaxpayers() { return true; }
        public boolean signed() { return true; }
        public List<String> missing() { return List.of(); }
    }

    private DeclarationRenderer() {
    }

    /** Writes declaration.html and css/legal.css into the folder, ready for Chrome to print. */
    public static Path render(Path repo, DeclarationDetails details, Path folder) throws IOException {
        FileTemplateResolver resolver = new FileTemplateResolver();
        resolver.setPrefix(repo.resolve(TEMPLATES).toString() + "/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setLinkBuilder(new RelativeLinks());

        Context context = new Context(Locale.forLanguageTag("es-ES"));
        context.setVariable("system", new SystemView(details.systemName(), details.systemId(), details.version(),
                details.producerName(), details.producerNif()));
        context.setVariable("declaration", new DeclarationView(details.producerAddress(), details.place(),
                details.date(), details.signatory()));
        context.setVariable("producerEmail", details.producerEmail());
        context.setVariable("producerWebsite", details.producerWebsite());
        String html = engine.process("fastfood/declaration", context);

        for (String draft : List.of("Borrador", "class=\"draft\"", "[pendiente", "[lugar]", "[fecha de firma]", "missing")) {
            if (html.contains(draft)) {
                throw new IllegalStateException("The rendered declaration is incomplete (it contains \"" + draft + "\")");
            }
        }
        Files.createDirectories(folder.resolve("css"));
        Files.copy(repo.resolve(STYLES), folder.resolve("css/legal.css"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Path page = folder.resolve("declaration.html");
        Files.writeString(page, html, StandardCharsets.UTF_8);
        return page;
    }

    /** "@{/css/legal.css}" becomes "css/legal.css", next to the rendered page. */
    static final class RelativeLinks extends AbstractLinkBuilder {
        @Override
        public String buildLink(IExpressionContext context, String base, Map<String, Object> parameters) {
            return base == null ? null : base.startsWith("/") ? base.substring(1) : base;
        }
    }
}
