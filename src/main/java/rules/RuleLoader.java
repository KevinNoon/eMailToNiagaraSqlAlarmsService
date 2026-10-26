package rules;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

// Loads/hot-reloads the rules/ directory. One bad file never aborts loading the others, and
// a failed reload never wipes out a previously-good set of loaded rules.
public class RuleLoader {
    private static final Logger logger = Logger.getLogger(RuleLoader.class.getName());

    private final Path rulesDirectory;
    private List<RuleSet> loadedRules = new ArrayList<>();
    private Map<String, Long> lastKnownMtimes = new HashMap<>();

    public RuleLoader(String rulesDirectoryPath) {
        this.rulesDirectory = Paths.get(rulesDirectoryPath);
    }

    public synchronized List<RuleSet> loadAll() {
        List<Path> files = listRuleFiles();
        List<RuleSet> rules = new ArrayList<>();
        Map<String, Long> mtimes = new HashMap<>();
        for (Path file : files) {
            try {
                rules.add(parseFile(file));
                mtimes.put(file.toString(), Files.getLastModifiedTime(file).toMillis());
            } catch (Exception e) {
                logger.warning("[RuleLoader] Skipping invalid rule file " + file + ": " + e.getMessage());
            }
        }
        loadedRules = rules;
        lastKnownMtimes = mtimes;
        return loadedRules;
    }

    public synchronized List<RuleSet> maybeReload(boolean reloadOnChange) {
        if (loadedRules.isEmpty()) {
            return loadAll();
        }
        if (!reloadOnChange) {
            return loadedRules;
        }
        if (!directoryChanged()) {
            return loadedRules;
        }

        List<Path> files = listRuleFiles();
        List<RuleSet> rules = new ArrayList<>();
        Map<String, Long> mtimes = new HashMap<>();
        for (Path file : files) {
            try {
                rules.add(parseFile(file));
                mtimes.put(file.toString(), Files.getLastModifiedTime(file).toMillis());
            } catch (Exception e) {
                logger.warning("[RuleLoader] Skipping invalid rule file " + file + ": " + e.getMessage());
            }
        }
        if (rules.isEmpty()) {
            logger.severe("[RuleLoader] Reload found zero valid rule files; keeping previously-loaded rules in place.");
            return loadedRules;
        }
        loadedRules = rules;
        lastKnownMtimes = mtimes;
        return loadedRules;
    }

    public synchronized List<RuleSet> getLoadedRules() {
        return loadedRules;
    }

    private boolean directoryChanged() {
        List<Path> files = listRuleFiles();
        if (files.size() != lastKnownMtimes.size()) {
            return true;
        }
        for (Path file : files) {
            Long known = lastKnownMtimes.get(file.toString());
            if (known == null) {
                return true;
            }
            try {
                if (Files.getLastModifiedTime(file).toMillis() != known) {
                    return true;
                }
            } catch (IOException e) {
                return true;
            }
        }
        return false;
    }

    private List<Path> listRuleFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(rulesDirectory)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rulesDirectory, "*.y*ml")) {
            for (Path path : stream) {
                files.add(path);
            }
        } catch (IOException e) {
            logger.warning("[RuleLoader] Could not list rules directory " + rulesDirectory + ": " + e.getMessage());
            return files;
        }
        // Sorted by filename ascending so "first match wins" is operator-controllable by
        // filename rather than filesystem iteration order.
        files.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        return files;
    }

    @SuppressWarnings("unchecked")
    static RuleSet parseFile(Path file) throws Exception {
        try (InputStream input = Files.newInputStream(file)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(input);
            if (!(loaded instanceof Map)) {
                throw new IllegalArgumentException("Top-level YAML content is not a mapping");
            }
            return RuleSet.fromMap((Map<String, Object>) loaded, file.getFileName().toString());
        }
    }
}
