/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.launcher;

import joptsimple.NonOptionArgumentSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import net.minecraftforge.util.data.json.MinecraftVersion;
import net.minecraftforge.util.logging.Logger;
import net.minecraftforge.util.os.OS;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// This is the SlimeLauncher replacement for [LegacyDev](https://github.com/MinecraftForge/LegacyDev/).
/// Which was written to Bridge 1.12.2 to FG3+
final class LegacyDev {
    private LegacyDev() {}

    static final Logger LOGGER = Main.LOGGER;
    static final String LEGACYDEV = "net.minecraftforge.legacydev.";
    static final String LEGACYDEV_CLIENT = LEGACYDEV + "MainClient";
    static final String LEGACYDEV_SERVER = LEGACYDEV + "MainServer";
    static final String USERDEV = "net.minecraftforge.userdev.";
    static final String USERDEV_TESTING = USERDEV + "LaunchTesting";

    static boolean is(String main) {
        return main.startsWith(LEGACYDEV);
    }

    static boolean isUserDev(String main) {
        return USERDEV_TESTING.equals(main);
    }

    static String getMainClass() {
        String mainClass = getenv("mainClass");
        if (mainClass == null)
            throw new IllegalArgumentException("Must specify mainClass environment variable");
        LOGGER.info("Legacy Main Class: " + mainClass);
        return mainClass;
    }

    private static Map<String, File> findAllClassPathEntries() {
        String[] parts = System.getProperty("java.class.path").split(File.pathSeparator);
        Map<String, File> files = new HashMap<>();
        for (String part : parts) {
            File file = new File(part);
            if (file.exists() && file.isFile())
                files.put(file.getName(), file);
        }
        return files;
    }

