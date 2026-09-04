import { useEffect, useState } from 'react';

export interface ChartThemePalette {
  axisText: string;
  gridStroke: string;
  tooltipBackground: string;
  tooltipBorder: string;
  primary: string;
  success: string;
  warning: string;
}

// Recharts consumes concrete color strings (SVG presentation attributes do not
// resolve var()), so chart colors are resolved from the global design tokens
// here and re-read whenever the document theme flips.
function readPalette(): ChartThemePalette {
  const rootStyle = getComputedStyle(document.documentElement);
  const token = (name: string) => rootStyle.getPropertyValue(name).trim();
  return {
    axisText: token('--color-text-muted'),
    gridStroke: token('--color-border'),
    tooltipBackground: token('--color-bg'),
    tooltipBorder: token('--color-border'),
    primary: token('--color-primary'),
    success: token('--color-success'),
    warning: token('--color-warning'),
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
