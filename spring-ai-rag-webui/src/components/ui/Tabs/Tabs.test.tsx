import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Tabs, tabDomIds, type TabItem } from './Tabs';

const ITEMS: TabItem[] = [
  { id: 'one', label: 'First', render: () => <p>Panel one</p> },
  { id: 'two', label: 'Second', render: () => <p>Panel two</p> },
  { id: 'three', label: 'Third', render: () => <p>Panel three</p> },
];

function renderTabs(props: Partial<React.ComponentProps<typeof Tabs>> = {}) {
  const onChange = props.onChange ?? vi.fn();
  const view = render(
    <Tabs
      items={ITEMS}
      activeId="one"
      onChange={onChange}
      ariaLabel="Example tabs"
      idPrefix="example"
      {...props}
    />,
  );
  return { ...view, onChange };
}

describe('Tabs', () => {
  it('exposes a named tablist', () => {
    renderTabs();
    expect(screen.getByRole('tablist', { name: 'Example tabs' })).toBeInTheDocument();
  });

  it('renders one tab per item and marks the active one selected', () => {
    renderTabs();
    const tabs = screen.getAllByRole('tab');
    expect(tabs).toHaveLength(3);
    expect(screen.getByRole('tab', { name: 'First' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Second' })).toHaveAttribute('aria-selected', 'false');
  });

  it('renders only the active panel', () => {
    renderTabs();
    expect(screen.getByText('Panel one')).toBeInTheDocument();
    expect(screen.queryByText('Panel two')).not.toBeInTheDocument();
  });

  it('links each tab to its panel in both directions', () => {
    renderTabs({ activeId: 'two' });
    const tab = screen.getByRole('tab', { name: 'Second' });
    const panel = screen.getByRole('tabpanel');

    expect(tab).toHaveAttribute('aria-controls', panel.id);
    expect(panel).toHaveAttribute('aria-labelledby', tab.id);
  });

  it('keeps inactive tabs out of the tab order with a roving tabindex', () => {
    renderTabs();
    expect(screen.getByRole('tab', { name: 'First' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('tab', { name: 'Second' })).toHaveAttribute('tabindex', '-1');
  });

  it('reports selection changes instead of owning the active id', () => {
    const { onChange } = renderTabs();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('commits a change on click', async () => {
    const user = userEvent.setup();
    const { onChange } = renderTabs();

    await user.click(screen.getByRole('tab', { name: 'Third' }));

    expect(onChange).toHaveBeenCalledWith('three');
  });

  it('moves to the next tab with ArrowRight and wraps around', async () => {
    const user = userEvent.setup();
    const { onChange } = renderTabs();

    await user.tab();
    expect(screen.getByRole('tab', { name: 'First' })).toHaveFocus();

    await user.keyboard('{ArrowRight}');
    expect(onChange).toHaveBeenCalledWith('two');

    await user.keyboard('{ArrowRight}{ArrowRight}');
    expect(onChange).toHaveBeenLastCalledWith('one');
  });

  it('moves to the previous tab with ArrowLeft and wraps around', async () => {
    const user = userEvent.setup();
    const { onChange } = renderTabs();

    await user.tab();
    await user.keyboard('{ArrowLeft}');

    expect(onChange).toHaveBeenLastCalledWith('three');
  });

  it('jumps to the first and last tab with Home and End', async () => {
    const user = userEvent.setup();
    const { onChange } = renderTabs();

    await user.tab();
    await user.keyboard('{End}');
    expect(onChange).toHaveBeenLastCalledWith('three');

    await user.keyboard('{Home}');
    expect(onChange).toHaveBeenLastCalledWith('one');
  });

  it('does not move focus on keys it does not own', async () => {
    const user = userEvent.setup();
    const { onChange } = renderTabs();

    await user.tab();
    await user.keyboard('{ArrowUp}');

    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole('tab', { name: 'First' })).toHaveFocus();
  });

  it('shows the panel for whichever tab the page marks active', () => {
    const { rerender } = renderTabs();
    rerender(
      <Tabs
        items={ITEMS}
        activeId="three"
        onChange={vi.fn()}
        ariaLabel="Example tabs"
        idPrefix="example"
      />,
    );
    expect(screen.getByText('Panel three')).toBeInTheDocument();
  });

  it('renders no panel when the active id matches nothing', () => {
    renderTabs({ activeId: 'missing' });
    expect(screen.getByRole('tablist')).toBeInTheDocument();
    expect(screen.queryByRole('tabpanel')).not.toBeInTheDocument();
  });

  it('renders no panel when the page owns it, but still points at the panel id', () => {
    const items: TabItem[] = [
      { id: 'one', label: 'First' },
      { id: 'two', label: 'Second' },
    ];
    render(
      <Tabs
        items={items}
        activeId="one"
        onChange={vi.fn()}
        ariaLabel="Example tabs"
        idPrefix="owned"
      />,
    );
    expect(screen.queryByRole('tabpanel')).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'First' })).toHaveAttribute(
      'aria-controls',
      tabDomIds('owned', 'one').panelId,
    );
  });

  it('exposes the ids a page needs to wire its own panel', () => {
    expect(tabDomIds('settings', 'cache')).toEqual({
      tabId: 'settings-tab-cache',
      panelId: 'settings-panel-cache',
    });
  });

  it('keeps ids stable across re-renders', () => {
    const { rerender } = renderTabs();
    const before = screen.getByRole('tab', { name: 'First' }).id;
    rerender(
      <Tabs
        items={ITEMS}
        activeId="two"
        onChange={vi.fn()}
        ariaLabel="Example tabs"
        idPrefix="example"
      />,
    );
    expect(screen.getByRole('tab', { name: 'First' }).id).toBe(before);
  });
});
