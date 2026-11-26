package dev.aegle.minecraft2shellfixng;

import org.lwjgl.system.Configuration;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MixinConfigPlugin implements IMixinConfigPlugin {

    private static Path libraryPath;

    private static synchronized Path getLibraryPath() throws IOException {
        if(libraryPath != null){
            return libraryPath;
        }
        String arch = switch (System.getProperty("os.arch")
                .toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> throw new UnsupportedOperationException(
                    "Unsupported architecture: "
                            + System.getProperty("os.arch"));
        };
        String fileName = System.mapLibraryName("freetype-" + arch);
        String resourceName = "/natives/" + fileName;
        try (InputStream in = MixinConfigPlugin.class.getResourceAsStream(resourceName)) {
            if(in == null){
                throw new UnsupportedOperationException("Native library unavailable: " + resourceName);
            }
            Path dir = Files.createTempDirectory("minecraft2shellfixng-"), target = dir.resolve(fileName).toAbsolutePath();
            dir.toFile().deleteOnExit();
            target.toFile().deleteOnExit();
            Files.copy(in, target);
            libraryPath = target;
            return libraryPath;
        }
    }

    @Override
    public void onLoad(String s) {
        try {
            Configuration.FREETYPE_LIBRARY_NAME.set(getLibraryPath().toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getRefMapperConfig() {
        return "";
    }

    @Override
    public boolean shouldApplyMixin(String s, String s1) {
        return false;
    }

    @Override
    public void acceptTargets(Set<String> set, Set<String> set1) {

    }

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }

    @Override
    public void postApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {

    }
}
