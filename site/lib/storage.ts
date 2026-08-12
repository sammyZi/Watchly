/**
 * AsyncStorage-shaped wrapper over localStorage, so the ported modules keep
 * their original async signatures. Everything degrades to "empty" and is
 * safe on the server (SSR) where there is no window.
 */
export async function getItem(key: string): Promise<string | null> {
  if (typeof window === 'undefined') return null;
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export async function setItem(key: string, value: string): Promise<void> {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Quota or private mode: the app must keep working without storage.
  }
}

export async function removeItem(key: string): Promise<void> {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.removeItem(key);
  } catch {
    // Ignore.
  }
}

export async function getAllKeys(): Promise<string[]> {
  if (typeof window === 'undefined') return [];
  try {
    return Object.keys(window.localStorage);
  } catch {
    return [];
  }
}

export async function multiRemove(keys: string[]): Promise<void> {
  if (typeof window === 'undefined') return;
  try {
    for (const key of keys) window.localStorage.removeItem(key);
  } catch {
    // Ignore.
  }
}