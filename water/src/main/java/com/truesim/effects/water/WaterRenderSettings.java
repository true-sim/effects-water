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
    public static final Setting<Float> MIST_DENSITY = Setting.decimal("water.render.mistDensity", 0.3f, 0, 100,
        "the mist's density as a multiple of the network's");

    /** The wind at the falls. */
    public static final Setting<Float> WIND_SPEED = Setting.decimal("water.render.windSpeed", 2.5f, 0, 40,
        "the wind at the falls, metres a second: their veils and spray drift in it");
    /** Where the wind blows towards. */
    public static final Setting<Float> WIND_DIRECTION = Setting.decimal("water.render.windDirection", 60, 0, 360,
        "where the wind blows towards, degrees from north, clockwise");
    /** How far a veil drifts. */
    public static final Setting<Float> SWAY_DRIFT = Setting.decimal("water.render.swayDrift", 0.03f, 0, 1,
        "metres a falling veil drifts sideways per metre fallen, per metre a second of wind");
    /** How high above the water the banks are wet. */
    public static final Setting<Float> WET_HEIGHT = Setting.decimal("water.render.wetHeight", 0.7f, 0, 10,
        "metres above the water's edge the banks are wet");
    /** The wetness map's cell. */
    public static final Setting<Float> WET_CELL = Setting.decimal("water.render.wetCell", 1, 0.25f, 16,
        "metres a cell of the wetness map");
    /** The plunge spray's reach, as a share of the fall's height. */
    public static final Setting<Float> PLUNGE_REACH = Setting.decimal("water.render.plungeReach", 0.12f, 0, 2,
        "how far the spray at a fall's foot reaches, as a share of the fall's height");

    /** Every setting, to register. */
    public static final List<Setting<?>> ALL = List.of(STONES, STONE_LIMIT, STONE_LEVEL, MIN_FALL, SPRAY_RATE,
        MAX_SPRAY, MIN_SPRAY, MIST, MIST_DENSITY, WIND_SPEED, WIND_DIRECTION, SWAY_DRIFT, WET_HEIGHT, WET_CELL,
        PLUNGE_REACH);

    private WaterRenderSettings() {
    }
}
