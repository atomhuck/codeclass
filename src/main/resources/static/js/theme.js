(() => {
  const key = "repethelper-theme";
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  const choice = () => localStorage.getItem(key) || "system";
  const resolved = () => choice() === "system" ? (media.matches ? "dark" : "light") : choice();
  const apply = () => {
    const theme = resolved();
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = theme;
    document.querySelector('meta[name="theme-color"]')?.setAttribute("content", theme === "dark" ? "#08111A" : "#F5F8FC");
    document.querySelectorAll("[data-theme-choice]").forEach(item => item.setAttribute("aria-pressed", String(item.dataset.themeChoice === choice())));
    document.querySelectorAll("[data-theme-toggle]").forEach(button => button.setAttribute("aria-pressed", String(theme === "dark")));
  };
  const set = value => {
    document.documentElement.classList.add("theme-switching");
    localStorage.setItem(key, value);
    apply();
    window.setTimeout(() => document.documentElement.classList.remove("theme-switching"), 380);
  };
  window.RepetHelperTheme = { apply, get: choice, set };
  apply();
  media.addEventListener?.("change", () => choice() === "system" && apply());
  document.addEventListener("click", event => {
    const button = event.target.closest("[data-theme-choice]");
    if (button) set(button.dataset.themeChoice);
    if (event.target.closest("[data-theme-toggle]")) set(resolved() === "dark" ? "light" : "dark");
    const dialog = document.querySelector("[data-logout-dialog]");
    const logout = event.target.closest("[data-logout-confirm]");
    if (logout && dialog) {
      logout.closest("details")?.removeAttribute("open");
      if (!dialog.open) dialog.showModal();
    }
  });
  document.addEventListener("pointerdown", event => {
    document.querySelectorAll("details.account-menu[open]").forEach(menu => {
      if (!menu.contains(event.target)) menu.removeAttribute("open");
    });
  });
  // Mobile browsers may restore focus to a form input after a page reload.
  // The invitation-code field is optional on an already connected student's dashboard,
  // so suppressing that restored focus avoids opening the software keyboard on load.
  window.addEventListener("pageshow", () => {
    window.setTimeout(() => {
      const active = document.activeElement;
      if (active?.matches?.("[data-no-restore-focus]")) active.blur();
    }, 0);
  });
})();
