package dev.mcneds.socialxpfarm;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IslandDestinationTest {
    @Test
    void disablingStopsBothDestinationsEvenWhenReconnectIsEnabled() {
        var config = new SocialXPFarmClient.Config();
        config.targetPlayer = "Someone";
        config.profileName = "Pineapple";
        config.autoReconnect = true;
        for (IslandDestination destination : IslandDestination.values()) {
            config.destination = destination;
            config.enabled = true;
            assertTrue(config.shouldRun());
            config.enabled = false;
            assertFalse(config.shouldRun());
        }
    }

    @Test
    void ownIslandDoesNotNeedGuestConfiguration() {
        assertTrue(IslandDestination.OWN.isConfigured("", ""));
        assertTrue(IslandDestination.OWN.isConfigured(null, null));
        assertFalse(IslandDestination.GUEST.isConfigured("Someone", ""));
        assertTrue(IslandDestination.GUEST.isConfigured("Someone", "Pineapple"));
    }

    @Test
    void ownIslandRequiresItsLocationRatherThanJustSkyBlock() {
        assertTrue(IslandDestination.OWN.reached("§e§lSKYBLOCK", List.of("§7⏣ §aYour Island")));
        assertFalse(IslandDestination.OWN.reached("SKYBLOCK", List.of("⏣ Village")));
        assertFalse(IslandDestination.OWN.reached("SKYBLOCK", List.of("⏣ The Garden")));
        assertFalse(IslandDestination.OWN.reached("SKYBLOCK", List.of()));
        assertFalse(IslandDestination.OWN.reached("LIMBO", List.of("Your Island")));
        assertFalse(IslandDestination.OWN.reached("SKYBLOCK", List.of("Travel to Your Island")));
    }

    @Test
    void guestAndOwnHealthyStatesAreDistinct() {
        assertTrue(IslandDestination.GUEST.reached("SKYBLOCK GUEST", List.of()));
        assertFalse(IslandDestination.OWN.reached("SKYBLOCK GUEST", List.of("Your Island")));
        assertFalse(IslandDestination.GUEST.reached("SKYBLOCK", List.of("Your Island")));
    }

    @Test
    void existingConfigsKeepGuestDestination() {
        var config = new Gson().fromJson("{\"targetPlayer\":\"Someone\",\"profileName\":\"Pineapple\"}", SocialXPFarmClient.Config.class);
        config.normalize();
        assertEquals(IslandDestination.GUEST, config.destination);
        assertTrue(config.isConfigured());
    }

    @Test
    void togglesAndOwnModeSurviveSavingAndLoading() {
        var config = new SocialXPFarmClient.Config();
        config.enabled = false;
        config.destination = IslandDestination.OWN;
        Gson gson = new Gson();
        var restored = gson.fromJson(gson.toJson(config), SocialXPFarmClient.Config.class);
        restored.normalize();
        assertFalse(restored.enabled);
        assertEquals(IslandDestination.OWN, restored.destination);
        assertTrue(restored.isConfigured());
    }

    @Test
    void invalidDestinationFallsBackToGuestWithoutStartingUnconfigured() {
        var config = new Gson().fromJson("{\"destination\":\"TYPO\"}", SocialXPFarmClient.Config.class);
        config.normalize();
        assertEquals(IslandDestination.GUEST, config.destination);
        assertFalse(config.isConfigured());
    }
}
