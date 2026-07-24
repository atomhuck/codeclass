(() => {
    if (localStorage.getItem("codeclass-cookie-notice") === "accepted") return;
    const notice = document.createElement("aside");
    notice.className = "cookie-notice";
    notice.setAttribute("role", "status");
    notice.innerHTML = `<div><b>Файлы cookie</b><p>CodeClass использует только необходимые cookie для входа и защиты аккаунта. <a href="/legal/privacy">Подробнее</a></p></div><button class="button primary" type="button">Понятно</button>`;
    notice.querySelector("button").addEventListener("click", () => {
        localStorage.setItem("codeclass-cookie-notice", "accepted");
        notice.remove();
    });
    document.addEventListener("DOMContentLoaded", () => document.body.appendChild(notice));
})();
