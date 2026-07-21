const SELECTED_MODEL_STORAGE_KEY = 'rag-selected-model';

export function getSelectedModel(): string {
  return localStorage.getItem(SELECTED_MODEL_STORAGE_KEY) ?? '';
}

export function saveSelectedModel(modelRef: string): void {
  if (modelRef) {
    localStorage.setItem(SELECTED_MODEL_STORAGE_KEY, modelRef);
  } else {
    localStorage.removeItem(SELECTED_MODEL_STORAGE_KEY);
  }
}
