package com.mythofcrawl.crawler.ui.dialogs;

import com.mythofcrawl.crawler.core.guardrail.ProxyInterceptQueue;
import com.mythofcrawl.crawler.core.sync.InstantEventBus;
import com.mythofcrawl.crawler.core.tree.NodeTypeCatalog;
import com.mythofcrawl.crawler.model.ActionGroup;
import com.mythofcrawl.crawler.model.CrawlState;
import com.mythofcrawl.crawler.ui.kit.FloatingModelessDialog;
import com.mythofcrawl.crawler.ui.kit.SearchField;
import com.mythofcrawl.crawler.ui.kit.ThemedTree;
import com.mythofcrawl.crawler.ui.theme.Theme;
import com.mythofcrawl.crawler.ui.theme.Ui;
import com.mythofcrawl.crawler.ui.theme.VectorIcons;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Detachable floating tree explorer for 14-inch & split-screen dual-monitor workflows.
 */
public final class DetachedTreeDialog extends FloatingModelessDialog {

    private final CrawlState state;
    private final ProxyInterceptQueue interceptQueue;
    private final Consumer<String> onSelectBranch;

    private final SearchField searchField = new SearchField("Lọc nhanh nhánh / API...");
    private final Ui.UiButton btnRefresh;
    private final Ui.UiButton btnExpandAll;
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(
        new ThemedTree.NodeWrapper(
            ThemedTree.NodeKind.ROOT, "root", "Target Root (/)", "",
            NodeTypeCatalog.SCREEN.iconKey, "0", Theme.Token.ACCENT, 0, null));
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final ThemedTree tree = new ThemedTree(treeModel);
    private final JScrollPane spTree = new JScrollPane(tree);

    private InstantEventBus.Subscription queueSub;

    public DetachedTreeDialog(
        Window owner,
        CrawlState state,
        ProxyInterceptQueue interceptQueue,
        Consumer<String> onSelectBranch
    ) {
        super(owner, "detached_tree_dialog", "Cây làm việc (Tách cửa sổ)", 420, 620);
        this.state = state;
        this.interceptQueue = interceptQueue;
        this.onSelectBranch = onSelectBranch;

        this.btnRefresh = Ui.iconBtn(VectorIcons.refresh(Theme.text()), "Làm mới cây");
        this.btnExpandAll = Ui.iconBtn(VectorIcons.expand(Theme.text()), "Mở hết nhánh");

        JPanel content = new JPanel(new BorderLayout(0, Theme.SPACE_2));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2, Theme.SPACE_2));

        JPanel top = new JPanel(new BorderLayout(Theme.SPACE_1, 0));
        top.setOpaque(false);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_1, 0));
        btns.setOpaque(false);
        btns.add(btnExpandAll);
        btns.add(btnRefresh);
        top.add(searchField, BorderLayout.CENTER);
        top.add(btns, BorderLayout.EAST);

        spTree.setBorder(BorderFactory.createLineBorder(Theme.border(), 1));
        spTree.getViewport().setBackground(Theme.surface());

        content.add(top, BorderLayout.NORTH);
        content.add(spTree, BorderLayout.CENTER);
        setBodyComponent(content);

        searchField.onSearchChange(q -> rebuild());
        btnRefresh.addActionListener(e -> rebuild());
        btnExpandAll.addActionListener(e -> {
            for (int i = 0; i < tree.getRowCount(); i++) {
                tree.expandRow(i);
            }
        });
        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            ThemedTree.NodeWrapper w = ThemedTree.NodeWrapper.unwrap(node);
            if (w != null && onSelectBranch != null && !w.id.isEmpty()) {
                onSelectBranch.accept("root".equals(w.id) ? "" : w.id);
            }
        });

        if (interceptQueue != null) {
            queueSub = interceptQueue.subscribe(ev -> SwingUtilities.invokeLater(this::rebuild));
        }
        rebuild();
    }

    public void rebuild() {
        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
        rootNode.removeAllChildren();
        Map<String, DefaultMutableTreeNode> branchMap = new LinkedHashMap<>();

        if (interceptQueue != null) {
            for (ProxyInterceptQueue.PendingInterceptItem p : interceptQueue.snapshotPending()) {
                if (!q.isEmpty() && !p.path.toLowerCase(Locale.ROOT).contains(q)
                    && !p.branchPath.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                DefaultMutableTreeNode parent = ensureBranch(p.branchPath, branchMap);
                parent.add(new DefaultMutableTreeNode(
                    new ThemedTree.NodeWrapper(
                        ThemedTree.NodeKind.HELD_PENDING,
                        p.id,
                        "[CHỜ DUYỆT] " + p.method + " " + p.path,
                        p.host,
                        "HELD_PENDING",
                        "CHỜ DUYỆT",
                        Theme.Token.PENDING,
                        1,
                        p
                    )));
            }
        }
        if (state != null) {
            for (ActionGroup g : state.getActionGroups().snapshotGroups()) {
                if (!q.isEmpty() && !g.actionPath.toLowerCase(Locale.ROOT).contains(q)
                    && !g.label.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                DefaultMutableTreeNode parent = ensureBranch(g.actionPath, branchMap);
                parent.add(new DefaultMutableTreeNode(
                    new ThemedTree.NodeWrapper(
                        ThemedTree.NodeKind.API_GROUP,
                        g.actionPath,
                        g.label.isEmpty() ? g.actionPath : g.label,
                        g.callCount() + " API",
                        NodeTypeCatalog.API.iconKey,
                        g.callCount() > 0 ? String.valueOf(g.callCount()) : "",
                        Theme.Token.INFO,
                        1,
                        g
                    )));
            }
        }
        treeModel.reload();
        tree.expandPath(new TreePath(rootNode.getPath()));
    }

    private DefaultMutableTreeNode ensureBranch(String rawPath, Map<String, DefaultMutableTreeNode> map) {
        String clean = (rawPath == null || rawPath.trim().isEmpty()) ? "/" : rawPath.trim();
        String[] segs = clean.split("\\s*>\\s*");
        DefaultMutableTreeNode cur = rootNode;
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            String s = segs[i].trim();
            if (s.isEmpty()) continue;
            if (acc.length() > 0) acc.append(" > ");
            acc.append(s);
            final String key = acc.toString();
            final int depth = i;
            final DefaultMutableTreeNode parentRef = cur;
            cur = map.computeIfAbsent(key, k -> {
                DefaultMutableTreeNode n = new DefaultMutableTreeNode(
                    new ThemedTree.NodeWrapper(
                        ThemedTree.NodeKind.BRANCH,
                        key,
                        s,
                        "",
                        NodeTypeCatalog.SCREEN.iconKey,
                        "",
                        Theme.forDepthToken(depth),
                        depth,
                        null
                    ));
                parentRef.add(n);
                return n;
            });
        }
        return cur;
    }

    @Override
    protected void onDispose() {
        if (queueSub != null) {
            try {
                queueSub.close();
            } catch (Exception ignored) {
            }
            queueSub = null;
        }
    }
}
