package io.github.zoyluo.aibot.mode;

import com.google.gson.annotations.SerializedName;

import java.util.Locale;
import java.util.Optional;

/** Runtime fairness profile. Unknown values are deliberately not coerced to operator mode. */
public enum OperatingProfile {
    @SerializedName("strict_survival")
    STRICT_SURVIVAL("strict_survival"),
    @SerializedName("operator")
    OPERATOR("operator");

    private final String configValue;

    OperatingProfile(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static Optional<OperatingProfile> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "strict_survival" -> Optional.of(STRICT_SURVIVAL);
            case "operator" -> Optional.of(OPERATOR);
            default -> Optional.empty();
        };
    }
}
