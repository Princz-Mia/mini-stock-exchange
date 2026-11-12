// Applies a stored theme before the first paint, so a page forced to one scheme never flashes the other while the
// app loads. It is a file of its own because the content security policy forbids inline scripts, and it is loaded
// from <head> without defer, so it runs before the body is drawn. The key matches src/app/preferences.ts.
(function () {
  try {
    var theme = window.localStorage.getItem('mse.theme');
    if (theme === 'light' || theme === 'dark') {
      document.documentElement.setAttribute('data-theme', theme);
    }
  } catch (error) {
    // Storage unavailable: the page follows the system's scheme.
  }
})();
