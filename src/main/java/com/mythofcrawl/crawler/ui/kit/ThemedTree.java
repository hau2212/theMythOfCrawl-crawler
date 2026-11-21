package com.mythofcrawl.crawler.ui.kit;

import com.mythofcrawl.crawler.ui.theme.FontManager;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreeModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.LayoutManager;

/**
 * Polymorphic {@link JTree} enforcing {@code ROW_TREE = 24} and clean {@code switch (kind)}
 * rendering over {@link NodeWrapper} — NEVER hard-casting node payloads (LAW 1–4 compliant).
 */
public class ThemedTree extends JTree {

    public enum NodeKind {
        ROOT,
        BRANCH,
        HELD_PENDING,
        API_GROUP,
        AGENT_ACTION,
        CLUSTER,
        GHOSTS
    }

    public static final class NodeWrapper {
        public final NodeKind kind;
        public final String id;
        public final String title;
        public final String subtitle;
        public final String nodeTypeKey;
        public final String badgeText;
        public final Theme.Token badgeToken;
        public final int depth;
        public final Object payload;

        public NodeWrapper(
            NodeKind kind,
            String id,
            String title,
            String subtitle,
            String nodeTypeKey,
            String badgeText,
            Theme.Token badgeToken,
            int depth,
            Object payload
        ) {
            this.kind = kind != null ? kind : NodeKind.BRANCH;
            this.id = id != null ? id : "";
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.nodeTypeKey = nodeTypeKey != null ? nodeTypeKey : "";
            this.badgeText = badgeText != null ? badgeText : "";
            this.badgeToken = badgeToken != null ? badgeToken : Theme.Token.NEUTRAL;
            this.depth = Math.max(0, depth);
            this.payload = payload;
        }

        public NodeKind kind() {
            return kind;
        }

        public static NodeWrapper unwrap(Object treeNodeOrObj) {
            Object cur = treeNodeOrObj;
            if (cur instanceof DefaultMutableTreeNode) {
                cur = ((DefaultMutableTreeNode) cur).getUserObject();
            }
            return cur instanceof NodeWrapper ? (NodeWrapper) cur : null;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private AutoCloseable themeSub;

    public ThemedTree() {
        super();
        initTree();
    }

    public ThemedTree(TreeModel model) {
        super(model);
        initTree();
    }

    private void initTree() {
        setRowHeight(Theme.ROW_TREE);
        setShowsRootHandles(true);
        setCellRenderer(new PolymorphicNodeRenderer());
        applyTheme();
    }

    public void applyTheme() {
        setFont(FontManager.uiRegular());
        setBackground(Theme.surface());
        setForeground(Theme.text());
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeSub == null) {
            themeSub = Theme.onThemeChange(this::applyTheme);
        }
    }

    @Override
    public void removeNotify() {
        if (themeSub != null) {
            try {
                themeSub.close();
            } catch (Exception ignored) {
            }
            themeSub = null;
        }
        super.removeNotify();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        applyTheme();
    }

    @Override
    protected void paintComponent(java.awt.Graphics g) {
        setBackground(Theme.surface());
        setForeground(Theme.text());
        super.paintComponent(g);
    }

    private static final class PolymorphicNodeRenderer extends JPanel implements TreeCellRenderer {
        private final JLabel lblIcon = new JLabel();
        private final JLabel lblTitle = new JLabel();
        private final JLabel lblSub = new JLabel();
        private final Pill badge = new Pill("", Theme.Token.NEUTRAL, false);

        PolymorphicNodeRenderer() {
            super();
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(1, Theme.SPACE_1, 1, Theme.SPACE_2));
            lblIcon.setAlignmentY(0.5f);
            lblTitle.setAlignmentY(0.5f);
            lblSub.setAlignmentY(0.5f);
            badge.setAlignmentY(0.5f);
            add(lblIcon);
            add(javax.swing.Box.createHorizontalStrut(Theme.SPACE_1));
            add(lblTitle);
            add(lblSub);
            add(javax.swing.Box.createHorizontalStrut(Theme.SPACE_1));
            add(badge);
        }

