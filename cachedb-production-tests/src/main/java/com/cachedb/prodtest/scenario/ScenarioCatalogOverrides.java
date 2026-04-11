package com.reactor.cachedb.prodtest.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ScenarioCatalogOverrides {

    private ScenarioCatalogOverrides() {
    }

    static List<EcommerceScenarioProfile> profiles(String propertyName, String defaultValue) {
        String raw = System.getProperty(propertyName, defaultValue);
        ArrayList<EcommerceScenarioProfile> profiles = new ArrayList<>();
        for (String rawEntry : raw.split("\\|")) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String[] parts = entry.split(";", -1);
            if (parts.length != 21) {
                throw new IllegalArgumentException("Invalid scenario catalog entry for " + propertyName + ": " + entry);
            }
            profiles.add(new EcommerceScenarioProfile(
                    parts[0].trim(),
                    EcommerceScenarioKind.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)),
                    parts[2].trim(),
                    Integer.parseInt(parts[3].trim()),
                    Integer.parseInt(parts[4].trim()),
                    Integer.parseInt(parts[5].trim()),
                    Integer.parseInt(parts[6].trim()),
                    Integer.parseInt(parts[7].trim()),
                    Integer.parseInt(parts[8].trim()),
                    Integer.parseInt(parts[9].trim()),
                    Integer.parseInt(parts[10].trim()),
                    Integer.parseInt(parts[11].trim()),
                    Integer.parseInt(parts[12].trim()),
                    Integer.parseInt(parts[13].trim()),
                    Integer.parseInt(parts[14].trim()),
                    Integer.parseInt(parts[15].trim()),
                    Integer.parseInt(parts[16].trim()),
                    Integer.parseInt(parts[17].trim()),
                    Integer.parseInt(parts[18].trim()),
                    Integer.parseInt(parts[19].trim()),
                    Integer.parseInt(parts[20].trim())
            ));
        }
        return List.copyOf(profiles);
    }

    static List<String> names(String propertyName, String defaultValue) {
        String raw = System.getProperty(propertyName, defaultValue);
        ArrayList<String> names = new ArrayList<>();
        for (String rawEntry : raw.split(",")) {
            String entry = rawEntry.trim();
            if (!entry.isEmpty()) {
                names.add(entry);
            }
        }
        return List.copyOf(names);
    }
}
