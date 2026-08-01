(() => {
  const syncVisualViewport = () => {
    const height = window.visualViewport?.height || window.innerHeight;
    document.documentElement.style.setProperty("--visual-viewport-height", `${Math.round(height)}px`);
  };
  syncVisualViewport();
  window.visualViewport?.addEventListener("resize", syncVisualViewport);
  window.visualViewport?.addEventListener("scroll", syncVisualViewport);

  const menus = () => [...document.querySelectorAll("details[data-menu]")];
  const closeMenu = menu => {
    if (!menu?.open) return;
    menu.removeAttribute("open");
    menu.querySelector("summary")?.setAttribute("aria-expanded", "false");
  };

  document.addEventListener("toggle", event => {
    const menu = event.target.closest?.("details[data-menu]");
    if (!menu) return;
    menu.querySelector("summary")?.setAttribute("aria-expanded", String(menu.open));
    if (menu.open) menus().filter(other => other !== menu).forEach(closeMenu);
  }, true);

  document.addEventListener("pointerdown", event => {
    menus().forEach(menu => {
      if (!menu.contains(event.target)) closeMenu(menu);
    });
  });

  document.addEventListener("keydown", event => {
    if (event.key !== "Escape") return;
    const opened = menus().find(menu => menu.open);
    if (opened) {
      event.preventDefault();
      closeMenu(opened);
      opened.querySelector("summary")?.focus();
    }
  });

  document.addEventListener("click", event => {
    const opener = event.target.closest("[data-dialog-open]");
    if (opener) {
      const dialog = document.getElementById(opener.dataset.dialogOpen);
      if (dialog && !dialog.open) {
        dialog.__returnFocus = opener;
        dialog.showModal();
      }
      return;
    }

    const closer = event.target.closest("[data-dialog-close]");
    if (closer) {
      closer.closest("dialog")?.close();
      return;
    }

    const logout = event.target.closest("[data-logout-confirm]");
    if (!logout) return;
    const dialog = document.querySelector("[data-logout-dialog]");
    closeMenu(logout.closest("details[data-menu]"));
    if (dialog && !dialog.open) dialog.showModal();
  });

  document.querySelectorAll("dialog").forEach(dialog => {
    dialog.addEventListener("click", event => {
      if (event.target === dialog) dialog.close();
    });
    dialog.addEventListener("close", () => {
      dialog.__returnFocus?.focus();
      dialog.__returnFocus = null;
    });
  });

  document.addEventListener("click", event => {
    const back = event.target.closest("[data-legal-back]");
    if (!back) return;
    try {
      const previous = new URL(document.referrer);
      if (previous.origin === location.origin && history.length > 1) {
        event.preventDefault();
        history.back();
      }
    } catch (_) { /* Direct opening uses the safe href fallback. */ }
  });
})();
