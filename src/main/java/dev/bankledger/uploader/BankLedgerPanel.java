package dev.bankledger.uploader;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The sidebar panel: an Upload now button, how the last upload went, and a link to the
 * dashboard. It replaces ticking "Upload now" in the settings, which RuneLite's config panel
 * forces to be a checkbox.
 */
class BankLedgerPanel extends PluginPanel {
  private final JLabel status = new JLabel();

  BankLedgerPanel(Runnable onUpload, Runnable onOpenDashboard) {
    setLayout(new BorderLayout());
    setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    JPanel body = new JPanel();
    body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
    body.setBackground(ColorScheme.DARK_GRAY_COLOR);

    JLabel title = new JLabel("Bank Ledger");
    title.setFont(FontManager.getRunescapeBoldFont());
    title.setForeground(Color.WHITE);

    JButton upload = new JButton("Upload now");
    upload.setToolTipText("Send your bank to Bank Ledger right away, skipping the cooldown");
    upload.addActionListener(e -> onUpload.run());

    JButton open = new JButton("Open my dashboard");
    open.addActionListener(e -> onOpenDashboard.run());

    status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
    status.setFont(FontManager.getRunescapeSmallFont());
    setStatus("No upload yet this session.");

    for (Component c : new Component[] {title, upload, open, status}) {
      ((javax.swing.JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
      if (c instanceof JButton) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
      }
      body.add(c);
      body.add(Box.createVerticalStrut(8));
    }
    add(body, BorderLayout.NORTH);
  }

  /** Any thread. */
  void setStatus(String text) {
    String html = "<html>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</html>";
    SwingUtilities.invokeLater(() -> status.setText(html));
  }

  /** The sidebar icon, drawn here so the plugin ships no image files: a gold coin with a "B". */
  static BufferedImage icon() {
    BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(new Color(0xD4A73A));
    g.fillOval(1, 1, 14, 14);
    g.setColor(new Color(0x8A6414));
    g.setStroke(new BasicStroke(1.2f));
    g.drawOval(1, 1, 14, 14);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
    g.drawString("B", 5, 12);
    g.dispose();
    return img;
  }
}
