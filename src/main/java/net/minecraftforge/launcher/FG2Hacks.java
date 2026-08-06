/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.launcher;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import net.minecraftforge.srgutils.MinecraftVersion;

import org.jetbrains.annotations.Nullable;

class FG2Hacks {
    /* ----------- COREMOD AND AT HACK --------- */
    // coremod hack
    private static final String           COREMOD_VAR    = "fml.coreMods.load";
    private static final String           COREMOD_MF     = "FMLCorePlugin";
    // AT hack
    private static final String           MOD_ATD_CLASS  = "net.minecraftforge.fml.common.asm.transformers.ModAccessTransformer";
    private static final String           MOD_AT_METHOD  = "addJar";

    private static final MinecraftVersion FG2_START = MinecraftVersion.from("1.8");
    private static final MinecraftVersion FG2_END = MinecraftVersion.from("1.12.2");
    private static @Nullable MinecraftVersion mcVer(String ver) {
        try {
            return MinecraftVersion.from(ver);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void searchCoremods(String mcVersion) {
        MinecraftVersion currentMC = mcVer(mcVersion);
        // Check if we're on a known version, between 1.8 and 1.12.2
        if (currentMC == null || currentMC.compareTo(FG2_START) < 0 || currentMC.compareTo(FG2_END) > 0)
            return;

        // initialize AT hack Method
        AtRegistrar atRegistrar = new AtRegistrar();

        Map<String, File> coreMap = new HashMap<>();
        // Enumerate classpath entries. Prefer URLClassLoader when available (Java 8);
        // on Java 9+ the app loader is not a URLClassLoader (Cleanroom runs 1.12.2 on Java 25).
        for (URL url : classpathUrls()) {
            try {
                searchCoremodAtUrl(url, atRegistrar, coreMap);
            } catch (IOException | InvocationTargetException | IllegalAccessException | URISyntaxException e) {
                Main.LOGGER.warn("FG2Hacks failed to search for coremod at url " + url, e);
            }
        }

        // Set The env to anything we've found from the classpath
        Set<String> coremodsSet = new HashSet<>();
        String coremodEnv = System.getProperty(COREMOD_VAR);
        if (coremodEnv != null && !coremodEnv.isEmpty())
            coremodsSet.addAll(Arrays.asList(coremodEnv.split(",")));
        coremodsSet.addAll(coreMap.keySet());
        System.setProperty(COREMOD_VAR,  String.join(",", coremodsSet));

        /*
        // ok.. tweaker hack now.
        if (!Strings.isNullOrEmpty(common.getTweakClass())) {
            common.extras.add("--tweakClass");
            common.extras.add("net.minecraftforge.gradle.tweakers.CoremodTweaker");
        }
         */
    }

    private static URL[] classpathUrls() {
        ClassLoader cl = FG2Hacks.class.getClassLoader();
        if (cl instanceof URLClassLoader)
            return ((URLClassLoader) cl).getURLs();

        String cp = System.getProperty("java.class.path", "");
        if (cp.isEmpty())
            return new URL[0];

        String[] parts = cp.split(File.pathSeparator);
        URL[] urls = new URL[parts.length];
        int n = 0;
        for (String part : parts) {
            try {
                urls[n++] = new File(part).toURI().toURL();
            } catch (IOException e) {
                Main.LOGGER.warn("FG2Hacks failed to resolve classpath entry " + part, e);
            }
        }
        return n == urls.length ? urls : Arrays.copyOf(urls, n);
    }

    private static void searchCoremodAtUrl(URL url, AtRegistrar atRegistrar, Map<String, File> coreMods) throws IOException, InvocationTargetException, IllegalAccessException, URISyntaxException {
        if (!url.getProtocol().startsWith("file")) // because file urls start with file://
            return;

        File coreMod = new File(url.toURI().getPath());
        if (!coreMod.exists())
            return;

        Manifest manifest = null;
        if (coreMod.isDirectory()) {
            File manifestMF = new File(coreMod, "META-INF/MANIFEST.MF");
            if (manifestMF.exists()) {
                FileInputStream stream = new FileInputStream(manifestMF);
                manifest = new Manifest(stream);
                stream.close();
            }
        } else if (coreMod.getName().endsWith("jar")) {
            try (JarFile jar = new JarFile(coreMod)) {
                manifest = jar.getManifest();
                if (manifest != null)
                    atRegistrar.addJar(jar, manifest);
            }
        }

        // we got the manifest? use it.
        if (manifest != null) {
            String clazz = manifest.getMainAttributes().getValue(COREMOD_MF);
            if (clazz != null && !clazz.isEmpty()) {
                Main.LOGGER.info("Found and added coremod: " + clazz);
                coreMods.put(clazz, coreMod);
            }
        }
    }

    /**
     * Hack to register jar ATs with Minecraft Forge
     */
    private static final class AtRegistrar {
        private static final Attributes.Name FMLAT = new Attributes.Name("FMLAT");
        @Nullable
        private Method newMethod = null;
        @Nullable
        private Method oldMethod = null;

        private AtRegistrar() {
            try {
                Class<?> modAtdClass = Class.forName(MOD_ATD_CLASS);
                try {
                    newMethod = modAtdClass.getDeclaredMethod(MOD_AT_METHOD, JarFile.class, String.class);
                } catch (NoSuchMethodException | SecurityException ignored) {
                    try {
                        oldMethod = modAtdClass.getDeclaredMethod(MOD_AT_METHOD, JarFile.class);
                    } catch (NoSuchMethodException | SecurityException ignored2) {
                        Main.LOGGER.error("Failed to find method " + MOD_ATD_CLASS + '.' + MOD_AT_METHOD);
                    }
                }
            } catch (ClassNotFoundException e) {
                Main.LOGGER.error("Failed to find class " + MOD_ATD_CLASS);
            }
        }

        public void addJar(JarFile jarFile, Manifest manifest) throws InvocationTargetException, IllegalAccessException {
            if (newMethod != null) {
                String ats = manifest.getMainAttributes().getValue(FMLAT);
                if (ats != null && !ats.isEmpty())
                    newMethod.invoke(null, jarFile, ats);
            } else if (oldMethod != null) {
                oldMethod.invoke(null, jarFile);
            }
        }
    }
}
