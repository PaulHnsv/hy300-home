package com.paulo.hyhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Favoritos (com ordem) e apps ocultos. */
final class Prefs {
    private static final String FAVORITES = "favorites";
    private static final String HIDDEN = "hidden";
    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    List<String> favorites() {
        String raw = sp.getString(FAVORITES, "");
        return raw.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(raw.split("\n")));
    }

    void setFavorites(List<String> keys) {
        sp.edit().putString(FAVORITES, TextUtils.join("\n", keys)).apply();
    }

    /** Favoritos padrão são criados uma única vez; depois a escolha é do usuário. */
    boolean favoritesSeeded() {
        return sp.getBoolean("seeded", false);
    }

    void markFavoritesSeeded() {
        sp.edit().putBoolean("seeded", true).apply();
    }

    Set<String> hidden() {
        return new HashSet<>(sp.getStringSet(HIDDEN, new HashSet<>()));
    }

    void setHidden(Set<String> keys) {
        sp.edit().putStringSet(HIDDEN, keys).apply();
    }
}