        @Override
        public Component getTreeCellRendererComponent(
            JTree tree,
            Object value,
            boolean selected,
            boolean expanded,
            boolean leaf,
            int row,
            boolean hasFocus
        ) {
            NodeWrapper w = NodeWrapper.unwrap(value);
            setBackground(selected ? Theme.selection() : Theme.surface());
            Color titleFg = selected ? Theme.selectionText() : Theme.text();
            Color subFg = selected ? Theme.selectionText() : Theme.muted();

            if (w == null) {
                lblIcon.setIcon(expanded ? VectorIcons.folderOpen(subFg) : VectorIcons.folder(subFg));
                lblTitle.setFont(FontManager.uiRegular());
                lblTitle.setForeground(titleFg);
                lblTitle.setText(value != null ? String.valueOf(value) : "");
                lblSub.setVisible(false);
                badge.setVisible(false);
                return this;
            }

            Icon nodeIcon;
            switch (w.kind) {
                case ROOT:
                    nodeIcon = VectorIcons.globe(Theme.token(Theme.Token.ACCENT).text());
                    lblTitle.setFont(FontManager.uiSemiBold());
                    break;
                case HELD_PENDING:
                    nodeIcon = VectorIcons.shieldAlert(Theme.token(Theme.Token.PENDING).text());
                    lblTitle.setFont(FontManager.uiSemiBold());
                    titleFg = selected ? Theme.selectionText() : Theme.token(Theme.Token.PENDING).text();
                    break;
                case API_GROUP:
                    nodeIcon = VectorIcons.apiNode(Theme.token(Theme.Token.INFO).text());
                    lblTitle.setFont(FontManager.uiMedium());
                    break;
                case AGENT_ACTION:
                    nodeIcon = VectorIcons.forNodeType(w.nodeTypeKey, Theme.token(Theme.forDepthToken(w.depth)).text());
                    lblTitle.setFont(FontManager.uiRegular());
                    break;
                case CLUSTER:
                    nodeIcon = expanded ? VectorIcons.folderOpen(Theme.token(Theme.Token.DUP).text())
                        : VectorIcons.folder(Theme.token(Theme.Token.DUP).text());
                    lblTitle.setFont(FontManager.uiMedium());
                    break;
                case GHOSTS:
                    nodeIcon = VectorIcons.ghostNode(Theme.token(Theme.Token.GHOST).text());
                    lblTitle.setFont(FontManager.uiMedium());
                    titleFg = selected ? Theme.selectionText() : Theme.token(Theme.Token.GHOST).text();
                    break;
                case BRANCH:
                default:
                    Color depthCol = Theme.token(Theme.forDepthToken(w.depth)).text();
                    nodeIcon = w.nodeTypeKey.isEmpty()
                        ? VectorIcons.branch(depthCol)
                        : VectorIcons.forNodeType(w.nodeTypeKey, depthCol);
                    lblTitle.setFont(FontManager.uiMedium());
                    break;
            }

            lblIcon.setIcon(nodeIcon);
            lblTitle.setForeground(titleFg);
            lblTitle.setText(w.title);

            if (!w.subtitle.isEmpty()) {
                lblSub.setVisible(true);
                lblSub.setFont(FontManager.uiSmall());
                lblSub.setForeground(subFg);
                lblSub.setText("  " + w.subtitle);
            } else {
                lblSub.setVisible(false);
            }

            if (!w.badgeText.isEmpty()) {
                badge.setVisible(true);
                badge.setPill(w.badgeText, w.badgeToken, w.kind == NodeKind.HELD_PENDING);
            } else {
                badge.setVisible(false);
            }
            invalidate();
            return this;
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            LayoutManager lm = getLayout();
            if (lm instanceof javax.swing.BoxLayout) {
                ((javax.swing.BoxLayout) lm).invalidateLayout(this);
            }
            return super.getPreferredSize();
        }

        @Override
        public void paint(java.awt.Graphics g) {
            LayoutManager lm = getLayout();
            if (lm instanceof javax.swing.BoxLayout) {
                ((javax.swing.BoxLayout) lm).invalidateLayout(this);
            }
            doLayout();
            super.paint(g);
        }
    }
}
