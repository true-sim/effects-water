// How the water material types hand a surface's flow to the flow hook (flow.slang), which animates it in world
// space with the frame's time (what a material type's surface() has neither of). A type writes the flow into surface
// fields the lighting reads only where their strength is set (Surface.anisotropy, Surface.iridescence, both left 0),
// and a mark in iridescenceIor; the hook reads them, puts the defaults back, and finishes the surface. Without the
// hook (a view running it not) the surface lights as the type left it, the stashed fields unread.
module waterMark;

import shading;

/** The mark of a water surface: iridescenceIor this less its kind. */
public static const float WATER_MARK = -4096.0;
/** A river's or stream's surface (flowingWater.slang). */
public static const int WATER_CHANNEL = 0;
/** A falling sheet (fallingWater.slang). */
public static const int WATER_SHEET = 1;
/** A sheet's jets (fallingWater.slang with jets). */
public static const int WATER_JET = 2;

/** What a water surface hands the hook. */
public struct WaterFlow
{
    public int kind;              // WATER_*
    public float3 direction;      // unit, world: where it flows
    public float speed;           // metres a second
    public float foam;            // 0 to 1: the vertex's foam
    public float side;            // channels: the shore, 0 to 1; falls: across the sheet, -1 to 1
    public float extra;           // channels: the depth, metres; falls: the atomised fraction
};

/** Stashes `f` in `s` for the hook. */
public void stashFlow(inout Surface s, WaterFlow f)
{
    s.anisotropyDirection = f.direction;
    s.anisotropy = 0;
    s.iridescenceFresnel = float3(f.speed, f.foam, f.side);
    s.iridescence = 0;
    s.iridescenceIor = WATER_MARK - float(f.kind);
    s.iridescenceThickness = f.extra;
}

/** Whether `s` is a water surface a type stashed its flow in. */
public bool isWater(Surface s)
{
    return s.iridescenceIor <= WATER_MARK + 0.5 && s.iridescenceIor >= WATER_MARK - 8.5 && s.iridescence == 0;
}

/** The flow stashed in `s`, its fields put back to their defaults. */
public WaterFlow takeFlow(inout Surface s)
{
    WaterFlow f;
    f.kind = int(round(WATER_MARK - s.iridescenceIor));
    f.direction = s.anisotropyDirection;
    f.speed = s.iridescenceFresnel.x;
    f.foam = s.iridescenceFresnel.y;
    f.side = s.iridescenceFresnel.z;
    f.extra = s.iridescenceThickness;
    s.anisotropyDirection = float3(1, 0, 0);
    s.iridescenceFresnel = 0;
    s.iridescenceIor = 1.3;
    s.iridescenceThickness = 400;
    return f;
}
