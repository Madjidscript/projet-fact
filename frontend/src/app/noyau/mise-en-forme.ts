/**
 * Met en forme une réponse de l'assistant. Le modèle écrit en Markdown simple
 * (**gras**, listes « - » ou « 1. », `code`, titres « ### ») : on le transforme en HTML.
 *
 * SÉCURITÉ : on échappe TOUT le texte d'abord, puis on n'ajoute que nos propres balises.
 * Un texte venu du modèle (ou d'une donnée qu'il a lue) ne peut donc jamais injecter
 * du HTML ou un script dans la page.
 */
export function miseEnForme(texte: string): string {
  const lignes = echapper(texte.trim()).split(/\r?\n/);
  const html: string[] = [];
  let liste: 'ul' | 'ol' | null = null;

  const fermerListe = () => {
    if (liste) {
      html.push(`</${liste}>`);
      liste = null;
    }
  };

  for (const brute of lignes) {
    const ligne = brute.trim();
    const puce = /^[-*•]\s+(.*)$/.exec(ligne);
    const numero = /^\d+[.)]\s+(.*)$/.exec(ligne);
    const titre = /^#{1,6}\s+(.*)$/.exec(ligne);

    if (puce || numero) {
      const type = puce ? 'ul' : 'ol';
      if (liste !== type) {
        fermerListe();
        html.push(`<${type}>`);
        liste = type;
      }
      html.push(`<li>${enLigne((puce ?? numero)![1])}</li>`);
    } else if (!ligne) {
      fermerListe();
    } else if (titre) {
      fermerListe();
      html.push(`<p class="titre-bulle">${enLigne(titre[1])}</p>`);
    } else {
      fermerListe();
      html.push(`<p>${enLigne(ligne)}</p>`);
    }
  }
  fermerListe();
  return html.join('');
}

/** Gras, italique et code à l'intérieur d'une ligne (le texte est déjà échappé). */
function enLigne(texte: string): string {
  return texte
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\s][^*]*)\*/g, '$1<em>$2</em>');
}

function echapper(texte: string): string {
  return texte.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}
