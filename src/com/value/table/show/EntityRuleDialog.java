package com.value.table.show;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.value.table.Messages;
import com.value.table.model.EntityRule;
import com.value.table.model.EntityRules;
import com.value.table.model.MemberCatalog;
import com.value.table.model.MemberRef;
import com.value.table.util.Util;

/**
 * Chooses which members become columns, one tab per type in the element's
 * hierarchy.
 * <p>
 * The tabs are split by the type that <em>declares</em> a member, because that
 * is how a class is read: the state it inherits, then the state it adds. The
 * tab is only a way to navigate the members, though - the rule that comes out of
 * it is written once, on the element's own type, and holds every ticked member
 * in tab order:
 * </p>
 * <pre>
 * com.demo.Employee#id,name,salary,dept
 * </pre>
 * <p>
 * One line is easier to read than one line per level, and it is what makes the
 * order of the columns adjustable: a tab is a block of columns, and the blocks
 * run from the base class down to the element's own type. The line describes
 * this table only - a Person list has its own line, and ticking a member under
 * the Person tab of an Employee table does not touch it.
 * </p>
 */
public final class EntityRuleDialog extends Dialog {

    private final List<MemberCatalog.Type> candidates;
    private final String tableName;
    /** Type the single rule is saved on: the element's own type. */
    private final String ownSignature;
    private final String ownName;
    /** Column labels the table shows right now; the boxes start ticked to match. */
    private final Set<String> shown;
    private final List<Tab> tabs = new ArrayList<Tab>();
    private Label summary;
    private Map<String, List<MemberRef>> rules;

    public EntityRuleDialog(Shell parent, String tableName, List<MemberCatalog.Type> candidates,
            String ownSignature, String ownName, List<String> shownLabels) {
        super(parent);
        this.tableName = tableName;
        this.candidates = candidates == null ? new ArrayList<MemberCatalog.Type>() : candidates;
        this.ownSignature = ownSignature;
        this.ownName = ownName == null ? Messages.get("dialog.unknownType") : ownName;
        this.shown = new LinkedHashSet<String>();
        if (shownLabels != null) {
            this.shown.addAll(shownLabels);
        }
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    /** The rules to save, or {@code null} when the dialog was cancelled. */
    public Map<String, List<MemberRef>> getRules() {
        return rules;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(tableName == null
                ? Messages.get("dialog.title")
                : Messages.bind("dialog.title.named", tableName));
        shell.setMinimumSize(760, 520);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        Button save = createButton(parent, IDialogConstants.OK_ID, Messages.get("dialog.save"), true);
        save.setToolTipText(Messages.bind("dialog.save.tip", ownName));
        createButton(parent, IDialogConstants.CANCEL_ID, Messages.get("dialog.cancel"), false);
    }

    @Override
    protected Composite createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        ((GridLayout) area.getLayout()).numColumns = 1;

        Label hint = new Label(area, SWT.WRAP);
        hint.setText(Messages.bind("dialog.hint", ownName));
        hint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        if (candidates.isEmpty()) {
            Label empty = new Label(area, SWT.WRAP);
            empty.setText(Messages.get("dialog.empty"));
            empty.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            return area;
        }

        TabFolder folder = new TabFolder(area, SWT.NONE);
        folder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        for (MemberCatalog.Type candidate : candidates) {
            TabItem item = new TabItem(folder, SWT.NONE);
            item.setText(candidate.simpleName());
            item.setToolTipText(candidate.name);
            Tab tab = new Tab(folder, candidate);
            item.setControl(tab.composite);
            // The folder only knows the item, the item only knows the control.
            tab.item = item;
            tabs.add(tab);
        }

        this.summary = new Label(area, SWT.WRAP);
        this.summary.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        updateSummary();
        return area;
    }

    @Override
    protected void okPressed() {
        Map<String, List<MemberRef>> result = new LinkedHashMap<String, List<MemberRef>>(
                EntityRules.current());
        List<MemberRef> merged = mergedMembers();
        /*
         * Only the element's own line is rewritten. A rule on another type
         * describes that type's own list - tick "id" under Person here and the
         * Person table, now or later, is not affected, and its existing line is
         * left exactly as it is.
         */
        result.remove(ownSignature);
        if (!merged.isEmpty()) {
            result.put(ownSignature, merged);
        }
        this.rules = result;
        super.okPressed();
    }

