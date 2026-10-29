package Services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against Panache queries that declare positional parameters but never
 * bind them.
 *
 * <p>{@code Entity.find("... ?1 ...")} with no argument list compiles fine and
 * only fails at runtime, inside the transaction, with
 * {@code QueryParameterException: No argument for ordinal parameter '?1'}.
 *
 * <p>That is what took live TV down: {@code XtreamSessionService.bumpLiveActivity}
 * was called for every HLS segment, so the playlist loaded and then every
 * segment answered HTTP 500. Because the query failed before executing, it did
 * not matter whether a session row existed.
 *
 * <p>The project has no {@code @QuarkusTest} infrastructure, so the invariant is
 * asserted over the sources rather than by executing the query.
 */
public class PanacheQueryBindingTest {

    /** {@code .find("...")} with no argument list — any {@code ?N} inside is unbound. */
    private static final Pattern UNBOUND_FIND =
            Pattern.compile("\\.find\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");

    private static final Pattern POSITIONAL = Pattern.compile("\\?\\d");

    @Test
    void everyPanacheFindBindsItsPositionalParameters() throws IOException {
        Path root = Paths.get("src", "main", "java");
        assertTrue(Files.isDirectory(root),
                "source root not found from " + Paths.get("").toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList())) {
                String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher m = UNBOUND_FIND.matcher(src);
                while (m.find()) {
                    if (!POSITIONAL.matcher(m.group(1)).find()) {
                        continue;
                    }
                    int line = (int) src.substring(0, m.start())
                            .chars().filter(c -> c == '\n').count() + 1;
                    offenders.add(root.relativize(file) + ":" + line + " -> " + m.group(1));
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "Panache .find(\"...?N...\") without bound arguments — pass the arguments "
                        + "after the query string: " + offenders);
    }
}
