import { useLayoutEffect } from 'react';

export type DocumentTheme = 'public' | 'console';

/**
 * Puts the theme on <html>, not on the page's wrapper, so that anything
 * rendered into a portal -- dialogs, toasts, tooltips -- is themed like the
 * page that opened it. See src/styles/tokens.css.
 */
export function useDocumentTheme(theme: DocumentTheme): void {
  useLayoutEffect(() => {
    const root = document.documentElement;
    const previous = root.dataset.theme;
    root.dataset.theme = theme;
    return () => {
      if (previous) root.dataset.theme = previous;
      else delete root.dataset.theme;
    };
  }, [theme]);
}
