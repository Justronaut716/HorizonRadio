package com.horizonradio.core.server;

import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves multilingual country and global chart names to canonical regions. */
public final class ChartRegionCatalog {

    public static final String GLOBAL_CODE = "GLOBAL";

    private static final Map<String, ChartRegion> REGIONS_BY_CODE;
    private static final Map<String, Set<String>> CODES_BY_ALIAS;

    static {
        Map<String, ChartRegion> regions = new HashMap<String, ChartRegion>();
        Map<String, Set<String>> aliases = new HashMap<String, Set<String>>();

        ChartRegion global = new ChartRegion(GLOBAL_CODE, "ZZ", "Global");
        regions.put(global.getCode(), global);
        addAlias(aliases, "Global", global.getCode());
        addAlias(aliases, "Weltweit", global.getCode());
        addAlias(aliases, "Worldwide", global.getCode());
        addAlias(aliases, "World", global.getCode());
        addAlias(aliases, "Mondial", global.getCode());
        addAlias(aliases, "Mundial", global.getCode());

        for (String countryCode : Locale.getISOCountries()) {
            String code = countryCode.toUpperCase(Locale.ROOT);
            Locale country = new Locale("", code);
            String displayName = country.getDisplayCountry(Locale.ENGLISH);
            if (displayName == null || displayName.length() == 0) {
                displayName = country.getDisplayCountry(Locale.GERMAN);
            }
            if (displayName == null || displayName.length() == 0) {
                displayName = code;
            }
            ChartRegion region = new ChartRegion(code, code.toLowerCase(Locale.ROOT), displayName);
            regions.put(code, region);
            addAlias(aliases, code, code);
        }

        for (ChartRegion region : regions.values()) {
            if (GLOBAL_CODE.equals(region.getCode())) {
                continue;
            }
            Locale country = new Locale("", region.getCode());
            for (Locale locale : Locale.getAvailableLocales()) {
                addAlias(aliases, country.getDisplayCountry(locale), region.getCode());
            }
        }

        addAlias(aliases, "Deutschland", "DE");
        addAlias(aliases, "Germany", "DE");
        addAlias(aliases, "Allemagne", "DE");
        addAlias(aliases, "Alemania", "DE");
        addAlias(aliases, "ドイツ", "DE");
        addAlias(aliases, "Amerika", "US");
        addAlias(aliases, "America", "US");
        addAlias(aliases, "USA", "US");
        addAlias(aliases, "United States", "US");
        addAlias(aliases, "United States of America", "US");
        addAlias(aliases, "Vereinigte Staaten", "US");
        addAlias(aliases, "États-Unis", "US");
        addAlias(aliases, "Estados Unidos", "US");
        addAlias(aliases, "Côte d'Ivoire", "CI");
        addAlias(aliases, "Côte d’Ivoire", "CI");
        addAlias(aliases, "Cote d'Ivoire", "CI");
        addAlias(aliases, "Ivory Coast", "CI");
        addAlias(aliases, "Elfenbeinküste", "CI");
        addAlias(aliases, "日本", "JP");
        addAlias(aliases, "Congo", "CG");
        addAlias(aliases, "Congo", "CD");

        REGIONS_BY_CODE = Collections.unmodifiableMap(regions);
        Map<String, Set<String>> immutableAliases = new HashMap<String, Set<String>>();
        for (Map.Entry<String, Set<String>> entry : aliases.entrySet()) {
            immutableAliases.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<String>(entry.getValue())));
        }
        CODES_BY_ALIAS = Collections.unmodifiableMap(immutableAliases);
    }

    private ChartRegionCatalog() {}

    public static ChartRegion global() {
        return REGIONS_BY_CODE.get(GLOBAL_CODE);
    }

    public static ChartRegion byCode(String value) {
        if (value == null) {
            return null;
        }
        return REGIONS_BY_CODE.get(
            value.trim()
                .toUpperCase(Locale.ROOT));
    }

    public static ChartRegion resolve(String value) {
        String normalized = normalize(value);
        Set<String> codes = CODES_BY_ALIAS.get(normalized);
        if (codes == null || codes.size() != 1) {
            return null;
        }
        return byCode(
            codes.iterator()
                .next());
    }

    public static boolean isAmbiguous(String value) {
        Set<String> codes = CODES_BY_ALIAS.get(normalize(value));
        return codes != null && codes.size() > 1;
    }

    private static void addAlias(Map<String, Set<String>> aliases, String alias, String code) {
        String normalized = normalize(alias);
        if (normalized.length() == 0) {
            return;
        }
        Set<String> codes = aliases.get(normalized);
        if (codes == null) {
            codes = new HashSet<String>();
            aliases.put(normalized, codes);
        }
        codes.add(code);
    }

    private static String normalize(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < decomposed.length();) {
            int codePoint = decomposed.codePointAt(index);
            index += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK) {
                continue;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                normalized.appendCodePoint(Character.toLowerCase(codePoint));
            }
        }
        return normalized.toString();
    }
}
