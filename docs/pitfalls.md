# Pitfalls: mistakes made once, so they are not made twice

Each entry is a mistake that really happened in this repository, in the engine's
[docs/pitfalls.md](https://github.com/true-sim/engine/blob/main/docs/pitfalls.md) form: what went wrong, how that was
established, the rule that follows, and where the proof is. Only verified things go here.

## A project with no particle module of its own gets no particle kernel dispatch

**What happened.** With effects-particles on the test classpath and a test shading listing (`slang { shadingHooks }`),
`compileTestSlang` failed: `particles/simulate.slang` includes `particle-modules/dispatch.slang`, which build-logic's
`GenerateSlangRecords` writes only when the sources declare a `[ParticleModule]` of their own
(`ParticleModules.any()` looks at the project's sources, not its libraries' index).

**Rule.** A project rebuilding effects-particles' simulation with its hooks needs `slang { particleModules = ... }`
and at least one particle module in the sources that compile it (here the tests': `testWater.slang`).

**Proof.** The build output `failed to find include file 'particle-modules/dispatch.slang'` before
`src/test/slang/water/test/testWater.slang` declared one; green after.

## Hook parameters take no `[Color]`

`[Color]` belongs to material parameters (the materials module); in a `[ShadingHookParameters]` struct slangc stops
with `unknown attribute 'Color'` (flow.slang's `foamColor`, the first build).

## `f16tof32` needs the Float16 capability

flow.slang first decoded the wetness map's halves with `f16tof32`; slangc declared SPIR-V's `Float16` capability and
the validation layer refused every shader module with the hook (`VUID-VkShaderModuleCreateInfo-pCode-08740`:
`shaderFloat16` not enabled; WaterRenderTest, 10 errors). The halves are decoded by hand (`halfBits`). Rule: no
16-bit float types or intrinsics in shaders the engine's device may run without `shaderFloat16`.

## A displacing material must arrive before the instances that take it

The falls' material (`waterfall`, GEOMETRY_DISPLACE) placed while still loading: the scene's material set took the
placeholder (metallic-roughness, not displacing), and when the data arrived `Materials.set` threw "Material 2 is drawn
by instances and did not displace" (WaterRenderTest). Rule: load the water's materials (`assets.acquire` until
loaded) before `WaterScene.place` (the tests' `WaterRig.preload`).
