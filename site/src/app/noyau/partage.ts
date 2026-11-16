/**
 * Partage d'un bien : la feuille de partage du téléphone quand elle existe, sinon le lien est
 * copié. Rend le texte à afficher au visiteur, ou null s'il a simplement fermé la feuille.
 */
export async function partager(titre: string, texte: string, url = location.href): Promise<string | null> {
  const nav = navigator as Navigator & { share?: (d: ShareData) => Promise<void> };
  if (nav.share) {
    try {
      await nav.share({ title: titre, text: texte, url });
      return null;
    } catch (e) {
      if ((e as DOMException)?.name === 'AbortError') return null;
    }
  }
  try {
    await navigator.clipboard.writeText(url);
    return 'Lien copié : collez-le dans un message pour le partager.';
  } catch {
    return 'Copiez l’adresse de la page pour la partager : ' + url;
  }
}
