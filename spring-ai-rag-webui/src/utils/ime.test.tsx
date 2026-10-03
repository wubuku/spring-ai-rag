import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ImeSafeForm } from '../components/ImeSafeForm';
import { isImeComposing } from './ime';

describe('isImeComposing', () => {
  it('recognizes native composition, legacy keyCode 229, and explicit composition state', () => {
    expect(isImeComposing({
      nativeEvent: { isComposing: true },
    })).toBe(true);
    expect(isImeComposing({
      nativeEvent: { keyCode: 229 },
    })).toBe(true);
    expect(isImeComposing({ keyCode: 229 })).toBe(true);
    expect(isImeComposing({}, true)).toBe(true);
    expect(isImeComposing({})).toBe(false);
  });

  it('treats a null or missing event as not composing', () => {
    // 表单守卫在某些路径上会拿到空事件（例如 blur 之后组件卸载）；
    // 把它当成"正在组合"会让守卫永远不放行，而不是只在真的组合时拦截。
    expect(isImeComposing(null)).toBe(false);
    expect(isImeComposing(undefined)).toBe(false);
    expect(isImeComposing(null, true)).toBe(true);
  });
});

describe('ImeSafeForm', () => {
  const renderForm = (props: Record<string, unknown> = {}, children = (
    <input aria-label="query" />
  )) => render(
    <ImeSafeForm {...props}>
      {children}
      <button type="submit">Submit</button>
    </ImeSafeForm>,
  );

  it('blocks an IME confirmation key from submitting or invoking a form key handler', () => {
    const onSubmit = vi.fn(event => event.preventDefault());
    const onKeyDown = vi.fn();
    renderForm({ onSubmit, onKeyDown });

    const input = screen.getByRole('textbox', { name: 'query' });
    const form = input.closest('form')!;
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, {
      key: 'Enter',
      keyCode: 229,
      which: 229,
    });
    fireEvent.submit(form);
    expect(onSubmit).not.toHaveBeenCalled();
    expect(onKeyDown).not.toHaveBeenCalled();

    fireEvent.compositionEnd(input, { data: '中文' });
    fireEvent.submit(form);
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('lets a non-composing Enter reach the form key handler untouched', () => {
    // 只钉住"拦得住"是不够的：守卫一旦写反或过度拦截，正常的回车提交
    // 会被静默吞掉，而那条路径在上面的用例里根本不会出现。
    const onKeyDown = vi.fn();
    renderForm({ onKeyDown });

    const input = screen.getByRole('textbox', { name: 'query' });
    // fireEvent 返回 !defaultPrevented，可直接断言"没有被拦"。
    expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(true);
    expect(onKeyDown).toHaveBeenCalledTimes(1);
  });

  it('ignores ordinary keys while a composition is active', () => {
    // 组合过程中的其它按键必须照常到达调用方；只有确认键被拦。
    const onKeyDown = vi.fn();
    renderForm({ onKeyDown });

    const input = screen.getByRole('textbox', { name: 'query' });
    fireEvent.compositionStart(input);
    expect(fireEvent.keyDown(input, { key: 'a' })).toBe(true);
    expect(onKeyDown).toHaveBeenCalledTimes(1);

    expect(fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 })).toBe(false);
    expect(onKeyDown).toHaveBeenCalledTimes(1);
  });

  it('blocks a 229 confirmation key even when no composition event fired', () => {
    // 有的浏览器在键盘事件上只带 229，不发 compositionstart。此时 keydown
    // 守卫必须仍能认出这是确认键；而 submit 守卫不参与这条路径——它只看
    // compositionstart/end 维护的状态，真实浏览器也不会在确认键后提交表单。
    const onKeyDown = vi.fn();
    renderForm({ onKeyDown });

    const input = screen.getByRole('textbox', { name: 'query' });
    expect(fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 })).toBe(false);
    expect(onKeyDown).not.toHaveBeenCalled();

    expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(true);
    expect(onKeyDown).toHaveBeenCalledTimes(1);
  });

  it('forwards the caller composition handlers', () => {
    // 11 个调用点里有 4 个自己挂了 onCompositionEnd 来提交查询；
    // 守卫若吞掉这些回调，输入框会永远不提交。
    const onCompositionStart = vi.fn();
    const onCompositionEnd = vi.fn();
    renderForm({ onCompositionStart, onCompositionEnd });

    const input = screen.getByRole('textbox', { name: 'query' });
    fireEvent.compositionStart(input);
    expect(onCompositionStart).toHaveBeenCalledTimes(1);

    fireEvent.compositionEnd(input, { data: '中文' });
    expect(onCompositionEnd).toHaveBeenCalledTimes(1);
    expect(onCompositionEnd.mock.calls[0][0].data).toBe('中文');
  });

  it('forwards caller props to the underlying form element', () => {
    // 调用方普遍靠 className / id / aria-label 定位这个 form；
    // 展开位置一旦写反，样式与无障碍名称会一起消失。
    renderForm({ className: 'login-form', id: 'login', 'aria-label': 'Login' });

    const form = document.querySelector('form')!;
    expect(form).toHaveAttribute('id', 'login');
    expect(form).toHaveAttribute('class', 'login-form');
    expect(form).toHaveAttribute('aria-label', 'Login');
  });

  it('keeps the caller key handler working after the composition ends', () => {
    const onKeyDown = vi.fn();
    renderForm({ onKeyDown });

    const input = screen.getByRole('textbox', { name: 'query' });
    fireEvent.compositionStart(input);
    fireEvent.compositionEnd(input, { data: '中文' });

    expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(true);
    expect(onKeyDown).toHaveBeenCalledTimes(1);
  });
});
