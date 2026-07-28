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
    if (event.target.closest("[data-logout-confirm]") && dialog) dialog.showModal();
  });
})();
