package com.truesim.effects.water;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.procedural.assets.GenerationCache;
import com.truesim.common.procedural.assets.ProceduralAssets;
import com.truesim.common.procedural.assets.WaterAssets;
import com.truesim.common.procedural.ecology.EcologyTerrain;
import com.truesim.common.procedural.water.WaterNetwork;
import com.truesim.common.procedural.water.WaterParameters;
import com.truesim.common.procedural.water.WaterRegion;
import com.truesim.common.procedural.water.Waterfall;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.fog.FogVolume;
import com.truesim.effects.terrain.data.TerrainModifier;
import com.truesim.effects.water.materials.FallingWaterParameters;
import com.truesim.effects.water.materials.FlowingWaterParameters;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.Renderable;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The water placed in a scene, its terrain modifier, materials and spray, on the CPU: a valley whose stream falls over
 * a cliff (common-procedural-water's network), placed as nodes with renderables, fog volumes and effects; the beds
 * carved and laid with gravel and sand; the materials' data; the flow's clock.
 */
class WaterSceneTest {

    /** A plateau draining south to a 150 m cliff at z = 1000, a trough keeping the stream along x = 0. */
    static final EcologyTerrain CLIFF = (x, z) -> {
        double across = 0.04 * Math.min(Math.abs(x), 400) + 0.2 * Math.max(0, Math.abs(x) - 400);
        double base = z < 1000 ? 2000 - 0.02 * z : z < 1030 ? 1980 - 5 * (z - 1000) : 1830 - 0.01 * (z - 1030);
        return base + across;
    };
    static final WaterRegion REGION = new WaterRegion(-1000, 0, 1000, 2500);

    private static WaterNetwork network;
    private static TaskScheduler tasks;
    private static AssetCache cache;

    @TempDir
    static Path dir;

    @BeforeAll
    static void build() {
        network = WaterNetwork.build(CLIFF, REGION, WaterParameters.DEFAULTS);
        tasks = new TaskScheduler();
        cache = new AssetCache(tasks);
    }

    @AfterAll
    static void close() {
        cache.close();
        tasks.close();
    }

    private static WaterScene.Look look() {
        return WaterScene.Look.of(WaterMaterials.river(FlowingWaterParameters.DEFAULTS).ref(),
            WaterMaterials.sheet(FallingWaterParameters.DEFAULTS).ref(),
            WaterMaterials.jets(FallingWaterParameters.DEFAULTS).ref(), null);
    }

    @Test
    void theNetworkIsPlacedAsNodes() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        PathAssets paths = new PathAssets(cache);
        ProceduralAssets rocks = new ProceduralAssets(paths, new GenerationCache(dir.resolve("rocks"))).install();
        Effects effects = new Effects(scene);
        EngineSettings settings = new EngineSettings();
        settings.set(WaterRenderSettings.STONE_LIMIT, 50);
        WaterScene water = new WaterScene(scene, new WaterAssets(paths), network, look()).stones(rocks)
            .effects(effects);
        assertThat(water.root()).isEqualTo(-1);
        assertThat(water.network()).isSameAs(network);
        water.place(settings);
        assertThatThrownBy(() -> water.place(settings)).isInstanceOf(IllegalStateException.class);
        var renderables = scene.components().store(Renderable.TYPE);
        int surfaces = 0;
        int falls = 0;
        int stones = 0;
        int mists = 0;
        for (long node : water.nodes()) {
            Renderable r = renderables.get(node);
            FogVolume fog = scene.components().get(node, FogVolume.TYPE);
            if (fog != null) {
                mists++;
                assertThat(fog.density()).isPositive();
            } else if (r != null && r.materials().size() == 2) {
                falls++;
            } else if (r != null && r.materials().size() == 1) {
                surfaces++;
            } else if (r != null) {
                stones++;
                assertThat(scene.local(node).scale().x()).isPositive();
            }
        }
        assertThat(surfaces).isEqualTo(network.chunks().size());
        long free = network.falls().stream().filter(Waterfall::free).count();
        assertThat(falls).isEqualTo(free).isPositive();
        assertThat(stones).isEqualTo(Math.min(50, network.stones().size()));
        assertThat(mists).isPositive();
        int sources = network.falls().stream().filter(f -> f.free() && f.height() >= 15)
            .mapToInt(f -> f.spray().size()).sum();
        assertThat(water.sprays()).hasSize(sources).isNotEmpty();
        // the stones the largest first
        assertThat(WaterScene.presetSize("boulder")).isEqualTo(1.2);
        assertThatThrownBy(() -> WaterScene.presetSize("pebble")).isInstanceOf(IllegalArgumentException.class);
        long root = water.root();
        water.close();
        assertThat(water.nodes()).isEmpty();
        assertThat(water.sprays()).isEmpty();
        assertThat(scene.contains(root)).isFalse();
        water.close();
    }

    @Test
    void settingsLeaveOutWhatTheySay() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        EngineSettings settings = new EngineSettings();
        settings.set(WaterRenderSettings.MIST, false);
        settings.set(WaterRenderSettings.MIN_FALL, 1000f);
        WaterScene water = new WaterScene(scene, new WaterAssets(new PathAssets(cache)), network, look())
            .place(settings);
        for (long node : water.nodes()) {
            assertThat(scene.components().get(node, FogVolume.TYPE)).isNull();
        }
        assertThat(water.sprays()).isEmpty();
        water.close();
        Set<String> names = new HashSet<>();
        for (Setting<?> s : WaterRenderSettings.ALL) {
            assertThat(names.add(s.name())).isTrue();
        }
        assertThatThrownBy(() -> WaterScene.Look.of(null, null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void bedsAreCarvedAndLaidWithGravel() {
        WaterTerrain beds = WaterTerrain.of(network);
        var c = network.channels().getLast();
        int i = c.count() / 2;
        double x = c.x(i);
        double z = c.z(i);
        double ground = CLIFF.height(x, z);
        assertThat(beds.height(x, z, 2, ground)).isLessThan(ground);
        double[] cover = new double[TerrainModifier.MATERIALS];
        assertThat(beds.materials(x, z, 2, cover)).isCloseTo(1, within(1e-9));
        assertThat(cover[TerrainModifier.GRAVEL]).isCloseTo(1, within(1e-9));
        // off the water: nothing
        double[] none = new double[TerrainModifier.MATERIALS];
        assertThat(beds.materials(x + 300, z, 2, none)).isZero();
        assertThat(beds.height(x + 300, z, 2, 5)).isEqualTo(5);
        assertThat(beds.bounds().contains(x, z)).isTrue();
        // across the bank: sand comes in, the total falling as grass grows back
        double rx = -c.directionZ(i);
        double rz = c.directionX(i);
        double sand = 0;
        for (double o = 0; o < 10; o += 0.25) {
            java.util.Arrays.fill(cover, 0);
            double total = beds.materials(x + rx * o, z + rz * o, 2, cover);
            assertThat(cover[TerrainModifier.GRAVEL] + cover[TerrainModifier.SAND]).isCloseTo(total, within(1e-9));
            sand = Math.max(sand, cover[TerrainModifier.SAND]);
        }
        assertThat(sand).isPositive();
        assertThatThrownBy(() -> WaterTerrain.of(network, 2)).isInstanceOf(IllegalArgumentException.class);
        java.util.Arrays.fill(cover, 0);
        WaterTerrain.of(network, 0).materials(x + rx * 3, z + rz * 3, 2, cover);
        assertThat(cover[TerrainModifier.SAND]).isZero();
    }

    @Test
    void materialsCarryTheirTypesAndFeatures() {
        MaterialData river = WaterMaterials.river(FlowingWaterParameters.DEFAULTS);
        assertThat(river.type()).isEqualTo(WaterMaterials.FLOWING);
        assertThat(river.features()).contains("transmission", "volume", "ior");
        assertThat(river.values().f32("turbidity", -1)).isEqualTo(FlowingWaterParameters.DEFAULTS.turbidity());
        MaterialData sheet = WaterMaterials.sheet(FallingWaterParameters.DEFAULTS);
        MaterialData jets = WaterMaterials.jets(FallingWaterParameters.DEFAULTS);
        assertThat(sheet.type()).isEqualTo(WaterMaterials.FALLING);
        assertThat(sheet.alphaMode()).isEqualTo(MaterialData.AlphaMode.BLEND);
        assertThat(sheet.doubleSided()).isTrue();
        assertThat(sheet.values().i32("jets", -1)).isZero();
        assertThat(jets.values().i32("jets", -1)).isEqualTo(1);
        assertThatThrownBy(() -> WaterMaterials.values(MaterialData.builder("x"), new Broken(1f)))
            .isInstanceOf(IllegalStateException.class);
    }

    /** A record whose accessor fails. */
    private record Broken(float value) {
        @Override
        public float value() {
            throw new IllegalArgumentException("broken");
        }
    }

    @Test
    void sprayIsEffectData() {
        assertThat(WaterSpray.FALLING.emitters()).hasSize(1);
        assertThat(WaterSpray.PLUNGE.emitters()).hasSize(1);
        assertThat(WaterSpray.falling(3)).isNotEqualTo(WaterSpray.falling(2));
        assertThat(WaterSpray.plunge(0)).isEqualTo(WaterSpray.plunge(0.05f));
    }
}
