package com.value.table;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.osgi.framework.Bundle;

/**
 * The one place user-visible text comes from.
 * <p>
 * Text lives in {@code messages.properties} (English, the base bundle) and
 * {@code messages_zh_CN.properties}. Code asks for a key, never for a language,
 * so adding a third language means adding one file.
 * </p>
 * <p>
 * The language is not decided by {@code org.eclipse.osgi.util.NLS} because the
 * platform's {@code -nl} switch is a process-wide decision the user cannot make
 * from this plug-in's preference page: here the preference is read once at
 * start-up and handed to {@link #useLanguage(String)}. Picking a different
 * language therefore takes a restart - the same limit every Eclipse plug-in
 * has, since the strings are already on screen by the time a page is saved.
 * </p>
 * <p>
 * A missing key is not an exception: it renders as {@code !key!} and is logged
 * once, so a typo is visible in the window it breaks instead of taking the
 * window down. {@code I18nTest} compares both bundles against the keys used in
 * the source, which is what keeps that from ever being the way a typo is found.
 * </p>
 * <p>
 * {@link #bind} goes through {@link MessageFormat}, so a parameterised message
 * must not contain a lone apostrophe - double it ({@code ''}) or, better, move
 * the apostrophe out of the parameterised text.
 * </p>
 */
public final class Messages {

    /** Bundle base name; the files sit next to this class. */
    private static final String BUNDLE_NAME = "com.value.table.messages";

    /** Chosen language, or null while nothing has chosen one. */
    private static volatile Locale locale;

    private static volatile ResourceBundle bundle;

    /** Reported once per key, so a missing key cannot flood the error log. */
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    private Messages() {
    }

    /**
     * Applies the stored preference: {@code "system"}, {@code "en"} or
     * {@code "zh"}. Called once at start-up; a later change only takes effect
     * after a restart.
     */
    public static synchronized void useLanguage(String preference) {
        Locale next = resolve(preference);
        if (!next.equals(locale)) {
            locale = next;
            bundle = null;
        }
    }

    /**
     * The language these messages are read in.
     * <p>
     * A Chinese system maps to {@code zh_CN} whatever the region: one Chinese
     * file, and a user in Hong Kong, Macao or Taiwan should not fall back to
     * English because their locale is {@code zh_TW}.
     * </p>
     */
    public static Locale getLocale() {
        Locale set = locale;
        return set != null ? set : systemLocale();
    }

    private static Locale systemLocale() {
        Locale system = Locale.getDefault();
        if ("zh".equals(system.getLanguage())) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        return system;
    }

    private static Locale resolve(String preference) {
        if ("en".equalsIgnoreCase(preference)) {
            return Locale.ENGLISH;
        }
        if ("zh".equalsIgnoreCase(preference)) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        return systemLocale();
    }

    /** One string, or {@code !key!} when the bundle has no such key. */
    public static String get(String key) {
        if (key == null) {
            return "";
        }
        try {
            return bundle().getString(key);
        } catch (MissingResourceException e) {
            if (MISSING.add(key)) {
                Activator.log("missing message key: " + key, null);
            }
            return "!" + key + "!";
        }
    }

    /** One string with {@code {0}}, {@code {1}}... filled in. */
    public static String bind(String key, Object... args) {
        String pattern = get(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (IllegalArgumentException e) {
            // A translation with a broken placeholder must not hide the text.
            Activator.log("cannot format message " + key, e);
            return pattern;
        }
    }

    private static ResourceBundle bundle() {
        ResourceBundle current = bundle;
        if (current == null) {
            synchronized (Messages.class) {
                current = bundle;
                if (current == null) {
                    /*
                     * No fallback to the JVM's default locale: choosing English
                     * on a Chinese system would otherwise load the Chinese file,
                     * because the default control falls back to the default
                     * locale before it falls back to the base bundle.
                     */
                    current = ResourceBundle.getBundle(BUNDLE_NAME, getLocale(),
                            Messages.class.getClassLoader(),
                            ResourceBundle.Control.getNoFallbackControl(
                                    ResourceBundle.Control.FORMAT_PROPERTIES));
                    bundle = current;
                }
            }
        }
        return current;
    }

    /**
     * A long text that is not a label - the rule syntax help.
     * <p>
     * Kept as a text file rather than as one properties line with {@code \n}
     * escapes: it is forty lines of aligned syntax that a translator has to be
     * able to read in its own shape.
     * </p>
     *
     * @param base file name without the language part, e.g. {@code "help"}
     * @return the text for the current language, or the base file, or "" when
     *         neither can be read
     */
    public static String text(String base) {
        String suffix = "";
        Locale current = getLocale();
        if ("zh".equals(current.getLanguage())) {
            suffix = "_zh_CN";
        }
        String text = read(base + suffix + ".txt");
        if (text == null && !suffix.isEmpty()) {
            text = read(base + ".txt");
        }
        return text == null ? "" : text;
    }

    private static String read(String name) {
        Activator activator = Activator.getDefault();
        if (activator == null) {
            return null;
        }
        Bundle bundleOfPlugin = activator.getBundle();
        URL url = FileLocator.find(bundleOfPlugin, new Path(name), null);
        if (url == null) {
            return null;
        }
        try (InputStream in = url.openStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            Activator.log("cannot read " + name, e);
            return null;
        }
    }
}
