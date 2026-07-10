package io.github.zoyluo.aibot.mode;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Pure profile migration and environment-override policy. */
public final class ProfileResolver {
    public static final String ENVIRONMENT_KEY = "AIBOT_PROFILE";

    private ProfileResolver() {
    }

    public static Resolution resolve(boolean existingFile, JsonObject root, String environmentValue) {
        List<Warning> warnings = new ArrayList<>();
        OperatingProfile fileProfile = resolveFileProfile(existingFile, root, warnings);

        if (environmentValue != null && !environmentValue.isBlank()) {
            var environmentProfile = OperatingProfile.parse(environmentValue);
            if (environmentProfile.isPresent()) {
                return new Resolution(environmentProfile.get(), Source.ENVIRONMENT, warnings);
            }
            warnings.add(Warning.INVALID_ENVIRONMENT_PROFILE);
            return new Resolution(OperatingProfile.STRICT_SURVIVAL, Source.FAIL_CLOSED, warnings);
        }

        Source source = existingFile && root != null && !root.has("profile")
                ? Source.LEGACY_COMPATIBILITY
                : Source.FILE_OR_DEFAULT;
        if (warnings.contains(Warning.INVALID_FILE_PROFILE)) {
            source = Source.FAIL_CLOSED;
        }
        return new Resolution(fileProfile, source, warnings);
    }

    private static OperatingProfile resolveFileProfile(boolean existingFile,
                                                        JsonObject root,
                                                        List<Warning> warnings) {
        if (!existingFile || root == null) {
            return OperatingProfile.STRICT_SURVIVAL;
        }
        // Presence is checked on the raw object because Gson maps both a missing enum and an invalid
        // enum value to null. Only a truly missing field receives legacy operator compatibility.
        if (!root.has("profile")) {
            warnings.add(Warning.LEGACY_PROFILE_MISSING);
            return OperatingProfile.OPERATOR;
        }
        JsonElement element = root.get("profile");
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            warnings.add(Warning.INVALID_FILE_PROFILE);
            return OperatingProfile.STRICT_SURVIVAL;
        }
        var parsed = OperatingProfile.parse(element.getAsString());
        if (parsed.isEmpty()) {
            warnings.add(Warning.INVALID_FILE_PROFILE);
            return OperatingProfile.STRICT_SURVIVAL;
        }
        return parsed.get();
    }

    public enum Source {
        FILE_OR_DEFAULT,
        LEGACY_COMPATIBILITY,
        ENVIRONMENT,
        FAIL_CLOSED
    }

    public enum Warning {
        LEGACY_PROFILE_MISSING,
        INVALID_FILE_PROFILE,
        INVALID_ENVIRONMENT_PROFILE
    }

    public record Resolution(OperatingProfile profile, Source source, List<Warning> warnings) {
        public Resolution {
            profile = profile == null ? OperatingProfile.STRICT_SURVIVAL : profile;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public long warningCount(Warning warning) {
            return warnings.stream().filter(warning::equals).count();
        }
    }
}