    /// Extract native libraries and inject them into the classloader.
    /// This mimics the behavior of the Vanilla Minecraft Launcher
    /// Because old versions of lwjgl didn't auto-extract their libraries
    static void setupNatives(MinecraftVersion versionJson, File cache) {
        OS currentOS = OS.current();
        Map<String, File> classpath = findAllClassPathEntries();
        LOGGER.info("Extracting natives to " + cache.getAbsolutePath());
        LOGGER.push();
        try {
            for (MinecraftVersion.Lib lib : versionJson.getLibs()) {
                if (!lib.allows(currentOS) || lib.info.extract == null || lib.dl == null || lib.dl == lib.info.downloads.artifact) // We only want natives
                    continue;

                String name = lib.dl.path.substring(lib.dl.path.lastIndexOf('/') + 1);
                File file = classpath.get(name);
                if (file == null) {
                    LOGGER.error(name + ": Missing");
                    continue;
                }

                LOGGER.info(name + " from " + file.getAbsolutePath());
                LOGGER.push();
                try (ZipFile zip = new ZipFile(file)) {
                    zipEntry:
                    for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                        ZipEntry entry = en.nextElement();

                        // Determine the output name, skipping 32-bit ELF on 64-bit JVMs.
                        // LWJGL2 natives jars bundle both 32- and 64-bit .so files; the 64-bit
                        // files are suffixed "64" (liblwjgl64.so) but System.loadLibrary("lwjgl")
                        // looks for liblwjgl.so — so rename only the lwjgl binary.
                        String outputName = entry.getName();
                        if (outputName.toLowerCase(java.util.Locale.ROOT).endsWith(".so")) {
                            // Peek at the ELF header to determine the class.
                            byte[] hdr = new byte[5];
                            InputStream probe = zip.getInputStream(entry);
                            int read = probe.read(hdr);
                            probe.close();
                            if (read == 5 && hdr[0] == 0x7f && hdr[1] == 'E' && hdr[2] == 'L' && hdr[3] == 'F') {
                                if (hdr[4] == 1) { // ELFCLASS32
                                    continue zipEntry; // 32-bit .so — incompatible with 64-bit JVM
                                }
                                if (hdr[4] == 2 && outputName.startsWith("liblwjgl64")) {
                                    outputName = "liblwjgl.so";
                                }
                            }
                        }

                        File output = new File(cache, outputName);
                        if (output.exists())
                            continue; // Assume its valid is already extracted

                        // Skip anything the json says to filter
                        if (lib.info.extract.exclude != null) {
                            for (String exclude : lib.info.extract.exclude) {
                                if (entry.getName().startsWith(exclude))
                                    continue zipEntry;
                            }
                        }

                        if (output.getParentFile() != null)
                            output.getParentFile().mkdirs();

                        try (FileOutputStream out = new FileOutputStream(output)) {
                            InputStream stream = zip.getInputStream(entry);
                            byte[] buf = new byte[8192];
                            int length;
                            while ((length = stream.read(buf)) != -1) {
                                out.write(buf, 0, length);
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    LOGGER.pop();
                }
            }
        } finally {
            LOGGER.pop();
        }

        String paths = System.getProperty("java.library.path");
        if (paths == null || paths.isEmpty())
            paths = cache.getAbsolutePath();
        else
            paths += File.pathSeparator + cache.getAbsolutePath();
        System.setProperty("java.library.path", paths);

        // Add the library path to the classloader if it has already been cached. It shouldn't be by now, and this only matters on java <= 8 so this reflection should be fairly safe
        try {
            final String[] usrPathsValue = paths.split(File.pathSeparator);
            final Field usrPathsField = ClassLoader.class.getDeclaredField("usr_paths");
            usrPathsField.setAccessible(true);
            usrPathsField.set(null, usrPathsValue);
        } catch (Throwable t) {
            // If we catch an exception we're on a modern version of java, and thus probably don't need these hacks
        }
    }

    /// Enhance the command line arguments like LegacyDev does
    /// This reads a lot of things from the environment because I was stupid when I originally designed Legacydev -Lex
    /// [Reference](https://github.com/MinecraftForge/LegacyDev/blob/master/src/main/java/net/minecraftforge/legacydev/Main.java#L81)
    @SuppressWarnings("DataFlowIssue") // Maps can have null values but IDEA yells about it
    static String[] enhanceArgs(String mainClass, String[] existing) {
        LOGGER.info("Enhancing Arguments");
        LOGGER.push();
        try {
            boolean isClient = LEGACYDEV_CLIENT.equals(mainClass);
            Map<String, String> values = new HashMap<>();
            if (isUserDev(mainClass)) {
                // We just need to find the launch target from the args
                // https://github.com/MinecraftForge/MinecraftForge/blob/2bfa53b05a1482255844807e52875d6c03dc48d0/src/userdev/java/net/minecraftforge/userdev/LaunchTesting.java#L38
                Map<String, String> tmp = new HashMap<>();
                tmp.put("launchTarget", getenv("target"));
                processArgs(values, existing);
                String target = tmp.get("launchTarget");
                if (target != null && target.contains("client"))
                    isClient = true;

                values.put("gameDir", ".");
                values.put("launchTarget", getenv("target"));
                values.put("fml.mcpVersion", getenv("MCP_VERSION"));
                values.put("fml.mcVersion", getenv("MC_VERSION"));
                values.put("fml.forgeGroup", getenv("FORGE_GROUP"));
                values.put("fml.forgeVersion", getenv("FORGE_VERSION"));
            }

            String tweak = getenv("tweakClass");
            if (tweak != null && !tweak.isEmpty()) {
                LOGGER.info("tweakClass: " + tweak);
                values.put("tweakClass", tweak);
            }

            if (isClient) {
                LOGGER.info("version:    " + getenv("MC_VERSION"));
                values.put("version", getenv("MC_VERSION"));
                values.put("assetIndex", "{asset_index}");
                values.put("assetsDir", "{assets_root}");
                values.put("accessToken", "Forge");
                values.put("userProperties", "[]");
                values.put("username", null);
                values.put("password", null);
            }

            List<String> extras = processArgs(values, existing);
            LOGGER.info("Extra:     " + extras);

            List<String> lst = new ArrayList<>(values.size() * 2 + extras.size());
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty())
                    continue;
                lst.add("--" + entry.getKey());
                lst.add(entry.getValue());
            }
            lst.addAll(extras);
            return lst.toArray(new String[0]);
        } finally {
            LOGGER.pop();
        }
    }

    private static List<String> processArgs(Map<String, String> values, String[] args) {
        final OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        for (String key : values.keySet())
            parser.accepts(key).withRequiredArg().ofType(String.class);

        final NonOptionArgumentSpec<String> nonOption = parser.nonOptions();

        final OptionSet options = parser.parse(args);
        for (String key : values.keySet()) {
            if (options.hasArgument(key)) {
                String value = (String) options.valueOf(key);
                values.put(key, value);
            }
        }

        return new ArrayList<>(nonOption.values(options)); // extras
    }

    private static @Nullable String getenv(String name) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? null : value;
    }

    private static boolean nullOrEmpty(@Nullable String value) {
        return value == null || value.isEmpty();
    }
}
