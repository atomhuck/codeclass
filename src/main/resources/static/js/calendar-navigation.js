(() => {
  const cache = new Map();
  const maxCachedMonths = 6;
  let activeRequest = null;

  const remember = (url, html) => {
    cache.delete(url);
    cache.set(url, html);
    while (cache.size > maxCachedMonths) cache.delete(cache.keys().next().value);
  };

  const parsePanel = html => {
    const template = document.createElement("template");
    template.innerHTML = html.trim();
    return template.content.firstElementChild;
  };

  const replaceCalendar = (current, html, canonicalUrl, action) => {
    const next = parsePanel(html);
    if (!next?.classList.contains("calendar-panel")) throw new Error("Invalid calendar response");
    current.replaceWith(next);
    if (canonicalUrl) history.pushState({ calendar: true }, "", canonicalUrl);
    document.dispatchEvent(new CustomEvent("calendar:updated", { detail: { panel: next } }));
    next.querySelector("[data-calendar-status]").textContent = `${next.querySelector("h2")?.textContent || "Календарь"} загружен`;
    next.querySelector(`[data-calendar-action="${action}"]`)?.focus();
  };

  const load = async (panel, fragmentUrl, canonicalUrl, action, pushState) => {
    activeRequest?.abort();
    const controller = new AbortController();
    activeRequest = controller;
    panel.setAttribute("aria-busy", "true");
    panel.classList.add("is-loading");
    try {
      let html = cache.get(fragmentUrl);
      if (!html) {
        const response = await fetch(fragmentUrl, {
          headers: { Accept: "text/html", "X-Requested-With": "XMLHttpRequest" },
          credentials: "same-origin",
          signal: controller.signal
        });
        if (!response.ok) throw new Error(`Calendar request failed: ${response.status}`);
        html = await response.text();
        remember(fragmentUrl, html);
      }
      if (controller.signal.aborted) return;
      replaceCalendar(panel, html, pushState ? canonicalUrl : null, action);
    } catch (error) {
      if (error.name !== "AbortError") window.location.assign(canonicalUrl);
    } finally {
      if (activeRequest === controller) activeRequest = null;
      panel.removeAttribute("aria-busy");
      panel.classList.remove("is-loading");
    }
  };

  document.addEventListener("click", event => {
    const link = event.target.closest("[data-calendar-link]");
    if (!link || event.defaultPrevented || (event.button != null && event.button !== 0) || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const panel = link.closest(".calendar-panel");
    const fragmentUrl = link.dataset.calendarFragmentUrl;
    if (!panel || !fragmentUrl) return;
    event.preventDefault();
    load(panel, fragmentUrl, link.href, link.dataset.calendarAction || "today", true);
  });

  window.addEventListener("popstate", () => {
    const panel = document.querySelector(".calendar-panel");
    if (!panel) return;
    const url = new URL(location.href);
    const fragmentUrl = `${panel.dataset.calendarEndpoint}${url.search}`;
    load(panel, fragmentUrl, null, "today", false);
  });
})();
