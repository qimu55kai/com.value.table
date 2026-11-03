package com.value.table.preferences;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.value.table.Activator;
import com.value.table.Messages;
import com.value.table.model.Config;
import com.value.table.model.EntityRules;
import com.value.table.show.HelpWindow;
import com.value.table.util.Util;

/**
 * The {@code Java > Debug > ValueTable} page.
 * <p>
 * Four groups, in the order a new user needs them:
 * </p>
 * <ol>
 * <li>the rule table - how to read a class that is not an array or a
 * collection, and the help window that explains the syntax;</li>
 * <li>the per-type column lists, which the table window writes when the user
 * picks columns, kept editable as text so a list can be copied between
 * workspaces;</li>
 * <li>the calls made into the target, which is the only part of the plug-in
 * that can hang a debug session;</li>
 * <li>the interface language.</li>
 * </ol>
 */
public class ToolPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private static final int COLUMN_COUNT = 4;

    private Table table;
    private final List<TableItem> items = new ArrayList<TableItem>();
    private Button removeBut;
    private Button addBut;
    private Text className;
    private Text getArray;
    private Text getRowName;
    private Text getColName;

    private Button invokeEnabled;
    private Button entityMethods;
    private Button monitorGuard;
    private Text blacklist;
    private Text timeout;
    private Text maxRows;
    private Text entityRules;
    private CCombo language;

    public ToolPreferencePage() {
        super();
        setDescription(Messages.get("pref.description"));
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        Composite top = new Composite(root, SWT.NONE);
        top.setLayout(new GridLayout(2, false));
        top.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        createRuleTable(top);
        createRuleInput(top);

        createEntityGroup(root);
        createSafetyGroup(root);
        createLanguageGroup(root);

        load();
        return root;
    }

    // ------------------------------------------------------------------
    // rules
    // ------------------------------------------------------------------

    private void createRuleTable(Composite parent) {
        table = new Table(parent,
                SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));

        table.addSelectionListener(new SelectionListener() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                int index = table.getSelectionIndex();
                if (index > -1) {
                    removeBut.setEnabled(true);
                    putDataToInput(index);
                }
            }

            @Override
            public void widgetDefaultSelected(SelectionEvent e) {
            }
        });

        // The columns share the width the table gets, whatever the window is.
        table.addControlListener(new ControlListener() {
            @Override
            public void controlResized(ControlEvent e) {
                TableColumn[] columns = table.getColumns();
                double oldWidth = 10.0;
                for (TableColumn column : columns) {
                    oldWidth += column.getWidth();
                }
                double scale = table.getSize().x / oldWidth;
                for (TableColumn column : columns) {
                    column.setWidth((int) (column.getWidth() * scale));
                }
            }

            @Override
            public void controlMoved(ControlEvent e) {
            }
        });

        String[] headers = {
                Messages.get("pref.col.class"),
                Messages.get("pref.col.rows"),
                Messages.get("pref.col.rowLabels"),
                Messages.get("pref.col.colLabels") };
        for (String header : headers) {
            TableColumn column = new TableColumn(table, SWT.NONE);
            column.setText(header);
            column.setWidth(100);
        }

        removeBut = new Button(parent, SWT.PUSH);
        removeBut.setText(Messages.get("pref.remove"));
        removeBut.setEnabled(false);
        removeBut.addSelectionListener(new SelectionListener() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                remove(table.getSelectionIndex());
                removeBut.setEnabled(false);
            }

            @Override
            public void widgetDefaultSelected(SelectionEvent e) {
            }
        });
    }

    private void createRuleInput(Composite parent) {
        Label helpLabel = new Label(parent, SWT.NONE);
        helpLabel.setText(Messages.get("pref.help.tip"));

        Button help = new Button(parent, SWT.PUSH);
        help.setText(Messages.get("pref.help.button"));
        help.addSelectionListener(new SelectionListener() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                new HelpWindow(getShell());
            }

            @Override
            public void widgetDefaultSelected(SelectionEvent e) {
            }
        });

        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.get("pref.rule.group"));
        group.setLayout(new GridLayout(2, true));
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        new Label(group, SWT.NONE).setText(Messages.get("pref.rule.name"));
        className = new Text(group, SWT.BORDER | SWT.SINGLE);
        className.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        className.addModifyListener(createModifyListener());

        new Label(group, SWT.NONE).setText(Messages.get("pref.rule.rows"));
        getArray = new Text(group, SWT.BORDER | SWT.SINGLE);
        getArray.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        getArray.addModifyListener(createModifyListener());

        new Label(group, SWT.NONE).setText(Messages.get("pref.rule.rowLabels"));
        getRowName = new Text(group, SWT.BORDER | SWT.SINGLE);
        getRowName.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(group, SWT.NONE).setText(Messages.get("pref.rule.colLabels"));
        getColName = new Text(group, SWT.BORDER | SWT.SINGLE);
        getColName.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        addBut = new Button(parent, SWT.PUSH);
        addBut.setText(Messages.get("pref.add"));
        addBut.setEnabled(false);
        addBut.addSelectionListener(new SelectionListener() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                add();
            }

            @Override
            public void widgetDefaultSelected(SelectionEvent e) {
            }
        });
    }

    private ModifyListener createModifyListener() {
        return new ModifyListener() {
            @Override
            public void modifyText(ModifyEvent e) {
                onInputChanged();
            }
        };
    }

    /** Enables Add once both mandatory fields have something in them. */
    private void onInputChanged() {
        if (addBut == null) {
            return;
        }
        addBut.setEnabled(!className.getText().trim().isEmpty()
                && !getArray.getText().trim().isEmpty());
    }

    private void putDataToInput(int index) {
        TableItem item = items.get(index);
        className.setText(item.getText(0));
        getArray.setText(item.getText(1));
        getRowName.setText(item.getText(2));
        getColName.setText(item.getText(3));
    }

    private void remove(int selectionIndex) {
        if (selectionIndex < 0 || selectionIndex >= items.size()) {
            return;
        }
        items.remove(selectionIndex);
        table.remove(selectionIndex);
    }

    private void add() {
        String name = Util.formatStr(className.getText());
        if (name == null || Util.formatStr(getArray.getText()) == null) {
            return;
        }
        String[] values = { name, getArray.getText(), getRowName.getText(), getColName.getText() };
        // One rule per type: entering a class that already has a row replaces it.
        for (TableItem item : items) {
            if (name.equals(item.getText(0))) {
                item.setText(values);
                table.redraw();
                return;
            }
        }
        TableItem item = new TableItem(table, SWT.NONE);
        items.add(item);
        item.setText(values);
        table.setSelection(item);
        removeBut.setEnabled(true);
    }

    // ------------------------------------------------------------------
    // per-type columns, calls, language
    // ------------------------------------------------------------------

    /**
     * The per-type column lists, in the form the table window writes them.
     * <p>
     * Editable as text on purpose: the dialog is the convenient way to build
     * one, but a list that can be read and copied between workspaces is worth
     * more than a widget that hides it.
     * </p>
     */
    private void createEntityGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.get("pref.columns.group"));
        group.setLayout(new GridLayout(1, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label note = new Label(group, SWT.WRAP);
        note.setText(Messages.get("pref.columns.note"));
        note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        entityRules = new Text(group, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, false);
        data.heightHint = 70;
        entityRules.setLayoutData(data);
        entityRules.setToolTipText(Messages.get("pref.columns.example"));
    }

    /**
     * Settings that decide which calls are made on the debug target. Reading a
     * value means executing code inside the target VM, which is the only part
     * of this plug-in that can hang a debug session, so it gets its own group.
     */
    private void createSafetyGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.get("pref.calls.group"));
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        invokeEnabled = new Button(group, SWT.CHECK);
        invokeEnabled.setText(Messages.get("pref.invoke"));
        invokeEnabled.setLayoutData(spanTwo());

        entityMethods = new Button(group, SWT.CHECK);
        entityMethods.setText(Messages.get("pref.entityMethods"));
        entityMethods.setLayoutData(spanTwo());

        monitorGuard = new Button(group, SWT.CHECK);
        monitorGuard.setText(Messages.get("pref.monitorGuard"));
        monitorGuard.setLayoutData(spanTwo());

        Label note = new Label(group, SWT.WRAP);
        note.setText(Messages.get("pref.calls.note"));
        note.setLayoutData(spanTwo());

        new Label(group, SWT.NONE).setText(Messages.get("pref.blacklist.label"));
        blacklist = new Text(group, SWT.BORDER | SWT.SINGLE);
        blacklist.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        blacklist.setToolTipText(Messages.get("pref.blacklist.tip"));

        new Label(group, SWT.NONE).setText(Messages.get("pref.timeout.label"));
        timeout = new Text(group, SWT.BORDER | SWT.SINGLE);
        timeout.setLayoutData(new GridData(70, SWT.DEFAULT));

        new Label(group, SWT.NONE).setText(Messages.get("pref.maxRows.label"));
        maxRows = new Text(group, SWT.BORDER | SWT.SINGLE);
        maxRows.setLayoutData(new GridData(70, SWT.DEFAULT));
    }

    /**
     * The interface language.
     * <p>
     * A preference page cannot change the language of the strings already on
     * screen - they were looked up when the widgets were built - so this stores
     * the choice and says it needs a restart. Offering "same as the system"
     * first keeps the common case free of surprises.
     * </p>
     */
    private void createLanguageGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.get("pref.language.group"));
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(group, SWT.NONE).setText(Messages.get("pref.language.label"));
        language = new CCombo(group, SWT.READ_ONLY | SWT.BORDER);
        language.setItems(new String[] {
                Messages.get("pref.language.system"),
                Messages.get("pref.language.zh"),
                Messages.get("pref.language.en") });
        language.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label note = new Label(group, SWT.WRAP);
        note.setText(Messages.get("pref.language.note"));
        note.setLayoutData(spanTwo());
    }

    private static GridData spanTwo() {
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.horizontalSpan = 2;
        return data;
    }

    // ------------------------------------------------------------------
    // load and save
    // ------------------------------------------------------------------

    private void load() {
        table.removeAll();
        items.clear();

        String stored = getPreferenceStore().getString(Config.USER_TABLE_TYPE);
        if (stored == null || stored.isEmpty()) {
            stored = Config.DEFAULT_USER_TABLE_TYPE;
            getPreferenceStore().setValue(Config.USER_TABLE_TYPE, stored);
            Config.init();
        }
        for (String line : stored.split(Config.ROW_SPLIT)) {
            String[] columns = line.split(Config.COL_SPLIT);
            // Tolerate the pre-1.0.0 layout, where a style column came first.
            if (columns.length == 5) {
                String[] shifted = new String[COLUMN_COUNT];
                System.arraycopy(columns, 1, shifted, 0, COLUMN_COUNT);
                columns = shifted;
            }
            if (columns.length != COLUMN_COUNT) {
                continue;
            }
            TableItem item = new TableItem(table, SWT.NONE);
            items.add(item);
            item.setText(columns);
        }
        ensureEntryRuleRow();
        loadSafety();
        onInputChanged();
    }

    /**
     * The {@code Map.Entry} rule is re-added by {@link Config#init()} when the
     * stored list does not have it, so the table has to show it as well -
     * otherwise the page looks as if the rule had been deleted while a map still
     * reads two columns per entry.
     */
    private void ensureEntryRuleRow() {
        for (TableItem item : items) {
            if ("java.util.Map$Entry".equals(item.getText(0))) {
                return;
            }
        }
        TableItem item = new TableItem(table, SWT.NONE);
        items.add(item);
        item.setText(Config.ENTRY_RULE.split(Config.COL_SPLIT));
    }

    private void loadSafety() {
        if (invokeEnabled == null) {
            return;
        }
        IPreferenceStore store = getPreferenceStore();
        invokeEnabled.setSelection(store.getBoolean(Config.INVOKE_ENABLED));
        entityMethods.setSelection(store.getBoolean(Config.ENTITY_METHODS));
        monitorGuard.setSelection(store.getBoolean(Config.MONITOR_GUARD));
        blacklist.setText(store.getString(Config.BLACKLIST));
        timeout.setText(String.valueOf(store.getInt(Config.FETCH_TIMEOUT)));
        maxRows.setText(String.valueOf(store.getInt(Config.MAX_ROWS)));
        if (entityRules != null) {
            entityRules.setText(EntityRules.format(EntityRules.current()));
        }
        if (language != null) {
            language.select(indexOfLanguage(store.getString(Config.LANGUAGE)));
        }
    }

    /**
     * Position of a stored language in the combo, which is ordered
     * system - zh - en. An unknown value falls back to "system".
     */
    private static int indexOfLanguage(String stored) {
        if (Config.LANGUAGE_CHINESE.equals(stored)) {
            return 1;
        }
        if (Config.LANGUAGE_ENGLISH.equals(stored)) {
            return 2;
        }
        return 0;
    }

    private static String languageAt(int index) {
        switch (index) {
            case 1:
                return Config.LANGUAGE_CHINESE;
            case 2:
                return Config.LANGUAGE_ENGLISH;
            default:
                return Config.LANGUAGE_SYSTEM;
        }
    }

    private void save() {
        StringBuilder rules = new StringBuilder();
        for (TableItem item : items) {
            try {
                if (rules.length() > 0) {
                    rules.append(Config.ROW_SPLIT);
                }
                for (int i = 0; i < COLUMN_COUNT; i++) {
                    if (i > 0) {
                        rules.append(Config.COL_SPLIT);
                    }
                    String value = Util.formatStr(item.getText(i));
                    rules.append(value == null ? "null" : value);
                }
            } catch (Exception e) {
                Activator.log("cannot save rule " + item.getText(0), e);
            }
        }
        getPreferenceStore().setValue(Config.USER_TABLE_TYPE, rules.toString());
        saveSafety();
        if (entityRules != null) {
            EntityRules.save(EntityRules.parse(entityRules.getText()));
        }
        Config.init();
    }

    private void saveSafety() {
        IPreferenceStore store = getPreferenceStore();
        if (invokeEnabled != null) {
            store.setValue(Config.INVOKE_ENABLED, invokeEnabled.getSelection());
        }
        if (entityMethods != null) {
            store.setValue(Config.ENTITY_METHODS, entityMethods.getSelection());
        }
        if (monitorGuard != null) {
            store.setValue(Config.MONITOR_GUARD, monitorGuard.getSelection());
        }
        if (blacklist != null) {
            store.setValue(Config.BLACKLIST, blacklist.getText().trim());
        }
        if (timeout != null) {
            store.setValue(Config.FETCH_TIMEOUT, parsePositive(timeout.getText(), 10));
        }
        if (maxRows != null) {
            store.setValue(Config.MAX_ROWS, parsePositive(maxRows.getText(), 2000));
        }
        if (language != null) {
            store.setValue(Config.LANGUAGE, languageAt(language.getSelectionIndex()));
        }
    }

    private static int parsePositive(String text, int fallback) {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    protected void performDefaults() {
        IPreferenceStore store = getPreferenceStore();
        store.setValue(Config.USER_TABLE_TYPE, store.getDefaultString(Config.USER_TABLE_TYPE));
        store.setValue(Config.INVOKE_ENABLED, store.getDefaultBoolean(Config.INVOKE_ENABLED));
        store.setValue(Config.ENTITY_METHODS, store.getDefaultBoolean(Config.ENTITY_METHODS));
        store.setValue(Config.MONITOR_GUARD, store.getDefaultBoolean(Config.MONITOR_GUARD));
        store.setValue(Config.BLACKLIST, store.getDefaultString(Config.BLACKLIST));
        store.setValue(Config.FETCH_TIMEOUT, store.getDefaultInt(Config.FETCH_TIMEOUT));
        store.setValue(Config.MAX_ROWS, store.getDefaultInt(Config.MAX_ROWS));
        store.setValue(Config.LANGUAGE, store.getDefaultString(Config.LANGUAGE));
        load();
        Config.init();
        super.performDefaults();
    }

    @Override
    protected void performApply() {
        save();
        super.performApply();
    }

    @Override
    public boolean performOk() {
        save();
        return super.performOk();
    }
}
