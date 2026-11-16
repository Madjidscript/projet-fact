package com.eburtis.fact.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import com.eburtis.fact.utils.Pdf;
import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

/**
 * Exporte des tableaux de données en Excel (une feuille par tableau) et en PDF (un document
 * paysage), comme le demande le § 4.8 : « chaque tableau de bord est exportable en PDF et en Excel ».
 */
@Service
public class ExportService {

    /** La façon d'afficher une colonne. */
    public enum Format { TEXTE, ENTIER, FCFA, TAUX, JOURS, MOIS, DATE }

    public record Colonne(String cle, String libelle, Format format) {
    }

    /** Un tableau : son titre, ses colonnes et ses lignes (un tableau JSON d'objets). */
    public record Tableau(String titre, List<Colonne> colonnes, JsonNode lignes) {
    }

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MOIS = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE);
    private static final DateTimeFormatter EDITION = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'mm");

    public byte[] excel(String titre, String sousTitre, List<Tableau> tableaux) {
        try (XSSFWorkbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            CellStyle entete = classeur.createCellStyle();
            XSSFFont gras = classeur.createFont();
            gras.setBold(true);
            entete.setFont(gras);
            entete.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            entete.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle titreStyle = classeur.createCellStyle();
            XSSFFont grand = classeur.createFont();
            grand.setBold(true);
            grand.setFontHeightInPoints((short) 13);
            titreStyle.setFont(grand);
            CellStyle montant = classeur.createCellStyle();
            montant.setDataFormat(classeur.createDataFormat().getFormat("# ##0"));
            CellStyle taux = classeur.createCellStyle();
            taux.setDataFormat(classeur.createDataFormat().getFormat("0.0"));

            for (Tableau t : tableaux) {
                Sheet f = classeur.createSheet(nomFeuille(classeur, t.titre()));
                Row r0 = f.createRow(0);
                Cell c0 = r0.createCell(0);
                c0.setCellValue(t.titre());
                c0.setCellStyle(titreStyle);
                f.createRow(1).createCell(0).setCellValue(titre + " · " + sousTitre);
                Row r = f.createRow(3);
                for (int j = 0; j < t.colonnes().size(); j++) {
                    Colonne col = t.colonnes().get(j);
                    Cell c = r.createCell(j);
                    c.setCellValue(col.libelle() + suffixe(col.format()));
                    c.setCellStyle(entete);
                }
                int i = 4;
                for (JsonNode l : t.lignes()) {
                    Row ligne = f.createRow(i++);
                    for (int j = 0; j < t.colonnes().size(); j++) {
                        Colonne col = t.colonnes().get(j);
                        JsonNode v = l.get(col.cle());
                        Cell c = ligne.createCell(j);
                        if (v == null || v.isNull()) {
                            continue;
                        }
                        switch (col.format()) {
                            case ENTIER, FCFA, JOURS -> {
                                c.setCellValue(v.asDouble());
                                c.setCellStyle(montant);
                            }
                            case TAUX -> {
                                c.setCellValue(v.asDouble());
                                c.setCellStyle(taux);
                            }
                            default -> c.setCellValue(texte(v, col.format()));
                        }
                    }
                }
                for (int j = 0; j < t.colonnes().size(); j++) {
                    f.autoSizeColumn(j);
                }
            }
            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] pdf(String titre, String sousTitre, List<Tableau> tableaux) {
        try (ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4.rotate(), 40, 40, 36, 44);
            PdfWriter ecrivain = PdfWriter.getInstance(doc, sortie);
            ecrivain.setPageEvent(Pdf.pied("FACT Immobilier · " + titre + " · édité le " + EDITION.format(LocalDateTime.now())));
            doc.addTitle(titre);
            doc.addAuthor("FACT Immobilier");
            doc.open();
            Pdf.entete(doc, titre, sousTitre);
            for (Tableau t : tableaux) {
                if (t.lignes() == null || t.lignes().isEmpty()) {
                    doc.add(Pdf.section(t.titre()));
                    doc.add(new Paragraph("Pas encore de données sur cette période.", Pdf.discret(9)));
                    continue;
                }
                PdfPTable tab = new PdfPTable(t.colonnes().size());
                tab.setWidthPercentage(100);
                tab.setSpacingBefore(4);
                // Le titre est la première ligne d'en-tête : il ne reste jamais seul en bas de page
                // et se répète sur la page suivante quand le tableau continue.
                com.lowagie.text.pdf.PdfPCell titreCell = new com.lowagie.text.pdf.PdfPCell(Pdf.section(t.titre()));
                titreCell.setColspan(t.colonnes().size());
                titreCell.setBorder(0);
                titreCell.setPaddingBottom(6);
                titreCell.setPaddingLeft(0);
                tab.addCell(titreCell);
                tab.setHeaderRows(2);
                float[] largeurs = new float[t.colonnes().size()];
                for (int j = 0; j < largeurs.length; j++) {
                    Format f = t.colonnes().get(j).format();
                    largeurs[j] = f == Format.TEXTE ? 2.4f : f == Format.FCFA ? 1.6f : 1f;
                }
                tab.setWidths(largeurs);
                for (Colonne col : t.colonnes()) {
                    tab.addCell(Pdf.titreColonne(col.libelle(), alignement(col.format())));
                }
                for (JsonNode l : t.lignes()) {
                    for (Colonne col : t.colonnes()) {
                        JsonNode v = l.get(col.cle());
                        tab.addCell(Pdf.donnee(v == null || v.isNull() ? "-" : texte(v, col.format()), alignement(col.format()), false));
                    }
                }
                doc.add(tab);
            }
            doc.close();
            return sortie.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** La valeur telle qu'on la lit : « 1 250 000 FCFA », « 87,5 % », « 12 j », « mars 2026 ». */
    public static String texte(JsonNode v, Format f) {
        return switch (f) {
            case ENTIER -> Pdf.entier(v.asLong());
            case FCFA -> Pdf.fcfa(v.asLong());
            case TAUX -> String.format(Locale.FRANCE, "%.1f %%", v.asDouble());
            case JOURS -> String.format(Locale.FRANCE, "%s j", v.asDouble() == Math.floor(v.asDouble())
                    ? String.valueOf(v.asLong()) : String.format(Locale.FRANCE, "%.1f", v.asDouble()));
            case MOIS -> {
                String m = MOIS.format(LocalDate.parse(v.asText().substring(0, 10)));
                yield Character.toUpperCase(m.charAt(0)) + m.substring(1);
            }
            case DATE -> JOUR.format(LocalDate.parse(v.asText().substring(0, 10)));
            case TEXTE -> v.asText();
        };
    }

    private static int alignement(Format f) {
        return f == Format.TEXTE || f == Format.MOIS || f == Format.DATE ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT;
    }

    private static String suffixe(Format f) {
        return switch (f) {
            case FCFA -> " (FCFA)";
            case TAUX -> " (%)";
            case JOURS -> " (jours)";
            default -> "";
        };
    }

    /** Nom de feuille Excel : 31 caractères au plus, sans caractère interdit, unique. */
    private static String nomFeuille(XSSFWorkbook c, String titre) {
        String base = titre.replaceAll("[\\\\/?*\\[\\]:]", " ");
        base = base.length() > 28 ? base.substring(0, 28) : base;
        String nom = base;
        for (int n = 2; c.getSheet(nom) != null; n++) {
            nom = base + " " + n;
        }
        return nom;
    }
}
