package com.value.table;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.value.table.model.Config;
import com.value.table.model.EntityResolver;
import com.value.table.util.Icons;

/**
 * The activator class controls the plug-in life cycle.
 */
public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.value.table";

    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        // Make sure the rule defaults exist before the first use, even when the
        // preference page has never been opened. Note: the old
        // AbstractUIPlugin.initializeDefaultPreferences(IPreferenceStore) hook is
        // deprecated for removal in recent Eclipse versions, so do it here.
        IPreferenceStore store = getPreferenceStore();
        store.setDefault(Config.USER_TABLE_TYPE, Config.DEFAULT_USER_TABLE_TYPE);
        store.setDefault(Config.INVOKE_ENABLED, true);
        store.setDefault(Config.MONITOR_GUARD, true);
        store.setDefault(Config.BLACKLIST, Config.DEFAULT_BLACKLIST);
        store.setDefault(Config.FETCH_TIMEOUT, 10);
        store.setDefault(Config.MAX_ROWS, 2000);
        store.setDefault(Config.ENTITY_METHODS, false);
        store.setDefault(Config.LANGUAGE, Config.LANGUAGE_SYSTEM);
        /*
         * The interface language is fixed here, once, because every string the
         * plug-in shows is looked up through Messages afterwards. A change on
         * the preference page is therefore a next-start change; that is the
         * same limit the platform's own -nl switch has.
         */
        Messages.useLanguage(store.getString(Config.LANGUAGE));
        // The rule table used to be filled from a static initialiser; it now
        // runs here, once, on a thread that is allowed to touch preferences.
        Config.init();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        // Member layouts are cached per type; a hot code replace can change
        // them, and a stopped bundle must not keep the cache alive.
        EntityResolver.clearCache();
        Icons.dispose();
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    public static ImageDescriptor getImageDescriptor(String path) {
        return imageDescriptorFromPlugin(PLUGIN_ID, path);
    }

    /**
     * Writes to the Eclipse error log instead of stderr, so a user can find and
     * report the problem from the Error Log view.
     */
    public static void log(String message, Throwable t) {
        Activator activator = getDefault();
        if (activator == null) {
            if (t != null) {
                t.printStackTrace();
            }
            return;
        }
        activator.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID,
                message == null ? "" : message, t));
    }
}
