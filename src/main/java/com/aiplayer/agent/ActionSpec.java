package com.aiplayer.agent;

import java.util.List;

public record ActionSpec(
    String name,
    List<String> requiredParameters,
    String description,
    boolean highRisk,
    boolean deepSeekCallable
) {
    public ActionSpec {
        requiredParameters = requiredParameters == null ? List.of() : List.copyOf(requiredParameters);
    }
}
