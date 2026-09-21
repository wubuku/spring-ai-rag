import {
  type CompositionEvent,
  type FormEvent,
  type FormEventHandler,
  type FormHTMLAttributes,
  type KeyboardEvent,
} from 'react';
import { useImeComposition } from '../../utils/ime';

type ImeSafeFormProps = Omit<
  FormHTMLAttributes<HTMLFormElement>,
  'onSubmit' | 'onKeyDown'
> & {
  onSubmit?: FormEventHandler<HTMLFormElement>;
  onKeyDown?: (event: KeyboardEvent<HTMLFormElement>) => void;
};

/**
 * Form boundary that prevents an IME-confirmation Enter from submitting.
 *
 * The keydown guard covers browsers that emit a submit immediately after the
 * composition key event. The submit guard covers browsers that do not expose
 * the composition state on the keyboard event.
 */
export function ImeSafeForm({
  onSubmit,
  onCompositionStart,
  onCompositionEnd,
  onKeyDown,
  ...props
}: ImeSafeFormProps) {
  const ime = useImeComposition();

  const handleCompositionStart = (event: CompositionEvent<HTMLFormElement>) => {
    ime.handleCompositionStart();
    onCompositionStart?.(event);
  };

  const handleCompositionEnd = (event: CompositionEvent<HTMLFormElement>) => {
    ime.handleCompositionEnd();
    onCompositionEnd?.(event);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLFormElement>) => {
    if ((event.key === 'Enter' || event.keyCode === 229)
        && ime.isComposing(event)) {
      event.preventDefault();
      return;
    }
    onKeyDown?.(event);
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    if (ime.isComposing(event)) {
      event.preventDefault();
      return;
    }
    onSubmit?.(event);
  };

  return (
    <form
      {...props}
      onCompositionStart={handleCompositionStart}
      onCompositionEnd={handleCompositionEnd}
      onKeyDown={handleKeyDown}
      onSubmit={handleSubmit}
    />
  );
}
