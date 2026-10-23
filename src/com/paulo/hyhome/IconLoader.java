package com.paulo.hyhome;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Carrega banners/ícones fora da thread de UI e guarda em cache. */
final class IconLoader {
    interface Callback { void onLoaded(Drawable d, boolean isBanner); }

    private static final class Entry {
        final Drawable.ConstantState state;
        final boolean banner;
        Entry(Drawable.ConstantState state, boolean banner) { this.state = state; this.banner = banner; }
    }

    private final PackageManager pm;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Entry> cache = new HashMap<>();

    IconLoader(PackageManager pm) { this.pm = pm; }

    void load(final AppEntry app, final ImageView target, final Callback cb) {
        target.setTag(app.key);
        Entry cached = cache.get(app.key);
        if (cached != null) {
            cb.onLoaded(cached.state.newDrawable(), cached.banner);
            return;
        }
        worker.execute(() -> {
            Drawable d = app.info.loadBanner(pm);
            if (d == null) d = app.info.applicationInfo.loadBanner(pm);
            boolean banner = d != null;
            if (d == null) d = app.info.loadIcon(pm);
            final Entry e = d.getConstantState() != null ? new Entry(d.getConstantState(), banner) : null;
            final Drawable fallback = d;
            final boolean isBanner = banner;
            main.post(() -> {
                if (e != null) cache.put(app.key, e);
                if (!app.key.equals(target.getTag())) return;  // a view já foi reaproveitada
                cb.onLoaded(e != null ? e.state.newDrawable() : fallback, isBanner);
            });
        });
    }

    void invalidate() { cache.clear(); }

    void shutdown() { worker.shutdownNow(); }
}
