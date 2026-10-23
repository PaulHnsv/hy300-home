package com.paulo.hyhome;

import android.content.ComponentName;
import android.content.Intent;
import android.media.tv.TvContract;
import android.provider.Settings;

import java.util.Arrays;
import java.util.List;

/** Atalhos fixos do HY300-T (keystone, HDMI e ajustes). */
final class Shortcuts {
    static final class Item {
        final String label;
        final int icon;
        final Intent intent;
        Item(String label, int icon, Intent intent) {
            this.label = label;
            this.icon = icon;
            this.intent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
    }

    /** Entrada HDMI física do HY300-T (TvInputService da Droidlogic). */
    private static final String HDMI_INPUT_ID = "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5";

    private Shortcuts() {}

    static List<Item> all() {
        return Arrays.asList(
                new Item("Keystone", R.drawable.sc_keystone, new Intent().setComponent(new ComponentName(
                        "com.softwinner.tcorrection", "com.softwinner.tcorrection.projection.ProjectionSettingActivity"))),
                new Item("Projeção", R.drawable.sc_projector, new Intent("action.htcsettings.prjsettings")
                        .setPackage("com.htc.htcsettings")),
                new Item("HDMI", R.drawable.sc_hdmi, new Intent(Intent.ACTION_VIEW,
                        TvContract.buildChannelUriForPassthroughInput(HDMI_INPUT_ID))),
                new Item("Wi-Fi", R.drawable.sc_wifi, new Intent(Settings.ACTION_WIFI_SETTINGS)),
                new Item("Bluetooth", R.drawable.sc_bluetooth, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)),
                new Item("Ajustes", R.drawable.sc_settings, new Intent(Settings.ACTION_SETTINGS)));
    }
}
