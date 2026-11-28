import { HttpOperatorClient } from './httpOperatorClient';
import { mockOperatorRepository } from './mockRepository';
import type { OperatorClient } from './operatorContract';

const rawMode = (import.meta.env.VITE_OPERATOR_CLIENT ?? 'mock').toLowerCase();

export const operatorClient: OperatorClient =
  rawMode === 'real' ? new HttpOperatorClient() : mockOperatorRepository;
