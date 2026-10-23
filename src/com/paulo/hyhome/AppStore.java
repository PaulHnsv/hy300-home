package com.paulo.hyhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lista os apps abríveis, preferindo a atividade de TV quando o app tem as duas. */
final class AppStore {
    private AppStore() {}

    static List<AppEntry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, byPackage);
        collect(pm, Intent.CATEGORY_LAUNCHER, false, byPackage);
        byPackage.remove(context.getPackageName());

        // Apps de pacotes diferentes com o mesmo nome (ex.: dois "Configurações"):
        // fica o que tem atividade de TV; se empatar, o primeiro encontrado.
        Map<String, AppEntry> byLabel = new LinkedHashMap<>();
        for (AppEntry a : byPackage.values()) {
            String k = a.label.toLowerCase(Locale.ROOT);
            AppEntry prev = byLabel.get(k);
            if (prev == null || (a.leanback && !prev.leanback)) byLabel.put(k, a);
        }
        List<AppEntry> apps = new ArrayList<>(byLabel.values());
        final Collator collator = Collator.getInstance(new Locale("pt", "BR"));
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(apps, (a, b) -> collator.compare(a.label, b.label));
        return apps;
    }

    private static void collect(PackageManager pm, String category, boolean leanback,
                                Map<String, AppEntry> out) {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (out.containsKey(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            out.put(pkg, new AppEntry(label == null ? pkg : label.toString(), ri.activityInfo, leanback));
        }
    }

    static Intent launchIntent(AppEntry app) {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(app.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }
}
