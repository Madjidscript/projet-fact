package com.eburtis.fact.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.eburtis.fact.repository.LocationRepository;
import com.eburtis.fact.utils.MontantEnLettres;
import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfGState;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import org.springframework.stereotype.Service;

/**
 * La quittance d'un encaissement, en PDF (§ 4.4). Elle dit qui a payé, pour quel logement, quelle
 * somme en chiffres et en lettres, et quelles périodes ce paiement solde. Un paiement qui ne
 * solde pas entièrement ses périodes donne un « reçu de paiement » : une quittance atteste un
 * loyer payé en entier. Un encaissement annulé porte la mention ANNULÉ.
 */
@Service
public class QuittanceService {

    /** Les périodes qu'un paiement règle, et pour combien. */
    public record Periode(LocalDate mois, long du, long regle, long reste) {
    }

    private static final Color NUIT = new Color(0x1A, 0x3F, 0x7A);
    private static final Color OCRE = new Color(0x23, 0x53, 0xA6);   // bleu roi du logo (nom gardé)
    private static final Color GRIS = new Color(0x6C, 0x75, 0x83);
    private static final Color FILET = new Color(0xE2, 0xE5, 0xEB);
    private static final Color FOND = new Color(0xF5, 0xF6, 0xF8);
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MOIS = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE);

    private final LocationRepository locationRepository;

    public QuittanceService(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    /**
     * Les périodes soldées par un encaissement : les paiements d'un bail règlent ses échéances de la
     * plus ancienne à la plus récente ; celui-ci prend la suite de ceux qui le précèdent.
     */
    public List<Periode> periodes(int idBail, long idEncaissement, long montant) {
        long debut = locationRepository.payeAvant(idBail, idEncaissement);
        long fin = debut + montant;
        List<Periode> r = new ArrayList<>();
        long cumul = 0;
        for (long[] e : locationRepository.echeancesBrutes(idBail)) {
            long deb = cumul, du = e[1];
            cumul += du;
            long part = Math.max(0, Math.min(cumul, fin) - Math.max(deb, debut));
            if (part > 0) {
                LocalDate mois = Instant.ofEpochSecond(e[0]).atZone(ZoneId.of("UTC")).toLocalDate();
                long dejaRegle = Math.max(0, Math.min(cumul, debut) - deb);
                r.add(new Periode(mois, du, part, du - dejaRegle - part));
            }
        }
        return r;
    }

    public byte[] pdf(JsonNode q, List<Periode> periodes, String editePar) {
        long montant = q.get("montant").asLong();
        long avance = montant - periodes.stream().mapToLong(Periode::regle).sum();
        boolean complet = !periodes.isEmpty() && periodes.stream().allMatch(p -> p.reste() == 0);
        boolean annule = !q.get("annuleLe").isNull();
        String titre = complet ? "QUITTANCE DE LOYER" : "REÇU DE PAIEMENT";

        try (ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 48, 48, 44, 54);
            PdfWriter ecrivain = PdfWriter.getInstance(doc, sortie);
            doc.addTitle(titre + " " + texte(q, "numero"));
            doc.addAuthor("FACT Immobilier");
            doc.open();
            Font titreF = police(BaseFont.HELVETICA_BOLD, 17, NUIT);
            Font gras = police(BaseFont.HELVETICA_BOLD, 10, Color.BLACK);
            Font normal = police(BaseFont.HELVETICA, 10, Color.BLACK);
            Font discret = police(BaseFont.HELVETICA, 8.5f, GRIS);
            Font marque = police(BaseFont.HELVETICA_BOLD, 15, NUIT);

            // ------------------------------------------------------------- en-tête
            PdfPTable entete = new PdfPTable(new float[]{1.3f, 1f});
            entete.setWidthPercentage(100);
            PdfPCell agence = cellule(null);
            Paragraph nom = new Paragraph();
            nom.add(new Chunk("FACT ", police(BaseFont.HELVETICA_BOLD, 15, OCRE)));
            nom.add(new Chunk("Immobilier", marque));
            agence.addElement(nom);
            agence.addElement(new Paragraph("Agence immobilière agréée · membre de la CDAIM", discret));
            agence.addElement(new Paragraph("Cocody Riviera 3, Cité Allabra, Abidjan", discret));
            agence.addElement(new Paragraph("27 22 46 91 38 · 05 55 43 55 55 · info@factimmobilier-ci.com", discret));
            entete.addCell(agence);
            PdfPCell numero = cellule(null);
            numero.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Paragraph t = new Paragraph(titre, titreF);
            t.setAlignment(Element.ALIGN_RIGHT);
            numero.addElement(t);
            Paragraph n = new Paragraph("N° " + texte(q, "numero"), gras);
            n.setAlignment(Element.ALIGN_RIGHT);
            numero.addElement(n);
            Paragraph d = new Paragraph("Payé le " + JOUR.format(LocalDate.parse(texte(q, "datePaiement"))), normal);
            d.setAlignment(Element.ALIGN_RIGHT);
            numero.addElement(d);
            entete.addCell(numero);
            doc.add(entete);
            doc.add(trait(OCRE, 2f, 10, 14));

            // ------------------------------------------------------ parties et logement
            PdfPTable parties = new PdfPTable(2);
            parties.setWidthPercentage(100);
            parties.setSpacingAfter(14);
            parties.addCell(encadre("LOCATAIRE", texte(q, "occupant"), "Réf. " + texte(q, "referenceOccupant") + " · bail " + texte(q, "referenceBail"), gras, normal, discret));
            // « lot Villa (Villa) » : le type n'est rappelé que s'il dit autre chose que le numéro.
            boolean typeUtile = q.hasNonNull("libelleTypeLot") && !texte(q, "libelleTypeLot").equalsIgnoreCase(texte(q, "numeroLot"));
            String lieu = texte(q, "batiment") + " — lot " + texte(q, "numeroLot") + (typeUtile ? " (" + texte(q, "libelleTypeLot") + ")" : "");
            String ou = (q.hasNonNull("quartier") ? texte(q, "quartier") + ", " : "") + texte(q, "commune")
                    + (q.hasNonNull("adresse") ? " · " + texte(q, "adresse") : "");
            parties.addCell(encadre("LOGEMENT", lieu, ou, gras, normal, discret));
            doc.add(parties);

            // -------------------------------------------------------------- la somme
            Paragraph somme = new Paragraph();
            somme.setLeading(16);
            somme.add(new Chunk(elision(texte(q, "occupant")) ? "Reçu d'" : "Reçu de ", normal));
            somme.add(new Chunk(texte(q, "occupant"), gras));
            somme.add(new Chunk(" la somme de ", normal));
            somme.add(new Chunk(fcfa(montant), gras));
            somme.add(new Chunk(" (" + MontantEnLettres.ecrire(montant) + " francs CFA)", normal));
            somme.add(new Chunk(complet ? ", en paiement du loyer et des charges des périodes ci-dessous, dont quittance." : ", en paiement partiel du loyer et des charges des périodes ci-dessous.", normal));
            somme.setSpacingAfter(12);
            doc.add(somme);

            // ------------------------------------------------------------ les périodes
            PdfPTable tableau = new PdfPTable(new float[]{1.6f, 1f, 1.2f, 1f});
            tableau.setWidthPercentage(100);
            for (String c : new String[]{"Période", "Loyer et charges", "Réglé par ce paiement", "Reste dû"}) {
                PdfPCell h = new PdfPCell(new Phrase(c, police(BaseFont.HELVETICA_BOLD, 9, Color.WHITE)));
                h.setBackgroundColor(NUIT);
                h.setPadding(6);
                h.setBorderColor(NUIT);
                h.setHorizontalAlignment(c.equals("Période") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
                tableau.addCell(h);
            }
            for (Periode p : periodes) {
                tableau.addCell(ligne(capitale(MOIS.format(p.mois())), normal, Element.ALIGN_LEFT));
                tableau.addCell(ligne(fcfa(p.du()), normal, Element.ALIGN_RIGHT));
                tableau.addCell(ligne(fcfa(p.regle()), gras, Element.ALIGN_RIGHT));
                tableau.addCell(ligne(p.reste() == 0 ? "Soldé" : fcfa(p.reste()), normal, Element.ALIGN_RIGHT));
            }
            if (avance > 0) {
                tableau.addCell(ligne("Avance sur les mois à venir", normal, Element.ALIGN_LEFT));
                tableau.addCell(ligne("", normal, Element.ALIGN_RIGHT));
                tableau.addCell(ligne(fcfa(avance), gras, Element.ALIGN_RIGHT));
                tableau.addCell(ligne("", normal, Element.ALIGN_RIGHT));
            }
            tableau.setSpacingAfter(14);
            doc.add(tableau);

            // --------------------------------------------------------- règlement
            PdfPTable details = new PdfPTable(new float[]{1f, 1.6f});
            details.setWidthPercentage(60);
            details.setHorizontalAlignment(Element.ALIGN_LEFT);
            detail(details, "Mode de paiement", texte(q, "libelleMode"), discret, normal);
            if (q.hasNonNull("referencePaiement")) {
                detail(details, "Référence", texte(q, "referencePaiement"), discret, normal);
            }
            long solde = q.get("solde").asLong();
            detail(details, "Solde du compte à ce jour", solde > 0 ? fcfa(solde) + " restant dû" : solde < 0 ? fcfa(-solde) + " d'avance" : "À jour", discret, gras);
            detail(details, "Propriétaire (bailleur)", texte(q, "proprietaire"), discret, normal);
            doc.add(details);

            Paragraph signature = new Paragraph("Pour FACT Immobilier, le gestionnaire", normal);
            signature.setAlignment(Element.ALIGN_RIGHT);
            signature.setSpacingBefore(28);
            doc.add(signature);
            Paragraph pied = new Paragraph("Document édité le " + JOUR.format(LocalDate.now()) + " par " + editePar
                    + ". Saisi par " + texte(q, "creePar") + ". Cette quittance annule tout reçu remis pour les mêmes sommes.", discret);
            pied.setSpacingBefore(42);
            doc.add(pied);

            if (annule) {
                filigrane(ecrivain, "ANNULÉ");
                Paragraph motif = new Paragraph("Encaissement annulé : " + texte(q, "motifAnnulation"), police(BaseFont.HELVETICA_BOLD, 10, new Color(0xD6, 0x45, 0x45)));
                motif.setSpacingBefore(10);
                doc.add(motif);
            }
            doc.close();
            return sortie.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Quittance impossible à produire", e);
        }
    }

    // ------------------------------------------------------------------ mise en page
    private static Font police(String nom, float taille, Color couleur) {
        try {
            return new Font(BaseFont.createFont(nom, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED), taille, Font.NORMAL, couleur);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PdfPCell cellule(Phrase p) {
        PdfPCell c = p == null ? new PdfPCell() : new PdfPCell(p);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    private static PdfPCell encadre(String titre, String principal, String second, Font gras, Font normal, Font discret) {
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(FOND);
        c.setBorderColor(FILET);
        c.setPadding(10);
        c.addElement(new Paragraph(titre, discret));
        Paragraph p = new Paragraph(principal, gras);
        p.setSpacingBefore(2);
        c.addElement(p);
        c.addElement(new Paragraph(second, normal));
        return c;
    }

    private static PdfPCell ligne(String texte, Font f, int alignement) {
        PdfPCell c = new PdfPCell(new Phrase(texte, f));
        c.setPadding(6);
        c.setBorderColor(FILET);
        c.setHorizontalAlignment(alignement);
        return c;
    }

    private static void detail(PdfPTable t, String libelle, String valeur, Font fl, Font fv) {
        PdfPCell l = cellule(new Phrase(libelle, fl));
        l.setPaddingBottom(5);
        t.addCell(l);
        PdfPCell v = cellule(new Phrase(valeur, fv));
        v.setPaddingBottom(5);
        t.addCell(v);
    }

    private static Paragraph trait(Color couleur, float epaisseur, float avant, float apres) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingBefore(avant - 10);
        p.setSpacingAfter(apres);
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderWidthBottom(epaisseur);
        c.setBorderColorBottom(couleur);
        c.setFixedHeight(2);
        t.addCell(c);
        p.add(t);
        return p;
    }

    private static void filigrane(PdfWriter ecrivain, String texte) {
        PdfContentByte dessus = ecrivain.getDirectContent();
        dessus.saveState();
        PdfGState g = new PdfGState();
        g.setFillOpacity(0.16f);
        dessus.setGState(g);
        ColumnText.showTextAligned(dessus, Element.ALIGN_CENTER,
                new Phrase(texte, police(BaseFont.HELVETICA_BOLD, 96, new Color(0xD6, 0x45, 0x45))), 297, 420, 35);
        dessus.restoreState();
    }

    private static String fcfa(long n) {
        return String.format(Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ') + " FCFA";
    }

    /** « d'Assi », « d'Ehui » : élision devant une voyelle ou un h muet. */
    private static boolean elision(String nom) {
        return !nom.isEmpty() && "AEIOUYÂÉÈÊÎÔÛaeiouyâéèêîôûHh".indexOf(nom.charAt(0)) >= 0;
    }

    private static String capitale(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String texte(JsonNode n, String champ) {
        return n.hasNonNull(champ) ? n.get(champ).asText() : "-";
    }
}
