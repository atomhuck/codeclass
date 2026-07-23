(() => {
  "use strict";

  const root = document.getElementById("whiteboard-app");
  if (!root || !window.fabric) return;

  const boardId = root.dataset.boardId;
  const isTeacher = root.dataset.userRole === "TEACHER";
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content || "";
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || "X-CSRF-TOKEN";
  const shell = document.getElementById("canvas-shell");
  const connectionLabel = document.getElementById("connection-label");
  const participantCount = document.getElementById("participant-count");
  const zoomValue = document.getElementById("zoom-value");
  const uploadInput = document.getElementById("image-upload");
  const remoteCursorLayer = document.getElementById("remote-cursors");
  const objectIndex = new Map();
  const remoteCursors = new Map();
  const remoteStrokes = new Map();
  const cursorColors = ["#45D8FF", "#FFD66B", "#FF6B7A", "#A98CFF", "#64F5A6"];

  let revision = 0;
  let socket = null;
  let reconnectAttempt = 0;
  let reconnectTimer = null;
  let connected = false;
  let snapshotLoading = false;
  let currentTool = "pencil";
  let previousTool = "pencil";
  let brushColor = "#64F5A6";
  let brushWidth = 4;
  let spacePressed = false;
  let panning = false;
  let panLast = null;
  let draftStrokeId = null;
  let draftPoints = [];
  let pendingPreviewPoints = [];
  let previewTimer = null;
  let lastCursorSentAt = 0;
  let gestureDistance = 0;

  const canvas = new fabric.Canvas("board-canvas", {
    selection: false,
    preserveObjectStacking: true,
    stopContextMenu: true,
    fireRightClick: true,
    enableRetinaScaling: true
  });
  canvas.freeDrawingBrush = new fabric.PencilBrush(canvas);
  canvas.freeDrawingBrush.color = brushColor;
  canvas.freeDrawingBrush.width = brushWidth;

  function resizeCanvas() {
    canvas.setDimensions({ width: shell.clientWidth, height: shell.clientHeight });
    canvas.requestRenderAll();
    renderRemoteCursors();
  }
  new ResizeObserver(resizeCanvas).observe(shell);
  resizeCanvas();

  function uuid() {
    return crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  function setConnected(value) {
    connected = value;
    root.classList.toggle("is-online", value);
    root.classList.toggle("is-offline", !value);
    connectionLabel.textContent = value ? "В сети" : "Переподключение…";
    document.querySelectorAll(".tool-button, #clear-board").forEach(button => {
      if (button.matches("[data-tool]")) return;
      button.disabled = !value;
    });
    applyTool();
  }

  function showToast(message, success = false) {
    const toast = document.createElement("div");
    toast.className = `toast${success ? " success" : ""}`;
    toast.textContent = message;
    document.getElementById("toast-stack").append(toast);
    setTimeout(() => toast.remove(), 4200);
  }

  function send(payload) {
    if (!connected || !socket || socket.readyState !== WebSocket.OPEN) return false;
    socket.send(JSON.stringify(payload));
    return true;
  }

  function connect() {
    clearTimeout(reconnectTimer);
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    socket = new WebSocket(`${protocol}//${location.host}/ws/boards/${boardId}`);
    socket.onopen = async () => {
      reconnectAttempt = 0;
      try {
        await loadSnapshot();
        setConnected(true);
      } catch (error) {
        showToast(error.message || "Не удалось синхронизировать доску");
        socket.close();
      }
    };
    socket.onmessage = event => {
      try { handleMessage(JSON.parse(event.data)); }
      catch { showToast("Получено некорректное событие доски"); }
    };
    socket.onclose = () => {
      setConnected(false);
      remoteCursors.forEach(item => item.element.remove());
      remoteCursors.clear();
      clearRemoteStrokes();
      const delay = Math.min(15000, 500 * (2 ** reconnectAttempt++));
      reconnectTimer = setTimeout(connect, delay);
    };
    socket.onerror = () => socket.close();
  }

  async function loadSnapshot() {
    if (snapshotLoading) return;
    snapshotLoading = true;
    try {
      const response = await fetch(`/api/boards/${boardId}/snapshot`, { headers: { Accept: "application/json" } });
      if (!response.ok) throw new Error(response.status === 403 ? "Нет доступа к этой доске" : "Не удалось загрузить доску");
      const snapshot = await response.json();
      canvas.discardActiveObject();
      canvas.getObjects().slice().forEach(object => canvas.remove(object));
      objectIndex.clear();
      for (const item of snapshot.objects) await addOrReplaceObject(item);
      revision = snapshot.revision;
      canvas.requestRenderAll();
    } finally {
      snapshotLoading = false;
    }
  }

  async function addOrReplaceObject(item) {
    const existing = objectIndex.get(item.id);
    if (existing) canvas.remove(existing);
    let object;
    if (item.type === "PATH") {
      const { type: ignoredType, ...pathOptions } = item.data;
      object = new fabric.Path(item.data.path, {
        ...pathOptions,
        selectable: false,
        evented: true,
        objectCaching: false,
        perPixelTargetFind: true
      });
    } else {
      const imageClass = fabric.FabricImage || fabric.Image;
      object = await imageClass.fromURL(item.imageUrl, { crossOrigin: "use-credentials" });
      object.set({
        ...item.data,
        selectable: currentTool === "select",
        evented: true,
        lockRotation: false,
        cornerColor: "#64F5A6",
        cornerStrokeColor: "#07110c",
        borderColor: "#45D8FF",
        transparentCorners: false
      });
    }
    object.__boardId = item.id;
    object.__boardType = item.type;
    object.__boardVersion = item.version;
    objectIndex.set(item.id, object);
    canvas.add(object);
    canvas.moveObjectTo?.(object, canvas.getObjects().length - 1);
    removeRemoteStrokeByObjectId(item.id);
    return object;
  }

  function removeObject(id) {
    const object = objectIndex.get(id);
    if (!object) return;
    canvas.remove(object);
    objectIndex.delete(id);
    canvas.requestRenderAll();
  }

  async function applyRevision(message, mutation) {
    const next = Number(message.revision);
    if (!Number.isFinite(next)) return;
    if (next > revision + 1) {
      await loadSnapshot();
      return;
    }
    if (next <= revision) return;
    await mutation();
    revision = next;
    canvas.requestRenderAll();
  }

  async function handleMessage(message) {
    switch (message.type) {
      case "presence.join":
      case "presence.leave":
        participantCount.textContent = message.participants ?? 1;
        if (message.type === "presence.leave" && message.sessionId) removeRemoteCursor(message.sessionId);
        break;
      case "cursor.move":
        updateRemoteCursor(message);
        break;
      case "stroke.begin":
        startRemoteStroke(message);
        break;
      case "stroke.points":
        extendRemoteStroke(message);
        break;
      case "object.created":
      case "object.updated":
        await applyRevision(message, () => addOrReplaceObject(message.object));
        break;
      case "object.deleted":
        await applyRevision(message, () => removeObject(message.objectId));
        break;
      case "board.cleared":
        await applyRevision(message, () => {
          canvas.discardActiveObject();
          canvas.getObjects().slice().forEach(object => canvas.remove(object));
          objectIndex.clear();
          clearRemoteStrokes();
        });
        break;
      case "sync.required":
        await loadSnapshot();
        break;
      case "error":
        showToast(message.message || "Действие отклонено сервером");
        break;
    }
  }

  function applyTool() {
    const effective = spacePressed ? "hand" : currentTool;
    canvas.isDrawingMode = connected && effective === "pencil";
    canvas.selection = false;
    canvas.defaultCursor = effective === "hand" ? "grab" : effective === "eraser" ? "crosshair" : "default";
    canvas.hoverCursor = effective === "eraser" ? "crosshair" : effective === "select" ? "move" : canvas.defaultCursor;
    canvas.getObjects().forEach(object => {
      object.selectable = connected && effective === "select" && object.__boardType === "IMAGE";
      object.evented = effective === "eraser" || (effective === "select" && object.__boardType === "IMAGE");
    });
    if (effective !== "select") canvas.discardActiveObject();
    canvas.requestRenderAll();
  }

  function selectTool(tool) {
    currentTool = tool;
    document.querySelectorAll("[data-tool]").forEach(button => button.classList.toggle("active", button.dataset.tool === tool));
    applyTool();
  }

  document.querySelectorAll("[data-tool]").forEach(button => button.addEventListener("click", () => selectTool(button.dataset.tool)));
  document.getElementById("brush-size").addEventListener("input", event => {
    brushWidth = Number(event.target.value);
    document.getElementById("brush-size-value").textContent = `${brushWidth} px`;
    canvas.freeDrawingBrush.width = brushWidth;
  });
  document.querySelectorAll(".color-swatch").forEach(button => button.addEventListener("click", () => setBrushColor(button.dataset.color)));
  document.getElementById("brush-color").addEventListener("input", event => setBrushColor(event.target.value));

  function setBrushColor(color) {
    brushColor = color.toUpperCase();
    canvas.freeDrawingBrush.color = brushColor;
    document.querySelectorAll(".color-swatch").forEach(button => button.classList.toggle("active", button.dataset.color === brushColor));
  }

  function scenePoint(event) {
    return canvas.getScenePoint ? canvas.getScenePoint(event) : canvas.getPointer(event);
  }

  canvas.on("mouse:down", opt => {
    if (!connected) return;
    const event = opt.e;
    const hand = currentTool === "hand" || spacePressed || event.button === 1;
    if (hand) {
      panning = true;
      canvas.isDrawingMode = false;
      canvas.defaultCursor = "grabbing";
      panLast = { x: event.clientX, y: event.clientY };
      event.preventDefault();
      return;
    }
    if (currentTool === "eraser") {
      const target = opt.target || canvas.findTarget(event);
      if (target?.__boardId) {
        send({ type: "object.delete", operationId: uuid(), objectId: target.__boardId });
      }
      return;
    }
    if (currentTool === "pencil") {
      draftStrokeId = uuid();
      const point = scenePoint(event);
      draftPoints = [{ x: point.x, y: point.y }];
      pendingPreviewPoints = [];
      send({ type: "stroke.begin", strokeId: draftStrokeId, color: brushColor, width: brushWidth, point });
    }
  });

  canvas.on("mouse:move", opt => {
    const event = opt.e;
    if (panning && panLast) {
      const transform = canvas.viewportTransform;
      transform[4] += event.clientX - panLast.x;
      transform[5] += event.clientY - panLast.y;
      panLast = { x: event.clientX, y: event.clientY };
      canvas.requestRenderAll();
      renderRemoteCursors();
      return;
    }
    const point = scenePoint(event);
    const now = performance.now();
    if (connected && now - lastCursorSentAt >= 50) {
      lastCursorSentAt = now;
      send({ type: "cursor.move", x: point.x, y: point.y });
    }
    if (connected && currentTool === "pencil" && draftStrokeId && event.buttons === 1) {
      const last = draftPoints[draftPoints.length - 1];
      if (!last || Math.hypot(point.x - last.x, point.y - last.y) >= .8) {
        draftPoints.push({ x: point.x, y: point.y });
        pendingPreviewPoints.push({ x: point.x, y: point.y });
        schedulePreviewSend();
      }
    }
  });

  canvas.on("mouse:up", () => {
    if (panning) {
      panning = false;
      panLast = null;
      applyTool();
    }
    flushPreview();
  });

  canvas.on("path:created", opt => {
    const path = opt.path;
    if (!connected || !draftStrokeId) {
      canvas.remove(path);
      return;
    }
    path.set({ selectable: false, evented: true, objectCaching: false, perPixelTargetFind: true });
    path.__boardId = draftStrokeId;
    path.__boardType = "PATH";
    objectIndex.set(draftStrokeId, path);
    const data = path.toObject(["path", "stroke", "strokeWidth", "strokeLineCap", "strokeLineJoin", "left", "top", "scaleX", "scaleY", "angle", "width", "height"]);
    delete data.type;
    data.stroke = brushColor;
    data.strokeWidth = brushWidth;
    send({ type: "stroke.commit", operationId: draftStrokeId, objectId: draftStrokeId, data });
    draftStrokeId = null;
    draftPoints = [];
    pendingPreviewPoints = [];
  });

  canvas.on("object:modified", opt => {
    const object = opt.target;
    if (!connected || object?.__boardType !== "IMAGE") return;
    const data = {
      left: object.left, top: object.top, width: object.width, height: object.height,
      scaleX: object.scaleX, scaleY: object.scaleY, angle: object.angle || 0
    };
    send({
      type: "object.update", operationId: uuid(), objectId: object.__boardId,
      expectedVersion: object.__boardVersion, data
    });
  });

  function schedulePreviewSend() {
    if (previewTimer) return;
    previewTimer = setTimeout(flushPreview, 35);
  }

  function flushPreview() {
    clearTimeout(previewTimer);
    previewTimer = null;
    if (!draftStrokeId || pendingPreviewPoints.length === 0) return;
    send({ type: "stroke.points", strokeId: draftStrokeId, points: pendingPreviewPoints.splice(0, 50) });
    if (pendingPreviewPoints.length) schedulePreviewSend();
  }

  function startRemoteStroke(message) {
    if (!validPoint(message.point)) return;
    const key = `${message.sessionId}:${message.strokeId}`;
    const stroke = new fabric.Polyline([message.point], {
      fill: null, stroke: message.color || "#45D8FF", strokeWidth: message.width || 4,
      strokeLineCap: "round", strokeLineJoin: "round", selectable: false, evented: false,
      opacity: .72, objectCaching: false
    });
    stroke.__previewObjectId = message.strokeId;
    remoteStrokes.set(key, stroke);
    canvas.add(stroke);
  }

  function extendRemoteStroke(message) {
    const key = `${message.sessionId}:${message.strokeId}`;
    const stroke = remoteStrokes.get(key);
    if (!stroke || !Array.isArray(message.points)) return;
    const points = message.points.filter(validPoint).slice(0, 50);
    if (!points.length) return;
    stroke.set({ points: stroke.points.concat(points) });
    stroke.setCoords();
    canvas.requestRenderAll();
  }

  function validPoint(point) {
    return point && Number.isFinite(point.x) && Number.isFinite(point.y)
      && Math.abs(point.x) <= 1000000 && Math.abs(point.y) <= 1000000;
  }

  function removeRemoteStrokeByObjectId(id) {
    remoteStrokes.forEach((stroke, key) => {
      if (stroke.__previewObjectId !== id) return;
      canvas.remove(stroke);
      remoteStrokes.delete(key);
    });
  }

  function clearRemoteStrokes() {
    remoteStrokes.forEach(stroke => canvas.remove(stroke));
    remoteStrokes.clear();
  }

  function updateRemoteCursor(message) {
    if (!Number.isFinite(message.x) || !Number.isFinite(message.y)) return;
    let cursor = remoteCursors.get(message.sessionId);
    if (!cursor) {
      const element = document.createElement("div");
      element.className = "remote-cursor";
      element.style.setProperty("--cursor", cursorColors[remoteCursors.size % cursorColors.length]);
      const name = document.createElement("span");
      name.textContent = message.actorName || "Участник";
      element.append(name);
      remoteCursorLayer.append(element);
      cursor = { element, x: 0, y: 0 };
      remoteCursors.set(message.sessionId, cursor);
    }
    cursor.x = message.x;
    cursor.y = message.y;
    renderRemoteCursors();
  }

  function renderRemoteCursors() {
    const transform = canvas.viewportTransform;
    remoteCursors.forEach(cursor => {
      const point = fabric.util.transformPoint(new fabric.Point(cursor.x, cursor.y), transform);
      cursor.element.style.transform = `translate(${point.x}px, ${point.y}px)`;
    });
  }

  function removeRemoteCursor(sessionId) {
    const cursor = remoteCursors.get(sessionId);
    cursor?.element.remove();
    remoteCursors.delete(sessionId);
  }

  function setZoom(next, point = new fabric.Point(canvas.getWidth() / 2, canvas.getHeight() / 2)) {
    const zoom = Math.max(.1, Math.min(8, next));
    canvas.zoomToPoint(point, zoom);
    zoomValue.textContent = `${Math.round(zoom * 100)}%`;
    renderRemoteCursors();
  }

  canvas.on("mouse:wheel", opt => {
    const delta = opt.e.deltaY;
    setZoom(canvas.getZoom() * (0.999 ** delta), new fabric.Point(opt.e.offsetX, opt.e.offsetY));
    opt.e.preventDefault();
    opt.e.stopPropagation();
  });
  document.getElementById("zoom-in").addEventListener("click", () => setZoom(canvas.getZoom() * 1.2));
  document.getElementById("zoom-out").addEventListener("click", () => setZoom(canvas.getZoom() / 1.2));
  document.getElementById("zoom-reset").addEventListener("click", () => {
    canvas.setViewportTransform([1, 0, 0, 1, 0, 0]);
    zoomValue.textContent = "100%";
    canvas.requestRenderAll();
    renderRemoteCursors();
  });

  shell.addEventListener("touchstart", event => {
    if (event.touches.length === 2) {
      gestureDistance = Math.hypot(
        event.touches[0].clientX - event.touches[1].clientX,
        event.touches[0].clientY - event.touches[1].clientY
      );
      canvas.isDrawingMode = false;
    }
  }, { passive: false });
  shell.addEventListener("touchmove", event => {
    if (event.touches.length !== 2 || !gestureDistance) return;
    const distance = Math.hypot(
      event.touches[0].clientX - event.touches[1].clientX,
      event.touches[0].clientY - event.touches[1].clientY
    );
    const rect = shell.getBoundingClientRect();
    const center = new fabric.Point(
      (event.touches[0].clientX + event.touches[1].clientX) / 2 - rect.left,
      (event.touches[0].clientY + event.touches[1].clientY) / 2 - rect.top
    );
    setZoom(canvas.getZoom() * distance / gestureDistance, center);
    gestureDistance = distance;
    event.preventDefault();
  }, { passive: false });
  shell.addEventListener("touchend", () => {
    gestureDistance = 0;
    applyTool();
  }, { passive: true });

  uploadInput.addEventListener("change", async () => {
    const file = uploadInput.files?.[0];
    uploadInput.value = "";
    if (!file || !connected) return;
    if (!["image/jpeg", "image/png"].includes(file.type)) return showToast("Разрешены только JPEG и PNG");
    if (file.size > 10 * 1024 * 1024) return showToast("Изображение превышает 10 МБ");
    const center = fabric.util.transformPoint(
      new fabric.Point(shell.clientWidth / 2, shell.clientHeight / 2),
      fabric.util.invertTransform(canvas.viewportTransform)
    );
    const data = new FormData();
    data.append("file", file);
    data.append("left", String(center.x));
    data.append("top", String(center.y));
    try {
      const response = await fetch(`/api/boards/${boardId}/images`, {
        method: "POST", headers: { [csrfHeader]: csrfToken }, body: data
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.error || "Не удалось загрузить изображение");
      if (result.revision > revision) {
        await addOrReplaceObject(result.object);
        revision = result.revision;
        canvas.requestRenderAll();
      }
      selectTool("select");
    } catch (error) { showToast(error.message); }
  });

  document.getElementById("copy-link").addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(location.href);
      showToast("Ссылка скопирована", true);
    } catch {
      window.prompt("Скопируйте ссылку на доску", location.href);
    }
  });

  const clearButton = document.getElementById("clear-board");
  if (isTeacher && clearButton) clearButton.addEventListener("click", () => {
    if (!connected) return;
    if (confirm("Полностью очистить доску? Все рисунки и фотографии будут удалены без возможности восстановления.")) {
      send({ type: "board.clear", operationId: uuid() });
    }
  });

  document.addEventListener("keydown", event => {
    if (event.target.matches("input")) return;
    if (event.code === "Space" && !spacePressed) {
      event.preventDefault();
      spacePressed = true;
      previousTool = currentTool;
      applyTool();
    } else if (event.key.toLowerCase() === "p") selectTool("pencil");
    else if (event.key.toLowerCase() === "e") selectTool("eraser");
    else if (event.key.toLowerCase() === "v") selectTool("select");
    else if (event.key.toLowerCase() === "h") selectTool("hand");
    else if ((event.key === "Delete" || event.key === "Backspace") && currentTool === "select") {
      const object = canvas.getActiveObject();
      if (object?.__boardId) send({ type: "object.delete", operationId: uuid(), objectId: object.__boardId });
    }
  });
  document.addEventListener("keyup", event => {
    if (event.code !== "Space") return;
    spacePressed = false;
    currentTool = previousTool;
    applyTool();
  });
  window.addEventListener("beforeunload", () => socket?.close());

  setConnected(false);
  selectTool("pencil");
  connect();
})();
