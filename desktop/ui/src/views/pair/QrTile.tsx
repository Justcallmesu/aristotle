import { useI18n } from "../../i18n";
import { QUIET_ZONE } from "./qr";

/** The server encodes the QR (P19); this only draws its modules as rects with a 4-module quiet zone. */
export function QrTile({ rows }: { rows: string[] }) {
  const { t } = useI18n();
  const width = rows[0].length;
  const size = Math.max(width, rows.length) + QUIET_ZONE * 2;
  const rects: { x: number; y: number }[] = [];
  rows.forEach((row, y) => {
    for (let x = 0; x < row.length; x++) if (row[x] === "1") rects.push({ x: x + QUIET_ZONE, y: y + QUIET_ZONE });
  });
  return (
    <div className="qr-tile" role="img" aria-label={t("qr_label")}>
      <svg viewBox={`0 0 ${size} ${size}`} aria-hidden="true" focusable="false">
        {rects.map(({ x, y }) => (
          <rect key={`${x},${y}`} x={x} y={y} width="1" height="1" />
        ))}
      </svg>
    </div>
  );
}
