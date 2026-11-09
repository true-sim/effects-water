package com.truesim.effects.water;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Vec3;
import com.truesim.common.procedural.assets.ProceduralAssets;
import com.truesim.common.procedural.assets.Recipe;
import com.truesim.common.procedural.assets.WaterAssets;
import com.truesim.common.procedural.rock.RockParams;
import com.truesim.common.procedural.tree.Season;
import com.truesim.common.procedural.water.BedStone;
import com.truesim.common.procedural.water.WaterNetwork;
import com.truesim.common.procedural.water.Waterfall;
import com.truesim.effects.atmosphere.fog.FogVolume;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.core.Mobility;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.Renderable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A water network placed in a scene: everything as nodes and components, the meshes generated assets (through the
 * asset cache, streamed like any other), so the renderer, the particles and the fog take them as they take the rest.
 *
 * <ul>
 *   <li>the channels' surfaces, a node per chunk at its corner, of the river material;</li>
 *   <li>each free fall's sheet and jets, a node at its lip, of the sheet and jets materials;</li>
 *   <li>the beds' stones, a node each with a rock of {@link ProceduralAssets} scaled and turned (optional);</li>
 *   <li>spray: the falls' spray sources as effects ({@link WaterSpray}) at nodes, rates from the water they turn to
 *       spray (optional, with an {@link Effects});</li>
 *   <li>mist: a fog volume ({@link FogVolume}, effects-atmosphere's fog) at each fall's foot.</li>
 * </ul>
 *
 * <pre>{@code
 * WaterMaterials.register(resources.materials());
 * WaterScene water = new WaterScene(scene, new WaterAssets(paths), rivers, WaterScene.Look.of(
 *     WaterMaterials.river(FlowingWaterParameters.DEFAULTS).ref(), WaterMaterials.sheet(FallingWaterParameters.DEFAULTS)
 *     .ref(), WaterMaterials.jets(FallingWaterParameters.DEFAULTS).ref(), stoneMaterial))
 *     .stones(procedural).effects(effects).place(settings);
 * port.add(new WaterFlow(view));                          // the flow's hook in the view, its clock advanced each frame
 * }</pre>
 */
public final class WaterScene implements AutoCloseable {

    /**
     * The materials the water draws with.
     *
     * @param river the channels' surfaces (the {@value WaterMaterials#FLOWING} type)
     * @param sheet the falls' sheets (the {@value WaterMaterials#FALLING} type)
     * @param jets the falls' jets
     * @param stones the stones' rock (null: the renderer's default)
     */
    public record Look(AssetRef<MaterialData> river, AssetRef<MaterialData> sheet, AssetRef<MaterialData> jets,
        AssetRef<MaterialData> stones) {

        public Look {
            Objects.requireNonNull(river, "river");
            Objects.requireNonNull(sheet, "sheet");
            Objects.requireNonNull(jets, "jets");
        }

        /** The materials. */
        public static Look of(AssetRef<MaterialData> river, AssetRef<MaterialData> sheet, AssetRef<MaterialData> jets,
            AssetRef<MaterialData> stones) {
            return new Look(river, sheet, jets, stones);
        }
    }

    private final SceneGraph scene;
    private final WaterAssets assets;
    private final WaterNetwork network;
    private final Look look;
    private final List<Long> nodes = new ArrayList<>();
    private final List<Effect> sprays = new ArrayList<>();
    private ProceduralAssets procedural;
    private Effects effects;
    private long root = -1;

    /** {@code network} to be placed in {@code scene}, its meshes from {@code assets}, drawn with {@code look}. */
    public WaterScene(SceneGraph scene, WaterAssets assets, WaterNetwork network, Look look) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.network = Objects.requireNonNull(network, "network");
        this.look = Objects.requireNonNull(look, "look");
    }

    /** Places the beds' stones, rocks of {@code rocks} (null: none). */
    public WaterScene stones(ProceduralAssets rocks) {
        this.procedural = rocks;
        return this;
    }

    /** Plays the falls' spray in {@code playing} (null: none). */
    public WaterScene effects(Effects playing) {
        this.effects = playing;
        return this;
    }

    /** The network placed. */
    public WaterNetwork network() {
        return network;
    }

    /** The node everything is under (-1 before {@link #place}). */
    public long root() {
        return root;
    }

    /** The nodes placed (surfaces, falls, stones, spray and mist), under {@link #root()}. */
    public List<Long> nodes() {
        return List.copyOf(nodes);
    }

    /** The spray effects playing. */
    public List<Effect> sprays() {
        return List.copyOf(sprays);
    }

    /** Places everything as {@code settings} say ({@link WaterRenderSettings}); once. */
    @AllowAllocation("placing a network, once")
    public WaterScene place(EngineSettings settings) {
        if (root >= 0) {
            throw new IllegalStateException("Placed already");
        }
        root = scene.create();
        scene.setMobility(root, Mobility.STATIC);
        var renderables = scene.components().store(Renderable.TYPE);
        for (WaterNetwork.WaterChunk chunk : network.chunks()) {
            long node = child();
            scene.setTranslation(node, chunk.originX(), 0, chunk.originZ());
            renderables.set(node, Renderable.of(assets.surfaceMesh(network, chunk.x(), chunk.z()),
                List.of(look.river())).withCastsShadows(false));
        }
        double minFall = settings.get(WaterRenderSettings.MIN_FALL);
        for (Waterfall fall : network.falls()) {
            if (!fall.free()) {
                continue;
            }
            double[] lip = network.fallOrigin(fall.id());
            long node = child();
            scene.setTranslation(node, lip[0], lip[1], lip[2]);
            renderables.set(node, Renderable.of(assets.fallMesh(network, fall.id()), List.of(look.sheet(),
                look.jets())).withCastsShadows(false));
            if (fall.height() >= minFall) {
                spray(fall, settings);
                mist(fall, settings);
            }
        }
        if (procedural != null && settings.get(WaterRenderSettings.STONES)) {
            stones(settings);
        }
        return this;
    }

    @AllowAllocation("placing a network, once")
    private long child() {
        long node = scene.create(root);
        scene.setMobility(node, Mobility.STATIC);
        nodes.add(node);
        return node;
    }

    @AllowAllocation("placing a network, once")
    private void spray(Waterfall fall, EngineSettings settings) {
        if (effects == null) {
            return;
        }
        double perCubic = settings.get(WaterRenderSettings.SPRAY_RATE);
        double max = settings.get(WaterRenderSettings.MAX_SPRAY);
        double min = settings.get(WaterRenderSettings.MIN_SPRAY);
        double to = com.truesim.common.math.Maths.toRadians(settings.get(WaterRenderSettings.WIND_DIRECTION));
        float wind = settings.get(WaterRenderSettings.WIND_SPEED);
        float windX = (float) (wind * com.truesim.common.math.Maths.sin(to));
        float windZ = (float) (-wind * com.truesim.common.math.Maths.cos(to));
        double reach = settings.get(WaterRenderSettings.PLUNGE_REACH) * fall.height();
        List<Waterfall.Spray> sources = fall.spray();
        for (int k = 0; k < sources.size(); k++) {
            Waterfall.Spray s = sources.get(k);
            boolean plunge = k == sources.size() - 1;
            float rate = (float) Math.clamp(s.rate() * perCubic, min, max);
            long node = child();
            scene.setTranslation(node, s.x(), s.y(), s.z());
            float radius = (float) Math.max(0.5, plunge ? Math.max(s.radius(), reach) : s.radius());
            if (plunge) {
                rate = (float) Math.min(max * 4, rate * 4);
            }
            Effect effect = effects.play(plunge ? WaterSpray.plunge(radius) : WaterSpray.falling(radius), node)
                .set(WaterSpray.RATE, rate).set(WaterSpray.WIND_X, windX).set(WaterSpray.WIND_Z, windZ);
            if (!plunge) {
                effect.set(WaterSpray.SPEED, (float) s.speed());
            }
            sprays.add(effect);
        }
    }

    @AllowAllocation("placing a network, once")
    private void mist(Waterfall fall, EngineSettings settings) {
        if (!settings.get(WaterRenderSettings.MIST)) {
            return;
        }
        Waterfall.Mist m = fall.mist();
        float density = (float) (m.density() * settings.get(WaterRenderSettings.MIST_DENSITY));
        if (!(density > 0)) {
            return;
        }
        long node = child();
        // centred at the foot, reaching as high again as the network says: thinning upward, its edge faded all the
        // way in, so no shell shows from close by
        double radiusY = 2 * m.radiusY();
        scene.setTranslation(node, m.x(), m.y() - 0.5 * m.radiusY(), m.z());
        scene.components().set(node, FogVolume.TYPE, FogVolume.ellipsoid(new Vec3(1.3 * m.radiusX(), radiusY,
            1.3 * m.radiusZ()), density).withFalloff((float) (2.5 / radiusY))
            .withBlendDistance((float) Math.min(1.3 * m.radiusX(), radiusY)));
    }

    @AllowAllocation("placing a network, once")
    private void stones(EngineSettings settings) {
        List<BedStone> all = new ArrayList<>(network.stones());
        all.sort(Comparator.comparingDouble(s -> -s.size()));
        int limit = Math.min(all.size(), settings.get(WaterRenderSettings.STONE_LIMIT));
        int level = settings.get(WaterRenderSettings.STONE_LEVEL);
        var renderables = scene.components().store(Renderable.TYPE);
        List<AssetRef<MaterialData>> materials = look.stones() == null ? List.of() : List.of(look.stones());
        for (int i = 0; i < limit; i++) {
            BedStone s = all.get(i);
            Recipe recipe = new Recipe("rock", s.preset(), Math.floorMod(s.seed(), WaterAssets.STONE_VARIANTS),
                Season.SUMMER, 0, List.of(""));
            long node = child();
            double scale = s.size() / presetSize(s.preset());
            double half = 0.5 * s.yaw();
            scene.setLocal(node, s.x(), s.y(), s.z(), 0, com.truesim.common.math.Maths.sin(half), 0, com.truesim.common.math.Maths.cos(half), scale, scale, scale);
            renderables.set(node, Renderable.of(procedural.mesh(recipe, level), materials));
        }
    }

    /** The size of rock preset {@code name}, metres. */
    static double presetSize(String name) {
        for (RockParams preset : RockParams.PRESETS) {
            if (preset.name().equals(name)) {
                return preset.size();
            }
        }
        throw new IllegalArgumentException("No rock preset '" + name + "'");
    }

    /** Removes every node it placed and stops its spray. */
    @Override
    @AllowAllocation("removing a network, once")
    public void close() {
        for (Effect e : sprays) {
            e.kill();
        }
        sprays.clear();
        if (root >= 0) {
            scene.remove(root);
            root = -1;
        }
        nodes.clear();
    }
}
