package com.paulo.hyhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Bloco focável: imagem em cima, nome embaixo. Foco = borda azul + leve zoom. */
final class Tile extends LinearLayout {
    final ImageView image;
    final TextView label;
    private final GradientDrawable bg = new GradientDrawable();
    private final int colorNormal, colorFocus, colorAccent, stroke;
    private final int tileWidth, margin;

    Tile(Context c, int width, int imageHeight) {
        super(c);
        setOrientation(VERTICAL);
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);

        colorNormal = c.getColor(R.color.tile);
        colorFocus = c.getColor(R.color.tile_focus);
        colorAccent = c.getColor(R.color.accent);
        stroke = Ui.dp(c, 3);
        bg.setCornerRadius(Ui.dp(c, 8));
        bg.setColor(colorNormal);
        setBackground(bg);
        int pad = Ui.dp(c, 4);
        setPadding(pad, pad, pad, pad);

        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, imageHeight));

        label = new TextView(c);
        label.setTextColor(c.getColor(R.color.text));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 2));
        addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        tileWidth = width;
        margin = Ui.dp(c, 6);

        setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? colorFocus : colorNormal);
            bg.setStroke(has ? stroke : 0, colorAccent);
            float s = has ? 1.06f : 1f;
            animate().scaleX(s).scaleY(s).setDuration(80).start();
        });
    }

    /** Ícone quadrado em vez de banner 16:9: deixa menor e centralizado. */
    void setIconPadding(boolean icon) {
        int p = icon ? Ui.dp(getContext(), 14) : 0;
        image.setPadding(p, p / 2, p, p / 2);
    }

    GridLayout.LayoutParams gridParams() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = tileWidth;
        lp.height = LayoutParams.WRAP_CONTENT;
        lp.setMargins(margin, margin, margin, margin);
        return lp;
    }

    LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileWidth, LayoutParams.WRAP_CONTENT);
        lp.setMargins(margin, margin, margin, margin);
        return lp;
    }
}
