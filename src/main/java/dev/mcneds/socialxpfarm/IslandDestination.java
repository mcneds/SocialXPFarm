package dev.mcneds.socialxpfarm;

import java.util.List;

enum IslandDestination {
    GUEST("guest island"),
    OWN("own island");

    final String label;

    IslandDestination(String label) { this.label = label; }

    boolean isConfigured(String player, String profile) {
        return this == OWN || (player != null && !player.isBlank() && profile != null && !profile.isBlank());
    }

    boolean reached(String title, List<String> sidebarLines) {
        String normalizedTitle = RecoveryPolicy.normalize(title);
        if (!normalizedTitle.contains("skyblock")) return false;
        boolean guest = normalizedTitle.contains("guest");
        if (this == GUEST) return guest;
        return !guest && sidebarLines.stream().map(RecoveryPolicy::normalize)
                .map(line -> line.replaceAll("[^a-z ]", "").strip())
                .anyMatch("your island"::equals);
    }
}
