# true-sim effects-water

Rivers, streams and waterfalls drawn for true-sim, from
[common-procedural-water](https://github.com/true-sim/common-procedural)'s networks: channels, falls, stones and the
beds' field. It is an optional library over the engine's core and uses only the core's generic hooks:

- **material types** (`[MaterialType]`, registered with the renderer's `Materials`): `flowingWater` for the channels'
  surfaces, transmissive with a volume (the engine's transmission samples the opaque scene behind, refracted, and
  absorbs it over the depth the vertices carry), `waterfall` for the falls' sheets and jets, alpha-blended;
- a **surface shading hook** (`water.flow`, `WaterFlow` adds it to a view and keeps its clock): the flow animated in
  world space in every way of shading;
- **scene components**: renderables for the meshes (generated assets through the asset cache), effects-atmosphere's
  `FogVolume` for the mist, scene-effects' effects (drawn by effects-particles) for the spray;
- effects-terrain's **terrain modifier** (`TerrainModifier`) for the beds and banks carved into the terrain, laid with
  gravel and sand.

| Module | Contents |
|---|---|
| `effects-water` | `WaterMaterials`, `WaterFlow`, `WaterSway`, `WaterWetness`, `WaterScene`, `WaterSpray`, `WaterTerrain`, `WaterRenderSettings`; the Slang of `src/main/slang/water/` |

```kotlin
dependencies {
    implementation("com.truesim.effects:effects-water:0.3.0")   // engine-scene 0.201, common-procedural 0.11, effects-terrain 0.5
}
```

## Using it

```java
// the ground before the water changes it, and the network over it (common-procedural-water)
TerrainSurface ground = new TerrainSurface(site, TerrainSettings.surface(settings));
WaterNetwork rivers = WaterNetwork.build((x, z) -> ground.heightAt(x, z, 2), region, WaterSettings.parameters(settings));
// the terrain with the beds and banks carved in (other modifiers, a settlement's roads, after it)
Terrain terrain = new Terrain(world, resources, site, terrainMaterial, settings,
    TerrainModifiers.of(WaterTerrain.of(rivers)));
// the materials' types, once; the materials loaded (the falls' displace: they must arrive before their nodes)
WaterMaterials.register(resources.materials());
WaterScene.Look look = WaterScene.Look.of(WaterMaterials.river(FlowingWaterParameters.DEFAULTS).ref(),
    WaterMaterials.sheet(FallingWaterParameters.DEFAULTS).ref(),
    WaterMaterials.jets(FallingWaterParameters.DEFAULTS).ref(), stoneMaterial);
// ... cache.acquire(look.river(), look.sheet(), look.jets()) and wait until they are loaded, then:
WaterScene water = new WaterScene(scene, new WaterAssets(paths), rivers, look)
    .stones(proceduralAssets)                    // rocks of common-procedural-assets, or none
    .effects(effects)                            // scene-effects' Effects: spray, or none
    .place(settings);
// in each view: the flow's hook, with the wet ground's map; each frame, its clock and the falls' sway
GpuBuffer wet = WaterWetness.of(rivers, settings).upload(device);  // close it with the view
WaterFlow flow = new WaterFlow(view).wetness(wet);
port.add(flow);
WaterSway sway = new WaterSway(gpuScene);
flow.advance(frame.delta());
sway.update(frame.delta(), settings);
```

With engine-render's `render.ssr.transparent` on (engine 0.201:
`graph.settings().set(ScreenSpaceReflections.TRANSPARENT, true)`, while `render.ssr` is on), the water reflects the
opaque scene (cliffs, trees, houses, its stones and banks) in screen space, as opaque surfaces do. The films run with
it on.

The view also needs what draws the rest: the view's transparency (`ChosenTransparency`: the water draws with the
blended surfaces), effects-particles' `ParticleDraw` and simulation for the spray, effects-atmosphere's `AtmosphereFog`
(with `.lighting(atmosphere)`: the mist lit by the sky and sun) for the mist's volumes. The application's shading listing
(`slang { shadingHooks = ... }`) must hold `water.flow` with every other hook it draws with.

## What it draws

- **Channels** (`flowingWater`): glacial water, milky turquoise-grey: the silt scatters light back within the water
  (`silt`, `turbidity`), more the deeper it is (`clarity`: the bed shows through shallow water), darkening toward the
  pools' colour in deep water (`pool`, `poolDepth`), clearer at the shore; what passes refracted (`ior` 1.33) and
  absorbed over the depth (`attenuationColor` after `attenuationDistance`); Fresnel reflections; foam where the vertices
  carry it (the Froude number, emergent stones, plunge pools). The flow hook moves it: two phases of ripples advected
  along the flow and cross-faded (Vlachos 2010), each place's phase offset by slow noise so no pulse sweeps the river,
