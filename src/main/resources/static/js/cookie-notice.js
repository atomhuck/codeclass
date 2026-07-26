(() => {
    const legacyKey = "codeclass-cookie-notice";
    const key = "repethelper-cookie-notice";
    if (localStorage.getItem(legacyKey) === "accepted") localStorage.setItem(key, "accepted");
    if (localStorage.getItem(key) === "accepted") return;
    const notice = document.createElement("aside");
    notice.className = "cookie-notice";
    notice.setAttribute("role", "status");
    notice.innerHTML = `<div><b>Файлы cookie</b><p>RepetHelper использует только необходимые cookie для входа и защиты аккаунта. <a href="/legal/privacy">Подробнее</a></p></div><button class="button primary" type="button">Понятно</button>`;
    notice.querySelector("button").addEventListener("click", () => {
        localStorage.setItem(key, "accepted");
        notice.remove();
    });
    document.addEventListener("DOMContentLoaded", () => document.body.appendChild(notice));
})();
