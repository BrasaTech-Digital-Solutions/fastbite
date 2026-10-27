package es.brasatech.fastbite;

import es.brasatech.fastbite.service.ClasspathDeclarationArchive;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Never ship a release whose declaración responsable isn't signed (advisor's answer D3): the pipeline runs the tests
 * before it deploys, so a release version (x.y.z, not -SNAPSHOT) without its signed PDF stops here.
 * Sign it with scripts/sign-declaration.sh x.y.z.
 */
class SignedDeclarationGuardTest {

    @Test
    @SuppressWarnings("unchecked")
    void aReleaseShipsItsOwnSignedDeclaration() throws Exception {
        Map<String, Object> yaml;
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            yaml = new Yaml().load(in);
        }
        Map<String, Object> verifactu = (Map<String, Object>) ((Map<String, Object>) yaml.get("fastbite")).get("verifactu");
        Matcher placeholder = Pattern.compile("^\\$\\{[A-Z0-9_]+:(.*)}$").matcher(String.valueOf(verifactu.get("version")));
        String version = placeholder.matches() ? placeholder.group(1) : String.valueOf(verifactu.get("version"));

        if (!version.matches("\\d+\\.\\d+\\.\\d+")) {
            assertThat(version).as("a development version").endsWith("-SNAPSHOT");
            return;
        }
        assertThat(new ClasspathDeclarationArchive().forVersion(version))
                .as("FastBite " + version + " is a release: sign its declaración responsable with "
                        + "scripts/sign-declaration.sh " + version + " and commit declarations/")
                .isPresent();
    }
}
