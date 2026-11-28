import { useState } from 'react';
import { Bug, RotateCcw } from 'lucide-react';
import { mockOperatorRepository } from '../domain/mockRepository';
import type { OperatorErrorCode } from '../domain/operatorContract';
import { Button } from '../ui/primitives';

const FAULTS: Array<{ label: string; code: OperatorErrorCode | null }> = [
  { label: '正常（无故障）', code: null },
  { label: '401 UNAUTHORIZED', code: 'UNAUTHORIZED' },
  { label: '403 FORBIDDEN', code: 'FORBIDDEN' },
  { label: '403 CSRF_REQUIRED', code: 'CSRF_REQUIRED' },
  { label: '409 TENANT_CONFLICT', code: 'TENANT_CONFLICT' },
];

export function DevPanel({ onRefresh }: { onRefresh: () => void }) {
  const [cfg, setCfg] = useState(() => mockOperatorRepository.getFaultConfig());

  return (
    <div
      data-testid="dev-panel"
      className="absolute top-12 right-3 z-40 w-[300px] rounded-[6px] border border-[var(--border-strong)] bg-[var(--card)] p-3.5 text-xs shadow-sm"
    >
      <div className="flex items-center justify-between border-b border-[var(--border)] pb-2">
        <span className="flex items-center gap-1.5 font-semibold text-[var(--foreground)]">
          <Bug className="h-3.5 w-3.5" />
          Mock 负向开关（仅原型模式）
        </span>
      </div>
      <div className="mt-2.5 space-y-2">
        <label className="block text-[11px] text-[var(--muted-foreground)]">强制返回错误码</label>
        <select
          data-testid="dev-fault-select"
          value={cfg.forcedError ?? ''}
          onChange={(e) => {
            const next = (e.target.value || null) as OperatorErrorCode | null;
            mockOperatorRepository.setFaultConfig({ forcedError: next });
            setCfg(mockOperatorRepository.getFaultConfig());
            onRefresh();
          }}
          className="w-full rounded-[4px] border border-[var(--border-strong)] bg-[var(--background)] px-2 py-1 text-xs"
        >
          {FAULTS.map((f) => (
            <option key={f.label} value={f.code ?? ''}>
              {f.label}
            </option>
          ))}
        </select>
        <label className="flex items-center gap-2 text-[11px] text-[var(--foreground)]">
          <input
            type="checkbox"
            checked={cfg.delayMs > 0}
            onChange={(e) => {
              mockOperatorRepository.setFaultConfig({ delayMs: e.target.checked ? 300 : 0 });
              setCfg(mockOperatorRepository.getFaultConfig());
            }}
          />
          模拟 300ms 网络延迟
        </label>
        <div className="pt-1">
          <Button
            size="sm"
            onClick={() => {
              mockOperatorRepository.reset();
              setCfg(mockOperatorRepository.getFaultConfig());
              onRefresh();
            }}
          >
            <RotateCcw className="h-3 w-3" />
            重置全部 Fixture 数据
          </Button>
        </div>
      </div>
    </div>
  );
}