    /** Every ticked member, in tab order, without repeats. */
    private List<MemberRef> mergedMembers() {
        List<MemberRef> merged = new ArrayList<MemberRef>();
        Set<String> taken = new LinkedHashSet<String>();
        for (Tab tab : tabs) {
            for (MemberRef member : tab.checkedMembers()) {
                if (taken.add(member.label)) {
                    merged.add(member);
                }
            }
        }
        return merged;
    }

    /** The line that will be stored, spelled out before it is stored. */
    private void updateSummary() {
        if (this.summary == null || this.summary.isDisposed()) {
            return;
        }
        List<MemberRef> merged = mergedMembers();
        StringBuilder text = new StringBuilder();
        if (merged.isEmpty()) {
            text.append(Messages.get("dialog.summary.none"));
        } else {
            text.append(Messages.bind("dialog.summary.line",
                    Util.classNameOf(ownSignature) + "#" + membersText(merged)));
        }
        this.summary.setText(text.toString());
    }

    /** The rule body as it will be stored: labels, a method with its (). */
    private static String membersText(List<MemberRef> members) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(Util.formatMemberRef(members.get(i)));
        }
        return text.toString();
    }

    // ------------------------------------------------------------------
    // one tab
    // ------------------------------------------------------------------

    private final class Tab {

        final MemberCatalog.Type candidate;
        final Composite composite;
        final Table fieldTable;
        final Table methodTable;
        TabItem item;
        boolean touched;

        Tab(Composite folder, MemberCatalog.Type candidate) {
            this.candidate = candidate;
            this.composite = new Composite(folder, SWT.NONE);
            GridLayout layout = new GridLayout(2, true);
            layout.marginWidth = 10;
            layout.marginHeight = 8;
            layout.verticalSpacing = 8;
            this.composite.setLayout(layout);

            this.fieldTable = memberTable(Messages.get("dialog.group.fields"), candidate.fields);
            this.methodTable = memberTable(Messages.get("dialog.group.methods"), candidate.methods);
            tickShown();
        }

        private Table memberTable(String title, List<MemberRef> members) {
            Group group = new Group(this.composite, SWT.NONE);
            group.setText(title);
            group.setLayout(new GridLayout(1, false));
            group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

            Table table = new Table(group, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL | SWT.SINGLE);
            table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            for (MemberRef member : members) {
                TableItem row = new TableItem(table, SWT.NONE);
                row.setText(member.label);
                row.setData(member);
            }
            table.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    // Only a check on the box counts; selecting a row does not.
                    if ((e.detail & SWT.CHECK) != 0) {
                        markTouched();
                    }
                }
            });

            Composite buttons = new Composite(group, SWT.NONE);
            buttons.setLayout(new GridLayout(2, true));
            buttons.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
            Button all = new Button(buttons, SWT.PUSH);
            all.setText(Messages.get("dialog.all"));
            all.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            all.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    checkAll(table, true);
                }
            });
            Button none = new Button(buttons, SWT.PUSH);
            none.setText(Messages.get("dialog.none"));
            none.setToolTipText(Messages.get("dialog.none.tip"));
            none.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            none.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    checkAll(table, false);
                }
            });
            return table;
        }

        private void checkAll(Table table, boolean checked) {
            for (TableItem row : table.getItems()) {
                row.setChecked(checked);
            }
            markTouched();
        }

        /**
         * Ticks what the table shows now.
         * <p>
         * Not "what a rule says", because a type may be on screen through the
         * automatic list rather than through a rule: opening the dialog and
         * saving it untouched has to keep the columns exactly as they are.
         * </p>
         */
        private void tickShown() {
            for (Table table : new Table[] { this.fieldTable, this.methodTable }) {
                for (TableItem row : table.getItems()) {
                    row.setChecked(shown.contains(((MemberRef) row.getData()).label));
                }
            }
        }

        List<MemberRef> checkedMembers() {
            List<MemberRef> chosen = new ArrayList<MemberRef>();
            for (Table table : new Table[] { this.fieldTable, this.methodTable }) {
                for (TableItem row : table.getItems()) {
                    if (row.getChecked()) {
                        chosen.add((MemberRef) row.getData());
                    }
                }
            }
            return chosen;
        }

        private void markTouched() {
            this.touched = true;
            if (this.item != null) {
                this.item.setText(candidate.simpleName() + " *");
            }
            updateSummary();
        }
    }
}
