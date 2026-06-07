import "./app/assets/css/main.css"

// Force dark theme on the story iframe — the dashboard's tokens resolve via the
// `.dark` class on <html>. Without this we render invisible-on-invisible.
if (typeof document !== "undefined") {
  document.documentElement.classList.add("dark")
  document.documentElement.style.background = "var(--background)"
  document.documentElement.style.color = "var(--foreground)"
}
