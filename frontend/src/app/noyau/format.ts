/**
 * Les formats d'affichage imposés par le § 8 des spécifications : sommes en francs CFA
 * sans décimale et séparateur de milliers, dates en jour/mois/année.
 */

const entier = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 });
const decimal = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const souple = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 });

/** « 375 000 FCFA ». */
export function fcfa(valeur: number | null | undefined): string {
  if (valeur === null || valeur === undefined || !Number.isFinite(Number(valeur))) return '-';
  return entier.format(Number(valeur)) + ' FCFA';
}

/** « 375 000 FCFA / mois » : le format du loyer sur le site (§ 3.2). */
export function loyerMensuel(valeur: number | null | undefined): string {
  const somme = fcfa(valeur);
  return somme === '-' ? somme : somme + ' / mois';
}

/** « 85 m² ». */
export function surface(valeur: number | null | undefined): string {
  if (valeur === null || valeur === undefined || !Number.isFinite(Number(valeur))) return '-';
  return entier.format(Number(valeur)) + ' m²';
}

/** « 92,5 % ». */
export function pourcentage(valeur: number | null | undefined): string {
  if (valeur === null || valeur === undefined || !Number.isFinite(Number(valeur))) return '-';
  return souple.format(Number(valeur)) + ' %';
}

/** « 12/11/2026 » depuis une date ISO. */
export function date(v: string | null | undefined): string {
  if (!v) return '-';
  const [a, m, j] = v.slice(0, 10).split('-');
  return j && m && a ? `${j}/${m}/${a}` : v;
}

/** « 12/11/2026 à 09:45 » depuis un instant ISO. */
export function dateHeure(v: string | null | undefined): string {
  if (!v) return '-';
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return v;
  const deux = (n: number) => String(n).padStart(2, '0');
  return `${deux(d.getDate())}/${deux(d.getMonth() + 1)}/${d.getFullYear()} à ${deux(d.getHours())}:${deux(d.getMinutes())}`;
}

/** Un nombre avec son séparateur de milliers. */
export function nombre(valeur: number | null | undefined): string {
  if (valeur === null || valeur === undefined || !Number.isFinite(Number(valeur))) return '-';
  return entier.format(Number(valeur));
}

/** Un nombre à une décimale. */
export function nombreDecimal(valeur: number | null | undefined): string {
  if (valeur === null || valeur === undefined || !Number.isFinite(Number(valeur))) return '-';
  return decimal.format(Number(valeur));
}

/** Les coordonnées GPS telles qu'on les relit sur le terrain : « 5.35160, -3.98690 ». */
export function coordonnees(latitude: number | null | undefined, longitude: number | null | undefined): string {
  if (latitude === null || latitude === undefined || longitude === null || longitude === undefined) return '-';
  return `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`;
}
