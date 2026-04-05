import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { Skeleton, SkeletonText, SkeletonCard, SkeletonTable } from './Skeleton';

describe('Skeleton', () => {
  it('renders skeleton with default styles', () => {
    const { container } = render(<Skeleton />);
    const skeleton = container.querySelector('[class*="skeleton"]');
    expect(skeleton).toBeInTheDocument();
  });

  it('renders skeleton with custom dimensions', () => {
    const { container } = render(<Skeleton width="200px" height="50px" />);
    const skeleton = container.querySelector('[class*="skeleton"]');
    expect(skeleton).toBeInTheDocument();
    expect(skeleton).toHaveStyle({ width: '200px', height: '50px' });
  });

  it('renders skeleton with custom border radius', () => {
    const { container } = render(<Skeleton borderRadius="50%" />);
    const skeleton = container.querySelector('[class*="skeleton"]');
    expect(skeleton).toBeInTheDocument();
    expect(skeleton).toHaveStyle({ borderRadius: '50%' });
  });

  it('SkeletonText renders multiple lines', () => {
    const { container } = render(<SkeletonText lines={3} />);
    const skeletons = container.querySelectorAll('[class*="skeleton"]');
    expect(skeletons.length).toBe(3);
  });

  it('SkeletonText last line has shorter width', () => {
    const { container } = render(<SkeletonText lines={3} lastLineWidth="40%" />);
    const skeletons = container.querySelectorAll('[class*="skeleton"]');
    // Last skeleton should have width 40%
    const lastSkeleton = skeletons[skeletons.length - 1];
    expect(lastSkeleton).toHaveStyle({ width: '40%' });
  });

  it('SkeletonCard renders multiple skeleton elements', () => {
    const { container } = render(<SkeletonCard />);
    const skeletons = container.querySelectorAll('[class*="skeleton"]');
    expect(skeletons.length).toBeGreaterThan(0);
  });

  it('SkeletonTable renders header and rows', () => {
    const { container } = render(<SkeletonTable rows={3} />);
    const skeletons = container.querySelectorAll('[class*="skeleton"]');
    expect(skeletons.length).toBeGreaterThan(3); // header + 3 rows
  });
});
