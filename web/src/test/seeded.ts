/** A small seeded generator (mulberry32), so every randomized case can be repeated. Returns integers below `below`. */
export function seeded(seed: number) {
  let state = seed >>> 0;
  return (below: number) => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return Math.floor((((t ^ (t >>> 14)) >>> 0) / 4294967296) * below);
  };
}
