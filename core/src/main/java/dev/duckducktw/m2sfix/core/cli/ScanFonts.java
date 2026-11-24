package dev.duckducktw.m2sfix.core.cli;

import dev.duckducktw.m2sfix.core.ScanResult;
import dev.duckducktw.m2sfix.core.TtfScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Command-line font scanner: prints the verdict for every {@code .ttf}/{@code .otf}
 * file given on the command line (directories are walked recursively).
 *
 * <pre>
 * java -cp m2sfix-core.jar dev.duckducktw.m2sfix.core.cli.ScanFonts font.ttf pack/assets
 * </pre>
 *
 * <p>Exit code is {@code 0} when nothing was flagged, {@code 1} when at least one
 * font was flagged, {@code 2} on a usage error.</p>
 */
public final class ScanFonts {

    private ScanFonts() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: ScanFonts <font-or-directory>...");
            System.exit(2);
        }

        List<Path> fonts = new ArrayList<>();
        for (String arg : args) {
            Path path = Path.of(arg);
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.walk(path)) {
                    stream.filter(Files::isRegularFile)
                            .filter(ScanFonts::looksLikeFont)
                            .forEach(fonts::add);
                } catch (IOException e) {
                    System.err.println("cannot walk " + path + ": " + e.getMessage());
                }
            } else if (Files.isRegularFile(path)) {
                fonts.add(path);
            } else {
                System.err.println("not found: " + path);
            }
        }
        Collections.sort(fonts);

        Map<String, Integer> counts = new TreeMap<>();
        int flagged = 0;
        for (Path font : fonts) {
            ScanResult result;
            try {
                result = TtfScanner.scan(Files.readAllBytes(font));
            } catch (IOException e) {
                result = new ScanResult(ScanResult.Verdict.UNPARSABLE, "cannot read: " + e.getMessage());
            }
            counts.merge(result.verdict().name(), 1, Integer::sum);
            if (result.verdict() != ScanResult.Verdict.CLEAN) {
                flagged++;
                System.out.println(result.verdict() + "\t" + font + "\t" + result.reason());
            }
        }

        System.out.println();
        System.out.println("scanned=" + fonts.size() + " flagged=" + flagged + " " + counts);
        System.exit(flagged == 0 ? 0 : 1);
    }

    private static boolean looksLikeFont(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc");
    }
}
