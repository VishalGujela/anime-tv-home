package com.vishal.animehome;

/** One character theme: colors, symbol, wallpapers, quotes and focus effect. */
final class Theme {
    final String id, name, series, symbol;
    final int accent, chipBg;
    final int fx;
    final String[] wallpapers;
    final String[] quotes;

    Theme(String id, String name, String series, String symbol, int accent, int chipBg, int fx,
          String[] wallpapers, String[] quotes) {
        this.id = id;
        this.name = name;
        this.series = series;
        this.symbol = symbol;
        this.accent = accent;
        this.chipBg = chipBg;
        this.fx = fx;
        this.wallpapers = wallpapers;
        this.quotes = quotes;
    }

    static final Theme[] ALL = {
        new Theme("kakashi", "KAKASHI HATAKE", "Naruto", "雷", 0xFF4FC3F7, 0xFF16213E, FxView.NONE,
            new String[] {"kakashi_1", "kakashi_2"},
            new String[] {
                "“Those who abandon their friends are worse than scum.”",
                "“I'll never let my comrades die. I'll protect you with my life.”",
            }),
    };
}
