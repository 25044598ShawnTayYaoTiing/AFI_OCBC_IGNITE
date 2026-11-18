import { useLayoutEffect, useRef } from "react";
import type { ReactNode } from "react";
import { createPortal } from "react-dom";
import { NavLink } from "react-router-dom";
import { ChevronDown } from "lucide-react";

export function SectionNav({
  items,
  label,
}: {
  items: { to: string; label: string; end?: boolean }[];
  label: string;
}) {
  return (
    <nav className="section-nav" aria-label={label}>
      {items.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end={item.end !== false}
          className={({ isActive }) => (isActive ? "active" : "")}
        >
          {item.label}
        </NavLink>
      ))}
    </nav>
  );
}

export function Disclosure({
  title,
  children,
  open = false,
}: {
  title: string;
  children: ReactNode;
  open?: boolean;
}) {
  return (
    <details className="disclosure" open={open || undefined}>
      <summary>
        {title}
        <ChevronDown size={16} aria-hidden="true" />
      </summary>
      <div className="disclosure-content">{children}</div>
    </details>
  );
}

export function Tabs({
  id,
  items,
  value,
  onChange,
  label,
  className = "inline-tabs",
}: {
  id: string;
  items: { value: string; label: string }[];
  value: string;
  onChange: (value: string) => void;
  label: string;
  className?: string;
}) {
  return (
    <>
      <div className={className} role="tablist" aria-label={label}>
        {items.map((item, index) => (
          <button
            id={`${id}-tab-${item.value}`}
            key={item.value}
            type="button"
            role="tab"
            aria-selected={value === item.value}
            aria-controls={`${id}-panel-${item.value}`}
            tabIndex={value === item.value ? 0 : -1}
            className={value === item.value ? "active" : ""}
            onClick={() => onChange(item.value)}
            onKeyDown={(event) => {
              const next =
                event.key === "ArrowRight"
                  ? (index + 1) % items.length
                  : event.key === "ArrowLeft"
                    ? (index + items.length - 1) % items.length
                    : event.key === "Home"
                      ? 0
                      : event.key === "End"
                        ? items.length - 1
                        : null;
              if (next === null) return;
              event.preventDefault();
              onChange(items[next].value);
              event.currentTarget.parentElement
                ?.querySelectorAll<HTMLButtonElement>('[role="tab"]')
                [next]?.focus();
            }}
          >
            {item.label}
          </button>
        ))}
      </div>
      {items
        .filter((item) => item.value !== value)
        .map((item) => (
          <div
            key={item.value}
            hidden
            role="tabpanel"
            id={`${id}-panel-${item.value}`}
            aria-labelledby={`${id}-tab-${item.value}`}
          />
        ))}
    </>
  );
}

export function TabPanel({
  id,
  value,
  children,
  className = "",
}: {
  id: string;
  value: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div
      id={`${id}-panel-${value}`}
      role="tabpanel"
      aria-labelledby={`${id}-tab-${value}`}
      tabIndex={0}
      className={className}
    >
      {children}
    </div>
  );
}

// The native modal keeps the background inert and contains keyboard focus.
export function Dialog({
  open = true,
  onClose,
  label,
  labelledBy,
  initialFocus,
  returnFocus,
  className = "",
  id,
  children,
}: {
  open?: boolean;
  onClose: () => void;
  label?: string;
  labelledBy?: string;
  initialFocus?: string;
  returnFocus?: string;
  className?: string;
  id?: string;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useLayoutEffect(() => {
    const dialog = ref.current;
    if (!open || !dialog) return;
    const previous = document.activeElement;
    dialog.showModal();
    if (initialFocus) dialog.querySelector<HTMLElement>(initialFocus)?.focus();
    return () => {
      dialog.close();
      const target =
        previous instanceof HTMLElement &&
        previous !== document.body &&
        previous.isConnected
          ? previous
          : returnFocus
            ? document.querySelector<HTMLElement>(returnFocus)
            : null;
      target?.focus();
    };
  }, [open, initialFocus, returnFocus]);
  return createPortal(
    <dialog
      ref={ref}
      id={id}
      className={`ui-dialog ${className}`}
      aria-label={label}
      aria-labelledby={labelledBy}
      tabIndex={-1}
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClick={(event) => {
        if (event.target !== event.currentTarget) return;
        const bounds = event.currentTarget.getBoundingClientRect();
        if (
          event.clientX < bounds.left ||
          event.clientX > bounds.right ||
          event.clientY < bounds.top ||
          event.clientY > bounds.bottom
        )
          onClose();
      }}
    >
      {children}
    </dialog>,
    document.body,
  );
}

export function MetricStrip({
  items,
}: {
  items: { label: string; value: ReactNode; note?: string }[];
}) {
  return (
    <div className="metric-strip" data-count={items.length}>
      {items.map((item) => (
        <div className="metric-item" key={item.label}>
          <span>{item.label}</span>
          <strong>{item.value}</strong>
          {item.note && <small>{item.note}</small>}
        </div>
      ))}
    </div>
  );
}
