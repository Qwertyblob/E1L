import { useEffect, useRef } from 'react';

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

// Keep Tab/Shift+Tab focus within `node`. Wraps from last->first (and the
// reverse) and pulls focus back inside if it has escaped the dialog.
function trapTab(event, node, items) {
  const first = items[0];
  const last = items[items.length - 1];
  const outside = !node.contains(document.activeElement);

  if (event.shiftKey && (document.activeElement === first || outside)) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && (document.activeElement === last || outside)) {
    event.preventDefault();
    first.focus();
  }
}

// Accessibility for modal dialogs: when `active` becomes true it moves focus
// into the dialog, traps Tab/Shift+Tab within it, closes on Escape, and
// restores focus to the previously focused element when the dialog closes.
// Attach the returned ref to the dialog container (give it tabIndex={-1}).
export function useFocusTrap(active, onClose) {
  const ref = useRef(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!active) return undefined;
    const node = ref.current;
    if (!node) return undefined;

    const previouslyFocused = document.activeElement;
    const focusables = () => node.querySelectorAll(FOCUSABLE);

    // Focus the container so screen readers announce the dialog label.
    node.focus();

    function onKeyDown(event) {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onCloseRef.current?.();
        return;
      }
      if (event.key !== 'Tab') return;

      const items = focusables();
      if (items.length === 0) {
        event.preventDefault();
        return;
      }
      trapTab(event, node, items);
    }

    node.addEventListener('keydown', onKeyDown);
    return () => {
      node.removeEventListener('keydown', onKeyDown);
      if (previouslyFocused instanceof HTMLElement) previouslyFocused.focus();
    };
  }, [active]);

  return ref;
}
