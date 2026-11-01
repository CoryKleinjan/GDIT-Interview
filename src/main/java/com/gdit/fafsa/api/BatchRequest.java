package com.gdit.fafsa.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BatchRequest(@NotNull @Size(min = 1, max = MAX_SIZE) List<JsonNode> applications) {

    /** Caps memory and request time per call; larger volumes belong on a queue (see DECISIONS.md). */
    static final int MAX_SIZE = 1000;
}
