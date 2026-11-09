package com.truesim.effects.water;

import com.truesim.common.procedural.water.WaterField;
import com.truesim.common.procedural.water.WaterNetwork;
import com.truesim.common.procedural.water.WaterRegion;
import com.truesim.effects.terrain.data.TerrainModifier;
import java.util.Objects;

/**
 * A water network's beds and banks as an effects-terrain modifier: the ground carved as {@link WaterField#carve}
 * cuts it (a parabolic bed under the water, banks blending back into the ground; never raised), and the bare ground
 * the water keeps: gravel in the bed and on the bars beside it, sand and silt fading out across the bank where grass
 * grows back ({@link WaterField#grass}).
 *
 * <pre>{@code
 * TerrainSurface ground = new TerrainSurface(site, TerrainSettings.surface(settings));      // unmodified
 * WaterNetwork rivers = WaterNetwork.build(TerrainEcology.of(ground, 2), region, parameters);
 * Terrain terrain = new Terrain(world, resources, site, material, settings,
 *     TerrainModifiers.of(WaterTerrain.of(rivers)));                                         // roads after it
 * }</pre>
 *
 * Work the network out from the unmodified ground: its surfaces lie where the data's ground is, and the beds are cut
 * below them.
 */
public final class WaterTerrain implements TerrainModifier {

    private final WaterField field;
    private final Bounds bounds;
    private final double sandShare;

    private WaterTerrain(WaterNetwork network, double sandShare) {
        this.field = network.field();
        WaterRegion r = network.region();
        this.bounds = new Bounds(r.x0(), r.z0(), r.x1(), r.z1());
        this.sandShare = sandShare;
    }

    /** {@code network}'s beds and banks, sand making up half the bare bank. */
    public static WaterTerrain of(WaterNetwork network) {
        return of(network, 0.5);
    }

    /** {@code network}'s beds and banks, sand making up {@code sandShare} (0 to 1) of the bare bank's outer part. */
    public static WaterTerrain of(WaterNetwork network, double sandShare) {
        Objects.requireNonNull(network, "network");
        if (!(sandShare >= 0 && sandShare <= 1)) {
            throw new IllegalArgumentException("A share is 0 to 1: " + sandShare);
        }
        return new WaterTerrain(network, sandShare);
    }

    @Override
    public double height(double x, double z, double footprint, double height) {
        return field.carve(x, z, height);
    }

    @Override
    public double materials(double x, double z, double footprint, double[] cover) {
        double bare = 1 - field.grass(x, z);
        if (bare <= 0) {
            return 0;
        }
        WaterField.Sample s = field.sample(x, z);
        // in the water and on the bars: gravel; across the bank's outer part, more sand
        double out = s == null ? 1 : Math.clamp((s.distance() - s.halfWidth()) / Math.max(s.bank(), 1e-6), 0, 1);
        double sand = bare * sandShare * out;
        cover[SAND] = sand;
        cover[GRAVEL] = bare - sand;
        return bare;
    }

    @Override
    public Bounds bounds() {
        return bounds;
    }

}
