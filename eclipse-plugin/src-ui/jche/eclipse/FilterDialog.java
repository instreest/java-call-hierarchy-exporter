// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * フィルタバーに入りきらない条件のダイアログ。
 *
 * どの項目も「見えている木の絞り込み」でしかなく、解析はしない。
 * そのことが伝わるよう、上に一言だけ添えてある。
 */
final class FilterDialog extends Dialog {

    private final FilterSettings settings;

    private Button includeTests;
    private Button includeGuessed;
    private Button applyExcludePackages;
    private Button dedupeCallers;

    FilterDialog(Shell shell, FilterSettings settings) {
        super(shell);
        this.settings = settings;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(Messages.get("filter.title"));
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        area.setLayout(layout);

        Label note = new Label(area, SWT.WRAP);
        note.setText(Messages.get("filter.note"));
        note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        includeTests = check(area, Messages.get("filter.includeTests"), settings.includeTests);
        includeGuessed = check(area, Messages.get("filter.includeGuessed"), settings.includeGuessed);
        applyExcludePackages = check(area, Messages.get("filter.applyExclude"),
                settings.applyExcludePackages);
        dedupeCallers = check(area, Messages.get("filter.dedupe"),
                settings.dedupeCallers);
        return area;
    }

    private static Button check(Composite parent, String text, boolean selected) {
        Button button = new Button(parent, SWT.CHECK);
        button.setText(text);
        button.setSelection(selected);
        return button;
    }

    @Override
    protected void okPressed() {
        settings.includeTests = includeTests.getSelection();
        settings.includeGuessed = includeGuessed.getSelection();
        settings.applyExcludePackages = applyExcludePackages.getSelection();
        settings.dedupeCallers = dedupeCallers.getSelection();
        super.okPressed();
    }
}
