package com.truesim.effects.water;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.common.procedural.assets.GenerationCache;
import com.truesim.common.procedural.assets.ProceduralAssets;
import com.truesim.common.procedural.assets.WaterAssets;
import com.truesim.common.procedural.ecology.EcologyTerrain;
import com.truesim.common.procedural.water.Channel;
import com.truesim.common.procedural.water.WaterNetwork;
import com.truesim.common.procedural.water.WaterParameters;
import com.truesim.common.procedural.water.WaterRegion;
import com.truesim.common.procedural.water.Waterfall;
import com.truesim.effects.atmosphere.render.AtmosphereExposure;
import com.truesim.effects.atmosphere.render.AtmosphereSky;
import com.truesim.effects.terrain.Terrain;
import com.truesim.effects.terrain.TerrainMaterial;
import com.truesim.effects.terrain.TerrainSettings;
import com.truesim.effects.terrain.data.TerrainAssets;
import com.truesim.effects.terrain.data.TerrainModifiers;
import com.truesim.effects.terrain.data.TerrainSite;
import com.truesim.effects.terrain.data.TerrainSurface;
import com.truesim.effects.terrain.material.TerrainParameters;
import com.truesim.effects.water.materials.FallingWaterParameters;
import com.truesim.effects.water.materials.FlowingWaterParameters;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.PathAssets;
import com.truesim.scene.render.MaterialData;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Films of the water over Lauterbrunnen (effects-terrain's cache), rendered by the engine: the Weisse Luetschine close
 * up from its bank, the Staubbach Fall's foot with its spray and mist, and the valley floor at noon with the river
 * before the fall. 1280 x 720 at 30 frames a second into {@code -Pfilms=<dir>} as {@code water-lauterbrunnen.mp4}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_FILMS", matches = "true")
class WaterFilms {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final double FPS = 30;
    private static final Setting<Float> EV100 = AtmosphereSky.ATMOSPHERE.setting("ev100");
    private static final WaterRegion REGION = new WaterRegion(-8000, -3000, 6000, 13000);

    /** A shot: a camera moving from one place to another, looking from one target to another, in its seconds. */
    record Shot(double seconds, double[] from, double[] to, double[] lookFrom, double[] lookTo, double fov) {
    }

    static Path cache() {
        String given = System.getProperty("truesim.terrain.cache");
        return given != null ? Path.of(given) : Path.of("..", "..", "effects-terrain", "terrain-cache");
    }

    static TerrainSite load(WaterRig rig) throws InterruptedException {
        PathAssets paths = new PathAssets(rig.assets);
        TerrainAssets.register(paths);
        TerrainAssets.mount(paths, cache());
        AssetRef<TerrainSite> ref = TerrainAssets.site(paths, "lauterbrunnen");
        rig.assets.acquire(ref);
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (rig.assets.get(ref) == null) {
            assertThat(rig.assets.failure(ref)).as("the site loading").isNull();
            assertThat(System.nanoTime()).as("the site loading").isLessThan(deadline);
            Thread.sleep(5);
            rig.tasks.drainCompleted();
        }
        return rig.assets.get(ref);
    }

    static MaterialData terrainMaterial() {
        TerrainMaterial.Layer[] layers = TestTextures.alpine();
        com.truesim.common.math.color.Color white = new com.truesim.common.math.color.Color(1, 1, 1, 1);
        TerrainMaterial.Cover cover = new TerrainMaterial.Cover(
            TestTextures.layer("gravel", 0.30f, 0.29f, 0.27f, 0.5f, 3, 0.9f, 48, 2),
            TestTextures.layer("cobbles", 0.24f, 0.23f, 0.22f, 0.6f, 4, 0.8f, 24, 2),
            TestTextures.layer("dirt", 0.26f, 0.19f, 0.13f, 0.35f, 2, 0.95f, 12, 4),
            TestTextures.layer("sand", 0.40f, 0.37f, 0.30f, 0.25f, 1, 0.9f, 32, 4));
        TerrainParameters rules = TerrainParameters.DEFAULTS.withRockColor(white).withGrassColor(white)
            .withScreeColor(white).withSnowColor(white).withGravelColor(white).withCobblesColor(white)
            .withDirtColor(white).withSandColor(white);
        return TerrainMaterial.data(rules, layers[0], layers[1], layers[2], layers[3], cover);
    }

    static Process ffmpeg(Path file) throws IOException {
        return new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s",
            WIDTH + "x" + HEIGHT, "-r", String.valueOf((int) FPS), "-i", "-", "-c:v", "libx264", "-preset", "slow",
            "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", file.toString())
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
    }

    static int tone(double c) {
        double x = Math.max(0, c);
        double mapped = Math.clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0, 1);
        double s = mapped <= 0.0031308 ? 12.92 * mapped : 1.055 * Math.pow(mapped, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }

    private static double[] p(double x, double y, double z) {
        return new double[] {x, y, z};
    }

    @Test
    void lauterbrunnen() throws Exception {
        assumeThat(Files.isRegularFile(cache().resolve("sites/lauterbrunnen/site.terrain")))
            .as("the Lauterbrunnen site in effects-terrain's cache").isTrue();
        Path dir = Path.of(System.getProperty("truesim.films", "build/films"));
        Files.createDirectories(dir);
        EngineSettings settings = new EngineSettings();
        settings.set(TerrainSettings.BUILD_BUDGET, 64);
        settings.set(WaterRenderSettings.MIST_DENSITY, 0.35f);
        Process ffmpeg = ffmpeg(dir.resolve("water-lauterbrunnen.mp4"));
        byte[] rgb = new byte[3 * WIDTH * HEIGHT];
        try (TestGpu gpu = TestGpu.open(); OutputStream out = ffmpeg.getOutputStream()) {
            // noon: the sun in the south, high
            try (WaterRig rig = new WaterRig(gpu, WIDTH, HEIGHT, settings, 180, 52)) {
                TerrainSite site = load(rig);
                TerrainSurface ground = new TerrainSurface(site, TerrainSettings.surface(settings));
                EcologyTerrain heights = (x, z) -> ground.heightAt(x, z, 2);
                long start = System.nanoTime();
                WaterNetwork rivers = WaterNetwork.build(heights, REGION, WaterParameters.DEFAULTS);
                System.out.printf(Locale.ROOT, "network: %d channels, %d falls in %.1f s%n", rivers.channels().size(),
                    rivers.falls().size(), (System.nanoTime() - start) / 1e9);
                rig.flow.wetness(rig.closing(WaterWetness.of(rivers, settings).upload(rig.device)));
                Terrain terrain = rig.terrain(site, terrainMaterial().ref(),
                    TerrainModifiers.of(WaterTerrain.of(rivers)));
                PathAssets paths = new PathAssets(rig.assets);
                ProceduralAssets procedural = new ProceduralAssets(paths, new GenerationCache(
                    Files.createDirectories(Path.of("build", "generated-rocks")))).install();
                WaterScene.Look look = WaterScene.Look.of(
                    WaterMaterials.river(FlowingWaterParameters.DEFAULTS).ref(),
                    WaterMaterials.sheet(FallingWaterParameters.DEFAULTS).ref(),
                    WaterMaterials.jets(FallingWaterParameters.DEFAULTS).ref(),
                    MaterialData.builder(MaterialData.METALLIC_ROUGHNESS).set("baseColor", 0.24f, 0.23f, 0.21f, 1f)
                        .set("roughness", 0.75f).build().ref());
                rig.preload(look.river(), look.sheet(), look.jets());
                WaterScene water = new WaterScene(rig.scene, new WaterAssets(paths), rivers, look)
                    .stones(procedural).effects(rig.effects).place(settings);
                Waterfall staubbach = staubbach(rivers);
                Channel fallChannel = rivers.channels().get(staubbach.channel());
                double[] lip = rivers.fallOrigin(staubbach.id());
                Waterfall.Path path = staubbach.path();
                double fx = path.x()[path.count() - 1];
                double fy = path.y()[path.count() - 1];
                double fz = path.z()[path.count() - 1];
                double[] river = river(rivers);
                double rx = river[0];
                double rz = river[1];
                double rdx = river[2];
                double rdz = river[3];
                double ry = terrain.heightAt(rx, rz);
                double dx = fallChannel.directionX(staubbach.lip());
                double dz = fallChannel.directionZ(staubbach.lip());
                Shot[] shots = {
                    // the river close up from its bank, moving downstream with it
                    new Shot(9, p(rx - rdz * 14 - rdx * 10, ry + 3.5, rz + rdx * 14 - rdz * 10),
                        p(rx - rdz * 13 + rdx * 12, ry + 3, rz + rdx * 13 + rdz * 12),
                        p(rx + rdx * 6, ry, rz + rdz * 6), p(rx + rdx * 30, ry, rz + rdz * 30), 60),
                    // the Staubbach's foot: spray and mist, looking up the fall
                    new Shot(9, p(fx + dx * 110 - dz * 50, fy + 5, fz + dz * 110 + dx * 50),
                        p(fx + dx * 85 + dz * 35, fy + 8, fz + dz * 85 - dx * 35),
                        p(fx, fy + 25, fz), p(fx, fy + 170, fz), 70),
                    // the valley floor at noon: the fall's veil down the cliff, the stream before it
                    new Shot(11, p(fx + dx * 520 - dz * 160, fy + 10, fz + dz * 520 + dx * 160),
                        p(fx + dx * 380 - dz * 90, fy + 12, fz + dz * 380 + dx * 90),
                        p(lip[0], lip[1] - 140, lip[2]), p(lip[0], lip[1] - 150, lip[2]), 50)};
                long frames = 0;
                double ev = 12;
                for (Shot shot : shots) {
                    rig.fovY = Math.toRadians(shot.fov());
                    place(rig, shot, 0);
                    rig.settle(30);
                    // spray running before filming
                    for (int warm = 0; warm < 60; warm++) {
                        rig.frame();
                    }
                    int count = (int) (shot.seconds() * FPS);
                    for (int f = 0; f < count; f++) {
                        place(rig, shot, f / (double) (count - 1));
                        rig.settings.set(EV100, (float) ev);
                        rig.frame();
                        float[] color = rig.rgb();
                        double logSum = 0;
                        double units = 1.2 * Math.pow(2, ev);
                        for (int i = 0; i < WIDTH * HEIGHT; i++) {
                            double luminance = 0.2126 * color[3 * i] + 0.7152 * color[3 * i + 1]
                                + 0.0722 * color[3 * i + 2];
                            logSum += Math.log(1e-4 + luminance * units);
                            rgb[3 * i] = (byte) tone(color[3 * i]);
                            rgb[3 * i + 1] = (byte) tone(color[3 * i + 1]);
                            rgb[3 * i + 2] = (byte) tone(color[3 * i + 2]);
                        }
                        double target = AtmosphereExposure.ev100(Math.exp(logSum / (WIDTH * HEIGHT))) + 1.0;
                        ev = frames == 0 ? target : AtmosphereExposure.adapt(ev, target, 1 / FPS, 1.5);
                        out.write(rgb);
                        frames++;
                    }
                    System.out.printf(Locale.ROOT, "shot done: %d frames, %d tiles, %d sprays%n", frames,
                        terrain.shownTiles(), water.sprays().size());
                }
                water.close();
            }
        }
        assertThat(ffmpeg.waitFor()).as("ffmpeg").isZero();
    }

    private static void place(WaterRig rig, Shot shot, double t) {
        double s = t * t * (3 - 2 * t);
        double[] eye = new double[3];
        double[] at = new double[3];
        for (int k = 0; k < 3; k++) {
            eye[k] = shot.from()[k] + s * (shot.to()[k] - shot.from()[k]);
            at[k] = shot.lookFrom()[k] + s * (shot.lookTo()[k] - shot.lookFrom()[k]);
        }
        double ground = rig.terrain.heightAt(eye[0], eye[2]);
        eye[1] = Math.max(eye[1], ground + 1.8);
        rig.look(eye[0], eye[1], eye[2], at[0] - eye[0], at[1] - eye[1], at[2] - eye[2]);
    }

    /** The tallest free fall within 500 m of where the Staubbach is (the village's south end, the west cliff). */
    static Waterfall staubbach(WaterNetwork rivers) {
        Waterfall best = null;
        for (Waterfall f : rivers.falls()) {
            double[] lip = rivers.fallOrigin(f.id());
            double ex = lip[0] + 280;
            double ez = lip[2] - 500;
            if (f.free() && ex * ex + ez * ez < 500 * 500 && (best == null || f.height() > best.height())) {
                best = f;
            }
        }
        assertThat(best).as("the Staubbach").isNotNull();
        return best;
    }

    /** The main river near the village: a point and its direction (x, z, dx, dz). */
    static double[] river(WaterNetwork rivers) {
        double nearest = Double.POSITIVE_INFINITY;
        double[] out = null;
        for (Channel c : rivers.channels()) {
            for (int i = 0; i < c.count(); i++) {
                double d = Math.abs(c.z(i) - 250);
                if (c.discharge(i) > 3 && d < nearest) {
                    nearest = d;
                    out = new double[] {c.x(i), c.z(i), c.directionX(i), c.directionZ(i)};
                }
            }
        }
        return out;
    }
}
