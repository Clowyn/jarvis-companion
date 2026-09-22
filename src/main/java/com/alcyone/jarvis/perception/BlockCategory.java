package com.alcyone.jarvis.perception;

/**
 * Standard categories for perceived blocks in the world.
 */
public enum BlockCategory {
    ORE("ore"),
    CONTAINER("container"),
    WORKSTATION("workstation"),
    UTILITY("utility"),
    HAZARD("hazard"),
    BUILDING("building"),
    OTHER("other"),
    NONE("none");

    private final String serializedName;

    BlockCategory(String serializedName) {
        this.serializedName = serializedName;
    }

    public String getSerializedName() {
        return serializedName;
    }
}
