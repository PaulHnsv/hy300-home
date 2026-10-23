package com.paulo.hyhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.graphics.PorterDuff;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Locale ptBR = new Locale("pt", "BR");
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", ptBR);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d 'de' MMMM", ptBR);

    private Prefs prefs;
    private IconLoader icons;
    private List<AppEntry> apps = new ArrayList<>();
    private boolean showHidden;
    private String focusKey;            // tile que deve receber foco após redesenhar

    private ScrollView scroll;
    private LinearLayout content;
    private TextView clock, date, net;
    private int cols, appTileW, bannerH, shortcutH;

    private ConnectivityManager cm;
    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { main.post(MainActivity.this::updateNetwork); }
        @Override public void onLost(Network n) { main.post(MainActivity.this::updateNetwork); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { main.post(MainActivity.this::updateNetwork); }
    };

    private final Runnable reloadRunnable = this::reload;
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            icons.invalidate();
            main.removeCallbacks(reloadRunnable);
            main.postDelayed(reloadRunnable, 500);  // vários eventos seguidos numa instalação
        }
    };
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        icons = new IconLoader(getPackageManager());
        cm = getSystemService(ConnectivityManager.class);
        computeMetrics();
        setContentView(buildRoot());

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);

        cm.registerDefaultNetworkCallback(netCallback);
        reload();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        unregisterReceiver(timeReceiver);
        cm.unregisterNetworkCallback(netCallback);
        icons.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        updateNetwork();
    }

    /** Botão HOME com a tela inicial já aberta: volta ao topo. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        focusKey = null;
        scroll.scrollTo(0, 0);
        focusDefault();
    }

    @Override
    public void onBackPressed() { /* tela inicial: não há para onde voltar */ }

    // ---------------------------------------------------------------- layout

    private void computeMetrics() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int side = Ui.dp(this, 24), margin = Ui.dp(this, 6);
        int usable = screenW - 2 * side;
        int target = Ui.dp(this, 150);
        cols = Math.max(4, usable / (target + 2 * margin));
        appTileW = usable / cols - 2 * margin;
        bannerH = (appTileW - Ui.dp(this, 8)) * 9 / 16;
        shortcutH = Ui.dp(this, 44);
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 24);
        root.setPadding(side, Ui.dp(this, 16), side, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        clock = Ui.text(this, 34, R.color.text);
        date = Ui.text(this, 15, R.color.text_dim);
        date.setPadding(Ui.dp(this, 12), 0, 0, Ui.dp(this, 4));
        View spacer = new View(this);
        net = Ui.text(this, 15, R.color.text_dim);
        net.setPadding(0, 0, Ui.dp(this, 6), Ui.dp(this, 4));
        header.addView(clock);
        header.addView(date);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        header.addView(net);
        root.addView(header);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 24));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void reload() {
        apps = AppStore.load(this);
        seedFavorites();
        render();
    }

    /** Na primeira abertura, favorita os apps de vídeo que estiverem instalados. */
    private void seedFavorites() {
        if (prefs.favoritesSeeded() || apps.isEmpty()) return;
        String[] wanted = {"org.smarttube.stable", "com.mxtech.videoplayer.ad", "com.netflix.mediaclient",
                "com.amazon.avod.thirdpartyclient", "com.waxrain.airplayer2"};
        List<String> favs = new ArrayList<>();
        for (String pkg : wanted)
            for (AppEntry a : apps)
                if (a.info.packageName.equals(pkg)) { favs.add(a.key); break; }
        prefs.setFavorites(favs);
        prefs.markFavoritesSeeded();
    }

    /** Atalho "Limpar RAM": encerra processos de fundo e mostra quanto ficou livre. */
    private void cleanMemory() {
        final ActivityManager am = getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        final long before = mi.availMem >> 20;
        freeMemory(null);
        main.postDelayed(() -> {
            ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(m);
            long after = m.availMem >> 20;
            Toast.makeText(this, "RAM livre: " + after + " MB (" + (after >= before ? "+" : "") + (after - before) + ")",
                    Toast.LENGTH_LONG).show();
            updateStatus();
        }, 1200);
    }

    private void render() {
        View focused = getCurrentFocus();
        if (focusKey == null && focused != null && focused.getTag() instanceof String) focusKey = (String) focused.getTag();
        content.removeAllViews();

        // Projetor
        content.addView(sectionTitle("Projetor"));
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        int scW = (getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 48)) / (Shortcuts.all().size() + 1)
                - Ui.dp(this, 12);
        for (final Shortcuts.Item s : Shortcuts.all()) {
            Tile t = new Tile(this, scW, shortcutH);
            t.setTag("sc:" + s.label);
            t.image.setImageResource(s.icon);
            t.image.setColorFilter(getColor(R.color.accent), PorterDuff.Mode.SRC_IN);
            t.image.setPadding(0, Ui.dp(this, 6), 0, 0);
            t.label.setText(s.label);
            t.setOnClickListener(v -> launch(s.intent, null));
            row.addView(t, t.rowParams());
        }
        Tile mem = new Tile(this, scW, shortcutH);
        mem.setTag("sc:ram");
        mem.image.setImageResource(R.drawable.sc_memory);
        mem.image.setColorFilter(getColor(R.color.accent), PorterDuff.Mode.SRC_IN);
        mem.image.setPadding(0, Ui.dp(this, 6), 0, 0);
        mem.label.setText("Limpar RAM");
        mem.setOnClickListener(v -> cleanMemory());
        row.addView(mem, mem.rowParams());
        content.addView(row);

        Map<String, AppEntry> byKey = new HashMap<>();
        for (AppEntry a : apps) byKey.put(a.key, a);
        Set<String> hidden = prefs.hidden();

        // Favoritos (na ordem escolhida)
        List<AppEntry> favs = new ArrayList<>();
        for (String k : prefs.favorites()) if (byKey.containsKey(k)) favs.add(byKey.get(k));
        if (!favs.isEmpty()) {
            content.addView(sectionTitle("Favoritos"));
            GridLayout g = grid();
            for (AppEntry a : favs) g.addView(appTile(a));
            content.addView(g);
        }

        // Todos os apps (menos favoritos; ocultos só aparecem se pedido)
        List<AppEntry> rest = new ArrayList<>();
        int hiddenCount = 0;
        for (AppEntry a : apps) {
            if (favs.contains(a)) continue;
            if (hidden.contains(a.key)) {
                hiddenCount++;
                if (!showHidden) continue;
            }
            rest.add(a);
        }
        content.addView(sectionTitle("Apps"));
        GridLayout all = grid();
        for (AppEntry a : rest) all.addView(appTile(a));
        content.addView(all);

        if (hiddenCount > 0) {
            Tile toggle = new Tile(this, appTileW, shortcutH);
            toggle.setTag("toggle");
            toggle.label.setText(showHidden ? "Esconder ocultos" : "Mostrar ocultos (" + hiddenCount + ")");
            toggle.setOnClickListener(v -> { showHidden = !showHidden; focusKey = "toggle"; render(); });
            content.addView(toggle, toggle.rowParams());
        }

        final String want = focusKey;
        focusKey = null;
        content.post(() -> {
            View target = want == null ? null : content.findViewWithTag(want);
            if (target != null) target.requestFocus(); else focusDefault();
        });
    }

    private Tile appTile(final AppEntry a) {
        final Tile t = new Tile(this, appTileW, bannerH);
        t.setTag("app:" + a.key);
        t.setLayoutParams(t.gridParams());
        t.label.setText(a.label);
        icons.load(a, t.image, (d, isBanner) -> {
            t.image.setImageDrawable(d);
            t.setIconPadding(!isBanner);
        });
        t.setOnClickListener(v -> launch(AppStore.launchIntent(a), a));
        t.setOnLongClickListener(v -> { showMenu(a); return true; });
        return t;
    }

    private void showMenu(final AppEntry a) {
        final List<String> favs = prefs.favorites();
        final Set<String> hidden = prefs.hidden();
        final boolean isFav = favs.contains(a.key), isHidden = hidden.contains(a.key);
        String[] items = {
                isFav ? "Remover dos favoritos" : "Adicionar aos favoritos",
                isHidden ? "Mostrar na lista" : "Ocultar da lista",
                "Informações do app"};
        new AlertDialog.Builder(this).setTitle(a.label).setItems(items, (d, which) -> {
            focusKey = "app:" + a.key;
            if (which == 0) {
                if (isFav) favs.remove(a.key); else favs.add(a.key);
                prefs.setFavorites(favs);
                render();
            } else if (which == 1) {
                if (isHidden) hidden.remove(a.key); else hidden.add(a.key);
                prefs.setHidden(hidden);
                render();
            } else {
                launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", a.info.packageName, null)), null);
            }
        }).show();
    }

    private TextView sectionTitle(String s) {
        TextView t = Ui.text(this, 14, R.color.text_dim);
        t.setPadding(Ui.dp(this, 6), Ui.dp(this, 14), 0, Ui.dp(this, 2));
        t.setText(s.toUpperCase(ptBR));
        return t;
    }

    private GridLayout grid() {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(cols);
        g.setClipChildren(false);
        return g;
    }

    private void focusDefault() {
        ArrayList<View> f = new ArrayList<>();
        content.addFocusables(f, View.FOCUS_DOWN);
        if (!f.isEmpty()) f.get(0).requestFocus();
    }

    private void launch(Intent i, AppEntry app) {
        try {
            if (app != null) freeMemory(app.info.packageName);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Com ~1 GB de RAM, encerra processos em segundo plano dos outros apps antes de abrir um.
     * killBackgroundProcesses só atinge o que o sistema já considera dispensável.
     * O receptor de AirPlay fica de fora para não derrubar o espelhamento.
     */
    private void freeMemory(String keep) {
        ActivityManager am = getSystemService(ActivityManager.class);
        for (AppEntry a : apps) {
            String pkg = a.info.packageName;
            if (pkg.equals(keep) || pkg.equals(getPackageName())) continue;
            if ((a.info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (pkg.contains("airplay") || pkg.contains("waxrain")) continue;
            am.killBackgroundProcesses(pkg);
        }
    }

    // ---------------------------------------------------------------- status

    private String netText = "";

    private void updateClock() {
        Date now = new Date();
        clock.setText(timeFmt.format(now));
        String d = dateFmt.format(now);
        date.setText(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
        updateStatus();
    }

    private void updateNetwork() {
        Network n = cm.getActiveNetwork();
        NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
        if (c == null) netText = "Sem rede";
        else if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) netText = "Ethernet";
        else if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) netText = "Wi-Fi";
        else netText = "Conectado";
        updateStatus();
    }

    /** Rede, RAM livre e temperatura do SoC (a térmica é o limite deste aparelho). */
    private void updateStatus() {
        StringBuilder sb = new StringBuilder(netText);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(mi);
        sb.append("  ·  RAM livre ").append(mi.availMem >> 20).append(" MB");
        int temp = socTemperature();
        if (temp > 0) sb.append("  ·  ").append(temp).append(" °C");
        net.setText(sb);
    }

    private static int socTemperature() {
        try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/thermal/thermal_zone0/temp"))) {
            return Integer.parseInt(r.readLine().trim()) / 1000;
        } catch (Exception e) {
            return -1;
        }
    }
}
