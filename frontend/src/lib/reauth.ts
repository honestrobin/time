// SPDX-License-Identifier: AGPL-3.0-only
// Sensitive actions answer 403 `reauth_required` when the sign-in isn't recent. The API client
// asks here for the password to be confirmed (the dialog in ReauthDialog), then retries.

type Opener = (done: (confirmed: boolean) => void) => void;

let opener: Opener | null = null;
let pending: Promise<boolean> | null = null;

/** Called by the dialog when it mounts; returns the cleanup. */
export function registerReauth(open: Opener): () => void {
  opener = open;
  return () => {
    if (opener === open) opener = null;
  };
}

/** Resolves true once the person confirmed who they are; parallel requests share one dialog. */
export function confirmIdentity(): Promise<boolean> {
  if (!opener) return Promise.resolve(false);
  const open = opener;
  pending ??= new Promise<boolean>((resolve) => open(resolve)).finally(() => {
    pending = null;
  });
  return pending;
}
