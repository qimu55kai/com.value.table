package com.value.table.show;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.value.table.Messages;
import com.value.table.model.Config;
import com.value.table.util.Icons;

/**
 * The rule syntax reference, opened from the preference page.
 * <p>
 * The text is not in this class and not in a message property: it is
 * {@code help.txt} / {@code help_zh_CN.txt} at the root of the plug-in, read
 * through {@link Messages#text(String)}. Forty lines of aligned syntax stay
 * readable that way, and a translator can open the file and edit it in place.
 * </p>
 */
public class HelpWindow {

    private Shell shell;

    public HelpWindow(Shell parentShell) {
        this.shell = new Shell(parentShell, SWT.CLOSE | SWT.RESIZE | SWT.TITLE | SWT.MIN);
        this.shell.setImage(Icons.getImage());
        this.shell.setText(Messages.get("help.title"));
        this.shell.setLayout(new GridLayout(1, false));

        Text text = new Text(this.shell,
                SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        text.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));
        text.setText(Messages.text("help"));

        int w = Math.min(Config.screenWidth(), 720);
        int h = Math.min(Config.screenHeight(), 520);
        this.shell.setBounds((Config.screenWidth() - w) / 2, (Config.screenHeight() - h) / 2, w, h);
        this.shell.open();
    }
}
