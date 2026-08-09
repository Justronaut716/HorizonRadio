package com.horizonradio.core.server;

import java.util.Locale;

import com.horizonradio.core.model.SearchResult;

/** Filters obvious non-music videos from the generic YouTube search feed. */
public final class MusicSearchFilter {

    private static final String[] NON_MUSIC_MARKERS = { "podcast", "vlog", "tutorial", "anleitung", "how to",
        "reaktion", "reaction", "reacts", "reacting", "gameplay", "walkthrough", "lets play", "news", "nachrichten",
        "interview", "review", "unboxing", "documentary", "dokumentation", "trailer", "teaser", "webinar", "lesson",
        "lektion", "course", "kurs", "highlights", "comedy", "komodie", "prank", "streich", "challenge", "rezept",
        "recipe", "cooking", "kochen", "gaming", "minecraft", "fortnite", "roblox", "asmr", "talk show", "talkshow",
        "behind the scenes", "making of" };

    private MusicSearchFilter() {}

    public static boolean isLikelyMusic(SearchResult result) {
        if (result == null) {
            return false;
        }
        String title = normalize(result.getTitle());
        if (title.length() == 0 || containsNonMusicMarker(title)) {
            return false;
        }
        return !containsNonMusicMarker(normalize(result.getChannel()));
    }

    private static boolean containsNonMusicMarker(String value) {
        if (value.length() == 0) {
            return false;
        }
        String padded = " " + value + " ";
        for (String marker : NON_MUSIC_MARKERS) {
            if (padded.contains(" " + marker + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? ""
            : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }
}
