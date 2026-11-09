// The water's flow, as a surface hook ("water.flow"): where a water material type stashed its flow (waterMark.slang)
// it animates the surface in world space with the frame's time, the same in every way of shading:
//
//   channels  flow-mapped ripples (two phases of noise advected along the flow and cross-faded, so the pattern moves
//             with the current without stretching; Vlachos 2010), stronger in fast water; standing waves where the
//             flow is fast and broken (over stones and steps: crests across the flow, their wavelength the one a
//             gravity wave needs to stand in the current, 2 pi v^2 / g), white at their crests; foam in streaks along
//             the flow, drifting with it, where the vertices carry foam; a line lapping at the shore
//   falls     streaks running down the sheet at the water's speed, brighter and rougher as it aerates
//
// Every other surface (the banks, stones, the rock behind a fall) is darkened and glossed where the water wets it: a
// band above the water's edge, and the rock a fall runs down (WaterWetness.java's map, at the record's address).
// The technique (WaterFlow.java) adds the hook to a view and writes its time and the map's address.
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
    public float waves = 0.35;            // the standing waves' slope where the water is white
    public float streakLength = 6;        // channels: foam streaks' length along the flow over their width
    public float wetDarkening = 0.35;     // what wet ground's colour is multiplied by
    public float wetGloss = 0.45;         // how much smoother wet ground turns (a share of its roughness)
    public float wetFade = 0.35;          // metres over which the wet band fades out at its top
    public uint64_t wetness = 0;          // the wetness map (WaterWetness), or 0: none
};

/** The wetness map's header (WaterWetness.java writes it). */
struct WetHeader
{
    float x0;          // the region's west edge
    float z0;          // north edge
    float cell;        // metres a cell
    float tile;        // metres a tile
    uint tilesX;
    uint tilesZ;
    uint cells;        // cells a tile side
    uint index;        // byte offset of the tile index: a uint per tile, 0 for none, else its number plus 1
    uint bases;        // byte offset of the tiles' base heights, a float each
    uint data;         // byte offset of the cells: per cell two float16 above the base, top low, bottom high
};

/** A float16's bits as a float, by hand: f16tof32 would need the Float16 capability, which devices may lack. */
float halfBits(uint h)
{
    uint e = (h >> 10) & 31u;
    uint m = h & 1023u;
    float sign = (h & 0x8000u) != 0 ? -1.0 : 1.0;
    if (e == 0)
    {
        return sign * float(m) * 5.9604645e-8; // subnormal: m x 2^-24
    }
    if (e == 31)
    {
        return sign * 65504.0;
    }
    return asfloat(((h & 0x8000u) << 16) | ((e + 112u) << 23) | (m << 13));
}

/** How wet the point `w` is (0 to 1) by the map at `address`. */
float wetness(uint64_t address, float3 w, float fade)
{
    WetHeader* h = (WetHeader*) address;
    float x0 = h[0].x0;
    float z0 = h[0].z0;
    float tile = h[0].tile;
    uint tilesX = h[0].tilesX;
    uint tilesZ = h[0].tilesZ;
    float tx = (w.x - x0) / tile;
    float tz = (w.z - z0) / tile;
    if (tx < 0 || tz < 0 || tx >= float(tilesX) || tz >= float(tilesZ))
    {
        return 0;
    }
    uint ti = min(uint(tx), tilesX - 1);
    uint tj = min(uint(tz), tilesZ - 1);
    uint* index = (uint*) (address + h[0].index);
    uint number = index[tj * tilesX + ti];
    if (number == 0)
    {
        return 0;
    }
    uint cells = h[0].cells;
    uint ci = min(uint((tx - float(ti)) * float(cells)), cells - 1);
    uint cj = min(uint((tz - float(tj)) * float(cells)), cells - 1);
    float* bases = (float*) (address + h[0].bases);
    uint* data = (uint*) (address + h[0].data);
    uint word = data[(number - 1) * cells * cells + cj * cells + ci];
    if (word == 0)
    {
        return 0;
    }
    float base = bases[number - 1];
    float top = base + halfBits(word & 0xffffu);
    float bottom = base + halfBits(word >> 16);
    if (w.y < bottom)
    {
        return 0;
    }
    return 1 - smoothstep(top - max(fade, 0.01), top, w.y);
}

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
            uint64_t map = params[0].wetness;
            if (map != 0)
            {
                float wet = wetness(map, p.world(), params[0].wetFade);
                if (wet > 0)
                {
                    s.albedo *= lerp(1.0, params[0].wetDarkening, wet);
                    s.roughness *= lerp(1.0, 1 - params[0].wetGloss, wet);
                }
            }
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
        // standing waves where the water runs fast and white: crests across the flow, standing still, their
        // wavelength 2 pi v^2 / g (a gravity wave's that stands in the current), broken up by noise across
        float white = smoothstep(0.25, 0.9, max(f.foam, f.speed / 4.5));
        float2 side = float2(-level.y, level.x);
        float along = dot(at, level);
        float acrossAt = dot(at, side);
        float wavelength = clamp(6.2831853 * f.speed * f.speed / 9.81, 0.4, 8.0);
        float3 breakup = fbm(float2(along / (wavelength * 2), acrossAt / (wavelength * 1.2)) + float2(3.3, 1.9));
        float crestPhase = 6.2831853 * along / wavelength + breakup.x * 4 + 0.6 * sin(time * 2.1 + acrossAt * 0.7);
        float crest = 0.5 + 0.5 * sin(crestPhase);
        float waveSlope = params[0].waves * white * (0.6 + breakup.x);
        float3 waved = normalize(s.normal - float3(level.x, 0, level.y) * (cos(crestPhase) * waveSlope));
        s.normal = dot(waved, n) > 0.2 ? waved : s.normal;
        // foam: streaks along the flow drifting with it where the vertices carry foam, white water at the waves'
        // crests, and a line lapping at the shore
        float fs = max(params[0].foamScale, 0.05);
        float stretch = max(params[0].streakLength, 1.0);
        float2 m0 = at - velocity * (phase0 * cycle);
        float2 m1 = at - velocity * (phase1 * cycle);
        float3 c0 = fbm(float2(dot(m0, level) / (fs * stretch), dot(m0, side) / fs) + float2(5.1, 2.3));
        float3 c1 = fbm(float2(dot(m1, level) / (fs * stretch), dot(m1, side) / fs) + float2(8.7, 4.4));
        float cells = 0.5 + c0.x * w0 + c1.x * w1;
        float carried = saturate(f.foam * params[0].foamCover);
        float patches = smoothstep(1 - carried, 1 - carried + 0.18, cells) * step(0.02, carried);
        float crests = white * smoothstep(0.55, 0.9, crest) * smoothstep(0.35, 0.6, cells);
        float lap = params[0].lapping * smoothstep(0.75, 0.98, f.side)
            * (0.5 + 0.5 * sin(time * 1.7 + (at.x + at.y) * 0.9 + c0.x * 6));
        float mask = saturate(max(max(patches, crests), lap * smoothstep(0.4, 0.7, cells)));
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
