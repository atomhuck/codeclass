(() => {
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
  });
})();
