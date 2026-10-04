// SPDX-License-Identifier: AGPL-3.0-only
import face from "./robin/robin-face.svg";
import nest from "./robin/robin-nest.svg";
import notes from "./robin/robin-notes.svg";
import promise from "./robin/robin-promise.svg";
import time from "./robin/robin-time.svg";

const poses = { face, nest, notes, promise, time };

export type RobinPose = keyof typeof poses;

/**
 * The robin (docs/brand/mascot; its gen.py writes these copies). It keeps people company in empty
 * states, first runs and a running timer, and stays out of money, data and errors. It's
 * decoration: the words next to it say what matters, so it has no alt text.
 */
export function Robin({ pose, width, className }: { pose: RobinPose; width: number; className?: string }) {
  // The poses are drawn on 400 × 440, the face on a square.
  const height = pose === "face" ? width : Math.round((width * 440) / 400);
  return <img className={className ? `robin ${className}` : "robin"} src={poses[pose]} alt="" width={width} height={height} />;
}
