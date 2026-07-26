(() => {
  const mobile = window.matchMedia("(max-width: 640px)");
  const initialise = () => {
    if (!mobile.matches) return;
    document.querySelectorAll(".calendar-panel").forEach(panel => {
      if (panel.dataset.mobileCalendarReady) return;
      const days = panel.querySelectorAll(".calendar.days .calendar-day:not(.muted)");
      if (!days.length) return;
      panel.dataset.mobileCalendarReady = "true";
      const agenda = document.createElement("section");
      agenda.className = "mobile-day-agenda";
      agenda.setAttribute("aria-live", "polite");
      panel.appendChild(agenda);
      const show = day => {
        days.forEach(item => item.classList.toggle("selected", item === day));
        const number = day.querySelector(".day-number")?.textContent || "";
        const events = [...day.querySelectorAll(".calendar-event")];
        agenda.innerHTML = `<p class="eyebrow">Выбранный день</p><h3>${number} ${panel.querySelector("h2")?.textContent || ""}</h3>`;
        if (!events.length) { agenda.insertAdjacentHTML("beforeend", '<p class="mobile-day-empty">На этот день занятий нет.</p>'); return; }
        const list = document.createElement("div");
        list.className = "mobile-day-list";
        events.forEach(event => list.appendChild(event.cloneNode(true)));
        agenda.appendChild(list);
      };
      days.forEach(day => {
        day.tabIndex = 0;
        day.setAttribute("role", "button");
        day.addEventListener("click", event => { if (!event.target.closest(".calendar-event")) show(day); });
        day.addEventListener("keydown", event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); show(day); } });
      });
      show([...days].find(day => day.classList.contains("today")) || [...days].find(day => day.querySelector(".calendar-event")) || days[0]);
    });
  };
  document.addEventListener("DOMContentLoaded", initialise);
  document.addEventListener("DOMContentLoaded", () => {
    if (location.hash === "#new-lesson") document.getElementById("new-lesson")?.setAttribute("open", "");
  });
  mobile.addEventListener?.("change", initialise);
})();
