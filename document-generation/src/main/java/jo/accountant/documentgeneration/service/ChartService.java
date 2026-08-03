package jo.accountant.documentgeneration.service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PiePlot;
import org.jfree.chart.plot.Plot;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.data.general.DefaultPieDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fix PDF v9.4 — Service de génération de graphiques pour les rapports PDF.
 *
 * <p>Utilise JFreeChart (licence LGPL) pour générer des graphiques PNG encodés en base64,
 * directement embarqués dans les templates HTML via {@code data:image/png;base64,...}.
 *
 * <p>Cas d'usage :
 * <ul>
 *   <li><b>Bilan</b> : camembert répartition Actif / Passif / Capitaux propres</li>
 *   <li><b>Compte de résultat</b> : barres Produits vs Charges</li>
 *   <li><b>Balance âgée</b> : barres par tranche (0-30, 31-60, 61-90, 90+)</li>
 *   <li><b>Déclaration TVA</b> : camembert TVA collectée vs déductible vs due</li>
 * </ul>
 *
 * <p>Palette "Corporate sobre" : bleu marine (#1a3a5c), gris (#6c757d), accent (#0d6efd).
 * Fond transparent pour s'intégrer au PDF.
 *
 * @author jo@Dev
 */
@Service
public class ChartService {

    private static final Logger LOG = LoggerFactory.getLogger(ChartService.class);

    // Palette "Corporate sobre" (alignée avec le CSS PDF)
    private static final Color NAVY = new Color(0x1a, 0x3a, 0x5c);
    private static final Color GRAY = new Color(0x6c, 0x75, 0x7d);
    private static final Color BLUE = new Color(0x0d, 0x6e, 0xfd);
    private static final Color LIGHT_GRAY = new Color(0xe9, 0xec, 0xef);
    private static final Color WHITE = Color.WHITE;

    /**
     * Génère un camembert (pie chart) en PNG base64.
     *
     * @param title titre du graphique (null = pas de titre)
     * @param data map {label → value} (ex: {"Actif" → 16500, "Passif" → 1500, "Capitaux propres" → 10000})
     * @param width largeur en px (ex: 400)
     * @param height hauteur en px (ex: 300)
     * @return le PNG encodé en base64, ou null si échec
     */
    public String generatePieChartBase64(String title, Map<String, ? extends Number> data,
                                          int width, int height) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            DefaultPieDataset<String> dataset = new DefaultPieDataset<>();
            data.forEach((label, value) -> dataset.setValue(label, value));

            JFreeChart chart = ChartFactory.createPieChart(
                title, dataset, false, false, false);

            // Style "Corporate sobre"
            chart.setBackgroundPaint(WHITE);
            chart.setPadding(RectangleInsets.ZERO_INSETS);
            if (chart.getTitle() != null) {
                chart.getTitle().setFont(new Font("Helvetica", Font.BOLD, 12));
                chart.getTitle().setPaint(NAVY);
            }

            PiePlot<String> plot = (PiePlot<String>) chart.getPlot();
            plot.setBackgroundPaint(WHITE);
            plot.setOutlineVisible(false);
            plot.setLabelFont(new Font("Helvetica", Font.PLAIN, 9));
            plot.setLabelBackgroundPaint(LIGHT_GRAY);
            plot.setLabelOutlinePaint(null);
            plot.setLabelShadowPaint(null);
            plot.setShadowPaint(null);
            plot.setInteriorGap(0.05);
            plot.setLabelGap(0.02);

            // Couleurs alternées navy / gray / blue
            Color[] palette = {NAVY, GRAY, BLUE, new Color(0x49, 0x57, 0x69), new Color(0x8a, 0x94, 0xa6)};
            int i = 0;
            for (String key : data.keySet()) {
                plot.setSectionPaint(key, palette[i % palette.length]);
                i++;
            }

            return renderChartBase64(chart, width, height);
        } catch (Exception e) {
            LOG.warn("Échec génération pie chart '{}' : {}", title, e.getMessage());
            return null;
        }
    }

    /**
     * Génère un graphique en barres horizontales simple (pour balances âgées, répartition charges).
     *
     * <p>Pour rester pragmatique, on génère un PNG manuellement (sans JFreeChart BarChart
     * qui nécessite CategoryDataset) — c'est suffisant pour des barres simples.
     *
     * @param title titre (null = pas de titre)
     * @param labels labels des barres (ex: ["0-30j", "31-60j", "61-90j", "90+j"])
     * @param values valeurs correspondantes (ex: [5000, 3000, 1500, 500])
     * @param width largeur px
     * @param height hauteur px
     * @return PNG base64, ou null si échec
     */
    public String generateBarChartBase64(String title, List<String> labels, List<? extends Number> values,
                                          int width, int height) {
        if (labels == null || values == null || labels.isEmpty() || labels.size() != values.size()) {
            return null;
        }
        try {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

            // Fond blanc
            g.setColor(WHITE);
            g.fillRect(0, 0, width, height);

            // Titre
            int yOffset = 0;
            if (title != null && !title.isBlank()) {
                g.setColor(NAVY);
                g.setFont(new Font("Helvetica", Font.BOLD, 12));
                g.drawString(title, 10, 18);
                yOffset = 30;
            }

            int chartHeight = height - yOffset - 20;
            int barHeight = Math.max(12, chartHeight / labels.size() - 6);
            int labelWidth = 80;
            int barAreaWidth = width - labelWidth - 60;

            double maxValue = values.stream().mapToDouble(Number::doubleValue).max().orElse(1);
            if (maxValue == 0) maxValue = 1;

            for (int i = 0; i < labels.size(); i++) {
                int y = yOffset + 10 + i * (barHeight + 6);
                double value = values.get(i).doubleValue();
                int barWidth = (int) ((value / maxValue) * barAreaWidth);

                // Label
                g.setColor(GRAY);
                g.setFont(new Font("Helvetica", Font.PLAIN, 10));
                g.drawString(labels.get(i), 5, y + barHeight - 2);

                // Barre
                g.setColor(NAVY);
                g.fillRect(labelWidth, y, barWidth, barHeight);

                // Valeur
                g.setColor(NAVY);
                g.setFont(new Font("Helvetica", Font.BOLD, 10));
                String valStr = formatNumber(value);
                g.drawString(valStr, labelWidth + barWidth + 5, y + barHeight - 2);
            }

            g.dispose();
            return writeImageBase64(img);
        } catch (Exception e) {
            LOG.warn("Échec génération bar chart '{}' : {}", title, e.getMessage());
            return null;
        }
    }

    private String renderChartBase64(JFreeChart chart, int width, int height) throws Exception {
        BufferedImage img = chart.createBufferedImage(width, height);
        return writeImageBase64(img);
    }

    private String writeImageBase64(BufferedImage img) throws Exception {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            ImageIO.write(img, "PNG", os);
            return Base64.getEncoder().encodeToString(os.toByteArray());
        }
    }

    private String formatNumber(double value) {
        if (value == (long) value) {
            return String.format("%,d", (long) value);
        }
        return String.format("%,.2f", value);
    }
}
