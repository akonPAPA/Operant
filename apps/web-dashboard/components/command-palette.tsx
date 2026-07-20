"use client";

// WP3 — bounded navigation command palette.
//
// SCOPE / SAFETY (governing engineering law):
// - This palette ONLY navigates to routes it is given as props. It issues no API calls, accepts no
//   arbitrary URLs, runs no commands, and performs no mutation, approval, or access elevation.
// - Its entries come from the WP1 navigation registry (tenant plane, palette-visible, capability-
//   filtered) resolved on the server and passed in as plain data — the browser never discovers
//   staff/internal routes here. UI visibility is not authorization; Core still authorizes every route.
// - No sensitive data is stored. There is no localStorage/session persistence.

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";

export type PaletteEntry = Readonly<{
  id: string;
  path: string;
  label: string;
  section: string;
  searchAliases: readonly string[];
}>;

/**
 * Pure, bounded filter over the small navigation registry. Case-insensitive substring match on
 * label, section, and search synonyms. Exported for unit testing. Empty query returns all entries.
 */
export function filterPaletteEntries(
  entries: readonly PaletteEntry[],
  query: string
): PaletteEntry[] {
  const q = query.trim().toLowerCase();
  if (q === "") {
    return [...entries];
  }
  return entries.filter((entry) => {
    if (entry.label.toLowerCase().includes(q)) return true;
    if (entry.section.toLowerCase().includes(q)) return true;
    return entry.searchAliases.some((alias) => alias.toLowerCase().includes(q));
  });
}

export function CommandPalette({ entries }: Readonly<{ entries: readonly PaletteEntry[] }>) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);

  const triggerRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);

  const baseId = useId();
  const listId = `${baseId}-list`;

  const results = useMemo(() => filterPaletteEntries(entries, query), [entries, query]);

  const close = useCallback(() => {
    setOpen(false);
    setQuery("");
    setActiveIndex(0);
    // Return focus to the invoker if it was a real, still-connected focusable element; otherwise
    // (e.g. opened via the global Ctrl/Cmd+K shortcut from document.body) fall back to the trigger,
    // so focus never lands on <body> after close.
    const previous = restoreFocusRef.current;
    const toRestore =
      previous && previous !== document.body && previous.isConnected ? previous : triggerRef.current;
    window.requestAnimationFrame(() => toRestore?.focus());
  }, []);

  const openPalette = useCallback(() => {
    restoreFocusRef.current = (document.activeElement as HTMLElement | null) ?? null;
    setQuery("");
    setActiveIndex(0);
    setOpen(true);
  }, []);

  // Global Ctrl/Cmd+K toggle. Only reacts to that chord, so it never captures ordinary typing
  // inside inputs, textareas, or contenteditable regions.
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setOpen((current) => {
          if (current) {
            return current;
          }
          restoreFocusRef.current = (document.activeElement as HTMLElement | null) ?? null;
          setQuery("");
          setActiveIndex(0);
          return true;
        });
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, []);

  // Move focus into the input when the dialog opens.
  useEffect(() => {
    if (open) {
      inputRef.current?.focus();
    }
  }, [open]);

  const navigateTo = useCallback(
    (path: string) => {
      close();
      router.push(path);
    },
    [close, router]
  );

  function onDialogKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      close();
      return;
    }
    if (event.key === "Tab") {
      // Focus is managed via aria-activedescendant on the input; trap Tab to keep it in the dialog.
      event.preventDefault();
      inputRef.current?.focus();
      return;
    }
    if (event.key === "ArrowDown") {
      event.preventDefault();
      if (results.length > 0) {
        setActiveIndex((index) => (index + 1) % results.length);
      }
      return;
    }
    if (event.key === "ArrowUp") {
      event.preventDefault();
      if (results.length > 0) {
        setActiveIndex((index) => (index - 1 + results.length) % results.length);
      }
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      const selected = results[activeIndex];
      if (selected) {
        navigateTo(selected.path);
      }
    }
  }

  const activeOptionId = results[activeIndex] ? `${baseId}-opt-${results[activeIndex].id}` : undefined;

  return (
    <>
      <button
        type="button"
        ref={triggerRef}
        className="command-trigger"
        onClick={openPalette}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <span aria-hidden="true">⌕</span>
        <span>Search</span>
        <kbd aria-hidden="true">Ctrl K</kbd>
      </button>

      {open ? (
        <div
          className="command-overlay"
          role="presentation"
          onMouseDown={(event) => {
            // Click outside the dialog closes; predictable behavior.
            if (event.target === event.currentTarget) {
              close();
            }
          }}
        >
          <div
            className="command-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="Command palette — search navigation"
            onKeyDown={onDialogKeyDown}
          >
            <input
              ref={inputRef}
              className="command-input"
              type="text"
              role="combobox"
              aria-expanded="true"
              aria-controls={listId}
              aria-activedescendant={activeOptionId}
              aria-label="Search navigation"
              placeholder="Search navigation…"
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                setActiveIndex(0);
              }}
            />
            {results.length > 0 ? (
              <ul className="command-list" id={listId} role="listbox" aria-label="Navigation results">
                {results.map((entry, index) => (
                  <li
                    key={entry.id}
                    id={`${baseId}-opt-${entry.id}`}
                    className="command-option"
                    role="option"
                    aria-selected={index === activeIndex}
                    onMouseEnter={() => setActiveIndex(index)}
                    onClick={() => navigateTo(entry.path)}
                  >
                    <span>{entry.label}</span>
                    <span className="command-option-section">{entry.section}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="command-empty" role="status" aria-live="polite">
                No navigation matches “{query}”.
              </p>
            )}
          </div>
        </div>
      ) : null}
    </>
  );
}
