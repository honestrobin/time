// SPDX-License-Identifier: AGPL-3.0-only
// Styles inside the button's shadow root: the page's CSS can't reach in, ours can't leak out.
export const STYLES = `
:host { all: initial; display: inline-block; position: relative; vertical-align: middle; margin: 0 8px; font: 500 13px/1.3 system-ui, -apple-system, "Segoe UI", sans-serif; color: #1c2b25; z-index: 2147483000; }
.track { display: inline-flex; align-items: center; gap: 6px; height: 28px; padding: 0 10px; border: 1px solid #7fa08c; border-radius: 6px; background: #ffffff; color: #1f5c45; font: inherit; cursor: pointer; white-space: nowrap; }
.track:hover { background: #dcebe2; }
.track:focus-visible { outline: 2px solid #2f6fd6; outline-offset: 2px; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: #1f5c45; }
.track.running { background: #1f5c45; color: #f3f5f0; border-color: #1f5c45; }
.track.running .dot { background: #c8322b; box-shadow: 0 0 0 2px #f3f5f0; animation: pulse 1.6s ease-in-out infinite; }
@keyframes pulse { 50% { opacity: .35; } }
@media (prefers-reduced-motion: reduce) { .track.running .dot { animation: none; } }
.panel { position: absolute; top: calc(100% + 6px); left: 0; width: 300px; padding: 14px; background: #ffffff; border: 1px solid #c5d6ca; border-radius: 8px; box-shadow: 0 6px 24px rgb(28 43 37 / .14), 0 1px 3px rgb(28 43 37 / .1); }
form { display: grid; gap: 10px; }
label { display: grid; gap: 4px; font-size: 12px; font-weight: 600; color: #4b5a53; }
select, input { font: 400 13px system-ui, sans-serif; color: #1c2b25; height: 30px; padding: 0 8px; border: 1px solid #7fa08c; border-radius: 4px; background: #ffffff; min-width: 0; }
select:focus, input:focus { outline: 2px solid #2f6fd6; outline-offset: 0; }
.actions { display: flex; justify-content: flex-end; gap: 8px; }
.actions button { height: 30px; padding: 0 12px; border-radius: 4px; border: 1px solid #7fa08c; background: #ffffff; color: #1c2b25; font: 600 13px system-ui, sans-serif; cursor: pointer; }
.actions button.primary { background: #1f5c45; border-color: #1f5c45; color: #f3f5f0; }
.actions button:disabled { opacity: .6; cursor: default; }
.error { margin: 0; color: #c8322b; font-size: 12px; min-height: 0; }
.error:empty { display: none; }
`;
