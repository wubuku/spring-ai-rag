import { useCallback, useRef } from 'react';

interface ImeNativeEvent {
  isComposing?: boolean;
  keyCode?: number;
}

export interface ImeEventLike {
  nativeEvent?: ImeNativeEvent | Event;
  key?: string;
  keyCode?: number;
}

/**
 * Detects keyboard events that belong to an active IME composition.
 *
 * Chromium normally exposes `isComposing`; some browsers and older event
 * paths expose only the legacy 229 key code. The explicit ref covers events
 * whose native event does not carry either signal.
 */
export function isImeComposing(
  event: ImeEventLike | null | undefined,
  compositionActive = false,
): boolean {
  const nativeEvent = event?.nativeEvent as ImeNativeEvent | undefined;
  return compositionActive
    || nativeEvent?.isComposing === true
    || nativeEvent?.keyCode === 229
    || event?.keyCode === 229;
}

export function useImeComposition() {
  const compositionActiveRef = useRef(false);

  const handleCompositionStart = useCallback(() => {
    compositionActiveRef.current = true;
  }, []);

  const handleCompositionEnd = useCallback(() => {
    compositionActiveRef.current = false;
  }, []);

  const isComposing = useCallback((event?: ImeEventLike | null) => (
    isImeComposing(event, compositionActiveRef.current)
  ), []);

  return {
    compositionActiveRef,
    handleCompositionStart,
    handleCompositionEnd,
    isComposing,
  };
}
