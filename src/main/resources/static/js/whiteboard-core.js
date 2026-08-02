(() => {
  "use strict";

  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const finitePoint = (point, limit = 1_000_000) => Boolean(point)
    && Number.isFinite(point.x) && Number.isFinite(point.y)
    && Math.abs(point.x) <= limit && Math.abs(point.y) <= limit;

  function clampViewport(transform, width, height, bounds) {
    const next = [...transform];
    const zoom = clamp(Number(next[0]) || 1, .1, 8);
    next[0] = zoom; next[3] = zoom; next[1] = 0; next[2] = 0;
    let centerX = (width / 2 - (Number(next[4]) || 0)) / zoom;
    let centerY = (height / 2 - (Number(next[5]) || 0)) / zoom;
    centerX = clamp(centerX, bounds.minX, bounds.maxX);
    centerY = clamp(centerY, bounds.minY, bounds.maxY);
    next[4] = width / 2 - centerX * zoom;
    next[5] = height / 2 - centerY * zoom;
    return next;
  }

  function simplifyPoints(points, tolerance = .8) {
    if (!Array.isArray(points) || points.length <= 2) return points || [];
    const sqTolerance = tolerance * tolerance;
    const radial = [points[0]];
    let previous = points[0];
    for (let i = 1; i < points.length; i++) {
      const point = points[i];
      const dx = point.x - previous.x;
      const dy = point.y - previous.y;
      if (dx * dx + dy * dy > sqTolerance) { radial.push(point); previous = point; }
    }
    if (previous !== points.at(-1)) radial.push(points.at(-1));
    return radial.slice(0, 5000);
  }

  class ActionHistory {
    constructor(maxActions = 100, maxBytes = 8 * 1024 * 1024) {
      this.maxActions = maxActions;
      this.maxBytes = maxBytes;
      this.undoStack = [];
      this.redoStack = [];
      this.bytes = 0;
    }
    sizeOf(action) {
      try { return new TextEncoder().encode(JSON.stringify(action)).length; }
      catch { return this.maxBytes + 1; }
    }
    push(action) {
      const size = this.sizeOf(action);
      if (size > this.maxBytes) return false;
      this.redoStack = [];
      this.undoStack.push({ action, size });
      this.bytes += size;
      while (this.undoStack.length > this.maxActions || this.bytes > this.maxBytes) {
        this.bytes -= this.undoStack.shift().size;
      }
      return true;
    }
    takeUndo() {
      const entry = this.undoStack.pop();
      if (entry) this.bytes -= entry.size;
      return entry;
    }
    commitUndo(entry) { if (entry) this.redoStack.push(entry); }
    rollbackUndo(entry) { if (entry) { this.undoStack.push(entry); this.bytes += entry.size; } }
    takeRedo() { return this.redoStack.pop(); }
    commitRedo(entry) { if (entry) { this.undoStack.push(entry); this.bytes += entry.size; } }
    rollbackRedo(entry) { if (entry) this.redoStack.push(entry); }
    clear() { this.undoStack = []; this.redoStack = []; this.bytes = 0; }
    canUndo() { return this.undoStack.length > 0; }
    canRedo() { return this.redoStack.length > 0; }
  }

  window.RepetHelperBoardCore = { clamp, finitePoint, clampViewport, simplifyPoints, ActionHistory };
})();
