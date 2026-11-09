# Working on effects-water

- Read `docs/pitfalls.md` here and the engine's (true-sim/engine `docs/pitfalls.md`) before changing per-frame code,
  shaders or benchmarks: they list mistakes made once, with their evidence and the rule that follows.
- Add to `docs/pitfalls.md` when a bug, a regression or a wrong assumption costs real time. Only verified things: a
  measurement, a reproduced failure, a test that fails without the fix, or code that shows the fault, with a link to
  the proof. A cause not found is written as an observation, never as a guess.
- Every cap, budget, rate and threshold is a setting (`WaterRenderSettings`), never a constant.
- Effects work in the view's render region (dynamic resolution) and allocate nothing per frame
  (`truesim.allocation-free`). Shaders live under `src/main/slang/water/`.
- No libraries beyond the JDK, true-sim's own and LWJGL. MIT.
