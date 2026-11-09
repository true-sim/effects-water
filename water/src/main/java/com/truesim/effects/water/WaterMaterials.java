package com.truesim.effects.water;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.color.Color;
import com.truesim.effects.water.materials.FallingWater;
import com.truesim.effects.water.materials.FallingWaterParameters;
import com.truesim.effects.water.materials.FlowingWater;
import com.truesim.effects.water.materials.FlowingWaterParameters;
import com.truesim.engine.render.Materials;
import com.truesim.scene.render.MaterialData;
import java.lang.reflect.RecordComponent;

/**
 * The water's materials as scene data: rivers and streams of the {@value #FLOWING} type (transmissive, refracting and
 * absorbing over their depth, with silt, Fresnel reflections and foam), falls' sheets and jets of the {@value #FALLING}
 * type (alpha-blended aerated water). Their types are registered with the renderer's materials once
 * ({@link #register}).
 *
 * <pre>{@code
 * WaterMaterials.register(resources.materials());
 * MaterialData river = WaterMaterials.river(FlowingWaterParameters.DEFAULTS);    // glacial: milky turquoise
 * MaterialData sheet = WaterMaterials.sheet(FallingWaterParameters.DEFAULTS);
 * MaterialData jets = WaterMaterials.jets(FallingWaterParameters.DEFAULTS);
 * }</pre>
 */
public final class WaterMaterials {

    /** The flowing water's type name. */
    public static final String FLOWING = "flowingWater";
    /** The falling water's type name. */
    public static final String FALLING = "waterfall";

    private WaterMaterials() {
    }

    /** Registers both types with {@code materials}. */
    @AllowAllocation("set up once")
    public static void register(Materials materials) {
        materials.register(FlowingWater.TYPE);
        materials.register(FallingWater.TYPE);
    }

    /**
     * A river's or stream's material: {@code p}'s optics, shown as transmissive with a volume (refraction and
     * absorption over the depth the vertices carry).
     */
    @AllowAllocation("material data, made rarely")
    public static MaterialData river(FlowingWaterParameters p) {
        MaterialData.Builder builder = MaterialData.builder(FLOWING);
        values(builder, p);
        return builder.feature("transmission", f -> f.set("transmissionFactor", 1f))
            .feature("volume", f -> f.set("thicknessFactor", 1f).set("attenuationDistance", p.attenuationDistance())
                .set("attenuationColor", (float) p.attenuationColor().r(), (float) p.attenuationColor().g(),
                    (float) p.attenuationColor().b()))
            .feature("ior", f -> f.set("ior", p.ior()))
            .build();
    }

    /** A fall's sheet: {@code p}, alpha-blended and seen from both sides. */
    @AllowAllocation("material data, made rarely")
    public static MaterialData sheet(FallingWaterParameters p) {
        return falling(p.withJets(0));
    }

    /** A fall's jets: {@code p} as the jets' submesh draws them. */
    @AllowAllocation("material data, made rarely")
    public static MaterialData jets(FallingWaterParameters p) {
        return falling(p.withJets(1));
    }

    @AllowAllocation("material data, made rarely")
    private static MaterialData falling(FallingWaterParameters p) {
        MaterialData.Builder builder = MaterialData.builder(FALLING);
        values(builder, p);
        return builder.alpha(MaterialData.AlphaMode.BLEND, 0.5f).doubleSided(true).build();
    }

    /** Every field of record {@code p} as a value of {@code builder}. */
    @AllowAllocation("material data, made rarely")
    static void values(MaterialData.Builder builder, Record p) {
        try {
            for (RecordComponent component : p.getClass().getRecordComponents()) {
                Object value = component.getAccessor().invoke(p);
                String name = component.getName();
                if (value instanceof Float f) {
                    builder.set(name, f);
                } else if (value instanceof Integer i) {
                    builder.set(name, i);
                } else if (value instanceof Color c) {
                    builder.set(name, (float) c.r(), (float) c.g(), (float) c.b());
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
