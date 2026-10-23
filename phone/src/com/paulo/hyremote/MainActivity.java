package com.paulo.hyremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/** Controle remoto do HY300-T: conversa com o ADB do projetor pela rede, sem cabo e sem app no projetor. */
public final class MainActivity extends Activity implements Controller.Events {
    private static final int BG = 0xFF0E1116, TILE = 0xFF1B2029, HOVER = 0xFF3A4660, ACCENT = 0xFF4FA3FF,
            TEXT = 0xFFE8ECF2, DIM = 0xFF8A93A3, GOOD = 0xFF3FB950, WARN = 0xFFE3B341, BAD = 0xFFF85149;

    private static final Map<String, Integer> KEYS = new HashMap<>();
    static {
        KEYS.put("up", 19); KEYS.put("down", 20); KEYS.put("left", 21); KEYS.put("right", 22); KEYS.put("ok", 23);
        KEYS.put("back", 4); KEYS.put("home", 3); KEYS.put("menu", 82); KEYS.put("power", 26);
        KEYS.put("vol_up", 24); KEYS.put("vol_down", 25); KEYS.put("mute", 164); KEYS.put("play", 85);
    }

    /** nome, alvo (pacote ou pacote/atividade), cor do ícone, letra. */
    private static final String[][] APPS = {
            {"SmartTube", "org.smarttube.stable", "#E5322D", "S"},
            {"Netflix", "com.netflix.mediaclient", "#B20710", "N"},
            {"MX Player", "com.mxtech.videoplayer.ad", "#2D6BD8", "MX"},
            {"TV Bro", "com.phlox.tvwebbrowser", "#2A8F6B", "W"},
            {"Keystone", "com.softwinner.tcorrection/com.softwinner.tcorrection.projection.ProjectionSettingActivity", "#2A3242", "⌗"},
            {"HY Home", "com.paulo.hyhome/.MainActivity", "#4FA3FF", "H"},
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private AdbKey key;
    private Controller ctl;
    private View dot;
    private TextView ramView, tempView, footer;
    private Runnable resetFooter;
    private float density;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        density = getResources().getDisplayMetrics().density;
        sp = getSharedPreferences("hyremote", MODE_PRIVATE);
        key = loadKey();
        String model = Build.MODEL == null ? "celular" : Build.MODEL.replaceAll("[^A-Za-z0-9]", "_");
        ctl = new Controller(key, "hyremote@" + model, this);
        ctl.start();
        setContentView(buildUi());
        String host = sp.getString("host", "");
        if (host.isEmpty()) showSetup(); else ctl.setTarget(host);
    }

    @Override
    protected void onDestroy() {
        ctl.shutdown();
        super.onDestroy();
    }

