const directoryButton = document.querySelector('.guide-menu');
directoryButton.hidden = false;
function openDirectoryFromLink() {
  if (location.hash === '#guide') directoryButton.setAttribute('aria-expanded', 'true');
}

openDirectoryFromLink();
window.addEventListener('hashchange', openDirectoryFromLink);
document.querySelector('.masthead a[href="#guide"]').addEventListener('click', () => {
  directoryButton.setAttribute('aria-expanded', 'true');
});
directoryButton.addEventListener('click', () => {
  const expanded = directoryButton.getAttribute('aria-expanded') === 'true';
  directoryButton.setAttribute('aria-expanded', String(!expanded));
});

for (const button of document.querySelectorAll('.branch-toggle')) {
  const submenu = document.getElementById(button.getAttribute('aria-controls'));
  function setExpanded(expanded) {
    button.setAttribute('aria-expanded', String(expanded));
    submenu.hidden = !expanded;
  }

  setExpanded(Boolean(button.closest('[data-active]')));
  button.hidden = false;
  button.addEventListener('click', () => setExpanded(submenu.hidden));
}