stronger in fast water; the detail is gradient noise in octaves each turned by an irrational angle and scaled by an
irrational ratio (no lattice or tile shows), its finer octaves and slope fading with distance into roughness; standing waves where the water runs fast and
  white, over stones and steps: crests across the flow at the wavelength a gravity wave needs to stand in the current
  (2 pi v^2 / g), white at their crests (`waves`); foam in streaks along the flow drifting with it (`streakLength`); a
  foam line lapping at the shore.
- **Falls** (`waterfall`): the sheet glassy at the lip and aerated below, its sides thinning, a white veil left as the
  water atomises (`veil`); the jets thinner and ragged; streaks running down at the water's speed (the hook). The veil
  sways in the wind: the type displaces its vertices by the `water.sway` geometry hook (`WaterSway` writes it each frame
  from `water.render.windSpeed` and `windDirection`), drifting downwind by how far the water has fallen
  (`swayDrift`, 0.03; out from the face, little across it), the swing travelling down the fall.
- **Wet ground** (`WaterWetness`): every other surface the water wets is darkened and glossed by the flow hook
  (`wetDarkening`, `wetGloss`, `wetFade`): the banks and stones in a band above the water's edge
  (`water.render.wetHeight`), and the rock a fall runs down, a dark wet streak from its lip to its foot. A sparse map
  of tiles over the region, uploaded once, read by its address.
- **Spray** (`WaterSpray`): from each spray source of a fall (common-procedural-water's `Waterfall.spray()`), streaks
  breaking into drifting drops, and a broad halo of soft puffs thrown up and spreading at the foot
  (`water.render.plungeReach` of the fall's height), drifting with the wind; rates from the water each source turns to
  spray (`water.render.sprayRate`, capped).
- **Mist**: an ellipsoid fog volume at each fall's foot, centred on it, as dense as the spray rate makes it
  (`water.render.mistDensity`), thinning upward and faded all the way in from its edge, so no shell shows close by.
- **Beds and banks** (`WaterTerrain`): the terrain cut to the bed's parabola and the banks' slope (never raised), gravel
  in the bed and on the bars, sand across the bank's outer part where grass grows back.
- **Stones**: the beds' stones as rocks scaled and turned, the largest first up to `water.render.stoneLimit`.

How the hook gets the flow: a material type's `surface()` has the vertex's flow but neither the world position nor the
time; a hook has both but not the vertex. So the water types stash the flow in surface fields the lighting reads only
where their strength is set (`anisotropyDirection`, `iridescenceFresnel`, `iridescenceIor` as a mark,
`iridescenceThickness`; `anisotropy` and `iridescence` stay 0), and the hook takes it back and puts the defaults back
(`waterMark.slang`). Without the hook in a view the water lights as the types leave it, still.

Reflections: with `render.ssr.transparent` off (the engine's default), transmissive and blended surfaces get no
screen-space reflections and the water reflects the environment (the sky capture) only.

## Settings

`WaterRenderSettings.ALL` (`water.render.*`): `stones`, `stoneLimit`, `stoneLevel`, `minFall` (the lowest fall dressed
with spray and mist), `sprayRate`, `maxSpray`, `minSpray`, `mist`, `mistDensity`, `windSpeed`, `windDirection`,
`swayDrift`, `wetHeight`, `wetCell`, `plungeReach`. The network's own tunables are
common-procedural-water's `WaterSettings` (`water.*`); the hook's (`rippleScale`, `rippleSlope`, `cycle`, `foamScale`,
`foamCover`, `foamColor`, `lapping`, `streakScale`, `streakContrast`, `waves`, `streakLength`, `wetDarkening`, `wetGloss`,
`wetFade`) are its record's, set as the scene's values
(engine-scene's `ShadingHookComponent` for `water.flow`).

## Tests and films

- `WaterSceneTest`, `WaterPartsTest` (CPU): a stream falling over a cliff placed as nodes (surfaces, falls, stones,
  mist, spray), what the settings leave out, the beds carved and laid with gravel and sand, the materials' data, the
  parameters' ranges.
- `WaterRenderTest` (GPU, validated): the water shows, the flow moves it, and with the hook off it stands still.
- `WaterFilms` (`TRUESIM_FILMS=true`, `-Pfilms=<dir>`, effects-terrain's cache): the Weisse Luetschine close up from its
  bank, the Staubbach Fall's foot with its spray and mist, the valley floor at noon.

## Building

```
GITHUB_TOKEN=$(gh auth token) GITHUB_ACTOR=<you> ./gradlew build
```

Sibling checkouts of the engine and its libraries are included builds when present (`-Ptruesim.<repo>=false` to use
the published ones).

## Licence

MIT. See [LICENSE](LICENSE).
