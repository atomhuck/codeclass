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
  };
  window.RepetHelperTheme = { apply, get: choice, set(value) { localStorage.setItem(key, value); apply(); } };
  apply();
  media.addEventListener?.("change", () => choice() === "system" && apply());
  document.addEventListener("click", event => {
    const button = event.target.closest("[data-theme-choice]");
    if (button) window.RepetHelperTheme.set(button.dataset.themeChoice);
  });
})();
