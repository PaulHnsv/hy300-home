package com.paulo.hyhome;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;

/** Um app instalável a partir da tela inicial. */
final class AppEntry {
    final String key;           // ComponentName curto, usado nas preferências
    final String label;
    final ComponentName component;
    final ActivityInfo info;
    final boolean leanback;     // tem atividade própria para TV (normalmente com banner)

    AppEntry(String label, ActivityInfo info, boolean leanback) {
        this.label = label;
        this.info = info;
        this.leanback = leanback;
        this.component = new ComponentName(info.packageName, info.name);
        this.key = component.flattenToShortString();
    }
}
