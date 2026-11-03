package com.value.table.util;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Shell;

import com.value.table.Activator;

/**
 * Single place that owns the plug-in icon.
 * <p>
 * The icon is resolved through an {@link ImageDescriptor}, which is what lets
 * JFace pick up the {@code @1.5x} / {@code @2x} variants next to the 1x PNG on
 * a high DPI display. Those variants only exist for PNG files - a GIF always
 * falls back to the 16px bitmap and is then stretched, which is why the old
 * {@code icon.gif} looked blurry once Windows scaled the IDE.
 * </p>
 * <p>
 * The image is created lazily on the UI thread and disposed when the bundle
 * stops, instead of being built from a static initializer that required a
 * {@code Display} to exist before anything else touched this class.
 * </p>
 */
public final class Icons {

    /** Base file; the {@code @1.5x} and {@code @2x} siblings sit next to it. */
    public static final String ICON_PATH = "icons/valuetable.png";

    private static Image icon;

    private Icons() {
    }

    /**
     * @return the shared icon, or {@code null} if it cannot be loaded.
     */
    public static Image getImage() {
        if (icon == null || icon.isDisposed()) {
            ImageDescriptor descriptor = Activator.getImageDescriptor(ICON_PATH);
            icon = descriptor == null ? null : descriptor.createImage();
        }
        return icon;
    }

    /**
     * Gives a window the plug-in icon. Safe to call with a disposed shell.
     */
    public static void applyTo(Shell shell) {
        if (shell == null || shell.isDisposed()) {
            return;
        }
        Image image = getImage();
        if (image != null) {
            shell.setImage(image);
        }
    }

    /**
     * Called from {@link Activator#stop(org.osgi.framework.BundleContext)}.
     */
    public static void dispose() {
        if (icon != null && !icon.isDisposed()) {
            icon.dispose();
        }
        icon = null;
    }
}
