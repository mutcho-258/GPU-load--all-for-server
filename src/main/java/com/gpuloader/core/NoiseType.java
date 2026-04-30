package com.gpuloader.core;

/**
 * GPUで生成される7種類のノイズ役割を定義します。
 */
public enum NoiseType {
    DENSITY(0, "Density"),
    TEMPERATURE(1, "Temperature"),
    HUMIDITY(2, "Humidity"),
    CONTINENTALNESS(3, "Continentalness"),
    EROSION(4, "Erosion"),
    WEIRDNESS(5, "Weirdness"),
    DEPTH(6, "Depth"),
    UNKNOWN(-1, "Unknown");

    private final int index;
    private final String name;

    NoiseType(int index, String name) {
        this.index = index;
        this.name = name;
    }

    public int getIndex() {
        return index;
    }

    public String getName() {
        return name;
    }

    public static NoiseType fromIndex(int index) {
        for (NoiseType type : values()) {
            if (type.index == index) return type;
        }
        return UNKNOWN;
    }
}
