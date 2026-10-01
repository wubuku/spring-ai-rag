import { useEffect, useState } from 'react';
import { resolveTokenValues } from '../design-system/tokens.generated';
import type { DesignTokenKey } from '../design-system/tokens.generated';

export interface ChartThemePalette {
  axisText: string;
  gridStroke: string;
  tooltipBackground: string;
  tooltipBorder: string;
  primary: string;
  success: string;
  warning: string;
  /** Ordered categorical ramp for multi-series charts. */
  category: string[];
}

// Recharts consumes concrete colour strings (SVG presentation attributes do not
// resolve var()), so the palette is read from the *live* theme through the
// generated token bridge. Re-read whenever the document theme flips, so charts
// follow a theme change without remounting. Series semantics are unchanged:
// primary/success/warning keep mapping to the same blue/green/amber roles.
const CHART_KEYS = [
  'chart-axis',
  'chart-grid',
  'chart-tooltip-surface',
  'chart-tooltip-border',
  'chart-category-1',
  'chart-category-2',
  'chart-category-3',
  'status-success',
  'status-warning',
] as const satisfies readonly DesignTokenKey[];

function readPalette(): ChartThemePalette {
  const tokens = resolveTokenValues(CHART_KEYS);
  return {
    axisText: tokens['chart-axis'],
    gridStroke: tokens['chart-grid'],
    tooltipBackground: tokens['chart-tooltip-surface'],
    tooltipBorder: tokens['chart-tooltip-border'],
    primary: tokens['chart-category-1'],
    success: tokens['status-success'],
    warning: tokens['status-warning'],
    category: [
      tokens['chart-category-1'],
      tokens['chart-category-2'],
      tokens['chart-category-3'],
    ],
  };
}

export function useChartTheme(): ChartThemePalette {
  const [palette, setPalette] = useState<ChartThemePalette>(readPalette);

  useEffect(() => {
    const observer = new MutationObserver(() => setPalette(readPalette()));
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    });
    return () => observer.disconnect();
  }, []);

  return palette;
}
