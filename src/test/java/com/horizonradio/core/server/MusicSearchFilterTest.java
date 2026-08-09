package com.horizonradio.core.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.horizonradio.core.model.SearchResult;

public class MusicSearchFilterTest {

    @Test
    public void rejectsCommonNonMusicVideoCategories() {
        assertFalse(MusicSearchFilter.isLikelyMusic(result("The Daily Podcast")));
        assertFalse(MusicSearchFilter.isLikelyMusic(result("How to make a guitar stand")));
        assertFalse(MusicSearchFilter.isLikelyMusic(result("Reaction to the new music video")));
        assertFalse(MusicSearchFilter.isLikelyMusic(result("Minecraft gameplay highlights")));
    }

    @Test
    public void keepsSongsOfficialVideosAndRemixes() {
        assertTrue(MusicSearchFilter.isLikelyMusic(result("Artist - Song (Official Music Video)")));
        assertTrue(MusicSearchFilter.isLikelyMusic(result("Artist - Song (Official Audio)")));
        assertTrue(MusicSearchFilter.isLikelyMusic(result("Artist - Song (Remix)")));
        assertTrue(MusicSearchFilter.isLikelyMusic(result("Artist - Song (Acoustic Cover)")));
    }

    private static SearchResult result(String title) {
        return new SearchResult("id", title, "channel", "3:00", "");
    }
}
