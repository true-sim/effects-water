package com.truesim.effects.water;

import com.truesim.engine.settings.Setting;
import java.util.List;

/**
 * How a water network is dressed in a scene ({@link WaterScene}), as engine settings: the stones, the spray's
 * particles, the mist. Read when the network is placed.
 */
public final class WaterRenderSettings {

    /** Whether the beds' stones are placed. */
    public static final Setting<Boolean> STONES = Setting.bool("water.render.stones", true,
        "place the stones of the river beds");
    /** The most stones placed (the largest first). */
    public static final Setting<Integer> STONE_LIMIT = Setting.integer("water.render.stoneLimit", 20_000, 0,
        1_000_000, "the most bed stones placed, the largest first");
    /** The rock level of detail the stones draw. */
    public static final Setting<Integer> STONE_LEVEL = Setting.integer("water.render.stoneLevel", 1, 0, 3,
        "the generated rock level the bed stones draw (0: the most detailed)");
    /** The lowest fall dressed with spray and mist. */
    public static final Setting<Float> MIN_FALL = Setting.decimal("water.render.minFall", 15, 0, 1000,
        "the lowest fall dressed with spray and mist, metres");
    /** Spray particles a second per cubic metre a second of spray. */
    public static final Setting<Float> SPRAY_RATE = Setting.decimal("water.render.sprayRate", 600, 0, 100_000,
        "spray particles a second for each cubic metre a second of water turned to spray");
    /** The most particles a second a spray source emits. */
    public static final Setting<Float> MAX_SPRAY = Setting.decimal("water.render.maxSpray", 160, 0, 10_000,
        "the most spray particles a second from one source");
    /** The fewest particles a second a spray source emits. */
    public static final Setting<Float> MIN_SPRAY = Setting.decimal("water.render.minSpray", 6, 0, 1000,
        "the fewest spray particles a second from one source");
    /** Whether mist lies at the falls' feet. */
    public static final Setting<Boolean> MIST = Setting.bool("water.render.mist", true,
        "mist (fog volumes) at the waterfalls' feet");
    /** The mist's density, as a multiple of the network's. */
    public static final Setting<Float> MIST_DENSITY = Setting.decimal("water.render.mistDensity", 1, 0, 100,
        "the mist's density as a multiple of the network's");

    /** Every setting, to register. */
    public static final List<Setting<?>> ALL = List.of(STONES, STONE_LIMIT, STONE_LEVEL, MIN_FALL, SPRAY_RATE,
        MAX_SPRAY, MIN_SPRAY, MIST, MIST_DENSITY);

    private WaterRenderSettings() {
    }
}
