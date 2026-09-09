package com.horizonradio.server;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import com.horizonradio.core.model.PlaylistSearchResult;

public class PlaylistSearchServiceTest {

    @Test
    public void parsesModernPlaylistCardsAndIgnoresVideos() {
        String json = "{\"items\":[{\"lockupViewModel\":{\"contentType\":\"LOCKUP_CONTENT_TYPE_PLAYLIST\",\"contentId\":\"PLjazz\","
            + "\"metadata\":{\"lockupMetadataViewModel\":{\"title\":{\"content\":\"Jazz collection\"},\"metadataParts\":[{\"text\":{\"content\":\"Jazz channel\"}}]}},"
            + "\"thumbnailBadgeViewModel\":{\"text\":\"35 videos\"}}},{\"lockupViewModel\":{\"contentType\":\"LOCKUP_CONTENT_TYPE_VIDEO\",\"contentId\":\"video\"}}]}";
        List<PlaylistSearchResult> results = PlaylistSearchService.parse(json);
        assertEquals(1, results.size());
        assertEquals("Jazz collection", results.get(0).title);
        assertEquals("Jazz channel", results.get(0).author);
        assertEquals("35 videos", results.get(0).videoCount);
        assertEquals(
            "https://www.youtube.com/playlist?list=PLjazz",
            results.get(0)
                .url());
    }

    @Test
    public void parsesClassicCardsWithoutDuplicatesOrUnsafeIds() {
        String card = "{\"playlistRenderer\":{\"playlistId\":\"PLclassic\",\"title\":{\"runs\":[{\"text\":\"Classics\"}]},\"videoCount\":\"10\"}}";
        List<PlaylistSearchResult> results = PlaylistSearchService.parse(
            "[" + card
                + ","
                + card
                + ",{\"playlistRenderer\":{\"playlistId\":\"bad&url\",\"title\":{\"simpleText\":\"Bad\"}}}]");
        assertEquals(1, results.size());
        assertEquals("Classics", results.get(0).title);
    }
}
