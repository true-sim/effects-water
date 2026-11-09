// The water's flow, as a surface hook ("water.flow"): where a water material type stashed its flow (waterMark.slang)
// it animates the surface in world space with the frame's time, the same in every way of shading:
//
//   channels  flow-mapped ripples (two phases of noise advected along the flow and cross-faded, so the pattern moves
//             with the current without stretching; Vlachos 2010), stronger in fast water; foam broken into patches
//             drifting with the current where the vertices carry it, and lapping at the shore
//   falls     streaks running down the sheet at the water's speed, brighter and rougher as it aerates
//
// Other surfaces pass untouched. The technique (WaterFlow.java) adds the hook to a view and writes its time.
module flow;

import hooks;
import shading;
import water.waterMark;

[ShadingHookParameters]
public struct WaterFlowParameters
{
    public float time = 0;                // seconds: the technique's clock (wrapped every day)
    public float rippleScale = 0.8;       // metres across the smallest ripples
    public float rippleSlope = 0.22;      // their slope in water flowing 1 m/s (the square root of the speed's ratio)
    public float cycle = 1.6;             // seconds a flow-map phase lasts
    public float foamScale = 0.45;        // metres across a foam cell
    public float foamCover = 0.85;        // how much of the foam the vertices carry shows as patches
    public float3 foamColor = float3(0.88, 0.91, 0.93);
    public float lapping = 0.6;           // the shore's foam line
    public float streakScale = 0.35;      // falls: metres across a streak
    public float streakContrast = 0.45;   // falls: how much the streaks brighten and darken
};

/** A hash of a lattice point, 0 to 1. */
float hash2(int2 c)
{
    uint h = uint(c.x) * 0x8da6b343u ^ uint(c.y) * 0xd8163841u;
    h ^= h >> 13;
    h *= 0x5bd1e995u;
    h ^= h >> 15;
    return float(h & 0xffffu) / 65535.0;
}

/** Value noise at `x` (0 to 1) and its gradient (in .yz). */
float3 noiseGrad(float2 x)
{
    float2 i = floor(x);
    float2 f = x - i;
    float2 u = f * f * (3 - 2 * f);
    float2 du = 6 * f * (1 - f);
    int2 c = int2(i);
    float a = hash2(c);
    float b = hash2(c + int2(1, 0));
    float d = hash2(c + int2(0, 1));
    float e = hash2(c + int2(1, 1));
    float k1 = b - a;
    float k2 = d - a;
    float k3 = a - b - d + e;
    float v = a + k1 * u.x + k2 * u.y + k3 * u.x * u.y;
    float2 g = du * float2(k1 + k3 * u.y, k2 + k3 * u.x);
    return float3(v, g);
}

/** Three octaves of value noise at `x`, -0.5 to 0.5 about, and its gradient. */
float3 fbm(float2 x)
{
    float3 sum = 0;
    float amplitude = 0.5;
    float scale = 1;
    [ForceUnroll]
    for (int k = 0; k < 3; k++)
    {
        float3 n = noiseGrad(x * scale + float2(17.3 * k, 9.1 * k));
        sum += float3((n.x - 0.5) * amplitude, n.yz * amplitude * scale);
        amplitude *= 0.5;
        scale *= 2.03;
    }
    return sum;
}

[ShadingHook("water.flow")]
public struct WaterFlowHook : ISurfaceHook
{
    public typealias Parameters = WaterFlowParameters;

    public static void surface(ShadingPoint p, WaterFlowParameters* params, inout Surface s)
    {
        if (!isWater(s))
        {
            return;
        }
        WaterFlow f = takeFlow(s);
        float time = params[0].time;
        float3 world = p.world();
        if (f.kind == WATER_CHANNEL)
        {
            channel(params, f, world, time, s);
        }
        else
        {
            fall(params, f, world, time, s);
        }
    }

