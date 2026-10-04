// SPDX-License-Identifier: AGPL-3.0-only
// When this server can't send email, an admin gets the invitation link to pass on by hand. The
// link is a way in for whoever holds it, so the dialog says that, and when it stops working.
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, useToast } from "../../design";
import { api, unwrap } from "../../lib/api";
import { formatDate } from "../../lib/format";

export interface InviteLink {
  name: string;
  url: string;
  expiresAt: string;
}

/** A new invitation link for the person; it retires any earlier one. */
export async function fetchInviteLink(id: string, name: string): Promise<InviteLink> {
  const res = await unwrap(api.POST("/api/v1/people/{id}/invite_link", { params: { path: { id } } }));
  return { name, url: res.url, expiresAt: res.expires_at };
}

export function InviteLinkDialog({ link, onClose }: { link: InviteLink | null; onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [field, setField] = useState<HTMLInputElement | null>(null);
  const copy = async () => {
    if (!link) return;
    try {
      await navigator.clipboard.writeText(link.url);
      toast(t("team.linkCopied"));
    } catch {
      // Some browsers refuse the clipboard: select the link so it can be copied by hand.
      field?.select();
    }
  };
  return (
    <Dialog open={link !== null} onOpenChange={(o) => !o && onClose()} title={link ? t("team.linkTitle", { name: link.name }) : ""}>
      {link && (
        <div className="stack">
          <p>{t("team.linkBody", { name: link.name, date: formatDate(link.expiresAt.slice(0, 10)) })}</p>
          <input ref={setField} className="input" readOnly value={link.url} aria-label={t("team.linkLabel")} onFocus={(e) => e.currentTarget.select()} />
          <DialogActions>
            <Button onClick={onClose}>{t("app.close")}</Button>
            <Button variant="primary" onClick={() => void copy()}>
              {t("team.copyLink")}
            </Button>
          </DialogActions>
        </div>
      )}
    </Dialog>
  );
}
