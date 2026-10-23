package com.paulo.hyhome;

import android.content.Context;
import android.util.TypedValue;
import android.widget.TextView;

final class Ui {
    private Ui() {}

    static int dp(Context c, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, float sp, int colorRes) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(c.getColor(colorRes));
        t.setIncludeFontPadding(false);
        return t;
    }
}
