// SPDX-License-Identifier: AGPL-3.0-only
// Help, one step from every page: a person first, then the guides and the keyboard shortcuts.
// The same in both editions.
import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { Button, Dialog, DialogActions, Kbd } from "../../design";

/** Where help goes. A person reads every message sent here. */
export const HELP_EMAIL = "hello@honestrobin.com";

const DOCS = "https://github.com/honestrobin/time/blob/main/docs";

interface HelpDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  shortcuts: { key: string; label: string }[];
  /** Only admins can export the account, so only they get the link. */
  canExport: boolean;
}

export function HelpDialog({ open, onOpenChange, shortcuts, canExport }: HelpDialogProps) {
  const { t } = useTranslation();
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={t("help.title")}>
      <div className="help stack">
        <section>
          <h3>{t("help.personTitle")}</h3>
          <p>{t("help.personLead")}</p>
          <p>
            <a className="help-email" href={`mailto:${HELP_EMAIL}`}>
              {HELP_EMAIL}
            </a>
          </p>
        </section>
        <section>
          <h3>{t("help.more")}</h3>
          <ul>
            <li>
              <a href={`${DOCS}/self-host.md`} target="_blank" rel="noreferrer">
                {t("help.selfHost")}
              </a>
            </li>
            <li>
              <a href={`${DOCS}/api.md`} target="_blank" rel="noreferrer">
                {t("help.api")}
              </a>
            </li>
            {canExport && (
              <li>
                <Link to="/settings/account" hash="export" onClick={() => onOpenChange(false)}>
                  {t("help.export")}
                </Link>
              </li>
            )}
          </ul>
        </section>
        <section>
          <h3>{t("shortcuts.title")}</h3>
          <table className="ledger">
            <tbody>
              {shortcuts.map((s) => (
                <tr key={s.key}>
                  <td style={{ width: 80 }}>
                    <Kbd>{s.key}</Kbd>
                  </td>
                  <td>{t(s.label)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
      <DialogActions>
        <Button onClick={() => onOpenChange(false)}>{t("app.close")}</Button>
      </DialogActions>
    </Dialog>
  );
}
