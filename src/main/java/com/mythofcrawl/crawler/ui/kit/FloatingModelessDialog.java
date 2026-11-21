package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base non-modal floating dialog supporting resize/move memory by {@code windowId},
 * Always-on-Top pin toggle, ESC close, and guaranteed {@link #onDispose()} cleanup (LAW 1–4 compliant).
 */
public class FloatingModelessDialog extends JDialog {

    private static final Map<String, Rectangle> BOUNDS_MEMORY = new ConcurrentHashMap<>();

    private final String windowId;
    private final JPanel rootContainer = new JPanel(new BorderLayout(0, 0));
    private final JPanel topBar = new JPanel(new BorderLayout(Theme.SPACE_2, 0));
    private final JLabel lblHeading = new JLabel();
    private final Ui.UiButton btnPin;
    private final JPanel bodySlot = new JPanel(new BorderLayout());
    private AutoCloseable themeSub;
    private boolean disposedHooksRun = false;

    public FloatingModelessDialog(Window owner, String windowId, String title, int defaultWidth, int defaultHeight) {
        super(owner instanceof Frame ? (Frame) owner : null, title != null ? title : "", false);
        this.windowId = (windowId != null && !windowId.trim().isEmpty()) ? windowId.trim() : "floating";
        this.lblHeading.setText(title != null ? title : "");

        this.btnPin = Ui.tonalBtn(Theme.Token.NEUTRAL, "Ghim", VectorIcons.pin(Theme.muted()));
        this.btnPin.setToolTipText("Luôn hiển thị trên cùng (Always on top)");
        this.btnPin.addActionListener(e -> {
            boolean next = !isAlwaysOnTop();
            setAlwaysOnTop(next);
            btnPin.setSelectedVisual(next);
            btnPin.setToken(next ? Theme.Token.ACCENT : Theme.Token.NEUTRAL);
            btnPin.setLabelAndIcon(next ? "Đang ghim" : "Ghim",
                VectorIcons.pin(next ? Theme.token(Theme.Token.ACCENT).text() : Theme.muted()));
        });

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        topBar.setOpaque(true);
        topBar.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_3, Theme.SPACE_2, Theme.SPACE_3));
        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 0));
        rightActions.setOpaque(false);
        rightActions.add(btnPin);
        topBar.add(lblHeading, BorderLayout.WEST);
        topBar.add(rightActions, BorderLayout.EAST);

        JPanel northWrap = new JPanel(new BorderLayout(0, 0));
        northWrap.setOpaque(false);
        northWrap.add(topBar, BorderLayout.CENTER);
        northWrap.add(Ui.hairline(true), BorderLayout.SOUTH);

        bodySlot.setOpaque(false);
        rootContainer.add(northWrap, BorderLayout.NORTH);
        rootContainer.add(bodySlot, BorderLayout.CENTER);
        setContentPane(rootContainer);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escClose");
        getRootPane().getActionMap().put("escClose", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                rememberBounds();
                runDisposeOnce();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                rememberBounds();
            }
        });

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                rememberBounds();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                rememberBounds();
            }
        });

        Rectangle saved = BOUNDS_MEMORY.get(this.windowId);
        if (saved != null && saved.width >= 260 && saved.height >= 180) {
            setBounds(saved);
        } else {
            setSize(new Dimension(Math.max(360, defaultWidth), Math.max(240, defaultHeight)));
            setLocationRelativeTo(owner);
        }

        themeSub = Theme.onThemeChange(this::applyTheme);
        applyTheme();
    }

    public final void setBodyComponent(JComponent comp) {
        bodySlot.removeAll();
        if (comp != null) {
            bodySlot.add(comp, BorderLayout.CENTER);
        }
        bodySlot.revalidate();
        bodySlot.repaint();
    }

    public final void setHeadingText(String text) {
        lblHeading.setText(text != null ? text : "");
        setTitle(text != null ? text : "");
    }

    private void rememberBounds() {
        if (isDisplayable() && getWidth() >= 260 && getHeight() >= 180) {
            BOUNDS_MEMORY.put(windowId, getBounds());
        }
    }

    private void runDisposeOnce() {
        if (disposedHooksRun) {
            return;
        }
        disposedHooksRun = true;
        if (themeSub != null) {
            try {
                themeSub.close();
            } catch (Exception ignored) {
            }
            themeSub = null;
        }
        onDispose();
    }

    @Override
    public void dispose() {
        rememberBounds();
        runDisposeOnce();
        super.dispose();
    }

    /**
     * Subclasses override to unsubscribe InstantEventBus or timer listeners cleanly.
     */
    protected void onDispose() {
    }

    public void applyTheme() {
        rootContainer.setBackground(Theme.canvas());
        topBar.setBackground(Theme.surface());
        lblHeading.setFont(FontManager.uiTitle());
        lblHeading.setForeground(Theme.text());
        repaint();
    }
}
