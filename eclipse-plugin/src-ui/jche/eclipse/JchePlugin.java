// Copyright 2026 Inoue Kazuhiro (instreest). SPDX-License-Identifier: Apache-2.0
package jche.eclipse;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * プラグインの入れ物。解析結果を持ち続ける {@link AnalysisService} の寿命を握る。
 *
 * バンドルは遅延起動（Bundle-ActivationPolicy: lazy）なので、コマンドを1度も使わなければ
 * ここは動かない。Eclipse の起動を遅くしないため、ここでは何も解析しない。
 */
public class JchePlugin extends AbstractUIPlugin {

    /** MANIFEST.MF の Bundle-SymbolicName（singleton 指定は含まない） */
    public static final String PLUGIN_ID = "io.github.instreest.jche.eclipse";

    private static JchePlugin instance;

    private AnalysisService service;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        service = new AnalysisService();
        service.start();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        try {
            if (service != null) {
                service.stop();
                service = null;
            }
            instance = null;
        } finally {
            super.stop(context);
        }
    }

    public static JchePlugin getDefault() {
        return instance;
    }

    /** 解析結果の常駐と再解析を受け持つサービス */
    public static AnalysisService service() {
        JchePlugin plugin = instance;
        return (plugin == null) ? null : plugin.service;
    }

    public static void log(int severity, String message, Throwable cause) {
        JchePlugin plugin = instance;
        IStatus status = new Status(severity, PLUGIN_ID, message, cause);
        if (plugin != null) {
            plugin.getLog().log(status);
        } else {
            System.err.println(message);
        }
    }
}
