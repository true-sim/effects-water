package com.truesim.effects.water;

import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowImpure;
import com.truesim.common.math.Maths;
import com.truesim.common.math.Vec3;
import com.truesim.effects.water.materials.WaterSwayParameters;
import com.truesim.engine.render.GeometryHooks;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.settings.EngineSettings;
import java.util.Objects;

/**
 * The wind the falls' veils sway in: writes the {@value #HOOK} geometry hook (water/sway.slang) into a scene's geometry
 * hook block each frame, the same for every view, so the waterfall type's displacement drifts the veil downwind by how
 * far it has fallen, swinging slowly, the swing travelling down the fall. The wind's speed and direction are settings
 * ({@link WaterRenderSettings#WIND_SPEED}, {@link WaterRenderSettings#WIND_DIRECTION}).
 *
 * <pre>{@code
 * WaterSway sway = new WaterSway(gpuScene);
 * // each frame, before it records:
 * sway.update(frame.delta(), settings);
 * }</pre>
 *
 * <p>The geometry hook block takes whole records, so each frame makes one (as the vegetation's wind does).
 */
public final class WaterSway {

    /** The hook's name: its {@code [ShadingHook]}. */
    public static final String HOOK = "water.sway";

    private final GeometryHooks hooks;
    private double time;

    /** The sway of {@code scene}'s falls: its shaders must have been built with the hook (the water's listing). */
    public WaterSway(GpuScene scene) {
        this.hooks = Objects.requireNonNull(scene, "scene").geometryHooks();
    }

    /** The clock, seconds. */
    public double time() {
        return time;
    }

    /** The next frame: {@code seconds} passed; writes the record for the next frame recorded. */
    @AllowAllocation("a record a frame: the geometry hook block takes whole records")
    @AllowImpure("keeps the sway's clock")
    public WaterSway update(double seconds, EngineSettings settings) {
        if (!(seconds >= 0)) {
            throw new IllegalArgumentException("Time runs forwards: " + seconds);
        }
        time = (time + seconds) % 86_400;
        double to = Maths.toRadians(settings.get(WaterRenderSettings.WIND_DIRECTION));
        // the direction it blows towards: from north, clockwise (x east, z south)
        Vec3 direction = new Vec3(Maths.sin(to), 0, -Maths.cos(to));
        float speed = settings.get(WaterRenderSettings.WIND_SPEED);
        hooks.set(HOOK, WaterSwayParameters.DEFAULTS.withDirection(direction).withSpeed(speed)
            .withDrift(settings.get(WaterRenderSettings.SWAY_DRIFT)).withTime((float) time));
        hooks.turn(HOOK, speed > 0);
        return this;
    }
}