    static void channel(WaterFlowParameters* params, WaterFlow f, float3 world, float time, inout Surface s)
    {
        float scale = max(params[0].rippleScale, 0.05);
        float cycle = max(params[0].cycle, 0.1);
        float2 level = f.direction.xz;
        level = dot(level, level) > 1e-8 ? normalize(level) : float2(1, 0);
        float2 velocity = level * f.speed;
        // two phases half a cycle apart, each advected from its start and faded out before it stretches too far
        float phase0 = frac(time / cycle);
        float phase1 = frac(time / cycle + 0.5);
        float w0 = 1 - abs(2 * phase0 - 1);
        float w1 = 1 - w0;
        float2 at = world.xz;
        float3 r0 = fbm((at - velocity * (phase0 * cycle)) / scale);
        float3 r1 = fbm((at - velocity * (phase1 * cycle)) / scale + float2(0.37, 0.71));
        // ripples stretched along the flow: across it the water's surface is rougher
        float2 gradient = (r0.yz * w0 + r1.yz * w1) / scale;
        float2 across = float2(-level.y, level.x);
        gradient = level * dot(gradient, level) * 0.6 + across * dot(gradient, across);
        float slope = params[0].rippleSlope * sqrt(max(f.speed, 0.05)) * (0.5 + f.foam);
        float3 n = s.normal;
        float3 tilted = normalize(n - float3(gradient.x, 0, gradient.y) * slope);
        s.normal = dot(tilted, n) > 0.2 ? tilted : n;
        // foam: patches drifting with the current where the vertices carry foam, and a line lapping at the shore
        float fs = max(params[0].foamScale, 0.05);
        float3 c0 = fbm((at - velocity * (phase0 * cycle)) / fs + float2(5.1, 2.3));
        float3 c1 = fbm((at - velocity * (phase1 * cycle)) / fs + float2(8.7, 4.4));
        float cells = 0.5 + c0.x * w0 + c1.x * w1;
        float carried = saturate(f.foam * params[0].foamCover);
        float patches = smoothstep(1 - carried, 1 - carried + 0.18, cells) * step(0.02, carried);
        float lap = params[0].lapping * smoothstep(0.75, 0.98, f.side)
            * (0.5 + 0.5 * sin(time * 1.7 + (at.x + at.y) * 0.9 + c0.x * 6));
        float mask = saturate(max(patches, lap * smoothstep(0.4, 0.7, cells)));
        s.albedo = lerp(s.albedo, params[0].foamColor, mask);
        s.transmission *= 1 - mask;
        s.roughness = lerp(s.roughness, 0.6, mask);
    }

    static void fall(WaterFlowParameters* params, WaterFlow f, float3 world, float time, inout Surface s)
    {
        float scale = max(params[0].streakScale, 0.02);
        // along the fall: height, moving down at the water's speed; across: the horizontal position across it
        float2 side = float2(-f.direction.z, f.direction.x);
        if (dot(side, side) < 1e-4)
        {
            side = normalize(float2(s.normal.z, -s.normal.x) + float2(1e-4, 0));
        }
        side = normalize(side);
        float a = dot(world.xz, side) / scale;
        float down = (world.y + time * max(f.speed, 1.0)) / (scale * 7);
        float3 n = fbm(float2(a, down));
        float3 m = fbm(float2(a * 0.37 + 11.0, down * 0.5 + time * 0.3));
        float streak = 0.5 + n.x + 0.5 * m.x;
        float contrast = params[0].streakContrast * (0.4 + 0.6 * f.foam);
        s.albedo *= 1 + (streak - 0.5) * 2 * contrast;
        s.roughness = saturate(s.roughness + (streak - 0.5) * 0.3);
        float3 normal = s.normal;
        float3 bent = normalize(normal + float3(side.x, 0, side.y) * (n.y * 0.25));
        s.normal = dot(bent, normal) > 0.2 ? bent : normal;
    }
};