    /** Volume do celular controla o volume do projetor. */
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP) { send("vol_up"); return true; }
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) { send("vol_down"); return true; }
        return super.onKeyDown(code, e);
    }

    private AdbKey loadKey() {
        try {
            String s = sp.getString("key", null);
            if (s != null) return AdbKey.fromPkcs8(Base64.decode(s, Base64.NO_WRAP));
            AdbKey k = AdbKey.generate();
            sp.edit().putString("key", Base64.encodeToString(k.pkcs8(), Base64.NO_WRAP)).apply();
            return k;
        } catch (Exception e) {
            throw new IllegalStateException("não foi possível criar a chave RSA", e);
        }
    }

    // ------------------------------------------------------------------ eventos do Controller (outra thread)
    @Override
    public void state(final int state, final String msg) {
        ui.post(() -> {
            int color = state == OK_STATE ? GOOD : state == Controller.OFFLINE ? BAD : WARN;
            dot.setBackground(circle(color));
            if (state != Controller.OK) {
                ramView.setText("—");
                tempView.setText("—");
                tempView.setTextColor(TEXT);
                hint(msg, 0);
            } else {
                hint(null, 0);
            }
        });
    }

    private static final int OK_STATE = Controller.OK;

    @Override
    public void stats(final int mem, final int temp) {
        ui.post(() -> {
            ramView.setText(mem + " MB");
            ramView.setTextColor(mem < 120 ? BAD : TEXT);
            if (temp > 0) {
                tempView.setText(temp + " °C");
                tempView.setTextColor(temp >= 90 ? BAD : temp >= 80 ? WARN : TEXT);
            }
        });
    }

    @Override
    public void toast(final String msg) {
        ui.post(() -> hint(msg, 4000));
    }

    // ------------------------------------------------------------------ interface
    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(12), dp(16), dp(16));
        sv.addView(root);

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(this);
        dot.setBackground(circle(WARN));
        head.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        TextView name = text("HY300-T", 17, TEXT, true);
        name.setPadding(dp(8), 0, 0, 0);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView more = text("⋯", 24, TEXT, false);
        more.setPadding(dp(12), 0, dp(4), dp(4));
        more.setOnClickListener(v -> showMenu(more));
        head.addView(more);
        root.addView(head);

        LinearLayout cards = row();
        ramView = card(cards, "RAM livre", 0);
        tempView = card(cards, "Temperatura", dp(8));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(12), 0, dp(12));
        root.addView(cards, cp);

        DPadView pad = new DPadView(this, this::send);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(pad, pp);

        root.addView(tileRow(
                tile("↩", "Voltar", false, () -> send("back")),
                tile("⌂", "Início", false, () -> send("home")),
                tile("≡", "Menu", false, () -> send("menu"))));
        root.addView(tileRow(
                tile("−", "Vol −", true, () -> send("vol_down")),
                tile("🔇", "Mudo", false, () -> send("mute")),
                tile("+", "Vol +", true, () -> send("vol_up"))));

        TextView apps = text("APPS", 11, DIM, false);
        apps.setPadding(dp(2), dp(12), 0, dp(6));
        root.addView(apps);
        for (int i = 0; i < APPS.length; i += 3) {
            View[] r = new View[3];
            for (int j = 0; j < 3; j++) r[j] = appTile(APPS[i + j]);
            root.addView(tileRow(r));
        }

        LinearLayout typer = row();
        typer.setGravity(Gravity.CENTER_VERTICAL);
        final EditText input = new EditText(this);
        input.setHint("Digitar no projetor");
        input.setHintTextColor(DIM);
        input.setTextColor(TEXT);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(round(TILE, 10));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        typer.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView sendBtn = tile("➤", "Enviar", false, () -> sendText(input));
        typer.addView(sendBtn, new LinearLayout.LayoutParams(dp(72), dp(52)));
        input.setOnEditorActionListener((v, a, e) -> { sendText(input); return true; });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.setMargins(0, dp(10), 0, 0);
        root.addView(typer, tp);

        footer = text("Volume do celular = volume do projetor.", 11, DIM, false);
        footer.setPadding(dp(2), dp(10), 0, 0);
        root.addView(footer);
        return sv;
    }

    private TextView card(LinearLayout parent, String title, int leftMargin) {
        LinearLayout c = column();
        c.setBackground(round(TILE, 10));
        c.setPadding(dp(12), dp(8), dp(12), dp(10));
        c.addView(text(title, 11, DIM, false));
        TextView v = text("—", 18, TEXT, true);
        c.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = leftMargin;
        parent.addView(c, lp);
        return v;
    }

    private LinearLayout tileRow(View... tiles) {
        LinearLayout r = row();
        for (View t : tiles) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(62), 1f);
            lp.setMargins(dp(3), dp(4), dp(3), dp(4));
            r.addView(t, lp);
        }
        return r;
    }

    /** Botão com símbolo em cima e rótulo embaixo. Com `repeat`, segurar repete a ação. */
    private TextView tile(String glyph, String label, final boolean repeat, final Runnable action) {
        final TextView t = new TextView(this);
        android.text.SpannableString label2 = new android.text.SpannableString(glyph + "\n" + label);
        label2.setSpan(new android.text.style.RelativeSizeSpan(1.7f), 0, glyph.length(), 0);
        t.setText(label2);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(TILE, 10));
        t.setClickable(true);
        final Runnable again = new Runnable() {
            @Override public void run() { action.run(); ui.postDelayed(this, 130); }
        };
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setBackground(round(HOVER, 10));
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (repeat) { action.run(); ui.postDelayed(again, 400); }
                    return true;
                case MotionEvent.ACTION_UP:
                    ui.removeCallbacks(again);
                    v.setBackground(round(TILE, 10));
                    if (!repeat && e.getX() >= 0 && e.getX() <= v.getWidth() && e.getY() >= 0 && e.getY() <= v.getHeight())
                        action.run();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(again);
                    v.setBackground(round(TILE, 10));
                    return true;
                default:
                    return true;
            }
        });
        return t;
    }

    private View appTile(final String[] app) {
        LinearLayout c = column();
        c.setGravity(Gravity.CENTER);
        c.setBackground(round(TILE, 10));
        TextView badge = text(app[3], 14, "#4FA3FF".equals(app[2]) ? BG : Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(round(Color.parseColor(app[2]), 8));
        c.addView(badge, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView label = text(app[0], 11, TEXT, false);
        label.setPadding(0, dp(4), 0, 0);
        c.addView(label);
        c.setClickable(true);
        c.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            openApp(app[1]);
        });
        return c;
    }

    // ------------------------------------------------------------------ ações
    private void send(String name) {
        ctl.key(KEYS.get(name));
    }

    private void openApp(String target) {
        if (target.contains("/")) ctl.shell("am start -n " + target);
        else ctl.shell("monkey -p " + target + " -c android.intent.category.LEANBACK_LAUNCHER 1");
        hint("Abrindo " + target.split("/")[0] + "…", 2500);
    }

    private void sendText(EditText input) {
        String s = input.getText().toString();
        if (s.isEmpty()) return;
        if (!s.matches("\\A\\p{ASCII}*\\z")) {
            hint("Só ASCII: acentos não funcionam com o `input text` do Android.", 4000);
            return;
        }
        ctl.shell("input text '" + s.replace("'", "'\\''").replace(" ", "%s") + "'");
        input.setText("");
    }

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Ligar/desligar a tela");
        m.getMenu().add(0, 2, 1, "Limpar RAM (processos em cache)");
        m.getMenu().add(0, 3, 2, "Configurar projetor");
        m.getMenu().add(0, 4, 3, "Ver minha chave ADB");
        m.setOnMenuItemClickListener(i -> {
            switch (i.getItemId()) {
                case 1: send("power"); break;
                case 2: ctl.shell("am kill-all"); hint("Limpando processos em cache…", 2500); break;
                case 3: showSetup(); break;
                default: showKey(); break;
            }
            return true;
        });
        m.show();
    }

    private void showSetup() {
        LinearLayout box = column();
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText ip = new EditText(this);
        ip.setHint("192.168.0.15");
        ip.setText(sp.getString("host", ""));
        ip.setSingleLine(true);
        box.addView(ip);
        TextView help = text("IP do projetor na rede Wi-Fi. A depuração por Wi-Fi (ADB, porta 5555) precisa estar ligada. "
                + "No primeiro uso, aceite a chave na tela do projetor e marque \"Sempre permitir\".", 12, 0xFF555555, false);
        help.setPadding(0, dp(8), 0, 0);
        box.addView(help);
        new AlertDialog.Builder(this).setTitle("Projetor").setView(box)
                .setPositiveButton("Conectar", (d, w) -> {
                    String h = ip.getText().toString().trim();
                    sp.edit().putString("host", h).apply();
                    ctl.setTarget(h);
                })
                .setNeutralButton("Minha chave", (d, w) -> showKey())
                .setNegativeButton("Cancelar", null).show();
    }

    /** Mostra a chave pública para autorizar pelo PC, se o pedido na tela do projetor não aparecer. */
    private void showKey() {
        final String line = key.publicKeyText("hyremote");
        new AlertDialog.Builder(this).setTitle("Chave ADB deste celular")
                .setMessage("Se o projetor não mostrar o pedido de autorização, acrescente esta linha a "
                        + "/data/misc/adb/adb_keys (como root, pelo adb do PC):\n\n" + line)
                .setPositiveButton("Copiar", (d, w) -> {
                    ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("chave adb", line));
                    Toast.makeText(this, "Copiada", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Fechar", null).show();
    }

    private void hint(String text, long ms) {
        if (resetFooter != null) ui.removeCallbacks(resetFooter);
        final String idle = "Volume do celular = volume do projetor.";
        footer.setText(text == null || text.isEmpty() ? idle : text);
        if (ms > 0) {
            resetFooter = () -> footer.setText(idle);
            ui.postDelayed(resetFooter, ms);
        }
    }

    // ------------------------------------------------------------------ helpers de layout
    private int dp(float v) {
        return Math.round(v * density);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }
}
