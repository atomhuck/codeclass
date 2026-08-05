(() => {
  "use strict";

  const root = document.getElementById("whiteboard-app");
  const core = window.RepetHelperBoardCore;
  if (!root || !window.fabric || !core) return;

  const boardId = root.dataset.boardId;
  const isTeacher = root.dataset.userRole === "TEACHER";
  const userName = root.dataset.userName;
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content || "";
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || "X-CSRF-TOKEN";
  const shell = document.getElementById("canvas-shell");
  const remoteCursorLayer = document.getElementById("remote-cursors");
  const objectIndex = new Map();
  const remoteCursors = new Map();
  const remoteStrokes = new Map();
  const pendingOperations = new Map();
  const pendingDeletes = new Set();
  const history = new core.ActionHistory(100, 8 * 1024 * 1024);
  const cursorColors = ["#45D8FF", "#FFD66B", "#FF6B7A", "#A98CFF", "#64F5A6"];
  const BASE_BOUND = 50_000;
  const SAFE_COORDINATE = 900_000;

  let revision = 0;
  let socket = null;
  let selfSessionId = null;
  let reconnectAttempt = 0;
  let reconnectTimer = null;
  let boardDeleted = false;
  let connected = false;
  let snapshotLoading = false;
  let currentTool = "pencil";
  let previousTool = "pencil";
  let brushColor = "#4F46E5";
  let brushWidth = 4;
  let fontSize = 28;
  let spacePressed = false;
  let panning = false;
  let panLast = null;
  let panPointerId = null;
  let pendingTextPoint = null;
  let preservedTextSelection = null;
  const textSaveTimers = new WeakMap();
  let draftStrokeId = null;
  let draftPoints = [];
  let pendingPreviewPoints = [];
  let previewTimer = null;
  let lastCursorSentAt = 0;
  let gestureDistance = 0;
  let boardTaskQueue = Promise.resolve();
  let eraserDragging = false;
  let eraserLastPoint = null;
  let eraserObjects = new Map();
  let uploadInProgress = false;
  let relatedCursor = null;
  let relatedLoading = false;
  let workspaceBounds = { minX: -BASE_BOUND, maxX: BASE_BOUND, minY: -BASE_BOUND, maxY: BASE_BOUND };

  document.querySelectorAll(".mobile-colors").forEach(container => {
    document.querySelectorAll(".desktop-toolbar .color-swatch, .desktop-toolbar .custom-color")
      .forEach(item => container.append(item.cloneNode(true)));
  });

  const canvas = new fabric.Canvas("board-canvas", {
    selection: false,
    preserveObjectStacking: true,
    stopContextMenu: true,
    fireRightClick: true,
    enableRetinaScaling: true,
    skipOffscreen: true,
    selectionColor: "rgba(79,70,229,.12)",
    selectionBorderColor: "#4F46E5",
    selectionLineWidth: 1.5
  });
  canvas.targetFindTolerance = 14;
  canvas.freeDrawingBrush = new fabric.PencilBrush(canvas);
  canvas.freeDrawingBrush.color = brushColor;
  canvas.freeDrawingBrush.width = brushWidth;

  function uuid() {
    return crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  function showToast(message, success = false) {
    const toast = document.createElement("div");
    toast.className = `toast${success ? " success" : ""}`;
    toast.textContent = message;
    document.getElementById("toast-stack").append(toast);
    setTimeout(() => toast.remove(), 4200);
  }

  function queueBoardTask(task) {
    const execution = boardTaskQueue.then(task);
    boardTaskQueue = execution.catch(error => showToast(error?.message || "Не удалось применить изменение доски"));
    return execution;
  }

  function setConnected(value) {
    connected = value;
    root.classList.toggle("is-online", value);
    root.classList.toggle("is-offline", !value);
    document.querySelectorAll(".connection-label").forEach(item => item.textContent = value ? "В сети" : "Подключение…");
    document.querySelectorAll(".tool-button, .context-action, [data-clear-board]").forEach(button => button.disabled = !value);
    applyTool();
  }

  function updateHistoryButtons() {
    document.querySelectorAll("[data-undo]").forEach(button => button.disabled = !connected || !history.canUndo());
    document.querySelectorAll("[data-redo]").forEach(button => button.disabled = !connected || !history.canRedo());
  }

  function send(payload) {
    if (!connected || !socket || socket.readyState !== WebSocket.OPEN) return false;
    socket.send(JSON.stringify(payload));
    return true;
  }

  function runOperation(payload) {
    const operationId = payload.operationId || uuid();
    payload.operationId = operationId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pendingOperations.delete(operationId);
        reject(new Error("Сервер не подтвердил действие. Доска синхронизирована повторно."));
        queueBoardTask(loadSnapshot).catch(() => {});
      }, 10_000);
      pendingOperations.set(operationId, { resolve, reject, timer });
      if (!send(payload)) {
        clearTimeout(timer);
        pendingOperations.delete(operationId);
        reject(new Error("Дождитесь подключения к доске"));
      }
    });
  }

  function settleOperation(message, error = null) {
    if (!message.operationId) return;
    const pending = pendingOperations.get(message.operationId);
    if (!pending) return;
    clearTimeout(pending.timer);
    pendingOperations.delete(message.operationId);
    error ? pending.reject(error) : pending.resolve(message);
  }

  function connect() {
    clearTimeout(reconnectTimer);
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    socket = new WebSocket(`${protocol}//${location.host}/ws/boards/${boardId}`);
    socket.onopen = async () => {
      reconnectAttempt = 0;
      try { await queueBoardTask(loadSnapshot); setConnected(true); }
      catch (error) { showToast(error.message || "Не удалось синхронизировать доску"); socket.close(); }
    };
    socket.onmessage = event => {
      try { const message = JSON.parse(event.data); queueBoardTask(() => handleMessage(message)).catch(() => {}); }
      catch { showToast("Получено некорректное событие доски"); }
    };
    socket.onclose = () => {
      setConnected(false);
      finishInteraction();
      pendingOperations.forEach(pending => { clearTimeout(pending.timer); pending.reject(new Error("Соединение потеряно")); });
      pendingOperations.clear();
      remoteCursors.forEach(item => item.element.remove()); remoteCursors.clear(); clearRemoteStrokes();
      if (boardDeleted) return;
      reconnectTimer = setTimeout(connect, Math.min(15_000, 500 * (2 ** reconnectAttempt++)));
    };
    socket.onerror = () => socket.close();
  }

  async function loadSnapshot() {
    if (snapshotLoading) return;
    snapshotLoading = true;
    try {
      const response = await fetch(`/api/boards/${boardId}/snapshot`, { headers: { Accept: "application/json" }, cache: "no-store" });
      if (!response.ok) throw new Error(response.status === 403 ? "Нет доступа к этой доске" : "Не удалось загрузить доску");
      const snapshot = await response.json();
      canvas.discardActiveObject();
      canvas.getObjects().slice().forEach(object => canvas.remove(object));
      objectIndex.clear(); pendingDeletes.clear();
      workspaceBounds = { minX: -BASE_BOUND, maxX: BASE_BOUND, minY: -BASE_BOUND, maxY: BASE_BOUND };
      for (const item of snapshot.objects) await addOrReplaceObject(item);
      revision = snapshot.revision;
      setBoardName(snapshot.displayName || root.dataset.boardName);
      extendWorkspaceForObjects();
      clampCurrentViewport();
      canvas.requestRenderAll();
    } finally { snapshotLoading = false; }
  }

  function commonObjectOptions(type) {
    const selectable = connected && currentTool === "select";
    return {
      selectable,
      evented: currentTool === "eraser" || selectable,
      perPixelTargetFind: type === "PATH",
      objectCaching: type !== "IMAGE",
      cornerColor: "#7C82FF",
      cornerStrokeColor: "#171A2B",
      borderColor: "#4F46E5",
      transparentCorners: false,
      lockUniScaling: true
    };
  }

  function restrictControls(object) {
    if (object.__boardType === "IMAGE") {
      object.lockRotation = false;
      object.lockScalingX = false; object.lockScalingY = false;
      object.setControlsVisibility?.({ mt: false, mb: false, ml: false, mr: false });
    } else {
      object.lockRotation = true;
      object.lockScalingX = true; object.lockScalingY = true;
      object.setControlsVisibility?.({ mt: false, mb: false, ml: false, mr: false, tl: false, tr: false, bl: false, br: false, mtr: false });
    }
  }

  async function addOrReplaceObject(item) {
    let existing = objectIndex.get(item.id);
    if (existing && existing.__boardType === item.type) {
      if (item.type === "TEXT") existing.set({ ...item.data, fontFamily: "Onest" });
      else existing.set(item.data);
      existing.set(commonObjectOptions(item.type));
      existing.__boardVersion = Number(item.version) || 0;
      existing.opacity = 1; existing.visible = true;
      restrictControls(existing); existing.setCoords();
      removeRemoteStrokeByObjectId(item.id);
      return existing;
    }
    if (existing) { canvas.remove(existing); objectIndex.delete(item.id); }
    let object;
    if (item.type === "PATH") {
      const { type: ignored, ...options } = item.data;
      object = new fabric.Path(item.data.path, { ...options, ...commonObjectOptions("PATH") });
    } else if (item.type === "TEXT") {
      object = new fabric.IText(item.data.text || "", { ...item.data, fontFamily: "Onest", ...commonObjectOptions("TEXT") });
    } else {
      const ImageClass = fabric.FabricImage || fabric.Image;
      object = await ImageClass.fromURL(item.imageUrl, { crossOrigin: "use-credentials" });
      object.set({ ...item.data, ...commonObjectOptions("IMAGE") });
    }
    object.__boardId = item.id;
    object.__boardType = item.type;
    object.__boardVersion = Number(item.version) || 0;
    restrictControls(object);
    objectIndex.set(item.id, object);
    canvas.add(object);
    canvas.moveObjectTo?.(object, canvas.getObjects().length - 1);
    removeRemoteStrokeByObjectId(item.id);
    return object;
  }

  function removeObject(id) {
    const object = objectIndex.get(id);
    if (object) canvas.remove(object);
    objectIndex.delete(id); pendingDeletes.delete(id);
  }

  async function applyRevision(message, mutation) {
    const next = Number(message.revision);
    if (!Number.isFinite(next)) { await mutation(); return; }
    if (next > revision + 1) { await loadSnapshot(); return; }
    if (next <= revision) return;
    await mutation(); revision = next; canvas.requestRenderAll();
  }

  async function handleMessage(message) {
    if (message.type === "operation.rejected" || message.type === "error") {
      const error = new Error(message.message || "Действие отклонено сервером");
      error.code = message.code;
      settleOperation(message, error);
      if (message.code === "COORDINATES_OUT_OF_RANGE") recoverCoordinates();
      showToast(error.message);
      return;
    }
    if (message.type === "sync.required") {
      const error = new Error("Объект уже изменён другим участником"); error.code = message.code;
      settleOperation(message, error); await loadSnapshot(); showToast(error.message); return;
    }
    switch (message.type) {
      case "presence.self": selfSessionId = message.sessionId; updateParticipants(message.participants); break;
      case "presence.join":
      case "presence.leave": updateParticipants(message.participants); if (message.type === "presence.leave") removeRemoteCursor(message.sessionId); break;
      case "cursor.move": if (message.sessionId !== selfSessionId) updateRemoteCursor(message); break;
      case "stroke.begin": if (message.sessionId !== selfSessionId) startRemoteStroke(message); break;
      case "stroke.points": if (message.sessionId !== selfSessionId) extendRemoteStroke(message); break;
      case "object.created":
      case "object.updated": await applyRevision(message, () => addOrReplaceObject(message.object)); break;
      case "objects.updated":
      case "objects.restored": await applyRevision(message, async () => { for (const item of message.objects || []) await addOrReplaceObject(item); }); break;
      case "object.deleted": await applyRevision(message, () => removeObject(message.objectId)); break;
      case "objects.deleted": await applyRevision(message, () => (message.objects || []).forEach(item => removeObject(item.id))); break;
      case "board.cleared": await applyRevision(message, () => { canvas.discardActiveObject(); canvas.getObjects().slice().forEach(item => canvas.remove(item)); objectIndex.clear(); history.clear(); updateHistoryButtons(); }); break;
      case "board.renamed": if (message.board?.displayName) setBoardName(message.board.displayName); break;
      case "board.deleted": boardDeleted = true; setConnected(false); showToast("Доска удалена преподавателем"); break;
    }
    if (message.actorSessionId === selfSessionId || pendingOperations.has(message.operationId)) settleOperation(message);
  }

  function updateParticipants(value) { document.querySelectorAll(".participant-count").forEach(item => item.textContent = value ?? 1); }
  function setBoardName(name) { root.dataset.boardName = name; document.querySelectorAll("#board-title-text,.current-board-name").forEach(item => item.textContent = name); document.title = `${name} — RepetHelper`; }

  function applyTool() {
    const effective = spacePressed ? "hand" : currentTool;
    canvas.isDrawingMode = connected && effective === "pencil";
    canvas.selection = connected && effective === "select";
    canvas.skipTargetFind = effective === "pencil" || effective === "hand" || effective === "text";
    canvas.targetFindTolerance = effective === "eraser" ? 16 : 6;
    canvas.defaultCursor = effective === "hand" ? "grab" : effective === "eraser" ? "crosshair" : effective === "text" ? "text" : "default";
    canvas.hoverCursor = effective === "eraser" ? "crosshair" : effective === "select" ? "move" : canvas.defaultCursor;
    canvas.getObjects().forEach(object => {
      object.selectable = connected && effective === "select";
      object.evented = effective === "eraser" || (effective === "select");
      restrictControls(object);
    });
    if (effective !== "select") canvas.discardActiveObject();
    document.querySelectorAll("[data-tool]").forEach(button => button.classList.toggle("active", button.dataset.tool === currentTool));
    document.querySelectorAll("[data-context]").forEach(panel => panel.hidden = panel.dataset.context !== currentTool);
    document.querySelectorAll(".desktop-toolbar .size-control:not(.text-size-control)").forEach(item => item.hidden = currentTool === "text");
    refreshTextSizeControls();
    canvas.requestRenderAll(); updateHistoryButtons();
  }

  function selectTool(tool) { currentTool = tool; applyTool(); closeMobileMore(); }
  document.querySelectorAll("[data-tool]").forEach(button => button.addEventListener("click", () => selectTool(button.dataset.tool)));

  document.querySelectorAll("[data-brush-size]").forEach(input => input.addEventListener("input", event => {
    brushWidth = Number(event.target.value);
    document.querySelectorAll("[data-brush-size]").forEach(item => item.value = brushWidth);
    document.querySelectorAll("[data-brush-size-value]").forEach(item => item.textContent = `${brushWidth} px`);
    canvas.freeDrawingBrush.width = brushWidth;
  }));
  document.querySelectorAll("[data-font-size]").forEach(input => {
    input.addEventListener("pointerdown", preserveCurrentTextSelection);
    input.addEventListener("input", event => previewSelectedTextSize(Number(event.target.value)));
    input.addEventListener("change", commitSelectedTextSize);
    input.addEventListener("pointercancel", commitSelectedTextSize);
    input.addEventListener("blur", commitSelectedTextSize);
  });

  function textObjectsInSelection() {
    return activeObjects().filter(object => object?.__boardType === "TEXT" || object?.__draftText);
  }

  function preserveCurrentTextSelection() {
    const text = textObjectsInSelection()[0];
    if (!text) return;
    clearScheduledTextSave(text);
    text.__fontControlActive = true;
    if (!text.__textBefore && text.__boardId) text.__textBefore = serializeObject(text);
    preservedTextSelection = {
      object: text,
      start: Number(text.selectionStart) || 0,
      end: Number(text.selectionEnd) || 0,
      before: text.__textBefore || serializeObject(text)
    };
  }

  function syncFontSizeInputs(size) {
    document.querySelectorAll("[data-font-size]").forEach(item => item.value = size);
    document.querySelectorAll("[data-font-size-value]").forEach(item => item.textContent = `${size} px`);
  }

  function previewSelectedTextSize(size) {
    if (!Number.isFinite(size)) return;
    fontSize = Math.max(12, Math.min(144, size));
    syncFontSizeInputs(fontSize);
    const selected = textObjectsInSelection();
    const targets = selected.length ? selected : preservedTextSelection?.object ? [preservedTextSelection.object] : [];
    targets.forEach(text => {
      if (!text.__textBefore && text.__boardId) text.__textBefore = serializeObject(text);
      const remembered = preservedTextSelection?.object === text ? preservedTextSelection : null;
      const start = text.isEditing ? Number(text.selectionStart) || 0 : remembered?.start ?? 0;
      const end = text.isEditing ? Number(text.selectionEnd) || 0 : remembered?.end ?? 0;
      if (start < end && typeof text.setSelectionStyles === "function") {
        text.setSelectionStyles({ fontSize }, start, end);
      } else if (text.isEditing && typeof text.setSelectionStyles === "function") {
        text.setSelectionStyles({ fontSize });
      } else {
        text.set({ fontSize, styles: withoutFontSizeStyles(text.styles) });
      }
      text.__fontSizeDirty = true;
      text.setCoords();
    });
    canvas.requestRenderAll();
  }

  async function commitSelectedTextSize() {
    const remembered = preservedTextSelection;
    const selected = textObjectsInSelection();
    const targets = selected.length ? selected : remembered?.object ? [remembered.object] : [];
    preservedTextSelection = null;
    for (const text of targets) {
      text.__fontControlActive = false;
      if (!text.__fontSizeDirty) continue;
      text.__fontSizeDirty = false;
      if (text.isEditing || text.__draftText) continue;
      clearScheduledTextSave(text);
      await updateExistingTextSize(text, text.__textBefore || (remembered?.object === text ? remembered.before : serializeObject(text)));
      text.__textBefore = null;
    }
    refreshTextSizeControls();
  }

  function withoutFontSizeStyles(styles) {
    const result = {};
    Object.entries(styles || {}).forEach(([line, characters]) => {
      const lineResult = {};
      Object.entries(characters || {}).forEach(([character, style]) => {
        const next = { ...(style || {}) };
        delete next.fontSize;
        if (Object.keys(next).length) lineResult[character] = next;
      });
      if (Object.keys(lineResult).length) result[line] = lineResult;
    });
    return result;
  }

  function refreshTextSizeControls() {
    const hasSelectedText = textObjectsInSelection().length > 0;
    document.querySelectorAll(".desktop-toolbar .text-size-control")
      .forEach(item => item.hidden = currentTool !== "text" && !hasSelectedText);
    document.querySelectorAll("[data-selected-text-size]").forEach(item => item.hidden = !hasSelectedText);
    document.querySelectorAll('[data-context="select"] .upload-button').forEach(item => item.hidden = hasSelectedText);
  }

  document.querySelectorAll("[data-color]").forEach(button => button.addEventListener("click", () => setColor(button.dataset.color)));
  document.querySelectorAll("[data-color-input]").forEach(input => input.addEventListener("input", event => setColor(event.target.value)));
  function setColor(color) {
    brushColor = color.toUpperCase(); canvas.freeDrawingBrush.color = brushColor;
    document.querySelectorAll("[data-color]").forEach(button => button.classList.toggle("active", button.dataset.color.toUpperCase() === brushColor));
    document.querySelectorAll("[data-color-input]").forEach(input => input.value = brushColor);
  }

  function scenePoint(event) { return canvas.getScenePoint ? canvas.getScenePoint(event) : canvas.getPointer(event); }
  function safeScenePoint(event) {
    const point = scenePoint(event);
    if (!core.finitePoint(point, SAFE_COORDINATE)) { recoverCoordinates(); return null; }
    return point;
  }

  function recoverCoordinates() {
    draftStrokeId = null; draftPoints = []; pendingPreviewPoints = []; clearTimeout(previewTimer); previewTimer = null;
    eraserDragging = false; eraserLastPoint = null; restorePendingEraser(); panning = false; panLast = null; panPointerId = null; pendingTextPoint = null;
    clampCurrentViewport(); applyTool();
  }

  function clampCurrentViewport() {
    const next = core.clampViewport(canvas.viewportTransform || [1,0,0,1,0,0], canvas.getWidth(), canvas.getHeight(), workspaceBounds);
    canvas.setViewportTransform(next); updateGrid(); renderRemoteCursors();
  }
  function updateGrid() {
    const transform = canvas.viewportTransform || [1,0,0,1,0,0];
    const size = Math.max(8, 24 * transform[0]);
    shell.style.backgroundSize = `${size}px ${size}px`;
    shell.style.backgroundPosition = `${transform[4] % size}px ${transform[5] % size}px`;
  }
  function extendWorkspaceForObjects() {
    objectIndex.forEach(object => {
      const rect = object.getBoundingRect();
      workspaceBounds.minX = Math.max(-SAFE_COORDINATE, Math.min(workspaceBounds.minX, rect.left - 1000));
      workspaceBounds.minY = Math.max(-SAFE_COORDINATE, Math.min(workspaceBounds.minY, rect.top - 1000));
      workspaceBounds.maxX = Math.min(SAFE_COORDINATE, Math.max(workspaceBounds.maxX, rect.left + rect.width + 1000));
      workspaceBounds.maxY = Math.min(SAFE_COORDINATE, Math.max(workspaceBounds.maxY, rect.top + rect.height + 1000));
    });
  }

  function resizeCanvas() { canvas.setDimensions({ width: shell.clientWidth, height: shell.clientHeight }); clampCurrentViewport(); canvas.requestRenderAll(); }
  new ResizeObserver(resizeCanvas).observe(shell); resizeCanvas();

  canvas.on("mouse:down", opt => {
    if (!connected) return;
    const event = opt.e;
    const hand = currentTool === "hand" || spacePressed || event.button === 1;
    if (hand) { startPan(event); return; }
    if (currentTool === "text") { pendingTextPoint = safeScenePoint(event); return; }
    if (currentTool === "eraser") { eraserDragging = true; eraserLastPoint = safeScenePoint(event); collectEraserHits(eraserLastPoint, eraserLastPoint); return; }
    if (currentTool === "pencil") {
      const point = safeScenePoint(event); if (!point) return;
      draftStrokeId = uuid(); draftPoints = [{ x: point.x, y: point.y }]; pendingPreviewPoints = [];
      send({ type: "stroke.begin", strokeId: draftStrokeId, color: brushColor, width: brushWidth, point });
    }
  });

  canvas.on("mouse:move", opt => {
    const event = opt.e;
    if (panning) { movePan(event); return; }
    const point = safeScenePoint(event); if (!point) return;
    if (connected && currentTool === "eraser" && eraserDragging) { collectEraserHits(eraserLastPoint, point); eraserLastPoint = point; }
    const now = performance.now();
    if (connected && now - lastCursorSentAt >= 50) { lastCursorSentAt = now; send({ type: "cursor.move", x: point.x, y: point.y }); }
    if (connected && currentTool === "pencil" && draftStrokeId && event.buttons === 1) {
      const last = draftPoints.at(-1);
      if (!last || Math.hypot(point.x-last.x,point.y-last.y) >= .8 / canvas.getZoom()) { draftPoints.push({x:point.x,y:point.y}); pendingPreviewPoints.push({x:point.x,y:point.y}); schedulePreviewSend(); }
    }
  });
  canvas.on("mouse:up", () => {
    const textPoint = pendingTextPoint;
    pendingTextPoint = null;
    finishInteraction();
    if (textPoint && currentTool === "text" && !spacePressed) createTextDraft(textPoint);
  });

  function clientPoint(event) {
    const source = event?.touches?.[0] || event?.changedTouches?.[0] || event;
    const x = Number(source?.clientX), y = Number(source?.clientY);
    return Number.isFinite(x) && Number.isFinite(y) ? { x, y } : null;
  }

  function startPan(event) {
    const point = clientPoint(event);
    if (!point) return recoverCoordinates();
    pendingTextPoint = null;
    panning = true;
    panLast = point;
    panPointerId = Number.isFinite(event?.pointerId) ? event.pointerId : null;
    canvas.isDrawingMode = false;
    canvas.defaultCursor = "grabbing";
    if (panPointerId != null) canvas.upperCanvasEl?.setPointerCapture?.(panPointerId);
    event.preventDefault?.();
  }

  function movePan(event) {
    if (!panning || !panLast) return false;
    if (panPointerId != null && event?.pointerId != null && event.pointerId !== panPointerId) return false;
    const point = clientPoint(event);
    if (!point) { recoverCoordinates(); return false; }
    const dx = point.x - panLast.x, dy = point.y - panLast.y;
    if (!Number.isFinite(dx) || !Number.isFinite(dy)) { recoverCoordinates(); return false; }
    if (dx || dy) {
      const viewport = [...canvas.viewportTransform];
      viewport[4] += dx; viewport[5] += dy;
      panLast = point;
      canvas.setViewportTransform(core.clampViewport(viewport, canvas.getWidth(), canvas.getHeight(), workspaceBounds));
      updateGrid(); canvas.requestRenderAll(); renderRemoteCursors();
    }
    event.preventDefault?.();
    return true;
  }

  canvas.upperCanvasEl?.addEventListener("pointermove", event => { if (panning) movePan(event); }, { passive: false });
  window.addEventListener("pointerup", event => {
    if (panning && (panPointerId == null || event.pointerId === panPointerId)) finishInteraction();
  });

  function finishInteraction() {
    if (eraserDragging) commitEraser();
    eraserDragging = false; eraserLastPoint = null;
    if (panning) {
      if (panPointerId != null && canvas.upperCanvasEl?.hasPointerCapture?.(panPointerId)) canvas.upperCanvasEl.releasePointerCapture(panPointerId);
      panning = false; panLast = null; panPointerId = null; applyTool();
    }
    flushPreview();
  }

  canvas.on("path:created", opt => {
    const path = opt.path;
    if (!connected || !draftStrokeId) { canvas.remove(path); return; }
    const id = draftStrokeId;
    path.set({ selectable:false,evented:true,objectCaching:true,perPixelTargetFind:true });
    path.__boardId=id; path.__boardType="PATH"; path.__boardVersion=0; objectIndex.set(id,path);
    const data=path.toObject(["path","stroke","strokeWidth","strokeLineCap","strokeLineJoin","left","top","scaleX","scaleY","angle","width","height"]); delete data.type;
    data.stroke=brushColor; data.strokeWidth=brushWidth;
    draftStrokeId=null; draftPoints=[]; pendingPreviewPoints=[];
    runOperation({type:"stroke.commit",operationId:id,objectId:id,data}).then(message => {
      history.push({kind:"visibility",active:true,objects:[versionedView(message.object)]}); updateHistoryButtons();
    }).catch(() => { removeObject(id); queueBoardTask(loadSnapshot); });
  });

  function schedulePreviewSend(){if(!previewTimer)previewTimer=setTimeout(flushPreview,35)}
  function flushPreview(){clearTimeout(previewTimer);previewTimer=null;if(!draftStrokeId||!pendingPreviewPoints.length)return;const points=core.simplifyPoints(pendingPreviewPoints.splice(0,50),.35);if(points.length)send({type:"stroke.points",strokeId:draftStrokeId,points});if(pendingPreviewPoints.length)schedulePreviewSend()}

  function createTextDraft(point) {
    const editing = canvas.getActiveObject();
    if (editing?.isEditing) return;
    const text = new fabric.IText("", { left:point.x,top:point.y,fontFamily:"Onest",fontSize,fill:brushColor,...commonObjectOptions("TEXT") });
    text.__boardType="TEXT"; text.__draftText=true; canvas.add(text); canvas.setActiveObject(text); text.enterEditing(); text.hiddenTextarea?.focus({ preventScroll:true }); canvas.requestRenderAll();
    setTimeout(() => { if (text.isEditing) text.hiddenTextarea?.focus({ preventScroll:true }); }, 80);
  }
  canvas.on("text:editing:entered", opt => {
    const text = opt.target;
    if (!text) return;
    clearScheduledTextSave(text);
    if (text.__boardId && !text.__textBefore) text.__textBefore=serializeObject(text);
    refreshTextSizeControls();
  });
  canvas.on("text:editing:exited", opt => scheduleTextSave(opt.target));

  function clearScheduledTextSave(text) {
    const timer = textSaveTimers.get(text);
    if (timer) clearTimeout(timer);
    textSaveTimers.delete(text);
  }

  function scheduleTextSave(text, delay = 260) {
    if (!text) return;
    clearScheduledTextSave(text);
    const timer = setTimeout(() => {
      textSaveTimers.delete(text);
      if (text.isEditing) return;
      if (text.__fontControlActive) return scheduleTextSave(text, 180);
      saveText(text);
    }, delay);
    textSaveTimers.set(text, timer);
  }

  async function saveText(text) {
    if (!text || text.__savingText) return;
    if (text.__fontControlActive) return scheduleTextSave(text, 180);
    text.__savingText=true;
    try {
      if (!text.text?.trim()) { if (text.__draftText) canvas.remove(text); return; }
      if (text.text.length > 2000 || text.text.split(/\r?\n/).length > 50) { showToast("Текст слишком длинный"); if(text.__draftText)canvas.remove(text); else text.set(text.__textBefore); return; }
      const data=serializeObject(text);
      if(text.__draftText){const id=uuid();text.__boardId=id;text.__boardVersion=0;text.__draftText=false;objectIndex.set(id,text);const message=await runOperation({type:"text.commit",objectId:id,data});history.push({kind:"visibility",active:true,objects:[versionedView(message.object)]});}
      else{const before=text.__textBefore||serializeObject(text);const message=await runOperation({type:"object.update",objectId:text.__boardId,expectedVersion:text.__boardVersion,data});history.push({kind:"update",objectId:text.__boardId,before,after:data,version:message.object.version});}
      updateHistoryButtons();
    }catch(error){showToast(error.message);await loadSnapshot();}finally{text.__savingText=false;text.__textBefore=null;if(!text.isEditing)applyTool()}
  }

  async function updateExistingTextSize(object,before){if(object.__savingText||!object.__boardId)return;const after=serializeObject(object);if(JSON.stringify(before)===JSON.stringify(after))return;try{const message=await runOperation({type:"object.update",objectId:object.__boardId,expectedVersion:object.__boardVersion,data:after});history.push({kind:"update",objectId:object.__boardId,before,after,version:message.object.version});updateHistoryButtons()}catch(error){showToast(error.message);await loadSnapshot()}}

  canvas.on("before:transform", opt => {
    const target=opt.transform?.target||opt.target;if(!target)return;
    target.__transformBefore={left:target.left,top:target.top,data:target.__boardId?serializeObject(target):null,objects:activeObjects(target).map(versionedObject)};
  });
  canvas.on("selection:created", opt => configureSelection(opt.selected));
  canvas.on("selection:updated", opt => configureSelection(opt.selected));
  canvas.on("selection:cleared", refreshTextSizeControls);
  function configureSelection(selected){const list=selected||[];if(list.length>1){const active=canvas.getActiveObject();if(active){active.lockScalingX=true;active.lockScalingY=true;active.lockRotation=true;active.setControlsVisibility?.({mt:false,mb:false,ml:false,mr:false,tl:false,tr:false,bl:false,br:false,mtr:false})}}const text=list.find(item=>item.__boardType==="TEXT");if(text){const size=Math.round(Number(text.fontSize)||fontSize);fontSize=size;syncFontSizeInputs(size)}refreshTextSizeControls()}
  function activeObjects(target=canvas.getActiveObject()){return target?.getObjects?target.getObjects():target?[target]:[]}
  function versionedObject(object){return{id:object.__boardId,expectedVersion:Number(object.__boardVersion)||0}}
  function versionedView(item){return{id:item.id,expectedVersion:Number(item.version)||0}}

  canvas.on("object:modified", async opt => {
    const target=opt.target;if(!connected||!target||target.__savingText)return;
    const start=target.__transformBefore;if(!start)return;target.__transformBefore=null;
    if(target.getObjects||target.__boardType!=="IMAGE"){
      const dx=(target.left??0)-(start.left??0),dy=(target.top??0)-(start.top??0);if(Math.abs(dx)<.0001&&Math.abs(dy)<.0001)return;
      const requested=start.objects.filter(item=>item.id);canvas.discardActiveObject();
      try{const message=await runOperation({type:"objects.move",objects:requested,deltaX:dx,deltaY:dy});history.push({kind:"move",objects:(message.objects||[]).map(versionedView),deltaX:dx,deltaY:dy});updateHistoryButtons()}catch(error){showToast(error.message);await loadSnapshot()}
    }else{
      const after=serializeObject(target);try{const message=await runOperation({type:"object.update",objectId:target.__boardId,expectedVersion:target.__boardVersion,data:after});history.push({kind:"update",objectId:target.__boardId,before:start.data,after,version:message.object.version});updateHistoryButtons()}catch(error){showToast(error.message);await loadSnapshot()}
    }
  });

  function serializeObject(object){
    if(object.__boardType==="TEXT"||object.__draftText)return{text:object.text,left:object.left,top:object.top,fontSize:object.fontSize,fill:String(object.fill||brushColor).toUpperCase(),styles:serializableTextStyles(object.styles)};
    if(object.__boardType==="IMAGE")return{left:object.left,top:object.top,width:object.width,height:object.height,scaleX:object.scaleX,scaleY:object.scaleY,angle:object.angle||0};
    const data=object.toObject(["path","stroke","strokeWidth","strokeLineCap","strokeLineJoin","left","top","scaleX","scaleY","angle","width","height"]);delete data.type;return data;
  }

  function serializableTextStyles(styles){
    const result={};
    Object.entries(styles||{}).forEach(([line,characters])=>{
      const lineResult={};
      Object.entries(characters||{}).forEach(([character,style])=>{
        const size=Number(style?.fontSize);
        if(Number.isFinite(size)&&size>=12&&size<=144)lineResult[character]={fontSize:size};
      });
      if(Object.keys(lineResult).length)result[line]=lineResult;
    });
    return result;
  }

  function collectEraserHits(from,to){if(!from||!to)return;const zoom=canvas.getZoom();const distance=Math.hypot(to.x-from.x,to.y-from.y);const steps=Math.max(1,Math.ceil(distance/Math.max(3/zoom,2)));for(let step=0;step<=steps;step++){const ratio=step/steps;const point=new fabric.Point(from.x+(to.x-from.x)*ratio,from.y+(to.y-from.y)*ratio);objectIndex.forEach(object=>{if(eraserObjects.has(object.__boardId)||!object.visible)return;const rect=object.getBoundingRect();const radius=14/zoom;if(point.x>=rect.left-radius&&point.x<=rect.left+rect.width+radius&&point.y>=rect.top-radius&&point.y<=rect.top+rect.height+radius){eraserObjects.set(object.__boardId,{object,opacity:object.opacity});object.set({opacity:.16,evented:false});}})}canvas.requestRenderAll()}
  function restorePendingEraser(){eraserObjects.forEach(({object,opacity})=>object.set({opacity:opacity??1,visible:true}));eraserObjects.clear();canvas.requestRenderAll()}
  async function commitEraser(){if(!eraserObjects.size)return;const ids=[...eraserObjects.keys()];eraserObjects.clear();await deleteIds(ids,true)}

  async function deleteSelection(){const objects=activeObjects().filter(item=>item.__boardId);if(!objects.length)return showToast("Сначала выделите объект");if(objects.length>500)return showToast("За один раз можно удалить не более 500 объектов");canvas.discardActiveObject();await deleteIds(objects.map(item=>item.__boardId),true)}
  async function deleteIds(ids,record){const selected=ids.map(id=>objectIndex.get(id)).filter(Boolean);const operationId=uuid();selected.forEach(object=>{pendingDeletes.add(object.__boardId);object.set({opacity:.16,evented:false})});canvas.requestRenderAll();try{const message=await runOperation({type:"objects.delete",operationId,objectIds:ids});const views=(message.objects||[]).map(versionedView);if(record&&views.length){history.push({kind:"visibility",active:false,objects:views,deleteOperationId:operationId});updateHistoryButtons()}}catch(error){selected.forEach(object=>{object.set({opacity:1,evented:true});pendingDeletes.delete(object.__boardId)});canvas.requestRenderAll();showToast(error.message);if(error.code!=="UNDO_EXPIRED")await loadSnapshot()}}
  document.querySelectorAll("[data-delete-selection]").forEach(button=>button.addEventListener("click",deleteSelection));

  async function setVisibility(action,wantActive){if(wantActive){const message=await runOperation({type:"objects.restore",deleteOperationId:action.deleteOperationId,objects:action.objects});action.objects=(message.objects||[]).map(versionedView);action.active=true}else{const operationId=uuid();const message=await runOperation({type:"objects.delete",operationId,objectIds:action.objects.map(item=>item.id)});action.objects=(message.objects||[]).map(versionedView);action.deleteOperationId=operationId;action.active=false}}
  async function executeHistoryAction(action,forward){
    if(action.kind==="visibility")return setVisibility(action,forward?action.initialActive:!action.initialActive);
    if(action.kind==="move"){const multiplier=forward?1:-1;const message=await runOperation({type:"objects.move",objects:action.objects,deltaX:action.deltaX*multiplier,deltaY:action.deltaY*multiplier});action.objects=(message.objects||[]).map(versionedView);return}
    if(action.kind==="update"){const data=forward?action.after:action.before;const message=await runOperation({type:"object.update",objectId:action.objectId,expectedVersion:action.version,data});action.version=message.object.version}
  }
  function normalizeHistoryAction(action){if(action.kind==="visibility"&&action.initialActive===undefined)action.initialActive=action.active;return action}
  const originalPush=history.push.bind(history);history.push=action=>originalPush(normalizeHistoryAction(action));
  async function undo(){const entry=history.takeUndo();if(!entry)return;updateHistoryButtons();try{await executeHistoryAction(entry.action,false);history.commitUndo(entry)}catch(error){history.rollbackUndo(entry);showToast(error.message);if(error.code!=="UNDO_EXPIRED")await loadSnapshot()}updateHistoryButtons()}
  async function redo(){const entry=history.takeRedo();if(!entry)return;updateHistoryButtons();try{await executeHistoryAction(entry.action,true);history.commitRedo(entry)}catch(error){history.rollbackRedo(entry);showToast(error.message);if(error.code!=="UNDO_EXPIRED")await loadSnapshot()}updateHistoryButtons()}
  document.querySelectorAll("[data-undo]").forEach(button=>button.addEventListener("click",undo));document.querySelectorAll("[data-redo]").forEach(button=>button.addEventListener("click",redo));

  function setZoom(next,point=new fabric.Point(canvas.getWidth()/2,canvas.getHeight()/2)){const zoom=core.clamp(next,.1,8);canvas.zoomToPoint(point,zoom);clampCurrentViewport();document.querySelectorAll(".zoom-value").forEach(item=>item.textContent=`${Math.round(zoom*100)}%`);canvas.requestRenderAll()}
  function centerBoard(){canvas.setViewportTransform([1,0,0,1,0,0]);document.querySelectorAll(".zoom-value").forEach(item=>item.textContent="100%");updateGrid();canvas.requestRenderAll();renderRemoteCursors()}
  document.querySelectorAll("[data-zoom-in]").forEach(button=>button.addEventListener("click",()=>setZoom(canvas.getZoom()*1.2)));document.querySelectorAll("[data-zoom-out]").forEach(button=>button.addEventListener("click",()=>setZoom(canvas.getZoom()/1.2)));document.querySelectorAll("[data-zoom-reset],[data-center-board]").forEach(button=>button.addEventListener("click",centerBoard));
  canvas.on("mouse:wheel",opt=>{const event=opt.e;const factor=event.deltaMode===1?16:event.deltaMode===2?canvas.getHeight():1;const dx=event.deltaX*factor,dy=event.deltaY*factor;if(event.ctrlKey||event.metaKey)setZoom(canvas.getZoom()*(.999**dy),new fabric.Point(event.offsetX,event.offsetY));else{const viewport=[...canvas.viewportTransform];viewport[4]-=dx;viewport[5]-=dy;canvas.setViewportTransform(core.clampViewport(viewport,canvas.getWidth(),canvas.getHeight(),workspaceBounds));updateGrid();canvas.requestRenderAll();renderRemoteCursors()}event.preventDefault();event.stopPropagation()});

  shell.addEventListener("touchstart",event=>{if(event.touches.length===2){gestureDistance=Math.hypot(event.touches[0].clientX-event.touches[1].clientX,event.touches[0].clientY-event.touches[1].clientY);canvas.isDrawingMode=false}}, {passive:false});
  shell.addEventListener("touchmove",event=>{if(event.touches.length!==2||!gestureDistance)return;const distance=Math.hypot(event.touches[0].clientX-event.touches[1].clientX,event.touches[0].clientY-event.touches[1].clientY);const rect=shell.getBoundingClientRect();const center=new fabric.Point((event.touches[0].clientX+event.touches[1].clientX)/2-rect.left,(event.touches[0].clientY+event.touches[1].clientY)/2-rect.top);setZoom(canvas.getZoom()*distance/gestureDistance,center);gestureDistance=distance;event.preventDefault()}, {passive:false});
  shell.addEventListener("touchend",()=>{gestureDistance=0;applyTool()},{passive:true});shell.addEventListener("pointercancel",finishInteraction);window.addEventListener("blur",()=>{spacePressed=false;finishInteraction()});document.addEventListener("visibilitychange",()=>{if(document.hidden)finishInteraction()});

  async function uploadImage(file){if(!file||!connected||uploadInProgress)return;if(!["image/jpeg","image/png"].includes(file.type))return showToast("Разрешены только JPEG и PNG");if(file.size>10*1024*1024)return showToast("Изображение превышает 10 МБ");uploadInProgress=true;const center=fabric.util.transformPoint(new fabric.Point(shell.clientWidth/2,shell.clientHeight/2),fabric.util.invertTransform(canvas.viewportTransform));if(!core.finitePoint(center,SAFE_COORDINATE)){uploadInProgress=false;recoverCoordinates();return showToast("Вернитесь ближе к центру доски")};const operationId=uuid();const data=new FormData();data.append("file",file);data.append("left",String(center.x));data.append("top",String(center.y));data.append("operationId",operationId);try{const response=await fetch(`/api/boards/${boardId}/images`,{method:"POST",headers:{[csrfHeader]:csrfToken},body:data});const result=await response.json().catch(()=>({}));if(!response.ok)throw new Error(result.error||"Не удалось загрузить изображение");await queueBoardTask(()=>handleMessage({type:"object.created",revision:result.revision,object:result.object}));history.push({kind:"visibility",active:true,objects:[versionedView(result.object)]});updateHistoryButtons();selectTool("select");const inserted=objectIndex.get(result.object.id);if(inserted){canvas.setActiveObject(inserted);canvas.requestRenderAll()}}catch(error){showToast(error.message)}finally{uploadInProgress=false}}
  document.querySelectorAll(".image-upload").forEach(input=>input.addEventListener("change",async()=>{const file=input.files?.[0];input.value="";await uploadImage(file)}));
  document.addEventListener("paste",async event=>{if(canvas.getActiveObject()?.isEditing)return;const item=Array.from(event.clipboardData?.items||[]).find(value=>value.type.startsWith("image/"));if(!item)return;event.preventDefault();const file=item.getAsFile();if(file)await uploadImage(file)});

  function startRemoteStroke(message){if(!core.finitePoint(message.point,1_000_000))return;const key=`${message.sessionId}:${message.strokeId}`;const stroke=new fabric.Polyline([message.point],{fill:null,stroke:message.color||"#4F46E5",strokeWidth:message.width||4,strokeLineCap:"round",strokeLineJoin:"round",selectable:false,evented:false,opacity:.72,objectCaching:false});stroke.__previewObjectId=message.strokeId;remoteStrokes.set(key,stroke);canvas.add(stroke)}
  function extendRemoteStroke(message){const stroke=remoteStrokes.get(`${message.sessionId}:${message.strokeId}`);if(!stroke||!Array.isArray(message.points))return;const points=message.points.filter(point=>core.finitePoint(point,1_000_000)).slice(0,50);if(!points.length)return;stroke.set({points:stroke.points.concat(points)});stroke.setCoords();canvas.requestRenderAll()}
  function removeRemoteStrokeByObjectId(id){remoteStrokes.forEach((stroke,key)=>{if(stroke.__previewObjectId===id){canvas.remove(stroke);remoteStrokes.delete(key)}})}function clearRemoteStrokes(){remoteStrokes.forEach(stroke=>canvas.remove(stroke));remoteStrokes.clear()}
  function updateRemoteCursor(message){if(!core.finitePoint(message,1_000_000))return;let cursor=remoteCursors.get(message.sessionId);if(!cursor){const element=document.createElement("div");element.className="remote-cursor";element.style.setProperty("--cursor",cursorColors[remoteCursors.size%cursorColors.length]);const name=document.createElement("span");name.textContent=message.actorName||"Участник";element.append(name);remoteCursorLayer.append(element);cursor={element,x:0,y:0};remoteCursors.set(message.sessionId,cursor)}cursor.x=message.x;cursor.y=message.y;renderRemoteCursors()}
  function renderRemoteCursors(){const transform=canvas.viewportTransform;remoteCursors.forEach(cursor=>{const point=fabric.util.transformPoint(new fabric.Point(cursor.x,cursor.y),transform);cursor.element.style.transform=`translate(${point.x}px, ${point.y}px)`})}function removeRemoteCursor(id){remoteCursors.get(id)?.element.remove();remoteCursors.delete(id)}

  document.querySelectorAll("[data-copy-link]").forEach(button=>button.addEventListener("click",async()=>{try{await navigator.clipboard.writeText(location.href);showToast("Ссылка скопирована",true)}catch{window.prompt("Скопируйте ссылку на доску",location.href)}closeMobileMore()}));
  document.querySelectorAll("[data-clear-board]").forEach(button=>button.addEventListener("click",async()=>{if(!connected||!confirm("Полностью очистить доску? Это действие нельзя отменить."))return;try{await runOperation({type:"board.clear"});history.clear();updateHistoryButtons();closeMobileMore()}catch(error){showToast(error.message)}}));

  const moreToggle=document.getElementById("mobile-more-toggle"),moreMenu=document.getElementById("mobile-more-menu");function closeMobileMore(){if(!moreMenu)return;moreMenu.hidden=true;moreToggle?.setAttribute("aria-expanded","false")}moreToggle?.addEventListener("click",event=>{event.stopPropagation();moreMenu.hidden=!moreMenu.hidden;moreToggle.setAttribute("aria-expanded",String(!moreMenu.hidden))});document.addEventListener("click",event=>{if(!event.target.closest(".mobile-context"))closeMobileMore()});

  const sidebarToggle=document.getElementById("sidebar-toggle"),sidebarClose=document.getElementById("sidebar-close"),sidebarBackdrop=document.getElementById("sidebar-backdrop"),sidebar=document.getElementById("board-sidebar");function openSidebar(){root.classList.add("sidebar-open");sidebarBackdrop.hidden=false;sidebar.setAttribute("aria-hidden","false");sidebarToggle?.setAttribute("aria-expanded","true");if(!document.querySelector(".related-board")&&!relatedLoading)loadRelatedBoards()};function closeSidebar(){root.classList.remove("sidebar-open");sidebarBackdrop.hidden=true;sidebar.setAttribute("aria-hidden","true");sidebarToggle?.setAttribute("aria-expanded","false")};sidebarToggle?.addEventListener("click",()=>root.classList.contains("sidebar-open")?closeSidebar():openSidebar());sidebarClose?.addEventListener("click",closeSidebar);sidebarBackdrop?.addEventListener("click",closeSidebar);
  async function loadRelatedBoards(){if(relatedLoading||!matchMedia("(min-width:801px)").matches)return;relatedLoading=true;const list=document.getElementById("related-boards");try{const url=new URL(`/api/boards/${boardId}/related`,location.origin);url.searchParams.set("limit","20");if(relatedCursor)url.searchParams.set("cursor",relatedCursor);const response=await fetch(url,{headers:{Accept:"application/json"},cache:"no-store"});if(!response.ok)throw new Error("Не удалось загрузить список досок");const data=await response.json();if(!relatedCursor)list.replaceChildren();for(const item of data.items||[]){const link=document.createElement("a");link.className="related-board";link.href=`/boards/${item.boardId}`;const strong=document.createElement("strong");strong.textContent=item.displayName;const meta=document.createElement("span");meta.textContent=new Intl.DateTimeFormat("ru-RU",{timeZone:"Europe/Moscow",day:"numeric",month:"long",year:"numeric",hour:"2-digit",minute:"2-digit"}).format(new Date(item.lessonStartAt));link.append(strong,meta);list.append(link)}if(!list.children.length){const empty=document.createElement("div");empty.className="sidebar-empty";empty.textContent="На прошлых занятиях пока нет заполненных досок";list.append(empty)}relatedCursor=data.nextCursor||null;document.getElementById("related-more").hidden=!relatedCursor}catch(error){list.innerHTML="";const item=document.createElement("div");item.className="sidebar-empty";item.textContent=error.message;list.append(item)}finally{relatedLoading=false}}
  document.getElementById("related-more")?.addEventListener("click",loadRelatedBoards);
  document.getElementById("board-name-form")?.addEventListener("submit",async event=>{event.preventDefault();const input=document.getElementById("board-name-input");const body=new URLSearchParams();body.set("name",input.value);try{const response=await fetch(`/api/boards/${boardId}/name`,{method:"POST",headers:{[csrfHeader]:csrfToken,"Content-Type":"application/x-www-form-urlencoded",Accept:"application/json"},body});const result=await response.json().catch(()=>({}));if(!response.ok)throw new Error(result.error||"Не удалось сохранить название");setBoardName(result.displayName);showToast("Название сохранено",true)}catch(error){showToast(error.message)}});

  function keyboardBlocked(){
    const active=canvas.getActiveObject();
    if(active?.isEditing)return true;
    return document.activeElement?.matches?.('textarea,[contenteditable=true],input:not([type="range"]):not([type="color"]):not([type="file"])')||false;
  }
  document.addEventListener("keydown",event=>{if(keyboardBlocked())return;if(event.code==="Space"&&!spacePressed){event.preventDefault();spacePressed=true;previousTool=currentTool;applyTool();return}if((event.ctrlKey||event.metaKey)&&event.code==="KeyZ"){event.preventDefault();event.shiftKey?redo():undo();return}if(event.ctrlKey&&event.code==="KeyY"){event.preventDefault();redo();return}if(event.ctrlKey||event.metaKey)return;const tools={KeyP:"pencil",KeyV:"select",KeyE:"eraser",KeyT:"text",KeyH:"hand"};if(tools[event.code]){event.preventDefault();selectTool(tools[event.code]);return}if((event.code==="Delete"||event.code==="Backspace")&&currentTool==="select"){event.preventDefault();deleteSelection()}if(event.code==="Escape"){closeSidebar();closeMobileMore();canvas.discardActiveObject();canvas.requestRenderAll()}});
  document.addEventListener("keyup",event=>{if(event.code!=="Space")return;spacePressed=false;currentTool=previousTool;applyTool()});window.addEventListener("beforeunload",()=>socket?.close());

  setConnected(false); selectTool("pencil"); updateGrid(); connect();
})();
