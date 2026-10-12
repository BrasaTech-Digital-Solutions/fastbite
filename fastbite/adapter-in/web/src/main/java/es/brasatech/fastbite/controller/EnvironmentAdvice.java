package es.brasatech.fastbite.controller;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tells every page which environment it belongs to, so the sandbox can never be mistaken for
 * production: its pages carry a banner, a favicon of their own and "[Sandbox]" in the tab title.
 * Read straight from the configuration, so every web slice has it.
 */
@ControllerAdvice
public class EnvironmentAdvice {

    /**
     * @param sandbox       pages of the sandbox
     * @param productionUrl where a linked sandbox sends people to production; null when standalone
     * @param practice      production with a sandbox its owners can enter
     */
    public record PlatformView(boolean sandbox, String productionUrl, boolean practice) {
    }

    private final PlatformView view;

    public EnvironmentAdvice(Environment configuration) {
        boolean sandbox = !"production".equalsIgnoreCase(configuration.getProperty("fastbite.environment", "sandbox").strip());
        String link = configuration.getProperty("fastbite.link.url", "").strip();
        this.view = new PlatformView(sandbox, sandbox && !link.isEmpty() ? link : null, !sandbox && !link.isEmpty());
    }

    @ModelAttribute("platform")
    public PlatformView platform() {
        return view;
    }
}
