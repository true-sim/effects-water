package com.truesim.effects.water;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectParameter;
import com.truesim.scene.effects.EffectType;
import com.truesim.scene.effects.FloatCurve;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Over;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Spawn;

/**
 * Spray as particle effects (scene-effects' {@link EffectData}, drawn by effects-particles): what a waterfall's water
 * becomes as it atomises, and what its plunge throws up at the foot. Each is played at a node of the fall's spray
 * sources ({@code Waterfall.spray()}), made for the source's radius, its rate from the water it turns to spray.
 *
 * <ul>
 *   <li>{@link #FALLING}: streaks of water falling at the fall's speed ({@link #SPEED}), stretched along their
 *       velocity, turning into drifting drops; {@link #RATE} particles a second.</li>
 *   <li>{@link #PLUNGE}: soft puffs of spray rising and spreading from the foot, carried by the wind, fading into
 *       the mist.</li>
 * </ul>
 */
public final class WaterSpray {

    /** Particles a second. */
    public static final EffectParameter.Float RATE = new EffectParameter.Float("rate");
    /** The falling water's speed there, metres a second (falling streaks). */
    public static final EffectParameter.Float SPEED = new EffectParameter.Float("speed");

    /** The kind of effect: spray, near enough to be seen. */
    public static final EffectType TYPE = EffectType.builder("water.spray").maxDistance(4000).maxInstances(512)
        .build();

    /** Water breaking up as it falls, from a source 2 m round. */
    public static final EffectData FALLING = falling(2);

    /** Spray thrown up where a fall lands, from 4 m round. */
    public static final EffectData PLUNGE = plunge(4);

    private WaterSpray() {
    }

    /** Water breaking up as it falls: streaks turning into drifting drops, from a sphere {@code radius} metres round. */
    @AllowAllocation("effect data, made as falls are placed")
    public static EffectData falling(float radius) {
        return EffectData.builder(TYPE).parameter(RATE, 60).parameter(SPEED, 20)
            .emitter("streaks", e -> e.capacity(4096).spawn(Spawn.rate(Bind.param(RATE)))
                .init(Shape.sphere(Math.max(radius, 0.1f)), Init.lifetime(Bind.random(1.2f, 2.4f)),
                    Init.velocity(Bind.random(-0.6f, 0.6f), Bind.param(SPEED).times(-0.8f), Bind.random(-0.6f, 0.6f)),
                    Init.size(Bind.random(0.25f, 0.6f)), Init.color(Bind.color(0.92f, 0.95f, 0.97f, 0.5f)))
                .update(Forces.gravity(Bind.constant(0.6f)), Forces.drag(0.35f), Forces.curl(0.6f, 0.15f),
                    Forces.wind(0.5f),
                    Over.size(FloatCurve.of(0, 0.6f, 0.5f, 1.2f, 1, 2.2f)),
                    Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.1f, 1, 1, 1, 0.8f)
                        .linear(0.6f, 1, 1, 1, 0.45f).linear(1, 1, 1, 1, 0).build()))
                .render(Renderer.billboard(Renderer.Facing.VELOCITY).set("stretch", 0.06f).set("soft", 1f)
                    .lighting(Renderer.Lighting.PER_VERTEX)))
            .build();
    }

    /** Spray thrown up and spreading where a fall lands, from a disc {@code radius} metres round. */
    @AllowAllocation("effect data, made as falls are placed")
    public static EffectData plunge(float radius) {
        return EffectData.builder(TYPE).parameter(RATE, 30)
            .emitter("puffs", e -> e.capacity(4096).spawn(Spawn.rate(Bind.param(RATE)))
                .init(Shape.disc(Math.max(radius, 0.1f)), Init.lifetime(Bind.random(3f, 6f)),
                    Init.velocity(Bind.random(-2.5f, 2.5f), Bind.random(0.5f, 3.5f), Bind.random(-2.5f, 2.5f)),
                    Init.size(Bind.random(1.5f, 4f)), Init.randomRotation(),
                    Init.color(Bind.color(0.9f, 0.93f, 0.95f, 0.35f)))
                .update(Forces.gravity(Bind.constant(0.05f)), Forces.drag(0.5f), Forces.curl(1.2f, 0.08f),
                    Forces.wind(0.9f),
                    Over.size(FloatCurve.of(0, 0.5f, 1, 3.5f)),
                    Over.color(ColorGradient.builder().linear(0, 1, 1, 1, 0).linear(0.15f, 1, 1, 1, 0.55f)
                        .linear(1, 1, 1, 1, 0).build()))
                .render(Renderer.billboard(Renderer.Facing.POSITION).set("soft", 2f)
                    .lighting(Renderer.Lighting.PER_VERTEX)))
            .build();
    }
}
