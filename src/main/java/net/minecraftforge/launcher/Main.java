/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.launcher;

import joptsimple.AbstractOptionSpec;
import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.util.data.json.JsonData;
import net.minecraftforge.util.data.json.MinecraftVersion;
import net.minecraftforge.util.hash.HashFunction;
import net.minecraftforge.util.logging.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Main {
    static final Logger LOGGER = Logger.create();
    private static final boolean DISABLE_ASSETS = Boolean.getBoolean("net.minecraftforge.launcher.skip.assets");
    private static final boolean DISABLE_COREMOD_SEARCH = Boolean.getBoolean("net.minecraftforge.launcher.skip.coremod.search");

    public static void main(String[] args) throws Throwable {
        long start = System.currentTimeMillis();
        Launcher launcher;
        try {
            LOGGER.capture();
            launcher = run(args);
        } catch (Throwable e) {
            LOGGER.release();
            throw e;
        }

        long total = System.currentTimeMillis() - start;
        if (LOGGER.isCapturing()) {
            LOGGER.drop();
            LOGGER.getInfo().print("Slime Launcher setup is up-to-date");
        } else {
            LOGGER.getInfo().print("Slime Launcher has finished setting up");
        }
        LOGGER.getInfo().println(", took " + total + "ms\n");

        launcher.run();
    }

    private static Launcher run(String[] rawArgs) throws Throwable {
        OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        // help message
        AbstractOptionSpec<Void> helpO = parser
            .accepts("help", "Displays this help message and exits")
            .forHelp();

        // cache directory
        ArgumentAcceptingOptionSpec<File> cache0 = parser
            .accepts("cache", "Directory to store launcher metadata")
            .withRequiredArg().ofType(File.class);

        // assets directory
        ArgumentAcceptingOptionSpec<File> assetsO = parser
            .accepts("assets", "Directory to store assets")
            .withRequiredArg().ofType(File.class).defaultsTo(Constants.ASSETS_DIR);

        // assets repo
        ArgumentAcceptingOptionSpec<String> assetsRepo0 = parser
            .accepts("assets-repo", "The assets repository (download server)")
            .withRequiredArg().ofType(String.class).defaultsTo(Constants.RESOURCES_URL);

        // metadata
        ArgumentAcceptingOptionSpec<File> metadataO = parser
            .accepts("metadata", "The metadata directory or zip to use for runs")
            .withRequiredArg().ofType(File.class);

        // main
        ArgumentAcceptingOptionSpec<String> mainClassO = parser
            .accepts("main", "The main class to run")
            .withRequiredArg().ofType(String.class);

        ArgumentAcceptingOptionSpec<File> toObfO = parser
            .accepts("to-obf", "Mapping file containing mapped to obfuscated names")
            .withRequiredArg().ofType(File.class);
        ArgumentAcceptingOptionSpec<File> toSrgO = parser
            .accepts("to-srg", "Mapping file containing mapped to SRG names")
            .withRequiredArg().ofType(File.class);

        ArgumentAcceptingOptionSpec<String> sideO = parser
            .accepts("launcher-side", "The 'side' to launch, `client|server`. If `server`, asset downloading, natives, and other features will be skipped")
            .withRequiredArg().ofType(String.class);

        Package pkg = Main.class.getPackage();
        LOGGER.info(pkg.getImplementationTitle() + ' ' + pkg.getImplementationVersion());

        SplitArgs _split = new SplitArgs(rawArgs);
        String[] slArgs = _split.sl;
        String[] mcArgs = _split.mc;

        OptionSet options = parser.parse(slArgs);
        if (options.has(helpO)) {
            parser.printHelpOn(LOGGER.getInfo());
            LOGGER.info("To pass arguments into the main class,\n" +
                "add '--' after the Slime Launcher arguments,\n" +
                "followed by your main arguments.");
            throw new IllegalArgumentException("Incomplete or invalid arguments");
        }

        File cache = options.valueOf(cache0);
        File assets = options.valueOf(assetsO);
        String assetsRepo = options.valueOf(assetsRepo0);
        File metadata = options.valueOf(metadataO);
        String mainClass = options.valueOf(mainClassO);
        File toObf = options.valueOf(toObfO);
        File toSrg = options.valueOf(toSrgO);

        LOGGER.info("Main Class: " + mainClass);
        boolean isLegacyDev = LegacyDev.is(mainClass);
        boolean isUserDev = LegacyDev.isUserDev(mainClass);
        if (isLegacyDev || isUserDev)
            mcArgs = LegacyDev.enhanceArgs(mainClass, mcArgs);

        boolean isClient = detectClientSide(options.valueOf(sideO), mainClass, mcArgs);

        if (isLegacyDev)
            mainClass = LegacyDev.getMainClass();
        else if (isUserDev) { // This is only used for 1.14->1.16, and only ever targeted cpw.mods.modlauncher.Launcher
            mainClass = "cpw.mods.modlauncher.Launcher";
            LOGGER.info("Overwriting Main Class: " + mainClass);
        }

        MinecraftVersion versionJson;
        if (metadata.isDirectory()) {
            File versionJsonFile = new File(metadata, "minecraft/version.json");
            if (versionJsonFile.exists()) versionJson = JsonData.minecraftVersion(versionJsonFile);
            else throw new FileNotFoundException("Missing minecraft/version.json in " + metadata.getAbsolutePath());
        } else if (metadata.isFile()) {
            try (ZipFile zip = new ZipFile(metadata)) {
                File versionJsonFile = extract(zip, "minecraft/version.json", cache);
                versionJson = JsonData.minecraftVersion(versionJsonFile);
            }
        } else {
            throw new IllegalArgumentException("Invalid metadata path: " + metadata.getAbsolutePath());
        }

        if (isClient) {
            DownloadAssets.checkAssets(assetsRepo, assets, versionJson, DISABLE_ASSETS);
            LegacyDev.setupNatives(versionJson, new File(cache, "natives"));
        }

        if (toObf != null && toSrg != null) {
            String hashObf = HashFunction.sha1().hash(toObf);
            String hashSrg = HashFunction.sha1().hash(toSrg);
            String hash = HashFunction.sha1().hash(hashObf + hashSrg);
            File dir = new File(cache, "mappings/srgs/" + hash);
            if (!dir.exists())
                dir.mkdirs();

            // Runtime deobf maps for FG2-era FML. Stock FMLDeobfuscatingRemapper parses classic
            // SRG (CL:/FD:/MD:); some forks parse TSRG instead. Detect from the remapper on the
            // launch classpath — do not special-case loader names.
            IMappingFile.Format deobfFormat = detectRuntimeDeobfMapFormat();
            LOGGER.info("Runtime deobf map format: " + deobfFormat);

            IMappingFile toObfMap = IMappingFile.load(toObf); // m->o
            IMappingFile toSrgMap = IMappingFile.load(toSrg); // m->s
            System.setProperty("net.minecraftforge.gradle.GradleStart.srgDir", dir.getCanonicalPath());
            setup(new File(dir, "notch-srg.srg"), "notch-srg", () -> toObfMap.reverse().chain(toSrgMap), IMappingFile.Format.SRG);
            setup(new File(dir, "notch-mcp.srg"), "notch-mcp", toObfMap::reverse, IMappingFile.Format.SRG);
            File srgMcp = new File(dir, "srg-mcp.srg");
            setup(srgMcp, "srg-mcp", toSrgMap::reverse, deobfFormat);
            setup(new File(dir, "mcp-srg.srg"), "mcp-srg", () -> toSrgMap, IMappingFile.Format.SRG);
            setup(new File(dir, "mcp-notch.srg"), "mcp-notch", () -> toObfMap, IMappingFile.Format.SRG);
            //System.setProperty("net.minecraftforge.gradle.GradleStart.csvDir", CSV_DIR.getCanonicalPath());

            // FG leaves MCP_TO_SRG={mcp_to_srg} as a passthrough for us to fill after generating SRG files.
            // Legacy boot mains (LegacyDev / Cleanroom) read that env var and set the srg-mcp system property.
            // Prefer the property already set by setup(); also push env when the placeholder survived.
            String mcpToSrgEnv = System.getenv("MCP_TO_SRG");
            if (mcpToSrgEnv != null && mcpToSrgEnv.contains("{mcp_to_srg}"))
                setEnv("MCP_TO_SRG", srgMcp.getCanonicalPath());
        }

        if (!DISABLE_COREMOD_SEARCH)
            FG2Hacks.searchCoremods(versionJson.id);

        LOGGER.info("Looking for main class: " + mainClass);
        Class<?> main = findMainClass(mainClass);
        MethodHandle mainMethod = findMainMethod(main);

        LOGGER.info("Sanitizing Minecraft arguments");
        for (int i = 0; i < mcArgs.length; i++) {
            mcArgs[i] = mcArgs[i]
                .replace("{asset_index}", versionJson.assetIndex.id)
                .replace("{assets_root}", assets.getAbsolutePath());
        }

        return new Launcher(mainClass, mainMethod, mcArgs);
    }

    private static void setup(File file, String name, Supplier<IMappingFile> map, IMappingFile.Format format) throws IOException {
        System.setProperty("net.minecraftforge.gradle.GradleStart.srg." + name, file.getCanonicalPath());
        if (file.exists() && !isMappingFormat(file, format)) {
            LOGGER.info("Regenerating " + file.getName() + " as " + format + " (cached file was a different format)");
            if (!file.delete())
                throw new IOException("Could not delete stale mapping file: " + file.getAbsolutePath());
        }
        if (!file.exists())
            map.get().write(file.toPath(), format);
    }

    /**
     * Choose the on-disk format for {@code srg-mcp} based on the FML deobf remapper present on the
     * launch classpath.
     * <ul>
     *   <li>Stock Forge FML splits on {@code ": "} and matches {@code CL}/{@code FD}/{@code MD} → {@link IMappingFile.Format#SRG}</li>
     *   <li>Forks that dropped those tags and parse tab-indented TSRG members → {@link IMappingFile.Format#TSRG}</li>
     * </ul>
     * Default is classic SRG when the remapper is absent or unrecognizable.
     */
    private static IMappingFile.Format detectRuntimeDeobfMapFormat() {
        final String remapper = "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper";
        try {
            Class<?> cls = Class.forName(remapper, false, Main.class.getClassLoader());
            try (InputStream in = cls.getResourceAsStream("/" + remapper.replace('.', '/') + ".class")) {
                if (in == null)
                    return IMappingFile.Format.SRG;
                byte[] bytes = readAllBytes(in);
                // Stock embeds the SRG type tags as Utf8 constants; TSRG-style parsers do not.
                boolean hasSrgTags = classHasUtf8(bytes, "CL") && classHasUtf8(bytes, "FD") && classHasUtf8(bytes, "MD");
                return hasSrgTags ? IMappingFile.Format.SRG : IMappingFile.Format.TSRG;
            }
        } catch (ClassNotFoundException | IOException e) {
            return IMappingFile.Format.SRG;
        }
    }

    /** True if {@code file} already looks like {@code format} (so we can reuse the cache). */
    private static boolean isMappingFormat(File file, IMappingFile.Format format) throws IOException {
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(file.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty())
                    continue;
                boolean prefixed = line.startsWith("CL:") || line.startsWith("PK:") || line.startsWith("FD:") || line.startsWith("MD:");
                switch (format) {
                    case SRG:
                    case XSRG:
                        return prefixed;
                    case TSRG:
                    case TSRG2:
                        return !prefixed;
                    default: // unknown: do not force regenerate
                        return true;
                }
            }
        }
        return false;
    }

    /** Read an entire stream into a byte array (Java 8 compatible). */
    private static byte[] readAllBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1)
            out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Scan a class file's constant pool for a Utf8 entry equal to {@code value}. */
    private static boolean classHasUtf8(byte[] classFile, String value) {
        byte[] utf = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // CONSTANT_Utf8_info: tag=1, u2 length, bytes
        for (int i = 0; i + 3 + utf.length <= classFile.length; i++) {
            if (classFile[i] != 1)
                continue;
            int len = ((classFile[i + 1] & 0xFF) << 8) | (classFile[i + 2] & 0xFF);
            if (len != utf.length)
                continue;
            boolean match = true;
            for (int j = 0; j < utf.length; j++) {
                if (classFile[i + 3 + j] != utf[j]) {
                    match = false;
                    break;
                }
            }
            if (match)
                return true;
        }
        return false;
    }

    /**
     * Best-effort process env mutation so LegacyDev/Cleanroom see a resolved MCP_TO_SRG.
     * May fail on modern JDKs without --add-opens; callers should also set the GradleStart system property.
     */
    @SuppressWarnings("unchecked")
    private static void setEnv(String key, String value) {
        try {
            Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
            for (String fieldName : new String[] { "theEnvironment", "theCaseInsensitiveEnvironment" }) {
                try {
                    Field field = pe.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    Object map = field.get(null);
                    if (map instanceof Map)
                        ((Map<String, String>) map).put(key, value);
                } catch (Exception ignored) {
                    // field absent or inaccessible on this JDK
                }
            }
        } catch (Exception e) {
            try {
                Map<String, String> env = System.getenv();
                Field m = env.getClass().getDeclaredField("m");
                m.setAccessible(true);
                ((Map<String, String>) m.get(env)).put(key, value);
            } catch (Exception e2) {
                LOGGER.warn("Could not set env " + key + " (JDK module lockdown); relying on system properties");
            }
        }
    }

    private static Class<?> findMainClass(String mainClass) {
        try {
            return Class.forName(mainClass);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not find main class!", e);
        }
    }

    private static MethodHandle findMainMethod(Class<?> main) {
        MethodHandles.Lookup lookup = findLookup();
        try {
            return lookup.findStatic(main, "main", MethodType.methodType(void.class, String[].class));
        } catch (IllegalAccessException e) {
            // Old versions of the bootstrap lib didn't export the class, so lets try with some basic reflection
            try {
                Method mtd = main.getDeclaredMethod("main", String[].class);
                return lookup.unreflect(mtd);
            } catch (NoSuchMethodException | IllegalAccessException ex) {
                IllegalStateException error = new IllegalStateException("Could not find main(String[]) in " + main.getName() + "!\r\n" +
                    "Try running with --add-opens java.base/java.lang.invoke=ALL-UNNAMED", e);
                error.addSuppressed(ex);
                throw error;
            }
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Could not find main(String[]) in " + main.getName() + '!', e);
        }
    }

    private static MethodHandles.Lookup findLookup() {
        // try and find the privileged lookup, this typically needs a command line argument but it **should** detect it from our Manifest
        try {
            Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            field.setAccessible(true);
            return (MethodHandles.Lookup)field.get(null);
        } catch (Exception ignored) {
            // This is expected if we don't have --add-opens java.base/java.lang.invoke=net.minecraftforge.launcher
        }
        return MethodHandles.lookup();
    }

    private static boolean detectClientSide(String arg, String mainClass, String[] mcArgs) {
        if (arg != null) {
            if ("client".equals(arg))
                return true;
            if ("server".equals(arg))
                return true;
            throw new IllegalArgumentException("Invalid --launcher-side argument: " + arg);
        }

        if (mainClass.toLowerCase(Locale.ENGLISH).contains("server"))
            return false;

        for (int x = 0; x < mcArgs.length; x++) {
            if (mcArgs[x].startsWith("--launchTarget")) {
                String value = mcArgs[x].indexOf('=') == 15 ? mcArgs[x].substring(16) : x < mcArgs.length - 1 ? mcArgs[x + 1] : null;
                return value == null || !value.toLowerCase(Locale.ENGLISH).contains("server");
            }
        }
        return true;
    }

    private static final class Launcher {
        private final String name;
        private final MethodHandle main;
        private final String[] args;

        private Launcher(String name, MethodHandle main, String[] args) {
            this.name = name;
            this.main = main;
            this.args = args;
        }

        @SuppressWarnings("ConfusingArgumentToVarargsMethod")
        private void run() throws Throwable {
            LOGGER.info("Launching using main class: " + this.name);
            this.main.invokeExact(this.args);
        }
    }

    private static final class SplitArgs {
        private final String[] sl;
        private final String[] mc;

        private SplitArgs(String[] args) {
            // we're looking for the first "--"
            int splitIdx = -1;
            for (int i = 0; i < args.length; i++) {
                if ("--".equals(args[i])) {
                    splitIdx = i;
                    break;
                }
            }

            if (splitIdx < 0) {
                this.sl = args;
                this.mc = new String[0];
            } else {
                this.sl = new String[splitIdx];
                this.mc = new String[args.length - splitIdx - 1];
                System.arraycopy(args, 0, this.sl, 0, splitIdx);
                System.arraycopy(args, splitIdx + 1, this.mc, 0, args.length - splitIdx - 1);
            }
        }
    }

    private static File extract(ZipFile zip, String name, File cache) throws IOException {
        File metadataDir = new File(cache, "metadata");
        if (!metadataDir.exists() && !metadataDir.mkdirs())
            throw new IllegalStateException("Failed to create directory: " + metadataDir.getAbsolutePath());

        ZipEntry entry = zip.getEntry(name);
        if (entry == null)
            throw new FileNotFoundException("Missing " + name + " in " + zip.getName());

        File output = new File(metadataDir, name);
        File outputDir = output.getParentFile();
        if (!outputDir.exists() && !outputDir.mkdirs())
            throw new IllegalStateException("Failed to create directory: " + outputDir.getAbsolutePath());

        // InputStream#transferTo(OutputStream)
        try (FileOutputStream out = new FileOutputStream(output)) {
            InputStream stream = zip.getInputStream(entry);
            byte[] buf = new byte[8192];
            int length;
            while ((length = stream.read(buf)) != -1) {
                out.write(buf, 0, length);
            }
        }

        return output;
    }
}
