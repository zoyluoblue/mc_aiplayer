package io.github.zoyluo.aibot.mode;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileResolverTest {
    @Test
    void newConfigDefaultsToStrictSurvival() {
        ProfileResolver.Resolution resolution = ProfileResolver.resolve(false, null, null);

        assertEquals(OperatingProfile.STRICT_SURVIVAL, resolution.profile());
        assertEquals(ProfileResolver.Source.FILE_OR_DEFAULT, resolution.source());
        assertTrue(resolution.warnings().isEmpty());
    }

    @Test
    void existingLegacyConfigWithoutProfileUsesOperatorAndOneWarning() {
        JsonObject legacy = JsonParser.parseString("{\"brain\":{}} ").getAsJsonObject();

        ProfileResolver.Resolution resolution = ProfileResolver.resolve(true, legacy, null);

        assertEquals(OperatingProfile.OPERATOR, resolution.profile());
        assertEquals(ProfileResolver.Source.LEGACY_COMPATIBILITY, resolution.source());
        assertEquals(1L, resolution.warningCount(ProfileResolver.Warning.LEGACY_PROFILE_MISSING));
        assertEquals(1, resolution.warnings().size());
    }

    @Test
    void explicitProfilesAreReadFromRawJsonPresence() {
        JsonObject strict = JsonParser.parseString("{\"profile\":\"strict_survival\"}").getAsJsonObject();
        JsonObject operator = JsonParser.parseString("{\"profile\":\"operator\"}").getAsJsonObject();

        assertEquals(OperatingProfile.STRICT_SURVIVAL,
                ProfileResolver.resolve(true, strict, null).profile());
        assertEquals(OperatingProfile.OPERATOR,
                ProfileResolver.resolve(true, operator, null).profile());
    }

    @Test
    void invalidOrNullFileValueFailsClosedInsteadOfLookingLegacy() {
        JsonObject invalid = JsonParser.parseString("{\"profile\":\"creative\"}").getAsJsonObject();
        JsonObject explicitNull = JsonParser.parseString("{\"profile\":null}").getAsJsonObject();

        for (JsonObject root : java.util.List.of(invalid, explicitNull)) {
            ProfileResolver.Resolution resolution = ProfileResolver.resolve(true, root, null);
            assertEquals(OperatingProfile.STRICT_SURVIVAL, resolution.profile());
            assertEquals(ProfileResolver.Source.FAIL_CLOSED, resolution.source());
            assertEquals(1L, resolution.warningCount(ProfileResolver.Warning.INVALID_FILE_PROFILE));
            assertEquals(0L, resolution.warningCount(ProfileResolver.Warning.LEGACY_PROFILE_MISSING));
        }
    }

    @Test
    void environmentOverridesFileButDoesNotDuplicateLegacyWarning() {
        JsonObject legacy = new JsonObject();

        ProfileResolver.Resolution resolution = ProfileResolver.resolve(true, legacy, "strict_survival");

        assertEquals(OperatingProfile.STRICT_SURVIVAL, resolution.profile());
        assertEquals(ProfileResolver.Source.ENVIRONMENT, resolution.source());
        assertEquals(1L, resolution.warningCount(ProfileResolver.Warning.LEGACY_PROFILE_MISSING));
    }

    @Test
    void invalidEnvironmentFailsClosedEvenWhenFileRequestsOperator() {
        JsonObject operator = JsonParser.parseString("{\"profile\":\"operator\"}").getAsJsonObject();

        ProfileResolver.Resolution resolution = ProfileResolver.resolve(true, operator, "god_mode");

        assertEquals(OperatingProfile.STRICT_SURVIVAL, resolution.profile());
        assertEquals(ProfileResolver.Source.FAIL_CLOSED, resolution.source());
        assertEquals(1L, resolution.warningCount(ProfileResolver.Warning.INVALID_ENVIRONMENT_PROFILE));
    }
}
