const test = require("node:test");
const assert = require("node:assert/strict");

global.window = {};
require("../../main/resources/static/js/whiteboard-core.js");
const { clampViewport, finitePoint, simplifyPoints, ActionHistory } = window.RepetHelperBoardCore;

test("viewport stays inside the logical workspace at every zoom", () => {
  const bounds = { minX: -50_000, maxX: 50_000, minY: -50_000, maxY: 50_000 };
  const low = clampViewport([.01, 0, 0, .01, 9e9, -9e9], 390, 700, bounds);
  assert.equal(low[0], .1);
  const centerX = (390 / 2 - low[4]) / low[0];
  const centerY = (700 / 2 - low[5]) / low[0];
  assert.ok(centerX >= bounds.minX && centerX <= bounds.maxX);
  assert.ok(centerY >= bounds.minY && centerY <= bounds.maxY);
  const high = clampViewport([20, 0, 0, 20, -9e9, 9e9], 1280, 800, bounds);
  assert.equal(high[0], 8);
});

test("invalid and remote coordinates are rejected", () => {
  assert.equal(finitePoint({ x: 10, y: -20 }), true);
  assert.equal(finitePoint({ x: Infinity, y: 0 }), false);
  assert.equal(finitePoint({ x: 1_000_001, y: 0 }), false);
});

test("dense path preview is simplified and capped", () => {
  const points = Array.from({ length: 8_000 }, (_, index) => ({ x: index / 10, y: index / 10 }));
  const simplified = simplifyPoints(points, .8);
  assert.ok(simplified.length < points.length);
  assert.ok(simplified.length <= 5_000);
  assert.deepEqual(simplified[0], points[0]);
});

test("history respects limits and clears redo on a new action", () => {
  const history = new ActionHistory(2, 10_000);
  history.push({ id: 1 }); history.push({ id: 2 }); history.push({ id: 3 });
  assert.equal(history.undoStack.length, 2);
  const entry = history.takeUndo(); history.commitUndo(entry);
  assert.equal(history.canRedo(), true);
  history.push({ id: 4 });
  assert.equal(history.canRedo(), false);
  history.clear();
  assert.equal(history.canUndo(), false);
});
